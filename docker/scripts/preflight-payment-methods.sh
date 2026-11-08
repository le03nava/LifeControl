#!/bin/bash
# ============================================
# LifeControl - the pre-flight report of ADR-0002 migration steps 4 and 6
# ============================================
# ADR-0002 step 4, verbatim: «For orders already `Completed`, insert one `sale_payment` per order with
# `amount = total_amount`, `direction = 'CHARGE'`, the order's `payment_method_id` and
# `paid_at = coalesce(o.updated_at, o.order_date)` — **not `now()`**, which would date every historical
# payment the day of the deploy and make every cash report by date wrong from that minute on.»
#
# ADR-0002 step 6, verbatim: «Drop `sales_orders.payment_method_id` once the payment rows are populated and
# every reader has moved.»
#
# This script answers the question both steps depend on and neither can answer for itself: **which
# `COMPLETED` sales orders would make `V87` refuse to drop `sales_orders.payment_method_id`, and why.** The
# two steps are two sides of that one measurement, which is why the report is the pre-flight of the pair and
# not of one of them: step 4 cannot state a payment for the rows this names (`sale_payment.method_id` is
# `NOT NULL`, `V83:79`), and step 6 cannot drop the column while any of them is still unpaid, because on
# those rows the column is the only record that the sale took money (`V87`'s guard).
#
# THE QUESTION, AND WHY IT IS THE GUARD'S SHAPE
# ---------------------------------------------
# **"Money was taken" is `status = 'COMPLETED'`, read from the tree and not guessed.**
# `SalesOrderService#chargeSalesOrder` moves the document to `COMPLETED` at
# `life-control-api/src/main/java/com/lifecontrol/api/salesorder/service/SalesOrderService.java:461`, and
# only after the guard at `:426` has established that the order's non-voided payments cover its total.
# `PENDING` is the transient state the same transaction auto-promotes through before that move (`:495`); at
# rest it holds no money, and a rollback takes it with it. ADR-0002 step 4 names the same value in the same
# word, «orders already `Completed`», so the report and the migration cannot disagree about which document
# the backfill is about. `status` is the typed column of `V66` and its closed set is the enum's, enforced by
# the `CHECK` that file derives from `SalesOrderState` — a schema that does not have the column cannot
# answer this report at all, and refuses below instead of printing a clean dataset.
#
# **"Blocks the drop" is exactly `V87`'s predicate: a `COMPLETED` order with no non-voided `sale_payment`
# row.** `sale_payment_void` is the payment's only transition (`V83:7-10`), so "not voided" is the absence
# of a void row naming the payment — the same shape `SalePaymentRepository.netPaid` sums and the charge's
# own guard reads. It is deliberately **not** `net = total`: a fully refunded sale legitimately has a net of
# zero and must not block anything, because it does have rows that say how it was paid. This report and
# `V87`'s guard answer the same question, so an operator cannot be told "nothing to decide" by one and
# refused by the other — which is the defect this definition removed: the guard refuses on the payment rows
# alone, and a report that also required `payment_method_id IS NULL` was blind to a `COMPLETED` order with a
# total of 0 that did carry a method.
#
# **Each blocking order states one of two reasons, disjoint on purpose** so the two counts add up to the
# number of rows the operator has to decide and no order is named twice:
#
#   * `no_method` — the document states no payment method (`payment_method_id IS NULL`), and
#     `sale_payment.method_id` is `NOT NULL` (`V83:79`), so `V86` may not invent one. A sentinel method in
#     a global catalog would be visible to every tenant and would state how a sale was paid when the data
#     never said — the reasoning `odd/tasks/money-first-class.md` records under **D-a**, and the set that
#     decision's report was written for. **This reason only exists while the column exists**; after `V87`
#     the same row is indistinguishable from one whose method was simply not backfilled, which is why the
#     report reads the column's presence from the catalog instead of assuming it.
#   * `zero_total` — the document states a method and its total is 0, and `sale_payment.amount` carries
#     `CHECK (amount > 0)` (`V83:83`), so no row can state it. The only row that would fit is a
#     ten-thousandth that is not money, or a row stating what nobody paid.
#
# A `COMPLETED` order with a method, a positive total and no payment row is **not** listed: `V86` states it,
# so there is nothing for a human to decide. That is the third set the backfill distinguishes, and leaving
# it out is what makes this report a decision list instead of an inventory.
#
# **`enabled` is deliberately not filtered.** A soft-deleted order still took money, and the backfill goes by
# state and not by a flag the UI toggles: excluding it here would leave that money with no payment row, no
# report that ever named it, and a `V87` refusal with no list to decide from.
#
# The columns printed are the ones a per-row decision needs and no more: the order id, `order_number`,
# `company_id` (the payment row's tenant), the state it was read at, `order_date`, **`updated_at`** — the
# `coalesce(o.updated_at, o.order_date)` step 4 dates `paid_at` with, printed so the operator sees the date
# the backfill would write and not the day of the deploy — `total_amount`, the `amount` the invented row
# would carry, and the reason.
#
# ONE STATEMENT THAT PARSES BEFORE AND AFTER THE DROP
# ---------------------------------------------------
# `V87` drops `sales_orders.payment_method_id`, so the two schemas this report has to read are "the column is
# there" and "the column is gone", and the question is the same in both: which completed sales have no
# non-voided payment row for a reason a human must settle. A statement that named the column as a column
# would stop parsing at `V87` — and a report that cannot parse exits 1, which an operator reads as "the
# database is behind" while it is in fact ahead.
#
# **Shape (a) of the two the task allowed: one SELECT, and the column's presence is asked of the catalog.**
# Two statements would have broken the read-only, one-statement contract this report and its suite keep —
# `docker/scripts/tests/preflight-payment-methods.test.sh` pins exactly one statement and no write keyword —
# and a second round trip could also read two different datasets. So the statement reads the document's
# method through its **row image**, `to_jsonb(o) ->> 'payment_method_id'`, which is a key lookup on a JSON
# document and not a column reference: on a schema without the column it yields `NULL` instead of failing to
# parse. That `NULL` is *not* the answer by itself — a dropped column and a stated-nothing column look the
# same — so a first CTE asks `information_schema.columns` whether the column still exists, and `no_method`
# is only reachable while it does. Before the drop both reasons can appear; after it, only `zero_total`.
# **The report reads the same question in both schemas; it does not read a different question more quietly.**
#
# FOUR THINGS THIS SCRIPT DOES NOT DO
# -----------------------------------
#   * **It does not run as the owner.** `sales_orders` is `FORCE ROW LEVEL SECURITY` (`V65`), so the owner
#     reads zero rows and prints a clean dataset; the reader is resolved by `_preflight_reader.sh`, the same
#     library the step-2 report sources, so the two cannot drift apart. The catalog probe is read as that
#     superuser too, so privilege filtering cannot hide the column either.
#   * **It does not write.** One `SELECT`, no `CREATE`, no `INSERT`, no `ALTER`, no advisory lock, no
#     session `SET` — a pre-flight that changed the data it measures would be measuring itself.
#   * **It does not decide.** Naming the row and its reason is the whole output; the decision is per order,
#     with this list in hand, and taken by an operator.
#   * **It does not answer on a schema it cannot read.** The head schema is the tree's: `V66` typed state,
#     `V80` `total_amount NUMERIC(19,4)`, `V83` `sale_payment`. On an older database the statement fails to
#     parse and this script refuses with the schema it needs and psql's own words, exit 1 — because an empty
#     answer from a database without a payment ledger is not "nothing to decide", it is "nobody could be
#     asked".
#
# Exit codes: 0 nothing needs a human (no `COMPLETED` order is blocked); 2 at least one order needs a
# per-row decision before the backfill can state it and `V87` can drop the column; 1 the report could not
# run (no superuser, or psql failed, or the schema is not the tree's) — never read as a clean dataset.
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

Answers the question ADR-0002 migration step 6 asks before it runs, and which its migration step 4 cannot
answer for itself: which COMPLETED sales orders would make V87 refuse to drop
sales_orders.payment_method_id, and why?

Money was taken means status = 'COMPLETED' (SalesOrderService.java:461, after the guard at :426). A blocking
order is a COMPLETED order with no non-voided sale_payment row — the shape V87's guard asks about, because
V83 makes a void an appended sale_payment_void row and never a status column. A fully refunded sale has a
net of zero and does not block anything: it does have rows that say how it was paid.

Each blocking order states one of two reasons, disjoint so the counts add up and no order is named twice:

  no_method   the document states no payment method, and sale_payment.method_id is NOT NULL (V83), so V86
              may not invent one. This is the set D-a of odd/tasks/money-first-class.md decides per row:
              skip the order and report it, or name the method it took. The reason only exists while the
              column exists.
  zero_total  the document states a method and its total is 0, and sale_payment.amount carries
              CHECK (amount > 0) (V83), so no row can state it.

A COMPLETED order with a method, a positive total and no payment row is not listed: V86 states it, so there
is nothing for a human to decide.

Each order is named with the columns a per-row decision needs: id, order_number, company_id, state,
order_date, updated_at — the coalesce(o.updated_at, o.order_date) step 4 dates paid_at with, never now() —
total_amount, the amount the payment row would carry, and the reason.

The two verdicts:

  nothing_to_decide  no COMPLETED order is blocked, so the backfill has nothing to invent and step 6 can
                     drop the column
  manual             at least one order is blocked, and the decision is taken per order with the list in
                     hand, before the backfill runs, not by it

One statement, and it reads the same question before and after the drop: the document's method is read
through its row image and the catalog says whether the column still exists, so the statement parses on this
tree and on the one V87 leaves behind. After the drop only zero_total can appear.

Reads only, and runs as a superuser because sales_orders is FORCE ROW LEVEL SECURITY: the owner would read
zero rows and report a clean dataset.

Exit codes: 0 nothing to decide, 2 at least one per-row decision, 1 the report could not run (no superuser,
psql failed, or the schema is not the one this tree ships).
EOF
}

case "${1:-dev}" in
-h | --help)
	usage
	exit 0
	;;
esac

resolve_compose_env "${1:-dev}"
db_name="$(preflight_db_name)"
init_preflight_reader

# 2 is not 1 on purpose: 1 is "the report did not run", and a caller that treats both as failure still
# has to tell an operator "the backfill needs a human" from "the backfill could not ask the question".
EXIT_MANUAL_DECISION=2

# One statement, and the two CTEs are part of it rather than two round trips: a report that asked the
# database twice could read two different datasets. Read it top to bottom:
#
#   shape      asks the catalog whether `payment_method_id` still exists. `to_jsonb` below yields NULL for a
#              key that is absent, so without this the dropped column would read as "states no method" on
#              every row and the report would name the whole dataset for a reason that no longer applies.
#   unpaid     V87's predicate, which is the question: COMPLETED orders with no non-voided payment row.
#              `to_jsonb(o) ->> 'payment_method_id'` is a key lookup on the row's JSON image, not a column
#              reference, so the statement parses on both schemas.
#   blocking   one reason per order, first match wins, so the two sets are disjoint by construction. A row
#              with neither reason is NULL here and drops out: V86 states it.
#
# The order is the operator's reading order — oldest document first, because that is the order the backfill
# would touch them.
read -r -d '' REPORT_SQL <<'SQL' || true
WITH shape AS (
    -- The schema is deliberately not filtered. One table of this name exists in this database, and a
    -- `current_schema()` filter would answer "the column is gone" the moment `search_path` resolved
    -- somewhere else — which is the silent answer, on the schema where both reasons still apply. The probe
    -- runs as the superuser the reader resolved, so the view's privilege filter cannot hide the column
    -- either.
    SELECT EXISTS (
               SELECT 1
                 FROM information_schema.columns c
                WHERE c.table_name = 'sales_orders'
                  AND c.column_name = 'payment_method_id') AS has_method_column
),
unpaid AS (
    SELECT o.id,
           o.order_number,
           o.company_id,
           o.status,
           o.order_date,
           o.updated_at,
           o.total_amount,
           to_jsonb(o) ->> 'payment_method_id' IS NULL AS states_no_method
      FROM sales_orders o
     WHERE o.status = 'COMPLETED'
       AND NOT EXISTS (
               SELECT 1
                 FROM sale_payment p
                WHERE p.company_id = o.company_id
                  AND p.sales_order_id = o.id
                  AND NOT EXISTS (
                          SELECT 1
                            FROM sale_payment_void v
                           WHERE v.payment_id = p.id
                             AND v.company_id = p.company_id))
),
blocking AS (
    SELECT u.id,
           u.order_number,
           u.company_id,
           u.status,
           u.order_date,
           u.updated_at,
           u.total_amount,
           CASE
               WHEN s.has_method_column AND u.states_no_method THEN 'no_method'
               WHEN u.total_amount = 0 THEN 'zero_total'
           END AS reason
      FROM unpaid u
     CROSS JOIN shape s
)
SELECT id,
       order_number,
       company_id,
       status,
       order_date,
       updated_at,
       total_amount,
       reason
  FROM blocking
 WHERE reason IS NOT NULL
 ORDER BY order_date, order_number
SQL

main() {
	local report_role report
	local decision_rows=0 no_method_rows=0 zero_total_rows=0

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
			print_error "$db_name does not carry the schema this report reads, so it cannot answer which orders block the drop:"
			printf '%s\n' "$report" | sed 's/^/  /'
			print_error "The report reads sales_orders.status (V66), total_amount at NUMERIC(19,4) (V80) and sale_payment/sale_payment_void (V83); payment_method_id (V1:478) is read through the row image, so it is optional, and V87 is the migration that removes it."
			print_error "An empty answer from a schema that does not have them would be a false clean dataset, so this is a refusal and not a verdict."
			return 1
		fi
		print_error "The pre-flight report failed in $db_name; nothing was reported and nothing was decided."
		printf '%s\n' "$report" | sed 's/^/  /'
		return 1
	fi

	while IFS='|' read -r order_id order_number company_id state order_date updated_at total_amount reason extra; do
		[ -n "${order_id:-}" ] || continue

		# A row that is not eight fields of known shape is not a report row, and guessing at it is how a
		# psql notice would become a decision. The whole line is echoed so the operator sees what psql
		# actually printed.
		if [ -n "${extra:-}" ]; then
			print_error "The report printed more fields than this script knows ($extra): $order_id|$order_number"
			return 1
		fi
		case "$order_id|$order_number|$company_id|$state|$total_amount|$reason" in
		*'||'* | *'|')
			print_error "The report printed a line this script cannot read: $order_id|$order_number|$company_id|$state|$order_date|$updated_at|$total_amount|$reason"
			return 1
			;;
		esac
		case "$total_amount" in
		*[!0-9.-]*)
			print_error "The report printed a line this script cannot read: $order_id|$order_number|$company_id|$state|$order_date|$updated_at|$total_amount|$reason"
			return 1
			;;
		esac
		# The reason is a closed set of two, because it is the half of the decision an operator acts on: a
		# third value would be read as "already handled" by a person scanning the list.
		case "$reason" in
		no_method) no_method_rows=$((no_method_rows + 1)) ;;
		zero_total) zero_total_rows=$((zero_total_rows + 1)) ;;
		*)
			print_error "The report printed a reason this script does not know ($reason): $order_id|$order_number|$company_id|$state|$order_date|$updated_at|$total_amount|$reason"
			return 1
			;;
		esac

		printf '%-10s %s  %s  company=%s  state=%s  order_date=%s  updated_at=%s  total=%s  reason=%s\n' \
			manual "$order_number" "$order_id" "$company_id" "$state" "$order_date" "$updated_at" \
			"$total_amount" "$reason"
		decision_rows=$((decision_rows + 1))
	done <<<"$report"

	if [ "$decision_rows" -eq 0 ]; then
		print_success "No completed order would block the drop: nothing in the two sets this report names (no_method, zero_total), so ADR-0002 step 6 can drop sales_orders.payment_method_id and step 4 has nothing to invent — D-a has nothing to decide."
		return 0
	fi

	print_status "Orders that block the drop: $decision_rows (no_method: $no_method_rows, zero_total: $zero_total_rows)"

	print_error "$decision_rows COMPLETED order(s) have no non-voided sale_payment row, so V87 cannot drop sales_orders.payment_method_id for a reason a human decides:"
	print_error "  no_method  ($no_method_rows): the document states no payment method, and sale_payment.method_id is NOT NULL, so V86 may not invent one — skip the order and report it, or name the method it took; the column that states it is about to be dropped."
	print_error "  zero_total ($zero_total_rows): the document's total is 0, and sale_payment.amount carries CHECK (amount > 0), so no row can state it."
	print_error "The decision is taken per order with this list in hand — before the backfill runs, not by it."
	return "$EXIT_MANUAL_DECISION"
}

main
