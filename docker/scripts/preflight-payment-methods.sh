#!/bin/bash
# ============================================
# LifeControl - the pre-flight report of ADR-0002 migration step 4, for D-a
# ============================================
# ADR-0002 step 4, verbatim: «For orders already `Completed`, insert one `sale_payment` per order with
# `amount = total_amount`, `direction = 'CHARGE'`, the order's `payment_method_id` and
# `paid_at = coalesce(o.updated_at, o.order_date)` — **not `now()`**, which would date every historical
# payment the day of the deploy and make every cash report by date wrong from that minute on.»
#
# That step cannot run over every `Completed` order, and `odd/tasks/money-first-class.md` records the
# reason as its open decision **D-a**: «A `Completed` order without a method has no row that satisfies
# both» — step 4's `sale_payment.method_id` is `NOT NULL` (`V83`) and `sales_orders.payment_method_id` is
# nullable (`V1__baseline_schema.sql:478`). The recommendation that record carries, verbatim: «Skip the
# order and report it — and measure before deciding. T3's pre-flight emits the count and the ids; at zero
# the decision evaporates, at a non-zero count it is taken per row with the list in hand.»
#
# The pre-flight T3 shipped, `preflight-money-parts.sh`, reports `total_amount` against the line sum —
# ADR-0002 **step 2** — so the measurement D-a depends on had no home. This script is that home: it is
# the pre-flight of **step 4**, not of step 2, and it is the only thing that answers D-a's question with
# a number instead of a guess.
#
# THE QUESTION, AND WHY IT IS THESE COLUMNS
# -----------------------------------------
# **"Money was taken" is `sales_orders.status = 'COMPLETED'`, read from the tree and not guessed.**
# `SalesOrderService#chargeSalesOrder` moves the document to `COMPLETED` at
# `life-control-api/src/main/java/com/lifecontrol/api/salesorder/service/SalesOrderService.java:461`, and
# only after the guard at `:426` has established that the order's non-voided payments cover its total.
# `PENDING` is the transient state the same transaction auto-promotes through before that move (`:495`);
# at rest it holds no money, and a rollback takes it with it. ADR-0002 step 4 names the same value in the
# same word, «orders already `Completed`», so the report and the migration cannot disagree about which
# document the backfill is about. `status` is the typed column of `V66` and its closed set is the enum's,
# enforced by the `CHECK` that file derives from `SalesOrderState` — a schema that does not have the
# column cannot answer this report at all, and refuses below instead of printing a clean dataset.
#
# **"Carries no payment method" is two facts, because the head schema records a sale's money in two
# places, and only both together are the set D-a decides per row:**
#
#   1. `sales_orders.payment_method_id IS NULL` — the column step 4 reads, nullable since
#      `V1__baseline_schema.sql:478`, and the one D-a is about;
#   2. **and no `sale_payment` row names the order** (`V83`). Since T6a the charge writes those rows
#      (`SalesOrderService.java:411`), so an order that already has one records how it was paid and
#      step 4 has nothing to invent for it — it would only state the money twice. An order with a method
#      and no payment row is step 4's normal work and not a decision; an order with neither is D-a's row.
#
# **`enabled` is deliberately not filtered.** A soft-deleted order still took money, and step 4 backfills
# by state and not by a flag the UI toggles: excluding it here would leave that money with no payment row
# and no report that ever named it.
#
# The columns printed are the ones a per-row decision needs and no more: the order id, `order_number`,
# `company_id` (the payment row's tenant), the state it was read at, `order_date`, **`updated_at`** — the
# `coalesce(o.updated_at, o.order_date)` step 4 dates `paid_at` with, printed so the operator sees the
# date the backfill would write and not the day of the deploy — and `total_amount`, the `amount` the
# invented row would carry.
#
# THREE THINGS THIS SCRIPT DOES NOT DO
# ------------------------------------
#   * **It does not run as the owner.** `sales_orders` is `FORCE ROW LEVEL SECURITY` (`V65`), so the owner
#     reads zero rows and prints a clean dataset; the reader is resolved by `_preflight_reader.sh`, the
#     same library the step-2 report sources, so the two cannot drift apart.
#   * **It does not write.** One `SELECT`, no `CREATE`, no `INSERT`, no `ALTER`, no advisory lock, no
#     session `SET` — a pre-flight that changed the data it measures would be measuring itself.
#   * **It does not answer on a schema it cannot read.** The head schema is the tree's: `V66` typed
#     state, `V80` `total_amount NUMERIC(19,4)`, `V83` `sale_payment`. On an older database the statement
#     fails to parse and this script refuses with the schema it needs and psql's own words, exit 1 —
#     because an empty answer from a database without a payment ledger is not "nothing to decide", it is
#     "nobody could be asked".
#
# Exit codes: 0 nothing needs a human (no `COMPLETED` order is missing a method); 2 at least one order
# needs a per-row decision before step 4 runs; 1 the report could not run (no superuser, or psql failed,
# or the schema is not the tree's) — never read as a clean dataset.
#
# Run: ./docker/scripts/preflight-payment-methods.sh [dev|staging|prod]
# Suite: ./docker/scripts/tests/preflight-payment-methods.test.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=_common.sh
source "$SCRIPT_DIR/_common.sh"
# The reporting reader both money pre-flights share: role candidates, the `rolsuper` probe, the database
# name and the one psql call shape. Resolved after the environment, below.
# shellcheck source=_preflight_reader.sh
source "$SCRIPT_DIR/_preflight_reader.sh"

usage() {
	cat <<'EOF'
Usage: preflight-payment-methods.sh [dev|staging|prod]

Answers the one question ADR-0002 migration step 4 cannot answer for itself, and which
odd/tasks/money-first-class.md records as its open decision D-a:

  which orders took money and carry no payment method, and what would step 4 have to invent for them?

Money was taken means status = 'COMPLETED' (SalesOrderService.java:461, after the guard at :426). A
payment method is missing when sales_orders.payment_method_id is NULL AND no sale_payment row names the
order (V83). Each such order is named with the columns a per-row decision needs: id, order_number,
company_id, state, order_date, updated_at — the coalesce(o.updated_at, o.order_date) step 4 dates paid_at
with, never now() — and total_amount, the amount the invented row would carry.

The two verdicts:

  nothing_to_decide  no COMPLETED order is missing a payment method, so step 4 has nothing to invent and
                     D-a has nothing to decide
  manual             at least one order is in that state, and D-a takes the decision per order with the
                     list in hand — skip the order and report it, or name a method — before the backfill
                     runs, not by it

Reads only, and runs as a superuser because sales_orders is FORCE ROW LEVEL SECURITY: the owner would
read zero rows and report a clean dataset.

Exit codes: 0 nothing to decide, 2 at least one per-row decision, 1 the report could not run (no
superuser, psql failed, or the schema is not the one this tree ships).
EOF
}

case "${1:-dev}" in
-h | --help)
	usage
	exit 0
	;;
esac

resolve_compose_env "${1:-dev}"
init_preflight_reader

# 2 is not 1 on purpose: 1 is "the report did not run", and a caller that treats both as failure still
# has to tell an operator "the backfill needs a human" from "the backfill could not ask the question".
EXIT_MANUAL_DECISION=2

# One statement, and the subquery is part of it rather than a second round trip: a report that asked the
# database twice could read two different datasets. The filter is the two halves of D-a's row, and the
# order is the operator's reading order — oldest document first, because that is the order the backfill
# would touch them.
read -r -d '' REPORT_SQL <<'SQL' || true
SELECT o.id,
       o.order_number,
       o.company_id,
       o.status,
       o.order_date,
       o.updated_at,
       o.total_amount
  FROM sales_orders o
 WHERE o.status = 'COMPLETED'
   AND o.payment_method_id IS NULL
   AND NOT EXISTS (
           SELECT 1
             FROM sale_payment p
            WHERE p.company_id = o.company_id
              AND p.sales_order_id = o.id)
 ORDER BY o.order_date, o.order_number
SQL

main() {
	local report_role report
	local decision_rows=0

	report_role="$(resolve_report_role)" || {
		print_error "No superuser could connect to $db_name in $POSTGRES_SERVICE, so the report did not run."
		print_error "Candidates tried in order: $REPORT_ROLE_CANDIDATES."
		print_error "sales_orders is FORCE ROW LEVEL SECURITY, so the owner reads zero rows: a non-superuser cannot answer this question, and an empty answer from one would be a false clean dataset."
		return 1
	}

	print_status "Reading $ENV as $report_role (superuser), because sales_orders is FORCE ROW LEVEL SECURITY and the owner would read zero rows."

	if ! report="$(psql_report "$report_role" --set ON_ERROR_STOP=1 --file - <<<"$REPORT_SQL" 2>&1)"; then
		# A schema older than the tree is the one failure that must never be read as a clean dataset: on a
		# database without `sale_payment` every completed order looks unpaid, and on one without `status`
		# the question cannot even be asked. psql's own words are printed, and the pieces the report needs
		# are named, so the operator knows what to migrate instead of reading "0 orders".
		if printf '%s' "$report" | grep -q 'does not exist'; then
			print_error "$db_name does not carry the schema this report reads, so it cannot answer D-a:"
			printf '%s\n' "$report" | sed 's/^/  /'
			print_error "The report reads sales_orders.status (V66), sales_orders.payment_method_id (V1:478), total_amount at NUMERIC(19,4) (V80) and sale_payment (V83)."
			print_error "An empty answer from a schema that does not have them would be a false clean dataset, so this is a refusal and not a verdict."
			return 1
		fi
		print_error "The pre-flight report failed in $db_name; nothing was reported and nothing was decided."
		printf '%s\n' "$report" | sed 's/^/  /'
		return 1
	fi

	while IFS='|' read -r order_id order_number company_id state order_date updated_at total_amount extra; do
		[ -n "${order_id:-}" ] || continue

		# A row that is not seven fields of known shape is not a report row, and guessing at it is how a
		# psql notice would become a decision. The whole line is echoed so the operator sees what psql
		# actually printed.
		if [ -n "${extra:-}" ]; then
			print_error "The report printed more fields than this script knows ($extra): $order_id|$order_number"
			return 1
		fi
		case "$order_id|$order_number|$company_id|$state|$total_amount" in
		*'||'* | *'|')
			print_error "The report printed a line this script cannot read: $order_id|$order_number|$company_id|$state|$order_date|$updated_at|$total_amount"
			return 1
			;;
		esac
		case "$total_amount" in
		*[!0-9.-]*)
			print_error "The report printed a line this script cannot read: $order_id|$order_number|$company_id|$state|$order_date|$updated_at|$total_amount"
			return 1
			;;
		esac

		printf '%-10s %s  %s  company=%s  state=%s  order_date=%s  updated_at=%s  total=%s\n' \
			manual "$order_number" "$order_id" "$company_id" "$state" "$order_date" "$updated_at" "$total_amount"
		decision_rows=$((decision_rows + 1))
	done <<<"$report"

	if [ "$decision_rows" -eq 0 ]; then
		print_success "No completed order is missing a payment method: ADR-0002 step 4 has nothing to invent, and D-a has nothing to decide."
		return 0
	fi

	print_status "Orders that took money and carry no payment method: $decision_rows"

	print_error "$decision_rows order(s) are COMPLETED with payment_method_id NULL and no sale_payment row."
	print_error "D-a: step 4 cannot state a method for them, and the decision is taken per order with this list in hand — skip the order and report it, or name a method — before the backfill runs, not by it."
	return "$EXIT_MANUAL_DECISION"
}

main
