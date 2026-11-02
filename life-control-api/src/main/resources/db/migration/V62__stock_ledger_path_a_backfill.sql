-- ============================================
-- V62 — ADR-0001 step 3: Path A, today's balances become the ledger, verified before this file commits
-- ============================================
-- ADR-0001 is accepted (`docs/adr/0001-inventory-ledger-single-source.md`, 2026-11-02). `V59` gave the
-- location tree its store, `V60` created the five tables and armed them, `V61` installed the engine — the
-- projection trigger, the allocation writer, the rebuild and the two checks. This file is step 3 of that
-- ADR's `## Migration from the current tree` (`:239`) and **nothing else**: every row of
-- `product_variant_locations` that holds stock becomes one `ADJUSTMENT_IN` (`:217-221`), the projections are
-- rebuilt from that ledger, and `I2` (`:181`) and `I6` (`:185`) are asserted **before the migration can
-- commit**. No reader moves, no column is dropped, no service changes — the readers are T5 of
-- `odd/tasks/inventory-ledger.md` and the drops are T6.
--
-- WHAT PATH A IS, IN THE ADR'S OWN WORDS, AND WHY THE REPLAY IS NOT A COPY
--   `:217-221`: "For every row of `product_variant_locations`, one `ADJUSTMENT_IN` with `quantity = stock`
--   and `unit_cost = cost_price` (from `product_variant_store_stock`), `source_type = 'MIGRATION'`. The
--   ledger's sum then equals today's balances by construction." The `product_variant_locations` row is the
--   authority Path A replays, not the store row: `InventoryService.java:57` states the invariant those two
--   tables have been maintained under by hand — `product_variant_store_stock.stock = SUM(
--   product_variant_locations.stock)` — and `:69-72` records why deriving one from the other was refused
--   for the whole life of this tree: a divergence repaired by derivation "silently resurrects stock a sale
--   already sold". This file therefore **writes the locations into the ledger and reports** the store rows
--   that disagree with them, instead of picking the number that would look nicer.
--
-- MEASURED IN THE TREE, opened rather than guessed
--   * `product_variant_locations` — `V10:18-26`: `(product_variant_id, store_location_id, stock
--     DECIMAL(12,2) NOT NULL DEFAULT 0)`, `UNIQUE (product_variant_id, store_location_id)` at `:25`, and
--     **no store column at all**. The store of a location is reached through `store_locations`, which is
--     why `V59:118-137` had to run first: it added `store_locations.company_store_id` (`:118`), backfilled
--     it through the zone (`:120-133`), made it `NOT NULL` (`:135`) and gave the table the
--     `UNIQUE (company_store_id, id)` (`:137`) that `fk_movement_location` (`V60:135-136`) resolves
--     against. So `sl.company_store_id` below is an exact match and not a guess.
--   * `product_variant_store_stock` — `V13:23-33`: `stock` (`:27`), `list_price` (`:28`),
--     `cost_price DECIMAL(12,2)` (`:29`) **nullable**, `UNIQUE (product_variant_id, company_store_id)`
--     (`:32`). Nullable is not an oversight to fix here: the column has been nullable since `V13`, so a
--     real tree has rows whose cost nobody wrote down, and this file decides what those rows are worth
--     (below) instead of refusing to run.
--   * the columns written — `V60:116-164`: `ck_movement_type` (`:143-146`) admits `ADJUSTMENT_IN`,
--     `ck_movement_quantity` (`:142`) refuses a row that moves nothing, `ck_movement_inbound_cost`
--     (`:147-149`) demands `unit_cost` **and** `currency` on an inbound row, and `uq_movement_source`
--     (`:171-173`) makes `(source_type, source_id, movement_type)` unique. Taking the location row's own
--     `id` as `source_id` satisfies that index one-to-one: a second run of the insert cannot double-book,
--     because the location id it would copy does not exist twice.
--   * the engine this file calls — `V61:219` `replay_stock_valuation` (which joins `companies` at `:296` for
--     the currency), `:671` `rebuild_stock_projections`, `:730` `verify_stock_projections` (`I2`), `:868`
--     `unallocated_stock_movements` (`I6`), and the projection trigger `:397-400`, which fires on every row
--     this file inserts and maintains `stock_balance` (`:379`), `stock_position` (`:385`) and
--     `stock_valuation` (`:391`).
--
-- WHY THE ROW-LEVEL SECURITY WINDOW, AND WHY IT IS SEVEN TABLES AND NOT TWO
--   `V52__tenant_isolation_machinery.sql:152-153` calls `ENABLE` **and `FORCE`** on every table it arms;
--   `V53:108` arms `companies` by its own `id`, `V53:123` arms `product_variants`, and
--   `V60:307-311` arms all five ledger tables. `FORCE` is what makes a policy apply to the table's
--   **owner**, and the owner is the role Flyway runs as — `lifecontrol_owner`, `NOSUPERUSER` by
--   `docker/postgres-init/lifecontrol-roles.sql`. Without a window every statement below reads an empty
--   tree **and reports success**: the insert would write zero movements, the rebuild would write zero
--   projections, and the two assertions would hold vacuously over nothing. That is the failure
--   `V58__variant_listing.sql:122-123` opens its window for, and this file uses the same shape.
--   Seven tables, because these are the seven the statements below actually touch:
--       companies          read by the insert (`c.currency`) and by the valuation replay (`V61:296`)
--       product_variants   read by the insert (the variant's company)
--       stock_movement     written by the insert, read by the rebuild and by both checks
--       stock_allocation   read by the valuation replay and by `I6`, although this file writes no allocation
--       stock_balance      written by the projection trigger and deleted/re-written by the rebuild
--       stock_position     same
--       stock_valuation    same
--   `store_locations`, `store_zones`, `product_variant_locations` and `product_variant_store_stock` are **not**
--   in the list, and that is a measurement rather than an omission: none of them carries a `company_id`, so
--   ADR-0005 never armed them — the same fact `V59`'s header records for the two it created.
--   `SECURITY DEFINER` would dodge all of this and is refused: the engine's head is the reason (`V61:119-120`)
--   — the writers meet the policies of the slice that armed them, and a definer function would bypass them.
--   The window is opened in section 1, **proven open in section 2 before any statement relies on it**, and
--   closed and re-proven in section 9.
--
-- THE FIVE DECISIONS THIS FILE TAKES, WHICH NEITHER THE ADR NOR THE RECORD SETTLED
--   1. **A location at zero balance writes no movement.** Path A says "for every row … `quantity = stock`",
--      and `ck_movement_quantity` (`V60:142`) refuses `quantity <= 0`, so a zero-stock location cannot be
--      replayed literally. It is also the honest answer: the ledger is a record of changes, a balance of
--      zero is the absence of one, and a zero-quantity row would exist only to say nothing. Such a location
--      therefore has no `stock_balance` row after this file — which is correct, because the rebuild derives
--      the projections from the same ledger (`V61:671-720`) and would produce the same nothing.
--   2. **A location with no recorded cost gets `unit_cost = 0`, and says so in its `reason`.** `cost_price`
--      is nullable (`V13:29`) and `ck_movement_inbound_cost` (`V60:147-149`) refuses an inbound row without
--      a cost, so one of the two has to give. The file does not refuse to run — a tree where nobody filled
--      the field is normal, and a migration nobody can run is a migration that gets edited under pressure —
--      and it does not invent a cost either. Zero plus a reason that names the missing fact is the honest
--      encoding of "unknown": it is the only value that makes the valuation report the cost as unmeasured
--      rather than as a number somebody made up. The count is **printed** (section 7) so the operator learns
--      how much of the valuation rests on that, instead of discovering it in a report.
--   3. **A location with a negative balance stops the file.** A negative location balance cannot be an
--      inbound row (`quantity > 0`), and it cannot be an `ADJUSTMENT_OUT` either: an outbound row is a
--      consumption, `I6` (`V61:868`) reports one whose allocations do not sum to its quantity, and this
--      file writes no allocation — so every negative balance would leave an `I6` violation behind for ever.
--      It is refused, listed, and left for the operator: a negative balance is a state the current writers
--      do not produce, and interpreting it here would invent a history to explain it.
--   4. **A store row with stock and no location in that store stops the file.** `product_variant_store_stock`
--      claims a quantity that no `product_variant_locations` row in that store holds, so Path A has nothing
--      to attribute and the alternatives are both false: inventing a location writes a fact nobody recorded,
--      and skipping it leaves the quantity the operator sees with no ledger behind it — exactly the silence
--      this ADR exists to remove. The refusal lists the `(variant, store)` pairs and stops, the report-then-fix
--      shape `V55__variant_measure_unit_required.sql:53-62` uses for the unit it refuses to guess.
--   5. **A store row whose `stock` differs from its locations' sum is reported and not repaired.** Path A
--      proceeds from the locations (`:217-221`), so for exactly those pairs the number the operator sees
--      changes. The pair is printed with both numbers (section 7), because a migration that silently moved a
--      visible number is the defect this whole ADR is about; and `product_variant_store_stock.stock` is left
--      exactly as it is, because it is not this file's column — retiring it is T6's slice.
--   Two more facts are stated rather than decided: `occurred_at` is the migration's own `now()`, because a
--   synthesized balance has no historical instant to borrow and stamping the location's `created_at` would
--   invent one; and `actor` is left `NULL`, because the attribution is `source_type = 'MIGRATION'` plus
--   `source_id`, and the file that wrote the rows is in `flyway_schema_history`.
--
-- WHAT THIS FILE REFUSES TO INTERPRET AND THE FOREIGN KEYS DO NOT
--   Nothing in the schema ties a `product_variant_locations` row to one tenant: it carries no `company_id`,
--   so a row pairing company A's variant with a location of company B's store is representable today. The
--   movement's `company_id` comes from the variant and its store from the location, so such a pair is refused
--   by `fk_movement_store` (`V60:133-134`) and this file aborts naming that constraint instead of writing a
--   cross-tenant movement. That is the correct outcome, and it is not repaired by deriving one side from the
--   other: the ADR's I3 is that a movement's store is its location's store, and a row where the variant and
--   the store disagree is data this migration refuses to interpret — the same call `V58:71-78` makes for a
--   cross-tenant listing.
--
-- THE REBUILD AND THE TWO ASSERTIONS (section 8), AND WHY THEY RUN BEFORE THE COMMIT
--   The trigger already projected every row this file inserted, so the rebuild is not a repair — it is the
--   **proof** that the fast path and the reference agree on the ledger this file just wrote: it deletes the
--   scope's three projections and writes them again from `replay_stock_ledger` (`V61:671-720`). Then `I2`
--   asks `verify_stock_projections` for the rows that disagree (`V61:730`) and `I6` asks
--   `unallocated_stock_movements` for outbound rows whose allocations do not sum (`V61:868`), and **either
--   one being non-empty raises**. The second is expected to be empty by construction — this file writes
--   inbound-only rows — so a row there is a defect in this file and not in the data, which is exactly the
--   distinction a failed migration should draw. A migration that reported success on a projection it never
--   verified is the failure ADR-0001 exists to remove.
--
-- WHAT THIS FILE DELIBERATELY DOES NOT DO
--   * no `DROP`, and in particular not `product_variant_locations.stock` or
--     `product_variant_store_stock.stock`: the balances stay readable until T5 has moved every reader and
--     T6 drops them. The ledger is a **second** copy of the quantity until then, on purpose, and I2 is what
--     says the two agree.
--   * no change to `product_variant_store_stock`: not its `stock` (T6), not its `cost_price`, and no
--     `variant_listing` row.
--   * no reader move, no entity, no DTO, no service, no Java: `ddl-auto=validate` is untouched because this
--     file adds no table and no column.
--   * no `inventory_movements` migration and no allocation: the old ledger's rows are T6's, and this
--     backfill writes inbound rows only.
--   * no `SECURITY DEFINER`, no index, no retention policy, no `stock_balance`/`stock_position`/
--     `stock_valuation` row authored by hand — the rebuild writes them from the replay.
--
-- Forward-only. Undoing it means deleting exactly the rows it wrote, which `source_type = 'MIGRATION'`
-- identifies and nothing else in this tree writes:
--     DELETE FROM stock_movement WHERE source_type = 'MIGRATION';
-- which is refused by `trg_stock_movement_append_only` (`V60:275-278`) while the trigger exists, so the undo
-- lifts it for that statement (`ALTER TABLE stock_movement DISABLE TRIGGER trg_stock_movement_append_only`)
-- and re-enables it immediately after — and only then calls `rebuild_stock_projections(company_id)` for the
-- companies it touched, because the projections would otherwise keep describing rows that no longer exist.
-- No projection row survives the undo by accident, no other ledger row is touched, and the two source tables
-- were never written by this file in the first place.
-- ============================================

-- ─── 1. the window: seven tables, for the length of this file's transaction ──────────────────────
-- The statements below write `stock_movement`, both projections and the valuation, and read `companies`,
-- `product_variants` and `stock_allocation` through the engine — every one of them armed with `FORCE`, which
-- applies to the owner this file runs as. See the header for the per-table reason; the shape is `V58:122-123`
-- and the close is `V58:181-182`.
ALTER TABLE companies NO FORCE ROW LEVEL SECURITY;
ALTER TABLE product_variants NO FORCE ROW LEVEL SECURITY;
ALTER TABLE stock_movement NO FORCE ROW LEVEL SECURITY;
ALTER TABLE stock_allocation NO FORCE ROW LEVEL SECURITY;
ALTER TABLE stock_balance NO FORCE ROW LEVEL SECURITY;
ALTER TABLE stock_position NO FORCE ROW LEVEL SECURITY;
ALTER TABLE stock_valuation NO FORCE ROW LEVEL SECURITY;

-- ─── 2. the window is proven open BEFORE anything relies on it ───────────────────────────────────
-- Two refusals, because "no policy is forced" and "the tree is reachable" are different facts and only the
-- second one is the premise of every statement below:
--   * a table that is still forced, which a later migration could reintroduce without touching this file;
--   * a window that is open and a tree that is still invisible, which is the empirical check: the joins
--     cannot lose a row — `product_variant_id` and `store_location_id` are both `NOT NULL` foreign keys
--     (`V10:20-21`) — so a difference between the two counts means a policy is filtering and the insert
--     below would write nothing while this file reported success.
DO $$
DECLARE
    v_forced    text;
    v_rows      bigint;
    v_reachable bigint;
BEGIN
    SELECT string_agg(c.relname, ', ' ORDER BY c.relname)
      INTO v_forced
      FROM pg_class c
     WHERE c.relname IN ('companies', 'product_variants', 'stock_movement', 'stock_allocation',
                         'stock_balance', 'stock_position', 'stock_valuation')
       AND c.relkind = 'r'
       AND c.relforcerowsecurity;

    IF v_forced IS NOT NULL THEN
        RAISE EXCEPTION
            'V62 could not open its NO FORCE window on: %. Every statement below would read an empty tree as the table owner and report success — the insert would write no movement, the rebuild no projection, and I2/I6 would hold over nothing (ADR-0005 correction 3; the window V58:122-123 opens).',
            v_forced;
    END IF;

    SELECT count(*) INTO v_rows FROM product_variant_locations;
    SELECT count(*)
      INTO v_reachable
      FROM product_variant_locations l
      JOIN product_variants v ON v.id = l.product_variant_id
      JOIN store_locations sl ON sl.id = l.store_location_id;

    IF v_reachable <> v_rows THEN
        RAISE EXCEPTION
            'V62 reaches % of the % product_variant_locations rows through product_variants and store_locations, and both of those foreign keys are NOT NULL and enforced (V10:20-21) — so only a row level security policy can lose one. The window above did not take, and the replay would report success over a tree it never read.',
            v_reachable, v_rows;
    END IF;
END $$;

-- ─── 3. the two states this file refuses to interpret ────────────────────────────────────────────
-- Both are listed in full, in a stable order, in one message each: `V55:52-62`'s shape, because a refusal
-- that names one row and hides the rest is a report the operator has to reconstruct by hand.
DO $$
DECLARE
    v_negative     text;
    v_unattributed text;
BEGIN
    -- A negative balance is neither an inbound row (`ck_movement_quantity`, V60:142) nor a legitimate
    -- outbound one (no allocation exists for it, and I6 would report it for ever). See decision 3.
    SELECT string_agg(
               format('location %s (variant %s, store %s): stock %s',
                      l.id, l.product_variant_id, sl.company_store_id, l.stock),
               ', ' ORDER BY l.id)
      INTO v_negative
      FROM product_variant_locations l
      JOIN store_locations sl ON sl.id = l.store_location_id
     WHERE l.stock < 0;

    IF v_negative IS NOT NULL THEN
        RAISE EXCEPTION
            'V62 aborted: these product_variant_locations rows hold a negative balance: %. Path A synthesizes one ADJUSTMENT_IN with quantity = stock, and ck_movement_quantity (V60:142) refuses a row that moves nothing; an outbound row instead would be a consumption with no allocation behind it, which invariant I6 (V61:868) would report for ever. A negative location balance is data this migration refuses to interpret — correct the balances and re-run this migration.',
            v_negative;
    END IF;

    -- A store row that claims stock no location of that store holds. See decision 4.
    SELECT string_agg(
               format('variant %s at store %s: product_variant_store_stock.stock = %s',
                      ss.product_variant_id, ss.company_store_id, ss.stock),
               ', ' ORDER BY ss.company_store_id, ss.product_variant_id)
      INTO v_unattributed
      FROM product_variant_store_stock ss
     WHERE ss.stock <> 0
       AND NOT EXISTS (
               SELECT 1
                 FROM store_locations sl
                 JOIN product_variant_locations l
                   ON l.store_location_id = sl.id
                  AND l.product_variant_id = ss.product_variant_id
                WHERE sl.company_store_id = ss.company_store_id);

    IF v_unattributed IS NOT NULL THEN
        RAISE EXCEPTION
            'V62 aborted: these (variant, store) pairs hold stock in product_variant_store_stock and have no product_variant_locations row in that store: %. Path A attributes the ledger to locations, so this quantity has no row to attribute and inventing one would write a fact nobody recorded, while skipping it would leave the number the operator sees with no ledger behind it. Give each of these pairs the location its stock actually sits in — the store row has been the aggregate of its locations (InventoryService.java:57) — and re-run this migration.',
            v_unattributed;
    END IF;
END $$;

-- ─── 4. the replay: one ADJUSTMENT_IN per location that holds stock ──────────────────────────────
-- The whole of Path A, in one statement, so a reviewer can read it beside `:217-221`:
--   quantity   = the location's balance                     (`quantity = stock`)
--   unit_cost  = the store row's `cost_price` for that pair, matching on `(product_variant_id,
--                company_store_id)` — `V13:32` makes that pair unique, so the match is exact — or 0 when the
--                row records no cost, with a `reason` that says the cost is unknown (decision 2)
--   currency   = `companies.currency`, the operating currency `V57` made `NOT NULL` and ADR-0002 decision 2
--                makes the only one a document can be in
--   source     = `'MIGRATION'` and the location row's own id, which `uq_movement_source` (V60:171-173) turns
--                into an idempotency key rather than a coincidence
--   reason     = where the row came from, in the row itself, for whoever reads the ledger later
-- `WHERE l.stock > 0` is decision 1 and not a convenience: a zero balance cannot be a movement, and the
-- rebuild derives the same nothing from the same ledger.
INSERT INTO stock_movement (company_id, company_store_id, store_location_id, product_variant_id,
                            movement_type, quantity, unit_cost, currency, source_type, source_id, reason)
SELECT v.company_id,
       sl.company_store_id,
       l.store_location_id,
       l.product_variant_id,
       'ADJUSTMENT_IN',
       l.stock,
       COALESCE(ss.cost_price, 0),
       c.currency,
       'MIGRATION',
       l.id,
       CASE
           WHEN ss.cost_price IS NULL THEN
               'ADR-0001 Path A: balance synthesized from product_variant_locations.stock at V62. ck_movement_inbound_cost (V60:147-149) demands a cost on an inbound row and product_variant_store_stock records no cost_price for this (variant, store), so unit_cost = 0 records that the cost is unknown rather than inventing one.'
           ELSE
               'ADR-0001 Path A: balance synthesized from product_variant_locations.stock at V62; unit_cost is the cost_price product_variant_store_stock records for this (variant, store).'
       END
  FROM product_variant_locations l
  JOIN product_variants v ON v.id = l.product_variant_id
  JOIN store_locations sl ON sl.id = l.store_location_id
  JOIN companies c ON c.id = v.company_id
  LEFT JOIN product_variant_store_stock ss
         ON ss.product_variant_id = l.product_variant_id
        AND ss.company_store_id = sl.company_store_id
 WHERE l.stock > 0;

-- ─── 5. the insert is checked against the tree it read, while the window is still open ───────────
-- A `FULL JOIN` between the locations that hold stock and the movements this file wrote, so a missing
-- movement, an extra one, or one that moves a different quantity are all named instead of counted. A
-- statement that writes N rows and reports N is not evidence that those were the right N.
DO $$
DECLARE
    v_expected bigint;
    v_written  bigint;
    v_wrong    text;
BEGIN
    SELECT count(*) INTO v_expected FROM product_variant_locations WHERE stock > 0;
    SELECT count(*)
      INTO v_written
      FROM stock_movement m
     WHERE m.source_type = 'MIGRATION'
       AND m.movement_type = 'ADJUSTMENT_IN';

    IF v_expected <> v_written THEN
        RAISE EXCEPTION
            'V62 wrote % movements for % locations that hold stock, and the replay is one row each (ADR-0001 :217-221). A difference here means the insert read a different tree than the one this file measured.',
            v_written, v_expected;
    END IF;

    SELECT string_agg(
               CASE
                   WHEN l.id IS NULL THEN
                       format('movement %s names source id %s, which is not a location holding stock',
                              m.id, m.source_id)
                   WHEN m.id IS NULL THEN
                       format('location %s holds %s and no movement was written for it', l.id, l.stock)
                   ELSE
                       format('location %s holds %s and its movement moves %s', l.id, l.stock, m.quantity)
               END,
               ', ')
      INTO v_wrong
      FROM (SELECT id, stock FROM product_variant_locations WHERE stock > 0) l
      FULL JOIN (SELECT id, source_id, quantity
                   FROM stock_movement
                  WHERE source_type = 'MIGRATION'
                    AND movement_type = 'ADJUSTMENT_IN') m
             ON m.source_id = l.id
     WHERE l.id IS NULL
        OR m.id IS NULL
        OR l.stock <> m.quantity;

    IF v_wrong IS NOT NULL THEN
        RAISE EXCEPTION
            'V62 wrote a ledger that does not match the balances it replayed: %. ADR-0001 Path A is one ADJUSTMENT_IN per product_variant_locations row with quantity = stock, and this file leaves nothing behind that is not that.',
            v_wrong;
    END IF;
END $$;

-- ─── 6. the rebuild: the projections, derived again from the ledger this file wrote ──────────────
-- Not a repair — the trigger already projected every row — but the proof that the fast path and the
-- reference agree, since the rebuild deletes the scope's three projections and writes them from
-- `replay_stock_ledger` (`V61:671-720`). One company at a time, over every company id the ledger holds —
-- which, before this file ran, was none, so the loop's scope is exactly what the replay above wrote and no
-- projection of another company is touched.
DO $$
DECLARE
    v_company uuid;
BEGIN
    FOR v_company IN SELECT DISTINCT m.company_id FROM stock_movement m ORDER BY m.company_id LOOP
        PERFORM rebuild_stock_projections(v_company);
    END LOOP;
END $$;

-- ─── 7. the two reports: what the operator's numbers are about to look like ──────────────────────
-- Both are printed rather than decided, and both name the rows. A divergence the store row keeps is a
-- number that changes for the operator, so it is said out loud here and not discovered on a screen; and how
-- many movements carry `unit_cost = 0` is a claim about how much of the valuation rests on a cost nobody
-- recorded, which is the one number in this file a report cannot recover later.
DO $$
DECLARE
    v_pairs       bigint;
    v_divergent   text;
    v_free        bigint;
    v_synthesized bigint;
BEGIN
    WITH diverged AS (
        SELECT ss.product_variant_id,
               ss.company_store_id,
               ss.stock AS store_stock,
               SUM(l.stock) AS locations_stock
          FROM product_variant_store_stock ss
          JOIN store_locations sl ON sl.company_store_id = ss.company_store_id
          JOIN product_variant_locations l
            ON l.store_location_id = sl.id
           AND l.product_variant_id = ss.product_variant_id
         GROUP BY ss.product_variant_id, ss.company_store_id, ss.stock
        HAVING ss.stock <> SUM(l.stock))
    SELECT count(*),
           string_agg(
               format('variant %s at store %s: product_variant_store_stock.stock = %s, sum(product_variant_locations.stock) = %s',
                      d.product_variant_id, d.company_store_id, d.store_stock, d.locations_stock),
               ', ' ORDER BY d.company_store_id, d.product_variant_id)
      INTO v_pairs, v_divergent
      FROM diverged d;

    IF v_pairs > 0 THEN
        RAISE NOTICE
            'V62 reports % (variant, store) pair(s) whose product_variant_store_stock.stock differs from the sum of its product_variant_locations.stock: %. The ledger follows the locations (ADR-0001 Path A, :217-221), so the ledger and the position are the location numbers and product_variant_store_stock keeps the value it had — for exactly these pairs the number the operator sees is not the ledger, until T6 retires that column.',
            v_pairs, v_divergent;
    END IF;

    SELECT count(*) FILTER (WHERE m.unit_cost = 0), count(*)
      INTO v_free, v_synthesized
      FROM stock_movement m
     WHERE m.source_type = 'MIGRATION';

    IF v_free > 0 THEN
        RAISE NOTICE
            'V62 recorded unit_cost = 0 for % of % synthesized movement(s), because product_variant_store_stock records no cost_price for them. ck_movement_inbound_cost (V60:147-149) requires a cost and "unknown" is the honest value; the affected movements say so in their reason, and their value therefore contributes 0 to stock_valuation.',
            v_free, v_synthesized;
    END IF;
END $$;

-- ─── 8. the two assertions, before this file can commit ──────────────────────────────────────────
-- `I2` (`:181`) over every company the ledger now holds a row for, and `I6` (`:185`) over the same: the
-- scope is derived from the ledger rather than from a list this file keeps, so nothing it wrote can escape
-- the check. Empty is the only acceptable answer, and each refusal carries the rows.
DO $$
DECLARE
    v_i2 text;
    v_i6 text;
BEGIN
    SELECT string_agg(
               format('%s (store %s, variant %s): quantity %s projected vs %s replayed, value %s vs %s',
                      d.projection, d.company_store_id, d.product_variant_id,
                      d.projected_quantity, d.replayed_quantity,
                      d.projected_total_value, d.replayed_total_value),
               ', ' ORDER BY d.projection, d.company_store_id, d.product_variant_id)
      INTO v_i2
      FROM (SELECT DISTINCT m.company_id FROM stock_movement m) c
      CROSS JOIN LATERAL verify_stock_projections(c.company_id) d;

    IF v_i2 IS NOT NULL THEN
        RAISE EXCEPTION
            'V62 rebuilt the projections and invariant I2 (ADR-0001 :181) still disagrees with the replay: %. The write, the rebuild and the check all read the same ledger, so this is not a drift to repair later — it is this file''s own result, and it must not commit.',
            v_i2;
    END IF;

    SELECT string_agg(
               format('outbound movement %s (variant %s, store %s) moves %s and its allocations sum to %s',
                      u.out_movement_id, u.product_variant_id, u.company_store_id, u.quantity, u.allocated),
               ', ' ORDER BY u.out_movement_id)
      INTO v_i6
      FROM (SELECT DISTINCT m.company_id FROM stock_movement m) c
      CROSS JOIN LATERAL unallocated_stock_movements(c.company_id) u;

    IF v_i6 IS NOT NULL THEN
        RAISE EXCEPTION
            'V62 leaves invariant I6 (ADR-0001 :185) violated: %. This file writes inbound-only rows, so an unallocated outbound row cannot come from the tree it replayed — it means this file wrote something it did not mean to, and the fix belongs here rather than in the data.',
            v_i6;
    END IF;
END $$;

-- ─── 9. the window closes, and the close is proven ───────────────────────────────────────────────
-- All seven go back under the policies they had — the replay above is the only statements in this file that
-- needed them off, and they have run. The check is `V55:85-102`'s: a forgotten close leaves a tree whose
-- owner reads and writes across tenants for every migration after this one, and the next boot would never
-- say so.
ALTER TABLE companies FORCE ROW LEVEL SECURITY;
ALTER TABLE product_variants FORCE ROW LEVEL SECURITY;
ALTER TABLE stock_movement FORCE ROW LEVEL SECURITY;
ALTER TABLE stock_allocation FORCE ROW LEVEL SECURITY;
ALTER TABLE stock_balance FORCE ROW LEVEL SECURITY;
ALTER TABLE stock_position FORCE ROW LEVEL SECURITY;
ALTER TABLE stock_valuation FORCE ROW LEVEL SECURITY;

DO $$
DECLARE
    v_unarmed text;
BEGIN
    SELECT string_agg(c.relname, ', ' ORDER BY c.relname)
      INTO v_unarmed
      FROM pg_class c
     WHERE c.relname IN ('companies', 'product_variants', 'stock_movement', 'stock_allocation',
                         'stock_balance', 'stock_position', 'stock_valuation')
       AND c.relkind = 'r'
       AND NOT (c.relrowsecurity AND c.relforcerowsecurity);

    IF v_unarmed IS NOT NULL THEN
        RAISE EXCEPTION
            'V62 opened a NO FORCE window and did not close it on: %. Those tables are readable and writable by their owner without a tenant scope, which is the state ADR-0005 correction 3 refuses, and every migration after this one would inherit it.',
            v_unarmed;
    END IF;
END $$;
