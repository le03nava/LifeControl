-- ============================================
-- V77 — product_variant_store_stock.list_price leaves (issue #779)
-- ============================================
-- `V58__variant_listing.sql` **copied** the store price into `variant_listing` instead of moving it, and said so
-- on purpose (`:38-42`): the legacy column stayed as it was so the two representations could be compared while
-- the reads moved. T4b then moved the reads — the store-scoped queries and the POS search take `l.listPrice`
-- from the listing — so from that slice on nothing writes this column (the price write of
-- `PUT /api/variants/{variantId}/stores/{storeId}` is one `INSERT … ON CONFLICT` on `variant_listing`) and
-- nothing reads it. What remained was dead weight that still *looked* live: a future reader could populate it,
-- or trust it, and be wrong. The entity's own Javadoc said as much; this file ends the state instead of
-- documenting it.
--
-- WHAT THE DROP REFUSES
-- ---------------------
-- Dropping the column is only safe while the listing still carries every price the column holds, because after
-- this file the listing is the only record of it. So the guard refuses when a store row holds a `list_price`
-- that its listing does not reproduce — either because no listing exists for that `(variant, store)` pair (the
-- price was never copied: `V58` copied only the rows it found priced) or because the two disagree (the listing
-- was edited after the copy, which is the normal life of a price). In both cases the number in the column is the
-- only remaining record of a price somebody set, and the file names the pair and the next step instead of
-- deleting it. `V74` refuses for the same reason about an unbooked quantity: a migration that silently discards
-- the sole record of a fact is not a cleanup.
--
-- A store row with `list_price IS NULL` is never an offender: there is nothing to lose. That is the majority of
-- the table's rows, because a row exists as soon as a receipt creates stock in a store that never priced the
-- article (ADR-0006 decision 6), and the price is a listing.
--
-- WHY THE GUARD RUNS INSIDE A `NO FORCE` WINDOW
-- ----------------------------------------------
-- The guard reads `variant_listing`, which `V58:189` arms with `apply_tenant_isolation`, and `V52:152-153`
-- installs every policy with `ENABLE` **and** `FORCE`: the owner is filtered too, and a migration runs as the
-- owner with no `app.company_id` set. Without a window each `NOT EXISTS` over that table sees zero rows, so
-- every priced store row would look unreproduced and the guard would refuse on every database that ever priced
-- anything — the shape of guard that gets edited under pressure at 3am. The target table itself needs no window:
-- `product_variant_store_stock` carries no `company_id` and `apply_tenant_isolation` never names it
-- (`V58:63-69` states the split), so a plain `ALTER TABLE … DROP COLUMN` on it is an ordinary catalog change.
--
-- The window is the mechanism `V57`, `V62`, `V64`, `V66`, `V72` and `V74` already use: it is transactional, it is
-- checked before it is relied on — a window that did not take answers every question with zero rows — and it is
-- closed by an assertion rather than by good intentions.

-- ============================================
-- 1. the NO FORCE window, opened before the first read
-- ============================================
ALTER TABLE variant_listing NO FORCE ROW LEVEL SECURITY;

DO $$
DECLARE
    v_still_forced text;
BEGIN
    SELECT string_agg(c.relname, ', ' ORDER BY c.relname) INTO v_still_forced
      FROM pg_class c
     WHERE c.relkind = 'r' AND c.relforcerowsecurity
       AND c.relname IN ('variant_listing');

    IF v_still_forced IS NOT NULL THEN
        RAISE EXCEPTION
            'V77 could not open its NO FORCE window on %: the guard below would read no listing, treat every '
            'priced store row as a price the listing does not reproduce, and refuse a migration that has nothing '
            'to refuse. A window that did not take is the whole defect this block is here to prevent.', v_still_forced;
    END IF;
END $$;

-- ============================================
-- 2. the proof the drop refuses to run without
-- ============================================
DO $$
DECLARE
    v_offenders bigint;
    v_variant uuid;
    v_store uuid;
    v_column numeric(12,2);
    v_listing numeric(19,4);
BEGIN
    SELECT count(*)
      INTO v_offenders
      FROM product_variant_store_stock ss
     WHERE ss.list_price IS NOT NULL
       AND NOT EXISTS (SELECT 1
                         FROM variant_listing vl
                        WHERE vl.product_variant_id = ss.product_variant_id
                          AND vl.company_store_id = ss.company_store_id
                          AND vl.list_price = ss.list_price);
    IF v_offenders > 0 THEN
        SELECT ss.product_variant_id, ss.company_store_id, ss.list_price, vl.list_price
          INTO v_variant, v_store, v_column, v_listing
          FROM product_variant_store_stock ss
          LEFT JOIN variant_listing vl
            ON vl.product_variant_id = ss.product_variant_id
           AND vl.company_store_id = ss.company_store_id
         WHERE ss.list_price IS NOT NULL
           AND (vl.id IS NULL OR vl.list_price <> ss.list_price)
         ORDER BY ss.product_variant_id, ss.company_store_id
         LIMIT 1;
        RAISE EXCEPTION
            'refusing to drop product_variant_store_stock.list_price: % store row(s) hold a price the listing '
            'does not reproduce, so the column is the only record of it. First offender: variant %, store % '
            '(column %, listing %). Next step: make the listing carry that price — the store-scoped write of '
            'PUT /api/variants/{variantId}/stores/{storeId} is the only writer of it — then re-run this migration. '
            'Nothing in this file copies the column into the listing, on purpose: the listing owns the price '
            '(ADR-0006 decision 5), and a migration that inherited the number would be a second writer of it.',
            v_offenders, v_variant, v_store, v_column, coalesce(v_listing::text, 'none');
    END IF;
END $$;

-- ============================================
-- 3. the window closes, and the close is checked
-- ============================================
-- After the guard and never before it: `FORCE` filters the owner, so a read placed below this `ALTER` would see
-- nothing and pass. `V72:148-155` and `V74` learned the same lesson in the same words.
ALTER TABLE variant_listing FORCE ROW LEVEL SECURITY;

DO $$
DECLARE
    v_not_forced text;
BEGIN
    SELECT string_agg(c.relname, ', ' ORDER BY c.relname) INTO v_not_forced
      FROM pg_class c
     WHERE c.relkind = 'r' AND NOT c.relforcerowsecurity
       AND c.relname IN ('variant_listing');

    IF v_not_forced IS NOT NULL THEN
        RAISE EXCEPTION
            'V77 left % without FORCE ROW LEVEL SECURITY: a table whose owner is exempt from its own policy is '
            'the state ADR-0005 exists to remove, and a window left open weakens the table for everything that '
            'runs after it (V55).', v_not_forced;
    END IF;
END $$;

-- ============================================
-- 4. the drop
-- ============================================
ALTER TABLE product_variant_store_stock DROP COLUMN list_price;

-- ============================================
-- 5. the self-check: the column is gone, and what shared its name is not
-- ============================================
-- Two blocks, because they fail for unrelated reasons. The first answers "did the drop reach exactly the column
-- it was aimed at" — which a half-applied file fails. The second answers "did a drop by name take a column from
-- another table that legitimately carries it": `list_price` is the name of a live column in `variant_listing` and
-- in `sales_order_items`, and a statement written against the wrong table would delete one of those instead.
DO $$
DECLARE
    v_present text;
BEGIN
    SELECT string_agg(table_name || '.' || column_name, ', ') INTO v_present
      FROM information_schema.columns
     WHERE table_schema = 'public'
       AND table_name = 'product_variant_store_stock'
       AND column_name = 'list_price';
    IF v_present IS NOT NULL THEN
        RAISE EXCEPTION 'product_variant_store_stock.list_price must be gone, but % still carries it', v_present;
    END IF;
END $$;

DO $$
DECLARE
    v_missing text;
    v_live integer;
BEGIN
    IF to_regclass('public.product_variant_store_stock') IS NULL THEN
        RAISE EXCEPTION
            'the table itself must survive this file: it keeps the identity rows the ledger points at and the '
            'cost an inbound movement has to state (V74). It is missing.';
    END IF;

    SELECT string_agg(c.column_name, ', ' ORDER BY c.column_name) INTO v_missing
      FROM (VALUES ('product_variant_id'), ('company_store_id'), ('cost_price'), ('created_at'), ('updated_at')) AS c(column_name)
     WHERE NOT EXISTS (SELECT 1
                         FROM information_schema.columns ic
                        WHERE ic.table_schema = 'public'
                          AND ic.table_name = 'product_variant_store_stock'
                          AND ic.column_name = c.column_name);
    IF v_missing IS NOT NULL THEN
        RAISE EXCEPTION
            'the drop took more than its column: product_variant_store_stock lost %. It keeps the pair, the cost '
            'the ledger reads and its timestamps, and none of those is this file''s to remove.', v_missing;
    END IF;

    SELECT count(*) INTO v_live
      FROM information_schema.columns
     WHERE table_schema = 'public'
       AND column_name = 'list_price'
       AND table_name IN ('variant_listing', 'sales_order_items');
    IF v_live <> 2 THEN
        RAISE EXCEPTION
            'variant_listing.list_price and sales_order_items.list_price are live columns of other tables and both '
            'must survive: % of the 2 are present. The price a client reads is the listing''s, and a sale keeps '
            'the price it was sold at — neither is the column this file retires.', v_live;
    END IF;
END $$;
