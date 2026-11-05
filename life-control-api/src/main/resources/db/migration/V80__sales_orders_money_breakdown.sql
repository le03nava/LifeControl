-- ============================================
-- V80 — sales_orders states its money: one currency, four parts, four decimals (ADR-0002 T2)
-- ============================================
-- `V1:477` declares `total_amount DECIMAL(12,2)` and nothing else: a document's money is a single number
-- with two decimals, no currency beside it, and no way to say what it is made of. This file gives the
-- document the three things ADR-0002 asks for — the currency it inherits from the company that owns it
-- (M6), the four parts whose sum is the total (M1), and the scale every amount is written at (decision 1,
-- `19,4`) — and adds the two constraints of that set that hold for the tree's own writer today,
-- `ck_order_parts_not_negative` and `ck_order_rounding_bounded` (M8). `ck_order_total` and `ck_item_total`
-- are the arithmetic of a breakdown, and they belong to the slice that also computes one.
--
-- WHY THE SHAPE AND THE ARITHMETIC SPLIT ACROSS TWO SLICES. `recalculateTotalAmount:1058` writes
-- `total_amount` from the lines' `final_price` and leaves the parts at 0, so `ck_order_total` refuses the
-- *current* service on its first line — measured, not assumed: the first full run of this branch answered
-- `409` where the sales suite expects `201`, in 41 cases. A constraint the tree's writer violates is a
-- work order for that writer, not a schema statement.
--
-- WHAT THIS FILE DOES NOT DO, AND WHY THAT IS A MEASURED DECISION AND NOT AN OMISSION (D-f, 2026-11-05).
-- Migration step 3 of the ADR backfills `subtotal` from `round(list_price * quantity, 4)` over the lines.
-- Measured in this tree, that reading does not survive the data: `SalesOrderService:555` writes
-- `finalPrice = listPrice - discountApplied` — the **unit** net price, quantity-independent — and
-- `recalculateTotalAmount:1058` sums those unit prices, so a legacy document's `total_amount` is a sum of
-- unit prices and never a sum of line totals. The suite's own fixture states it (`SalesOrderServiceTest`,
-- the line built for the total cases): `quantity 2.00, listPrice 100.00, discountApplied 10.00 →
-- finalPrice 90.00`, where the line total the ADR reads into that column is 190.00.
--
-- Backfilling `subtotal` from line totals would therefore leave every legacy document with
-- `total_amount <> subtotal - discount_total + tax_total`, and the gap is not a rounding artifact that
-- `ck_order_rounding_bounded` can absorb: it is the document's own quantity, per line. The migration would
-- abort on production data, which ADR-0002 step 2 accepts only when a report has already decided per row.
--
-- So a legacy row is backfilled to the one statement about it that is true: `subtotal = total_amount`, the
-- other three parts `0`. The row then means «this document carries no recorded breakdown; its total is the
-- number the old writer computed», which is the truth, and no stored amount moves by one cent — the
-- property ADR-0002 step 3 keeps in its own words («`total_amount` keeps its value») and the one a
-- financial migration may not lose. The price of the choice is named instead of hidden: **M2
-- (`subtotal = Σ line.subtotal`) is forward-only for this table.** T6 makes it true for every document it
-- writes; for a document that predates this file it is false, permanently and visibly, because
-- `rounding_adjustment` stays 0 instead of being inflated to hide the difference.
--
-- The line half of the same decision — `ck_item_total` — is not in `V81` either, because it reads
-- `final_price` as a line total and every legacy line with `quantity <> 1` would be refused. Both are one
-- follow-up, `odd/tasks/money-first-class.md`, and both are what T6 and T7 have to make true.
--
-- WHAT IT REFUSES. A document whose parts do not add up to its total, a currency the catalog does not
-- hold, a negative part, and a rounding adjustment wider than one minor unit. Each is reported before it is
-- constrained: a constraint that aborts a deploy names the constraint, never the row.
--
-- THE WINDOW, AND WHY IT IS TWO TABLES. `V65` armed **and** FORCEd `sales_orders`, so the owner — the role
-- Flyway runs as — reads zero rows under the policy, and `V57` armed `companies` the same way. Both
-- windows are load-bearing: without the first the backfill `UPDATE` matches nothing and reports success,
-- and without the second the `companies` row it reads to learn the currency does not exist, so every
-- document comes out unset and the same silence follows one statement later. The two `FORCE` ALTERs at the
-- end are the other half of the pair, and the last block fails this file if they did not land.

-- ─── 1. the window ────────────────────────────────────────────────────────────────────────────────
ALTER TABLE sales_orders NO FORCE ROW LEVEL SECURITY;
ALTER TABLE companies    NO FORCE ROW LEVEL SECURITY;

DO $$
DECLARE
    v_forced text;
BEGIN
    SELECT string_agg(c.relname, ', ' ORDER BY c.relname)
      INTO v_forced
      FROM pg_class c
     WHERE c.relname IN ('sales_orders', 'companies')
       AND c.relkind = 'r'
       AND c.relforcerowsecurity;

    IF v_forced IS NOT NULL THEN
        RAISE EXCEPTION
            'V80 could not open its NO FORCE window on: %. Every statement below would match zero rows '
            'and report success, leaving every existing document without a currency and without a '
            'breakdown. See the NO FORCE window V57 and V68 use, and ADR-0005 correction 3.', v_forced;
    END IF;
END $$;

-- ─── 2. the currency: read from the company that owns the document, never assumed ─────────────────
-- Nullable first, because `NOT NULL` cannot be added over rows, and no `DEFAULT`, because a default here
-- would be the platform choosing a tenant's currency — the failure class D-c exists to remove. The value
-- every existing document inherits is its own company's, which `V57` already made mandatory.
ALTER TABLE sales_orders
    ADD COLUMN currency CHAR(3) REFERENCES currency (code);

UPDATE sales_orders so
   SET currency = c.currency
  FROM companies c
 WHERE c.id = so.company_id
   AND so.currency IS NULL;

-- `V68`'s two constraint triggers on this table are `DEFERRABLE INITIALLY DEFERRED`, so the `UPDATE` above
-- queued their judgement for COMMIT — and PostgreSQL refuses an `ALTER TABLE` while trigger events are
-- pending (SQLSTATE 55006, measured on this file's first run). `SET CONSTRAINTS ALL IMMEDIATE` runs them
-- now, inside the window, which is the same step `V68` takes for its own seed and for the same reason: the
-- checks have to see the rows before the `FORCE` ALTERs put the policy back in front of the owner's reads.
-- It holds for the rest of the transaction, so the two `UPDATE`s below queue nothing either.
SET CONSTRAINTS ALL IMMEDIATE;

-- ─── 3. the self-check the backfill needs before it can be called a backfill ─────────────────────
-- Two refusals, because there are two ways this file does nothing: the window did not take, or the
-- company a document names is not visible to this role. Both report success on their own.
DO $$
DECLARE
    v_missing bigint;
    v_sample  text;
BEGIN
    SELECT count(*) INTO v_missing FROM sales_orders WHERE currency IS NULL;

    IF v_missing > 0 THEN
        SELECT string_agg(s.order_number, ', ' ORDER BY s.order_number)
          INTO v_sample
          FROM (SELECT order_number
                  FROM sales_orders
                 WHERE currency IS NULL
                 ORDER BY order_number
                 LIMIT 20) s;

        RAISE EXCEPTION
            'V80 backfilled sales_orders.currency from the company that owns each document and % documents '
            'are still unset (first 20 by number: %). Either the NO FORCE window above did not take, or '
            'the companies read matched nothing — both are silent. Every document inherits its company''s '
            'operating currency (ADR-0002 decision 2), so an unset row means the statement reached no row, '
            'not that a company has no currency: V57 made that column NOT NULL.', v_missing, v_sample;
    END IF;
END $$;

ALTER TABLE sales_orders ALTER COLUMN currency SET NOT NULL;

-- ─── 4. the currency is derived from the company, so the NOT NULL above has a writer ─────────────
-- The column is `NOT NULL` from here on, and the tree's writer does not send it: `SalesOrderService`
-- builds the row from the request, and the request has no currency (T7 is the surface that states one).
-- A `DEFAULT 'MXN'` would be D-c's failure class one layer down — the platform writing a tenant's
-- currency — so the value is derived instead, from the company that owns the document, which is the row
-- ADR-0002 decision 2 says the currency comes from in the first place. Two refusals, and the second is
-- the one that makes M7 («two currencies on one document are impossible») a fact for every writer:
-- a company this role cannot see is refused by name, and a document that states another currency than
-- its company's is refused instead of silently overwritten.
CREATE FUNCTION sales_order_currency_from_company() RETURNS trigger AS $$
DECLARE
    v_currency CHAR(3);
BEGIN
    SELECT c.currency INTO v_currency FROM companies c WHERE c.id = NEW.company_id;

    IF v_currency IS NULL THEN
        RAISE EXCEPTION
            'sales order % names a company this writer cannot see, so the document''s currency cannot be '
            'derived: a document of another tenant is hidden by the policy instead of refused (ADR-0005).',
            NEW.id;
    END IF;

    IF NEW.currency IS NULL THEN
        NEW.currency := v_currency;
    ELSIF NEW.currency IS DISTINCT FROM v_currency THEN
        RAISE EXCEPTION
            'sales order % states currency %, and its company operates in %: a document cannot be in a '
            'currency other than the company''s operating currency (ADR-0002 decision 2, M7).',
            NEW.id, NEW.currency, v_currency;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_sales_orders_currency_from_company
    BEFORE INSERT OR UPDATE OF company_id, currency ON sales_orders
    FOR EACH ROW EXECUTE FUNCTION sales_order_currency_from_company();

COMMENT ON FUNCTION sales_order_currency_from_company() IS
    'ADR-0002 decision 2 / M6-M7: the document inherits its company''s currency and cannot state another '
    'one. The derivation is in the schema so that the NOT NULL holds for every writer, including the one '
    'that predates T7, and so that no caller can name a currency the tenant does not operate in.';

-- ─── 5. the parts, and the total they have to add up to ───────────────────────────────────────────
-- `DEFAULT 0` is kept from the ADR's schema block on purpose: the current writer already totals a document
-- before its lines exist (`SalesOrderService:175`, `BigDecimal.ZERO`), so zero is a state the tree has, not
-- a value this file invents for it.
ALTER TABLE sales_orders
    ADD COLUMN subtotal            NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN discount_total      NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN tax_total           NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN rounding_adjustment NUMERIC(19,4) NOT NULL DEFAULT 0;

-- `NULL` becomes 0 first, and in this order: a `NULL` in a `CHECK` is not a refusal, it is a pass, and a
-- pass is the one way a breakdown can be bypassed without an error. ADR-0002 step 3.
UPDATE sales_orders SET total_amount = 0 WHERE total_amount IS NULL;

ALTER TABLE sales_orders ALTER COLUMN total_amount TYPE NUMERIC(19,4);

-- D-f: the legacy document has no recorded breakdown, so its total is carried as its subtotal and the
-- three parts it never had stay zero. Nothing is re-derived from the lines, because the lines are unit
-- prices and the derivation would rewrite the document's total.
UPDATE sales_orders SET subtotal = total_amount;

ALTER TABLE sales_orders ALTER COLUMN total_amount SET NOT NULL;

-- ─── 6. the reports that run before the constraints ───────────────────────────────────────────────
DO $$
DECLARE
    v_negative bigint;
    v_sample   text;
    v_drift    bigint;
BEGIN
    SELECT count(*) INTO v_negative
      FROM sales_orders
     WHERE subtotal < 0 OR discount_total < 0 OR tax_total < 0 OR total_amount < 0;

    IF v_negative > 0 THEN
        SELECT string_agg(s.order_number, ', ' ORDER BY s.order_number)
          INTO v_sample
          FROM (SELECT order_number
                  FROM sales_orders
                 WHERE subtotal < 0 OR discount_total < 0 OR tax_total < 0 OR total_amount < 0
                 ORDER BY order_number
                 LIMIT 20) s;

        RAISE EXCEPTION
            'V80 refuses to constrain % documents whose money is negative (first 20 by number: %). '
            'ck_order_parts_not_negative states that a part is never below zero, so a negative total is a '
            'row a human has to explain before a constraint can speak about it — not a value this file '
            'flips to make the deploy pass.', v_negative, v_sample;
    END IF;

    SELECT count(*) INTO v_drift
      FROM sales_orders
     WHERE total_amount <> subtotal - discount_total + tax_total + rounding_adjustment;

    IF v_drift > 0 THEN
        RAISE EXCEPTION
            'V80 read % documents whose parts do not add up to their total after the backfill, which is '
            'the state ck_order_total will refuse once T6 writes the parts. The backfill sets subtotal = '
            'total_amount and the other three parts to 0, so this can only mean the UPDATE reached no row.',
            v_drift;
    END IF;
END $$;

-- ─── 7. the constraints this file can stand behind, last, and never the other way around ──────────
-- `ck_order_total` (M1) is **not** among them, and the reason is measured rather than cautious: the tree
-- already has a writer that fails it. `recalculateTotalAmount:1058` sets `total_amount` from the lines'
-- `final_price` on every add, edit and delete, while `subtotal` and the other two parts stay 0 — so the
-- constraint would refuse the *current* service on its first line, and the integration suite observed it
-- (41 cases answering `409` instead of `201` on the first run of this file). A constraint the tree's own
-- writer violates is not a schema statement, it is a work order for that writer, and it belongs to T6/T7
-- with `ck_item_total`. What lands here is what holds for both writers: a part is never negative, and the
-- rounding is never wider than one minor unit.
ALTER TABLE sales_orders
    ADD CONSTRAINT ck_order_parts_not_negative
        CHECK (subtotal >= 0 AND discount_total >= 0 AND tax_total >= 0 AND total_amount >= 0),
    -- M8's local half: one minor unit is the widest bound any currency allows, so a larger adjustment is
    -- wrong in every currency without reading the catalog. The currency half is the domain guard's.
    ADD CONSTRAINT ck_order_rounding_bounded CHECK (abs(rounding_adjustment) <= 1);

COMMENT ON COLUMN sales_orders.currency IS
    'ADR-0002 decision 2 / M6: the document''s currency, inherited from the company that owns it. There is '
    'no second currency column on the line or the payment, so two currencies on one document cannot be '
    'stated (M7).';

COMMENT ON COLUMN sales_orders.subtotal IS
    'ADR-0002 decision 3 / M2: the sum of the lines'' subtotals. Forward-only for this column: a document '
    'that predates V80 carries its old total here and no breakdown, because sales_order_items.final_price '
    'was a unit price and not a line total (D-f, V80''s header).';

COMMENT ON COLUMN sales_orders.rounding_adjustment IS
    'ADR-0002 M8: the single rounding to the currency''s minor unit, applied once at the document boundary. '
    'Bounded by ck_order_rounding_bounded to one unit; that it is not wider than the currency allows is '
    'the domain guard''s check, because a CHECK cannot read the catalog.';

-- ─── 8. the window closes, and a forgotten close fails this file instead of the next one ──────────
ALTER TABLE sales_orders FORCE ROW LEVEL SECURITY;
ALTER TABLE companies    FORCE ROW LEVEL SECURITY;

DO $$
DECLARE
    v_unarmed text;
BEGIN
    SELECT string_agg(c.relname, ', ' ORDER BY c.relname)
      INTO v_unarmed
      FROM pg_class c
     WHERE c.relname IN ('sales_orders', 'companies')
       AND c.relkind = 'r'
       AND NOT (c.relrowsecurity AND c.relforcerowsecurity);

    IF v_unarmed IS NOT NULL THEN
        RAISE EXCEPTION
            'V80 opened a NO FORCE window and did not close it on: %. That table is readable by its owner '
            'without a tenant scope, which is the state ADR-0005 correction 3 refuses.', v_unarmed;
    END IF;
END $$;
