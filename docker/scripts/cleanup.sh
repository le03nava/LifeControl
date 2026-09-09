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
	compose_run down 2>/dev/null || true
	print_success "All containers stopped"
}

cleanup_docker() {
	local project_name
	project_name=$(get_env_var COMPOSE_PROJECT_NAME)

	if [ -z "$project_name" ]; then
		print_warning "COMPOSE_PROJECT_NAME not set in $ENV_FILE — skipping Docker cleanup to avoid touching other projects."
		return 1
	fi

	print_status "Cleaning up Docker resources for project '$project_name'..."

	print_status "Stopping containers..."
	compose_run down --remove-orphans 2>/dev/null || true

	print_status "Removing unused $project_name containers..."
	docker container prune -f --filter "label=com.docker.compose.project=$project_name"

	print_status "Removing unused $project_name images..."
	docker image prune -f --filter "label=com.docker.compose.project=$project_name"

	print_status "Removing unused $project_name networks..."
	docker network prune -f --filter "label=com.docker.compose.project=$project_name"

	print_success "Docker cleanup completed (volumes preserved, other projects untouched)"
}

cleanup_volumes() {
	local auto="${1:-}"
	local project_name
	project_name=$(get_env_var COMPOSE_PROJECT_NAME)

	if [ "$auto" != "--yes" ]; then
		print_warning "This will delete ALL data in the '$project_name' Docker volumes!"
		print_warning "Databases will be reset to empty state!"

		read -p "Are you sure? (y/n): " -n 1 -r
		echo

		if [[ ! $REPLY =~ ^[Yy]$ ]]; then
			print_status "Volumes cleanup cancelled"
			return
		fi
	fi

	if [ -z "$project_name" ]; then
		print_warning "COMPOSE_PROJECT_NAME not set in $ENV_FILE — cannot scope volume prune, skipping."
		return 1
	fi

	print_status "Removing project Docker volumes..."
	docker volume prune -f --filter "label=com.docker.compose.project=$project_name"
	print_success "Project volumes cleanup completed"
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

rotate_secrets() {
	local auto="${1:-}"
	local secrets_dir="$DOCKER_DIR/secrets"
	local rotated=0

	if [ ! -d "$secrets_dir" ]; then
		print_error "$secrets_dir not found — run setup-env.sh first"
		return 1
	fi

	if [ "$auto" != "--yes" ]; then
		print_warning "This will REGENERATE all materialized docker/secrets files from their templates."
		print_warning "Existing credential values will be overwritten. Running services must be restarted."
		read -p "Are you sure? (y/n): " -n 1 -r
		echo
		if [[ ! $REPLY =~ ^[Yy]$ ]]; then
			print_status "Secret rotation cancelled"
			return
		fi
	fi

	for template in "$secrets_dir"/*.template; do
		[ -e "$template" ] || continue
		secret_name="$(basename "$template" .template)"
		secret_file="$secrets_dir/$secret_name"

		# Strip comment/blank lines so the file holds only the value
		grep -v '^[[:space:]]*#' "$template" | grep -v '^[[:space:]]*$' > "$secret_file"
		if [ ! -s "$secret_file" ]; then
			print_error "Secret template $template produced an empty file — fix the template"
			rm -f "$secret_file"
			continue
		fi
		chmod 0444 "$secret_file" 2>/dev/null || true
		print_success "Rotated $secret_name (edit $secret_file with the new value)"
		rotated=$((rotated + 1))
	done

	if [ "$rotated" -eq 0 ]; then
		print_warning "No secrets rotated — no *.template files found in $secrets_dir"
	else
		print_success "Secret rotation completed ($rotated file(s))"
		print_warning "After rotating secrets, restart services so containers pick up the new values:"
		print_warning "  ./docker/scripts/deploy.sh $ENV restart"
	fi
}

full_cleanup() {
	local project_name
	project_name=$(get_env_var COMPOSE_PROJECT_NAME)
	print_warning "This will perform a FULL cleanup of project '$project_name'!"
	print_warning "This includes:"
	echo "  - All Docker containers (stopped)"
	echo "  - All $project_name Docker images"
	echo "  - All $project_name Docker volumes (DATA LOSS!)"
	echo "  - All local data directories"
	echo ""

	read -p "Are you sure? (y/n): " -n 1 -r
	echo

	if [[ $REPLY =~ ^[Yy]$ ]]; then
		stop_containers
		cleanup_volumes --yes
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
	echo "  docker     - Clean Docker resources for this project (containers, images, networks)"
	echo "              Preserves volumes and other projects"
	echo "  volumes    - Clean this project's Docker volumes only (DESTRUCTIVE - deletes all data)"
	echo "  local      - Clean local data directories only"
	echo "  builds     - Clean build artifacts only"
	echo "  secrets    - Rotate docker/secrets files from their templates (overwrites current values)"
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
	echo "  $0 secrets staging     # Regenerate secret files from templates"
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
secrets)
	rotate_secrets
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
