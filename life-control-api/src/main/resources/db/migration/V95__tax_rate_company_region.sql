-- ============================================
-- V95 — a tax rate states the region it applies to, and one day admits one rate per scope
--        (S1, S4, S5 and S7 of odd/tasks/tax-rate-region-axis.md, T1; issue #855)
-- ============================================
-- `V82:61` made `(company_id, tax_code, effective_from)` unique, so a company states one rate per code per
-- day — and with that shape the one pair the country's geography actually produces is not representable: a
-- company selling on the northern frontier charges 8% IVA there and 16% everywhere else, for the **same**
-- code `IVA`. The resolution then succeeds and answers the wrong rate, and no refusal can catch it: the row
-- it should have answered cannot exist, because the key leaves no room for it.
--
-- WHY THE AXIS IS THE STORE'S REGION, AND NOT THE PRODUCT AND NOT A SECOND CATALOG CODE (D1).
-- The frontier designation is geographic: two stores of one region must not be able to disagree about the
-- rate, and a store that moves between zones keeps its region's rate. `products.tax_code` keeps saying
-- **what** is sold, which is an item fact; **how much** is the place's, so the place is where the amount
-- gains a dimension. Both rejected alternatives put a location fact on an item fact: a second catalog code
-- (`IVA_FRONTERA`) would need two products for the same article — the code is exactly what a report filters,
-- so the same article sold in two stores would report as two different goods — and a rate on the product
-- would make every sale of that product charge 8%, including the ones outside the frontier.
--
-- The axis is reachable from what the documents already carry: `company_stores.company_zone_id` →
-- `company_zones.company_region_id` → `company_regions`, and both `sales_orders.company_store_id` and
-- `purchase_orders.company_store_id` exist. No claim, no new document column, no new module edge (S8).
--
-- WHY TWO PARTIAL UNIQUE INDEXES INSTEAD OF ONE COMPOSITE KEY. A single
-- `UNIQUE (company_id, tax_code, company_region_id, effective_from)` would **not** replace the old key: it
-- treats NULLs as distinct, so two company-wide rows (`company_region_id IS NULL`) could share a day, which
-- is exactly the ambiguity `V82:61` exists to prevent — `uq_tax_rate_company_code_from` refused it, and
-- swapping it for that composite key would quietly allow it back. Two partial indexes state the two scopes
-- the column has, one key each:
--   * `uq_tax_rate_scope_from` — `(company_id, tax_code, effective_from) WHERE company_region_id IS NULL`,
--     the old key, said as a partial index so the company-wide scope keeps the guarantee it always had;
--   * `uq_tax_rate_region_scope_from` — `(company_id, tax_code, company_region_id, effective_from) WHERE
--     company_region_id IS NOT NULL`, the new scope, where the region is part of the identity because a
--     region-scoped rate is a different fact from the company-wide one.
-- Both keep `(company_id, tax_code)` as their leading columns, which is the prefix the resolution reads
-- (`V82:74`) — so the two indexes serve the two reads and neither duplicates the other: a region read is
-- answered by the second (`company_id, tax_code, company_region_id` prefix) and a company-wide read by the
-- first, and no read of either scope scans the other's rows.
--
-- WHY THIS FILE WRITES NO ROW, AND WHY THAT MEANS NO WINDOW AND NO DEFERRED DISCHARGE. `V92`, `V93` and
-- `V94` had to open `NO FORCE ROW LEVEL SECURITY` windows because they recomputed stored rows of an armed
-- table, and `V92`/`V93` also had to discharge deferred `CONSTRAINT TRIGGER`s because they **updated** rows
-- (`V94`'s header states the measurement). Here `company_region_id` arrives `NULL` on every existing row,
-- and `NULL` is precisely what those rows mean — company-wide — so there is nothing to backfill and no
-- statement in this file writes a row. An `ADD COLUMN` that rewrites nothing queues nothing, and the only
-- reads below are catalog reads, so no window has to be opened and none is left behind.
--
-- WHAT IT DOES NOT DO, named so it is not discovered in the diff:
--   * **No stored rate and no line is rewritten (S4).** Every row already in `tax_rate` is company-wide and
--     stays the row it is; every `sales_order_items` and `purchase_order_details` line keeps the
--     `tax_code`/`tax_rate`/`tax_amount` it was created with, because this file touches neither table.
--   * **No resolver and no ABM.** That the region wins over the company-wide rate, and that a region with
--     rows but none applicable refuses the line, is `TaxRateService.resolve` (T2). That a rate for another
--     company's region is refused before it is stored is the write path's check (T3, S7) — this FK states
--     that the region exists, which is all a foreign key can say when the owner of a region is derived by
--     `V50`'s trigger and the two companies involved are the same column's business.
--   * **No `tax_code` change.** The code is still the product's fact; this file only gives the amount a place
--     to live.

-- ─── 1. the place a rate applies to: nullable, and NULL means the company-wide rate every row already is ──
-- `NULL` is the right encoding and not an accident: a region column that arrived `NOT NULL` would need a
-- value for every existing row, and the only honest value is "none of them". A sentinel region would be a
-- row the store chain can never resolve, which is a second way to say "company-wide" that a resolver has to
-- know about.
--
-- The reference points at the region the store chain already names (`company_regions.id`); the region's own
-- company is derived by `V50`'s trigger, and that a rate's region belongs to the rate's company is refused
-- by the write path (S7) rather than by the key — the same division `purchase_orders.company_store_id`
-- uses, because a referential check cannot compare two arbitrary columns of the row being written.
ALTER TABLE tax_rate
    ADD COLUMN company_region_id UUID NULL REFERENCES company_regions (id);

COMMENT ON COLUMN tax_rate.company_region_id IS
    'The region this rate applies to, or NULL for the company-wide rate. The region is the store''s, three '
    'hops that already exist (company_stores.company_zone_id -> company_zones.company_region_id), and it is '
    'the axis because the frontier rate of IVA is a geographic fact: the same code, two places, two amounts '
    '(issue #855, D1). A region that states rows for a code is authoritative for it — the resolver prefers '
    'the region row and refuses the line when none applies on the document''s date, instead of charging the '
    'company-wide rate in a place it does not belong to (D2).';

-- ─── 2. the key: one rate per day per scope, stated as two partial indexes so NULLs cannot collide ──────
-- Dropped by name and re-stated, never left beside the new pair: keeping it would refuse the very second
-- row this file exists to allow, and a deploy would look applied while the feature was unusable.
ALTER TABLE tax_rate DROP CONSTRAINT uq_tax_rate_company_code_from;

-- The company-wide scope, unchanged in meaning. Partial rather than absorbing the region into the columns
-- for the reason in the header: PostgreSQL treats NULLs as distinct, so a composite key would let two
-- company-wide rows share a day.
CREATE UNIQUE INDEX uq_tax_rate_scope_from ON tax_rate (company_id, tax_code, effective_from)
    WHERE company_region_id IS NULL;

-- The region scope, where the region is part of the identity: two regions may state the same code from the
-- same day, which is the whole point, and neither may state it twice.
CREATE UNIQUE INDEX uq_tax_rate_region_scope_from
    ON tax_rate (company_id, tax_code, company_region_id, effective_from)
    WHERE company_region_id IS NOT NULL;

-- The third unique key on this table, `uq_tax_rate_company_id` (`V88`), is deliberately untouched: it is the
-- target of `variant_listing.fk_listing_tax_rate`, keys no day, and is what lets a listing point at the row a
-- rate was resolved to. This file changes keys over `effective_from` and nothing else.

-- ─── 3. the self-check: the column, the two keys and the reference are the ones this file declares ─────
-- Catalog reads only, and deliberately so: `tax_rate` is armed with `ENABLE` + `FORCE` row-level security
-- (`V82`, `V53:114`), and Flyway runs as the table's owner, whom the policy also filters
-- (`V54:50-58`, `V64`) — a row count here would answer zero for the wrong reason and report «nothing wrong»
-- over none of the rows. Every fact asserted below lives in `pg_catalog`, where no policy applies.
DO $$
DECLARE
    v_old_key    bigint;
    v_keys       bigint;
    v_wide_ok    boolean;
    v_region_ok  boolean;
    v_fk         bigint;
    v_nullable   boolean;
BEGIN
    -- The old key must be gone: if the DROP did not land, every insert of the second rate this file allows
    -- is refused by a constraint nobody would think to look for after the file «succeeded».
    SELECT count(*) INTO v_old_key
      FROM pg_constraint
     WHERE conrelid = 'tax_rate'::regclass
       AND conname = 'uq_tax_rate_company_code_from';

    IF v_old_key <> 0 THEN
        RAISE EXCEPTION
            'V95 still reads uq_tax_rate_company_code_from on tax_rate. That key allows one rate per company, '
            'code and day, so a frontier region could not state its 8%% beside the company-wide 16%% and issue '
            '#855 would remain unrepresentable whatever the service says.';
    END IF;

    -- The old key must be gone as an index as well, not only as a constraint: a bare index of that name would
    -- still refuse the second row this file exists to allow, and `pg_constraint` would not show it.
    IF to_regclass('uq_tax_rate_company_code_from') IS NOT NULL THEN
        RAISE EXCEPTION
            'V95 still reads a relation named uq_tax_rate_company_code_from. That key allows one rate per '
            'company, code and day, so a frontier region could not state its 8%% beside the company-wide 16%% '
            'and issue #855 would remain unrepresentable.';
    END IF;

    -- Exactly the two keys this file states, counted by the column that makes them rate keys: `effective_from`.
    -- The count deliberately ignores `uq_tax_rate_company_id` (`V88`), which exists as the target of
    -- `variant_listing.fk_listing_tax_rate` and keys no day at all — a bare `indisunique` count would report
    -- three and the check would be about foreign keys instead of about rates.
    SELECT count(*) INTO v_keys
      FROM pg_index i
     WHERE i.indrelid = 'tax_rate'::regclass
       AND i.indisunique
       AND NOT i.indisprimary
       AND strpos(pg_get_indexdef(i.indexrelid), 'effective_from') > 0;

    IF v_keys <> 2 THEN
        RAISE EXCEPTION
            'V95 expects exactly two unique keys on tax_rate over `effective_from` (the company-wide scope and '
            'the region scope) and read %. One means a scope lost its key and two rates can share a day; three '
            'means a key this file does not declare survived the deploy.', v_keys;
    END IF;

    -- Each half must be a partial unique index over the columns it states, and its predicate must be the
    -- scope it names. The predicate is compared through `pg_get_expr` for `IS NULL` / `IS NOT NULL` only, so
    -- the check does not depend on how the server spaces the expression.
    SELECT i.indisunique
           AND i.indpred IS NOT NULL
           AND strpos(pg_get_expr(i.indpred, i.indrelid), 'company_region_id IS NULL') > 0
           AND strpos(pg_get_indexdef(i.indexrelid), '(company_id, tax_code, effective_from)') > 0
      INTO v_wide_ok
      FROM pg_index i
     WHERE i.indexrelid = to_regclass('uq_tax_rate_scope_from');

    IF v_wide_ok IS NULL OR NOT v_wide_ok THEN
        RAISE EXCEPTION
            'V95 left uq_tax_rate_scope_from as something other than the company-wide key it declares: a '
            'unique, partial index on (company_id, tax_code, effective_from) over company_region_id IS NULL. '
            'Without it two company-wide rates can share one day and «the latest rate» depends on insertion '
            'order (V82:61).';
    END IF;

    SELECT i.indisunique
           AND i.indpred IS NOT NULL
           AND strpos(pg_get_expr(i.indpred, i.indrelid), 'company_region_id IS NOT NULL') > 0
           AND strpos(pg_get_indexdef(i.indexrelid), '(company_id, tax_code, company_region_id, effective_from)') > 0
      INTO v_region_ok
      FROM pg_index i
     WHERE i.indexrelid = to_regclass('uq_tax_rate_region_scope_from');

    IF v_region_ok IS NULL OR NOT v_region_ok THEN
        RAISE EXCEPTION
            'V95 left uq_tax_rate_region_scope_from as something other than the region key it declares: a '
            'unique, partial index on (company_id, tax_code, company_region_id, effective_from) over '
            'company_region_id IS NOT NULL. A region that could state the same code twice on one day has two '
            'answers for the same document, and the resolver would pick whichever the planner returned first.';
    END IF;

    -- The column must be nullable, because `NULL` is the company-wide rate: a NOT NULL column would have
    -- needed a value for every row that existed before this file, and none of them belongs to a region.
    SELECT NOT a.attnotnull INTO v_nullable
      FROM pg_attribute a
     WHERE a.attrelid = 'tax_rate'::regclass
       AND a.attname = 'company_region_id'
       AND NOT a.attisdropped;

    IF v_nullable IS NULL OR NOT v_nullable THEN
        RAISE EXCEPTION
            'V95 read tax_rate.company_region_id as NOT NULL or absent. NULL is the company-wide rate — the '
            'only meaning every row that predates this file can have — so a NOT NULL column here retires the '
            'scope that answers every company today.';
    END IF;

    -- And the reference has to point at company_regions, one column wide: a foreign key to another table
    -- would accept an id that no region answers, and the resolver would then filter on a region that does
    -- not exist while reporting a rate nobody could find.
    SELECT count(*) INTO v_fk
      FROM pg_constraint c
     WHERE c.conrelid = 'tax_rate'::regclass
       AND c.contype = 'f'
       AND c.confrelid = 'company_regions'::regclass;

    IF v_fk <> 1 THEN
        RAISE EXCEPTION
            'V95 read % foreign keys from tax_rate to company_regions and expects exactly one. The column '
            'would otherwise accept an id no region answers, which the resolver reads as «this place states '
            'no rate» instead of as a broken row.', v_fk;
    END IF;
END $$;
