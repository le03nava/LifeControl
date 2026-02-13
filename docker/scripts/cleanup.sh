#!/bin/bash
# ============================================
# LifeControl - Cleanup Script
# ============================================

set -e

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

cleanup_docker() {
	print_status "Cleaning up Docker resources..."

	# Stop all containers
	print_status "Stopping containers..."
	docker-compose down --remove-orphans 2>/dev/null || true

	# Remove unused containers
	print_status "Removing unused containers..."
	docker container prune -f

	# Remove unused images
	print_status "Removing unused images..."
	docker image prune -f -a

	# Remove unused volumes (optional)
	print_status "Removing unused volumes..."
	docker volume prune -f

	# Remove unused networks
	print_status "Removing unused networks..."
	docker network prune -f

	print_success "Docker cleanup completed"
}

cleanup_local() {
	print_status "Cleaning up local files..."

	# Remove old data directories
	if [ -d "./data" ]; then
		print_warning "Removing ./data directory..."
		rm -rf ./data
	fi

	if [ -d "./volume-data" ]; then
		print_warning "Removing ./volume-data directory..."
		rm -rf ./volume-data
	fi

	# Remove old docker directories
	if [ -d "./docker/keycloak_postgres_data" ]; then
		rm -rf ./docker/keycloak_postgres_data
	fi

	print_success "Local cleanup completed"
}

cleanup_builds() {
	print_status "Cleaning up build artifacts..."

	# Clean gradle builds
	if [ -d "./api-gateway/build" ]; then
		rm -rf ./api-gateway/build
	fi

	if [ -d "./api-gateway/bin" ]; then
		rm -rf ./api-gateway/bin
	fi

	print_success "Build cleanup completed"
}

full_cleanup() {
	print_warning "This will perform a FULL cleanup!"
	print_warning "This includes:"
	echo "  - All Docker containers (stopped)"
	echo "  - All Docker images"
	echo "  - All Docker volumes (DATA LOSS!)"
	echo "  - All local data directories"
	echo ""

	read -p "Are you sure? Type 'yes' to confirm: " -r
	echo

	if [ "$REPLY" = "yes" ]; then
		cleanup_docker
		cleanup_local
		cleanup_builds
		print_success "Full cleanup completed!"
	else
		print_status "Cleanup cancelled"
	fi
}

show_help() {
	echo "LifeControl Cleanup Script"
	echo "=========================="
	echo ""
	echo "Usage: $0 [option]"
	echo ""
	echo "Options:"
	echo "  docker     - Clean Docker resources only"
	echo "  local      - Clean local data directories only"
	echo "  builds     - Clean build artifacts only"
	echo "  all        - Full cleanup (Docker + local + builds)"
	echo "  help       - Show this help"
	echo ""
}

# Main
case "${1:-help}" in
docker)
	cleanup_docker
	;;
local)
	cleanup_local
	;;
builds)
	cleanup_builds
	;;
all | full)
	full_cleanup
	;;
help | --help | -h)
	show_help
	;;
*)
	print_error "Unknown option: $1"
	show_help
	exit 1
	;;
esac
