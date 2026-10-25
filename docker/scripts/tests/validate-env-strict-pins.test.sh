#!/bin/bash
# ============================================
# LifeControl - validate-env.sh strict pin gate tests
# ============================================
# Issue #597 asks for the stale-pin report to be a *warning* by default and a *failure* only when the
# caller asks for it. That decision is the one that can stop a production deploy, and a grep for the
# flag does not pin it: these cases run the real `validate-env.sh` and assert its exit code.
#
# The script is driven inside a scratch tree that mirrors the only layout it depends on:
#
#   <work>/.env.<env>            the materialised env file under test
#   <work>/.env.template         the tracked template it is compared against
#   <work>/scripts/              copies of validate-env.sh and _common.sh, so SCRIPT_DIR and DOCKER_DIR
#                                resolve inside the scratch tree and never touch the repository's files
#   <work>/secrets/              one materialised secret at mode 0444, which is what the gate requires
#   <work>/bin/docker            a stub that answers `docker info`, so no daemon is needed
#
# No Docker daemon, no network, no compose and no repository file is read or written.
#
# Run: ./docker/scripts/tests/validate-env-strict-pins.test.sh
# Exits 0 only when every case passes.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DOCKER_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

PASS_COUNT=0
FAIL_COUNT=0

pass() {
	printf 'PASS: %s\n' "$1"
	PASS_COUNT=$((PASS_COUNT + 1))
}

fail() {
	printf 'FAIL: %s\n' "$1"
	FAIL_COUNT=$((FAIL_COUNT + 1))
}

assert_equal() {
	if [ "$2" = "$3" ]; then
		pass "$1 ($3)"
	else
		fail "$1 (expected $2, got $3)"
	fi
}

assert_contains() {
	if printf '%s' "$2" | grep -qF -- "$3"; then
		pass "$1"
	else
		fail "$1 (the output never mentioned '$3')"
	fi
}

assert_absent() {
	if printf '%s' "$2" | grep -qF -- "$3"; then
		fail "$1 ('$3' appeared in the output)"
	else
		pass "$1"
	fi
}

WORK="$(mktemp -d)"
# shellcheck disable=SC2329  # invoked by the EXIT trap below
cleanup() {
	chmod -R u+rwX "$WORK" 2>/dev/null
	rm -rf "$WORK"
}
trap cleanup EXIT

mkdir -p "$WORK/scripts" "$WORK/secrets" "$WORK/bin"
cp "$DOCKER_DIR/scripts/validate-env.sh" "$DOCKER_DIR/scripts/_common.sh" "$WORK/scripts/"
cp "$DOCKER_DIR/.env.template" "$WORK/.env.template" 2>/dev/null || true

# `docker info` is the only Docker call the script makes; the stub answers it, so this suite needs no
# daemon. A `#!/bin/sh` stub is deliberate: validating an env file does not need a real client.
printf '#!/bin/sh\nexit 0\n' >"$WORK/bin/docker"
chmod +x "$WORK/bin/docker"

# The gate's own preconditions: the four required variables, and one materialised secret at 0444.
REQUIRED='COMPOSE_PROJECT_NAME=lc-test
ENVIRONMENT=dev
API_GATEWAY_MANAGEMENT_PORT=9001
LIFECONTROL_API_PORT=8082'
printf '%s\n' "$REQUIRED" >"$WORK/.env.template"
printf 'LOKI_IMAGE=grafana/loki:3.7.8\n' >>"$WORK/.env.template"
printf 'secret\n' >"$WORK/secrets/db_password"
chmod 0444 "$WORK/secrets/db_password"

OUT="$WORK/out.log"
LAST_RC=0
LAST_TEXT=""
# run_gate <loki image> [extra args...] — writes the env file, runs the gate, captures code and text.
run_gate() {
	local image="$1"
	shift
	printf '%s\n' "$REQUIRED" >"$WORK/.env.dev"
	printf 'LOKI_IMAGE=%s\n' "$image" >>"$WORK/.env.dev"
	PATH="$WORK/bin:$PATH" "$WORK/scripts/validate-env.sh" "$@" >"$OUT" 2>&1
	LAST_RC=$?
	LAST_TEXT="$(sed 's/\x1b\[[0-9;]*m//g' "$OUT")"
}

# ------------------------------------------
# 1. Drift without the flag is a warning: the production gate keeps passing.
# ------------------------------------------
run_gate "grafana/loki:2.9.0" dev
assert_equal "a stale pin without the flag passes the gate" "0" "$LAST_RC"
assert_contains "the stale pin is reported" "$LAST_TEXT" "pins 1 image(s)"
assert_contains "the report names the key" "$LAST_TEXT" "LOKI_IMAGE"
assert_contains "the report names both values" "$LAST_TEXT" "grafana/loki:2.9.0"
assert_contains "the report names the template value" "$LAST_TEXT" "grafana/loki:3.7.8"
assert_contains "the gate says it validated" "$LAST_TEXT" "Validation passed!"
assert_absent "the non-strict run never reports a strict failure" "$LAST_TEXT" "Strict pin check failed"

# ------------------------------------------
# 2. Drift WITH the flag fails, and says why in its own words.
# ------------------------------------------
run_gate "grafana/loki:2.9.0" dev --strict-pins
assert_equal "a stale pin with --strict-pins fails the gate" "1" "$LAST_RC"
assert_contains "the strict verdict names the count" "$LAST_TEXT" "Strict pin check failed: 1 image pin(s)"
assert_absent "the strict verdict is not dressed up as a generic validation error" "$LAST_TEXT" "Validation failed with 0 error(s)"

# ------------------------------------------
# 3. The environment stays the first positional argument it always was, and the flag is accepted in any
#    order — a caller that puts it first must not silently get a non-strict run.
# ------------------------------------------
run_gate "grafana/loki:2.9.0" --strict-pins dev
assert_equal "--strict-pins before the environment also fails" "1" "$LAST_RC"
assert_contains "the flag-first run still reports the drift" "$LAST_TEXT" "pins 1 image(s)"

# ------------------------------------------
# 4. An aligned file passes even under the strict flag: the flag alone is not a failure.
# ------------------------------------------
run_gate "grafana/loki:3.7.8" dev --strict-pins
assert_equal "an aligned file passes under --strict-pins" "0" "$LAST_RC"
assert_contains "an aligned file is reported as aligned" "$LAST_TEXT" "same image versions"
# The script's other checks (volume directory, undeclared keys) legitimately warn, so the assertion
# names the pin warning's own phrase instead of the word WARNING.
assert_absent "an aligned file never reports pin drift" "$LAST_TEXT" "names differently"

# ------------------------------------------
# 5. The pre-existing failure paths are untouched: the gate still refuses a broken environment with
#    aligned pins, without the flag, and without claiming anything about pins.
# ------------------------------------------
chmod 0644 "$WORK/secrets/db_password"
run_gate "grafana/loki:3.7.8" dev
assert_equal "a broken secret still fails the gate without the flag" "1" "$LAST_RC"
assert_contains "the pre-existing failure is still reported" "$LAST_TEXT" "Validation failed with"
assert_absent "a pre-existing failure is not blamed on the pins" "$LAST_TEXT" "Strict pin check failed"
chmod 0444 "$WORK/secrets/db_password"

rm -f "$WORK/.env.dev"
PATH="$WORK/bin:$PATH" "$WORK/scripts/validate-env.sh" dev >"$OUT" 2>&1
LAST_RC=$?
assert_equal "a missing env file still fails the gate" "1" "$LAST_RC"

# ------------------------------------------
# 6. An unknown environment is still refused, flag or not.
# ------------------------------------------
PATH="$WORK/bin:$PATH" "$WORK/scripts/validate-env.sh" nope --strict-pins >"$OUT" 2>&1
LAST_RC=$?
assert_equal "an unknown environment is refused with the flag present" "1" "$LAST_RC"

echo "------------------------------------------"
printf 'validate-env-strict-pins: %s passed, %s failed\n' "$PASS_COUNT" "$FAIL_COUNT"
[ "$FAIL_COUNT" -eq 0 ] || exit 1
exit 0
