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
	env_flag_is_truthy "${SKIP_BUILD:-}"
}

# Check if STRICT_REVISION asks for the revision report to fail the command.
#
# `report_image_revisions` only warns, so `health` stays a read-only report by default -- the same
# contract `report_stale_image_pins` has, and for the same reason: this is not the prod gate. An
# operator who wants the evidence to stop a command asks for it here, the way `--strict-pins` asks
# validate-env.sh.
is_strict_revision() {
	env_flag_is_truthy "${STRICT_REVISION:-}"
}

usage() {
	echo "Usage: $0 [dev|staging|prod] [start|stop|restart|build|build-images|up|logs|status|version|clean|health]"
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
	echo "  version           - Show the revision each running application image was built from (exits non-zero when one is not HEAD)"
	echo "  clean             - Stop and remove volumes"
	echo "  health            - Check service health"
	echo ""
	echo "Environment Variables:"
	echo "  BUILD_PROFILE     - Build profile (dev|staging|prod), default: current environment"
	echo "  SKIP_BUILD       - Set to '1', 'true' or 'yes' (case-insensitive) to skip build in start"
	echo "  STRICT_REVISION  - Set to '1', 'true' or 'yes' to fail health when a running application image was not built from HEAD"
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

	# The Java Dockerfiles do not compile: they COPY a pre-built JAR. Fail here,
	# immediately before docker build, if any JAR is missing or older than its
	# module's build inputs. This is the only path that builds images, so every
	# command that calls it (start and build-images) is covered; start with
	# SKIP_BUILD=true builds no images and therefore needs no guard.
	local jar_version
	jar_version="$(resolve_jar_version)"
	local service
	for service in "${JAVA_SERVICES[@]}"; do
		if ! verify_artifact_freshness "$service" "$service/build/libs/$service-$jar_version.jar"; then
			print_error "$service build artifact is not fresh; aborting before docker build."
			exit 1
		fi
	done

	# Stamp the revision each image carries, for traceability of what runs. The shell environment
	# takes precedence over --env-file, so exporting is enough for compose's ${GIT_COMMIT:-unknown}.
	# What the stamp admits about a dirty tree is image_revision_stamp's contract, not this call.
	local git_commit
	git_commit="$(image_revision_stamp)"
	export GIT_COMMIT="$git_commit"
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

	# Postgres first, alone: the two roles (ADR-0005) have to exist before any application connects,
	# and a volume that already holds data never runs the image's init scripts again. db-roles.sh
	# applies the same body of SQL through the container, so one run covers both kinds of volume.
	print_status "Starting PostgreSQL for $ENV environment..."
	compose_run up -d lifecontrol-postgres
	if ! "$SCRIPT_DIR/db-roles.sh" "$ENV"; then
		print_error "Database role setup failed — aborting start. An application that connects without these roles cannot isolate a tenant."
		exit 1
	fi
	print_success "Database roles in place"

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

# Answer "which code is running", and let a script act on the answer.
#
# The report is shared with health_check, so exactly one place decides what a revision means. This
# command adds only the exit contract: 0 when every running application image was built from HEAD.
show_version() {
	report_image_revisions
	if [ "${IMAGE_REVISION_MISMATCH_COUNT:-0}" -eq 0 ]; then
		return 0
	fi
	print_error "The stack is not running the code this repository has checked out."
	return 1
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

# Loki and Tempo images are distroless: an in-container healthcheck cannot run, so their
# readiness is measured from the host through the published port. Prints one
# " <name>(not-ready)" token per service that is not answering, and returns non-zero when
# at least one did not. Ports that are not configured are skipped.
host_readiness_probes() {
	local failures=""
	local loki_port tempo_port
	loki_port="$(get_env_var LOKI_PORT)"
	tempo_port="$(get_env_var TEMPO_PORT)"

	local probes=()
	if [ -n "$loki_port" ]; then
		probes+=("Loki http://127.0.0.1:$(get_env_var LOKI_PORT)/ready")
	fi
	if [ -n "$tempo_port" ]; then
		probes+=("Tempo http://127.0.0.1:$(get_env_var TEMPO_PORT)/ready")
	fi

	local entry name url
	for entry in ${probes[@]+"${probes[@]}"}; do
		name="${entry%% *}"
		url="${entry#* }"
		if ! curl -sf -o /dev/null --max-time 5 "$url"; then
			failures="$failures ${name}(not-ready)"
		fi
	done

	[ -z "$failures" ] || printf '%s\n' "$failures"
	[ -z "$failures" ]
}

health_check() {
	# Which code is running is part of "healthy": a green stack over an image built ten commits ago
	# is the failure this whole unit exists for, and `up` / `SKIP_BUILD=true` reach this function
	# without ever building. The report is read-only and only warns, so a start is never failed by
	# it; STRICT_REVISION is the opt-in that turns the same evidence into a failure -- the shape
	# --strict-pins gives the image pins, and for the same reason: this is not the prod gate.
	report_image_revisions
	if is_strict_revision && [ "${IMAGE_REVISION_MISMATCH_COUNT:-0}" -gt 0 ]; then
		print_error "STRICT_REVISION is set and ${IMAGE_REVISION_MISMATCH_COUNT} application image(s) are not HEAD."
		return 1
	fi

	print_status "Waiting for all services to become healthy..."

	local max_attempts=30
	local attempt=1

	local has_curl=1
	if ! command -v curl >/dev/null 2>&1; then
		has_curl=0
		print_warning "curl not found: Loki and Tempo readiness (/ready) will NOT be checked."
	fi

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

		if [ "$has_curl" -eq 1 ]; then
			local not_ready="" not_observable=""
			# `|| true` keeps set -e happy while still capturing the tokens on stdout.
			not_ready="$(host_readiness_probes)" || true
			# The signals have to be readable, not merely running: a green health check over an
			# unreadable trace store is the failure this probe was written for.
			not_observable="$(host_observability_probes)" || true
			unhealthy="$unhealthy$not_ready$not_observable"
		fi

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
	# Before building anything: a materialised env file that pins an image the template no longer
	# names would otherwise start an old image with a green deploy. Report only -- validated by
	# `validate-env.sh <env> --strict-pins` when a caller wants it to be a failure.
	report_stale_image_pins "$ENV_FILE" "$DOCKER_DIR/.env.template"
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
version)
	check_requirements
	show_version
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
