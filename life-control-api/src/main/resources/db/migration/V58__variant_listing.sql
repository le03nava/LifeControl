-- ============================================
-- V58 — ADR-0006 decision 5: the price becomes a listing, one per store, and stock stays where it is
-- ============================================
-- ADR-0006 is accepted (`docs/adr/0006-catalog-identity.md`, 2026-11-01). This file is the SCHEMA HALF of
-- its migration step 4 and nothing more: `variant_listing` exists, the prices that live in
-- `product_variant_store_stock` today are copied into it, and it is armed on `tenant_table` in the same
-- file that creates it. **No reader moves**, no entity is added and nothing is dropped — the read paths
-- are the next slice and ADR-0001 will eventually own the position projection.
--
-- WHAT THE ADR ASKS FOR, measured in the tree:
--   * decision 5 (`docs/adr/0006-catalog-identity.md:57-59`) replaces the price half of
--     `product_variant_store_stock` — `(variant, store)` with `list_price`, `currency`, `tax_rate_id`,
--     `enabled`, and **no stock and no cost**.
--   * `V13__product_variant_store_stock.sql:23-33` is that table: `stock`, `list_price` and `cost_price` in
--     one row, unique per `(product_variant_id, company_store_id)` (`:32`), and
--     `ProductVariantService#upsertStoreStock` writes both prices on the row (`ProductVariantService.java:444`)
--     before delegating the stock to the inventory engine. Two facts with different authorities share one
--     lifecycle, which is why the price screen is also a stock editor.
--   * `currency` is not invented here: `V57__company_currency.sql:82` added `companies.currency` and `:123`
--     made it `NOT NULL`, so the listing's currency is read from the variant's company, which is the only
--     place the product states one.
--
-- TWO DELIBERATE DEPARTURES FROM THE ADR'S `## Schema` BLOCK (`docs/adr/0006-catalog-identity.md:109-123`),
-- and both are named here because a reviewer reading the ADR beside this file will look for them:
--
--   1. **`tax_rate_id` is NOT created.** The block declares `tax_rate_id UUID REFERENCES tax_rate (id)`
--      (`:116`), and `tax_rate` belongs to ADR-0002's T4 (`docs/adr/0002-money-first-class.md:121` declares
--      it; `odd/tasks/money-first-class.md:88` is the task, gated on decision **D-d**, which
--      `docs/adr/0002-money-first-class.md:9` states is still open). No migration in the tree provides the
--      table, and a column cannot reference a table that does not exist, so the column is added later, by an
--      `ALTER`, in the slice that creates `tax_rate`. A listing therefore states its price and its currency
--      today and nothing about tax — the state decision 5 describes minus the half its own dependency does
--      not provide.
--
--   2. **`product_variant_store_stock` keeps `stock` and `cost_price`.** Decision 5 says a listing has no
--      stock and no cost, and step 4 (`:150-152`) says the store row's `stock` becomes one `ADJUSTMENT_IN`
--      movement through ADR-0001 Path A with `cost_price` as its `unit_cost` — and ADR-0001 does not exist
--      in the tree. So the price is **COPIED, NOT MOVED**: every store row that has a non-null `list_price`
--      produces its listing, and the old table stays exactly as it is, still the only place stock and cost
--      live. This is a **transitional two-representation state**, and it is retired in two steps: T4b moves
--      the price reads (the two store-scoped joins, `ProductVariantRepository.java:167-174` and `:197-209`,
--      which today `INNER JOIN` the store row) onto `variant_listing`, and ADR-0001's ledger retires
--      `stock`/`cost_price` from the store row by turning them into movements. Until both land, the price
--      exists twice on purpose, and the copy in this file is the only reason the two can be compared.
--
-- WHY THE ORDER INSIDE THIS FILE IS LOAD-BEARING, and it is four steps, not one:
--   1. `CREATE TABLE` — no rows yet, so nothing can be refused while it is empty.
--   2. the backfill, **before** the table is armed. An armed table (`ENABLE` + `FORCE`) refuses an insert
--      from its owner unless the session states `app.company_id` first, and this backfill writes rows for
--      every tenant at once — exactly the failure the last three migrations on this branch exist to avoid,
--      met here from the writing side instead of the reading one.
--   3. the self-check, while the window is still open: a backfill that read nothing inserts nothing and
--      reports success in silence, which is the defect ADR-0005 correction 3 records. The count below is
--      the copy's own evidence.
--   4. `SELECT apply_tenant_isolation('variant_listing')` **last**, so the table is armed by the migration
--      that creates it. `TenantIsolationStartupCheck` refuses to boot a database where a table with a
--      `company_id` is not on `tenant_table` (`unprotected_tenant_tables()`,
--      `V52__tenant_isolation_machinery.sql:203`), so a file that created the table and armed it "later"
--      would be a tree that does not start.
--
-- WHY THE ROW-LEVEL SECURITY WINDOW, AND ON WHICH TABLES — ADR-0005 correction 3.
--   `V52__tenant_isolation_machinery.sql:152-153` calls `ENABLE` **and `FORCE`** on every table it arms,
--   `V53__tenant_isolation_policies.sql:108` arms `companies` and `:122-123` arms `products` and
--   `product_variants`. `FORCE` is what makes a policy apply to the table **owner**, and the owner is the
--   role Flyway runs as — so the backfill's join would match **zero rows and report success** without a
--   window. `product_variant_store_stock` needs no window: it carries no `company_id`
--   (`V13:23-33`), so `V53` never armed it and its rows are readable by the owner as they are.
--   `product_variants` and `companies` are read by the join and both are armed, so both get the window,
--   it lasts this file's transaction, and it is closed — and re-checked — before the file ends.
--
-- WHAT THE COPY CANNOT DO, and this is a real trap rather than a theoretical one: `product_variant_store_stock`
--   carries no `company_id`, so **nothing in the schema ties its variant and its store to one tenant** — a
--   row pairing company A's variant with company B's store is representable today. The listing's
--   `company_id` comes from the variant, so such a pair is refused by `fk_listing_store` (C6) and this
--   migration aborts naming that constraint instead of writing a cross-tenant listing. That is the correct
--   outcome and it is deliberately not "fixed" by deriving the company from the store: the ADR's rule is
--   that a listing's variant and store are one tenant, so a row where they disagree is data this migration
--   refuses to interpret.
--
-- WHAT THIS FILE DOES NOT DO, all of it on purpose: no reader changes, no Java entity, no drop of
--   `product_variant_store_stock`, no `tax_rate`, no `sale_payment`, and no index beyond the unique key the
--   ADR's block states. That last one is reported rather than hidden: the tenant predicate
--   `company_id = <scope>` has no leading-column index on this table, which is the shape
--   `V53__tenant_isolation_policies.sql:91-103` added for the three tables that lacked one. It is a
--   per-table decision with its own write cost, so it is left out of the schema slice instead of being
--   invented here.
-- ============================================

-- ─── 1. the table, exactly the shape decision 5 states minus `tax_rate_id` ──────────────────────
-- Same column order, types and constraint names as `docs/adr/0006-catalog-identity.md:109-123`, so the
-- `ALTER` that adds `tax_rate_id` later is an addition to this statement and not a rewrite of it.
CREATE TABLE variant_listing (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id         UUID NOT NULL,
    product_variant_id UUID NOT NULL,
    company_store_id   UUID NOT NULL,
    list_price         NUMERIC(19,4) NOT NULL CHECK (list_price >= 0),
    currency           CHAR(3) NOT NULL REFERENCES currency (code),
    enabled            BOOLEAN NOT NULL DEFAULT true,
    CONSTRAINT fk_listing_variant
        FOREIGN KEY (company_id, product_variant_id) REFERENCES product_variants (company_id, id),
    CONSTRAINT fk_listing_store
        FOREIGN KEY (company_id, company_store_id) REFERENCES company_stores (company_id, id),
    CONSTRAINT uq_listing_variant_store UNIQUE (product_variant_id, company_store_id));

COMMENT ON TABLE variant_listing IS
    'ADR-0006 decision 5: a price in a store. No stock and no cost — stock is ADR-0001''s ledger and cost is '
    'the inbound movement''s. "Sellable here" is this table; "there is stock here" is the ledger, and a sale '
    'needs both (decision 6). Since V58 the price still ALSO lives in product_variant_store_stock: the copy '
    'is transitional until the price reads move here and ADR-0001''s ledger retires the store row''s stock.';

COMMENT ON COLUMN variant_listing.list_price IS
    'The sellable price in this store. NUMERIC(19,4) — ADR-0002 decision 1''s scale, wider than the 12,2 the '
    'store row holds, so the copy loses nothing. Negative prices are refused by the CHECK: the write path '
    'already refuses them (ProductVariantStoreStockRequest.java:24).';

COMMENT ON COLUMN variant_listing.currency IS
    'The operating currency of the variant''s company (companies.currency, V57), copied at write time. No '
    'DEFAULT and no derivation from the store: a listing states the money it is priced in.';

-- ─── 2. the window: without it, the backfill reads no variant and no company ─────────────────────
ALTER TABLE product_variants NO FORCE ROW LEVEL SECURITY;
ALTER TABLE companies NO FORCE ROW LEVEL SECURITY;

-- ─── 3. the copy: every priced store row becomes exactly one listing ────────────────────────────
-- `WHERE ss.list_price IS NOT NULL` is the whole rule of step 4 ("for each row that has one"): a store row
-- with no price has no price to copy, and inventing one would price an article the operator never priced.
-- `JOIN companies` cannot drop a row — `product_variants.company_id` is `NOT NULL` and its composite key
-- (`V40:106`) makes it the variant's own company — so the row count is preserved by construction, which is
-- what the self-check in step 4 asserts instead of trusts.
-- One listing per `(variant, store)` follows from the store row's own `UNIQUE(product_variant_id,
-- company_store_id)` (`V13:32`), so `uq_listing_variant_store` cannot be hit by this statement.
INSERT INTO variant_listing (company_id, product_variant_id, company_store_id, list_price, currency)
SELECT v.company_id, ss.product_variant_id, ss.company_store_id, ss.list_price, c.currency
  FROM product_variant_store_stock ss
  JOIN product_variants v ON v.id = ss.product_variant_id
  JOIN companies c ON c.id = v.company_id
 WHERE ss.list_price IS NOT NULL;

-- ─── 4. the self-check, while the window is still open ─────────────────────────────────────────
-- Three refusals, because there are three ways this file can report success and have done nothing:
--   * the window did not take, so the join read no row and the copy wrote nothing;
--   * the copy ran but wrote a different number of rows than the priced store rows — a silent partial copy
--     that no later migration could reconstruct;
--   * the copy is exactly right and the window is still open, which would leave the catalogue readable by
--     its owner without a tenant scope, the state ADR-0005 correction 3 refuses.
DO $$
DECLARE
    v_forced   text;
    v_expected bigint;
    v_actual   bigint;
BEGIN
    SELECT string_agg(c.relname, ', ' ORDER BY c.relname)
      INTO v_forced
      FROM pg_class c
     WHERE c.relname IN ('product_variants', 'companies')
       AND c.relkind = 'r'
       AND c.relforcerowsecurity;

    IF v_forced IS NOT NULL THEN
        RAISE EXCEPTION
            'V58 could not open its NO FORCE window on: %. The copy below would read no variant and no '
            'company and report success, leaving variant_listing empty. See ADR-0005 correction 3, and the '
            'NO FORCE window the catalog-identity migrations V54 and V55 use.', v_forced;
    END IF;

    SELECT count(*) INTO v_expected FROM product_variant_store_stock WHERE list_price IS NOT NULL;
    SELECT count(*) INTO v_actual FROM variant_listing;

    IF v_expected <> v_actual THEN
        RAISE EXCEPTION
            'V58 copied % listings for % store rows with a price (ADR-0006 step 4: "for each row that has '
            'one"). The copy read a different catalogue than the one it wrote, which no later migration can '
            'reconstruct — check the NO FORCE window at the top of this file.', v_actual, v_expected;
    END IF;
END $$;

-- ─── 5. the window closes ──────────────────────────────────────────────────────────────────────
-- Both tables go back under the policies they had. The backfill is the only statement of this file that
-- needed them off, and it has already run.
ALTER TABLE product_variants FORCE ROW LEVEL SECURITY;
ALTER TABLE companies FORCE ROW LEVEL SECURITY;

-- ─── 6. the table is armed in the migration that creates it ─────────────────────────────────────
-- The helper and not a hand-written `CREATE POLICY`: `apply_tenant_isolation`
-- (`V52__tenant_isolation_machinery.sql:100-184`) is the only way a table gets a policy — it enables and
-- forces RLS, writes the single `p_tenant_<name>` predicate, and records the `tenant_table` row the startup
-- check reads. A `CREATE POLICY` here would be a policy the list does not know about.
SELECT apply_tenant_isolation('variant_listing');

-- ─── 7. the FORCE self-check this branch ends every migration with ──────────────────────────────
-- A forgotten close, or a table that was never armed, fails this file instead of the next boot. The
-- `tenant_table` row is checked too: the policy alone is not the contract, the registration is.
DO $$
DECLARE
    v_unarmed text;
    v_listed  boolean;
BEGIN
    SELECT string_agg(c.relname, ', ' ORDER BY c.relname)
      INTO v_unarmed
      FROM pg_class c
     WHERE c.relname IN ('product_variants', 'companies', 'variant_listing')
       AND c.relkind = 'r'
       AND NOT (c.relrowsecurity AND c.relforcerowsecurity);

    IF v_unarmed IS NOT NULL THEN
        RAISE EXCEPTION
            'V58 left these tables without ENABLE + FORCE row level security: %. Either a window was not '
            'closed — the state ADR-0005 correction 3 refuses — or variant_listing was created and never '
            'armed, which the startup check would refuse on the next boot anyway.', v_unarmed;
    END IF;

    SELECT EXISTS (
               SELECT 1 FROM tenant_table t
                WHERE t.table_name = 'variant_listing'::regclass
                  AND t.policy_kind = 'tenant'
                  AND t.key_column = 'company_id')
      INTO v_listed;

    IF NOT v_listed THEN
        RAISE EXCEPTION
            'V58 created variant_listing but tenant_table does not list it as a tenant table keyed on '
            'company_id, so unprotected_tenant_tables() would refuse to boot this database.';
    END IF;
END $$;
