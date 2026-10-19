#!/bin/bash
# ============================================
# LifeControl - missing-key report robustness tests
# ============================================
# Independent probes for S6-C1 / S6-C2 that the shipped env-defaults.test.sh does not
# cover: malformed or adversarial inputs to report_missing_env_keys must stay a no-op
# that returns 0, and the report must never write the env file.
#
# C1's stake is specific: validate-env.sh is the production start gate (deploy.sh
# refuses to start prod when it exits non-zero), so an input that made the report
# return non-zero, or that tripped `set -e` inside validate-env.sh, would be able to
# stop a production deploy. No Docker, no network: every case is a scratch file.
#
# Run: ./docker/scripts/tests/env-report-robustness.test.sh
# Exits 0 only when every case passes.
#
# No `set -e`: the cases assert return codes explicitly, so each one is captured.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../_common.sh
source "$SCRIPT_DIR/../_common.sh"

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

TMP_BASE="$(mktemp -d)"
# shellcheck disable=SC2329  # invoked by the EXIT trap below
cleanup() {
	rm -rf "$TMP_BASE"
}
trap cleanup EXIT

tpl="$TMP_BASE/tpl"
env="$TMP_BASE/env"

# A template that exercises the shapes a real env file can be handed: a key that is a
# prefix of another, an empty value, and lines whose names are not identifiers (the
# extraction must ignore them rather than interpolate them into a grep pattern).
printf 'GOOD=1\nEMPTY=\nA=1\nAB=2\nWEIRD-KEY=1\nWEIRD.KEY=2\n1NUM=3\nTRICKY=${FOO}\n' >"$tpl"
printf 'GOOD=1\nEMPTY=\nA=\n' >"$env"

# --- the report never changes a caller's exit code ---------------------------------

out="$(report_missing_env_keys "$env" "$tpl" 2>&1)"
rc=$?
if [ "$rc" -eq 0 ]; then
	pass "the report returns 0 on an ordinary missing-key run"
else
	fail "the report returned $rc on an ordinary missing-key run"
fi

out="$(report_missing_env_keys "$TMP_BASE/absent.env" "$tpl" 2>&1)"
rc=$?
if [ "$rc" -eq 0 ]; then
	pass "the report returns 0 when the env file is absent"
else
	fail "the report returned $rc when the env file is absent"
fi

out="$(report_missing_env_keys "$env" "$TMP_BASE/absent.tpl" 2>&1)"
rc=$?
if [ "$rc" -eq 0 ]; then
	pass "the report returns 0 when the template is absent"
else
	fail "the report returned $rc when the template is absent"
fi

# COMPOSE_FILES unset is the standalone path: compose_report_files must fall back to the
# repository's own compose files instead of expanding an unset array under `set -u`.
out="$(env -u COMPOSE_FILES bash -c 'source "$1/../_common.sh" 2>/dev/null; report_missing_env_keys "$2" "$3"' _ "$SCRIPT_DIR" "$env" "$tpl" 2>&1)"
rc=$?
if [ "$rc" -eq 0 ]; then
	pass "the report returns 0 with COMPOSE_FILES unset"
else
	fail "the report returned $rc with COMPOSE_FILES unset"
fi

# --- what counts as declared -------------------------------------------------------

printf '%s' "$out" >/dev/null
out="$(report_missing_env_keys "$env" "$tpl" 2>&1)"
if printf '%s' "$out" | grep -q 'EMPTY is not declared'; then
	fail "an empty-valued key must count as declared"
else
	pass "an empty-valued key counts as declared"
fi

if printf '%s' "$out" | grep -q 'WEIRD'; then
	fail "a template line whose name is not an identifier must never be extracted"
else
	pass "non-identifier template lines are not extracted"
fi

if printf '%s' "$out" | grep -q 'AB is not declared'; then
	pass "a key that is a prefix of another is still reported"
else
	fail "AB was not reported"
fi

printf 'GOOD=1\r\nEMPTY=1\r\nA=1\r\nAB=1\r\nTRICKY=1\r\n' >"$TMP_BASE/crlf"
out="$(report_missing_env_keys "$TMP_BASE/crlf" "$tpl" 2>&1)"
rc=$?
if [ "$rc" -eq 0 ] && ! printf '%s' "$out" | grep -q 'GOOD is not declared'; then
	pass "a CRLF env file still counts its declared keys"
else
	fail "a CRLF env file is mishandled (rc=$rc)"
fi

# --- the report is read-only -------------------------------------------------------

before="$(cksum <"$env")"
report_missing_env_keys "$env" "$tpl" >/dev/null 2>&1
if [ "$before" = "$(cksum <"$env")" ]; then
	pass "the report is read-only (the env file is byte-identical)"
else
	fail "the report modified the env file"
fi

# --- the message may not assert what the report did not measure ---------------------
#
# MAILPIT_IMAGE and MAILPIT_PORT are declared in the template and defaulted in the dev
# override only. A staging or prod report loads neither, so there is no default *in the
# files in force*; the honest statement is exactly that. The report used to answer "its
# own consumer reads it directly", which asserts a consumer nothing here verified and
# which is false for these two: in dev they are compose-published ports and image tags,
# not values a script reads. A report that misdescribes how a value is decided is the
# same defect class this feature exists to remove, one level down.

printf 'WORKER_ENABLED=false\n' >"$TMP_BASE/minimal.env"
COMPOSE_FILES=(-f "$DOCKER_DIR/docker-compose.yml")
out="$(report_missing_env_keys "$TMP_BASE/minimal.env" "$DOCKER_DIR/.env.template" 2>&1)"
unset COMPOSE_FILES

if printf '%s' "$out" | grep -q 'no default in the compose files this environment loads'; then
	pass "a key with no default in the files in force is reported as exactly that"
else
	fail "a key with no default in the files in force is not reported truthfully"
fi

if printf '%s' "$out" | grep -q 'its own consumer reads it directly'; then
	fail "the report asserts a consumer it never verified"
else
	pass "the report asserts no consumer it did not verify"
fi

echo "------------------------------------------"
printf 'env-report-robustness: %s passed, %s failed\n' "$PASS_COUNT" "$FAIL_COUNT"
[ "$FAIL_COUNT" -eq 0 ] || exit 1
exit 0
