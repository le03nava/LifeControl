#!/bin/bash
# ============================================
# LifeControl - stale image pin report tests
# ============================================
# Issue #597: `docker/.env.<env>` files are materialised once and never reconciled, so a pin that moves
# in `docker/.env.template` leaves the old image behind in a file that still reads as correct. This
# suite pins the report that makes that visible, and the two wirings that put it in front of an operator:
#
#   1. `report_stale_image_pins` names every `*_IMAGE` whose value differs between an env file and the
#      template, naming the key AND both values, and says nothing when they agree.
#   2. The report is read-only and always returns 0: `validate-env.sh` is the production start gate
#      (`deploy.sh` refuses to start prod when it exits non-zero), so the report must never be able to
#      stop a start on its own. Only an explicit `--strict-pins` may turn it into a failure; that
#      behaviour is driven end-to-end by `validate-env-strict-pins.test.sh`.
#
# The report also must not invent drift: compose strips surrounding whitespace, unwraps a fully quoted
# value and drops an inline ` # comment`, so a file written that way is correct and reporting it is the
# false positive that would get `--strict-pins` switched off.
#
# No Docker, no network, no compose: every case is a scratch file. The repository's own `.env.<env>`
# files are gitignored and absent in CI, which is exactly why the fixtures here are synthetic.
#
# Run: ./docker/scripts/tests/env-image-pin-drift.test.sh
# Exits 0 only when every case passes.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../_common.sh
source "$SCRIPT_DIR/../_common.sh"

DOCKER_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
VALIDATE_ENV_SH="$DOCKER_DIR/scripts/validate-env.sh"
DEPLOY_SH="$DOCKER_DIR/scripts/deploy.sh"

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

# assert_contains <description> <haystack> <needle>
assert_contains() {
	if printf '%s' "$2" | grep -qF -- "$3"; then
		pass "$1"
	else
		fail "$1 (the report never mentioned '$3')"
	fi
}

# assert_absent <description> <haystack> <needle>
assert_absent() {
	if printf '%s' "$2" | grep -qF -- "$3"; then
		fail "$1 ('$3' appeared in the report)"
	else
		pass "$1"
	fi
}

# assert_equal <description> <expected> <actual>
assert_equal() {
	if [ "$2" = "$3" ]; then
		pass "$1 ($3)"
	else
		fail "$1 (expected $2, got $3)"
	fi
}

TMP_BASE="$(mktemp -d)"
# shellcheck disable=SC2329  # invoked by the EXIT trap below
cleanup() {
	rm -rf "$TMP_BASE"
}
trap cleanup EXIT

# The report sets STALE_IMAGE_PIN_COUNT in the *caller's* shell, so it has to be called directly: a
# command substitution would run it in a subshell, where the counter never comes back and every
# assertion about it would read a stale value. The text is captured through a file instead.
REPORT_OUT="$TMP_BASE/report.out"
REPORT_TEXT=""
REPORT_RC=0
run_report() {
	report_stale_image_pins "$1" "$2" >"$REPORT_OUT" 2>&1
	REPORT_RC=$?
	REPORT_TEXT="$(cat "$REPORT_OUT")"
}

# ------------------------------------------
# 1. A divergent pin is reported with the key and both values, and an agreeing one is not.
# ------------------------------------------
tpl="$TMP_BASE/template"
env="$TMP_BASE/.env.fixture"
printf 'LOKI_IMAGE=grafana/loki:3.7.8\nTEMPO_IMAGE=grafana/tempo:3.1.0\nPOSTGRES_IMAGE=postgres:18.6\n' >"$tpl"
printf 'LOKI_IMAGE=grafana/loki:2.9.0\nTEMPO_IMAGE=grafana/tempo:3.1.0\nPOSTGRES_IMAGE=postgres:18.6\n' >"$env"

run_report "$env" "$tpl"
assert_equal "the report returns 0 on a divergent pin, so it can never stop a start" "0" "$REPORT_RC"
assert_contains "the divergence is reported" "$REPORT_TEXT" "LOKI_IMAGE"
assert_contains "the message names the value the env file pins" "$REPORT_TEXT" "grafana/loki:2.9.0"
assert_contains "the message names the value the template pins" "$REPORT_TEXT" "grafana/loki:3.7.8"
assert_contains "the report says it is a report, not a failure" "$REPORT_TEXT" "not a failure"
assert_absent "a pin that agrees is not reported" "$REPORT_TEXT" "TEMPO_IMAGE"
assert_equal "the count is the number of divergent pins" "1" "$STALE_IMAGE_PIN_COUNT"

# ------------------------------------------
# 2. An aligned env file produces no warning at all.
# ------------------------------------------
aligned="$TMP_BASE/.env.aligned"
printf 'LOKI_IMAGE=grafana/loki:3.7.8\nTEMPO_IMAGE=grafana/tempo:3.1.0\nPOSTGRES_IMAGE=postgres:18.6\n' >"$aligned"

run_report "$aligned" "$tpl"
assert_equal "an aligned env file returns 0" "0" "$REPORT_RC"
assert_absent "an aligned env file produces no warning" "$REPORT_TEXT" "WARNING"
assert_absent "an aligned env file names no divergent key" "$REPORT_TEXT" "LOKI_IMAGE"
assert_equal "the count of an aligned env file is 0" "0" "$STALE_IMAGE_PIN_COUNT"
assert_contains "an aligned env file is reported as such" "$REPORT_TEXT" "same image versions"

# ------------------------------------------
# 3. A legal spelling is not drift: compose trims whitespace, unwraps a fully quoted value and drops
#    an inline comment. Anything else here would fail `--strict-pins` on a correct file.
# ------------------------------------------
spelled="$TMP_BASE/.env.spelled"
{
	printf 'LOKI_IMAGE=grafana/loki:3.7.8 # pinned for the observability unit\n'
	printf 'TEMPO_IMAGE="grafana/tempo:3.1.0"\n'
	printf "POSTGRES_IMAGE='postgres:18.6'   \n"
} >"$spelled"

run_report "$spelled" "$tpl"
assert_equal "a commented, quoted or padded pin is not drift" "0" "$STALE_IMAGE_PIN_COUNT"
assert_absent "a legal spelling produces no warning" "$REPORT_TEXT" "WARNING"

# A comment inside quotes is part of the value, so the value really does differ there.
in_quotes="$TMP_BASE/.env.in-quotes"
printf 'LOKI_IMAGE="grafana/loki:3.7.8 # not a comment"\n' >"$in_quotes"
run_report "$in_quotes" "$tpl"
assert_equal "a '#' inside quotes stays part of the value" "1" "$STALE_IMAGE_PIN_COUNT"

# ------------------------------------------
# 4. Only `*_IMAGE` keys are compared, and only the ones the env file declares.
#    Ports and limits legitimately differ per environment; an undeclared pin runs the compose default,
#    which image-pins.test.sh already pins against the template.
# ------------------------------------------
mixed="$TMP_BASE/.env.mixed"
printf 'LOKI_PORT=3100\nPOSTGRES_MEMORY_LIMIT=1g\n' >"$mixed"
run_report "$mixed" "$tpl"
assert_equal "a non-pin value is never compared" "0" "$STALE_IMAGE_PIN_COUNT"
assert_absent "a non-pin key is not reported" "$REPORT_TEXT" "LOKI_PORT"

undeclared="$TMP_BASE/.env.undeclared"
printf 'POSTGRES_IMAGE=postgres:18.6\n' >"$undeclared"
run_report "$undeclared" "$tpl"
assert_equal "an undeclared pin is not drift: it runs the compose default" "0" "$STALE_IMAGE_PIN_COUNT"
assert_absent "an undeclared pin is not reported" "$REPORT_TEXT" "LOKI_IMAGE"

# ------------------------------------------
# 5. The report is read-only.
# ------------------------------------------
before="$(cksum <"$env")"
run_report "$env" "$tpl"
if [ "$before" = "$(cksum <"$env")" ]; then
	pass "the report is read-only (the env file is byte-identical)"
else
	fail "the report modified the env file"
fi

# ------------------------------------------
# 6. An absent file is a warning, never a failure, and resets the count.
# ------------------------------------------
run_report "$env" "$tpl" # leaves the count at 1 on purpose
run_report "$TMP_BASE/absent.env" "$tpl"
assert_equal "an absent env file returns 0" "0" "$REPORT_RC"
assert_contains "an absent env file is named" "$REPORT_TEXT" "absent.env"
assert_equal "an absent env file resets the count" "0" "$STALE_IMAGE_PIN_COUNT"

run_report "$env" "$TMP_BASE/absent.template"
assert_equal "an absent template returns 0" "0" "$REPORT_RC"
assert_contains "an absent template is named" "$REPORT_TEXT" "absent.template"
assert_equal "an absent template resets the count" "0" "$STALE_IMAGE_PIN_COUNT"

# ------------------------------------------
# 7. A caller that runs `set -e` survives every path of the report: this is the property that keeps the
#    production gate from aborting on a missing file or on drift.
# ------------------------------------------
for case_name in divergent aligned absent-env absent-template; do
	case "$case_name" in
	divergent)
		probe_env="$env"
		probe_template="$tpl"
		;;
	aligned)
		probe_env="$aligned"
		probe_template="$tpl"
		;;
	absent-env)
		probe_env="$TMP_BASE/nope.env"
		probe_template="$tpl"
		;;
	absent-template)
		probe_env="$env"
		probe_template="$TMP_BASE/nope.tpl"
		;;
	esac
	reached="$(env A="$probe_env" B="$probe_template" C="$SCRIPT_DIR/../_common.sh" bash -c \
		'set -e; source "$C"; report_stale_image_pins "$A" "$B" >/dev/null; echo REACHED' 2>/dev/null)"
	assert_equal "a set -e caller survives the report ($case_name)" "REACHED" "$reached"
done

# ------------------------------------------
# 8. validate-env.sh wires the report as a warning, from exactly one place, and never counts it as an
#    error: the production start gate cannot start failing on drift alone.
# ------------------------------------------
if [ ! -f "$VALIDATE_ENV_SH" ]; then
	fail "missing docker/scripts/validate-env.sh"
else
	call_sites="$(grep -c 'report_stale_image_pins "' "$VALIDATE_ENV_SH")"
	assert_equal "validate-env.sh calls the report exactly once" "1" "$call_sites"

	if grep -q 'report_stale_image_pins "' "$VALIDATE_ENV_SH"; then
		pass "validate-env.sh reports the drift"
	else
		fail "validate-env.sh does not report the drift"
	fi

	# The function that owns the report must not touch the error counter. Read from the function body,
	# not from the whole file, so an unrelated increment elsewhere cannot satisfy the assertion.
	body="$(awk '/^check_image_pins\(\) \{/,/^}/' "$VALIDATE_ENV_SH")"
	if [ -z "$body" ]; then
		fail "validate-env.sh has no check_image_pins function to hold the report"
	else
		if printf '%s' "$body" | grep -q 'report_stale_image_pins'; then
			pass "check_image_pins is what calls the report"
		else
			fail "check_image_pins does not call the report"
		fi
		if printf '%s' "$body" | grep -q 'ERRORS='; then
			fail "check_image_pins counts the drift as an error, which would make the prod gate fail on drift alone"
		else
			pass "check_image_pins never counts the drift as an error"
		fi
	fi

	if grep -q -- '--strict-pins' "$VALIDATE_ENV_SH"; then
		pass "validate-env.sh accepts the explicit --strict-pins request"
	else
		fail "validate-env.sh does not accept --strict-pins, so drift can never be a failure"
	fi

	if [ -n "$body" ] && printf '%s' "$body" | grep -q 'STALE_IMAGE_PIN_COUNT'; then
		fail "the strict decision lives in check_image_pins, where it would also abort the non-strict summary"
	else
		pass "the strict decision is not inside the report's own check function"
	fi
fi

# ------------------------------------------
# 9. deploy.sh prints the report in the `start` dispatch, before it builds.
# ------------------------------------------
if [ ! -f "$DEPLOY_SH" ]; then
	fail "missing docker/scripts/deploy.sh"
else
	start_block="$(awk '/^start\)$/,/^;;$/' "$DEPLOY_SH")"
	if [ -z "$start_block" ]; then
		fail "deploy.sh has no start) dispatch to hold the report"
	else
		if printf '%s' "$start_block" | grep -q 'report_stale_image_pins'; then
			pass "deploy.sh start reports the drift"
		else
			fail "deploy.sh start does not report the drift"
		fi
		report_line="$(printf '%s\n' "$start_block" | grep -n 'report_stale_image_pins' | cut -d: -f1)"
		build_line="$(printf '%s\n' "$start_block" | grep -n 'build_services' | head -1 | cut -d: -f1)"
		if [ -n "$report_line" ] && [ -n "$build_line" ] && [ "$report_line" -lt "$build_line" ]; then
			pass "deploy.sh start reports the drift before building"
		else
			fail "deploy.sh start does not report the drift before building (report at '$report_line', build at '$build_line')"
		fi
	fi
fi

echo "------------------------------------------"
printf 'env-image-pin-drift: %s passed, %s failed\n' "$PASS_COUNT" "$FAIL_COUNT"
[ "$FAIL_COUNT" -eq 0 ] || exit 1
exit 0
