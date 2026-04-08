#!/bin/bash
# ============================================
# LifeControl - Cleanup Script
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

# Use docker compose v2
DOCKER_COMPOSE="docker compose"

# Default environment
ENV=${2:-dev}

# Parse option first
OPTION=${1:-help}
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

# ============================================
# Stop containers (without removing)
# ============================================
stop_containers() {
	print_status "Stopping all containers..."

	$DOCKER_COMPOSE $COMPOSE_FILES --env-file "$ENV_FILE" down 2>/dev/null || true

	print_success "All containers stopped"
}

cleanup_docker() {
	print_status "Cleaning up Docker resources..."

	# Stop all containers using same compose files and env as deploy.sh
	print_status "Stopping containers..."
	$DOCKER_COMPOSE $COMPOSE_FILES --env-file "$ENV_FILE" down --remove-orphans 2>/dev/null || true

	# Remove unused containers
	print_status "Removing unused containers..."
	docker container prune -f

	# Remove unused images
	print_status "Removing unused images..."
	docker image prune -f -a

	# Remove unused networks
	print_status "Removing unused networks..."
	docker network prune -f

	print_success "Docker cleanup completed (volumes preserved)"
}

cleanup_volumes() {
	print_status "Cleaning up Docker volumes..."

	# ⚠️ WARNING: This will delete ALL data in volumes!
	print_warning "This will delete ALL data in Docker volumes!"
	print_warning "Databases will be reset to empty state!"

	read -p "Are you sure? Type 'yes' to confirm: " -r
	echo

	if [ "$REPLY" = "yes" ]; then
		print_status "Removing all Docker volumes..."
		docker volume prune -f
		print_success "Volumes cleanup completed"
	else
		print_status "Volumes cleanup cancelled"
	fi
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
		stop_containers
		cleanup_volumes
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
	echo "Usage: $0 [option] [env]"
	echo ""
	echo "Options:"
	echo "  stop       - Stop all containers (preserves volumes, images, networks)"
	echo "  docker     - Clean Docker resources (containers, images, networks)"
	echo "              Preserves volumes!"
	echo "  volumes    - Clean Docker volumes only (DESTRUCTIVE - deletes all data)"
	echo "  local      - Clean local data directories only"
	echo "  builds     - Clean build artifacts only"
	echo "  all        - Full cleanup (stop + docker + volumes + local + builds)"
	echo "  help       - Show this help"
	echo ""
	echo "Environment:"
	echo "  dev        - Development (default)"
	echo "  staging    - Staging"
	echo "  prod       - Production"
	echo ""
	echo "Examples:"
	echo "  $0 stop dev            # Stop all containers"
	echo "  $0 docker dev          # Clean Docker, keep volumes"
	echo "  $0 volumes dev         # Delete all volumes (data loss!)"
	echo "  $0 all staging         # Full cleanup, including volumes"
	echo "  $0 stop prod"
	echo ""
}

# Main

case "${OPTION:-help}" in
stop)
	stop_containers
	;;
docker)
	cleanup_docker
	;;
volumes)
	cleanup_volumes
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
	print_error "Unknown option: $OPTION"
	show_help
	exit 1
	;;
esac
