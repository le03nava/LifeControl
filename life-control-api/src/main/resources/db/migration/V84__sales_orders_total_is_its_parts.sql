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
-- WHY THIS FILE ALSO RESTATES ROWS BEFORE IT CONSTRAINS THEM, WHEN MIGRATION STEP 2 DEMANDED A REPORT.
-- Step 2's report exists for the rows a constraint is about to refuse, and `V80` ran one — for the rows the
-- table held when `V80` ran: `V80:193` recorded each of them as `subtotal = total_amount` with the other
-- three parts at zero, which satisfies this equality by construction. What that report could not see is the
-- writer that has run ever since. `origin/main`'s `recalculateTotalAmount` writes `total_amount` from the
-- lines' unit prices and never touches a part, and the four parts are `NOT NULL DEFAULT 0`, so **every order
-- created after the `V80` release** carries `subtotal = discount_total = tax_total = rounding_adjustment =
-- 0` beside a non-zero `total_amount`. Adding the constraint over those rows aborts the deploy on production
-- data — the failure step 2 forbids — and `docker/scripts/preflight-money-parts.sh` cannot report it: it
-- compares the total with the sum of the lines, and these rows' parts agree with nothing at all. So this
-- file states them the way `V80` stated the legacy ones, before the constraint judges them.
--
-- Why that is a backfill and not the report step 2 asks for, in one sentence: step 2's report answers «do the
-- parts disagree with the lines», which is a question about data a `CHECK` cannot read (M2 sums another
-- table) and the one `preflight-money-parts.sh` (T3) still answers; this file's question is narrower and
-- answerable from the row alone, «do the four parts add up to the total», and a report about a different
-- question cannot satisfy a constraint that asks this one.
--
-- WHY THE BACKFILL IS NARROWER THAN «EVERY ROW», AND WHY THAT IS THE POINT. The `WHERE` names exactly the
-- shape the pre-T6a writer produces — all four parts zero, the total stated — and nothing else, so a
-- document that already states its parts is never restated: the only rows this touches are the ones that
-- carried no breakdown at all. It cannot move a total either: it reads `total_amount` and writes only
-- `subtotal`, so the number the old writer computed is the number the document still states (ADR-0002 step
-- 3). And `ck_order_parts_not_negative` keeps holding: the subtotal it writes is the total the writer summed
-- from non-negative line amounts, and the three parts it leaves alone are the zeroes that already satisfied
-- the check. A stated total below zero is the one shape this copy cannot state without a negative part, and
-- it is not reachable through the writer this backfill exists for.
--
-- WHY THE WINDOW IS OPENED AGAIN. `V65` armed **and** FORCEd `sales_orders`, and `apply_tenant_isolation`
-- reaches the owner, so an `UPDATE` run as `lifecontrol_owner` — the role delivery migrates with — matches
-- zero rows and reports success while every row it meant to reach is still zero. `V80` opened this window
-- for the same reason and closed it again; this file needs it for the same one. The `ADD CONSTRAINT` below
-- is also the assertion that the window worked: a row the backfill missed is a row the constraint refuses,
-- by name.
--
-- WHY THE ROUNDING IS STILL BOUNDED BY A CONSTANT. M8 refuses a rounding difference larger than one minor
-- unit, and `ck_order_rounding_bounded` (V80) bounds it by `1` with no reference to the currency catalog,
-- because a `CHECK` cannot read another table. The catalog's own bound is the domain guard's, and it lives
-- in the service that knows the document's currency. This constraint is the equality M1 states and nothing
-- more; it does not restate the bound `V80` already made.

-- ─── 1. the window ────────────────────────────────────────────────────────────────────────────────
ALTER TABLE sales_orders NO FORCE ROW LEVEL SECURITY;

-- ─── 2. the rows the pre-T6a writer left with a total and no breakdown ────────────────────────────
-- `V80:193` stated the rows that existed when it ran; these are the ones written since, in the same shape
-- and by the same reasoning: the document's total is its subtotal, and the three parts it never had stay
-- zero. Nothing is re-derived from the lines here — that is M2's forward-only debt, and re-deriving it would
-- move a stored total.
UPDATE sales_orders
   SET subtotal = total_amount
 WHERE subtotal = 0
   AND discount_total = 0
   AND tax_total = 0
   AND rounding_adjustment = 0
   AND total_amount <> 0;

-- `V68`'s constraint trigger on this table is `DEFERRABLE INITIALLY DEFERRED`, so the `UPDATE` above queued
-- its judgement for COMMIT — and PostgreSQL refuses an `ALTER TABLE` while trigger events are pending
-- (SQLSTATE 55006, measured on this file's first run at the `FORCE` below). `SET CONSTRAINTS ALL IMMEDIATE`
-- runs the judgement now, inside the window, exactly as `V80` does for the same statement on the same table:
-- the check has to see the rows before the `FORCE` ALTER puts the policy back in front of the owner's reads.
-- It holds for the rest of the transaction, so the `ALTER TABLE`s below queue nothing.
SET CONSTRAINTS ALL IMMEDIATE;

ALTER TABLE sales_orders FORCE ROW LEVEL SECURITY;

-- ─── 3. the equality M1 states ────────────────────────────────────────────────────────────────────
ALTER TABLE sales_orders
    ADD CONSTRAINT ck_order_total
        CHECK (total_amount = subtotal - discount_total + tax_total + rounding_adjustment);

COMMENT ON CONSTRAINT ck_order_total ON sales_orders IS
    'ADR-0002 invariant M1: the document states its parts, and the four of them add up to total_amount '
    'exactly. A difference of a ten-thousandth is a bug and not a rounding artifact (decision 3).';
