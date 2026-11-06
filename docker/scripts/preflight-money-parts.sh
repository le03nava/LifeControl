#!/bin/bash
# ============================================
# LifeControl - the pre-flight report of ADR-0002 migration step 2, before the constraint
# ============================================
# ADR-0002 step 2, verbatim: «Pre-flight, before any constraint. Report every order whose `total_amount`
# disagrees with its line sum, because those are the rows `ck_order_total` will refuse … Without this
# step the constraint aborts the deploy on production data instead of on a report.» This script is that
# report: it reads, it names a verdict per order, and it writes nothing.
#
# The two verdicts, verbatim from the same step: «A drift within one minor unit is `rounding_adjustment`
# on that row. A larger drift is a manual decision per row, taken before the migration runs and not by
# the migration.»
#
# Three things about this script are decisions rather than transcription, and each one is measurably the
# only sane option on the tree this runs against:
#
#   1. **It reads as a superuser, and that is not a convenience.** `sales_orders` is armed with
#      `ENABLE` + `FORCE` ROW LEVEL SECURITY (`V65`), and `apply_tenant_isolation` makes the owner
#      subject to the policy too. Run as `lifecontrol_owner` — the role Flyway uses — this report reads
#      **zero rows and prints a clean dataset**, which is the exact failure `V54` and `V64` already
#      record. So the reader is resolved by asking `pg_roles` for `rolsuper`, in the same order the roles
#      wrapper uses, and the owner is deliberately **not** a candidate: a candidate that connects but
#      reads nothing is worse than no answer.
#   2. **The line sum is read from the column, not re-derived.** The ADR's block computes each line's
#      candidate inline because, when it was written, no column held it. `V81` derives that same
#      expression into `sales_order_items.subtotal` and enforces it with `ck_item_subtotal`, so this
#      report reads the column the constraint reads: two ways of computing one line's money is how a
#      report and a constraint end up disagreeing.
#   3. **The tolerance is the order's currency, not a literal.** One minor unit is `0.01` for `MXN`
#      (`currency.minor_units = 2`) and a whole unit for a currency with none — `M8` puts the currency
#      half of that bound on the domain for the same reason: a `CHECK` cannot read the catalog.
#      `ck_order_rounding_bounded` (`abs(rounding_adjustment) <= 1`, `V80`) is the currency-agnostic
#      half, and it is the loosest bound any currency allows, not this report's threshold.
#
# The comparison itself is done in ten-thousandths — the scale every part carries since `V80` — as
# integers, so no rounding and no floating point stands between the measured drift and the verdict.
#
# Exit codes: 0 nothing needs a human (no drift, or every drifting order is absorbable by its own
# `rounding_adjustment`); 2 at least one order needs a manual decision before the migration runs;
# 1 the report could not run (no superuser, or psql failed) — never read as a clean dataset.
#
# Run: ./docker/scripts/preflight-money-parts.sh [dev|staging|prod]
# Suite: ./docker/scripts/tests/preflight-money-parts.test.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=_common.sh
source "$SCRIPT_DIR/_common.sh"

usage() {
	cat <<'EOF'
Usage: preflight-money-parts.sh [dev|staging|prod]

Reports every sales order whose total_amount disagrees with the sum of its enabled lines' money, which is
the set of rows ADR-0002's ck_order_total will refuse once T6 writes the parts. Each order is named with
one of two verdicts:

  rounding_adjustment  the drift is within one minor unit of the order's currency, so the migration
                       absorbs it on that row and no human decides
  manual               the drift is larger, and step 2 says the decision is taken per row, before the
                       migration runs — not by the migration

Reads only, and runs as a superuser because sales_orders is FORCE ROW LEVEL SECURITY: the owner would
read zero rows and report a clean dataset.

Exit codes: 0 nothing to decide, 2 at least one manual decision, 1 the report could not run.
EOF
}

case "${1:-dev}" in
-h | --help)
	usage
	exit 0
	;;
esac

resolve_compose_env "${1:-dev}"

POSTGRES_SERVICE="lifecontrol-postgres"
# The maintenance superuser is the first candidate for a reason `V64` records: PostgreSQL refuses to take
# SUPERUSER away from the role initdb created, so on any volume this repository initializes,
# `lifecontrol_bootstrap` is a superuser with no password that reaches the database over the container's
# local socket. `postgres` covers an image that still has it, and the declared initdb role covers a volume
# whose key names something else. The owner is not a candidate — see the header.
REPORT_ROLE_CANDIDATES="lifecontrol_bootstrap postgres"
SUPERUSER_PROBE="SELECT 1 FROM pg_roles WHERE rolname = current_user AND rolsuper"
# 2 is not 1 on purpose: 1 is "the report did not run", and a caller that treats both as failure still
# has to tell an operator "the deploy needs a human" from "the deploy could not ask the question".
EXIT_MANUAL_DECISION=2

db_name="$(get_env_var LIFECONTROL_POSTGRES_DB)"
if [ -z "$db_name" ]; then
	db_name="lifecontrol"
fi

# A declared role that is already a candidate is not tried twice: the report would ask the same role the
# same question and print the same answer, which reads as two candidates to an operator debugging it.
declared_role="$(get_env_var LIFECONTROL_POSTGRES_USER)"
case " $REPORT_ROLE_CANDIDATES " in
*" $declared_role "*) ;;
*) [ -z "$declared_role" ] || REPORT_ROLE_CANDIDATES="$REPORT_ROLE_CANDIDATES $declared_role" ;;
esac

psql_report() {
	local username="$1"
	shift
	compose_run exec -T "$POSTGRES_SERVICE" psql \
		--username "$username" --dbname "$db_name" \
		--no-psqlrc --no-password --tuples-only --no-align "$@"
}

# resolve_report_role echoes the first candidate that connects AND answers `rolsuper` with one row. A
# candidate that connects but is not a superuser prints no row, which is the shape of the defect this
# whole resolution exists for: the report would run and find nothing.
resolve_report_role() {
	local candidate
	for candidate in $REPORT_ROLE_CANDIDATES; do
		if [ "$(psql_report "$candidate" --command "$SUPERUSER_PROBE" 2>/dev/null)" = "1" ]; then
			printf '%s' "$candidate"
			return 0
		fi
	done
	return 1
}

# The report. Every amount on both tables is `NUMERIC(19,4)` since `V80`/`V81`, so the drift is exact and
# `<> 0` is the honest filter: `V80`'s own self-check for the parts equation asks the same question the
# same way, and a drift of one ten-thousandth is a row the constraint refuses, not a rounding artifact.
read -r -d '' REPORT_SQL <<'SQL' || true
WITH line_money AS (
    SELECT i.sales_order_id,
           coalesce(sum(i.subtotal), 0)         AS line_subtotal,
           coalesce(sum(i.discount_applied), 0) AS line_discount
      FROM sales_order_items i
     WHERE i.enabled
     GROUP BY i.sales_order_id
), measured AS (
    SELECT o.id,
           o.order_number,
           o.currency,
           o.total_amount,
           coalesce(l.line_subtotal, 0) - coalesce(l.line_discount, 0) AS line_net
      FROM sales_orders o
      LEFT JOIN line_money l ON l.sales_order_id = o.id
)
SELECT m.id,
       m.order_number,
       m.currency,
       m.total_amount,
       m.line_net,
       m.total_amount - m.line_net                                AS drift,
       ((m.total_amount - m.line_net) * 10000)::bigint             AS drift_units,
       power(10::numeric, (4 - c.minor_units)::numeric)::bigint    AS tolerance_units
  FROM measured m
  JOIN currency c ON c.code = m.currency
 WHERE m.total_amount - m.line_net <> 0
 ORDER BY abs(m.total_amount - m.line_net) DESC, m.order_number
SQL

main() {
	local report_role report
	local adjust_rows=0 manual_rows=0

	report_role="$(resolve_report_role)" || {
		print_error "No superuser could connect to $db_name in $POSTGRES_SERVICE, so the report did not run."
		print_error "Candidates tried in order: $REPORT_ROLE_CANDIDATES."
		print_error "sales_orders is FORCE ROW LEVEL SECURITY, so the owner reads zero rows: a non-superuser cannot answer this question, and an empty answer from one would be a false clean dataset."
		return 1
	}

	print_status "Reading $ENV as $report_role (superuser), because sales_orders is FORCE ROW LEVEL SECURITY and the owner would read zero rows."

	if ! report="$(psql_report "$report_role" --set ON_ERROR_STOP=1 --file - <<<"$REPORT_SQL" 2>&1)"; then
		print_error "The pre-flight report failed in $db_name; nothing was reported and nothing was decided."
		printf '%s\n' "$report" | sed 's/^/  /'
		return 1
	fi

	while IFS='|' read -r order_id order_number currency total_amount line_net drift drift_units tolerance_units extra; do
		[ -n "${order_id:-}" ] || continue

		# A row that is not eight fields of known shape is not a report row, and guessing at it is how a
		# psql notice would become a verdict. The whole line is echoed so the operator sees what psql
		# actually printed.
		case "${drift_units#-}" in
		'' | *[!0-9]*)
			print_error "The report printed a line this script cannot read: $order_id|$order_number|$currency|$total_amount|$line_net|$drift|$drift_units|$tolerance_units|$extra"
			return 1
			;;
		esac
		case "${tolerance_units#-}" in
		'' | *[!0-9]*)
			print_error "The report printed a line this script cannot read: $order_id|$order_number|$currency|$total_amount|$line_net|$drift|$drift_units|$tolerance_units|$extra"
			return 1
			;;
		esac
		if [ -n "${extra:-}" ]; then
			print_error "The report printed more fields than this script knows ($extra): $order_id|$order_number"
			return 1
		fi

		# One minor unit, measured in the order's own currency, in ten-thousandths. `|| true` because a
		# one-ten-thousandth drift is a verdict, not an error.
		if [ "${drift_units#-}" -le "$tolerance_units" ]; then
			printf '%-20s %s  %s  total=%s  lines=%s  drift=%s\n' \
				rounding_adjustment "$order_number" "$currency" "$total_amount" "$line_net" "$drift"
			adjust_rows=$((adjust_rows + 1))
		else
			printf '%-20s %s  %s  total=%s  lines=%s  drift=%s\n' \
				manual "$order_number" "$currency" "$total_amount" "$line_net" "$drift"
			manual_rows=$((manual_rows + 1))
		fi
	done <<<"$report"

	if [ $((adjust_rows + manual_rows)) -eq 0 ]; then
		print_success "No order disagrees with its line sum: nothing for ck_order_total to refuse."
		return 0
	fi

	print_status "Orders that disagree with their line sum: $((adjust_rows + manual_rows)) ($adjust_rows rounding_adjustment, $manual_rows manual)"

	if [ "$manual_rows" -gt 0 ]; then
		print_error "$manual_rows order(s) drift further than one minor unit of their own currency."
		print_error "ADR-0002 step 2: that decision is taken per order, before the migration runs, and not by the migration."
		return "$EXIT_MANUAL_DECISION"
	fi

	print_success "Every drifting order is within one minor unit of its currency: rounding_adjustment absorbs them, no decision needed."
	return 0
}

main
