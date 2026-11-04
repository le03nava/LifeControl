-- ============================================
-- V74 — the two legacy stock columns leave (ADR-0001 T4, S5)
-- ============================================
-- `product_variant_locations.stock` and `product_variant_store_stock.stock` are the last two places a quantity
-- could be read from that the ledger does not own. They go together because they were always one fact written
-- twice: the per-location row and the per-store aggregate, kept in step by every mover since W3-D5 and by nothing
-- else. What replaced them is `stock_balance` and `stock_position`, the projections `V61`'s trigger derives from
-- every `stock_movement`, and which every reader and every decision already answers from: the store-scoped list,
-- the POS search, the per-store editor's response, the sale's 409 gate and the measure-unit history guard.
--
-- WHAT THE DROP REFUSES, AND WHY A FROZEN COLUMN STILL NEEDS A GUARD
-- -----------------------------------------------------------------
-- Both columns stopped being written in T3c, so a divergence between them and the ledger is EXPECTED: the numbers
-- in them are the last values the old writer set, and every movement since has moved only the projections. A guard
-- that refused on disagreement would refuse on every database that has sold anything since T3c shipped — which is
-- the shape of guard that gets edited under pressure at 3am, and this file refuses to be that.
--
-- What is NOT expected, and is the only thing worth blocking on, is a number with no ledger row behind it at all:
-- a location or a store that the legacy column says holds quantity, and that the ledger has never moved. Dropping
-- the column would delete the sole record of stock that nobody booked, so it refuses and names the pair and the
-- next step. `V62` replayed every legacy balance into the ledger, so a tree that has run it has a row for every
-- non-zero legacy value and this guard passes; a row written by hand after `V62` is exactly what it catches.
--
-- WHAT DOES NOT DIE
-- ----------------
-- **Both tables survive.** `product_variant_store_stock` still carries the two facts the ledger needs from it: the
-- cost an inbound movement has to state (`ck_movement_inbound_cost`) and a row to serialize a per-store edit on.
-- `product_variant_locations` keeps its identity and its timestamps; what it loses is the number. Dropping either
-- table is not this file's decision and not this unit's — ADR-0001 retires a quantity, not an identity.
--
-- WHY THE GUARD RUNS INSIDE A `NO FORCE` WINDOW
-- ----------------------------------------------
-- The guard compares the legacy columns against `stock_balance` and `stock_position`, and both are armed on
-- `tenant_table` with `ENABLE` **and** `FORCE` (`V60`): the owner is filtered too, and a migration runs as the
-- owner with no `app.company_id` set. Without a window each `NOT EXISTS` sees zero rows, so the guard would refuse
-- on every database that ever held stock — which is every database, and the shape this file refuses to be. The
-- window is the mechanism `V57`, `V62`, `V64`, `V66` and `V72` already use; it is transactional, it is checked
-- before it is relied on (a window that did not take answers every question with zero rows and PASSES), and it is
-- closed by an assertion rather than by good intentions. This is not a style choice: the first draft of this file
-- had no window, and four migration suites caught it with exactly that refusal.
--
-- WHY THE ASSERTIONS ARE TWO BLOCKS AND NOT ONE
-- ---------------------------------------------
-- The first answers "did the drop reach exactly the two columns it was aimed at", which is the question a
-- half-applied file fails. The second answers "is what shared those tables still there" — the cost the store row
-- must keep stating, the identity rows the ledger's foreign keys point at, and the tables themselves — which is the
-- question a drop that cascaded further than intended fails. They fail for unrelated reasons and a single message
-- would name only one of them.

-- ============================================
-- 1. the NO FORCE window, opened before the first read
-- ============================================
ALTER TABLE stock_balance  NO FORCE ROW LEVEL SECURITY;
ALTER TABLE stock_position NO FORCE ROW LEVEL SECURITY;

DO $$
DECLARE
    v_still_forced text;
BEGIN
    SELECT string_agg(c.relname, ', ' ORDER BY c.relname) INTO v_still_forced
      FROM pg_class c
     WHERE c.relkind = 'r' AND c.relforcerowsecurity
       AND c.relname IN ('stock_balance', 'stock_position');

    IF v_still_forced IS NOT NULL THEN
        RAISE EXCEPTION
            'V74 could not open its NO FORCE window on %: the guard below would read no projection row, treat '
            'every non-zero legacy balance as unbooked stock and refuse a migration that has nothing to refuse. '
            'A window that did not take is the whole defect this block is here to prevent.', v_still_forced;
    END IF;
END $$;

-- ============================================
-- 2. the proof the drop refuses to run without
-- ============================================
DO $$
DECLARE
    v_offenders bigint;
    v_variant uuid;
    v_location uuid;
    v_store uuid;
BEGIN
    SELECT count(*)
      INTO v_offenders
      FROM product_variant_locations pvl
     WHERE pvl.stock <> 0
       AND NOT EXISTS (SELECT 1
                         FROM stock_balance b
                        WHERE b.store_location_id = pvl.store_location_id
                          AND b.product_variant_id = pvl.product_variant_id);
    IF v_offenders > 0 THEN
        SELECT pvl.product_variant_id, pvl.store_location_id
          INTO v_variant, v_location
          FROM product_variant_locations pvl
         WHERE pvl.stock <> 0
           AND NOT EXISTS (SELECT 1
                             FROM stock_balance b
                            WHERE b.store_location_id = pvl.store_location_id
                              AND b.product_variant_id = pvl.product_variant_id)
         ORDER BY pvl.product_variant_id, pvl.store_location_id
         LIMIT 1;
        RAISE EXCEPTION
            'refusing to drop product_variant_locations.stock: % location balance(s) hold a quantity the ledger '
            'never moved, so the column is the only record of them. First offender: variant %, location %. Next '
            'step: book that quantity as a movement at that location (a RECEIPT or an ADJUSTMENT_IN through the '
            'API, which is the only writer of stock_movement and the only thing the projections answer from), then '
            're-run this migration. The number in the column is NOT copied anywhere by this file: the ledger must '
            'observe it, not inherit it (ADR-0001 decision 1).',
            v_offenders, v_variant, v_location;
    END IF;

    SELECT count(*)
      INTO v_offenders
      FROM product_variant_store_stock pvss
     WHERE pvss.stock <> 0
       AND NOT EXISTS (SELECT 1
                         FROM stock_position p
                        WHERE p.company_store_id = pvss.company_store_id
                          AND p.product_variant_id = pvss.product_variant_id);
    IF v_offenders > 0 THEN
        SELECT pvss.product_variant_id, pvss.company_store_id
          INTO v_variant, v_store
          FROM product_variant_store_stock pvss
         WHERE pvss.stock <> 0
           AND NOT EXISTS (SELECT 1
                             FROM stock_position p
                            WHERE p.company_store_id = pvss.company_store_id
                              AND p.product_variant_id = pvss.product_variant_id)
         ORDER BY pvss.product_variant_id, pvss.company_store_id
         LIMIT 1;
        RAISE EXCEPTION
            'refusing to drop product_variant_store_stock.stock: % store aggregate(s) hold a quantity the ledger '
            'never moved. First offender: variant %, store %. Next step: book that quantity as a movement in that '
            'store (a RECEIPT or an ADJUSTMENT_IN through the API), then re-run this migration. Nothing in this '
            'file copies the column into the ledger, on purpose.',
            v_offenders, v_variant, v_store;
    END IF;
END $$;

-- ============================================
-- 3. the window closes, and the close is checked
-- ============================================
-- After the guard and never before it: `FORCE` filters the owner, so a read placed below these two `ALTER`s would
-- see nothing and pass. `V72:148-155` learned the same lesson in the same words.
ALTER TABLE stock_balance  FORCE ROW LEVEL SECURITY;
ALTER TABLE stock_position FORCE ROW LEVEL SECURITY;

DO $$
DECLARE
    v_not_forced text;
BEGIN
    SELECT string_agg(c.relname, ', ' ORDER BY c.relname) INTO v_not_forced
      FROM pg_class c
     WHERE c.relkind = 'r' AND NOT c.relforcerowsecurity
       AND c.relname IN ('stock_balance', 'stock_position');

    IF v_not_forced IS NOT NULL THEN
        RAISE EXCEPTION
            'V74 left % without FORCE ROW LEVEL SECURITY: a table whose owner is exempt from its own policy is '
            'the state ADR-0005 exists to remove, and a window left open weakens the table for everything that '
            'runs after it (V55).', v_not_forced;
    END IF;
END $$;

-- ============================================
-- 4. the drop
-- ============================================
ALTER TABLE product_variant_locations DROP COLUMN stock;
ALTER TABLE product_variant_store_stock DROP COLUMN stock;

-- ============================================
-- 5. the self-check: the columns are gone, and what shared their tables is not
-- ============================================
DO $$
DECLARE
    v_present text;
BEGIN
    SELECT string_agg(table_name || '.' || column_name, ', ')
      INTO v_present
      FROM information_schema.columns
     WHERE table_schema = 'public'
       AND column_name = 'stock'
       AND table_name IN ('product_variant_locations', 'product_variant_store_stock');
    IF v_present IS NOT NULL THEN
        RAISE EXCEPTION 'the two legacy stock columns must be gone, but % still carries one', v_present;
    END IF;

    IF to_regclass('public.product_variant_locations') IS NULL
       OR to_regclass('public.product_variant_store_stock') IS NULL THEN
        RAISE EXCEPTION
            'the tables themselves must survive this file: they keep the identity rows the ledger points at and '
            'the cost an inbound movement has to state. One of them is missing.';
    END IF;

    IF NOT EXISTS (SELECT 1
                     FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND table_name = 'product_variant_store_stock'
                      AND column_name = 'cost_price') THEN
        RAISE EXCEPTION
            'product_variant_store_stock.cost_price is gone, and ck_movement_inbound_cost requires it on every '
            'row that adds quantity: the ledger could not book a receipt after this file.';
    END IF;

    RAISE NOTICE
        'V74: both legacy stock columns are gone; product_variant_locations and product_variant_store_stock '
        'survive with their identity and cost columns. The ledger is the only quantity that remains.';
END $$;
