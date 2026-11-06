#!/bin/bash
# ============================================
# LifeControl - the pre-flight report of ADR-0002 migration step 2 (money-first-class T3)
# ============================================
# The ADR asks for a report before the constraint, not after: «Without this step the constraint aborts the
# deploy on production data instead of on a report». `preflight-money-parts.sh` is that report, and this
# suite pins the four things that make it worth running:
#
#   1. **It reads as a superuser, never as the owner.** `sales_orders` is armed with `FORCE ROW LEVEL
#      SECURITY` (`V65`), which filters the table owner too, so the same report run as `lifecontrol_owner`
#      reads zero rows and reports a clean dataset. That is the failure mode `V54` and `V64` already
#      record, and it is why the script resolves a `rolsuper` candidate instead of using the pool.
#   2. **The two verdicts are the boundary the ADR names**, and the boundary is measured in the currency's
#      minor units rather than in a literal: one minor unit is `0.01` for `MXN` (`minor_units = 2`) and
#      `1` for a currency with none. A drift of exactly one minor unit is `rounding_adjustment`; one
#      ten-thousandth more is a manual decision, because the migration does not take it.
#   3. **The query asks the question against the schema that exists.** The ADR's block computes
#      `list_price * quantity` inline because no column held it; `V81` derives that expression into
#      `sales_order_items.subtotal` under `ck_item_subtotal`, so the report reads the column the
#      constraint reads and cannot disagree with it.
#   4. **It is read-only and its exit code is a gate**: 0 when nothing needs a human, 2 when at least one
#      order does, 1 when the report could not run. Nothing in the env file, the database or the tree is
#      written by a report.
#
# No Docker daemon, no network, no psql, no repository file written: the script runs in a throwaway tree
# under `mktemp -d` with a stub `docker` on PATH that logs every argv, models the `rolsuper` probe, and
# replays crafted report rows or captures the SQL the script sent.
#
# Run: ./docker/scripts/tests/preflight-money-parts.test.sh
# Exits 0 only when every check passes.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../_common.sh
source "$SCRIPT_DIR/../_common.sh"

DOCKER_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
PREFLIGHT_SH="$DOCKER_DIR/scripts/preflight-money-parts.sh"
ADR_0002="$(cd "$DOCKER_DIR/.." && pwd)/docs/adr/0002-money-first-class.md"

PASS_COUNT=0
FAIL_COUNT=0

TMP_BASE="$(mktemp -d)"
# shellcheck disable=SC2329  # invoked by the EXIT trap below, which shellcheck cannot trace
cleanup() {
	rm -rf "$TMP_BASE"
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

check_equal() {
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
		printf '%s\n' "$2" | cut -c1-200 | sed 's/^/        /' | head -12
	fi
}

assert_absent() {
	if printf '%s' "$2" | grep -qF -- "$3"; then
		fail "$1 ('$3' appeared)"
		printf '%s\n' "$2" | cut -c1-200 | sed 's/^/        /' | head -12
	else
		pass "$1"
	fi
}

# ------------------------------------------
# The throwaway tree: the script under test, the helpers it sources, a minimal env file, and a stub
# `docker` that stands in for the daemon and for psql inside the container.
# ------------------------------------------
WORK="$TMP_BASE/preflight"
mkdir -p "$WORK/scripts" "$WORK/bin"
cp "$PREFLIGHT_SH" "$DOCKER_DIR/scripts/_common.sh" "$WORK/scripts/" 2>/dev/null || true

printf 'LIFECONTROL_POSTGRES_DB=lifecontrol\nLIFECONTROL_POSTGRES_USER=lifecontrol_bootstrap\n' >"$WORK/.env.dev"

cat >"$WORK/bin/docker" <<'STUB'
#!/bin/sh
# Records every argv, models the `rolsuper` probe, and replays the report rows the case supplies.
printf '%s\n' "$*" >>"$DOCKER_LOG"

previous=""
username=""
for argument in "$@"; do
	if [ "$previous" = "--username" ]; then username="$argument"; fi
	previous="$argument"
done

case "$*" in
*"--file -"*)
	# The report: capture the SQL the script sent, then answer with the rows this case supplies.
	cat >"$SQL_LOG"
	if [ -n "${FAIL_REPORT:-}" ]; then
		printf 'ERROR:  relation "sales_orders" does not exist\n' >&2
		exit 1
	fi
	cat "$REPORT_ROWS"
	exit 0
	;;
esac

case "$*" in
*rolsuper*)
	case " ${SUPERUSERS:-} " in
	*" $username "*) printf '1\n' ;;
	esac
	exit 0
	;;
esac

exit 0
STUB
chmod +x "$WORK/bin/docker"

: >"$WORK/report-rows"
: >"$WORK/sql.log"
: >"$WORK/docker.log"

# run_preflight
# A case sets SUPERUSERS / FAIL_REPORT in front of the call, which exports them for the function's
# duration and therefore for the subshell that runs the script. The rows and the probe log are per-case;
# the SQL log is reset here so a case can never read the previous one's answer.
run_preflight() {
	: >"$WORK/docker.log"
	: >"$WORK/sql.log"
	(
		PATH="$WORK/bin:$PATH" \
			DOCKER_LOG="$WORK/docker.log" \
			SQL_LOG="$WORK/sql.log" \
			REPORT_ROWS="$WORK/report-rows" \
			"$WORK/scripts/preflight-money-parts.sh" dev
	) >"$WORK/out.log" 2>&1
	PRE_RC=$?
	PRE_TEXT="$(sed 's/\x1b\[[0-9;]*m//g' "$WORK/out.log")"
	PRE_CALLS="$(cat "$WORK/docker.log")"
	PRE_SQL="$(cat "$WORK/sql.log")"
}

PRE_RC=0
PRE_TEXT=""
PRE_CALLS=""
PRE_SQL=""

# rows <line>...  — the report rows a case answers with, in the script's own field order:
# order_id|order_number|currency|total_amount|line_net|drift|drift_units|tolerance_units
rows() {
	: >"$WORK/report-rows"
	for line in "$@"; do
		printf '%s\n' "$line" >>"$WORK/report-rows"
	done
}

env_before="$(cksum <"$WORK/.env.dev")"

# ------------------------------------------
# 1. The verdict boundary: one minor unit, measured in the order's own currency.
# ------------------------------------------
echo "== 1. the two verdicts: one minor unit is rounding_adjustment, one ten-thousandth more is manual"

rows \
	'11111111-1111-1111-1111-111111111111|A-1|MXN|1234.5600|1234.5500|0.0100|100|100' \
	'22222222-2222-2222-2222-222222222222|A-2|MXN|1234.5600|1234.5599|0.0001|1|100' \
	'33333333-3333-3333-3333-333333333333|A-3|MXN|1234.5600|1234.5400|0.0200|200|100' \
	'44444444-4444-4444-4444-444444444444|A-4|MXN|1234.5600|1200.0000|34.5600|345600|100'
SUPERUSERS="lifecontrol_bootstrap" run_preflight

check_equal "a dataset with a manual row exits 2" "2" "$PRE_RC"
assert_contains "exactly one minor unit is rounding_adjustment (A-1)" "$PRE_TEXT" \
	'rounding_adjustment  A-1  MXN'
assert_contains "a ten-thousandth is rounding_adjustment too (A-2)" "$PRE_TEXT" \
	'rounding_adjustment  A-2  MXN'
assert_contains "two minor units is a manual decision (A-3)" "$PRE_TEXT" 'manual               A-3  MXN'
assert_contains "a wide drift is a manual decision (A-4)" "$PRE_TEXT" 'manual               A-4  MXN'
assert_contains "the report names the drift it measured" "$PRE_TEXT" 'drift=34.5600'
assert_contains "the summary counts both verdicts" "$PRE_TEXT" '2 rounding_adjustment, 2 manual'

# A zero-minor-unit currency: the tolerance is a whole unit, so 1.0000 is absorbed and 1.0001 is not.
rows \
	'55555555-5555-5555-5555-555555555555|B-1|JPY|100.0000|99.0000|1.0000|10000|10000' \
	'66666666-6666-6666-6666-666666666666|B-2|JPY|100.0000|98.9999|1.0001|10001|10000'
SUPERUSERS="lifecontrol_bootstrap" run_preflight

check_equal "a zero-minor-unit currency still exits 2 for B-2" "2" "$PRE_RC"
assert_contains "a whole unit of drift is rounding_adjustment when the currency has no minor units" \
	"$PRE_TEXT" 'rounding_adjustment  B-1  JPY'
assert_contains "one ten-thousandth past the whole unit is manual" "$PRE_TEXT" 'manual               B-2  JPY'

# ------------------------------------------
# 2. Nothing to decide: an empty report, and a report whose every row is absorbable.
# ------------------------------------------
echo "== 2. nothing to decide exits 0, and says which of the two clean states it is"

rows
SUPERUSERS="lifecontrol_bootstrap" run_preflight
check_equal "a dataset with no drift exits 0" "0" "$PRE_RC"
assert_contains "it says no order disagrees" "$PRE_TEXT" 'No order disagrees with its line sum'
assert_contains "it names the constraint the report is the pre-flight for" "$PRE_TEXT" 'ck_order_total'

rows '11111111-1111-1111-1111-111111111111|A-1|MXN|1234.5600|1234.5500|0.0100|100|100'
SUPERUSERS="lifecontrol_bootstrap" run_preflight
check_equal "a rounding-only dataset exits 0" "0" "$PRE_RC"
assert_contains "it reports the absorbed row" "$PRE_TEXT" 'rounding_adjustment  A-1  MXN'
assert_contains "it says the migration absorbs it and no human decides" "$PRE_TEXT" \
	'no decision needed'

# ------------------------------------------
# 3. The role: `FORCE ROW LEVEL SECURITY` filters the owner, so the report needs a superuser.
# ------------------------------------------
echo "== 3. the report resolves a superuser and never reads as the owner"

rows '11111111-1111-1111-1111-111111111111|A-1|MXN|1234.5600|1234.5500|0.0100|100|100'
SUPERUSERS="lifecontrol_bootstrap" run_preflight
assert_contains "the maintenance superuser is probed first" "$PRE_CALLS" '--username lifecontrol_bootstrap'
assert_absent "the owner is never the connection that reads" "$PRE_CALLS" '--username lifecontrol_owner'
assert_absent "the pool's role is never the connection that reads" "$PRE_CALLS" '--username lifecontrol_app'
assert_contains "it explains why the owner cannot be the reader" "$PRE_TEXT" 'FORCE ROW LEVEL SECURITY'

rows '11111111-1111-1111-1111-111111111111|A-1|MXN|1234.5600|1234.5500|0.0100|100|100'
SUPERUSERS="" run_preflight
check_equal "no superuser means the report does not run" "1" "$PRE_RC"
assert_contains "the failure says no superuser could connect" "$PRE_TEXT" 'No superuser could connect'
assert_absent "and no report was attempted" "$PRE_SQL" 'sales_orders'

# ------------------------------------------
# 4. The SQL: the ADR's question, asked against the schema that exists.
# ------------------------------------------
echo "== 4. the query reads the derived line subtotal and the schema's own scale"

rows
SUPERUSERS="lifecontrol_bootstrap" run_preflight
assert_contains "the line sum reads the derived column" "$PRE_SQL" 'sum(i.subtotal)'
assert_contains "the discount is read from the line" "$PRE_SQL" 'sum(i.discount_applied)'
assert_contains "only enabled lines count, as the ADR states" "$PRE_SQL" 'i.enabled'
assert_contains "a cancelled line is excluded, so the join is a LEFT JOIN" "$PRE_SQL" 'LEFT JOIN'
assert_contains "the tolerance comes from the currency's minor units" "$PRE_SQL" 'minor_units'
assert_contains "the drift is compared in ten-thousandths, exactly" "$PRE_SQL" 'drift_units'
assert_absent "the pre-V81 inline candidate is not re-derived" "$PRE_SQL" 'list_price * quantity'
assert_contains "the run sets ON_ERROR_STOP so a SQL error is not a clean report" "$PRE_CALLS" \
	'--set ON_ERROR_STOP=1'

# ------------------------------------------
# 5. Failures are failures: a broken report, an unparsable row, an unknown environment.
# ------------------------------------------
echo "== 5. a report that cannot run never reads as a clean dataset"

rows
SUPERUSERS="lifecontrol_bootstrap" FAIL_REPORT=1 run_preflight
check_equal "a psql failure exits 1" "1" "$PRE_RC"
assert_absent "and it is not reported as a clean dataset" "$PRE_TEXT" 'No order disagrees'

rows 'WARNING: this is not a report row'
SUPERUSERS="lifecontrol_bootstrap" run_preflight
check_equal "an unparsable row exits 1" "1" "$PRE_RC"
assert_contains "the failure shows the line it could not read" "$PRE_TEXT" 'WARNING: this is not a report row'

rows '11111111-1111-1111-1111-111111111111|A-1|MXN|1234.5600|1234.5500|0.0100|x|100'
SUPERUSERS="lifecontrol_bootstrap" run_preflight
check_equal "a non-numeric drift exits 1 instead of comparing" "1" "$PRE_RC"

(
	set +e
	"$WORK/scripts/preflight-money-parts.sh" nope >"$WORK/bad-env.log" 2>&1
	printf '%s' "$?" >"$WORK/bad-env.rc"
)
check_equal "an unknown environment exits 1" "1" "$(cat "$WORK/bad-env.rc")"

# ------------------------------------------
# 6. A report writes nothing.
# ------------------------------------------
echo "== 6. the report is read-only: the env file it read is byte-identical"
env_after="$(cksum <"$WORK/.env.dev")"
check_equal "the env file is untouched" "$env_before" "$env_after"
check_equal "no login was attempted against the pool's credential" "0" \
	"$(grep -c -- '--password' "$WORK/docker.log" 2>/dev/null || true)"

# ------------------------------------------
# 7. The ADR sentence this script exists to obey is still the sentence ADR-0002 states.
# ------------------------------------------
echo "== 7. the record's contract still matches the ADR it quotes"
# The step, not the file: squashed to one line and whitespace-collapsed on purpose, because the assertion
# is about the sentences ADR-0002 states and not about where the file happens to wrap them.
step2="$(awk '/^2\. \*\*Pre-flight/,/^3\. Add every column/' "$ADR_0002" | tr '\n' ' ' | tr -s ' ')"
assert_contains "the ADR still says the deploy aborts on the constraint instead of on a report" "$step2" \
	'aborts the deploy on production data instead of on a report'
assert_contains "the ADR still names the constraint whose rows the report is" "$step2" 'ck_order_total'
assert_contains "the ADR still states the rounding verdict" "$step2" \
	'A drift within one minor unit is `rounding_adjustment` on that row'
assert_contains "the ADR still takes the larger drift away from the migration" "$step2" \
	'A larger drift is a manual decision per row, taken before the migration runs and not by the migration'

# ------------------------------------------
# Summary
# ------------------------------------------
echo "------------------------------------------"
printf 'preflight-money-parts: %s passed, %s failed\n' "$PASS_COUNT" "$FAIL_COUNT"
if [ "$FAIL_COUNT" -ne 0 ]; then
	exit 1
fi
exit 0
