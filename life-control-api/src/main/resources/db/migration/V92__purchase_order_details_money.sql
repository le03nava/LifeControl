-- ============================================
-- V92 — a purchase-order line's money has one definition, at four decimals, derived by one function
--        (S2 and S6 of odd/tasks/purchase-order-money.md, T1)
-- ============================================
-- `purchase_order_details.total` is `DECIMAL(12,2)` since `V1:344` and its only writer is
-- `PurchaseOrderService.lineTotal:1038-1040` — `unitPrice.multiply(quantity).setScale(2, HALF_UP)`, called at
-- `:243`, `:333`, `:446` and `:491`. Two decimals is a rounding the line cannot express and the document
-- cannot sum: `sales_order_items` moved to four in `V81` and `V85` for exactly this reason.
--
-- WHY THE BASE IS A SECOND COLUMN AND NOT A LATER MEANING OF THE SAME ONE. The successor unit
-- (`odd/tasks/purchase-order-line-tax.md`) adds `tax_amount` and states `total = subtotal + tax_amount`. A
-- single `total` that starts including a tax once rows exist is a column whose meaning changes under the rows
-- already stored, and every document total would then have to be re-derived a second time. So the base gets
-- its own column now, while the total still equals it, and the successor adds a term instead of a meaning.
--
-- WHY `total` IS NO LONGER WRITTEN BY JAVA. A derived column the application also sends is a column with two
-- writers, and the value the mapping carries is the value the row gets on any statement this trigger's column
-- list does not name — the stale-mapping trap `V81`'s javadoc records for sales. `PurchaseOrderDetail.total`
-- therefore becomes `insertable = false, updatable = false` and `PurchaseOrderService.lineTotal` is deleted in
-- the same commit, so the schema is the only thing that states the number.
--
-- WHY THE BACKFILL RESTATES EVERY ROW, AND WHAT THAT COSTS. `V1`'s writer rounded to two decimals, so a row
-- whose product has a fractional quantity carries a total the four-decimal expression disagrees with by up to
-- 0.005. Restating it from the row's own parts — `unit_price * quantity`, both already on the row — gives the
-- column one meaning after the deploy instead of two that every report has to filter by age, and the declared
-- cost is exactly that 0.005. It touches **money on financial rows**, which is why this file is `Risk: item 1`
-- in its record; the alternative is two truths for one column, which is worse and permanent.
--
-- WHY `SET CONSTRAINTS ALL IMMEDIATE` IS NOT OPTIONAL HERE. `trg_purchase_order_details_transition_recorded`
-- (`V69:84-87`) is a `CONSTRAINT TRIGGER ... AFTER INSERT OR UPDATE ... DEFERRABLE INITIALLY DEFERRED`, so the
-- `UPDATE` below queues its judgement for COMMIT — and PostgreSQL refuses an `ALTER TABLE` while trigger events
-- are pending (`SQLSTATE 55006`, measured in `V80`, `V84` and `V85` on the same statement shape). Running them
-- here also means the two statements below queue nothing. The function itself tolerates a money-only update:
-- `V85` restated every `sales_order_items` row with the same pair of triggers present.
--
-- WHY THE FUNCTION MAY READ `NEW.subtotal`. `V85:69-71` and `V90` warn against reading a derived column,
-- because a *different* trigger may not have written it yet and same-event triggers fire in **name order**.
-- That warning does not apply to a column this function assigns one line earlier: the read is of its own
-- assignment, which is why both halves live in one function instead of two triggers. The one column it listens
-- to is `unit_price`/`quantity` — the facts — and never `total`, which would make the derivation an input of
-- itself.
--
-- WHAT THIS FILE DOES NOT DO, named so it is not discovered in the diff:
--   * **No equality check between `subtotal` and `total`.** The equality the successor needs is
--     `total = subtotal + tax_amount`, and landing `total = subtotal` first would be a constraint that
--     migration has to drop and re-add over live rows. The derivation is the only writer in the meantime, and
--     `V1`'s `total >= 0` check stays and keeps holding.
--   * **No tax column.** S7: this slice states no tax; `tax_amount` arrives with the successor, in this same
--     function, as the second term of `total`.
--   * **No RLS window.** `purchase_order_details` carries no `company_id`, so `apply_tenant_isolation` never
--     armed it and the owner — the role delivery migrates as — sees every row. `V69:31` says so in as many
--     words. The opposite is `purchase_orders`, which is FORCEd and gets its window in `V93`.
--   * **No new index.** The backfill is a full scan of a table read only by `idx_pod_order` and nobody queries
--     `subtotal`.

-- ─── 1. the two columns: the base appears, the total widens to hold it ────────────────────────────
ALTER TABLE purchase_order_details
    ALTER COLUMN total TYPE NUMERIC(19, 4),
    ADD COLUMN subtotal NUMERIC(19, 4);

-- ─── 2. the rows, restated from their own parts ─────────────────────────────────────────────────
-- Not narrowed to the rows that disagree: after this statement the column has one meaning for every row, old
-- and new. The expression is repeated on both lines because a single `UPDATE`'s `SET` list reads the row as it
-- was, never the values assigned beside it — `total` cannot name the `subtotal` this same statement writes.
UPDATE purchase_order_details
   SET subtotal = round(unit_price * quantity, 4),
       total    = round(unit_price * quantity, 4) + 0;

-- The deferred transition trigger above queued one event per row; an `ALTER TABLE` is refused while any are
-- pending, and the `SET NOT NULL` below is an `ALTER TABLE`. See the header.
SET CONSTRAINTS ALL IMMEDIATE;

ALTER TABLE purchase_order_details
    ALTER COLUMN subtotal SET NOT NULL;

-- ─── 3. the derivation, so every future write states the same number ────────────────────────────
CREATE FUNCTION purchase_order_detail_money() RETURNS trigger AS $$
BEGIN
    NEW.subtotal := round(NEW.unit_price * NEW.quantity, 4);
    -- The successor's term goes here: `+ COALESCE(NEW.tax_amount, 0)` (odd/tasks/purchase-order-line-tax.md).
    -- It is `+ 0` today because this slice states no tax, and stating the zero is what makes the expression the
    -- completed one rather than a line that will have to be rewritten.
    NEW.total    := NEW.subtotal + 0;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Name order costs nothing here: `trg_purchase_order_details_money` sorts before
-- `trg_purchase_order_details_transition_recorded`, and the only other trigger this table ever carried
-- (`trg_purchase_order_details_status_bridge`, `V66`) was dropped by `V71` with the column it bridged from. So
-- nothing writes a column this function reads, and nothing reads a column this function writes.
CREATE TRIGGER trg_purchase_order_details_money
    BEFORE INSERT OR UPDATE OF unit_price, quantity ON purchase_order_details
    FOR EACH ROW EXECUTE FUNCTION purchase_order_detail_money();

COMMENT ON FUNCTION purchase_order_detail_money() IS
    'S2/S6 of odd/tasks/purchase-order-money.md: sets subtotal = round(unit_price * quantity, 4) and then '
    'total = subtotal + 0, the base and the line''s cost, in that order and in one function. The tax term of '
    'total arrives with odd/tasks/purchase-order-line-tax.md. It listens to unit_price and quantity — the '
    'facts — and never to a column it derives, so the derivation cannot become its own input.';

COMMENT ON COLUMN purchase_order_details.subtotal IS
    'S2: round(unit_price * quantity, 4), the line''s base, derived by trg_purchase_order_details_money. The '
    'tax the successor unit adds is computed on this number, which is why it is a column and not an '
    'expression each report repeats.';

COMMENT ON COLUMN purchase_order_details.total IS
    'S2: subtotal + 0 today, subtotal + tax_amount once odd/tasks/purchase-order-line-tax.md lands, derived by '
    'trg_purchase_order_details_money. It was written by PurchaseOrderService.lineTotal at two decimals until '
    'V92 restated every row at four.';

-- ─── 4. the self-check: does the column state the line, on every row? ───────────────────────────
-- Legitimate here and not on an armed table: `purchase_order_details` carries no `company_id` and no policy
-- (`V69:31`), so the owner reads every row and a count of zero means what it says — the defect `V64:30-42`
-- records for `products`, a FORCEd table where the owner reads nothing and zero is indistinguishable from
-- «nothing wrong», is not reachable from this file.
DO $$
DECLARE
    v_bad  bigint;
    v_null bigint;
BEGIN
    SELECT count(*) INTO v_null
      FROM purchase_order_details
     WHERE subtotal IS NULL;

    IF v_null > 0 THEN
        RAISE EXCEPTION
            'V92 read % lines with no subtotal after the backfill and the SET NOT NULL it was meant to '
            'prepare. The column is filled from the row''s own unit_price and quantity, so this can only mean '
            'the UPDATE reached no row.', v_null;
    END IF;

    SELECT count(*) INTO v_bad
      FROM purchase_order_details
     WHERE subtotal <> round(unit_price * quantity, 4)
        OR total <> subtotal + 0;

    IF v_bad > 0 THEN
        RAISE EXCEPTION
            'V92 read % lines whose money is not round(unit_price * quantity, 4) and that number, after the '
            'backfill. The backfill states that expression directly, so this can only mean a row it did not '
            'reach or a trigger that rewrote one behind it.', v_bad;
    END IF;
END $$;
