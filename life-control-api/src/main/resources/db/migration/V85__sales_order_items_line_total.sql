-- ============================================
-- V85 — a sales order line's final_price is its line total (ADR-0002 T7c, decision 3, D-h)
-- ============================================
-- `V81`'s header states the measurement this file answers. The ADR's schema block declares
-- `ck_item_total CHECK (final_price = subtotal - discount_applied + tax_amount)`, which reads `final_price`
-- as a **line total**, while the tree wrote the **unit** net price (`SalesOrderService:231`, `:317`, `:330`,
-- `:565`, `:616`, all `finalPrice = listPrice - discountApplied`). A line with
-- `quantity 2, list_price 100, discount_applied 10` carried `90.0000` where its line total is `190.0000`, so
-- the constraint could only have been landed against history and `V81` deferred it.
--
-- TWO THINGS CHANGED SINCE `V81`, AND BOTH ARE MEASURED IN THIS TREE.
--
-- First, the document stopped summing unit prices. `V84`/T6a's `recomputeBreakdown` sums
-- `sales_order_items.subtotal`, `discount_applied` and `tax_amount` with one aggregate, so the document's
-- total is Σ(subtotal − discount + tax) — which is exactly the line total this file states. The unit reading
-- is therefore the one that disagrees with the document above it, not the one that agrees: the ADR's form
-- became the tree's own arithmetic in the slice that gave the document a writer.
--
-- Second, `final_price` lost its last monetary consumer. No production code sums it, derives a total from it
-- or reads it for a calculation; the only readers left are the two response mappers that report it. What the
-- column is, in one sentence: **a derived column the API reports and nothing computes with**, which is what
-- makes restating it a schema statement instead of a financial rewrite.
--
-- WHY THE BACKFILL RESTATES EVERY ROW. A constraint over the column judges rows that already exist, and a
-- legacy row's `final_price` is a number written under a different meaning. Restating it from the row's own
-- parts — `subtotal − discount_applied + tax_amount`, all three already on the row since `V81` — gives the
-- column one meaning after the deploy instead of two that every report has to filter by age. This is the
-- shape `D-f` rejected for `total_amount` and this file deliberately does not touch: **no document's
-- `total_amount` moves**, because the document's money comes from the parts and never from this column, and
-- neither does any document's stored `subtotal`, `discount_total` or `tax_total`.
--
-- WHY THE TRIGGER READS `list_price * quantity` INSTEAD OF `NEW.subtotal`. PostgreSQL fires same-event
-- triggers in **name order**, and `trg_sales_order_items_subtotal` (`V81`) sorts after
-- `trg_sales_order_items_final_price`: reading `NEW.subtotal` here would read the value the OLD row carried on
-- every insert and on every edit of `list_price`/`quantity`. The expression below is the same one `V81`
-- derives `subtotal` with, evaluated here, so the two agree whether or not the other trigger has run yet —
-- and `ck_item_subtotal` plus `ck_item_total` are what hold them equal from then on.
--
-- WHY THE WRITER STOPS SENDING IT. A derived column must not be sent by the application: the value a mapping
-- carries is the value the row gets on any statement this trigger's column list does not name, which is
-- exactly the stale-mapping trap `V81`'s javadoc records for `subtotal`. `SalesOrderItem.finalPrice` becomes
-- `insertable = false, updatable = false` in the same commit, and the service reports the row it refreshed
-- instead of the number it built.
--
-- WHAT IT DOES NOT DO. It does not restate `tax_amount` or `tax_rate` (no rate row exists, so both are 0 and
-- `ck_item_tax` holds), it does not touch `subtotal` (`V81` derives it and `ck_item_subtotal` holds it), and
-- it does not move any `sales_orders` column. It needs no `NO FORCE` window: `sales_order_items` carries no
-- `company_id` and no policy (`V81`'s header says so in as many words), and this file reads no other table.

-- ─── 1. the rows, restated from their own parts ─────────────────────────────────────────────────
-- Not narrowed to the rows that disagree, and that is the point of the previous paragraph: after this
-- statement the column has one meaning for every row, old and new, instead of a meaning that depends on when
-- the row was written.
UPDATE sales_order_items
   SET final_price = subtotal - discount_applied + tax_amount;

-- The same step `V81` takes, and for the same measured reason: `V68`'s constraint trigger on this table is
-- `DEFERRABLE INITIALLY DEFERRED`, and an `ALTER TABLE` is refused while trigger events are pending
-- (SQLSTATE 55006). Running them here also means the two statements below queue nothing.
SET CONSTRAINTS ALL IMMEDIATE;

-- ─── 2. the derivation, so every future write states the same number ────────────────────────────
CREATE FUNCTION sales_order_item_final_price() RETURNS trigger AS $$
BEGIN
    NEW.final_price := round(NEW.list_price * NEW.quantity, 4) - NEW.discount_applied + NEW.tax_amount;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_sales_order_items_final_price
    BEFORE INSERT OR UPDATE OF list_price, quantity, discount_applied, tax_amount ON sales_order_items
    FOR EACH ROW EXECUTE FUNCTION sales_order_item_final_price();

COMMENT ON FUNCTION sales_order_item_final_price() IS
    'ADR-0002 decision 3 (D-h): final_price = round(list_price * quantity, 4) - discount_applied + '
    'tax_amount, derived by the schema so the writer that used to send a unit price cannot state a number '
    'ck_item_total refuses. It evaluates list_price * quantity itself instead of reading NEW.subtotal, '
    'because same-event triggers fire in name order and trg_sales_order_items_subtotal sorts after this one.';

-- ─── 3. the self-check: does the column state the line, on every row? ───────────────────────────
-- A failure here can only mean the UPDATE above reached no row — the failure mode a closed window produces
-- on a forced table, and the one `V54` and `V64` already record. `sales_order_items` is not forced, so the
-- count is the honest question to ask.
DO $$
DECLARE
    v_bad bigint;
BEGIN
    SELECT count(*) INTO v_bad
      FROM sales_order_items
     WHERE final_price <> round(list_price * quantity, 4) - discount_applied + tax_amount;

    IF v_bad > 0 THEN
        RAISE EXCEPTION
            'V85 read % lines whose final_price is not round(list_price * quantity, 4) - discount_applied + '
            'tax_amount after the backfill, which is the state ck_item_total exists to refuse. The backfill '
            'states that expression directly, so this can only mean the UPDATE reached no row.', v_bad;
    END IF;
END $$;

-- ─── 4. the constraint, last ────────────────────────────────────────────────────────────────────
-- It holds for **every** writer because the column is derived instead of trusted: the trigger above writes
-- the expression this constraint states, on each insert and on each edit of the three numbers it is made of.
-- A hand-written value that disagrees is still refused, because the derivation's column list does not name
-- `final_price`: an `UPDATE` that only states the total is the one case the derivation cannot answer for.
ALTER TABLE sales_order_items
    ADD CONSTRAINT ck_item_total CHECK (final_price = subtotal - discount_applied + tax_amount);

COMMENT ON COLUMN sales_order_items.final_price IS
    'ADR-0002 decision 3: subtotal - discount_applied + tax_amount, the line''s total, derived by '
    'trg_sales_order_items_final_price. It was the unit net price (list_price - discount_applied) until V85; '
    'ck_item_total states the meaning, so the column cannot drift from the parts it is made of.';
