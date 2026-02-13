#!/bin/bash
# ============================================
# LifeControl - Environment Validation Script
# ============================================

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

ERRORS=0

print_status() { echo -e "${BLUE}[INFO]${NC} $1"; }
print_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
print_warning() { echo -e "${YELLOW}[WARNING]${NC} $1"; }
print_error() { echo -e "${RED}[ERROR]${NC} $1"; }

check_env_file() {
	print_status "Checking environment file..."
	if [ -f ".env" ]; then
		print_success ".env file found"
		return 0
	else
		print_error ".env file not found!"
		print_status "Run: cp .env.template .env"
		return 1
	fi
}

check_required_vars() {
	print_status "Checking required variables..."

	REQUIRED_VARS=(
		"COMPOSE_PROJECT_NAME"
		"ENVIRONMENT"
		"MONGO_ROOT_PASSWORD"
		"MYSQL_ROOT_PASSWORD"
		"KEYCLOAK_POSTGRES_PASSWORD"
		"KC_ADMIN_PASSWORD"
	)

	for var in "${REQUIRED_VARS[@]}"; do
		if grep -q "^${var}=" .env; then
			VALUE=$(grep "^${var}=" .env | cut -d'=' -f2-)
			if [ -z "$VALUE" ] || [[ "$VALUE" == *"CHANGEME"* ]]; then
				print_warning "$var is not set or uses default value"
			else
				print_success "$var is set"
			fi
		else
			print_error "$var is missing"
			((ERRORS++))
		fi
	done
}

check_docker() {
	print_status "Checking Docker..."
	if docker info >/dev/null 2>&1; then
		print_success "Docker is running"
	else
		print_error "Docker is not running"
		((ERRORS++))
	fi
}

check_ports() {
	print_status "Checking ports..."

	PORTS=(
		"MONGO_PORT"
		"MYSQL_PORT"
		"KEYCLOAK_PORT"
		"API_GATEWAY_PORT"
	)

	for port_var in "${PORTS[@]}"; do
		PORT=$(grep "^${port_var}=" .env 2>/dev/null | cut -d'=' -f2-)
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

	if [ -f ".env.secrets" ]; then
		print_success "Secrets file exists"

		# Check for default passwords
		if grep -q "CHANGEME" .env.secrets; then
			print_warning "Secrets file contains CHANGEME values - update them!"
			((ERRORS++))
		else
			print_success "Secrets appear to be configured"
		fi
	else
		print_warning "Secrets file not found (optional for dev)"
	fi
}

check_volume_dirs() {
	print_status "Checking volume directories..."

	if [ -d "volumes" ]; then
		print_success "Volume directory exists"
	else
		print_warning "Volume directory not found - will be created on first run"
	fi
}

main() {
	echo ""
	echo "=========================================="
	echo "LifeControl - Environment Validation"
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
