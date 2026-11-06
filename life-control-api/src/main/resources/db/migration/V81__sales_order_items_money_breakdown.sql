-- ============================================
-- V81 — sales_order_items states its money: four decimals, the line's parts, and what was sold (ADR-0002 T2)
-- ============================================
-- `V1:496-509` gives a line `quantity`, `list_price`, `discount_applied` and `final_price`, all
-- `DECIMAL(12,2)`, and nothing else. Two decimals cannot hold a per-gram price or a quantity a unit price
-- implies (ADR-0002 decision 1), a nullable `discount_applied` makes `ck_item_*` pass as `NULL` instead of
-- refusing (`V1:502`), and a line that records only a variant id cannot say what the person bought once the
-- catalog renames or deletes it. This file fixes all three: the four amounts become `19,4`, `NULL` becomes
-- `0` before the `NOT NULL` lands, the line gains its own `subtotal`, tax code, rate and amount, and it
-- keeps the three snapshots of what it sold.
--
-- WHAT IT DOES NOT ADD, AND THE MEASUREMENT BEHIND IT (D-f, 2026-11-05 — the same decision `V80`'s header
-- states in full). The ADR's schema block declares
-- `ck_item_total CHECK (final_price = subtotal - discount_applied + tax_amount)`, which reads
-- `final_price` as a **line total**. Measured in this tree it is the **unit** net price:
-- `SalesOrderService:555,618` write `finalPrice = listPrice - discountApplied`, and
-- `recalculateTotalAmount:1058` sums those unit prices into the document total. A line with
-- `quantity 2.00, listPrice 100.00, discountApplied 10.00` carries `final_price = 90.00` while the line
-- total the constraint expects is `190.00`, so the constraint would refuse **every** legacy line with
-- `quantity <> 1` — the migration aborts on production data, and the rows it refuses are correct by the
-- semantics they were written under.
--
-- `ck_item_subtotal` and `ck_item_tax` do land, because both speak about columns this file creates and
-- backfills itself: `subtotal` is written as exactly `round(list_price * quantity, 4)`, and `tax_amount` as
-- exactly `round((subtotal - discount_applied) * tax_rate, 4)` with a rate of `0`. `ck_item_total` waits
-- for the slice that also moves the writer (T6, T7), because until then it would be a constraint against
-- history rather than against the code.
--
-- WHAT IT REFUSES. A line whose subtotal is not its own price times its quantity, and a tax amount that is
-- not its taxable base times its rate. The `NOT NULL` on `discount_applied` refuses the `NULL` that used to
-- make a check pass silently.
--
-- THE WINDOW, AND WHY IT IS THE CATALOG'S. `sales_order_items` carries no `company_id` (`V68:133-138`
-- says so in as many words) and no policy, so nothing here needs a window for the lines themselves. The
-- snapshots are a different matter: `V53:122-123` arms `products` and `product_variants` through
-- `apply_tenant_isolation`, which `V52:152-153` writes as `ENABLE` **and `FORCE`**, so the owner reads zero
-- catalog rows unless the window is open. Closed, the join below matches nothing, every snapshot stays
-- `NULL`, and the migration reports success — the failure this pair of windows exists to prevent, and the
-- reason the self-check names the count instead of trusting the `UPDATE`'s row count.

-- ─── 1. the window: the two catalog tables the snapshots are read from ────────────────────────────
ALTER TABLE products         NO FORCE ROW LEVEL SECURITY;
ALTER TABLE product_variants NO FORCE ROW LEVEL SECURITY;

DO $$
DECLARE
    v_forced text;
BEGIN
    SELECT string_agg(c.relname, ', ' ORDER BY c.relname)
      INTO v_forced
      FROM pg_class c
     WHERE c.relname IN ('products', 'product_variants')
       AND c.relkind = 'r'
       AND c.relforcerowsecurity;

    IF v_forced IS NOT NULL THEN
        RAISE EXCEPTION
            'V81 could not open its NO FORCE window on: %. The snapshot backfill joins these tables and '
            'would read nothing, leaving every line without the name it sold and reporting success. See the '
            'NO FORCE window V57 and V68 use, and ADR-0005 correction 3.', v_forced;
    END IF;
END $$;

-- ─── 2. the four amounts, at the scale every money column is written at now ───────────────────────
ALTER TABLE sales_order_items
    ALTER COLUMN quantity         TYPE NUMERIC(19,4),
    ALTER COLUMN list_price       TYPE NUMERIC(19,4),
    ALTER COLUMN discount_applied TYPE NUMERIC(19,4),
    ALTER COLUMN final_price      TYPE NUMERIC(19,4);

-- `NULL` becomes 0 **before** the `NOT NULL` lands, and both are needed: `V1:502` declares
-- `discount_applied DECIMAL(12,2) DEFAULT 0` without `NOT NULL`, and a `DEFAULT` never rewrites the rows
-- that already exist. A row that carries `NULL` today would make `ck_item_total` pass as `NULL` instead of
-- refusing — the one way a breakdown is bypassed without an error. ADR-0002 step 3.
UPDATE sales_order_items SET discount_applied = 0 WHERE discount_applied IS NULL;

-- The same step `V80` takes, and for the same measured reason: `V68`'s trigger on this table is
-- `DEFERRABLE INITIALLY DEFERRED`, and an `ALTER TABLE` is refused while trigger events are pending
-- (SQLSTATE 55006). Running them here also means the two `UPDATE`s below queue nothing.
SET CONSTRAINTS ALL IMMEDIATE;

ALTER TABLE sales_order_items ALTER COLUMN discount_applied SET NOT NULL;

-- ─── 3. the line's own parts and what it sold ─────────────────────────────────────────────────────
ALTER TABLE sales_order_items
    ADD COLUMN subtotal              NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN tax_code              VARCHAR(20)   NOT NULL DEFAULT 'NA',
    ADD COLUMN tax_rate              NUMERIC(9,6)  NOT NULL DEFAULT 0,
    ADD COLUMN tax_amount            NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN product_name_snapshot VARCHAR(255),
    ADD COLUMN variant_name_snapshot VARCHAR(255),
    ADD COLUMN attributes_snapshot   JSONB;

-- The subtotal is the one number this file can state about a legacy line without reading the catalog or the
-- document above it: the price times the quantity the row itself carries, at four decimals.
UPDATE sales_order_items SET subtotal = round(list_price * quantity, 4);

-- The snapshots come from the catalog as it stands today, which is the only state available to a migration:
-- `products.name` (`V1:187`) and `product_variants.variant_name` (`V1:406`, nullable), reached through the
-- line's own `product_variant_id` (`V1:499`) and the variant's `product_id` (`V1:402`). `tax_code` stays
-- `'NA'` and `tax_rate` stays 0 because no rate row exists yet — that is T4's table, and a migration does
-- not invent a rate for a sale that was never taxed. `attributes_snapshot` stays `NULL`: ADR-0002 step 5
-- asks for the two names, and the attributes are a catalog shape T8 renders.
UPDATE sales_order_items i
   SET product_name_snapshot = p.name,
       variant_name_snapshot = v.variant_name
  FROM product_variants v
  JOIN products p ON p.id = v.product_id
 WHERE v.id = i.product_variant_id;

-- ─── 4. the self-check: did the backfill reach every row? ─────────────────────────────────────────
-- `sales_order_items.product_variant_id` is `NOT NULL REFERENCES product_variants (id)` (`V1:499`) and
-- `product_variants.product_id` is `NOT NULL REFERENCES products (id)` (`V1:402`), so every line has a
-- product the join can reach: a `NULL` snapshot can only mean the join read nothing, and that is exactly
-- what a closed window does. The subtotal half asks the same question about arithmetic instead of rows.
DO $$
DECLARE
    v_no_name    bigint;
    v_bad_subtot bigint;
BEGIN
    SELECT count(*) INTO v_no_name FROM sales_order_items WHERE product_name_snapshot IS NULL;

    IF v_no_name > 0 THEN
        RAISE EXCEPTION
            'V81 left % lines without product_name_snapshot, and every line names a variant whose product '
            'row exists (V1:402, V1:499). A snapshot that did not land means the products read matched no '
            'row — check the NO FORCE window at the top of this file — not a product without a name: '
            'products.name is NOT NULL.', v_no_name;
    END IF;

    SELECT count(*) INTO v_bad_subtot
      FROM sales_order_items
     WHERE subtotal <> round(list_price * quantity, 4);

    IF v_bad_subtot > 0 THEN
        RAISE EXCEPTION
            'V81 read % lines whose subtotal is not round(list_price * quantity, 4) after the backfill, '
            'which is the state ck_item_subtotal exists to refuse. The backfill states that expression '
            'directly, so this can only mean the UPDATE reached no row.', v_bad_subtot;
    END IF;
END $$;

-- ─── 5. the constraints this file can stand behind, last ────────────────────────────────────────
-- `ck_item_subtotal` holds for **every** writer because the column is derived instead of trusted: the
-- trigger below writes the expression the constraint states, on each insert and on each edit of the two
-- numbers it is made of. Without it the tree's own writer — which sends no subtotal — would insert `0` and
-- the constraint would refuse the current service (`SalesOrderService:225`, `:351`, `:364`), the same
-- collision `V80` records for `ck_order_total`. A hand-written subtotal that disagrees is still refused,
-- because the derivation runs before the check and an `UPDATE` that never touches the two inputs is the one
-- case the derivation cannot answer for.
CREATE FUNCTION sales_order_item_subtotal() RETURNS trigger AS $$
BEGIN
    NEW.subtotal := round(NEW.list_price * NEW.quantity, 4);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_sales_order_items_subtotal
    BEFORE INSERT OR UPDATE OF list_price, quantity ON sales_order_items
    FOR EACH ROW EXECUTE FUNCTION sales_order_item_subtotal();

COMMENT ON FUNCTION sales_order_item_subtotal() IS
    'ADR-0002 decision 3: the line''s subtotal is price times quantity, derived by the schema so that the '
    'writer that predates T7 cannot insert a row the constraint would refuse.';

-- `ck_item_total` is deliberately absent; the header states why and `odd/tasks/money-first-class.md`
-- carries it as the follow-up T6/T7 close.
ALTER TABLE sales_order_items
    ADD CONSTRAINT ck_item_subtotal CHECK (subtotal = round(list_price * quantity, 4)),
    ADD CONSTRAINT ck_item_tax
        CHECK (tax_amount = round((subtotal - discount_applied) * tax_rate, 4));

COMMENT ON COLUMN sales_order_items.subtotal IS
    'ADR-0002 decision 3: round(list_price * quantity, 4), the line''s money before its discount and its '
    'tax. ck_item_subtotal states it, so the column cannot drift from the two numbers it is made of.';

COMMENT ON COLUMN sales_order_items.tax_amount IS
    'ADR-0002 decision 3: round((subtotal - discount_applied) * tax_rate, 4), with the rate copied onto the '
    'line from tax_rate at write time, so a later rate change cannot restate a past sale. 0 until a rate '
    'row exists (T4).';

COMMENT ON COLUMN sales_order_items.product_name_snapshot IS
    'ADR-0002 step 5: what the line sold, kept beside the catalog instead of behind it. A rename or a '
    'deletion in products must not restate a document that was already closed.';

-- ─── 6. the window closes, and a forgotten close fails this file instead of the next one ──────────
ALTER TABLE products         FORCE ROW LEVEL SECURITY;
ALTER TABLE product_variants FORCE ROW LEVEL SECURITY;

DO $$
DECLARE
    v_unarmed text;
BEGIN
    SELECT string_agg(c.relname, ', ' ORDER BY c.relname)
      INTO v_unarmed
      FROM pg_class c
     WHERE c.relname IN ('products', 'product_variants')
       AND c.relkind = 'r'
       AND NOT (c.relrowsecurity AND c.relforcerowsecurity);

    IF v_unarmed IS NOT NULL THEN
        RAISE EXCEPTION
            'V81 opened a NO FORCE window and did not close it on: %. That table is readable by its owner '
            'without a tenant scope, which is the state ADR-0005 correction 3 refuses.', v_unarmed;
    END IF;
END $$;
