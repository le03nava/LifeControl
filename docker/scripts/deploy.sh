#!/bin/bash
# ============================================
# LifeControl - Deployment Script
# ============================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/_common.sh"

# Resolve env (defaults to dev)
resolve_compose_env "${1:-dev}"

# Default build profile
BUILD_PROFILE=${BUILD_PROFILE:-dev}

# Check if SKIP_BUILD is set to a truthy value
is_skip_build() {
	local value="${SKIP_BUILD:-}"
	case "${value,,}" in
	1 | true | yes) return 0 ;;
	*) return 1 ;;
	esac
}

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
	$DOCKER_COMPOSE $COMPOSE_FILES --env-file "$ENV_FILE" build --no-cache
	print_success "Docker images built!"
}

start_services() {
	print_status "Starting services for $ENV environment..."
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
	print_url "API Gateway"         "$(get_env_var API_GATEWAY_PORT)"
	print_url "API Gateway Act"     "$(get_env_var API_GATEWAY_MANAGEMENT_PORT)"
	print_url "Keycloak"            "$(get_env_var KEYCLOAK_PORT)"
	print_url "Grafana"             "$(get_env_var GRAFANA_PORT)"
	print_url "Prometheus"          "$(get_env_var PROMETHEUS_PORT)"
	print_url "Loki"                "$(get_env_var LOKI_PORT)"
	print_url "Tempo"               "$(get_env_var TEMPO_PORT)"
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
	if [[ $REPLY =~ ^[Yy]$ ]]; then
		$DOCKER_COMPOSE $COMPOSE_FILES --env-file "$ENV_FILE" down -v
		print_success "Services and volumes cleaned!"
	else
		print_status "Cancelled"
	fi
}

health_check() {
	print_status "Checking health..."

	local mgmt_port
	mgmt_port=$(get_env_var API_GATEWAY_MANAGEMENT_PORT)
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
