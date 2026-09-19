#!/bin/bash
# ============================================
# LifeControl - Shared Script Library
# ============================================
# Source this file at the top of every script:
#   SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
#   source "$SCRIPT_DIR/_common.sh"

# Resolve docker/ directory (absolute path)
DOCKER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Resolve repository root (parent of docker/)
REPO_ROOT="$(cd "$DOCKER_DIR/.." && pwd)"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Output helpers
print_status() { echo -e "${BLUE}[INFO]${NC} $1"; }
print_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
print_warning() { echo -e "${YELLOW}[WARNING]${NC} $1"; }
print_error() { echo -e "${RED}[ERROR]${NC} $1"; }

# Use docker compose v2 (docker-compose v1 not available in WSL2)
DOCKER_COMPOSE=(docker compose)

# ------------------------------------------
# Environment normalisation
# ------------------------------------------

# Normalize raw env argument to canonical name.
# Prints canonical name or "" if unknown.
normalize_env() {
	local raw="${1,,}" # lowercase
	case "$raw" in
	dev | development) echo "dev" ;;
	staging | stg) echo "staging" ;;
	prod | production) echo "prod" ;;
	*) echo "" ;;
	esac
}

# Resolve compose globals from a raw env argument.
# Sets: ENV (canonical), ENV_FILE (absolute), COMPOSE_FILES (flags for -f).
# Invalid env -> exit 1 listing valid envs.
resolve_compose_env() {
	local raw="${1:-dev}"
	local canonical
	canonical=$(normalize_env "$raw")
	if [ -z "$canonical" ]; then
		print_error "Invalid environment: $raw"
		echo "Valid environments: dev|development, staging|stg, prod|production"
		exit 1
	fi

	ENV="$canonical"
	ENV_FILE="$DOCKER_DIR/.env.$ENV"
	COMPOSE_FILES=(-f "$DOCKER_DIR/docker-compose.yml")

	if [ "$ENV" = "dev" ]; then
		COMPOSE_FILES+=(-f "$DOCKER_DIR/docker-compose.override.yml")
	fi
	if [ "$ENV" = "prod" ]; then
		COMPOSE_FILES+=(-f "$DOCKER_DIR/docker-compose.prod.yml")
	fi
}

# Run docker compose with the already-resolved env file and compose files.
# Usage: compose_run up -d   |   compose_run ps
compose_run() {
	"${DOCKER_COMPOSE[@]}" "${COMPOSE_FILES[@]}" --env-file "$ENV_FILE" "$@"
}

# ------------------------------------------
# Env-file reading (pipeline-safe for set -e)
# ------------------------------------------

# Read a variable value from ENV_FILE.
# Uses grep | tail -1 | cut — tail is last in pipeline so exit code 0 even
# when grep finds nothing (avoids set -e trap).
get_env_var() {
	local name="$1"
	local value
	value="$(grep -E "^${name}=" "$ENV_FILE" 2>/dev/null | tail -1 | cut -d= -f2- || true)"
	echo "$value"
}

# ------------------------------------------
# URL printing
# ------------------------------------------

# Print a service URL only when port is non-empty.
# Usage: print_url "Label" "port"
print_url() {
	local label="$1"
	local port="$2"
	if [ -n "$port" ]; then
		echo -e "${GREEN}${label}:${NC}       http://localhost:${port}"
	fi
}

# ------------------------------------------
# Build artifact freshness (pipeline safety)
# ------------------------------------------

# Portable file mtime in seconds since epoch (GNU stat, then BSD/macOS stat).
file_mtime() {
	stat -c %Y "$1" 2>/dev/null || stat -f %m "$1" 2>/dev/null
}

# Verify that a pre-built artifact is not older than the module it was built from.
#
# The Java Dockerfiles do not compile: they COPY a pre-built JAR. Copying a stale
# JAR yields an image that looks new and is not, so the caller must fail here
# instead of shipping it.
#
# Usage: verify_artifact_freshness <module_rel_path> <artifact_rel_path> [<repo_root>]
# Returns 0 when fresh, 1 when missing/empty/stale.
verify_artifact_freshness() {
	local module="$1"
	local artifact="$2"
	local root="${3:-${REPO_ROOT:-$PWD}}"
	local module_abs="$root/$module"
	local artifact_abs="$root/$artifact"

	if [ ! -s "$artifact_abs" ]; then
		print_error "Artefacto ausente o vacío: $artifact"
		print_status "Compilá el módulo antes de construir la imagen (ver README → Docker)."
		return 1
	fi

	# 1) Ninguna fuente del módulo puede ser más nueva que el artefacto.
	local newest_source
	newest_source="$(find "$module_abs" -type f \
		\( -name '*.java' -o -name '*.gradle' -o -name '*.properties' \
			-o -name '*.yml' -o -name '*.yaml' -o -name '*.sql' \) \
		-not -path '*/build/*' -newer "$artifact_abs" -print -quit 2>/dev/null)"

	if [ -n "$newest_source" ]; then
		print_error "Artefacto obsoleto: hay fuentes más nuevas que $artifact"
		print_status "Más reciente: ${newest_source#"$root"/}"
		print_status "Rebuild: (cd $module && ./gradlew bootJar -Pprofile=<env> -x test)"
		return 1
	fi

	# 2) ...ni el último commit que toca el módulo.
	if git -C "$root" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
		local commit_ts artifact_ts
		commit_ts="$(git -C "$root" log -1 --format=%ct -- "$module" 2>/dev/null || true)"
		artifact_ts="$(file_mtime "$artifact_abs")"
		if [ -n "$commit_ts" ] && [ -n "$artifact_ts" ] && [ "$artifact_ts" -lt "$commit_ts" ]; then
			print_error "Artefacto obsoleto: $module tiene commits posteriores a $artifact"
			while IFS= read -r line; do
				print_status "$line"
			done < <(git -C "$root" log -3 --format='%h %ad %s' --date=short -- "$module" 2>/dev/null)
			return 1
		fi
	else
		print_warning "Sin repositorio git en $root: no puedo comparar $artifact contra el último commit"
	fi

	return 0
}
