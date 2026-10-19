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
# Declared-key report (read-only)
# ------------------------------------------

# Keys a tracked env template declares, one per line, in file order.
#
# Only a `KEY=value` line at column 0 declares a key: the `# KEY=` lines document a
# materialized secret (SMTP_USER) and are not declarations, so they are not reported.
template_declared_keys() {
	local template="$1"
	grep -E '^[A-Za-z_][A-Za-z0-9_]*=' "$template" 2>/dev/null | cut -d= -f1
}

# The compose files a report consults, one absolute path per line.
#
# COMPOSE_FILES (set by resolve_compose_env) names the files actually in force, so a dev
# report can say 'true' for WORKER_ENABLED while a prod report says 'false'. Without it
# every compose file is consulted, base first, so the report still works standalone.
compose_report_files() {
	if [ -n "${COMPOSE_FILES+set}" ] && [ "${#COMPOSE_FILES[@]}" -gt 0 ]; then
		local arg
		for arg in "${COMPOSE_FILES[@]}"; do
			case "$arg" in
			-f) continue ;;
			esac
			printf '%s\n' "$arg"
		done
		return 0
	fi
	printf '%s\n' "$DOCKER_DIR/docker-compose.yml" \
		"$DOCKER_DIR/docker-compose.override.yml" \
		"$DOCKER_DIR/docker-compose.prod.yml"
}

# The value compose falls back to for ${KEY:-value}, last file wins (the order compose
# merges them). Prints nothing when there is no `:-` fallback, which includes the
# ${KEY:?message} form: that one makes compose refuse to start instead of defaulting.
compose_default_for_key() {
	local key="$1"
	local file hit default=""
	while IFS= read -r file; do
		[ -f "$file" ] || continue
		hit="$(grep -hoE '\$\{'"$key"':-[^}]*\}' "$file" 2>/dev/null | tail -1)"
		if [ -n "$hit" ]; then
			hit="${hit#*:-}"
			default="${hit%\}}"
		fi
	done < <(compose_report_files)
	printf '%s' "$default"
}

# 0 when a compose file declares ${KEY:?message} for the key: compose then refuses to start
# without it, so there is no default to report -- the operator is told by compose itself.
compose_key_is_required() {
	local key="$1"
	local file
	while IFS= read -r file; do
		[ -f "$file" ] || continue
		if grep -qE '\$\{'"$key"':\?' "$file" 2>/dev/null; then
			return 0
		fi
	done < <(compose_report_files)
	return 1
}

# Report the keys a template declares that an env file does not carry.
#
# Read-only by contract: it never writes to the env file, and it always returns 0. A
# missing key is legal -- compose supplies a default -- so this is a report, never a gate.
# validate-env.sh is the **production start gate** (deploy.sh refuses to start prod when it
# exits non-zero), so a missing key must never be able to stop a production start; and
# rewriting the env file would destroy the distinction between an operator's declared value
# and a template default, with no commit to audit.
#
# Usage: report_missing_env_keys <env_file> <template_file>
report_missing_env_keys() {
	local env_file="$1"
	local template="$2"

	if [ ! -f "$template" ]; then
		print_warning "Cannot report missing keys: $template not found"
		return 0
	fi
	if [ ! -f "$env_file" ]; then
		print_warning "Cannot report missing keys: $env_file not found (create it with setup-env.sh)"
		return 0
	fi

	# A key declared with an empty value counts as declared: that is an operator decision,
	# and this function never rewrites it.
	local key
	local -a missing=()
	while IFS= read -r key; do
		[ -n "$key" ] || continue
		if ! grep -qE "^${key}=" "$env_file" 2>/dev/null; then
			missing+=("$key")
		fi
	done < <(template_declared_keys "$template")

	if [ "${#missing[@]}" -eq 0 ]; then
		print_success "$env_file declares every key $template defines"
		return 0
	fi

	print_warning "$env_file is missing ${#missing[@]} key(s) declared in $template: the stack runs on compose defaults for them"
	local default
	for key in "${missing[@]}"; do
		default="$(compose_default_for_key "$key")"
		if [ -n "$default" ]; then
			print_warning "  ${key} is not declared: falls back to the compose default '${default}'"
		elif compose_key_is_required "$key"; then
			print_warning "  ${key} is not declared: a compose file requires it (\${${key}:?}), so compose refuses to start without it"
		else
			# Deliberately says only what this function measured. An earlier wording asserted
			# "its own consumer reads it directly", which is false for the keys a dev-only
			# compose file defaults (MAILPIT_PORT, MAILPIT_IMAGE): in this environment nothing
			# reads them and they simply have no default in force. A report that misdescribes
			# how a value is decided is the defect class this report exists to remove.
			print_warning "  ${key} is not declared: no default in the compose files this environment loads"
		fi
	done
	print_status "This is a report, not a failure: copy the missing key into $env_file to declare its value."
	return 0
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

	if [ ! -d "$module_abs" ]; then
		print_error "Module directory not found: $module"
		print_status "Expected build inputs under $module_abs"
		return 1
	fi

	if [ ! -s "$artifact_abs" ]; then
		print_error "Artifact missing or empty: $artifact"
		print_status "Build the module before building its image (see README, Docker scripts)."
		return 1
	fi

	# One list of JAR build inputs drives both checks below so they cannot drift.
	# bootJar packages the main source set only, so src/test/** cannot affect the
	# JAR and must stay out of the mtime scan; Gradle writes bookkeeping such as
	# .gradle/<version>/gc.properties after producing the JAR, so a whole-module
	# scan would always report a freshly built artifact as stale; and build inputs
	# outside src/ (gradle/, buildSrc/, root Gradle files) must stay covered so a
	# silent miss cannot let a stale JAR through.
	local build_inputs=()
	local main_src=""
	if [ -d "$module_abs/src/main" ]; then
		main_src="src/main"
	elif [ -d "$module_abs/src" ]; then
		main_src="src"
	fi
	if [ -n "$main_src" ]; then
		build_inputs+=("$main_src")
	fi
	local gradle_file
	for gradle_file in build.gradle build.gradle.kts settings.gradle settings.gradle.kts gradle.properties; do
		if [ -f "$module_abs/$gradle_file" ]; then
			build_inputs+=("$gradle_file")
		fi
	done
	local gradle_dir
	for gradle_dir in gradle buildSrc; do
		if [ -d "$module_abs/$gradle_dir" ]; then
			build_inputs+=("$gradle_dir")
		fi
	done

	if [ "${#build_inputs[@]}" -eq 0 ]; then
		print_warning "No build inputs found under $module: cannot check $artifact against them by mtime"
		return 0
	fi

	# find roots are absolute; git pathspec entries are repository-relative. Both
	# are derived from build_inputs, the single source of truth.
	local scan_roots=() pathspec=() build_input
	for build_input in "${build_inputs[@]}"; do
		scan_roots+=("$module_abs/$build_input")
		pathspec+=("$module/$build_input")
	done

	# 1) No real build input may be newer than the artifact.
	local newest_source
	newest_source="$(find "${scan_roots[@]}" -type f \
		\( -name '*.java' -o -name '*.gradle' -o -name '*.kts' \
			-o -name '*.properties' -o -name '*.yml' -o -name '*.yaml' \
			-o -name '*.sql' -o -name '*.xml' -o -name '*.json' -o -name '*.kt' \
			-o -name '*.toml' \) \
		-newer "$artifact_abs" -print -quit 2>/dev/null)"

	if [ -n "$newest_source" ]; then
		print_error "Stale artifact: source files are newer than $artifact"
		print_status "Newest: ${newest_source#"$root"/}"
		print_status "Rebuild: (cd $module && ./gradlew bootJar -Pprofile=<env> -x test)"
		return 1
	fi

	# 2) Nor may the last commit touching a build input. Restricting the pathspec
	# to build inputs keeps a documentation-only commit such as a module
	# Dockerfile or README from marking the JAR stale.
	if git -C "$root" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
		local commit_ts artifact_ts
		commit_ts="$(git -C "$root" log -1 --format=%ct -- "${pathspec[@]}" 2>/dev/null || true)"
		artifact_ts="$(file_mtime "$artifact_abs")"
		if [ -n "$commit_ts" ] && [ -n "$artifact_ts" ] && [ "$artifact_ts" -lt "$commit_ts" ]; then
			print_error "Stale artifact: commits touching $module build inputs are newer than $artifact"
			while IFS= read -r line; do
				print_status "$line"
			done < <(git -C "$root" log -3 --format='%h %ad %s' --date=short -- "${pathspec[@]}" 2>/dev/null)
			return 1
		fi
	else
		print_warning "No git repository at $root: cannot compare $artifact against its last commit"
	fi

	return 0
}
