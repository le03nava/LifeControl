-- ============================================
-- V60 — ADR-0001: the stock ledger's schema — the single fact, its allocations, its projections, its cost
-- ============================================
-- ADR-0001 is accepted (`docs/adr/0001-inventory-ledger-single-source.md`, 2026-11-02). This file is the
-- SCHEMA HALF of its migration step 2 (`:236-238`) and nothing more: five tables, one column on
-- `companies`, the append-only rule enforced twice, and the five tables armed on `tenant_table` in the
-- same file that creates them. **No service, no entity, no reader and no backfill** — the projection
-- trigger, the rebuild function and the allocation writer are T3, the replay is T4, the readers are T5 and
-- the drops are T6 of `odd/tasks/inventory-ledger.md`. That record's `## Tasks` row for T2 is the scope of
-- this file, and its S2, S5 and S8 are what it implements.
--
-- WHAT THE ADR ASKS FOR, measured in the tree:
--   * decision 1 (`:48-50`) — one fact, one row, rows never updated and never deleted. The table it
--     replaces is measured, not assumed: `V10__inventory.sql:30` `inventory_movements` carries
--     `reference_type`/`reference_id` (`:37-38`) and no identity for the row it reverses, which is why
--     `SalesOrderService` recomputes a remainder from the ledger instead of naming a row. `stock_movement`
--     carries `reverses_movement_id` for exactly that.
--   * decision 2 (`:51-55`) — the two projections and, since ADR correction 2 (`:283-287`), the valuation
--     that states the currency its two money columns are in.
--   * decision 3 (`:56-58`) — `stock_allocation`, one row per (outbound, inbound) pair, which is what makes
--     a reversal free exactly what the original consumed.
--   * decision 4 (`:59-61`) — `unit_cost` and `currency` on every inbound row, the cost of the consumed
--     units on the allocation, and `stock_valuation` for the value on hand.
--   * decision 5 (`:62-65`) — nothing in this file requires a store-scoped row to exist before a receipt:
--     the projection is what gets upserted, and that is T3's trigger.
--
-- THE THREE CORRECTIONS ARE ALREADY FOLDED IN, and none of them is re-decided here:
--   1. the migration number is taken when it lands, not from the ADR's plan (`:278-282`), which is why this
--      file is `V60` and not one of the `V52`–`V55` the plan once named.
--   2. `stock_movement.currency` (`:88`) and `stock_valuation.currency` (`:164`) reference the `currency`
--      catalog `V57__company_currency.sql:60` created, and the valuation's is `NOT NULL`. The unit is
--      stated by the column instead of held as a free string.
--   3. D1.a runs before the ledger tables, not beside them (`:288-292`): `fk_movement_location` below
--      references `store_locations (company_store_id, id)`, which `V59__store_location_tree_store_scope.sql:137`
--      is the statement that created. Without `V59` this file does not compile, which is the whole point of
--      the correction.
--
-- WHY THE COMPOSITE KEYS, AND WHAT EACH ONE MAKES UNWRITABLE
--   `fk_movement_store` and `fk_movement_location` are not two independent foreign keys: the location key is
--   `(company_store_id, store_location_id)` against `store_locations (company_store_id, id)`, so a movement
--   in store A that names a location of store B is refused by the database (invariant I3, `:182`). The
--   targets exist and are named, measured rather than assumed: `uq_store_locations_company_store_id`
--   (`V59:137`), `uq_company_stores_company_id` (`V48__store_hierarchy_store_company_scope.sql:44`) and
--   `uq_product_variants_company_id` (`V40__product_catalog_company_contract.sql:106`). That is the same
--   mechanism `V40`–`V51` used one level up, and it is why this table needs no service-side check.
--
-- WHY APPEND-ONLY IS ENFORCED TWICE (`:173-174`), and why the second half is not redundant
--   * The trigger is what holds for **every** writer that can reach the statement: the application, an
--     operator's script, a future migration, the suite. A trigger and not a rule written in a service,
--     because a service is one caller among many.
--   * The grant set is what holds for the **application role** and it is a different fact: `lifecontrol_app`
--     never gets `UPDATE` or `DELETE`, so the ordinary write path cannot even attempt the statement that
--     the trigger would refuse. Two mechanisms, two audiences, and a defect in one is still caught by the
--     other.
--   * The revoke is needed because `V52`'s default privileges would otherwise grant it:
--     `V52__tenant_isolation_machinery.sql:196-197` records
--     `GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES` for every table this role creates from then on, and
--     `V52:187` granted the same on every table that already existed. A new table therefore arrives with
--     `UPDATE` and `DELETE` already in hand, and `REVOKE` below is the only statement that takes them away.
--   * `stock_allocation` is covered by both halves for the same reason, and the reason is invariant I6
--     (`:185`): that invariant is `SUM(allocation.quantity)` against the outbound row's `quantity`, and a
--     deleted allocation is indistinguishable from an allocation writer that never ran — the same silence
--     the trigger on `stock_movement` exists to remove.
--
-- WHY THE FIVE TABLES ARE ARMED IN THIS FILE (`:236-238`), AND WHY NOT `currency`
--   `apply_tenant_isolation` (`V52__tenant_isolation_machinery.sql:100`) is called five times, once per
--   table that carries `company_id`, the way `V58__variant_listing.sql` armed the listing. It is the last
--   statement group of this file, because a table that carries `company_id` and is not on the list is
--   refused by `unprotected_tenant_tables()` (`V52:209`) at the next boot — so a file that created the
--   tables and armed them "later" would be a tree that does not start. **All five are armed and none is
--   left out**: every one of the five carries `company_id`, so leaving one unarmed is not a smaller
--   decision, it is a database that refuses to start.
--   `currency` carries no policy and no `tenant_table` row, which is the same treatment `V57`'s own header
--   states for it: it is a platform catalog with no `company_id`, and a tenant predicate over a column the
--   table does not have is not a rule, it is empty.
--
-- WHY THIS FILE NEEDS NO ROW-LEVEL SECURITY WINDOW, measured rather than argued
--   The five new tables are created empty and no statement here writes a row of them: no backfill, no seed,
--   no copy. The one statement that touches an existing armed table is
--   `ALTER TABLE companies ADD COLUMN costing_method … NOT NULL DEFAULT 'FIFO'`, which is a catalogue-only
--   change since PostgreSQL 11 — the value is stored as the column's `missing` value and no row is read or
--   written — and a `CHECK` that `ALTER TABLE … ADD CONSTRAINT` validates reads the heap directly, not
--   through the rewriter, so `FORCE` does not filter it. `companies` is armed by
--   `V53__tenant_isolation_policies.sql:108`, which is why this is stated instead of left to be discovered:
--   a `NO FORCE` window exists for files that must WRITE rows across tenants, and this is not one. Both
--   statements were also OBSERVED over a `companies` table that already has rows:
--   `ProductVariantListingMigrationIntegrationTest` seeds a company at `V57` and migrates to head as
--   `lifecontrol_owner`, which is the credential and the `FORCE`d state delivery runs this file under. If a
--   later slice adds a backfill here, it needs a window, and `V58__variant_listing.sql:122-123` is the shape.
--
-- WHAT THIS FILE DOES NOT DO, all of it on purpose:
--   * no trigger on `stock_movement` maintaining `stock_balance`/`stock_position`, no rebuild function, no
--     allocation writer, no `I2`/`I6` verification query — T3;
--   * no row in any of the five tables, and therefore no Path A replay — T4;
--   * no reader moves, no entity, no DTO: `ddl-auto=validate` is unchanged by this file because nothing
--     maps these tables yet, which is also why the migration adds no Java;
--   * no index beyond the two partial indexes the ADR's block declares. The tenant predicate
--     `company_id = <scope>` therefore has no leading-column index on `stock_movement`: the same per-table
--     write-cost decision `V58` left out of its schema slice rather than inventing it, and T3 is where the
--     access pattern that would justify one becomes measurable;
--   * `stock_valuation` is created and **not** maintained: whether the trigger or only the rebuild writes it
--     is one of the two questions the ADR's acceptance leaves open (`:294-298`), and it is T3's to take.
--
-- Forward-only. Undoing it by hand means dropping the five tables (their rows are the ledger — a reversal of
-- this file loses facts, which is why there is no `DROP` here), dropping the two privileges back with
-- `GRANT UPDATE, DELETE`, dropping `refuse_ledger_mutation()` and its two triggers, dropping
-- `companies.costing_method` with its `CHECK`, and removing the five `tenant_table` rows and their policies,
-- which is `DROP POLICY p_tenant_<table>` followed by `DELETE FROM tenant_table WHERE table_name = …`.
-- ============================================

-- ─── 1. the single fact ─────────────────────────────────────────────────────────────────────────
-- Column order, types and constraint names are the ADR's `## Schema` block verbatim (`:79-116`), so a
-- reviewer can read this statement beside it. `currency` is nullable here and `NOT NULL` in
-- `stock_valuation` because the ADR says exactly that: only an inbound row must state its money
-- (`ck_movement_inbound_cost`), and the valuation always has an amount to denominate.
CREATE TABLE stock_movement (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id           UUID NOT NULL,
    company_store_id     UUID NOT NULL,
    store_location_id    UUID NOT NULL,
    product_variant_id   UUID NOT NULL,
    movement_type        VARCHAR(24) NOT NULL,
    quantity             NUMERIC(19,4) NOT NULL,
    unit_cost            NUMERIC(19,4),
    currency             CHAR(3) REFERENCES currency (code),
    occurred_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor                VARCHAR(255),
    source_type          VARCHAR(32) NOT NULL,
    source_id            UUID NOT NULL,
    reverses_movement_id UUID,
    reason               TEXT,

    CONSTRAINT fk_movement_store
        FOREIGN KEY (company_id, company_store_id)  REFERENCES company_stores (company_id, id),
    CONSTRAINT fk_movement_location
        FOREIGN KEY (company_store_id, store_location_id) REFERENCES store_locations (company_store_id, id),
    CONSTRAINT fk_movement_variant
        FOREIGN KEY (company_id, product_variant_id) REFERENCES product_variants (company_id, id),
    CONSTRAINT fk_movement_reverses
        FOREIGN KEY (reverses_movement_id) REFERENCES stock_movement (id),

    CONSTRAINT ck_movement_quantity CHECK (quantity > 0),
    CONSTRAINT ck_movement_type CHECK (movement_type IN (
        'RECEIPT', 'RECEIPT_REVERSAL', 'SALE', 'SALE_REVERSAL',
        'ADJUSTMENT_IN', 'ADJUSTMENT_OUT', 'TRANSFER_IN', 'TRANSFER_OUT')),
    -- cost travels inward: a row that adds quantity must say what it cost
    CONSTRAINT ck_movement_inbound_cost CHECK (
        movement_type NOT IN ('RECEIPT', 'ADJUSTMENT_IN', 'TRANSFER_IN')
        OR (unit_cost IS NOT NULL AND currency IS NOT NULL)),
    -- a reversal names what it reverses; nothing else does
    CONSTRAINT ck_movement_reversal CHECK (
        (movement_type LIKE '%REVERSAL') = (reverses_movement_id IS NOT NULL))
);

COMMENT ON TABLE stock_movement IS
    'ADR-0001 decision 1: the only authority on quantity. Append-only — a correction is a new row, and a '
    'reversal is a new row that names the row it reverses (reverses_movement_id). Never updated, never '
    'deleted: refused by trg_stock_movement_append_only and by the grant set lifecontrol_app holds.';

COMMENT ON COLUMN stock_movement.reverses_movement_id IS
    'ADR-0001 decision 1: the row this one reverses, and uq_movement_one_reversal makes it at most once. '
    'ck_movement_reversal ties its presence to the movement_type, so a reversal that names nothing and a '
    'SALE that names something are both refused.';

COMMENT ON COLUMN stock_movement.currency IS
    'ADR-0001 decision 4 and correction 2: the unit the row''s unit_cost is denominated in, as a row of the '
    'platform currency catalog (V57). Required by ck_movement_inbound_cost on every row that adds quantity, '
    'and allowed but normally absent on an outbound one, whose cost lives in stock_allocation.unit_cost.';

-- one movement per source document line and direction: a retried request cannot double-book
CREATE UNIQUE INDEX uq_movement_source
    ON stock_movement (source_type, source_id, movement_type)
    WHERE reverses_movement_id IS NULL;
-- at most one reversal per movement
CREATE UNIQUE INDEX uq_movement_one_reversal
    ON stock_movement (reverses_movement_id)
    WHERE reverses_movement_id IS NOT NULL;

-- ─── 2. consumption, recorded ───────────────────────────────────────────────────────────────────
-- The pair key is the invariant: one allocation per (outbound, inbound) pair, so the consumed quantity can
-- be read per row instead of re-derived from a reference id (`:56-58`).
CREATE TABLE stock_allocation (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id      UUID NOT NULL,
    out_movement_id UUID NOT NULL REFERENCES stock_movement (id),
    in_movement_id  UUID NOT NULL REFERENCES stock_movement (id),
    quantity        NUMERIC(19,4) NOT NULL,
    unit_cost       NUMERIC(19,4) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_allocation_quantity CHECK (quantity > 0),
    CONSTRAINT ck_allocation_distinct CHECK (out_movement_id <> in_movement_id),
    CONSTRAINT uq_allocation_pair UNIQUE (out_movement_id, in_movement_id)
);

COMMENT ON TABLE stock_allocation IS
    'ADR-0001 decision 3: which inbound row each outbound quantity consumed, and the cost applied to those '
    'units (decision 4). Append-only for the same reason as the ledger: invariant I6 sums this table '
    'against the outbound row, and a deleted allocation reads exactly like a writer that never ran.';

-- ─── 3. the projections: derived, disposable, never authored ───────────────────────────────────
-- No foreign keys, exactly as the ADR's block declares them: a projection may be dropped and rebuilt from
-- the ledger without a constraint ever standing between the rebuild and an empty table. Their only
-- guarantee is the replay (invariant I2), which is T3's, not a key here.
CREATE TABLE stock_balance (             -- per location
    company_id         UUID NOT NULL,
    store_location_id  UUID NOT NULL,
    product_variant_id UUID NOT NULL,
    quantity           NUMERIC(19,4) NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (store_location_id, product_variant_id));

CREATE TABLE stock_position (            -- per store: the number the POS sells against
    company_id         UUID NOT NULL,
    company_store_id   UUID NOT NULL,
    product_variant_id UUID NOT NULL,
    quantity           NUMERIC(19,4) NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (company_store_id, product_variant_id));

CREATE TABLE stock_valuation (           -- cost of what is on hand, per store
    company_id         UUID NOT NULL,
    company_store_id   UUID NOT NULL,
    product_variant_id UUID NOT NULL,
    quantity           NUMERIC(19,4) NOT NULL,
    currency           CHAR(3) NOT NULL REFERENCES currency (code),
    total_value        NUMERIC(19,4) NOT NULL,
    avg_unit_cost      NUMERIC(19,4),
    PRIMARY KEY (company_store_id, product_variant_id));

COMMENT ON TABLE stock_balance IS
    'ADR-0001 decision 2: the balance of one variant in one location, derived from the ledger and disposable '
    '— the rebuild replays stock_movement, and nothing authors this table. No key and no constraint on '
    'purpose: a projection that can be rebuilt needs no guarantee of its own.';

COMMENT ON TABLE stock_position IS
    'ADR-0001 decision 2: the per-store number a sale checks and the operator sees. Derived and disposable '
    'like stock_balance, and the reason the per-store aggregate is not dropped: it is read on every sell.';

COMMENT ON TABLE stock_valuation IS
    'ADR-0001 decision 4 and correction 2: the value of what is on hand, per store, in the currency its two '
    'money columns are denominated in. Whether the movement trigger or only the rebuild maintains it is the '
    'question the acceptance left open (:294-298) and T3 decides.';

-- ─── 4. the costing policy of a company ────────────────────────────────────────────────────────
-- The ADR states the column and, as a comment, the closed vocabulary (`:169-170`); the vocabulary becomes a
-- constraint here, because a value the resolver does not know is a policy that silently falls through to
-- whatever the code's default branch is. `DEFAULT 'FIFO'` is the ADR's and it is a migration default: it
-- backfills the companies that exist, and changing it afterwards is legal and not retroactive (`:209`).
ALTER TABLE companies ADD COLUMN costing_method VARCHAR(10) NOT NULL DEFAULT 'FIFO';

ALTER TABLE companies ADD CONSTRAINT ck_companies_costing_method
    CHECK (costing_method IN ('FIFO', 'AVERAGE'));

COMMENT ON COLUMN companies.costing_method IS
    'ADR-0001 decision 4: how consumption is valued for this company — FIFO consumes inbound rows '
    'oldest-first, AVERAGE carries the moving average in force at that instant. Not retroactive: prior '
    'allocations keep the cost they were given, which is what makes a re-issued document reproducible.';

-- ─── 5. append-only, twice: the trigger every writer meets … ───────────────────────────────────
-- One function for both tables: the rule is the same sentence, and a second copy of it is a second place
-- for it to drift. No `RETURN`: RAISE EXCEPTION never returns, and a row trigger that returned here would
-- be the bug this function exists to prevent.
CREATE OR REPLACE FUNCTION refuse_ledger_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is append-only (ADR-0001 decision 1): % is refused. A correction is a new movement, never an edit of a recorded fact',
        TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION refuse_ledger_mutation() IS
    'ADR-0001 decision 1: refuses any UPDATE or DELETE on the ledger tables. The other half of the rule is '
    'the grant set lifecontrol_app holds (INSERT, SELECT only), so the application never reaches this.';

CREATE TRIGGER trg_stock_movement_append_only
    BEFORE UPDATE OR DELETE ON stock_movement
    FOR EACH ROW
    EXECUTE FUNCTION refuse_ledger_mutation();

CREATE TRIGGER trg_stock_allocation_append_only
    BEFORE UPDATE OR DELETE ON stock_allocation
    FOR EACH ROW
    EXECUTE FUNCTION refuse_ledger_mutation();

-- ─── 6. … and the grant set the application role meets first ──────────────────────────────────
-- `REVOKE` and not merely "never grant": `V52__tenant_isolation_machinery.sql:196-197` recorded
-- `GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO lifecontrol_app` as a DEFAULT PRIVILEGE, so every
-- table created after it — including these two — arrives already writable by the application role. The
-- explicit `GRANT SELECT, INSERT` after each `REVOKE` is deliberate rather than implied: it states the
-- complete set this role is meant to hold, so a later `REVOKE ALL` elsewhere cannot leave the table
-- unreadable without this file's intent being visible.
REVOKE ALL ON stock_movement FROM lifecontrol_app;
GRANT SELECT, INSERT ON stock_movement TO lifecontrol_app;

REVOKE ALL ON stock_allocation FROM lifecontrol_app;
GRANT SELECT, INSERT ON stock_allocation TO lifecontrol_app;

-- The three projections keep the ordinary DML grant they inherit: they are written (and, by the rebuild,
-- rewritten) by the application's own paths, so `UPDATE` and `DELETE` are theirs to have. Nothing is
-- revoked from them and nothing is granted: `V52`'s default privileges already did it, and restating them
-- here would be a second place that decides the same fact.

-- ─── 7. the five tables are armed in the migration that creates them ──────────────────────────
-- Last, and one call per table: `apply_tenant_isolation` is the only way a table gets a policy, and the
-- row it writes in `tenant_table` is what `unprotected_tenant_tables()` (`V52:209`) and the startup check
-- read. All five carry `company_id`, so all five are `tenant` kind on that column.
SELECT apply_tenant_isolation('stock_movement');
SELECT apply_tenant_isolation('stock_allocation');
SELECT apply_tenant_isolation('stock_balance');
SELECT apply_tenant_isolation('stock_position');
SELECT apply_tenant_isolation('stock_valuation');

-- ─── 8. the self-check this branch ends every migration with ──────────────────────────────────
-- Three refusals, because there are three ways this file can report success and have decided nothing:
--   * a table that was created and never armed, or armed without `FORCE` — which the startup check would
--     refuse on the next boot, but only then;
--   * a `tenant_table` row that is missing, or that names the wrong kind or key, so the policy filters
--     something other than the tenant column;
--   * a grant set that still lets the application role rewrite the ledger, which is the half of
--     append-only that a trigger cannot cover.
DO $$
DECLARE
    v_ledger  text[] := ARRAY['stock_movement', 'stock_allocation', 'stock_balance',
                              'stock_position', 'stock_valuation'];
    v_unarmed text;
    v_unlisted text;
    v_writable text;
BEGIN
    SELECT string_agg(c.relname, ', ' ORDER BY c.relname)
      INTO v_unarmed
      FROM pg_class c
     WHERE c.relname = ANY (v_ledger)
       AND c.relkind = 'r'
       AND NOT (c.relrowsecurity AND c.relforcerowsecurity);

    IF v_unarmed IS NOT NULL THEN
        RAISE EXCEPTION
            'V60 left these ledger tables without ENABLE + FORCE row level security: %. Either apply_tenant_isolation was not called for them or it did not take, and the startup check would refuse this database on the next boot.',
            v_unarmed;
    END IF;

    SELECT string_agg(t.name, ', ' ORDER BY t.name)
      INTO v_unlisted
      FROM unnest(v_ledger) AS t(name)
     WHERE NOT EXISTS (
               SELECT 1 FROM tenant_table l
                WHERE l.table_name = t.name::regclass
                  AND l.policy_kind = 'tenant'
                  AND l.key_column = 'company_id');

    IF v_unlisted IS NOT NULL THEN
        RAISE EXCEPTION
            'V60 created these ledger tables but tenant_table does not list them as tenant tables keyed on company_id: %. unprotected_tenant_tables() would refuse to boot this database, and a wrong kind or key would filter the wrong column.',
            v_unlisted;
    END IF;

    SELECT string_agg(c.relname, ', ' ORDER BY c.relname)
      INTO v_writable
      FROM pg_class c
     WHERE c.relname IN ('stock_movement', 'stock_allocation')
       AND c.relkind = 'r'
       AND (has_table_privilege('lifecontrol_app', c.oid, 'UPDATE')
            OR has_table_privilege('lifecontrol_app', c.oid, 'DELETE'));

    IF v_writable IS NOT NULL THEN
        RAISE EXCEPTION
            'V60 could not take UPDATE and DELETE away from lifecontrol_app on: %. The trigger is then the only half of the append-only rule that holds, and the application role would meet a refusal at runtime instead of never reaching the statement. Check the REVOKE above against the default privileges V52 recorded.',
            v_writable;
    END IF;
END $$;
