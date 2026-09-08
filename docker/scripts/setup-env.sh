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

# Create volume directories from VOLUMES_ROOT
print_status "Creating volume directories..."
VOLUMES_ROOT_VAL=$(get_env_var VOLUMES_ROOT)
if [ -n "$VOLUMES_ROOT_VAL" ]; then
	# Support both relative (dev/staging) and absolute (prod) VOLUMES_ROOT values
	case "$VOLUMES_ROOT_VAL" in
		/*) mkdir -p "$VOLUMES_ROOT_VAL"/{keycloak-postgres/data,postgres/{data,lifecontrol},redis/data,keycloak/realms,prometheus/{data,config},grafana/{data,config},tempo/{data,config},loki/{data,config}} ;;
		*)  mkdir -p "$DOCKER_DIR/$VOLUMES_ROOT_VAL"/{keycloak-postgres/data,postgres/{data,lifecontrol},redis/data,keycloak/realms,prometheus/{data,config},grafana/{data,config},tempo/{data,config},loki/{data,config}} ;;
	esac
	print_success "Volume directories created"
else
	print_warning "VOLUMES_ROOT not set in $ENV_FILE — volume directories not created. Compose will fail loudly if VOLUMES_ROOT is missing."
fi

# Copy selected env file to .env (convenience for manual docker-compose usage)
cp "$ENV_FILE" "$DOCKER_DIR/.env"
print_success "Copied $ENV_FILE to $DOCKER_DIR/.env"

# For production, check secrets
if [ "$ENV" = "prod" ]; then
	print_status "Checking secrets..."
	if [ ! -f "$DOCKER_DIR/.env.secrets" ]; then
		print_error "Secrets file $DOCKER_DIR/.env.secrets not found!"
		print_status "Creating template..."
		cp "$DOCKER_DIR/.env.secrets.template" "$DOCKER_DIR/.env.secrets"
		print_warning "Please edit $DOCKER_DIR/.env.secrets and fill in the values"
	else
		print_success "Secrets file found"
	fi

	# Create secrets directory
	mkdir -p "$DOCKER_DIR/secrets"
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
