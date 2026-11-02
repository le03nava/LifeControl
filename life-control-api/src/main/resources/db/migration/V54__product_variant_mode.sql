-- ============================================
-- V54 — ADR-0006 decision 1: a product states whether it is SIMPLE or MULTI
-- ============================================
-- ADR-0006 is accepted (`docs/adr/0006-catalog-identity.md`, 2026-11-01). This file is its migration
-- step 1 and nothing more: the column, its two legal values, and the derivation for the catalogue that
-- already exists. Nothing writes it yet — the entity, the request and the single creation transaction
-- that will state the mode are the next slices of `odd/tasks/catalog-identity.md`, and landing them here
-- would make one review unit out of three decisions.
--
-- WHY THE COLUMN EXISTS AT ALL, measured in the tree:
--   * `V14__product_variant_definition.sql:72-75` made the variant the global sellable definition and
--     required a barcode and a name for it, so nothing in the schema could say whether an article is a
--     loaf of bread (one sellable thing, no barcode, no decision to make) or a shoe (a family of sizes).
--   * `POST /api/companies/{companyId}/products` writes a product and no variant
--     (`ProductService.java:65-91`), and each variant is a second HTTP call
--     (`ProductVariantService.java:200`). A `SIMPLE` article therefore has to be created as two calls
--     today, and nothing can refuse the second variant of one.
--   * `V40__product_catalog_company_contract.sql:101-126` gave `products` its tenant key, so the mode is
--     a tenant fact with a policy on it, which is why the backfill below cannot assume it can read.
--
-- WHY THE ROW-LEVEL SECURITY WINDOW — ADR-0005 correction 3, hit for the first time in a migration.
-- `V52__tenant_isolation_machinery.sql:154-155` calls `ENABLE` **and `FORCE`** on every table it arms,
-- and `V53__tenant_isolation_policies.sql:108-114` arms `products` and `product_variants`. `FORCE` is
-- what makes a policy apply to the table **owner** as well, and the owner is the role Flyway runs as
-- (`docker-compose.yml:529`) — so a migration that runs after `V53` and does not set `app.company_id`
-- first matches **zero rows**, silently. `NO FORCE` on the two tables is the only door a `NOSUPERUSER`
-- owner has, it lasts for this file's transaction, and it is closed again before the self-check's result
-- is trusted. The `DO` block at the end is what turns a forgotten window into a failed migration instead
-- of a database where every product reads `SIMPLE` and nothing complains.
-- ============================================

-- ─── 1. the column, the two values, and the default that makes the backfill free ────────────────
-- `DEFAULT 'SIMPLE'` is a migration default, not a request default: every product that existed before
-- this file has zero or one variant, and `SIMPLE` forbids nothing that already exists. From the creation
-- transaction on, the mode is stated explicitly by the caller and the default stops being load-bearing.
ALTER TABLE products
    ADD COLUMN variant_mode VARCHAR(6) NOT NULL DEFAULT 'SIMPLE',
    ADD CONSTRAINT ck_products_variant_mode CHECK (variant_mode IN ('SIMPLE', 'MULTI'));

COMMENT ON COLUMN products.variant_mode IS
    'ADR-0006 decision 1: SIMPLE means exactly one variant, written by the backend with the product; '
    'MULTI means the variants are declared and at least one is required. Immutable once a variant holds '
    'stock (ADR migration step 6, with ADR-0001).';

-- ─── 2. the window: without it, every statement below sees no row ───────────────────────────────
ALTER TABLE products NO FORCE ROW LEVEL SECURITY;
ALTER TABLE product_variants NO FORCE ROW LEVEL SECURITY;

-- `MULTI` for every product that already carries more than one variant, `SIMPLE` for the rest. Derived
-- from what the catalogue is: no row is reclassified by hand, nothing is discarded, and no product is
-- invented. `SIMPLE` is the column's default, so only the first half writes.
UPDATE products p
   SET variant_mode = 'MULTI'
 WHERE (SELECT count(*) FROM product_variants v WHERE v.product_id = p.id) > 1;

-- ─── 3. the self-check, while the window is still open ─────────────────────────────────────────
-- The failure this catches is the one nobody would notice: with the window closed — or with `FORCE`
-- added to these tables by a later migration and this file replayed on a database that already had
-- rows — the `UPDATE` above matches nothing AND raises nothing, and the whole tree reads `SIMPLE`
-- while products carry five variants each. A migration that reports success while doing nothing is the
-- defect ADR-0005 correction 3 records, so the derivation is read back and refused if it did not take.
DO $$
DECLARE
    v_wrong text;
BEGIN
    SELECT string_agg(format('%s (%s variants)', p.sku, n.variants), ', ' ORDER BY p.sku)
      INTO v_wrong
      FROM products p
      JOIN (SELECT product_id, count(*) AS variants
              FROM product_variants
             GROUP BY product_id) n ON n.product_id = p.id
     WHERE p.variant_mode = 'SIMPLE'
       AND n.variants > 1;

    IF v_wrong IS NOT NULL THEN
        RAISE EXCEPTION
            'V54 derived products.variant_mode but these products are still SIMPLE with more than one '
            'variant: %. The derivation read nothing, which means the session could not see the rows — see '
            'the NO FORCE window at the top of this file, and ADR-0005 correction 3.',
            v_wrong;
    END IF;
END $$;

-- ─── 4. the window closes ──────────────────────────────────────────────────────────────────────
-- Both tables go back under the policies they had. Nothing in this file runs after this line, so no
-- statement of this migration ever executes outside them on purpose.
ALTER TABLE product_variants FORCE ROW LEVEL SECURITY;
ALTER TABLE products FORCE ROW LEVEL SECURITY;
