#!/bin/bash
# ============================================
# LifeControl - Deployment Script
# ============================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/_common.sh"

# Resolve env (defaults to dev)
resolve_compose_env "${1:-dev}"

# Default build profile (fallback: current environment)
BUILD_PROFILE=${BUILD_PROFILE:-$ENV}

# Java services whose Dockerfiles COPY a pre-built JAR (they do not compile).
JAVA_SERVICES=("api-gateway" "life-control-api")

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
	echo "  BUILD_PROFILE     - Build profile (dev|staging|prod), default: current environment"
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

# Resolve the JAR version exactly like compose interpolates APP_VERSION:
# shell environment first, then --env-file, then the built-in default.
resolve_jar_version() {
	local jar_version="${APP_VERSION:-}"
	if [ -z "$jar_version" ]; then
		jar_version="$(get_env_var APP_VERSION)"
	fi
	if [ -z "$jar_version" ]; then
		jar_version="0.0.1-SNAPSHOT"
	fi
	echo "$jar_version"
}

build_services() {
	print_status "Building services for $ENV environment (profile: $BUILD_PROFILE)..."

	local repo_root="$DOCKER_DIR/.."
	local jar_version
	jar_version="$(resolve_jar_version)"

	# Build Java services
	for service in "${JAVA_SERVICES[@]}"; do
		if [ ! -f "$repo_root/$service/gradlew" ]; then
			print_error "$service/gradlew not found: cannot build the JAR that its Dockerfile COPYs."
			print_error "Restore the Gradle wrapper before deploying (api-gateway/gradlew is not tracked by git)."
			exit 1
		fi

		print_status "Building $service..."
		cd "$repo_root/$service"
		chmod +x gradlew
		# The Java Dockerfiles do not compile: they COPY build/libs/<service>-<version>.jar.
		# Swallowing a Gradle failure here would let docker build bake in the previous JAR.
		if ! ./gradlew bootJar --no-daemon -Pprofile=$BUILD_PROFILE -x test; then
			print_error "$service Gradle build failed."
			print_error "The Java Dockerfile only COPYs build/libs/$service-$jar_version.jar, so continuing would ship a stale artifact as if it were freshly built. Aborting."
			exit 1
		fi
		cd - >/dev/null
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
		# This pre-build is only an early local check: the Angular image build
		# recompiles from source in its multi-stage Dockerfile, so a failure here
		# is deliberately not fatal.
		local angular_warn="Angular build failed (early local check only). The image build recompiles Angular from source and will fail on its own if compilation is broken."
		print_status "Building Angular app (config: $ANGULAR_CONFIG)..."
		cd "$repo_root/life-control-app-angular"
		if [ -d "node_modules" ]; then
			npm run build -- --configuration=$ANGULAR_CONFIG || print_warning "$angular_warn"
		else
			npm install && npm run build -- --configuration=$ANGULAR_CONFIG || print_warning "$angular_warn"
		fi
		cd - >/dev/null
	fi

	print_success "Build completed!"
}

build_images() {
	print_status "Building Docker images for $ENV environment..."

	local repo_root="$DOCKER_DIR/.."

	# The Java Dockerfiles do not compile: they COPY a pre-built JAR. Fail here,
	# immediately before docker build, if any JAR is missing or older than its
	# module. This is the only path that builds images, so start, build-images
	# and start with SKIP_BUILD=true are all covered.
	local jar_version
	jar_version="$(resolve_jar_version)"
	local service
	for service in "${JAVA_SERVICES[@]}"; do
		if ! verify_artifact_freshness "$service" "$service/build/libs/$service-$jar_version.jar"; then
			print_error "$service build artifact is not fresh; aborting before docker build."
			exit 1
		fi
	done

	# Sello la revisión git en cada imagen para trazabilidad de lo que corre.
	# El entorno del shell tiene precedencia sobre --env-file, así que export
	# alcanza para que la interpolación ${GIT_COMMIT:-unknown} de compose lo tome.
	local git_commit
	# set -e está activo: el guard evita que un fallo de git aborte el build.
	git_commit="$(git -C "$repo_root" rev-parse HEAD 2>/dev/null || true)"
	export GIT_COMMIT="${git_commit:-unknown}"
	print_status "Stamping GIT_COMMIT=$GIT_COMMIT"

	compose_run build --no-cache
	print_success "Docker images built!"
}

start_services() {
	if [ "$ENV" = "prod" ]; then
		print_status "Running production validation gate..."
		if ! "$SCRIPT_DIR/validate-env.sh" prod; then
			print_error "Validation failed — aborting start. Fix the reported issues and retry."
			exit 1
		fi
		print_success "Production validation passed"
	fi

	print_status "Starting services for $ENV environment..."
	compose_run up -d
	print_success "Services started!"
	show_status
}

stop_services() {
	print_status "Stopping services..."
	compose_run down
	print_success "Services stopped!"
}

restart_services() {
	stop_services
	sleep 2
	start_services
	health_check
}

show_status() {
	echo ""
	print_status "Service Status:"
	compose_run ps

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
	SERVICE=${1:-}
	if [ -n "$SERVICE" ]; then
		compose_run logs -f "$SERVICE"
	else
		compose_run logs -f
	fi
}

clean_services() {
	print_warning "This will remove all volumes and data!"
	read -p "Are you sure? (y/n): " -n 1 -r
	echo
	if [[ $REPLY =~ ^[Yy]$ ]]; then
		compose_run down -v
		print_success "Services and volumes cleaned!"
	else
		print_status "Cancelled"
	fi
}

health_check() {
	print_status "Waiting for all services to become healthy..."

	local max_attempts=30
	local attempt=1

	while [ "$attempt" -le "$max_attempts" ]; do
		local ps_output expected count unhealthy=""
		expected=$(compose_run ps --services 2>/dev/null | sed '/^$/d' | wc -l)
		ps_output=$(compose_run ps --format '{{.Service}} {{.State}} {{.Health}}' 2>/dev/null)
		count=$(printf '%s\n' "$ps_output" | sed '/^$/d' | wc -l)

		local line svc state health
		while IFS= read -r line; do
			svc=$(echo "$line" | cut -d' ' -f1)
			state=$(echo "$line" | cut -d' ' -f2)
			health=$(echo "$line" | cut -d' ' -f3)
			if [ "$state" != "running" ]; then
				unhealthy="$unhealthy $svc($state)"
			elif [ -n "$health" ] && [ "$health" != "healthy" ]; then
				unhealthy="$unhealthy $svc($health)"
			fi
		done <<< "$ps_output"

		if [ -n "$unhealthy" ] || [ "$count" -lt "$expected" ]; then
			print_status "Attempt $attempt/$max_attempts — waiting for:$unhealthy"
			sleep 5
			attempt=$((attempt + 1))
		else
			print_success "All services healthy!"
			return 0
		fi
	done

	print_error "Health check failed after $max_attempts attempts — not healthy:$unhealthy"
	compose_run ps
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
