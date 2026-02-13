#!/bin/bash
# ============================================
# LifeControl - Environment Setup Script
# ============================================

set -e

# Docker directory
DOCKER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

print_status() { echo -e "${BLUE}[INFO]${NC} $1"; }
print_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
print_warning() { echo -e "${YELLOW}[WARNING]${NC} $1"; }
print_error() { echo -e "${RED}[ERROR]${NC} $1"; }

# Check environment argument
ENV=${1:-dev}

# Environment files
case "$ENV" in
dev | development)
	ENV_FILE="$DOCKER_DIR/.env.dev"
	print_status "Setting up DEVELOPMENT environment"
	;;
staging | stg)
	ENV_FILE="$DOCKER_DIR/.env.staging"
	print_status "Setting up STAGING environment"
	;;
prod | production)
	ENV_FILE="$DOCKER_DIR/.env.prod"
	print_status "Setting up PRODUCTION environment"
	;;
*)
	print_error "Invalid environment: $ENV"
	echo "Usage: $0 [dev|staging|prod]"
	exit 1
	;;
esac

# Check if env file exists
if [ ! -f "$ENV_FILE" ]; then
	print_error "Environment file $ENV_FILE not found!"
	print_status "Creating from template..."
	cp "$DOCKER_DIR/.env.template" "$ENV_FILE"
	print_success "Created $ENV_FILE"
	print_warning "Please edit $ENV_FILE and fill in the values"
	exit 1
fi

print_status "Using environment file: $ENV_FILE"

# Copy selected environment file to .env (in docker dir)
cp "$ENV_FILE" "$DOCKER_DIR/.env"
print_success "Copied $ENV_FILE to $DOCKER_DIR/.env"

# Create volume directories
print_status "Creating volume directories..."
mkdir -p volumes/{mongodb/data,mysql/{data,init},postgres/data,kafka/data,keycloak/realms,prometheus/{data,config},grafana/{data,config},tempo/{data,config},loki/{data,config}}
mkdir -p volumes-mysql/init
print_success "Volume directories created"

# Check Docker
if ! docker info >/dev/null 2>&1; then
	print_error "Docker is not running!"
	exit 1
fi
print_success "Docker is running"

# Create network if needed
NETWORK_NAME=$(grep "^NETWORK_NAME" "$ENV_FILE" | cut -d'=' -f2)
NETWORK_NAME=${NETWORK_NAME:-lifecontrol-network-dev}

if ! docker network ls | grep -q "$NETWORK_NAME"; then
	print_status "Creating Docker network: $NETWORK_NAME"
	docker network create "$NETWORK_NAME"
	print_success "Network created"
fi

# For production, check secrets
if [ "$ENV" = "prod" ] || [ "$ENV" = "production" ]; then
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
	mkdir -p secrets
fi

print_success "Environment setup completed!"
print_status "Run 'docker-compose --env-file $ENV_FILE up -d' to start services"

# Show service URLs
echo ""
print_status "Service URLs:"
echo "=========================================="
case "$ENV" in
dev)
	echo -e "${GREEN}API Gateway:${NC}      http://localhost:9000"
	echo -e "${GREEN}Keycloak:${NC}         http://localhost:8181"
	echo -e "${GREEN}Grafana:${NC}          http://localhost:3000"
	echo -e "${GREEN}Prometheus:${NC}       http://localhost:9090"
	echo -e "${GREEN}Loki:${NC}            http://localhost:3100"
	echo -e "${GREEN}Kafka UI:${NC}         http://localhost:8086"
	;;
staging)
	echo -e "${GREEN}API Gateway:${NC}      http://localhost:9100"
	echo -e "${GREEN}Keycloak:${NC}         http://localhost:8281"
	echo -e "${GREEN}Grafana:${NC}          http://localhost:3100"
	echo -e "${GREEN}Prometheus:${NC}       http://localhost:9190"
	;;
prod)
	echo -e "${GREEN}API Gateway:${NC}      http://localhost:9200"
	echo -e "${GREEN}Keycloak:${NC}         http://localhost:8381"
	echo -e "${GREEN}Grafana:${NC}          http://localhost:3200"
	echo -e "${GREEN}Prometheus:${NC}       http://localhost:9290"
	;;
esac
echo "=========================================="
