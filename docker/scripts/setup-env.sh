#!/bin/bash
# ============================================
# LifeControl - Environment Setup Script
# ============================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/_common.sh"

# Resolve env (defaults to dev)
resolve_compose_env "${1:-dev}"

print_status "Setting up ${ENV} environment"

# Check if env file exists; create from template if missing
if [ ! -f "$ENV_FILE" ]; then
	print_status "Environment file $ENV_FILE not found. Creating from template..."
	cp "$DOCKER_DIR/.env.template" "$ENV_FILE"
	# Template defaults VOLUMES_ROOT to the dev volume root; point it at this
	# environment's volume root so non-dev setups never share the dev volumes.
	# dev/staging keep a repo-relative dir; prod uses an absolute root outside
	# the repo (see docker-compose.prod.yml and .env.prod).
	if [ "$ENV" = "prod" ]; then
		sed -i "s|^VOLUMES_ROOT=.*|VOLUMES_ROOT=/var/lib/lifecontrol/volumes|" "$ENV_FILE"
		# Prod uses a postgres host port distinct from dev/staging (5435) so a
		# fresh prod setup never collides with other envs on the same host.
		sed -i "s|^LIFECONTROL_POSTGRES_PORT=.*|LIFECONTROL_POSTGRES_PORT=5445|" "$ENV_FILE"
		# Prod passwords come exclusively from docker/secrets/<name> files mounted
		# at /run/secrets/<name> (see entrypoint wrappers). Strip every password
		# and client-secret related line so compose can never interpolate a
		# plaintext value — the wrapper is the sole password source.
		sed -i -E "/^[A-Z0-9_]*PASSWORD(_FILE)?=/d; /^KC_[A-Z0-9_]*=/d; /^[A-Z0-9_]*_FILE=/d; /^[A-Z0-9_]*CLIENT_SECRET=/d" "$ENV_FILE"
	else
		sed -i "s|^VOLUMES_ROOT=.*|VOLUMES_ROOT=./volumes-${ENV}|" "$ENV_FILE"
	fi
	print_success "Created $ENV_FILE"
	print_warning "Please review $ENV_FILE and fill in any secrets before starting services."
fi

print_status "Using environment file: $ENV_FILE"

# Check Docker first
if ! docker info >/dev/null 2>&1; then
	print_error "Docker is not running!"
	exit 1
fi
print_success "Docker is running"

# Install reference monitoring configs (prometheus/tempo/grafana) into the
# environment's volume root if absent. Prometheus/Tempo need a config file to
# start and the grafana config dir is the provisioning/datasources mount.
# Never overwrites existing user edits.
install_monitoring_configs() {
	local vol_path="$1"
	local src="$DOCKER_DIR/config"
	local copied=0

	if [ -f "$src/prometheus/prometheus.yml" ] && [ ! -f "$vol_path/prometheus/config/prometheus.yml" ]; then
		cp "$src/prometheus/prometheus.yml" "$vol_path/prometheus/config/prometheus.yml"
		copied=$((copied + 1))
	fi
	if [ -f "$src/tempo/tempo.yml" ] && [ ! -f "$vol_path/tempo/config/tempo.yml" ]; then
		cp "$src/tempo/tempo.yml" "$vol_path/tempo/config/tempo.yml"
		copied=$((copied + 1))
	fi
	if [ -f "$src/grafana/datasources.yml" ] && [ ! -f "$vol_path/grafana/config/datasources.yml" ]; then
		cp "$src/grafana/datasources.yml" "$vol_path/grafana/config/datasources.yml"
		copied=$((copied + 1))
	fi

	if [ "$copied" -gt 0 ]; then
		print_success "Monitoring configs installed ($copied file(s))"
	else
		print_status "Monitoring configs already present — not overwritten"
	fi

	# The grafana container runs as uid 1000:1000; make its data dir writable.
	if [ -d "$vol_path/grafana/data" ]; then
		if chown 1000:1000 "$vol_path/grafana/data" 2>/dev/null; then
			print_success "grafana data dir owner set to 1000:1000"
		else
			print_warning "Could not chown '$vol_path/grafana/data' to 1000:1000 — apply manually:"
			print_warning "  chown 1000:1000 '$vol_path/grafana/data'"
		fi
	fi
}

# Create volume directories from VOLUMES_ROOT and install reference configs
print_status "Creating volume directories..."
VOLUMES_ROOT_VAL=$(get_env_var VOLUMES_ROOT)
if [ -n "$VOLUMES_ROOT_VAL" ]; then
	# Support both relative (dev/staging) and absolute (prod) VOLUMES_ROOT values
	case "$VOLUMES_ROOT_VAL" in
		/*) vol_path="$VOLUMES_ROOT_VAL" ;;
		*)  vol_path="$DOCKER_DIR/$VOLUMES_ROOT_VAL" ;;
	esac
	mkdir -p "$vol_path"/{keycloak-postgres/data,postgres/{data,lifecontrol},redis/data,keycloak/realms,prometheus/{data,config},grafana/{data,config},tempo/{data,config},loki/{data,config}}
	print_success "Volume directories created"

	install_monitoring_configs "$vol_path"
else
	print_warning "VOLUMES_ROOT not set in $ENV_FILE — volume directories not created. Compose will fail loudly if VOLUMES_ROOT is missing."
fi

# Copy selected env file to .env (convenience for manual docker-compose usage)
cp "$ENV_FILE" "$DOCKER_DIR/.env"
print_success "Copied $ENV_FILE to $DOCKER_DIR/.env"

# For production, materialize docker/secrets/* files from templates
if [ "$ENV" = "prod" ]; then
	print_status "Materializing docker/secrets files..."

	# Ensure the directory exists (gitignored; only *.template is tracked)
	mkdir -p "$DOCKER_DIR/secrets"

	SECRETS_DIR="$DOCKER_DIR/secrets"
	FILES_BLOCKED=0

	for template in "$SECRETS_DIR"/*.template; do
		[ -e "$template" ] || continue
		secret_name="$(basename "$template" .template)"
		secret_file="$SECRETS_DIR/$secret_name"

		if [ -f "$secret_file" ]; then
			print_success "Secret $secret_name already exists — keeping it"
			continue
		fi

		# Strip comment/blank lines so the materialized file holds only the value
		grep -v '^[[:space:]]*#' "$template" | grep -v '^[[:space:]]*$' > "$secret_file"
		if [ ! -s "$secret_file" ]; then
			print_error "Secret template $template produced an empty file — fix the template"
			exit 1
		fi
		if chmod 0444 "$secret_file" 2>/dev/null; then
			print_success "Created $secret_name (chmod 0444 — container uid 999/1000/472 can all read it)"
		else
			FILES_BLOCKED=1
			print_warning "Could not chmod 0444 $secret_file — run manually: chmod 0444 $secret_file"
		fi
	done

	if [ "$FILES_BLOCKED" -eq 1 ]; then
		print_warning "Some docker/secrets files could not be made world-readable (0444)."
		print_warning "Containers run as non-root uids — apply manually before deploy:"
		print_warning "  chmod 0444 $SECRETS_DIR/*"
	fi
fi

print_success "Environment setup completed!"
print_status "Run './docker/scripts/deploy.sh ${ENV} start' to start services"

# Show service URLs (derived from env vars)
echo ""
print_status "Service URLs:"
echo "=========================================="
print_url "API Gateway"    "$(get_env_var API_GATEWAY_PORT)"
print_url "Keycloak"       "$(get_env_var KEYCLOAK_PORT)"
print_url "Grafana"        "$(get_env_var GRAFANA_PORT)"
print_url "Prometheus"     "$(get_env_var PROMETHEUS_PORT)"
print_url "Loki"           "$(get_env_var LOKI_PORT)"
print_url "Tempo"          "$(get_env_var TEMPO_PORT)"
echo "=========================================="