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
# Bind-mounted state (${VOLUMES_ROOT})
# ------------------------------------------
# The containers' state is NOT in Docker volumes: compose mounts host directories under
# ${VOLUMES_ROOT} into postgres, keycloak (the realm copies), redis, loki, prometheus, tempo and
# grafana, and `docker compose down -v` removes NAMED volumes only -- a bind mount is a host
# directory and compose never touches it. Any command or report that says it cleaned "the volumes"
# therefore has to answer for these paths explicitly, and exactly one function resolves the root so
# that every script answers with the same path.

# Absolute host path of this environment's bind-mount root. Prints nothing when the env file does not
# declare VOLUMES_ROOT; compose then refuses to start, which is a different and louder failure.
volumes_root_host_path() {
	local vol_root
	vol_root="$(get_env_var VOLUMES_ROOT)"
	[ -n "$vol_root" ] || return 0
	case "$vol_root" in
	/*) printf '%s' "$vol_root" ;;
	*) printf '%s' "$DOCKER_DIR/${vol_root#./}" ;;
	esac
}

# True (0) when VOLUMES_ROOT points outside the checkout. prod sets it to
# /var/lib/lifecontrol/volumes, and the state of a production host is not something a convenience
# command deletes: the scripts name the path and leave the deletion to the operator.
volumes_root_is_outside_repo() {
	case "$(get_env_var VOLUMES_ROOT)" in
	/*) return 0 ;;
	*) return 1 ;;
	esac
}

# The directories that exist directly under the root, one name per line, sorted: the bind mounts that
# survive `down -v`. setup-env.sh creates exactly one directory per service under this root, so the
# answer needs no second list to maintain.
volumes_root_entries() {
	local root="${1:-}"
	local entry
	if [ -z "$root" ] || [ ! -d "$root" ]; then
		return 0
	fi
	for entry in "$root"/*/; do
		[ -d "$entry" ] || continue
		basename "$entry"
	done | sort
}

# The relative directory this environment owns, as setup-env.sh writes it (./volumes-<env>). The
# deletion path and its refusal both name it, so the name cannot drift from the value they compare.
volumes_root_env_dir_name() {
	printf 'volumes-%s' "$ENV"
}

# True (0) when `--data` may delete this root: it is this environment's own data directory, that is,
# a real directory (not a symlink) that resolves to $DOCKER_DIR/volumes-<env>.
#
# A strict-descendant test is not enough. `VOLUMES_ROOT=.` resolves to docker/ itself, `./secrets`
# resolves to the credential files, and `./volumes-dev/../volumes-staging` resolves to another
# environment's live database: each is one typo in an env file away, and this deletion is
# irreversible. Anything that is not exactly this environment's directory is left to the operator.
volumes_root_is_deletable() {
	local root="${1:-}"
	local resolved expected
	[ -n "$root" ] && [ -d "$root" ] && [ ! -L "$root" ] || return 1
	resolved="$(cd -- "$root" && pwd -P)" || return 1
	expected="$(cd -- "$DOCKER_DIR" && pwd -P)/$(volumes_root_env_dir_name)"
	[ "$resolved" = "$expected" ]
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
# Stale image pin report (read-only)
# ------------------------------------------

# The regex that matches a declaration of one key in an env-style file, as compose matches it:
# optional leading whitespace, an optional `export `, and optional whitespace around the `=`.
#
# The two readers below take this string instead of each spelling the pattern themselves: they must
# agree on what "declared" means, and the first version of this code did not -- fixing the value
# reader alone left the declaration guard on the old `^KEY=` form, so `export KEY=v` and `KEY = v`
# were read correctly and then skipped as undeclared, which is a false NEGATIVE that hides real drift.
#
# Usage: env_key_declaration_pattern <key>
env_key_declaration_pattern() {
	printf '^[[:space:]]*(export[[:space:]]+)?%s[[:space:]]*=' "$1"
}

# The value a `KEY=value` line gives a key in an env-style file, last declaration winning (the same
# precedence --env-file applies). Reads an arbitrary file, unlike get_env_var, which is bound to
# ENV_FILE. Prints nothing when the file does not declare the key.
#
# Usage: env_file_value <file> <key>
env_file_value() {
	local file="$1"
	local key="$2"
	local value
	value="$(awk -v pattern="$(env_key_declaration_pattern "$key")" '
		{
			line = $0
			sub(/\r$/, "", line)
			if (line ~ pattern) {
				sub(pattern, "", line)
				value = line
			}
		}
		END { printf "%s", value }
	' "$file" 2>/dev/null || true)"
	normalize_env_value "$value"
}

# 0 when the file declares the key at all, whatever its value -- including an empty one. Same pattern,
# same file, same answer as env_file_value.
#
# Usage: env_file_declares_key <file> <key>
env_file_declares_key() {
	local file="$1"
	local key="$2"
	awk -v pattern="$(env_key_declaration_pattern "$key")" '
		{
			line = $0
			sub(/\r$/, "", line)
			if (line ~ pattern) { found = 1; exit }
		}
		END { exit(found ? 0 : 1) }
	' "$file" 2>/dev/null
}

# The value compose actually reads from an env-file line: surrounding whitespace trimmed, a quoted
# value unwrapped (`LOKI_IMAGE="grafana/loki:3.7.8"` pins the same image as the bare form, with or
# without a trailing ` # comment`) and an inline ` # comment` dropped when it follows whitespace --
# inside quotes it stays part of the value, which is why a quoted value is read up to its closing
# quote and stops there.
#
# This exists because comparing raw text reports a legal spelling as drift, and `--strict-pins` would
# then fail a file that is correct. A report that cries wolf on a quoted value is a report that gets
# switched off.
#
# Usage: normalize_env_value <raw value>
normalize_env_value() {
	local value="${1%$'\r'}"
	# Trim leading then trailing whitespace without external tools.
	value="${value#"${value%%[![:space:]]*}"}"
	value="${value%"${value##*[![:space:]]}"}"
	case "$value" in
	\"*)
		# Quoted: the value is what is between the quotes; anything after the closing quote
		# (an inline comment in practice) is not part of it.
		value="${value#\"}"
		value="${value%%\"*}"
		;;
	\'*)
		value="${value#\'}"
		value="${value%%\'*}"
		;;
	*)
		value="${value%%[[:space:]]#*}"
		value="${value%"${value##*[![:space:]]}"}"
		;;
	esac
	printf '%s' "${value%$'\r'}"
}

# The `*_IMAGE` keys a template declares, one per line, sorted so a report reads the same twice.
# Only the pins: ports and memory limits legitimately differ per environment.
#
# Usage: template_image_keys <template>
template_image_keys() {
	local template="$1"
	grep -E '^[A-Za-z_][A-Za-z0-9_]*_IMAGE=' "$template" 2>/dev/null | cut -d= -f1 | sort -u
}

# Divergent pins the last report_stale_image_pins call found. Set to 0 by every call, so a caller
# that wants to be strict reads the count of the run it just made and never a stale one.
#
# Call the report DIRECTLY, never in a command substitution: `out=$(report_stale_image_pins ...)`
# runs it in a subshell, so the report prints but this counter never reaches the caller and a strict
# gate silently reads 0. Redirect to a file when the text is also needed.
STALE_IMAGE_PIN_COUNT=0

# Report the `*_IMAGE` keys an env file pins at a value the template names differently.
#
# The env files are gitignored, materialised once by setup-env.sh and never reconciled, so a pin
# that moves in the template leaves the old image behind in a file that still reads as correct.
# That is how Tempo 2.3.0 and Loki 2.9.0 survived in staging and prod -- the two generations behind
# the observability incident -- while every versioned place said otherwise (issue #597).
#
# Read-only by contract, and always returns 0: validate-env.sh is the **production start gate**
# (deploy.sh refuses to start prod when it exits non-zero), so drift has to be visible without being
# able to stop a start. A caller that wants a failure asks for it and reads STALE_IMAGE_PIN_COUNT.
#
# Only pins the env file declares are compared. An undeclared pin is not drift: it runs the compose
# default, and image-pins.test.sh pins every compose default against the template.
#
# Usage: report_stale_image_pins <env_file> <template_file>
report_stale_image_pins() {
	local env_file="$1"
	local template="$2"

	STALE_IMAGE_PIN_COUNT=0

	if [ ! -f "$template" ]; then
		print_warning "Cannot report stale image pins: $template not found"
		return 0
	fi
	if [ ! -f "$env_file" ]; then
		print_warning "Cannot report stale image pins: $env_file not found (create it with setup-env.sh)"
		return 0
	fi

	local key env_value template_value compared=0
	local -a stale_keys=() stale_env=() stale_template=()
	while IFS= read -r key; do
		[ -n "$key" ] || continue
		env_file_declares_key "$env_file" "$key" || continue
		env_value="$(env_file_value "$env_file" "$key")"
		# A declared-but-empty value is not drift: the compose files use `${KEY:-default}`, which
		# substitutes on empty as well as on unset, so the pinned default is what would run -- and
		# image-pins.test.sh pins every compose default against this template.
		[ -n "$env_value" ] || continue
		compared=$((compared + 1))
		template_value="$(env_file_value "$template" "$key")"
		if [ "$env_value" != "$template_value" ]; then
			stale_keys+=("$key")
			stale_env+=("$env_value")
			stale_template+=("$template_value")
		fi
	done <<< "$(template_image_keys "$template")"

	if [ "$compared" -eq 0 ]; then
		print_status "$env_file declares no image pin: every image comes from the compose defaults"
		return 0
	fi

	if [ "${#stale_keys[@]}" -eq 0 ]; then
		print_success "$env_file pins the same image versions as $template ($compared compared)"
		return 0
	fi

	STALE_IMAGE_PIN_COUNT="${#stale_keys[@]}"
	print_warning "$env_file pins ${STALE_IMAGE_PIN_COUNT} image(s) that $template names differently: this environment would run them and nothing else would say so"
	local i
	for i in "${!stale_keys[@]}"; do
		print_warning "  ${stale_keys[$i]}: $env_file has '${stale_env[$i]}', $template has '${stale_template[$i]}'"
	done
	print_status "This is a report, not a failure: update the value in $env_file (setup-env.sh never rewrites it), or keep it deliberately."
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

# ------------------------------------------
# Observability probes (parsing only)
# ------------------------------------------
# The stack collected three signals and could vouch for none of them: `docker ps` said every
# container was running while Tempo answered `vParquet2 is not a valid block version` for every
# trace it had flushed (HTTP 500 on /api/traces/<id>), so the trace store was readable in the index
# and unreadable in fact. These two parsers and the probes in deploy.sh exist because a running
# process is not a working signal.
#
# Both read a file instead of the network so the suites can feed them fixtures: the Docker
# toolchain has no JSON parser, and a probe whose verdict is only testable by breaking a live stack
# never gets tested at all.

# Jobs Prometheus reports as scrapeable-but-failing, one per line, from the JSON of
# `GET /api/v1/query?query=up == 0`.
#
# Reading `up == 0` instead of `/api/v1/targets` is deliberate: every result object of that query
# carries exactly one `job` label, so the name of the failing target is extracted without pairing
# fields inside a target object — no dependence on key order and no object splitting in shell.
# Fails closed: a response that is not `"status":"success"` prints `unreadable`, because a probe
# that treats a broken answer as "nothing is failing" is the bug this whole unit is about.
prometheus_not_up_jobs() {
	local response_file="$1"
	if ! grep -q '"status":"success"' "$response_file" 2>/dev/null; then
		printf 'unreadable\n'
		return 0
	fi
	grep -o '"job":"[^"]*"' "$response_file" 2>/dev/null | cut -d'"' -f4 | sort -u
}

# The most recent trace id a Tempo `/api/search` response carries, or nothing when the store holds
# no trace yet.
#
# Empty output is a legal state, not a failure: a freshly installed stack has no trace to read until
# traffic arrives, and the caller says so out loud instead of reporting a broken read.
tempo_latest_trace_id() {
	local response_file="$1"
	grep -o '"traceID":"[0-9a-f]*"' "$response_file" 2>/dev/null | head -1 | cut -d'"' -f4
}

# Prometheus reports every target up, and Tempo hands back a trace it has already flushed.
#
# `docker ps` cannot see either fact, and this stack has already served a green health check over a
# trace store that answered HTTP 500 for every trace on disk (`vParquet2 is not a valid block
# version`). The signal was collected, the container was healthy, and the data was unreadable —
# which is the one combination a health check exists to catch.
#
# Reads `up == 0` from Prometheus' query API because each result object carries exactly one `job`
# label, so the name of the failing target needs no field pairing in shell; see
# `prometheus_not_up_jobs` in _common.sh. Prints one ` <name>(<detail>)` token per failing signal and
# returns non-zero when at least one did. Ports that are not configured are skipped, and a trace
# store with nothing in it is announced on stderr instead of failing: a freshly installed stack has
# no trace to read until traffic arrives.
host_observability_probes() {
	local failures="" prom_port tempo_port
	prom_port="$(get_env_var PROMETHEUS_PORT)"
	tempo_port="$(get_env_var TEMPO_PORT)"

	local work
	if ! work="$(mktemp -d)"; then
		printf ' observability(no-temp-dir)'
		return 1
	fi
	# shellcheck disable=SC2064 # expand $work now: the trap fires after it has left scope
	trap "rm -rf '$work'" RETURN

	if [ -n "$prom_port" ]; then
		if curl -sf --max-time 5 -o "$work/up.json" \
			"http://127.0.0.1:${prom_port}/api/v1/query?query=up%20%3D%3D%200"; then
			local job
			while IFS= read -r job; do
				[ -n "$job" ] || continue
				case "$job" in
				unreadable) failures="$failures Prometheus(report-unreadable)" ;;
				*) failures="$failures Prometheus(${job}-not-up)" ;;
				esac
			done <<< "$(prometheus_not_up_jobs "$work/up.json")"
		else
			failures="$failures Prometheus(no-api)"
		fi
	fi

	if [ -n "$tempo_port" ]; then
		# The window is the probe: it asks for a trace between 24h and 2h old, which by then lives in
		# a flushed block. Reading the newest trace instead passes on exactly the state this probe
		# exists to catch -- measured on the broken stack, `limit=1` answered 200 from the ingester
		# while every trace older than the last flush answered 500.
		local start_ts end_ts
		start_ts=$(($(date +%s) - 86400))
		end_ts=$(($(date +%s) - 7200))
		if curl -sf --max-time 5 -o "$work/search.json" \
			"http://127.0.0.1:${tempo_port}/api/search?start=${start_ts}&end=${end_ts}&limit=1"; then
			local trace_id code
			trace_id="$(tempo_latest_trace_id "$work/search.json")"
			if [ -z "$trace_id" ]; then
				printf 'Tempo has no flushed trace in the probe window (24h to 2h ago): nothing to read yet.\n' >&2
			elif code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 \
				"http://127.0.0.1:${tempo_port}/api/traces/${trace_id}")" && [ "$code" = "200" ]; then
				:
			else
				failures="$failures Tempo(trace-not-readable:${code:-unknown})"
			fi
		else
			failures="$failures Tempo(no-api)"
		fi
	fi

	printf '%s\n' "$failures"
	[ -z "$failures" ]
}

# ------------------------------------------
# Environment flag helpers
# ------------------------------------------

# 0 when an environment flag is set to a truthy value.
#
# One implementation for every `VAR=1|true|yes` switch, so two switches can never disagree about
# what "on" means -- the case-insensitive spelling SKIP_BUILD established. An empty value is off.
#
# Usage: env_flag_is_truthy <value>
env_flag_is_truthy() {
	local value="${1:-}"
	case "${value,,}" in
	1 | true | yes) return 0 ;;
	*) return 1 ;;
	esac
}

# ------------------------------------------
# Image revision evidence (read-only)
# ------------------------------------------

# The OCI annotation the three application Dockerfiles bake with the revision they were built from.
# It is the only claim an image makes about its own code, and until this section existed nothing in
# the repository read it back.
IMAGE_REVISION_LABEL="org.opencontainers.image.revision"

# What compose interpolates for ${GIT_COMMIT:-unknown} when the variable is not exported -- the
# path a direct `docker compose build` takes. It is the absence of evidence, never a match.
IMAGE_REVISION_UNKNOWN="unknown"

# The suffix a revision carries when the image was built from a tree with uncommitted changes, in
# the `git describe` spelling, so a revision stays one readable token.
IMAGE_REVISION_DIRTY_SUFFIX="-dirty"

# The verdicts image_revision_verdict can print, in the order they dominate each other.
# `dirty` beats `drift`: an image built from a dirty tree carries code no commit carries, which is
# true whatever revision sits under the suffix.
# shellcheck disable=SC2034  # read by image-revision.test.sh, which sources this file to hold the vocabulary stable
IMAGE_REVISION_VERDICTS="match drift dirty unknown absent"

# Services whose images are built locally from this repository and therefore carry the label.
#
# One compose service name per line. The **image name** is deliberately not here: compose names a
# built image `<project>-<service>`, and the project is per environment, so asking compose which
# image a container runs is the only way to name it without hardcoding one convention per service.
image_revision_services() {
	printf '%s\n' api-gateway lifecontrol-api web-app
}

# The commit HEAD points at in the repository these scripts live in. Prints nothing when git cannot
# answer, so a caller can tell "no evidence" from "a revision".
current_git_revision() {
	git -C "${REPO_ROOT:-$PWD}" rev-parse HEAD 2>/dev/null || true
}

# 0 when the working tree carries changes no commit has. Ignored paths (build output, the
# materialized .env files) are not changes: they are what running this stack produces, and counting
# them would report every image as dirty on every machine.
git_tree_is_dirty() {
	local changes
	changes="$(git -C "${REPO_ROOT:-$PWD}" status --porcelain 2>/dev/null || true)"
	[ -n "$changes" ]
}

# The image a service's container actually runs, **by ID**.
#
# By ID on purpose: a tag is a pointer that a later build can move, so a tag-based check answers
# "which image would run", not "which image is running". Prints nothing when the service has no
# **running** container, which the verdict reports as `absent` instead of guessing.
#
# Usage: service_running_image_id <compose service>
service_running_image_id() {
	local service="$1"
	local container_id image_id
	# No `-a`: a stopped container is not the image that is running. Including it made `version`
	# report a stopped stack as `match` with a zero exit code -- the answer reversed.
	container_id="$(compose_run ps -q "$service" 2>/dev/null | head -1 || true)"
	[ -n "$container_id" ] || return 0
	image_id="$(docker inspect --format '{{.Image}}' "$container_id" 2>/dev/null || true)"
	printf '%s' "$image_id"
}

# The revision label of an image, read by ID. Prints nothing when the image or the label is absent.
#
# Usage: image_revision_label <image id>
image_revision_label() {
	local image_id="$1"
	[ -n "$image_id" ] || return 0
	docker image inspect "$image_id" \
		--format '{{index .Config.Labels "'"$IMAGE_REVISION_LABEL"'"}}' 2>/dev/null || true
}

# `sha256:d740bd21c95d078b...` -> `d740bd21c95d`, for a one-line report.
short_digest() {
	local value="${1#sha256:}"
	printf '%s' "${value:0:12}"
}

# The revision a build stamps into an image: HEAD, plus the dirty suffix when the tree carries
# changes no commit has, or `unknown` when git cannot answer at all.
#
# A build from a dirty tree is NOT the commit under it, and an image stamping the bare HEAD would
# claim a provenance no commit supports. The `-dirty` suffix is git's own spelling and keeps the
# value one token that image_revision_verdict can read back.
image_revision_stamp() {
	local revision
	revision="$(current_git_revision)"
	if [ -z "$revision" ]; then
		printf '%s' "$IMAGE_REVISION_UNKNOWN"
		return 0
	fi
	if git_tree_is_dirty; then
		revision="${revision}${IMAGE_REVISION_DIRTY_SUFFIX}"
	fi
	printf '%s' "$revision"
}

# The verdict of one image against the commit that is checked out. Pure: no docker, no git, no
# filesystem, so every branch of it is testable without a daemon.
#
# Usage: image_revision_verdict <label> <expected revision> <present: 1|0>
# Prints one of IMAGE_REVISION_VERDICTS.
image_revision_verdict() {
	local label="$1"
	local expected="${2:-}"
	local present="${3:-1}"

	# Nothing to inspect is not a pass: a service that was never created has no code of record.
	if [ "$present" != "1" ]; then
		printf 'absent'
		return 0
	fi

	# An image with no label, or the literal `unknown`, cannot be compared to anything. Reporting it
	# as a match is the defect this whole section exists to remove.
	if [ -z "$label" ] || [ "$label" = "$IMAGE_REVISION_UNKNOWN" ]; then
		printf 'unknown'
		return 0
	fi

	case "$label" in
	*"$IMAGE_REVISION_DIRTY_SUFFIX")
		printf 'dirty'
		return 0
		;;
	esac

	# An unreadable HEAD gives no basis for a match either.
	if [ -z "$expected" ] || [ "$label" != "$expected" ]; then
		printf 'drift'
		return 0
	fi

	printf 'match'
}

# Images whose revision is not proven to be HEAD. Set to 0 by every report_image_revisions call, so
# a caller that wants to be strict reads the count of the run it just made.
#
# Call the report DIRECTLY, never in a command substitution: `out=$(report_image_revisions)` runs it
# in a subshell, so the report prints but this counter never reaches the caller and a strict gate
# silently reads 0. Redirect to a file when the text is also needed.
IMAGE_REVISION_MISMATCH_COUNT=0

# Report the revision each locally built application image was built from, against HEAD.
#
# Read-only by contract, and always returns 0: it exists to be seen, and the caller that wants a
# failure reads IMAGE_REVISION_MISMATCH_COUNT (validate-env.sh is the production start gate, so a
# report here must never be able to stop a start on its own).
#
# Prints one aligned line per service plus a summary, and one extra line when the working tree is
# dirty -- an image cannot carry changes nobody committed, and saying so is cheaper than the
# question it prevents.
report_image_revisions() {
	IMAGE_REVISION_MISMATCH_COUNT=0

	local expected
	expected="$(current_git_revision)"
	if [ -z "$expected" ]; then
		# No readable HEAD proves nothing, and "nothing proven" is not "everything matches". Counting
		# zero here let `version` exit 0 and the strict health guard pass over a stack no image was
		# ever compared to a commit -- the exact silent pass this report exists to remove. Every
		# application service counts as not proven, so both callers fail closed.
		IMAGE_REVISION_MISMATCH_COUNT="$(image_revision_services | grep -c . || true)"
		print_warning "Cannot read the repository revision: no running image can be tied to a commit"
		print_warning "${IMAGE_REVISION_MISMATCH_COUNT} application image(s) count as NOT proven to be HEAD (fail closed)"
		return 0
	fi

	print_status "Image revisions for ${ENV:-this environment} (HEAD $(short_digest "$expected")):"

	local service image_id label verdict
	local -a mismatched=()
	while IFS= read -r service; do
		[ -n "$service" ] || continue
		image_id="$(service_running_image_id "$service")"
		label="$(image_revision_label "$image_id")"
		if [ -n "$image_id" ]; then
			verdict="$(image_revision_verdict "$label" "$expected" 1)"
		else
			verdict="$(image_revision_verdict "$label" "$expected" 0)"
		fi

		printf '  %-18s %-14s %-28s %s\n' \
			"$service" "$(short_digest "$image_id")" "${label:-<none>}" "$verdict"

		if [ "$verdict" != "match" ]; then
			mismatched+=("$service")
		fi
	done <<< "$(image_revision_services)"

	if git_tree_is_dirty; then
		print_warning "The working tree has uncommitted changes: no image carries them."
	fi

	if [ "${#mismatched[@]}" -eq 0 ]; then
		print_success "Every running application image was built from HEAD ($(short_digest "$expected"))."
		return 0
	fi

	IMAGE_REVISION_MISMATCH_COUNT="${#mismatched[@]}"
	print_warning "${IMAGE_REVISION_MISMATCH_COUNT} application image(s) are NOT proven to be HEAD: ${mismatched[*]}"
	print_warning "  matching:   the image was built from this commit"
	print_warning "  drift:      the image was built from another commit (rebuild it)"
	print_warning "  dirty:      the image was built from uncommitted changes (rebuild from a clean tree)"
	print_warning "  unknown:    the image carries no revision (built without deploy.sh, or before this check)"
	print_warning "  absent:     the service has no container (start it)"
	print_status "This is a report, not a failure: deploy.sh <env> version exits non-zero on the same evidence."
	return 0
}
