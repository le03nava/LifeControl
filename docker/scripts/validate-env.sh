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
		"KEYCLOAK_POSTGRES_PASSWORD"
		"KC_ADMIN_PASSWORD"
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

check_secrets() {
	print_status "Checking secrets..."

	if [ -f "$DOCKER_DIR/.env.secrets" ]; then
		print_success "Secrets file exists"

		# Check for default passwords
		if grep -q "CHANGEME" "$DOCKER_DIR/.env.secrets"; then
			print_warning "Secrets file contains CHANGEME values - update them!"
			ERRORS=$((ERRORS + 1))
		else
			print_success "Secrets appear to be configured"
		fi
	else
		print_warning "Secrets file not found (optional for dev)"
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
	check_secrets
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
