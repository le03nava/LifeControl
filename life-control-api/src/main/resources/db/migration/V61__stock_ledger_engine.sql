-- ============================================
-- V61 — ADR-0001: the stock ledger's engine — the projection, the consumption, the value, the replay
-- ============================================
-- ADR-0001 is accepted (`docs/adr/0001-inventory-ledger-single-source.md`, 2026-11-02) and `V60` created
-- its five tables and armed them. This file is its migration step "the projections and the rebuild": the
-- projection trigger (decision 2, `:51-55`), the allocation writer (decision 3, `:56-58`) with the costing
-- it applies (decision 4, `:59-61`), the full-replay rebuild that makes the projections disposable, and
-- the two checks — `I2` (`:181`) and `I6` (`:185`) — a person and the suite both run. **No entity, no
-- service, no reader, no backfill**: the readers are T5 of `odd/tasks/inventory-ledger.md`, the replay of
-- today's balances is T4, and the drops are T6. Everything here is a database function called through SQL,
-- because that is the decision recorded in that record's `## Log` ("the ledger's writers are SQL
-- functions"): under FIFO the consumption is a set operation over rows the ledger already holds, and a
-- Java implementation would read them back into memory and reproduce exactly the remainder arithmetic
-- `SalesOrderService.java:948-962` performs today — the structure the ADR removes.
--
-- THE TWO DECISIONS THE ACCEPTANCE LEFT OPEN ARE TAKEN HERE, as the record's `## Log` already recorded
--   * `stock_valuation` is **maintained incrementally, not rebuild-only** (`:294-298` asked). Every writer
--     that can change it — the movement trigger for an inbound or a reversal, the allocation writer for a
--     consumption — refreshes it in the same transaction, so the value a report reads is never a number
--     between two rebuilds.
--   * the `I2`/`I6` verification **runs in the suite CI already runs**, not on a nightly task: this
--     repository schedules no nightly job, so "nightly" would be a claim with no runner behind it. The
--     comparison is `verify_stock_projections()` / `unallocated_stock_movements()` — the same two functions
--     an operator calls — plus the integration test that fails loudly.
--
-- WHY ONE EXPRESSION AND NOT TWO (the part a reviewer should read first)
--   The obvious fast path adds a delta per row and the obvious replay adds the same deltas in order, and
--   then the two must agree in the last decimal. They are instead the **same expression**: the value of what
--   is on hand is a sum over the ledger and the allocations,
--       Σ (inbound quantity × its unit cost) − Σ (allocation quantity × its frozen unit cost)
--       − Σ (the quantity a receipt reversal took back × the reversed row's cost),
--   where an allocation stops counting the moment its outbound row has a reversal — the same set
--   `stock_supply_remaining` subtracts from the pool, so "a reversal restores value, not only quantity"
--   (decision 4) is arithmetic instead of a branch somebody has to remember to write twice.
--   `replay_stock_valuation` is that expression, `refresh_stock_valuation` writes its result into the
--   projection, `replay_stock_ledger` hands the same rows to the rebuild and to the check, and the trigger
--   calls `refresh_stock_valuation`.
--   The fast path and the reference are therefore one definition, and `I2`'s valuation half cannot drift by
--   construction rather than by luck. This is *stronger* than the ADR's sentence that "`AVERAGE` is the only
--   policy where a replay can differ from an incremental run in the last decimal (`:205-208`)": with one
--   expression there is no last decimal to differ in. The rest of that paragraph is implemented as written —
--   rounding happens at document boundaries, never per allocation: an allocation freezes its own
--   `unit_cost`, and every remainder stays in `total_value`, which is the projection.
--   The cost is stated rather than hidden: refreshing the value re-reads the key's own ledger history (one
--   index scan over `(company_store_id, product_variant_id)`), so a movement costs what that variant's
--   history in that store costs to sum. It buys the property decision 2 exists for — the projection cannot
--   disagree with the replay that rebuilt it.
--
-- THE MOVING AVERAGE IN FORCE, AND WHY IT IS A QUOTIENT AND NOT THE STORED COLUMN
--   Under `AVERAGE` the allocation freezes "the moving average in force at that instant" (`:203-204`), and the
--   instant is between the outbound movement and its allocations. At that moment `stock_valuation.quantity`
--   has already followed the position down while `total_value` has not moved yet — the closed form has no
--   term for an outbound until its allocations exist — so the stored `avg_unit_cost` is a transient quotient
--   of the two: `300 / 15` instead of `300 / 20`. The writer therefore computes
--   `ROUND(total_value / (quantity + this movement's quantity), 4)`, which is the average over the quantity
--   that was on hand before the movement, and that is the number the replay would have produced one statement
--   earlier. Reading the stored column instead would apply the wrong cost — measured, not supposed: the
--   AVERAGE case of the suite failed with `20.0000` against `15` until the quotient replaced the read.
--   No reader ever sees the transient value: the movement and its allocations land in one transaction, and a
--   caller that writes a movement without allocating it is what `I6` reports.
--
-- WHY THE BALANCE AND THE POSITION *ARE* INCREMENTAL, AND WHY THAT IS STILL EXACT
--   Their arithmetic is a sum of `NUMERIC(19,4)` quantities, which is associative and loses nothing:
--   `+q` and `−q` in any order give the replay's number, digit for digit. So they take the O(1) delta in the
--   trigger — the ADR's "fast path" — and `replay_stock_ledger` recomputes them with plain aggregates for
--   the rebuild and the check. A projection whose arithmetic cannot round has nothing to drift.
--
-- WHAT THE DIRECTION IS, AND WHY `SALE_REVERSAL` ADDS
--   `stock_movement_sign` is the one place a movement type becomes a direction, and every consumer
--   (trigger, replay, checks) calls it. Four types add — `RECEIPT`, `ADJUSTMENT_IN`, `TRANSFER_IN`,
--   `SALE_REVERSAL` — and four subtract — `SALE`, `ADJUSTMENT_OUT`, `TRANSFER_OUT`, `RECEIPT_REVERSAL`.
--   A reversal takes the contrary direction of the row it reverses, and that is the point of naming it:
--   decision 1 (`:48-50`) says a reversal is a new row that names the row it reverses, and decision 4 says
--   it "restores value, not only quantity" — a `SALE_REVERSAL` that subtracted would take the goods out a
--   second time. What "reversal types subtract" means is the reversal of an **inbound** row
--   (`RECEIPT_REVERSAL`), which is the one that takes quantity back out.
--
-- WHY A REVERSAL MUST BE AN EXACT INVERSE (`validate_stock_reversal`), a rule neither the ADR nor the
-- record states, and why it is a refusal instead of a proration
--   Decision 3's whole claim is that a reversal "frees exactly the rows the original consumed, by
--   construction". The freeing is a **read** rule, not a write: `stock_allocation` is append-only (`V60`'s
--   third decision, and `I6` is the reason), so a reversal cannot delete or negate the original's rows —
--   instead an allocation stops counting as consumption when its outbound row has a reversal, and
--   `stock_supply_remaining` is the single query that says so. That is exact only if the reversal moves the
--   same quantity as the row it reverses; a partial reversal would free a fraction, and the fraction is
--   precisely the "remainder re-derived from a reference id" arithmetic decision 3 exists to remove. So a
--   partial reversal is refused rather than prorated, and a partial correction is a new movement
--   (`ADJUSTMENT_IN`/`OUT`, which say what they are). Two more refusals come with it: a reversal stays in
--   the store and variant it reverses, because both the freed rows and the value are accounted per
--   (company, store, variant) and a reversal elsewhere would subtract value from a store that never held
--   it; and a reversal of a reversal is refused, because it would record a value term the replay cannot
--   attribute. All three are `BEFORE INSERT` and raise before the row exists.
--
-- I8 — WHAT THIS TREE ACTUALLY ALLOWS, MEASURED, AND THE CONSERVATIVE RULE THAT FOLLOWS
--   Invariant I8 (`:187`) says "balance can never be negative in a store that has **no setting allowing
--   it**". The setting does not exist: `V11__store_inventory_settings.sql:19-26` creates
--   `store_inventory_settings` with `company_store_id`, `receiving_location_id`, `sales_location_id`,
--   `version`, `created_at`, `updated_at` — two locations, a version and two timestamps. There is no
--   allow-negative column anywhere in `db/migration/**` (grep: no `allow_negative`, no `allowNegative`), and
--   `InventoryService.applySaleDeduction` refuses the same way today by comparing the store's stock with
--   `BigDecimal.ZERO`. So the conservative rule is: **the position never goes below zero**, for every
--   store, and it is refused by the projection trigger — the one place every writer passes, including a
--   caller that writes a movement and never allocates it. The refusal reads `stock_position ... FOR UPDATE`,
--   which is also the serialization point of a `(store, variant)` key: without the row lock two concurrent
--   deductions both read the old balance, both pass the guard, and the second commits a negative number.
--   The grain is the **store**, which is the grain I8 names and the number a sale checks; a location may
--   hold a deficit while the store covers it, exactly as today's `InventoryService` allows.
--
-- ROW LEVEL SECURITY: WHAT IT MEANS FOR THIS FILE, AND WHY NOTHING HERE IS `SECURITY DEFINER`
--   The five tables are armed with `ENABLE` + `FORCE` by `V60` (`apply_tenant_isolation`, five calls at
--   `V60__stock_ledger_schema.sql:307-311`), and these functions are `SECURITY INVOKER` on purpose. The
--   consequence is the trap of this slice, so it is stated rather than discovered: **a trigger that writes a
--   projection row runs as the calling role and meets the policy**, so a caller that inserts a movement must
--   already carry the tenant scope (`TenantFixtures.inTenant` in the suite; `TenantScope`'s transaction
--   boundary in production). Nothing here writes a row across tenants, so nothing here needs a `NO FORCE`
--   window; a backfill that does —
--   T4's Path A replay — needs the window `V58__variant_listing.sql:122-123` establishes
--   (`ALTER TABLE … NO FORCE ROW LEVEL SECURITY`), and `rebuild_stock_projections` is the function it will
--   call. `SECURITY DEFINER` would dodge the policy instead of meeting it and is refused: the self-check at
--   the end of this file fails if any of these functions ever gains `prosecdef`.
--   For the same reason there is **no view** for the supply pool: a view runs as its owner unless it is
--   declared `security_invoker`, so a view over `stock_allocation` and `stock_movement` would be a readable
--   hole in an armed tree the day someone forgets the flag. `stock_supply_remaining` is a function, which
--   is invoker by default.
--   Function `EXECUTE` is granted to `PUBLIC` by PostgreSQL, so `lifecontrol_app` can already call all of
--   this; the self-check asserts that instead of granting it a second time, so a later
--   `REVOKE ALL ON ALL FUNCTIONS` is caught here rather than at a runtime permission error.
--
-- TWO INDEXES, AND WHY THEY ARE IN THIS FILE RATHER THAN IN `V60`
--   `V60`'s header left the index question to this slice on purpose ("T3 is where the access pattern
--   becomes measurable"). It is measurable now, and both indexes exist for one query each:
--   `idx_movement_store_variant` for the pool and the key refresh — every writer filters the ledger by
--   `(company_store_id, product_variant_id)` and the writer orders it by `occurred_at`, so the index carries
--   the three columns the queries name; `idx_allocation_in_movement` because `stock_supply_remaining` asks
--   "what has been consumed from *this* inbound row", and `uq_allocation_pair` is keyed `(out_movement_id,
--   in_movement_id)` — its prefix answers the opposite question, so without the second index every supply
--   row of a key scans the whole allocation table.
--
-- WHAT THIS FILE DELIBERATELY DOES NOT DO
--   * no row in any ledger table and no backfill — T4 owns the replay of today's balances;
--   * no reader moves, no entity, no DTO, no mapping: `ddl-auto=validate` is untouched because this file
--     adds no column and no table;
--   * no `INSERT` path for a movement (that is T5's, and the manual per-store edit becomes one), so nothing
--     here decides where a `source_type` or an `actor` comes from;
--   * no retention, no compaction, no cost of a **transfer** — decision 4 says a transfer carries "the cost
--     of the allocations it consumed", which is T5's caller to pass as `unit_cost` on the `TRANSFER_IN`;
--   * no `SECURITY DEFINER`, no `NO FORCE` window, and no `search_path` pinning beyond what the SQL
--     functions resolve at creation;
--   * no partial reversal, no reversal chain, no reversal across a store (the three refusals above).
--
-- Forward-only. Undoing it by hand means dropping the two triggers
-- (`trg_stock_movement_reversal_shape`, `trg_stock_movement_projection`) and their functions, dropping the
-- two indexes, dropping `allocate_stock_movement`, `rebuild_stock_projections`, `verify_stock_projections`,
-- `unallocated_stock_movements`, `replay_stock_ledger`, `replay_stock_valuation`,
-- `refresh_stock_valuation`, `stock_supply_remaining` and `stock_movement_sign`, and — because the
-- projections are derived — rebuilding them afterwards is impossible once this file is gone, so the three
-- tables keep whatever the last writer projected. No ledger row is touched by the undo, which is the point:
-- the facts are `V60`'s and they survive.
-- ============================================

-- ─── 1. the direction, in one place ───────────────────────────────────────────────────────────────
-- One function, four types each way, and every consumer calls it: the trigger, the replay, the two checks
-- and the allocation writer. A second `CASE` anywhere is a second place for a movement type to change
-- direction without the others noticing. The vocabulary is closed by `ck_movement_type` (`V60`), so the
-- `CASE` is total; the trigger still refuses a `NULL` sign explicitly rather than projecting `NULL`.
CREATE OR REPLACE FUNCTION stock_movement_sign(p_movement_type varchar) RETURNS integer AS $$
    SELECT CASE
               WHEN p_movement_type IN ('RECEIPT', 'ADJUSTMENT_IN', 'TRANSFER_IN', 'SALE_REVERSAL') THEN 1
               WHEN p_movement_type IN ('SALE', 'ADJUSTMENT_OUT', 'TRANSFER_OUT', 'RECEIPT_REVERSAL') THEN -1
           END;
$$ LANGUAGE sql IMMUTABLE;

COMMENT ON FUNCTION stock_movement_sign(varchar) IS
    'ADR-0001 decisions 1 and 4: +1 for the four types that add quantity, -1 for the four that remove it. A '
    'reversal takes the contrary direction of the row it reverses, which is why SALE_REVERSAL adds. Every '
    'consumer of a direction calls this function; none of them re-states the mapping.';

-- ─── 2. what one inbound row still holds, and the indexes that make it cheap ───────────────────────
-- The pool is a read rule because `stock_allocation` is append-only: consumed quantity is subtracted, and a
-- reversal is subtracted by *not* counting the allocations of a row that has one. That is decision 3's
-- "frees exactly the rows the original consumed, by construction" — no writer ever frees anything.
CREATE OR REPLACE FUNCTION stock_supply_remaining(p_in_movement_id uuid) RETURNS numeric(19,4) AS $$
    SELECT i.quantity
           - COALESCE((SELECT SUM(a.quantity)
                         FROM stock_allocation a
                        WHERE a.in_movement_id = i.id
                          AND NOT EXISTS (SELECT 1
                                            FROM stock_movement r
                                           WHERE r.reverses_movement_id = a.out_movement_id)), 0)
           - COALESCE((SELECT SUM(r.quantity)
                         FROM stock_movement r
                        WHERE r.reverses_movement_id = i.id), 0)
      FROM stock_movement i
     WHERE i.id = p_in_movement_id;
$$ LANGUAGE sql STABLE;

COMMENT ON FUNCTION stock_supply_remaining(uuid) IS
    'ADR-0001 decision 3: the quantity of one inbound row that is still available to consume — its quantity '
    'minus what non-reversed outbound rows allocated from it, minus what a reversal took back out of it. '
    'Defined here once, because the allocation writer asks it twice (the total, and the oldest-first walk).';

-- The two indexes: each answers one of the two queries above, and both were written in `V60` as "T3 is
-- where the access pattern becomes measurable" rather than guessed.
CREATE INDEX idx_movement_store_variant
    ON stock_movement (company_store_id, product_variant_id, occurred_at);

CREATE INDEX idx_allocation_in_movement
    ON stock_allocation (in_movement_id);

-- ─── 3. the value of what is on hand, as one expression ───────────────────────────────────────────
-- The three terms, and nothing else:
--   + every inbound row's quantity at its own cost (its reversal is not an inbound row: it names one);
--   − the allocations of every outbound row of the key that has **no** reversal: a reversed outbound is not
--     a consumption any more, which is how "a reversal restores value" is arithmetic rather than a branch;
--   − what a receipt reversal took back, at the cost of the row it reverses, prorated to its own quantity
--     (equal to it by the reversal-shape rule, and written as a product so the expression is total).
-- `quantity` is the sum of the signed movements, which is the position's own number: a projection that
-- stated a different quantity from the position it belongs to would describe stock nobody can sell.
CREATE OR REPLACE FUNCTION replay_stock_valuation(
    p_company_id uuid,
    p_company_store_id uuid,
    p_product_variant_id uuid)
RETURNS TABLE (
    company_id uuid,
    company_store_id uuid,
    product_variant_id uuid,
    quantity numeric(19,4),
    currency char(3),
    total_value numeric(19,4),
    avg_unit_cost numeric(19,4))
AS $$
BEGIN
    RETURN QUERY
    WITH scoped AS (
        SELECT m.id,
               m.company_id,
               m.company_store_id,
               m.product_variant_id,
               m.quantity,
               m.unit_cost,
               m.reverses_movement_id,
               stock_movement_sign(m.movement_type) AS sign
          FROM stock_movement m
         WHERE m.company_id = p_company_id
           AND m.company_store_id = p_company_store_id
           AND m.product_variant_id = p_product_variant_id),
    terms AS (
        -- the quantity each movement moved, and the value only an inbound row adds
        SELECT s.company_id,
               s.company_store_id,
               s.product_variant_id,
               s.sign * s.quantity AS quantity,
               CASE
                   WHEN s.reverses_movement_id IS NULL AND s.sign = 1 THEN s.quantity * s.unit_cost
                   ELSE 0
               END AS value
          FROM scoped s
        UNION ALL
        -- what the outbound rows consumed. An outbound row that has been reversed is not a consumption any
        -- more: its allocations keep the record of what it took (`stock_allocation` is append-only), but the
        -- quantity and the value are back, which is why `stock_supply_remaining` subtracts exactly the same
        -- set. The supply rows' own terms above are untouched by a reversal, so a reversed outbound
        -- contributes nothing here rather than its amount back.
        SELECT o.company_id,
               o.company_store_id,
               o.product_variant_id,
               0::numeric,
               -(a.quantity * a.unit_cost)
          FROM stock_allocation a
          JOIN stock_movement o ON o.id = a.out_movement_id
         WHERE o.company_id = p_company_id
           AND o.company_store_id = p_company_store_id
           AND o.product_variant_id = p_product_variant_id
           AND NOT EXISTS (SELECT 1
                             FROM stock_movement r
                            WHERE r.reverses_movement_id = o.id)
        UNION ALL
        -- what a reversal of an inbound row took back out, at that row's cost
        SELECT rev.company_id,
               rev.company_store_id,
               rev.product_variant_id,
               0::numeric,
               -(rev.quantity * sup.unit_cost)
          FROM scoped rev
          JOIN stock_movement sup ON sup.id = rev.reverses_movement_id
         WHERE sup.reverses_movement_id IS NULL
           AND stock_movement_sign(sup.movement_type) = 1)
    SELECT t.company_id,
           t.company_store_id,
           t.product_variant_id,
           ROUND(SUM(t.quantity), 4),
           c.currency,
           ROUND(SUM(t.value), 4),
           CASE WHEN SUM(t.quantity) > 0 THEN ROUND(SUM(t.value) / SUM(t.quantity), 4) ELSE NULL END
      FROM terms t
      JOIN companies c ON c.id = p_company_id
     GROUP BY t.company_id, t.company_store_id, t.product_variant_id, c.currency;
END;
$$ LANGUAGE plpgsql STABLE;

COMMENT ON FUNCTION replay_stock_valuation(uuid, uuid, uuid) IS
    'ADR-0001 decision 4 and `## Costing and valuation`: the value of what is on hand for one (company, '
    'store, variant), replayed from the ledger and the recorded allocations. The one expression the trigger, '
    'the rebuild and the check all use, so the fast path and the reference cannot disagree. currency is the '
    'company''s operating currency (companies.currency, V57), which ADR-0002 decision 2 makes the only one a '
    'document can be in.';

-- ─── 4. the projection row, written from that expression ──────────────────────────────────────────
-- Called by the movement trigger and by the allocation writer, and by an operator who wants one key
-- recomputed. One statement decides which columns the projection holds; a second caller writing them
-- itself would be the second definition this file exists to avoid.
CREATE OR REPLACE FUNCTION refresh_stock_valuation(
    p_company_id uuid,
    p_company_store_id uuid,
    p_product_variant_id uuid) RETURNS void AS $$
DECLARE
    v_row record;
BEGIN
    SELECT * INTO v_row
      FROM replay_stock_valuation(p_company_id, p_company_store_id, p_product_variant_id) r;

    IF NOT FOUND THEN
        -- No movement left for this key: the projection has nothing to describe, and a row would be a
        -- number with no ledger behind it. Only reachable after the ledger rows themselves are removed.
        DELETE FROM stock_valuation v
         WHERE v.company_store_id = p_company_store_id
           AND v.product_variant_id = p_product_variant_id;
        RETURN;
    END IF;

    INSERT INTO stock_valuation AS v (company_id, company_store_id, product_variant_id, quantity, currency,
                                      total_value, avg_unit_cost)
    VALUES (v_row.company_id, v_row.company_store_id, v_row.product_variant_id, v_row.quantity, v_row.currency,
            v_row.total_value, v_row.avg_unit_cost)
    ON CONFLICT (company_store_id, product_variant_id) DO UPDATE
       SET company_id = EXCLUDED.company_id,
           quantity = EXCLUDED.quantity,
           currency = EXCLUDED.currency,
           total_value = EXCLUDED.total_value,
           avg_unit_cost = EXCLUDED.avg_unit_cost;
END;
$$ LANGUAGE plpgsql;

-- ─── 5. the projection trigger: the fast path ─────────────────────────────────────────────────────
-- AFTER INSERT and not BEFORE: the ledger is append-only (`V60`), so there is no update or delete for a
-- projection to follow, and the row this projects must already be the stored fact. The balance is keyed by
-- location and the position by store, both taken from `NEW` — the same company, the same variant, one
-- number per grain. Decision 5 (`:62-65`) is why this is an upsert and not an update: receiving into a store
-- that never stocked the variant is a normal operation, and it creates the projection row.
CREATE OR REPLACE FUNCTION project_stock_movement() RETURNS trigger AS $$
DECLARE
    v_sign integer := stock_movement_sign(NEW.movement_type);
    v_position numeric(19,4);
BEGIN
    IF v_sign IS NULL THEN
        RAISE EXCEPTION
            'stock_movement_sign does not know the movement type %, so this row cannot be projected. ck_movement_type is the closed vocabulary (ADR-0001 decision 1).',
            NEW.movement_type;
    END IF;

    -- I8, refused before anything is projected. The row lock is the point as much as the check: two
    -- concurrent deductions of the same key would both read the old balance without it.
    IF v_sign < 0 THEN
        SELECT p.quantity
          INTO v_position
          FROM stock_position p
         WHERE p.company_store_id = NEW.company_store_id
           AND p.product_variant_id = NEW.product_variant_id
           FOR UPDATE;

        IF COALESCE(v_position, 0) < NEW.quantity THEN
            RAISE EXCEPTION
                'stock would go negative: variant % in store % has % on hand and this % moves % out. Refused before the projection is written — ADR-0001 invariant I8, and no store setting in this tree allows a negative balance (store_inventory_settings, V11, has no such column), so the conservative rule is zero for every store.',
                NEW.product_variant_id, NEW.company_store_id, COALESCE(v_position, 0), NEW.movement_type,
                NEW.quantity;
        END IF;
    END IF;

    INSERT INTO stock_balance AS b (company_id, store_location_id, product_variant_id, quantity, updated_at)
    VALUES (NEW.company_id, NEW.store_location_id, NEW.product_variant_id, v_sign * NEW.quantity, now())
    ON CONFLICT (store_location_id, product_variant_id) DO UPDATE
       SET quantity = b.quantity + EXCLUDED.quantity,
           updated_at = now();

    INSERT INTO stock_position AS p (company_id, company_store_id, product_variant_id, quantity, updated_at)
    VALUES (NEW.company_id, NEW.company_store_id, NEW.product_variant_id, v_sign * NEW.quantity, now())
    ON CONFLICT (company_store_id, product_variant_id) DO UPDATE
       SET quantity = p.quantity + EXCLUDED.quantity,
           updated_at = now();

    PERFORM refresh_stock_valuation(NEW.company_id, NEW.company_store_id, NEW.product_variant_id);

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_stock_movement_projection
    AFTER INSERT ON stock_movement
    FOR EACH ROW
    EXECUTE FUNCTION project_stock_movement();

-- ─── 6. the reversal's shape: three refusals, before the row exists ───────────────────────────────
-- `ck_movement_reversal` (`V60`) already ties "is a reversal" to "names a row". These are the three facts a
-- reversal has to satisfy for the arithmetic above to be exact, and each of them is refused rather than
-- repaired: a partial reversal, a reversal in another store, and a chain of reversals.
CREATE OR REPLACE FUNCTION validate_stock_reversal() RETURNS trigger AS $$
DECLARE
    v_reversed stock_movement;
BEGIN
    IF NEW.reverses_movement_id IS NULL THEN
        RETURN NEW;
    END IF;

    SELECT * INTO v_reversed
      FROM stock_movement m
     WHERE m.id = NEW.reverses_movement_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'stock_movement % names % as the row it reverses, and no such movement exists',
            NEW.id, NEW.reverses_movement_id;
    END IF;

    IF v_reversed.company_id <> NEW.company_id
       OR v_reversed.company_store_id <> NEW.company_store_id
       OR v_reversed.product_variant_id <> NEW.product_variant_id THEN
        RAISE EXCEPTION
            'a reversal stays in the store and variant it reverses: movement % is variant % in store % and this reversal is variant % in store %. Both the freed rows and the value are accounted per (company, store, variant), so a reversal elsewhere would subtract from a store that never held it (ADR-0001 decision 3).',
            v_reversed.id, v_reversed.product_variant_id, v_reversed.company_store_id, NEW.product_variant_id,
            NEW.company_store_id;
    END IF;

    IF v_reversed.reverses_movement_id IS NOT NULL THEN
        RAISE EXCEPTION
            'a reversal of a reversal is refused: % already reverses %, and a chain would record a value the replay cannot attribute (ADR-0001 decision 1)',
            v_reversed.id, v_reversed.reverses_movement_id;
    END IF;

    IF v_reversed.quantity <> NEW.quantity THEN
        RAISE EXCEPTION
            'a reversal moves exactly the quantity of the row it reverses: % moves % and this reversal moves %. A partial correction is a new movement, not a reversal — a partial reversal would free a fraction of the rows the original consumed, which is the remainder arithmetic ADR-0001 decision 3 removes (decision 3).',
            v_reversed.id, v_reversed.quantity, NEW.quantity;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_stock_movement_reversal_shape
    BEFORE INSERT ON stock_movement
    FOR EACH ROW
    EXECUTE FUNCTION validate_stock_reversal();

-- ─── 7. the allocation writer: what each outbound quantity consumed, and at what cost ────────────
-- Given an outbound movement, consume the inbound rows of its key oldest-first and record one
-- `stock_allocation` row per consumed row (decision 3). Under `FIFO` the cost applied is the consumed row's
-- own; under `AVERAGE` it is the moving average in force, read from the projection the trigger keeps current
-- (decision 4 and `## Costing and valuation`). The rows consumed are the same in both policies — quantity is
-- quantity — and only the frozen cost differs.
--
-- Idempotent, twice over: a second call for a row that already has allocations returns 0 without writing
-- anything, because `uq_allocation_pair` plus append-only makes that set final (the correction path is to
-- reverse the outbound movement, which is decision 3's whole point); and the inserts carry
-- `ON CONFLICT DO NOTHING`, so a concurrent second call cannot double-book a pair. The serialization point
-- is the position row the trigger already locked for this key.
CREATE OR REPLACE FUNCTION allocate_stock_movement(p_out_movement_id uuid) RETURNS integer AS $$
DECLARE
    v_out stock_movement;
    v_position numeric(19,4);
    v_available numeric(19,4);
    v_average numeric(19,4);
    v_take numeric(19,4);
    v_consumed numeric(19,4) := 0;
    v_written integer := 0;
    v_supply record;
BEGIN
    SELECT * INTO v_out
      FROM stock_movement m
     WHERE m.id = p_out_movement_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'allocate_stock_movement: no movement % — a consumption names a fact that exists '
            '(ADR-0001 decision 3)', p_out_movement_id;
    END IF;

    IF stock_movement_sign(v_out.movement_type) <> -1 THEN
        RAISE EXCEPTION
            'allocate_stock_movement: % is a % and adds quantity, so it consumes nothing. Only an outbound movement is allocated (ADR-0001 decision 3).',
            p_out_movement_id, v_out.movement_type;
    END IF;

    IF v_out.reverses_movement_id IS NOT NULL THEN
        RAISE EXCEPTION
            'allocate_stock_movement: % is a reversal of %, and a reversal consumes nothing — it names the row it reverses, and the rows it frees are the ones that row consumed (ADR-0001 decision 3).',
            p_out_movement_id, v_out.reverses_movement_id;
    END IF;

    IF EXISTS (SELECT 1 FROM stock_allocation a WHERE a.out_movement_id = p_out_movement_id) THEN
        RETURN 0;
    END IF;

    -- The same row the projection trigger locked, in the same statement order: one serialization point per
    -- (store, variant), so two allocations of the same key cannot consume the same quantity.
    SELECT p.quantity
      INTO v_position
      FROM stock_position p
     WHERE p.company_store_id = v_out.company_store_id
       AND p.product_variant_id = v_out.product_variant_id
       FOR UPDATE;

    SELECT COALESCE(SUM(stock_supply_remaining(s.id)), 0)
      INTO v_available
      FROM stock_movement s
     WHERE s.company_id = v_out.company_id
       AND s.company_store_id = v_out.company_store_id
       AND s.product_variant_id = v_out.product_variant_id
       AND s.reverses_movement_id IS NULL
       AND stock_movement_sign(s.movement_type) = 1
       AND s.unit_cost IS NOT NULL;

    -- The refusal comes before anything is written, which is what invariant I8 asks of a deduction and what
    -- I6 asks of this writer: an outbound row whose allocations cannot sum to its quantity must not exist,
    -- and the balance guard in the trigger is a different fact (it holds even for a caller that never
    -- allocates).
    IF v_available < v_out.quantity THEN
        RAISE EXCEPTION
            'allocate_stock_movement: % needs % of variant % in store % and only % is unallocated. The inbound rows this outbound claims to consume do not cover it, so its allocations would be incomplete (ADR-0001 invariant I6), and no setting in this tree allows a store to go negative (invariant I8).',
            p_out_movement_id, v_out.quantity, v_out.product_variant_id, v_out.company_store_id, v_available;
    END IF;

    -- The moving average IN FORCE is the value on hand over the quantity the key held **before** this
    -- movement: the closed form below has no term for an outbound row until its allocations exist, so
    -- `total_value` is still the pre-consumption value, while `quantity` already followed the position. The
    -- quotient of the two is therefore the average at the instant of consumption, which is what decision 4
    -- freezes into each allocation.
    IF (SELECT c.costing_method FROM companies c WHERE c.id = v_out.company_id) = 'AVERAGE' THEN
        SELECT ROUND(v.total_value / NULLIF(v.quantity + v_out.quantity, 0), 4)
          INTO v_average
          FROM stock_valuation v
         WHERE v.company_store_id = v_out.company_store_id
           AND v.product_variant_id = v_out.product_variant_id;

        IF v_average IS NULL THEN
            RAISE EXCEPTION
                'allocate_stock_movement: company % costs by AVERAGE and the valuation of variant % in store % has no moving average in force, so there is no cost to apply (ADR-0001 `## Costing and valuation`)',
                v_out.company_id, v_out.product_variant_id, v_out.company_store_id;
        END IF;
    END IF;

    FOR v_supply IN
        SELECT s.id,
               s.unit_cost,
               stock_supply_remaining(s.id) AS remaining
          FROM stock_movement s
         WHERE s.company_id = v_out.company_id
           AND s.company_store_id = v_out.company_store_id
           AND s.product_variant_id = v_out.product_variant_id
           AND s.reverses_movement_id IS NULL
           AND stock_movement_sign(s.movement_type) = 1
           AND s.unit_cost IS NOT NULL
         ORDER BY s.occurred_at, s.id
    LOOP
        EXIT WHEN v_consumed >= v_out.quantity;
        CONTINUE WHEN v_supply.remaining <= 0;

        v_take := LEAST(v_supply.remaining, v_out.quantity - v_consumed);

        INSERT INTO stock_allocation (company_id, out_movement_id, in_movement_id, quantity, unit_cost)
        VALUES (v_out.company_id, p_out_movement_id, v_supply.id, v_take, COALESCE(v_average, v_supply.unit_cost))
        ON CONFLICT (out_movement_id, in_movement_id) DO NOTHING;

        v_written := v_written + 1;
        v_consumed := v_consumed + v_take;
    END LOOP;

    -- One refresh for the whole document, after every allocation of it is written: the ADR's "rounding
    -- happens at document boundaries, never per allocation", stated as one call instead of one per row.
    PERFORM refresh_stock_valuation(v_out.company_id, v_out.company_store_id, v_out.product_variant_id);

    RETURN v_written;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION allocate_stock_movement(uuid) IS
    'ADR-0001 decision 3: records which inbound rows one outbound movement consumed, one stock_allocation '
    'row each, oldest-first, at the cost the company''s costing_method applies (FIFO: the row''s own; '
    'AVERAGE: the moving average in force). Refuses a non-outbound row, a reversal, and a quantity the '
    'unallocated inbound rows cannot cover; idempotent against uq_allocation_pair. Returns the number of '
    'allocation rows written.';

-- ─── 8. the full replay: the projections, rebuilt from the ledger ─────────────────────────────────
-- One row per projection from each branch, tagged so `rebuild_stock_projections` can route it and
-- `verify_stock_projections` can compare it. The balance and the position are sums of the signed
-- quantities (exact); the value comes from the one expression in section 3.
CREATE OR REPLACE FUNCTION replay_stock_ledger(
    p_company_id uuid,
    p_company_store_id uuid DEFAULT NULL,
    p_product_variant_id uuid DEFAULT NULL)
RETURNS TABLE (
    projection text,
    company_id uuid,
    company_store_id uuid,
    store_location_id uuid,
    product_variant_id uuid,
    quantity numeric(19,4),
    currency char(3),
    total_value numeric(19,4),
    avg_unit_cost numeric(19,4))
AS $$
BEGIN
    RETURN QUERY
    SELECT 'stock_balance'::text,
           m.company_id,
           m.company_store_id,
           m.store_location_id,
           m.product_variant_id,
           ROUND(SUM(stock_movement_sign(m.movement_type) * m.quantity), 4),
           NULL::char(3),
           NULL::numeric(19,4),
           NULL::numeric(19,4)
      FROM stock_movement m
     WHERE m.company_id = p_company_id
       AND (p_company_store_id IS NULL OR m.company_store_id = p_company_store_id)
       AND (p_product_variant_id IS NULL OR m.product_variant_id = p_product_variant_id)
     GROUP BY m.company_id, m.company_store_id, m.store_location_id, m.product_variant_id;

    RETURN QUERY
    SELECT 'stock_position'::text,
           m.company_id,
           m.company_store_id,
           NULL::uuid,
           m.product_variant_id,
           ROUND(SUM(stock_movement_sign(m.movement_type) * m.quantity), 4),
           NULL::char(3),
           NULL::numeric(19,4),
           NULL::numeric(19,4)
      FROM stock_movement m
     WHERE m.company_id = p_company_id
       AND (p_company_store_id IS NULL OR m.company_store_id = p_company_store_id)
       AND (p_product_variant_id IS NULL OR m.product_variant_id = p_product_variant_id)
     GROUP BY m.company_id, m.company_store_id, m.product_variant_id;

    RETURN QUERY
    SELECT 'stock_valuation'::text,
           v.company_id,
           v.company_store_id,
           NULL::uuid,
           v.product_variant_id,
           v.quantity,
           v.currency,
           v.total_value,
           v.avg_unit_cost
      FROM (SELECT DISTINCT m.company_id, m.company_store_id, m.product_variant_id
              FROM stock_movement m
             WHERE m.company_id = p_company_id
               AND (p_company_store_id IS NULL OR m.company_store_id = p_company_store_id)
               AND (p_product_variant_id IS NULL OR m.product_variant_id = p_product_variant_id)) k
      CROSS JOIN LATERAL replay_stock_valuation(k.company_id, k.company_store_id, k.product_variant_id) v;
END;
$$ LANGUAGE plpgsql STABLE;

COMMENT ON FUNCTION replay_stock_ledger(uuid, uuid, uuid) IS
    'ADR-0001 decision 2: every projection row of a scope, recomputed from stock_movement and '
    'stock_allocation. Scoped to one company, and narrowed to one store and/or one variant when the caller '
    'says so. The reference the rebuild writes and the check compares against — one definition, two uses.';

-- ─── 9. the rebuild: what makes the projections disposable ───────────────────────────────────────
-- Delete the scope, then write the replay. Callable for one (company, store, variant) — the operator's
-- narrow repair — or for a whole company, which is what T4's backfill and a scheduled job would call.
-- Deleting first is what makes a stale row disappear: an insert-only repair could never remove a projection
-- row whose movements no longer exist.
CREATE OR REPLACE FUNCTION rebuild_stock_projections(
    p_company_id uuid,
    p_company_store_id uuid DEFAULT NULL,
    p_product_variant_id uuid DEFAULT NULL)
RETURNS bigint AS $$
DECLARE
    v_row record;
    v_written bigint := 0;
BEGIN
    DELETE FROM stock_balance b
     WHERE b.company_id = p_company_id
       AND (p_product_variant_id IS NULL OR b.product_variant_id = p_product_variant_id)
       AND (p_company_store_id IS NULL
            OR EXISTS (SELECT 1
                         FROM store_locations l
                        WHERE l.id = b.store_location_id
                          AND l.company_store_id = p_company_store_id));

    DELETE FROM stock_position p
     WHERE p.company_id = p_company_id
       AND (p_company_store_id IS NULL OR p.company_store_id = p_company_store_id)
       AND (p_product_variant_id IS NULL OR p.product_variant_id = p_product_variant_id);

    DELETE FROM stock_valuation v
     WHERE v.company_id = p_company_id
       AND (p_company_store_id IS NULL OR v.company_store_id = p_company_store_id)
       AND (p_product_variant_id IS NULL OR v.product_variant_id = p_product_variant_id);

    FOR v_row IN
        SELECT * FROM replay_stock_ledger(p_company_id, p_company_store_id, p_product_variant_id)
    LOOP
        IF v_row.projection = 'stock_balance' THEN
            INSERT INTO stock_balance (company_id, store_location_id, product_variant_id, quantity, updated_at)
            VALUES (v_row.company_id, v_row.store_location_id, v_row.product_variant_id, v_row.quantity, now());
        ELSIF v_row.projection = 'stock_position' THEN
            INSERT INTO stock_position (company_id, company_store_id, product_variant_id, quantity, updated_at)
            VALUES (v_row.company_id, v_row.company_store_id, v_row.product_variant_id, v_row.quantity, now());
        ELSE
            INSERT INTO stock_valuation (company_id, company_store_id, product_variant_id, quantity, currency,
                                         total_value, avg_unit_cost)
            VALUES (v_row.company_id, v_row.company_store_id, v_row.product_variant_id, v_row.quantity,
                    v_row.currency, v_row.total_value, v_row.avg_unit_cost);
        END IF;
        v_written := v_written + 1;
    END LOOP;

    RETURN v_written;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION rebuild_stock_projections(uuid, uuid, uuid) IS
    'ADR-0001 decision 2: deletes the scope''s three projections and writes them again from '
    'replay_stock_ledger. Returns the number of projection rows written. This is what makes them disposable '
    '— nothing authors them. It runs as the calling role, so it meets the tenant policies: a caller writing '
    'across tenants needs the NO FORCE window V58:122-123 establishes.';

-- ─── 10. the two checks: I2 and I6, the same query for the suite and for a person ────────────────
-- One row per projection row that disagrees with the replay, carrying both numbers so an operator sees what
-- is stored beside what should be. Empty is the healthy answer; `rebuild_stock_projections` is the repair.
CREATE OR REPLACE FUNCTION verify_stock_projections(
    p_company_id uuid,
    p_company_store_id uuid DEFAULT NULL,
    p_product_variant_id uuid DEFAULT NULL)
RETURNS TABLE (
    projection text,
    company_id uuid,
    company_store_id uuid,
    store_location_id uuid,
    product_variant_id uuid,
    projected_quantity numeric(19,4),
    replayed_quantity numeric(19,4),
    projected_total_value numeric(19,4),
    replayed_total_value numeric(19,4),
    projected_avg_unit_cost numeric(19,4),
    replayed_avg_unit_cost numeric(19,4),
    projected_currency char(3),
    replayed_currency char(3))
AS $$
BEGIN
    RETURN QUERY
    WITH r AS (
        SELECT *
          FROM replay_stock_ledger(p_company_id, p_company_store_id, p_product_variant_id) replay_row
         WHERE replay_row.projection = 'stock_balance'
    ), b AS (
        SELECT pb.*
          FROM stock_balance pb
         WHERE pb.company_id = p_company_id
           AND (p_product_variant_id IS NULL OR pb.product_variant_id = p_product_variant_id)
           AND (p_company_store_id IS NULL
                OR EXISTS (SELECT 1
                             FROM store_locations l
                            WHERE l.id = pb.store_location_id
                              AND l.company_store_id = p_company_store_id))
    )
    SELECT 'stock_balance'::text,
           COALESCE(b.company_id, r.company_id),
           COALESCE(l.company_store_id, r.company_store_id),
           COALESCE(b.store_location_id, r.store_location_id),
           COALESCE(b.product_variant_id, r.product_variant_id),
           b.quantity,
           r.quantity,
           NULL::numeric(19,4),
           NULL::numeric(19,4),
           NULL::numeric(19,4),
           NULL::numeric(19,4),
           NULL::char(3),
           NULL::char(3)
      FROM r
      FULL JOIN b
             ON b.store_location_id = r.store_location_id
            AND b.product_variant_id = r.product_variant_id
      LEFT JOIN store_locations l ON l.id = COALESCE(b.store_location_id, r.store_location_id)
     WHERE b.store_location_id IS NULL
        OR r.store_location_id IS NULL
        OR b.quantity IS DISTINCT FROM r.quantity;

    RETURN QUERY
    WITH r AS (
        SELECT *
          FROM replay_stock_ledger(p_company_id, p_company_store_id, p_product_variant_id) replay_row
         WHERE replay_row.projection = 'stock_position'
    ), p AS (
        SELECT pp.*
          FROM stock_position pp
         WHERE pp.company_id = p_company_id
           AND (p_company_store_id IS NULL OR pp.company_store_id = p_company_store_id)
           AND (p_product_variant_id IS NULL OR pp.product_variant_id = p_product_variant_id)
    )
    SELECT 'stock_position'::text,
           COALESCE(p.company_id, r.company_id),
           COALESCE(p.company_store_id, r.company_store_id),
           NULL::uuid,
           COALESCE(p.product_variant_id, r.product_variant_id),
           p.quantity,
           r.quantity,
           NULL::numeric(19,4),
           NULL::numeric(19,4),
           NULL::numeric(19,4),
           NULL::numeric(19,4),
           NULL::char(3),
           NULL::char(3)
      FROM r
      FULL JOIN p
             ON p.company_store_id = r.company_store_id
            AND p.product_variant_id = r.product_variant_id
     WHERE p.company_store_id IS NULL
        OR r.company_store_id IS NULL
        OR p.quantity IS DISTINCT FROM r.quantity;

    RETURN QUERY
    WITH r AS (
        SELECT *
          FROM replay_stock_ledger(p_company_id, p_company_store_id, p_product_variant_id) replay_row
         WHERE replay_row.projection = 'stock_valuation'
    ), v AS (
        SELECT pv.*
          FROM stock_valuation pv
         WHERE pv.company_id = p_company_id
           AND (p_company_store_id IS NULL OR pv.company_store_id = p_company_store_id)
           AND (p_product_variant_id IS NULL OR pv.product_variant_id = p_product_variant_id)
    )
    SELECT 'stock_valuation'::text,
           COALESCE(v.company_id, r.company_id),
           COALESCE(v.company_store_id, r.company_store_id),
           NULL::uuid,
           COALESCE(v.product_variant_id, r.product_variant_id),
           v.quantity,
           r.quantity,
           v.total_value,
           r.total_value,
           v.avg_unit_cost,
           r.avg_unit_cost,
           v.currency,
           r.currency
      FROM r
      FULL JOIN v
             ON v.company_store_id = r.company_store_id
            AND v.product_variant_id = r.product_variant_id
     WHERE v.company_store_id IS NULL
        OR r.company_store_id IS NULL
        OR v.quantity IS DISTINCT FROM r.quantity
        OR v.total_value IS DISTINCT FROM r.total_value
        OR v.avg_unit_cost IS DISTINCT FROM r.avg_unit_cost
        OR v.currency IS DISTINCT FROM r.currency;
END;
$$ LANGUAGE plpgsql STABLE;

COMMENT ON FUNCTION verify_stock_projections(uuid, uuid, uuid) IS
    'ADR-0001 invariant I2 (:181) and decision 2 (`:51-55`): the projection rows that disagree with a full '
    'replay of the ledger, with both numbers, for stock_balance, stock_position and stock_valuation. Empty '
    'means the projections are what the ledger says they are. The suite asserts it is empty, a person runs '
    'it against a database, and both call rebuild_stock_projections to repair.';

-- I6's own query: an outbound row whose recorded consumption does not sum to its quantity. Separate from I2
-- on purpose — it is a different question (did a writer run completely?), it needs no replay, and mixing it
-- into the row shape above would make one function answer two things.
CREATE OR REPLACE FUNCTION unallocated_stock_movements(
    p_company_id uuid,
    p_company_store_id uuid DEFAULT NULL,
    p_product_variant_id uuid DEFAULT NULL)
RETURNS TABLE (
    out_movement_id uuid,
    company_id uuid,
    company_store_id uuid,
    product_variant_id uuid,
    quantity numeric(19,4),
    allocated numeric(19,4))
AS $$
BEGIN
    RETURN QUERY
    SELECT m.id,
           m.company_id,
           m.company_store_id,
           m.product_variant_id,
           m.quantity,
           COALESCE(SUM(a.quantity), 0)::numeric(19,4)
      FROM stock_movement m
      LEFT JOIN stock_allocation a ON a.out_movement_id = m.id
     WHERE m.company_id = p_company_id
       AND (p_company_store_id IS NULL OR m.company_store_id = p_company_store_id)
       AND (p_product_variant_id IS NULL OR m.product_variant_id = p_product_variant_id)
       AND m.reverses_movement_id IS NULL
       AND stock_movement_sign(m.movement_type) = -1
     GROUP BY m.id, m.company_id, m.company_store_id, m.product_variant_id, m.quantity
    HAVING COALESCE(SUM(a.quantity), 0) <> m.quantity;
END;
$$ LANGUAGE plpgsql STABLE;

COMMENT ON FUNCTION unallocated_stock_movements(uuid, uuid, uuid) IS
    'ADR-0001 invariant I6 (:185): every outbound row and what its allocations sum to, for the rows where '
    'the two differ. A row here means an allocation writer did not run or ran incompletely — silence is '
    'indistinguishable from a correct no-op unless this is asked.';

-- ─── 11. the self-check this file ends with ───────────────────────────────────────────────────────
-- Four refusals, because there are four ways this file can report success and have installed nothing:
--   * a function that does not exist under the name the Java side and the suite call;
--   * a function with `SECURITY DEFINER`, which would run outside ADR-0005's policies — the one thing this
--     slice refuses by name;
--   * a role that cannot execute them, which is a permission error at the first write instead of here;
--   * a trigger that is missing or disabled, which is a projection that silently stops moving while the
--     ledger keeps recording.
DO $$
DECLARE
    v_engine text[] := ARRAY['stock_movement_sign', 'stock_supply_remaining', 'replay_stock_valuation',
                             'refresh_stock_valuation', 'project_stock_movement', 'validate_stock_reversal',
                             'allocate_stock_movement', 'replay_stock_ledger', 'rebuild_stock_projections',
                             'verify_stock_projections', 'unallocated_stock_movements'];
    v_callable text[] := ARRAY['allocate_stock_movement', 'rebuild_stock_projections',
                               'verify_stock_projections', 'unallocated_stock_movements'];
    v_triggers text[] := ARRAY['trg_stock_movement_projection', 'trg_stock_movement_reversal_shape'];
    v_missing text;
    v_definer text;
    v_denied text;
    v_untriggered text;
BEGIN
    SELECT string_agg(t.name, ', ' ORDER BY t.name)
      INTO v_missing
      FROM unnest(v_engine) AS t(name)
     WHERE NOT EXISTS (SELECT 1
                         FROM pg_proc p
                         JOIN pg_namespace n ON n.oid = p.pronamespace
                        WHERE p.proname = t.name
                          AND n.nspname = current_schema());

    IF v_missing IS NOT NULL THEN
        RAISE EXCEPTION
            'V61 did not create these ledger functions in schema %: %. The trigger, the writer, the rebuild and the checks are what T5 and the suite call by name.',
            current_schema(), v_missing;
    END IF;

    SELECT string_agg(t.name, ', ' ORDER BY t.name)
      INTO v_definer
      FROM unnest(v_engine) AS t(name)
      JOIN pg_proc p ON p.proname = t.name
      JOIN pg_namespace n ON n.oid = p.pronamespace AND n.nspname = current_schema()
     WHERE p.prosecdef;

    IF v_definer IS NOT NULL THEN
        RAISE EXCEPTION
            'V61 left these ledger functions SECURITY DEFINER: %. They run as the calling role on purpose, so ADR-0005''s policies apply to the row they write; a definer function would bypass the very policy V60 armed.',
            v_definer;
    END IF;

    SELECT string_agg(t.name, ', ' ORDER BY t.name)
      INTO v_denied
      FROM unnest(v_callable) AS t(name)
     WHERE NOT has_function_privilege('lifecontrol_app', t.name::regproc, 'EXECUTE');

    IF v_denied IS NOT NULL THEN
        RAISE EXCEPTION
            'lifecontrol_app cannot execute these ledger functions: %. PostgreSQL grants EXECUTE to PUBLIC by default, so this means something revoked it — and the application would then fail at its first movement with a permission error instead of here.',
            v_denied;
    END IF;

    SELECT string_agg(t.name, ', ' ORDER BY t.name)
      INTO v_untriggered
      FROM unnest(v_triggers) AS t(name)
     WHERE NOT EXISTS (SELECT 1
                         FROM pg_trigger tg
                         JOIN pg_class c ON c.oid = tg.tgrelid
                        WHERE tg.tgname = t.name
                          AND c.relname = 'stock_movement'
                          AND tg.tgenabled = 'O');

    IF v_untriggered IS NOT NULL THEN
        RAISE EXCEPTION
            'V61 left these triggers on stock_movement missing or disabled: %. Without them the ledger records facts and no projection follows, which is the silent divergence decision 2 exists to remove.',
            v_untriggered;
    END IF;
END $$;
