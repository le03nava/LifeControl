#!/bin/bash
# ============================================
# LifeControl - Cleanup Script
# ============================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/_common.sh"

# Parse option first
OPTION=${1:-help}
# Resolve env (defaults to dev)
resolve_compose_env "${2:-dev}"

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

	print_status "Stopping containers..."
	$DOCKER_COMPOSE $COMPOSE_FILES --env-file "$ENV_FILE" down --remove-orphans 2>/dev/null || true

	print_status "Removing unused containers..."
	docker container prune -f

	print_status "Removing unused images..."
	docker image prune -f -a

	print_status "Removing unused networks..."
	docker network prune -f

	print_success "Docker cleanup completed (volumes preserved)"
}

cleanup_volumes() {
	print_status "Cleaning up Docker volumes..."

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

	# Only the CURRENT environment's volume directory may be removed here.
	# Absolute VOLUMES_ROOT (prod, e.g. /var/lib/lifecontrol/volumes) points OUTSIDE
	# the repo: never delete prod data locally — it must be cleaned manually or
	# via a scripted cleanup. Relative roots (dev/staging, ./volumes-<env>) are
	# scoped to this env only; other envs' volume dirs are never touched.
	local vol_root
	vol_root=$(get_env_var VOLUMES_ROOT)
	if [[ "$vol_root" == /* ]]; then
		print_warning "VOLUMES_ROOT=$vol_root is absolute (prod) — local data at that path is deliberately NOT cleaned here."
		print_warning "Clean $vol_root manually or via a scripted cleanup."
	elif [ -n "$vol_root" ]; then
		# Relative root (dev/staging): remove only this env's dir, e.g. ./volumes-dev.
		local vol_dir="${vol_root#./}"
		local target="$DOCKER_DIR/$vol_dir"
		if [ -d "$target" ]; then
			print_warning "Removing $ENV volume directory $target..."
			rm -rf "$target"
		else
			print_status "No local volume directory to remove for $ENV ($target)"
		fi
	else
		print_warning "VOLUMES_ROOT is not set in $ENV_FILE — no local volume directory removed."
	fi

	print_success "Local cleanup completed"
}

cleanup_builds() {
	print_status "Cleaning up build artifacts..."

	local repo_root="$DOCKER_DIR/.."

	# api-gateway
	if [ -d "$repo_root/api-gateway/build" ]; then
		rm -rf "$repo_root/api-gateway/build"
	fi
	if [ -d "$repo_root/api-gateway/bin" ]; then
		rm -rf "$repo_root/api-gateway/bin"
	fi

	# life-control-api
	if [ -d "$repo_root/life-control-api/build" ]; then
		rm -rf "$repo_root/life-control-api/build"
	fi
	if [ -d "$repo_root/life-control-api/bin" ]; then
		rm -rf "$repo_root/life-control-api/bin"
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
