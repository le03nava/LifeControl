#!/bin/bash
# ============================================
# LifeControl - Environment Validation Script
# ============================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/_common.sh"

# Resolve env (defaults to dev)
resolve_compose_env "${1:-dev}"

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
		# Skip tracked templates and dotfiles; only materialized secrets are gated
		case "$(basename "$secret_file")" in
			*.template | .gitignore) continue ;;
		esac
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
	check_docker
	check_ports
	check_secret_files
	check_volume_dirs

	echo ""
	echo "=========================================="

	if [ $ERRORS -gt 0 ]; then
		print_error "Validation failed with $ERRORS error(s)"
		exit 1
	else
		print_success "Validation passed!"
		exit 0
	fi
}

main "$@"