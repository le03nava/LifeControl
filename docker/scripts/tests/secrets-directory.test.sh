#!/bin/bash
# ============================================
# LifeControl - validate-env.sh: a directory in docker/secrets/ is not a secret
# ============================================
# The gate that `deploy.sh prod start` runs before production starts iterates `docker/secrets/*` and
# demands, of every entry, content that is non-empty, free of CHANGEME and mode 0444. A *directory* in
# that folder satisfies none of that, and it is not an operator's mistake: compose bind-mounts
# `./secrets/smtp_password`, and Docker creates an empty directory in place of a bind source that does
# not exist (docker-compose.yml:354-357 says so and states the entrypoint treats it as "no credential").
#
# Measured on 2026-10-29, the gate answered that state with three lines that contradict each other —
# `grep: …: Is a directory`, then `[SUCCESS] … set` for the directory, then `[ERROR] … mode is 755` —
# and failed with one error. A production start would be refused over a file that is not required.
#
# What this suite pins, and why each case is here rather than in a grep:
#
#   1. an optional secret as a directory (no `.template`) is a WARNING and the gate still passes;
#   2. the same entry never earns a `set` line, and `grep`'s own complaint never reaches the operator;
#   3. a secret that a `.template` declares (so `setup-env.sh` materializes it) stays an ERROR, because
#      that is the case where a directory really does mean the secrets were never materialized;
#   4. the four failures that existed before this change still fail: empty, CHANGEME, mode, and
#      "nothing materialized at all";
#   5. a directory does not count as a materialized secret, so a folder holding only that directory
#      still says `No secrets materialized`.
#
# The script runs inside a scratch tree that mirrors the only layout it depends on:
#
#   <work>/.env.dev / .env.template   the env file under test and the template it is compared against
#   <work>/scripts/                   copies of validate-env.sh and _common.sh, so SCRIPT_DIR and
#                                     DOCKER_DIR resolve inside the scratch tree
#   <work>/secrets/                   the folder under test
#   <work>/bin/docker                 a stub that answers `docker info`, so no daemon is needed
#
# No Docker daemon, no network, no compose and no repository file is read or written.
#
# Run: ./docker/scripts/tests/secrets-directory.test.sh
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

# `docker info` is the only Docker call the script makes; the stub answers it, so this suite needs no
# daemon. A `#!/bin/sh` stub is deliberate: validating an env file does not need a real client.
printf '#!/bin/sh\nexit 0\n' >"$WORK/bin/docker"
chmod +x "$WORK/bin/docker"

# The gate's own preconditions: the four required variables, and one image pin that matches its template,
# so the run reaches the secrets check without unrelated failures.
REQUIRED='COMPOSE_PROJECT_NAME=lc-test
ENVIRONMENT=dev
API_GATEWAY_MANAGEMENT_PORT=9001
LIFECONTROL_API_PORT=8082'
printf '%s\n' "$REQUIRED" >"$WORK/.env.dev"
printf 'LOKI_IMAGE=grafana/loki:3.7.8\n' >>"$WORK/.env.dev"
printf '%s\n' "$REQUIRED" >"$WORK/.env.template"
printf 'LOKI_IMAGE=grafana/loki:3.7.8\n' >>"$WORK/.env.template"

# The two names the cases share: one secret a `.template` declares (setup-env.sh materializes it -- a
# missing one is a real misconfiguration) and one that has no template at all (created by hand, read only
# when the feature that needs it is configured). Today the second is `smtp_password`, and
# docker-compose.yml:354 states it is required only when the relay authenticates.
DECLARED='lifecontrol_postgres_password'
OPTIONAL='smtp_password'

# seed_secrets <declared-shape> -- rebuilds the secrets folder around the case's shape.
#   file  the declared secret is a materialized file at 0444, and the optional one is a directory
#   dir   the declared secret is itself a directory
#   only-dir  nothing but the optional secret's directory
seed_secrets() {
	rm -rf "$WORK/secrets"
	mkdir -p "$WORK/secrets"
	printf 'secret\n' >"$WORK/secrets/$DECLARED.template"
	case "$1" in
	file)
		printf 'secret\n' >"$WORK/secrets/$DECLARED"
		chmod 0444 "$WORK/secrets/$DECLARED"
		mkdir "$WORK/secrets/$OPTIONAL"
		;;
	dir)
		mkdir "$WORK/secrets/$DECLARED"
		;;
	only-dir)
		mkdir "$WORK/secrets/$OPTIONAL"
		;;
	esac
}

OUT="$WORK/out.log"
LAST_RC=0
LAST_TEXT=""
run_gate() {
	PATH="$WORK/bin:$PATH" "$WORK/scripts/validate-env.sh" dev >"$OUT" 2>&1
	LAST_RC=$?
	LAST_TEXT="$(sed 's/\x1b\[[0-9;]*m//g' "$OUT")"
}

# ------------------------------------------
# 1. An optional secret as a directory: the gate passes, and says what that directory is.
# ------------------------------------------
seed_secrets file
run_gate
assert_equal "a directory in place of a secret with no template does not fail the gate" "0" "$LAST_RC"
assert_contains "the warning names the entry" "$LAST_TEXT" "$WORK/secrets/$OPTIONAL"
assert_contains "the warning says the entry is a directory" "$LAST_TEXT" "is a directory"
assert_contains "the warning says where the directory comes from" "$LAST_TEXT" "Docker creates it in place of a bind-mount source"
assert_contains "the gate still reports success" "$LAST_TEXT" "Validation passed!"
assert_absent "the directory is not reported as an error" "$LAST_TEXT" "[ERROR]"
assert_absent "the directory never earns a success line of its own" "$LAST_TEXT" "$OPTIONAL set"
assert_absent "grep's own complaint never reaches the operator" "$LAST_TEXT" "Is a directory"

# ------------------------------------------
# 2. A secret a `.template` declares, delivered as a directory: still an error, and one that names the
#    command that materializes it — this is the shape where "directory" means "setup-env.sh never ran".
# ------------------------------------------
seed_secrets dir
run_gate
assert_equal "a directory in place of a templated secret fails the gate" "1" "$LAST_RC"
assert_contains "the error names the entry" "$LAST_TEXT" "$WORK/secrets/$DECLARED"
assert_contains "the error is an error" "$LAST_TEXT" "[ERROR]"
assert_contains "the error names the template that declares it" "$LAST_TEXT" "$DECLARED.template"
assert_contains "the error names the command that materializes it" "$LAST_TEXT" "setup-env.sh"
assert_contains "the gate says it failed" "$LAST_TEXT" "Validation failed"

# ------------------------------------------
# 3. The four failures that existed before this change keep failing, with the same words.
# ------------------------------------------
seed_secrets file
chmod u+w "$WORK/secrets/$DECLARED"
printf 'CHANGEME\n' >"$WORK/secrets/$DECLARED"
chmod 0444 "$WORK/secrets/$DECLARED"
run_gate
assert_equal "a CHANGEME secret still fails the gate" "1" "$LAST_RC"
assert_contains "the CHANGEME failure still names CHANGEME" "$LAST_TEXT" "still contains CHANGEME"

seed_secrets file
chmod 0644 "$WORK/secrets/$DECLARED"
run_gate
assert_equal "a secret at the wrong mode still fails the gate" "1" "$LAST_RC"
assert_contains "the mode failure still names the expected mode" "$LAST_TEXT" "must be 0444"

seed_secrets file
chmod u+w "$WORK/secrets/$DECLARED"
: >"$WORK/secrets/$DECLARED"
chmod 0444 "$WORK/secrets/$DECLARED"
run_gate
assert_equal "an empty secret still fails the gate" "1" "$LAST_RC"
assert_contains "the empty failure still says so" "$LAST_TEXT" "is missing or empty"

# ------------------------------------------
# 4. A directory is not a materialized secret: a folder holding only that directory says so.
# ------------------------------------------
seed_secrets only-dir
run_gate
assert_equal "a folder with only the optional directory fails the gate" "1" "$LAST_RC"
assert_contains "the gate says nothing was materialized" "$LAST_TEXT" "No secrets materialized"

# ------------------------------------------
# 5. The control: a materialized secret at 0444 and nothing else passes, so case 1's warning is provably
#    not what keeps the gate green.
# ------------------------------------------
rm -rf "$WORK/secrets"
mkdir -p "$WORK/secrets"
printf 'secret\n' >"$WORK/secrets/$DECLARED"
chmod 0444 "$WORK/secrets/$DECLARED"
run_gate
assert_equal "a materialized secret alone passes the gate" "0" "$LAST_RC"
assert_absent "a materialized secret produces no directory warning" "$LAST_TEXT" "is a directory"

echo ""
echo "=========================================="
printf 'secrets-directory: %d passed, %d failed\n' "$PASS_COUNT" "$FAIL_COUNT"
echo "=========================================="

[ "$FAIL_COUNT" -eq 0 ]
