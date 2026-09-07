#!/bin/bash
# ============================================
# LifeControl - Deployment Script
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

# Check if SKIP_BUILD is set to a truthy value
is_skip_build() {
	local value="${SKIP_BUILD:-}"
	case "${value,,}" in
	1 | true | yes) return 0 ;;
	*) return 1 ;;
	esac
}

# Use docker compose v2 (docker-compose v1 not available in WSL2)
DOCKER_COMPOSE="docker compose"

# Default environment
ENV=${1:-dev}
BUILD_PROFILE=${BUILD_PROFILE:-dev}
COMPOSE_FILES="-f $DOCKER_DIR/docker-compose.yml"

# Add override for development
if [ "$ENV" = "dev" ] || [ "$ENV" = "development" ]; then
	COMPOSE_FILES="$COMPOSE_FILES -f $DOCKER_DIR/docker-compose.override.yml"
fi

# Add production compose file
if [ "$ENV" = "prod" ] || [ "$ENV" = "production" ]; then
	COMPOSE_FILES="$COMPOSE_FILES -f $DOCKER_DIR/docker-compose.prod.yml"
fi

# Environment file
ENV_FILE="$DOCKER_DIR/.env.$ENV"

usage() {
	echo "Usage: $0 [dev|staging|prod] [start|stop|restart|build|build-images|up|logs|status|clean|health]"
	echo ""
	echo "Environments:"
	echo "  dev, development    - Development environment"
	echo "  staging, stg        - Staging environment"
	echo "  prod, production   - Production environment"
	echo ""
	echo "Commands:"
	echo "  start              - Build services, build images, and start containers (use SKIP_BUILD=true to skip the build)"
	echo "  build              - Build services (Java + Angular) only (no containers)"
	echo "  build-images       - Build Docker images only (uses pre-built services)"
	echo "  up                - Start services without building"
	echo "  stop              - Stop services"
	echo "  restart           - Restart services"
	echo "  logs              - Show logs"
	echo "  status            - Show status"
	echo "  clean             - Stop and remove volumes"
	echo "  health            - Check service health"
	echo ""
	echo "Environment Variables:"
	echo "  BUILD_PROFILE     - Build profile (dev|staging|prod), default: dev"
	echo "  SKIP_BUILD       - Set to '1', 'true' or 'yes' (case-insensitive) to skip build in start"
	exit 1
}

check_requirements() {
	print_status "Checking requirements..."

	if [ ! -f "$ENV_FILE" ]; then
		print_error "Environment file $ENV_FILE not found!"
		print_status "Run: ./docker/scripts/setup-env.sh $ENV"
		exit 1
	fi

	if ! docker info >/dev/null 2>&1; then
		print_error "Docker is not running!"
		exit 1
	fi

	print_success "Requirements OK"
}

build_services() {
	print_status "Building services for $ENV environment (profile: $BUILD_PROFILE)..."

	# Script can be invoked from the repo root or from docker/. Resolve paths
	# against DOCKER_DIR so builds always find the Gradle wrappers.
	local repo_root="$DOCKER_DIR/.."

	# Build Java services
	local java_services=("api-gateway" "life-control-api")

	for service in "${java_services[@]}"; do
		if [ -f "$repo_root/$service/gradlew" ]; then
			print_status "Building $service..."
			cd "$repo_root/$service"
			chmod +x gradlew
			./gradlew bootJar --no-daemon -Pprofile=$BUILD_PROFILE -x test || print_warning "$service build failed, will try docker build"
			cd - >/dev/null
		else
			print_warning "Skipping $service (gradlew not found)"
		fi
	done

	# Map BUILD_PROFILE to Angular configuration
	case "$BUILD_PROFILE" in
	dev | development) ANGULAR_CONFIG="development" ;;
	staging | stg) ANGULAR_CONFIG="production" ;;
	prod | production) ANGULAR_CONFIG="production" ;;
	*) ANGULAR_CONFIG="$BUILD_PROFILE" ;;
	esac

	# Build Angular app
	if [ -f "$repo_root/life-control-app-angular/package.json" ]; then
		print_status "Building Angular app (config: $ANGULAR_CONFIG)..."
		cd "$repo_root/life-control-app-angular"
		if [ -d "node_modules" ]; then
			npm run build -- --configuration=$ANGULAR_CONFIG || print_warning "Angular build failed"
		else
			npm install && npm run build -- --configuration=$ANGULAR_CONFIG || print_warning "Angular build failed"
		fi
		cd - >/dev/null
	fi

	print_success "Build completed!"
}

build_images() {
	print_status "Building Docker images for $ENV environment..."

	# Build Docker images with --no-cache to ensure fresh builds
	$DOCKER_COMPOSE $COMPOSE_FILES --env-file "$ENV_FILE" build --no-cache

	print_success "Docker images built!"
}

start_services() {
	print_status "Starting services for $ENV environment..."

	# Start containers (services should already be built and images should be built)
	$DOCKER_COMPOSE $COMPOSE_FILES --env-file "$ENV_FILE" up -d

	print_success "Services started!"
	show_status
}

stop_services() {
	print_status "Stopping services..."
	$DOCKER_COMPOSE $COMPOSE_FILES --env-file "$ENV_FILE" down
	print_success "Services stopped!"
}

restart_services() {
	stop_services
	sleep 2
	start_services
}

show_status() {
	echo ""
	print_status "Service Status:"
	$DOCKER_COMPOSE $COMPOSE_FILES --env-file "$ENV_FILE" ps

	echo ""
	print_status "Service URLs:"
	echo "=========================================="

	case "$ENV" in
	dev | development)
		echo -e "${GREEN}API Gateway:${NC}      http://localhost:9000"
		echo -e "${GREEN}API Gateway Act:${NC} http://localhost:9001"
		echo -e "${GREEN}Keycloak:${NC}         http://localhost:8181"
		echo -e "${GREEN}Grafana:${NC}          http://localhost:3000"
		echo -e "${GREEN}Prometheus:${NC}       http://localhost:9090"
		echo -e "${GREEN}Loki:${NC}            http://localhost:3100"
		echo -e "${GREEN}Tempo:${NC}           http://localhost:3110"
		;;
	staging | stg)
		echo -e "${GREEN}API Gateway:${NC}      http://localhost:9100"
		echo -e "${GREEN}API Gateway Act:${NC} http://localhost:9101"
		echo -e "${GREEN}Keycloak:${NC}         http://localhost:8281"
		echo -e "${GREEN}Grafana:${NC}          http://localhost:3100"
		echo -e "${GREEN}Prometheus:${NC}       http://localhost:9190"
		;;
	prod | production)
		echo -e "${GREEN}API Gateway:${NC}      http://localhost:9200"
		echo -e "${GREEN}API Gateway Act:${NC} http://localhost:9201"
		echo -e "${GREEN}Keycloak:${NC}         http://localhost:8381"
		echo -e "${GREEN}Grafana:${NC}          http://localhost:3200"
		echo -e "${GREEN}Prometheus:${NC}       http://localhost:9290"
		;;
	esac
	echo "=========================================="
}

show_logs() {
	SERVICE=${2:-}
	if [ -n "$SERVICE" ]; then
		$DOCKER_COMPOSE $COMPOSE_FILES --env-file "$ENV_FILE" logs -f "$SERVICE"
	else
		$DOCKER_COMPOSE $COMPOSE_FILES --env-file "$ENV_FILE" logs -f
	fi
}

clean_services() {
	print_warning "This will remove all volumes and data!"
	read -p "Are you sure? (yes/no): " -n 1 -r
	echo
	if [[ $REPLY =~ ^[Yy][Ee][Ss]$ ]]; then
		$DOCKER_COMPOSE $COMPOSE_FILES --env-file "$ENV_FILE" down -v
		print_success "Services and volumes cleaned!"
	else
		print_status "Cancelled"
	fi
}

health_check() {
	print_status "Checking health..."

	# Actuator is exposed on the management port (matches the Docker healthcheck
	# in docker-compose.yml). The server port is protected by OAuth2 -> 401.
	local mgmt_port
	mgmt_port=$(grep -E "^API_GATEWAY_MANAGEMENT_PORT=" "$ENV_FILE" 2>/dev/null | head -1 | cut -d= -f2)
	mgmt_port="${mgmt_port:-9001}"

	MAX_ATTEMPTS=30
	ATTEMPT=1

	while [ $ATTEMPT -le $MAX_ATTEMPTS ]; do
		if curl -sf "http://localhost:${mgmt_port}/actuator/health" >/dev/null 2>&1; then
			print_success "API Gateway is healthy!"
			return 0
		fi
		print_status "Attempt $ATTEMPT/$MAX_ATTEMPTS: Waiting for API Gateway..."
		sleep 5
		((ATTEMPT++))
	done

	print_error "Health check failed!"
	return 1
}

# Main
COMMAND=${2:-start}

case "$COMMAND" in
start)
	check_requirements
	if is_skip_build; then
		print_warning "SKIP_BUILD=true: skipping build"
	else
		build_services
		build_images
	fi
	start_services
	health_check
	;;
build)
	check_requirements
	build_services
	print_status "Services built successfully! Use 'start' to run containers or 'build-images' to create Docker images."
	;;
build-images)
	check_requirements
	build_images
	print_status "Docker images built successfully! Use 'start' or 'up' to run containers."
	;;
up)
	check_requirements
	start_services
	health_check
	;;
stop)
	stop_services
	;;
restart)
	restart_services
	;;
logs)
	show_logs "${@:3}"
	;;
status)
	show_status
	;;
clean)
	clean_services
	;;
health)
	health_check
	;;
*)
	usage
	;;
esac
