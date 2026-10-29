#!/bin/bash
# ============================================
# LifeControl - Environment Validation Script
# ============================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/_common.sh"

# Arguments: [env] [--strict-pins]. The environment stays the first positional argument it always
# was, and `resolve_compose_env "${1:-dev}"` ignored anything after it -- that contract is preserved,
# including for `-h`/`--help`, which this script has never accepted: they resolve as an invalid
# environment here exactly as they did before this unit. `--strict-pins` is the only new token.
ENV_ARG=""
STRICT_PINS=0
env_taken=0
for arg in "$@"; do
	case "$arg" in
	--strict-pins) STRICT_PINS=1 ;;
	*)
		if [ "$env_taken" -eq 0 ]; then
			ENV_ARG="$arg"
		fi
		env_taken=1
		;;
	esac
done

resolve_compose_env "${ENV_ARG:-dev}"

ERRORS=0

check_env_file() {
	print_status "Checking environment file..."
	if [ -f "$ENV_FILE" ]; then
		print_success "$ENV_FILE found"
		return 0
	else
		print_error "$ENV_FILE not found!"
		print_status "Run: ./docker/scripts/setup-env.sh $ENV"
		return 1
	fi
}

check_required_vars() {
	print_status "Checking required variables..."

	REQUIRED_VARS=(
		"COMPOSE_PROJECT_NAME"
		"ENVIRONMENT"
		"API_GATEWAY_MANAGEMENT_PORT"
		"LIFECONTROL_API_PORT"
	)

	for var in "${REQUIRED_VARS[@]}"; do
		if grep -q "^${var}=" "$ENV_FILE"; then
			VALUE=$(grep "^${var}=" "$ENV_FILE" | cut -d'=' -f2-)
			if [ -z "$VALUE" ] || [[ "$VALUE" == *"CHANGEME"* ]]; then
				print_warning "$var is not set or uses default value"
			else
				print_success "$var is set"
			fi
		else
			print_error "$var is missing"
			ERRORS=$((ERRORS + 1))
		fi
	done
}

check_declared_vars() {
	print_status "Checking keys declared in .env.template..."
	# Report only, never a gate: a key absent from the env file is legal, because compose
	# supplies its default. deploy.sh runs this script as the production start gate and
	# aborts the deploy when it exits non-zero, so a missing key must never be able to stop
	# a production start. The report is also read-only: it never rewrites the env file.
	report_missing_env_keys "$ENV_FILE" "$DOCKER_DIR/.env.template"
}

check_image_pins() {
	print_status "Checking image pins against .env.template..."
	# Report only, and the strict decision deliberately does NOT live here: main turns a divergent
	# count into a failure only when the caller passed --strict-pins. This function therefore never
	# adds to ERRORS, which is what keeps the production gate from failing on drift alone.
	report_stale_image_pins "$ENV_FILE" "$DOCKER_DIR/.env.template"
}

check_docker() {
	print_status "Checking Docker..."
	if docker info >/dev/null 2>&1; then
		print_success "Docker is running"
	else
		print_error "Docker is not running"
		ERRORS=$((ERRORS + 1))
	fi
}

check_ports() {
	print_status "Checking ports..."

	PORTS=(
		"KEYCLOAK_PORT"
		"API_GATEWAY_PORT"
		"API_GATEWAY_MANAGEMENT_PORT"
		"LIFECONTROL_API_PORT"
	)

	for port_var in "${PORTS[@]}"; do
		PORT=$(get_env_var "$port_var")
		if [ -n "$PORT" ]; then
			if netstat -tuln 2>/dev/null | grep -q ":$PORT " || ss -tuln 2>/dev/null | grep -q ":$PORT "; then
				print_warning "$port_var ($PORT) is already in use"
			else
				print_success "$port_var ($PORT) is available"
			fi
		fi
	done
}

check_secret_files() {
	print_status "Checking docker/secrets files..."

	SECRETS_DIR="$DOCKER_DIR/secrets"

	if [ ! -d "$SECRETS_DIR" ]; then
		print_error "$SECRETS_DIR not found!"
		print_status "Run: ./docker/scripts/setup-env.sh $ENV"
		ERRORS=$((ERRORS + 1))
		return 1
	fi

	FOUND=0
	for secret_file in "$SECRETS_DIR"/*; do
		local secret_name
		secret_name="$(basename "$secret_file")"
		# Skip tracked templates and dotfiles; only materialized secrets are gated
		case "$secret_name" in
			*.template | .gitignore) continue ;;
		esac

		# A directory is not a secret and is never read as one. Compose bind-mounts a file per secret
		# and Docker creates an empty directory in place of a bind source that does not exist
		# (docker-compose.yml:354-357 documents exactly that). Reading it went wrong in three
		# measured ways: `test -s` is true for a directory, `grep` answered "Is a directory" instead
		# of "no CHANGEME" -- which made the entry earn a success line it never earned -- and the
		# mode of a directory is never 0444, so the gate failed over a file nothing required.
		if [ -d "$secret_file" ]; then
			if [ -f "$secret_file.template" ]; then
				# The template set is exactly what setup-env.sh materializes, so a directory where a
				# templated secret belongs means that secret was never materialized. This is the case
				# where a directory really is a misconfiguration, and it keeps failing the gate.
				print_error "$secret_file is a directory, but $secret_name.template declares this secret"
				print_status "Run: ./docker/scripts/setup-env.sh $ENV (it materializes every *.template)"
				ERRORS=$((ERRORS + 1))
			else
				# No template declares it, so nothing materializes it and nothing is missing: it is a
				# file created by hand and read only when the feature that needs it is configured.
				print_warning "$secret_file is a directory, not a secret file: Docker creates it in place of a bind-mount source that does not exist"
				print_status "  No .template declares this name, so setup-env.sh never materializes it: it is created by hand and read only when the feature that needs it is configured. Remove it with: rmdir $secret_file"
			fi
			# Never counted as materialized: a folder holding only this directory still says
			# 'No secrets materialized' below, which is the truth.
			continue
		fi

		FOUND=1

		if [ ! -s "$secret_file" ]; then
			print_error "$secret_file is missing or empty"
			ERRORS=$((ERRORS + 1))
			continue
		fi

		if grep -q "CHANGEME" "$secret_file"; then
			print_error "$secret_file still contains CHANGEME — replace with the real value"
			ERRORS=$((ERRORS + 1))
		else
			print_success "$secret_file set"
		fi

		MODE=$(stat -c '%a' "$secret_file" 2>/dev/null || stat -f '%Lp' "$secret_file" 2>/dev/null || echo "?")
		if [ "$MODE" != "444" ] && [ "$MODE" != "0444" ]; then
			print_error "$secret_file mode is $MODE — must be 0444 so container uids (999/1000/472) can read it"
			ERRORS=$((ERRORS + 1))
		else
			print_success "$secret_file mode 0444"
		fi
	done

	if [ "$FOUND" -eq 0 ]; then
		print_error "No secrets materialized in $SECRETS_DIR"
		print_status "Run: ./docker/scripts/setup-env.sh $ENV"
		ERRORS=$((ERRORS + 1))
	fi
}

# The one secret with no `.template`, and the one requirement that is conditional rather than structural.
# `docker/secrets/smtp_password` is created by hand, so its absence is legal -- until the relay is told to
# authenticate. `SMTP_AUTH=true` is what makes it required, and it is read by two other programs that
# refuse without it: keycloak-setup.sh refuses to write the relay when the file is missing, empty or still
# holds CHANGEME (keycloak-setup.sh:262-278), and Grafana's entrypoint exports it as GF_SMTP_PASSWORD only
# when GF_SMTP_USER is non-empty. Without this check the gate reported "nothing is missing" over a
# directory -- or no file at all -- in exactly the configuration that needs one, and let a production
# start proceed to a setup step that would then abort.
check_smtp_credential() {
	if [ "$(get_env_var SMTP_AUTH)" != "true" ]; then
		return 0
	fi

	print_status "Checking the SMTP credential (SMTP_AUTH=true)..."
	local credential="$SECRETS_DIR/smtp_password"

	if [ -f "$credential" ] && [ -s "$credential" ] && ! grep -q "CHANGEME" "$credential"; then
		print_success "$credential set"
		return 0
	fi

	print_error "$credential is required because SMTP_AUTH=true, and it must be a non-empty file without CHANGEME"
	print_status "Create it by hand -- it has no .template on purpose -- or set SMTP_AUTH=false in $ENV_FILE"
	ERRORS=$((ERRORS + 1))
}

check_volume_dirs() {
	print_status "Checking volume directories for $ENV environment..."

	local vol_root
	vol_root=$(get_env_var VOLUMES_ROOT)
	local vol_path=""
	if [ -n "$vol_root" ]; then
		# Support both relative (dev/staging) and absolute (prod) VOLUMES_ROOT values
		case "$vol_root" in
			/*) vol_path="$vol_root" ;;
			*)  vol_path="$DOCKER_DIR/$vol_root" ;;
		esac
	fi
	if [ -n "$vol_path" ] && [ -d "$vol_path" ]; then
		print_success "Volume directory $vol_root exists"
	else
		print_warning "Volume directory not found - will be created by setup-env.sh"
	fi
}

main() {
	echo ""
	echo "=========================================="
	echo "LifeControl - Environment Validation ($ENV)"
	echo "=========================================="
	echo ""

	check_env_file || exit 1
	check_required_vars
	check_declared_vars
	check_image_pins
	check_docker
	check_ports
	check_secret_files
	check_smtp_credential
	check_volume_dirs

	echo ""
	echo "=========================================="

	if [ $ERRORS -gt 0 ]; then
		print_error "Validation failed with $ERRORS error(s)"
		exit 1
	elif [ "$STRICT_PINS" -eq 1 ] && [ "$STALE_IMAGE_PIN_COUNT" -gt 0 ]; then
		# Its own line on purpose: the drift was reported as a warning, so calling it a validation
		# *error* here would contradict the report the operator just read.
		print_error "Strict pin check failed: $STALE_IMAGE_PIN_COUNT image pin(s) in $ENV_FILE diverge from .env.template"
		exit 1
	else
		print_success "Validation passed!"
		exit 0
	fi
}

main "$@"