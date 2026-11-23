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
#   <work>/secrets/              one materialised credential in its declared shape, which is what the
#                                gate requires: `redis_password` at 0400 owned by uid 1001
#   <work>/bin/docker            a stub that answers `docker info`, so no daemon is needed
#   <work>/bin/stat              a shim that answers the gate's `stat -c '%a %u %g'` for that one file
#
# The `stat` shim is here for the same reason the `docker` stub is: the credential contract names uids
# (1000, 1001, root) that a test run cannot own a file as, so the one question the gate asks about the
# inode is doubled. The real metadata path and the real permission semantics are covered by
# secrets-ownership.test.sh, which drives a real `stat` for the drift half and real containers for the
# criterion itself.
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

# The credential metadata double, documented above `install_metadata_double` in the header.
METADATA_TSV="$WORK/metadata.tsv"
: >"$METADATA_TSV"
REAL_STAT="$(command -v stat)"
cat >"$WORK/bin/stat" <<SHIM
#!/bin/bash
if [ "\${1:-}" = "-c" ] && [ "\${2:-}" = "%a %u %g" ]; then
	name="\$(basename "\${@: -1}")"
	while IFS='|' read -r n m u g; do
		[ -n "\$n" ] || continue
		if [ "\$n" = "\$name" ]; then printf '%s %s %s\\n' "\$m" "\$u" "\$g"; exit 0; fi
	done <"$METADATA_TSV"
fi
exec "$REAL_STAT" "\$@"
SHIM
chmod +x "$WORK/bin/stat"

# declare_metadata <name> <mode> <uid> <gid> -- the shape the gate will read for that file.
declare_metadata() {
	printf '%s|%s|%s|%s\n' "$1" "$2" "$3" "$4" >>"$METADATA_TSV"
}

# The gate's own preconditions: the four required variables, and one materialised credential in the shape
# its row declares -- `redis_password` is 0400 owned by uid 1001, the api's account.
REQUIRED='COMPOSE_PROJECT_NAME=lc-test
ENVIRONMENT=dev
API_GATEWAY_MANAGEMENT_PORT=9001
LIFECONTROL_API_PORT=8082'
printf '%s\n' "$REQUIRED" >"$WORK/.env.template"
printf 'LOKI_IMAGE=grafana/loki:3.7.8\n' >>"$WORK/.env.template"
printf 'secret\n' >"$WORK/secrets/redis_password"
chmod 0400 "$WORK/secrets/redis_password"
declare_metadata redis_password 400 1001 0

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
assert_contains "the non-strict summary is not reused for the strict verdict" "$LAST_TEXT" "Validation"

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

# A legal spelling of an ALIGNED pin still passes under the flag: this is the case the first version of
# the report got wrong (a quoted value with an inline comment), end to end through the gate.
run_gate '"grafana/loki:3.7.8" # keep pinned' dev --strict-pins
assert_equal "a quoted, commented, aligned pin passes under --strict-pins" "0" "$LAST_RC"
assert_absent "a quoted, commented, aligned pin reports no drift" "$LAST_TEXT" "names differently"

# The environment is the FIRST positional: a later argument is ignored, exactly as before this unit.
rm -f "$WORK/.env.staging"
run_gate "grafana/loki:3.7.8" dev staging
assert_equal "a second positional is ignored, as before this unit" "0" "$LAST_RC"

# `-h`/`--help` were never accepted by this script and still are not: they resolve as an invalid
# environment. Adding a help screen would have changed the exit code of an existing invocation.
PATH="$WORK/bin:$PATH" "$WORK/scripts/validate-env.sh" --help >"$OUT" 2>&1
LAST_RC=$?
assert_equal "--help still exits 1, as before this unit" "1" "$LAST_RC"
assert_contains "--help is still refused as an unknown environment" "$(sed 's/\x1b\[[0-9;]*m//g' "$OUT")" "Invalid environment"

# ------------------------------------------
# 5. The pre-existing failure paths are untouched: the gate still refuses a broken environment with
#    aligned pins, without the flag, and without claiming anything about pins.
# ------------------------------------------
# A credential whose shape drifts is still a failure of its own, and still not blamed on the pins.
: >"$METADATA_TSV"
declare_metadata redis_password 444 1001 0
run_gate "grafana/loki:3.7.8" dev
assert_equal "a broken secret still fails the gate without the flag" "1" "$LAST_RC"
assert_contains "the pre-existing failure is still reported" "$LAST_TEXT" "Validation failed with"
assert_contains "the failure is the credential's mode, and it says which" "$LAST_TEXT" "must be 0400"
assert_absent "a pre-existing failure is not blamed on the pins" "$LAST_TEXT" "Strict pin check failed"
: >"$METADATA_TSV"
declare_metadata redis_password 400 1001 0

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
