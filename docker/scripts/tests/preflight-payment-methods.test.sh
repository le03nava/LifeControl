#!/bin/bash
# ============================================
# LifeControl - the pre-flight report of ADR-0002 migration step 4 (D-a of money-first-class)
# ============================================
# `preflight-payment-methods.sh` is the report `odd/tasks/money-first-class.md` asks for in D-a: «Skip the
# order and report it — and measure before deciding. T3's pre-flight emits the count and the ids; at zero
# the decision evaporates, at a non-zero count it is taken per row with the list in hand». T3 shipped the
# pre-flight of ADR-0002 **step 2** (the total against the line sum), so this suite pins the measurement
# D-a depends on, which is step 4's:
#
#   1. **The question is the tree's, not a guess.** "Money was taken" is `status = 'COMPLETED'` —
#      `SalesOrderService.java:461`, after the guard at `:426` — and "no payment method" is
#      `payment_method_id IS NULL` **and** no `sale_payment` row (`V83`). Both halves are asserted against
#      the SQL the script actually sent, so a future edit that answers a different question fails here.
#   2. **The verdicts and the exit codes are the step-2 report's contract**, for the same reason: 0
#      nothing to decide, 2 at least one per-row decision, 1 the report could not run. 2 is not 1 because
#      a deploy has to tell "needs a human" from "could not ask".
#   3. **A schema it cannot read is a refusal, never a clean dataset.** A database older than the tree has
#      no `sale_payment`, so every completed order would look unpaid and the report would answer "nothing
#      to decide" with confidence it did not earn. That case exits 1 and names what it needed.
#   4. **It reads as a superuser and writes nothing.** `sales_orders` is `FORCE ROW LEVEL SECURITY`
#      (`V65`), so the owner reads zero rows; and the report issues exactly one `SELECT` — no `INSERT`,
#      `UPDATE`, `DELETE`, `ALTER`, `CREATE` or `DROP` in the statement it sends.
#
# No Docker daemon, no network, no psql, no repository file written: the script runs in a throwaway tree
# under `mktemp -d` with a stub `docker` on PATH that logs every argv, models the `rolsuper` probe, and
# replays crafted report rows, a missing schema or a failing psql.
#
# Run: ./docker/scripts/tests/preflight-payment-methods.test.sh
# Exits 0 only when every check passes.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../_common.sh
source "$SCRIPT_DIR/../_common.sh"

DOCKER_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
PREFLIGHT_SH="$DOCKER_DIR/scripts/preflight-payment-methods.sh"
ADR_0002="$(cd "$DOCKER_DIR/.." && pwd)/docs/adr/0002-money-first-class.md"
FEATURE_RECORD="$(cd "$DOCKER_DIR/.." && pwd)/odd/tasks/money-first-class.md"

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

assert_matches() {
	if printf '%s' "$2" | grep -qE -- "$3"; then
		pass "$1"
	else
		fail "$1 (the output never matched /$3/)"
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
# The throwaway tree: the script under test, the two libraries it sources, a minimal env file, and a stub
# `docker` that stands in for the daemon and for psql inside the container.
# ------------------------------------------
WORK="$TMP_BASE/preflight-payment"
mkdir -p "$WORK/scripts" "$WORK/bin"
cp "$PREFLIGHT_SH" "$DOCKER_DIR/scripts/_common.sh" "$DOCKER_DIR/scripts/_preflight_reader.sh" \
	"$WORK/scripts/" 2>/dev/null || true

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
	if [ -n "${SCHEMA_MISSING:-}" ]; then
		printf 'ERROR:  relation "sale_payment" does not exist\nLINE 12:              FROM sale_payment p\n' >&2
		exit 1
	fi
	if [ -n "${FAIL_REPORT:-}" ]; then
		printf 'ERROR:  canceling statement due to statement timeout\n' >&2
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
# A case sets SUPERUSERS / FAIL_REPORT / SCHEMA_MISSING in front of the call, which exports them for the
# function's duration and therefore for the subshell that runs the script. The rows and the probe log are
# per-case; the SQL log is reset here so a case can never read the previous one's answer.
run_preflight() {
	: >"$WORK/docker.log"
	: >"$WORK/sql.log"
	(
		PATH="$WORK/bin:$PATH" \
			DOCKER_LOG="$WORK/docker.log" \
			SQL_LOG="$WORK/sql.log" \
			REPORT_ROWS="$WORK/report-rows" \
			"$WORK/scripts/preflight-payment-methods.sh" dev
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
# order_id|order_number|company_id|state|order_date|updated_at|total_amount
rows() {
	: >"$WORK/report-rows"
	for line in "$@"; do
		printf '%s\n' "$line" >>"$WORK/report-rows"
	done
}

env_before="$(cksum <"$WORK/.env.dev")"

# ------------------------------------------
# 1. The question: an order that took money and carries no method is a per-row decision.
# ------------------------------------------
echo "== 1. one order in that state exits 2 and names the row the decision is about"

rows '11111111-1111-1111-1111-111111111111|A-1|aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa|COMPLETED|2026-01-02 03:04:05|2026-01-03 04:05:06|1234.5600'
SUPERUSERS="lifecontrol_bootstrap" run_preflight

check_equal "one order missing a method exits 2" "2" "$PRE_RC"
assert_matches "the row is named with its verdict and order number" "$PRE_TEXT" '^manual +A-1 '
assert_contains "the row carries the order id" "$PRE_TEXT" '11111111-1111-1111-1111-111111111111'
assert_contains "the row carries the tenant the payment would belong to" "$PRE_TEXT" \
	'company=aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
assert_contains "the row carries the state it was read at" "$PRE_TEXT" 'state=COMPLETED'
assert_contains "the row carries the order date" "$PRE_TEXT" 'order_date=2026-01-02 03:04:05'
assert_contains "the row carries updated_at, which step 4 dates paid_at with" "$PRE_TEXT" \
	'updated_at=2026-01-03 04:05:06'
assert_contains "the row carries the amount the invented payment would state" "$PRE_TEXT" 'total=1234.5600'
assert_contains "it says the decision is taken per order" "$PRE_TEXT" 'the decision is taken per order'
assert_contains "it names the decision D-a records" "$PRE_TEXT" 'skip the order and report it'

# ------------------------------------------
# 2. Several rows: the count is stated, and every row is listed.
# ------------------------------------------
echo "== 2. several orders state the count, and nothing is decided by the report"

rows \
	'11111111-1111-1111-1111-111111111111|A-1|aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa|COMPLETED|2026-01-02 03:04:05|2026-01-03 04:05:06|10.0000' \
	'22222222-2222-2222-2222-222222222222|A-2|aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa|COMPLETED|2026-02-02 03:04:05|2026-02-03 04:05:06|20.0000' \
	'33333333-3333-3333-3333-333333333333|A-3|aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa|COMPLETED|2026-03-02 03:04:05|2026-03-03 04:05:06|30.0000'
SUPERUSERS="lifecontrol_bootstrap" run_preflight

check_equal "three orders exit 2" "2" "$PRE_RC"
assert_contains "the count is stated" "$PRE_TEXT" 'carry no payment method: 3'
assert_contains "every order number is listed" "$PRE_TEXT" 'A-1'
assert_contains "every order number is listed (A-2)" "$PRE_TEXT" 'A-2'
assert_contains "every order number is listed (A-3)" "$PRE_TEXT" 'A-3'
assert_contains "the report states it does not decide" "$PRE_TEXT" 'not by it'

# ------------------------------------------
# 3. Nothing to decide exits 0, and says which of the two clean states it is.
# ------------------------------------------
echo "== 3. a dataset with no such order exits 0 and says so"

rows
SUPERUSERS="lifecontrol_bootstrap" run_preflight
check_equal "no order missing a method exits 0" "0" "$PRE_RC"
assert_contains "it says step 4 has nothing to invent" "$PRE_TEXT" 'No completed order is missing a payment method'
assert_contains "it names the decision that evaporates at zero" "$PRE_TEXT" 'D-a has nothing to decide'
assert_absent "no per-row verdict is printed" "$PRE_TEXT" 'manual'

# ------------------------------------------
# 4. The SQL: D-a's question, asked against the schema that exists.
# ------------------------------------------
echo "== 4. the query asks the question the tree states"

rows
SUPERUSERS="lifecontrol_bootstrap" run_preflight
assert_contains "money was taken is the COMPLETED state" "$PRE_SQL" "o.status = 'COMPLETED'"
assert_contains "the method column step 4 reads is the one judged" "$PRE_SQL" 'o.payment_method_id IS NULL'
assert_contains "an order that already has a payment row is not D-a's row" "$PRE_SQL" 'FROM sale_payment p'
assert_contains "that exclusion is a NOT EXISTS, so the order is still read" "$PRE_SQL" 'NOT EXISTS'
assert_contains "the tenant is read, because the payment row states it" "$PRE_SQL" 'o.company_id'
assert_contains "updated_at is read, because step 4 dates paid_at with it" "$PRE_SQL" 'o.updated_at'
assert_contains "the amount the invented row would carry is read" "$PRE_SQL" 'o.total_amount'
assert_absent "no payment is dated with now(), which ADR-0002 step 4 forbids" "$PRE_SQL" 'now()'
assert_contains "the run sets ON_ERROR_STOP so a SQL error is not a clean report" "$PRE_CALLS" \
	'--set ON_ERROR_STOP=1'

# ------------------------------------------
# 5. The reader: `FORCE ROW LEVEL SECURITY` filters the owner, so the report needs a superuser.
# ------------------------------------------
echo "== 5. the report resolves a superuser and never reads as the owner"

rows '11111111-1111-1111-1111-111111111111|A-1|aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa|COMPLETED|2026-01-02 03:04:05|2026-01-03 04:05:06|10.0000'
SUPERUSERS="lifecontrol_bootstrap" run_preflight
assert_contains "the maintenance superuser is probed first" "$PRE_CALLS" '--username lifecontrol_bootstrap'
assert_absent "the owner is never the connection that reads" "$PRE_CALLS" '--username lifecontrol_owner'
assert_absent "the pool's role is never the connection that reads" "$PRE_CALLS" '--username lifecontrol_app'
assert_contains "it explains why the owner cannot be the reader" "$PRE_TEXT" 'FORCE ROW LEVEL SECURITY'

rows '11111111-1111-1111-1111-111111111111|A-1|aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa|COMPLETED|2026-01-02 03:04:05|2026-01-03 04:05:06|10.0000'
SUPERUSERS="" run_preflight
check_equal "no superuser means the report does not run" "1" "$PRE_RC"
assert_contains "the failure says no superuser could connect" "$PRE_TEXT" 'No superuser could connect'
assert_absent "and no report was attempted" "$PRE_SQL" 'sales_orders'
assert_absent "and no verdict was printed" "$PRE_TEXT" 'manual'

# ------------------------------------------
# 6. A report writes nothing.
# ------------------------------------------
echo "== 6. the report is read-only: one SELECT, no write statement, env file untouched"

rows
SUPERUSERS="lifecontrol_bootstrap" run_preflight
check_equal "exactly one report statement is sent" "1" "$(grep -c -- '--file -' "$WORK/docker.log")"
for keyword in INSERT UPDATE DELETE ALTER CREATE DROP; do
	assert_absent "the statement it sends has no $keyword" "$PRE_SQL" "$keyword"
done
assert_matches "the statement is a SELECT" "$PRE_SQL" '^SELECT '
assert_absent "the statement takes no advisory lock" "$PRE_SQL" 'pg_advisory'
assert_absent "the statement sets no session state" "$PRE_SQL" 'SET '
script_writes="$(grep -vE '^[[:space:]]*#' "$PREFLIGHT_SH" |
	grep -cE '\b(INSERT|UPDATE|DELETE|ALTER|CREATE|DROP)\b' || true)"
check_equal "no write statement outside the comments either" "0" "$script_writes"

env_after="$(cksum <"$WORK/.env.dev")"
check_equal "the env file it read is untouched" "$env_before" "$env_after"
check_equal "no login was attempted against the pool's credential" "0" \
	"$(grep -c -- '--password' "$WORK/docker.log" 2>/dev/null || true)"

# ------------------------------------------
# 7. Failures are failures: a schema it cannot read, a broken report, an unparsable row.
# ------------------------------------------
echo "== 7. a report that cannot run never reads as a clean dataset"

rows
SUPERUSERS="lifecontrol_bootstrap" SCHEMA_MISSING=1 run_preflight
check_equal "a schema without sale_payment exits 1" "1" "$PRE_RC"
assert_contains "the refusal says the schema is not the one the report reads" "$PRE_TEXT" \
	'does not carry the schema this report reads'
assert_contains "it names the table that is missing, in psql's own words" "$PRE_TEXT" 'sale_payment" does not exist'
assert_contains "it names the migrations the report needs" "$PRE_TEXT" 'V83'
assert_absent "and it is not reported as nothing to decide" "$PRE_TEXT" 'nothing to decide'
assert_absent "and no per-row verdict is printed" "$PRE_TEXT" 'manual'

rows
SUPERUSERS="lifecontrol_bootstrap" FAIL_REPORT=1 run_preflight
check_equal "a psql failure exits 1" "1" "$PRE_RC"
assert_absent "and it is not reported as a clean dataset" "$PRE_TEXT" 'No completed order is missing'
assert_contains "the failure shows what psql said" "$PRE_TEXT" 'statement timeout'

rows 'WARNING: this is not a report row'
SUPERUSERS="lifecontrol_bootstrap" run_preflight
check_equal "an unparsable row exits 1" "1" "$PRE_RC"
assert_contains "the failure shows the line it could not read" "$PRE_TEXT" 'WARNING: this is not a report row'

rows '11111111-1111-1111-1111-111111111111|A-1|aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa|COMPLETED|2026-01-02 03:04:05|2026-01-03 04:05:06|not-a-number'
SUPERUSERS="lifecontrol_bootstrap" run_preflight
check_equal "a non-numeric amount exits 1 instead of being printed as a decision" "1" "$PRE_RC"

rows '11111111-1111-1111-1111-111111111111|A-1|aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa|COMPLETED|2026-01-02 03:04:05|2026-01-03 04:05:06|10.0000|extra'
SUPERUSERS="lifecontrol_bootstrap" run_preflight
check_equal "a row with more fields than the report knows exits 1" "1" "$PRE_RC"

(
	set +e
	"$WORK/scripts/preflight-payment-methods.sh" nope >"$WORK/bad-env.log" 2>&1
	printf '%s' "$?" >"$WORK/bad-env.rc"
)
check_equal "an unknown environment exits 1" "1" "$(cat "$WORK/bad-env.rc")"

# ------------------------------------------
# 8. `--help` states the question, the two verdicts and the three exit codes.
# ------------------------------------------
echo "== 8. --help is the contract an operator reads before running it"

(
	set +e
	"$WORK/scripts/preflight-payment-methods.sh" --help >"$WORK/help.log" 2>&1
	printf '%s' "$?" >"$WORK/help.rc"
)
HELP_TEXT="$(cat "$WORK/help.log")"
check_equal "--help exits 0" "0" "$(cat "$WORK/help.rc")"
assert_contains "the help states the question D-a asks" "$HELP_TEXT" \
	'which orders took money and carry no payment method'
assert_contains "the help names the state that means money was taken" "$HELP_TEXT" \
	"Money was taken means status = 'COMPLETED'"
assert_contains "the help names both verdicts" "$HELP_TEXT" 'nothing_to_decide'
assert_contains "the help names the per-row verdict" "$HELP_TEXT" 'manual'
assert_contains "the help names the three exit codes" "$HELP_TEXT" \
	'Exit codes: 0 nothing to decide, 2 at least one per-row decision, 1 the report could not run'
assert_contains "the help says it reads as a superuser and why" "$HELP_TEXT" 'FORCE ROW LEVEL SECURITY'
assert_contains "the help states the ADR step this report is the pre-flight of" "$HELP_TEXT" 'migration step 4'

# ------------------------------------------
# 9. The record's contract still matches the ADR and the feature record it quotes.
# ------------------------------------------
echo "== 9. the contract this script obeys is still the one the documents state"
# The step, not the file: squashed to one line and whitespace-collapsed on purpose, because the assertion
# is about the sentences ADR-0002 states and not about where the file happens to wrap them.
step4="$(awk '/^4\. \*\*For orders already/,/^5\. Copy/' "$ADR_0002" | tr '\n' ' ' | tr -s ' ')"
assert_contains "the ADR still backfills the orders already Completed" "$step4" 'For orders already `Completed`'
assert_contains "the ADR still dates the historical payment from the order" "$step4" \
	'coalesce(o.updated_at, o.order_date)'
assert_contains "the ADR still forbids now() for it" "$step4" 'not `now()`'
assert_contains "the ADR still reads the method from the order's own column" "$step4" \
	"the order's \`payment_method_id\`"

decision_a="$(grep -E '^\| D-a \|' "$FEATURE_RECORD" | tr '\n' ' ' | tr -s ' ')"
assert_contains "D-a still skips the order and reports it" "$decision_a" 'Skip the order and report it'
assert_contains "D-a still measures before deciding" "$decision_a" 'measure before deciding'
assert_contains "D-a still takes a non-zero count per row with the list in hand" "$decision_a" \
	'taken per row with the list in hand'

# ------------------------------------------
# Summary
# ------------------------------------------
echo "------------------------------------------"
printf 'preflight-payment-methods: %s passed, %s failed\n' "$PASS_COUNT" "$FAIL_COUNT"
if [ "$FAIL_COUNT" -ne 0 ]; then
	exit 1
fi
exit 0
