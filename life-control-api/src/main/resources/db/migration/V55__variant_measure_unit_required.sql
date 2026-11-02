-- ============================================
-- V55 — ADR-0006 decision 3: a variant declares the unit its quantities are expressed in
-- ============================================
-- ADR-0006 is accepted (`docs/adr/0006-catalog-identity.md`, 2026-11-01). This file is its migration
-- step 2 and nothing more: the column stops accepting `NULL`, behind the report that refuses to guess.
-- The barcode relaxation, the mode trigger and the listing split are the later slices of
-- `odd/tasks/catalog-identity.md` and are deliberately absent here.
--
-- WHY THE COLUMN STOPS BEING NULLABLE, measured in the tree:
--   * `V29__product_variant_measure_unit.sql:23-24` added `measure_unit_id` as nullable, on the honest
--     argument that every row that existed then had never declared one (`V29:14-18`). The catalog it
--     needs was already there: `measure_units` is seeded by `V3` (`sat_code 'H87'` is "Pieza").
--   * The request mirrored the column: `ProductVariantRequest.measureUnitId` is not `@NotBlank`/
--     `@NotNull`, so a variant could be created with no unit at all, and a quantity of 3 of nothing is
--     not a quantity (ADR-0006 decision 3, invariant C4).
--
-- WHY THE REPORT AND NOT A BACKFILL — this is the whole point of the file. The unit a row's quantities
-- were always expressed in is information the database does not have: `product_variant_store_stock.stock`
-- and `inventory_movements` store a number and no unit. Defaulting every row to 'H87' (Pieza) would
-- invent that fact and silently reinterpret stock that is already stored, which is exactly the
-- alternative ADR-0006 rejects. So the migration runs the ADR's own query as a report over the rows that
-- are still null, and **aborts** naming each one; the operator declares the unit through the variant
-- endpoint — which already allows the first declaration even when the variant holds stock
-- (`ProductVariantService.java:589-595`) — and re-runs the migration. Nothing is guessed, and the
-- refusal is atomic: Flyway runs this file in one transaction, so the refusal leaves the column exactly
-- as nullable as it was.
--
-- WHY THE ROW-LEVEL SECURITY WINDOW, again — ADR-0005 correction 3. `V52__tenant_isolation_machinery.sql:152-153`
-- calls `ENABLE` **and `FORCE`** on every table it arms and `V53__tenant_isolation_policies.sql:122-123` arms
-- `products` and `product_variants`; `FORCE` is what makes a policy apply to the table **owner**, and the
-- owner is the role Flyway runs as. Without the window the report below joins two tables and matches
-- **zero rows**, silently: the migration would then reach `SET NOT NULL` with no list to show, and
-- PostgreSQL's own refusal of that statement names the column and not a single row — leaving the operator
-- with no way to know which variants to fix. That is the trap this header exists to name, and it is the
-- reason the report runs with the policies off and the window is closed again before this file ends.
--
-- The `DO` block at the end is what turns a forgotten close into a failed migration instead of a database
-- whose catalogue is readable by every migration that runs after this one.
-- ============================================

-- ─── 1. the window: without it, the report below sees no row ────────────────────────────────────
ALTER TABLE products NO FORCE ROW LEVEL SECURITY;
ALTER TABLE product_variants NO FORCE ROW LEVEL SECURITY;

-- ─── 2. the report, and the refusal it raises ───────────────────────────────────────────────────
-- One line per row, in the shape the operator can act on: `<sku> / <variant_name> (variant <id>)`. The
-- order is stable (sku, then variant name) so two runs of the same database print the same list, and the
-- concatenation is a single `string_agg` so the message carries **every** row instead of the first one.
DO $$
DECLARE
    v_missing text;
BEGIN
    SELECT string_agg(
               format('%s / %s (variant %s)', p.sku, v.variant_name, v.id),
               ', ' ORDER BY p.sku, v.variant_name)
      INTO v_missing
      FROM product_variants v
      JOIN products p ON p.id = v.product_id
     WHERE v.measure_unit_id IS NULL;

    IF v_missing IS NOT NULL THEN
        RAISE EXCEPTION
            'V55 aborted: these variants declare no measure unit: %. '
            'A quantity without a unit is a meaningless number (ADR-0006 decision 3), so this migration '
            'assigns none and guesses nothing. Declare the unit each of these variants has always been '
            'expressed in — through the variant endpoint, PUT '
            '/api/companies/{companyId}/products/{productId}/variants/{variantId}, which allows the first '
            'declaration even when the variant holds stock — and re-run this migration.',
            v_missing;
    END IF;
END $$;

-- ─── 3. the column, now that every row states its unit ──────────────────────────────────────────
-- No default and no backfill: the rows above either declare a unit or stopped this file at step 2.
ALTER TABLE product_variants ALTER COLUMN measure_unit_id SET NOT NULL;

COMMENT ON COLUMN product_variants.measure_unit_id IS
    'ADR-0006 decision 3: every quantity of the variant is expressed in this unit. Required — a quantity '
    'without a unit is a meaningless number. The first declaration is allowed even when the variant holds '
    'stock; a later change is refused once it does.';

-- ─── 4. the window closes ──────────────────────────────────────────────────────────────────────
-- Both tables go back under the policies they had, and the self-check below is what makes a forgotten
-- close fail this migration instead of arming the next one with an unreadable catalogue.
ALTER TABLE product_variants FORCE ROW LEVEL SECURITY;
ALTER TABLE products FORCE ROW LEVEL SECURITY;

DO $$
DECLARE
    v_unarmed text;
BEGIN
    SELECT string_agg(c.relname, ', ' ORDER BY c.relname)
      INTO v_unarmed
      FROM pg_class c
     WHERE c.relname IN ('products', 'product_variants')
       AND NOT (c.relrowsecurity AND c.relforcerowsecurity);

    IF v_unarmed IS NOT NULL THEN
        RAISE EXCEPTION
            'V55 opened a NO FORCE window and did not close it on: %. Those tables are now readable by '
            'their owner without a tenant scope, which is the state ADR-0005 correction 3 refuses.',
            v_unarmed;
    END IF;
END $$;
