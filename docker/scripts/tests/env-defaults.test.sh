#!/bin/bash
# ============================================
# LifeControl - env default invariant tests
# ============================================
# `docker/.env.template` is the only tracked env file: every `.env.<env>` is a local
# materialization that `setup-env.sh` creates once and never reconciles. A key added to the
# template after that day is therefore *absent* from the operator's env file, and the stack
# silently runs on the compose default instead of on a declared value. That is how the
# access-provisioning worker shipped OFF in every environment with nobody able to see it:
# `WORKER_ENABLED` was one of the 16 keys `docker/.env.dev` did not carry.
#
# Measured on this tree, not inferred: the template declares 88 keys, 80 of them are bound
# by a `${KEY:-...}` / `${KEY:?...}` interpolation in `docker-compose*.yml`, and exactly 8
# are not — the five `SMTP_*` keys (read by `keycloak-setup.sh`'s `get_env`), `ENVIRONMENT`
# (`validate-env.sh`'s `REQUIRED_VARS`), `CIRCUIT_BREAKER_*` (the api-gateway's Spring
# properties) and `GF_AUTH_ANONYMOUS_ORG_ROLE` (the k8s Grafana manifests). Those eight are
# **allowlisted with their reader, never deleted**: "no compose reference" does not mean
# "unused" — `CIRCUIT_BREAKER_WINDOW_SIZE` and `GF_AUTH_ANONYMOUS_ORG_ROLE` were wrongly
# called dead keys once, and this test asserts the consumer instead of assuming there is
# none.
#
# The test also pins the two halves of the fix:
#
#   1. `report_missing_env_keys` (`docker/scripts/_common.sh`) reports the keys an env file
#      is missing and the compose default each one falls back to. It is read-only and it
#      always returns 0, because `validate-env.sh` is the **prod start gate**
#      (`deploy.sh:173-179` aborts the deploy on its non-zero exit): a report may never be
#      able to stop a production start on a condition prod is not expected to satisfy.
#   2. the access-provisioning worker is ON by default in dev
#      (`docker-compose.override.yml`, dev-only) and stays OFF in the base compose and in
#      staging/prod, because it mutates another system with nobody watching.
#
# No Docker daemon, no network, no Gradle: static checks plus a hermetic function drive on
# throwaway trees under `mktemp -d`.
#
# Run: ./docker/scripts/tests/env-defaults.test.sh
# Exits 0 only when every check passes.

# No `set -e`: the cases assert return codes, so each is captured explicitly.
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../_common.sh
source "$SCRIPT_DIR/../_common.sh"

ENV_TEMPLATE="$DOCKER_DIR/.env.template"
COMPOSE_BASE="$DOCKER_DIR/docker-compose.yml"
COMPOSE_OVERRIDE="$DOCKER_DIR/docker-compose.override.yml"
COMPOSE_PROD="$DOCKER_DIR/docker-compose.prod.yml"
VALIDATE_SH="$DOCKER_DIR/scripts/validate-env.sh"
COMMON_SH="$DOCKER_DIR/scripts/_common.sh"
SETUP_ENV_SH="$DOCKER_DIR/scripts/setup-env.sh"

PASS_COUNT=0
FAIL_COUNT=0

TMP_BASE="$(mktemp -d)"

# shellcheck disable=SC2329  # invoked by the EXIT trap below, which shellcheck cannot trace
cleanup() {
	if [ -n "${TMP_BASE:-}" ] && [ -d "$TMP_BASE" ]; then
		rm -rf "$TMP_BASE"
	fi
}
trap cleanup EXIT

pass() {
	printf 'PASS: %s\n' "$1"
	PASS_COUNT=$((PASS_COUNT + 1))
}

fail() {
	printf 'FAIL: %s\n' "$1"
	FAIL_COUNT=$((FAIL_COUNT + 1))
}

# check_equal <description> <expected> <actual>
check_equal() {
	if [ "$2" = "$3" ]; then
		pass "$1 ($3)"
	else
		fail "$1 (expected $2, got $3)"
	fi
}

# assert_contains <name> <needle> <haystack>
assert_contains() {
	if printf '%s' "$3" | grep -qF -- "$2"; then
		pass "$1"
	else
		fail "$1 (missing output: $2)"
		printf '%s\n' "$3" | sed 's/^/        /'
	fi
}

# assert_not_contains <name> <needle> <haystack>
assert_not_contains() {
	if printf '%s' "$3" | grep -qF -- "$2"; then
		fail "$1 (unexpected output: $2)"
		printf '%s\n' "$3" | sed 's/^/        /'
	else
		pass "$1"
	fi
}

# assert_file_contains <description> <file> <needle>
assert_file_contains() {
	local description="$1" file="$2" needle="$3"
	if [ ! -f "$file" ]; then
		fail "$description (missing ${file#"$REPO_ROOT"/})"
		return
	fi
	if grep -qF -- "$needle" "$file"; then
		pass "$description"
	else
		fail "$description (${file#"$REPO_ROOT"/} never mentions '$needle')"
	fi
}

# in_list <needle> <space separated list>
in_list() {
	case " $2 " in
	*" $1 "*) return 0 ;;
	esac
	return 1
}

# ------------------------------------------
# Readers, deliberately independent of the implementation in _common.sh: if the two
# disagreed on what "declared" means, a shared helper would hide exactly that bug.
# ------------------------------------------

# Keys the template declares, one per line. Only a `KEY=value` line at column 0 declares a
# key; the `# KEY=` lines are documentation (SMTP_USER is materialized from a secret).
template_keys() {
	grep -E '^[A-Za-z_][A-Za-z0-9_]*=' "$ENV_TEMPLATE" 2>/dev/null | cut -d= -f1 | sort -u
}

# 0 when the compose files bind the key with ${KEY:-value} (a fallback) or ${KEY:?message}
# (a hard requirement: compose refuses to start instead of running on nothing). The `$` and
# `{` are kept apart on purpose: a `\{` inside a bracket-less ERE would open an interval.
has_compose_binding() {
	grep -qE '\$\{'"$1"'(:-[^}]*|:\?[^}]*)\}' "$DOCKER_DIR"/docker-compose*.yml 2>/dev/null
}

# Every key interpolated in the compose files, one per line.
compose_interpolation_keys() {
	grep -ohE '\$\{[A-Za-z_][A-Za-z0-9_]*(:-[^}]*|:\?[^}]*)?\}' \
		"$DOCKER_DIR"/docker-compose*.yml 2>/dev/null |
		sed -E 's/^\$\{([A-Za-z_][A-Za-z0-9_]*).*/\1/' | sort -u
}

# All values of ${KEY:-...} for one key, one per line.
compose_defaults_for() {
	sed -n 's/.*\${'"$1"':-\([^}]*\)}.*/\1/p' "$DOCKER_DIR"/docker-compose*.yml 2>/dev/null
}

# service_block <compose file> <service name>
service_block() {
	awk -v svc="$2" '
		$0 == "  " svc ":" { inside = 1; print; next }
		inside && /^  [a-zA-Z0-9_-]+:$/ { inside = 0 }
		inside { print }
	' "$1"
}

# The materialization branch of setup-env.sh, extracted verbatim: the `if [ ! -f "$ENV_FILE" ]`
# block that copies .env.template and applies the per-environment seds. Driving that branch in
# a throwaway tree, instead of running setup-env.sh, keeps this test hermetic and offline:
# the script also requires a Docker daemon and writes volume dirs and docker/secrets.
materialize_branch() {
	awk '/^if \[ ! -f "\$ENV_FILE" \]; then$/ { inside = 1 }
		inside { print }
		inside && /^fi$/ { exit }' "$SETUP_ENV_SH"
}

# fresh_materialization <env> -> the VALUE of WORKER_ENABLED in an env file that setup-env.sh's
# own branch creates from the template for that environment. Empty when the branch fails.
fresh_materialization() {
	local env_name="$1"
	local root="$TMP_BASE/materialize-$env_name"
	mkdir -p "$root/docker"
	cp "$ENV_TEMPLATE" "$root/docker/.env.template"
	(
		# The same variables setup-env.sh has when the branch runs.
		DOCKER_DIR="$root/docker"
		ENV="$env_name"
		ENV_FILE="$root/docker/.env.$env_name"
		eval "$(materialize_branch)" >/dev/null 2>&1 || exit 1
		sed -n 's/^WORKER_ENABLED=//p' "$ENV_FILE" | tail -1
	)
}

# The first ${WORKER_ENABLED:-value} in a compose file.
worker_default_in() {
	sed -n 's/.*\${WORKER_ENABLED:-\([^}]*\)}.*/\1/p' "$1" | head -1
}

# The file that really reads a key that has no compose binding. An allowlist entry is only
# honest while this reader still exists and still names the key (see section 2).
allowlist_reader() {
	case "$1" in
	SMTP_*) printf '%s' "$DOCKER_DIR/scripts/keycloak-setup.sh" ;;
	ENVIRONMENT) printf '%s' "$VALIDATE_SH" ;;
	CIRCUIT_BREAKER_*)
		printf '%s' "$REPO_ROOT/api-gateway/src/main/resources/application.properties"
		;;
	GF_AUTH_ANONYMOUS_ORG_ROLE) printf '%s' "$REPO_ROOT/k8s/manifests/infra/grafana.yml" ;;
	*) printf '' ;;
	esac
}

# The frozen allowlist: the keys measured to have no compose binding, each with the reader
# that consumes it. Adding a key here is a reviewed decision, exactly like bumping a pin in
# image-pins.test.sh.
ALLOWLISTED_KEYS="SMTP_HOST SMTP_PORT SMTP_FROM SMTP_STARTTLS SMTP_AUTH ENVIRONMENT CIRCUIT_BREAKER_WINDOW_SIZE GF_AUTH_ANONYMOUS_ORG_ROLE"

# Compose-internal keys: interpolated between compose services (or by the build), never an
# operator knob, so they deliberately do not live in .env.template (image-pins.test.sh pins
# the same fact for LIFECONTROL_POSTGRES_IMAGE). Pinned here so a *typo'd* knob — e.g.
# ${WORKER_ENABLE:-false} next to a template's WORKER_ENABLED — cannot hide as an
# "internal" key, and each entry must stay interpolated with a default compose supplies.
COMPOSE_INTERNAL_KEYS="APP_VERSION GF_AUTH_BASIC_ENABLED GF_SECURITY_ADMIN_USER GIT_COMMIT
KC_HTTPS_PORT KEYCLOAK_INTERNAL_URI LIFECONTROL_API_URI LIFECONTROL_POSTGRES_IMAGE
OTLP_TRACING_ENDPOINT REDIS_HOST REDIS_INTERNAL_PORT REDIS_MAXMEMORY REDIS_MEMORY_LIMIT
WEB_APP_MEMORY_LIMIT"

template_key_list="$(template_keys | tr '\n' ' ')"
internal_key_list="$(printf '%s' "$COMPOSE_INTERNAL_KEYS" | tr '\n' ' ')"

# ------------------------------------------
# 1. Every template key is bound by compose, or allowlisted with a reader that exists.
# ------------------------------------------
echo "== 1. every declared key has a compose binding or an allowlisted reader"
no_binding_keys=""
while IFS= read -r key; do
	[ -n "$key" ] || continue
	if has_compose_binding "$key"; then
		pass "$key is bound in a docker-compose*.yml"
		continue
	fi
	no_binding_keys="$no_binding_keys $key"
	reader="$(allowlist_reader "$key")"
	if [ -z "$reader" ]; then
		fail "$key has no compose binding and no allowlisted reader: nothing declares the value it runs on"
	elif [ ! -f "$reader" ]; then
		fail "$key is allowlisted as read by ${reader#"$REPO_ROOT"/}, which does not exist"
	else
		pass "$key has no compose binding, allowlisted with its reader ${reader#"$REPO_ROOT"/}"
	fi
done <<< "$(template_keys)"

# ------------------------------------------
# 2. The allowlist is exactly the measured set, and every entry still has its consumer.
#    A widened allowlist would silently re-open the defect this test exists for.
# ------------------------------------------
echo "== 2. the allowlist is exactly the measured set and every consumer still exists"
expected_allowlist="$(printf '%s\n' $ALLOWLISTED_KEYS | sort -u | tr '\n' ' ' | sed 's/ $//')"
actual_allowlist="$(printf '%s\n' $no_binding_keys | sed '/^$/d' | sort -u | tr '\n' ' ' | sed 's/ $//')"
check_equal "keys with no compose binding are exactly the frozen allowlist" \
	"$expected_allowlist" "$actual_allowlist"

for key in $ALLOWLISTED_KEYS; do
	reader="$(allowlist_reader "$key")"
	case "$key" in
	SMTP_*)
		# keycloak-setup.sh reads the SMTP channel through `get_env VAR default` and then
		# fails closed outside dev when the value is still the dev default.
		assert_file_contains "$key is read by keycloak-setup.sh's get_env" "$reader" "get_env $key"
		;;
	ENVIRONMENT)
		assert_file_contains "$key is a validate-env.sh REQUIRED_VAR" "$reader" '"ENVIRONMENT"'
		;;
	CIRCUIT_BREAKER_*)
		assert_file_contains "$key is a gateway Spring property default" "$reader" "${key}:"
		;;
	GF_AUTH_ANONYMOUS_ORG_ROLE)
		assert_file_contains "$key is consumed by the k8s Grafana manifests" "$reader" "$key"
		;;
	esac
done

# ------------------------------------------
# 3. No compose interpolation names an undeclared key, unless it is a pinned internal one —
#    and a pinned internal key must still be interpolated and still carry a default, so the
#    allowlist cannot rot into a place where typos hide.
# ------------------------------------------
echo "== 3. every compose interpolation is a declared key or a pinned compose-internal key"
while IFS= read -r key; do
	[ -n "$key" ] || continue
	if in_list "$key" "$template_key_list"; then
		pass "$key is interpolated by compose and declared in .env.template"
		continue
	fi
	if in_list "$key" "$internal_key_list"; then
		if [ -z "$(compose_defaults_for "$key")" ]; then
			fail "$key is a pinned compose-internal key but compose has no \${$key:-default} for it"
		else
			pass "$key is a pinned compose-internal key with a compose default"
		fi
		continue
	fi
	fail "$key is interpolated in a compose file but .env.template never declares it: a real knob would run on a default nobody declared"
done <<< "$(compose_interpolation_keys)"

for key in $COMPOSE_INTERNAL_KEYS; do
	if grep -qE '\$\{'"$key"':' "$DOCKER_DIR"/docker-compose*.yml 2>/dev/null; then
		pass "$key is still interpolated (no rotting allowlist entry)"
	else
		fail "$key is pinned as compose-internal but no compose file interpolates it any more: drop it from the allowlist"
	fi
done

# ------------------------------------------
# 4. report_missing_env_keys: reports, never writes, never fails.
#    Driven directly: validate-env.sh also checks Docker and ports, so it cannot be run
#    end to end here. COMPOSE_FILES is set explicitly per case so the expected default is
#    the one in force for that environment, not an accident of file order.
# ------------------------------------------
echo "== 4. report_missing_env_keys reports, never writes, never fails"
report_dir="$(mktemp -d "$TMP_BASE/report.XXXXXX")"
report_template="$report_dir/.env.template"
report_env="$report_dir/.env.dev"

cat >"$report_template" <<'TEMPLATE'
# A comment line mentions NOT_A_KEY=commented but declares nothing.
COMPOSE_PROJECT_NAME=lifecontrol
ENVIRONMENT=development
WEB_APP_URL=http://localhost:4200
WORKER_ENABLED=false
CIRCUIT_BREAKER_WINDOW_SIZE=5
TEMPLATE

cat >"$report_env" <<'ENVFILE'
COMPOSE_PROJECT_NAME=lifecontrol
ENVIRONMENT=development
ENVFILE

report_before="$(cksum <"$report_env")"

# Dev: base compose first, the dev-only override last (that is the order compose merges).
DEV_OUT="$(
	COMPOSE_FILES=(-f "$COMPOSE_BASE" -f "$COMPOSE_OVERRIDE")
	report_missing_env_keys "$report_env" "$report_template" 2>&1
)"
DEV_RC=$?

check_equal "report returns 0 when keys are missing (a set -e caller keeps running)" 0 "$DEV_RC"
assert_contains "report names a key missing from the env file" "WORKER_ENABLED is not declared" "$DEV_OUT"
assert_contains "report names the compose default the key falls back to" \
	"falls back to the compose default 'true'" "$DEV_OUT"
assert_contains "report names a key that falls back to a base default" \
	"WEB_APP_URL is not declared" "$DEV_OUT"
assert_contains "report names that base default's value" \
	"falls back to the compose default 'http://localhost:4200'" "$DEV_OUT"
assert_contains "report names a key with no compose default" \
	"CIRCUIT_BREAKER_WINDOW_SIZE is not declared" "$DEV_OUT"
assert_contains "report says a key with no default in the files in force is exactly that" \
	"no default in the compose files this environment loads" "$DEV_OUT"
assert_contains "report names the template it compared against" "$report_template" "$DEV_OUT"
assert_not_contains "report never names a key the env file declares" \
	"COMPOSE_PROJECT_NAME is not declared" "$DEV_OUT"
assert_not_contains "report never invents a key from a comment line" "NOT_A_KEY" "$DEV_OUT"
check_equal "report is read-only (env file unchanged)" "$report_before" "$(cksum <"$report_env")"

# Prod: same absent key, but the base default is the one prod runs.
PROD_OUT="$(
	# shellcheck disable=SC2034  # read by compose_report_files, sourced from _common.sh
	COMPOSE_FILES=(-f "$COMPOSE_BASE" -f "$COMPOSE_PROD")
	report_missing_env_keys "$report_env" "$report_template" 2>&1
)"
assert_contains "report follows the compose files of the environment (prod keeps the worker off)" \
	"falls back to the compose default 'false'" "$PROD_OUT"

# No COMPOSE_FILES at all: still a report, still exit 0.
BARE_OUT="$(report_missing_env_keys "$report_env" "$report_template" 2>&1)"
BARE_RC=$?
check_equal "report works when no compose file list was resolved" 0 "$BARE_RC"
assert_contains "report without COMPOSE_FILES still names a missing key" \
	"WORKER_ENABLED is not declared" "$BARE_OUT"

# A caller under `set -e` (validate-env.sh runs that way) must not be aborted by the report.
caller_script="$report_dir/caller.sh"
cat >"$caller_script" <<CALLER
#!/bin/bash
set -e
source "$COMMON_SH"
report_missing_env_keys "$report_env" "$report_template"
echo CALLER_CONTINUED
CALLER
chmod +x "$caller_script"
caller_out="$("$caller_script" 2>&1)"
caller_rc=$?
check_equal "a set -e caller keeps its own exit code" 0 "$caller_rc"
assert_contains "a set -e caller continues past the report" "CALLER_CONTINUED" "$caller_out"

# An absent env file is not this function's error to raise: the caller checks that first.
absent_env="$report_dir/.env.absent"
absent_out="$(report_missing_env_keys "$absent_env" "$report_template" 2>&1)"
absent_rc=$?
check_equal "report returns 0 when the env file does not exist" 0 "$absent_rc"
if [ -f "$absent_env" ]; then
	fail "report created the missing env file (it must never write)"
else
	pass "report never creates the env file it was given"
fi
assert_contains "report says it cannot compare when the env file is absent" \
	"Cannot report" "$absent_out"

# A template with nothing missing says so and stays quiet.
complete_template="$report_dir/.env.complete"
cat >"$complete_template" <<'TEMPLATE'
COMPOSE_PROJECT_NAME=lifecontrol
TEMPLATE
complete_out="$(report_missing_env_keys "$report_env" "$complete_template" 2>&1)"
assert_not_contains "report reports nothing when every declared key is present" \
	"is not declared" "$complete_out"

# ------------------------------------------
# 5. validate-env.sh wires the report as a *warning*: the production start gate cannot be
#    stopped by a missing key. Static, because the script also checks Docker and ports.
# ------------------------------------------
echo "== 5. validate-env.sh reports the keys but never counts them as an error"
validate_call_body="$(
	awk '/^check_declared_vars\(\) \{/ { inside = 1 } inside { print } inside && /^}$/ { exit }' "$VALIDATE_SH"
)"
assert_contains "validate-env.sh calls report_missing_env_keys" \
	"report_missing_env_keys" "$validate_call_body"
assert_not_contains "the report block never touches the error counter" \
	"ERRORS" "$validate_call_body"
call_sites="$(grep -cE '^[[:space:]]*check_declared_vars$' "$VALIDATE_SH" | tr -d ' ')"
check_equal "validate-env.sh calls the report exactly once, from main" "1" "$call_sites"

# ------------------------------------------
# 6. The worker's defaults declare themselves: ON in dev only, OFF everywhere else, and the
#    template's worker block names the consequence of leaving it off.
# ------------------------------------------
echo "== 6. worker defaults: dev on, base/prod off, and the consequence is declared"
override_api="$(service_block "$COMPOSE_OVERRIDE" lifecontrol-api)"
base_api="$(service_block "$COMPOSE_BASE" lifecontrol-api)"

assert_contains "the dev override enables the worker by default" \
	'- WORKER_ENABLED=${WORKER_ENABLED:-true}' "$override_api"
assert_contains "the base compose keeps the worker off by default" \
	'- WORKER_ENABLED=${WORKER_ENABLED:-false}' "$base_api"
assert_not_contains "the base compose never enables the worker" \
	'WORKER_ENABLED:-true' "$base_api"
check_equal "the prod compose overrides no worker switch" "0" \
	"$(grep -c 'WORKER_ENABLED' "$COMPOSE_PROD" | tr -d ' ')"
assert_file_contains "the template declares the worker off" "$ENV_TEMPLATE" "WORKER_ENABLED=false"

# A machine that runs `setup-env.sh dev` for the first time must not get a dev stack with the
# worker off: the freshly created file has to carry the value explicitly, because a declared
# value beats the override's default. staging and prod keep the template's false.
check_equal "a fresh dev env file declares the worker on" "true" "$(fresh_materialization dev)"
check_equal "a fresh staging env file keeps the worker off" "false" "$(fresh_materialization staging)"
check_equal "a fresh prod env file keeps the worker off" "false" "$(fresh_materialization prod)"

# The two places that express dev=on are pinned against each other: changing one and
# forgetting the other fails here, whichever side was edited.
dev_materialized="$(fresh_materialization dev)"
override_worker_default="$(worker_default_in "$COMPOSE_OVERRIDE")"
check_equal "the dev materialization and the dev override default agree" \
	"$dev_materialized" "$override_worker_default"
check_equal "that agreed value is true" "true" "$dev_materialized"

# One key, one reason: a second per-key rewrite in setup-env.sh would be the generalisation
# that S6-C2 rejects, so it fails here instead of growing unnoticed.
worker_sed_lines="$(grep -cE 'sed .*WORKER_ENABLED' "$SETUP_ENV_SH" | tr -d ' ')"
check_equal "setup-env.sh rewrites exactly one worker key, in one place" "1" "$worker_sed_lines"

# Which compose files are in force per environment is what keeps the dev default dev-only.
dev_branch="$(grep -A1 'if \[ "\$ENV" = "dev" \]' "$COMMON_SH")"
assert_contains "the dev-only override is loaded only for dev" \
	"docker-compose.override.yml" "$dev_branch"

# The worker block is prose wrapped at the file's own width, so it is normalised before
# matching: a phrase split across two comment lines still names the consequence.
worker_comment="$(
	awk '/^# ACCESS-PROVISIONING WORKER/ { inside = 1 }
		inside { print }
		inside && /^WORKER_ENABLED=/ { exit }' "$ENV_TEMPLATE" |
		sed -e 's/^# \{0,1\}//' |
		tr '\n' ' ' |
		sed -e 's/  */ /g'
)"
assert_contains "the worker block names what an unapplied task means" \
	"never applied" "$worker_comment"
assert_contains "the worker block names the missing account" \
	"no account is created" "$worker_comment"
assert_contains "the worker block names the missing activation email" \
	"no activation email is sent" "$worker_comment"
assert_contains "the worker block says nothing else reports it" \
	"nothing else reports" "$worker_comment"

# ------------------------------------------
# Summary
# ------------------------------------------
echo "------------------------------------------"
printf 'env-defaults: %s passed, %s failed\n' "$PASS_COUNT" "$FAIL_COUNT"
if [ "$FAIL_COUNT" -ne 0 ]; then
	exit 1
fi
exit 0
