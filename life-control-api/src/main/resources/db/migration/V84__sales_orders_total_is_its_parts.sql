-- ============================================
-- V84 — a sales order's total is its parts, exactly (ADR-0002 T6a, invariant M1)
-- ============================================
-- `V80` gave the document `subtotal`, `discount_total`, `tax_total` and `rounding_adjustment`, and it
-- stated the two checks it could add without a writer: the parts are not negative and the rounding is
-- bounded. It deliberately did not add the equality, and its header says why: M1 is a `CHECK` with
-- **exact** equality, and until something computes the parts the only rows that could satisfy it are the
-- legacy ones `V80` itself backfilled with `subtotal = total_amount` and the rest at zero.
--
-- This file adds it, in the slice that gives it a writer. The writer is
-- `SalesOrderService#recomputeBreakdown`, which sums the lines the document owns and writes the four parts
-- plus the total under the order's row lock, in the same transaction as the line write (M2). Without the
-- writer the constraint would refuse every new order; with it, the two are the same statement.
--
-- WHY NO PRE-FLIGHT REPORT, WHEN MIGRATION STEP 2 DEMANDED ONE. Step 2's report exists for the rows a
-- constraint is about to refuse, and `V80` already ran it: every row it could not reconcile was recorded as
-- `subtotal = total_amount` with the other three parts at zero, which satisfies this equality by
-- construction. The report that mattered is `docker/scripts/preflight-money-parts.sh` (T3), and it covers
-- the drift `ck_order_total` cannot see — a document whose parts add up but disagree with its own lines.
--
-- WHY THE ROUNDING IS STILL BOUNDED BY A CONSTANT. M8 refuses a rounding difference larger than one minor
-- unit, and `ck_order_rounding_bounded` (V80) bounds it by `1` with no reference to the currency catalog,
-- because a `CHECK` cannot read another table. The catalog's own bound is the domain guard's, and it lives
-- in the service that knows the document's currency. This constraint is the equality M1 states and nothing
-- more; it does not restate the bound `V80` already made.
ALTER TABLE sales_orders
    ADD CONSTRAINT ck_order_total
        CHECK (total_amount = subtotal - discount_total + tax_total + rounding_adjustment);

COMMENT ON CONSTRAINT ck_order_total ON sales_orders IS
    'ADR-0002 invariant M1: the document states its parts, and the four of them add up to total_amount '
    'exactly. A difference of a ten-thousandth is a bug and not a rounding artifact (decision 3).';
