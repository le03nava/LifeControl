-- ============================================
-- V75 — inventory_movements leaves (ADR-0001 T4, S5)
-- ============================================
-- `inventory_movements` is the ledger `V10` created and ADR-0001 replaced: the table that claimed to explain every
-- change of quantity, whose rows were never the authority for anything the application reads once `V61`'s
-- projections existed. What it still carried when this file runs is one thing — a **row id** — and that id was the
-- `source_id` of every movement booked before `V73`. Two identities were retired before this drop, in this order:
-- a sale books its own document line plus the line's write counter (`V73`), a receipt books its own line
-- (`GOODS_RECEIPT_ITEM`), and a manual edit states its own movement id (D-2). A reversal names the movement it
-- corrects. So the ledger's identity no longer depends on this table and the code that read it is gone.
--
-- WHAT DIES WITH IT, AND WHAT DOES NOT
-- ------------------------------------
-- The table, its five indexes and its rows go together. What does **not** die is any fact of quantity: `V62`
-- replayed every legacy balance into `stock_movement`, so the ledger already holds the history this table
-- described, and it holds it as rows nothing can update or delete. What is lost with the table is the row-level
-- provenance of the movements booked before `V73` — `reference_type`/`reference_id` lived here, and a mirrored
-- movement's `source_id` was this table's primary key, which now names nothing. That is a fact this file states
-- rather than a defect to fix later: from here on a pre-`V73` movement's provenance is `source_type =
-- 'INVENTORY_MOVEMENT'` and the `occurred_at` it was booked with, which is what an operator can still read.
--
-- THE DEPLOY-WINDOW COST, AND THE DECISION TAKEN ABOUT IT
-- ------------------------------------------------------
-- A sale booked before `V73` was reachable for a reversal only through the mirror: the ledger row named this
-- table's row, and `StockLedgerEngine` resolved one to the other. That fallback was deleted with the code that
-- read it, so from this file on such a sale is no longer reversible through the API — the cost D-1 of
-- `odd/tasks/ledger-mirror-retirement.md` names. D-1 asked this migration to **refuse** while an unreversed
-- movement of that shape exists; it does not, and the reason is measured: `V62`'s backfill creates exactly those
-- movements for every sale this deployment ever booked, so the refusal would fire on every database that has sold
-- anything before `V73` — including the one this repository develops against — and a migration that cannot run is
-- a migration that gets edited under pressure. The maintainer's decision, recorded in the unit's log, is
-- **report and proceed, the shape `V62` already uses for a number it cannot interpret**. So this file counts them,
-- prints the count and a sample an operator can act on, and drops. A deployment that wants to preserve
-- reversibility for those sales reverses them through the API **before** running this migration; that is the only
-- moment it is possible, and the report below is what tells it the size of the job.
--
-- WHY A `RAISE NOTICE` AND NOT A SILENT DROP
-- ------------------------------------------
-- A notice is what an operator sees while the migration runs, and `V62` established the shape: the report is not
-- persisted, so a later requirement for a queryable audit is a new decision, not an omission here. What is
-- deliberately absent is any count that this file asserts: an assertion over the data would be the refusal D-1
-- asked for and the maintainer refused, dressed as a check.

-- ============================================
-- 1. the report: what this deployment loses the ability to reverse
-- ============================================
DO $$
DECLARE
    v_legacy_rows bigint;
    v_mirrored_sales bigint;
    v_unreversed bigint;
    v_sample text;
BEGIN
    SELECT count(*) INTO v_legacy_rows FROM inventory_movements;

    SELECT count(*), count(*) FILTER (WHERE NOT EXISTS (SELECT 1
                                                          FROM stock_movement r
                                                         WHERE r.reverses_movement_id = m.id))
      INTO v_mirrored_sales, v_unreversed
      FROM stock_movement m
     WHERE m.source_type = 'INVENTORY_MOVEMENT'
       AND m.movement_type = 'SALE';

    SELECT string_agg(line, E'\n  ')
      INTO v_sample
      FROM (SELECT format('movement %s: variant %s, store %s, location %s, quantity %s, occurred_at %s',
                          m.id, m.product_variant_id, m.company_store_id, m.store_location_id, m.quantity,
                          m.occurred_at) AS line
              FROM stock_movement m
             WHERE m.source_type = 'INVENTORY_MOVEMENT'
               AND m.movement_type = 'SALE'
               AND NOT EXISTS (SELECT 1
                                 FROM stock_movement r
                                WHERE r.reverses_movement_id = m.id)
             ORDER BY m.occurred_at, m.id
             LIMIT 10) sample;

    RAISE NOTICE
        'V75: dropping inventory_movements (% row(s)). The ledger keeps every quantity fact (V62 replayed them '
        'into stock_movement); what ends is the row-level provenance of movements booked before V73.',
        v_legacy_rows;
    RAISE NOTICE
        'V75: % sale movement(s) name a legacy row (source_type = INVENTORY_MOVEMENT). % of them are NOT reversed '
        'and stop being reversible through the API once this file commits (D-1). A deployment that wants them '
        'reversible must reverse them through the API before upgrading past this migration.',
        v_mirrored_sales, v_unreversed;
    IF v_sample IS NOT NULL THEN
        RAISE NOTICE E'V75: the unreversed ones, oldest first (at most ten):\n  %', v_sample;
    END IF;
END $$;

-- ============================================
-- 2. the drop
-- ============================================
-- The five indexes and the primary key go with the table: PostgreSQL collects them, so no `DROP INDEX` is written
-- here. `V10:42-46` names them, and the cost of the omission is nothing — the relation is gone, so an index on it
-- cannot survive.
DROP TABLE inventory_movements;

-- ============================================
-- 3. the self-check: the ledger is intact and the mirror is gone
-- ============================================
DO $$
DECLARE
    v_missing text;
BEGIN
    IF to_regclass('public.inventory_movements') IS NOT NULL THEN
        RAISE EXCEPTION 'inventory_movements must be gone, and it is still a relation';
    END IF;

    SELECT string_agg(missing, ', ')
      INTO v_missing
      FROM (SELECT 'stock_movement' AS missing
             WHERE to_regclass('public.stock_movement') IS NULL
            UNION ALL
            SELECT 'stock_balance'
             WHERE to_regclass('public.stock_balance') IS NULL
            UNION ALL
            SELECT 'stock_position'
             WHERE to_regclass('public.stock_position') IS NULL
            UNION ALL
            SELECT 'stock_valuation'
             WHERE to_regclass('public.stock_valuation') IS NULL
            UNION ALL
            SELECT 'stock_allocation'
             WHERE to_regclass('public.stock_allocation') IS NULL) gone;
    IF v_missing IS NOT NULL THEN
        RAISE EXCEPTION
            'the ledger itself must survive the drop of the mirror it replaced, but these are missing: %. The '
            'projections and the allocations are what every reader and every decision answer from '
            '(ADR-0001 decisions 1 and 2).', v_missing;
    END IF;

    -- The two keys I5 and I4 rest on. A drop that cascaded into `stock_movement` would take them silently, and the
    -- ledger would keep booking rows while refusing nothing.
    SELECT string_agg(missing, ', ')
      INTO v_missing
      FROM (SELECT 'uq_movement_source' AS missing
             WHERE to_regclass('public.uq_movement_source') IS NULL
            UNION ALL
            SELECT 'uq_movement_one_reversal'
             WHERE to_regclass('public.uq_movement_one_reversal') IS NULL) gone;
    IF v_missing IS NOT NULL THEN
        RAISE EXCEPTION
            'the ledger''s two unique keys must survive: % missing (I5 as V73 states it, and I4: at most one '
            'reversal per movement).', v_missing;
    END IF;

    RAISE NOTICE
        'V75: the mirror is gone; stock_movement keeps its rows, its two keys, the three projections and the '
        'allocations.';
END $$;
