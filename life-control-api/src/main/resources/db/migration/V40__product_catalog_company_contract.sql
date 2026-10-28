-- ============================================
-- V40 — Product catalog, step 2 of 2: the company contract
-- ============================================
--
--  !!!  DESTRUCTIVE MIGRATION — DATA DISCARD BY DECISION S2  !!!
--
--  This migration TRUNCATES the product catalog and the operational chain that hangs off it and
--  must NOT be run against any database whose products, variants, orders, receipts or stock are
--  worth keeping. It is safe only because the maintainer authorized discarding rows that cannot be
--  attributed to a company instead of inventing an owner for them (spec S2, 2026-10-27: «no hay una
--  version productiva»). There is no undo, and there is no backfill to reconstruct.
--
-- ============================================
--
-- WHY
-- ---
-- V39 added `products.company_id` and `product_variants.company_id` as NULLABLE columns and stopped
-- there on purpose: a `NOT NULL` column cannot arrive before the write surface knows where the
-- company comes from. It knows now — the API moved to `/api/companies/{companyId}/products`, a
-- product takes the company named by the path, a variant takes its product's, a category of another
-- company is refused, the SKU key is per company and the barcode gate is per company. This
-- migration is what turns those service rules into rules the database enforces, so a later service
-- change cannot reopen a cross-tenant hole by forgetting one of them.
--
-- The three seams it closes, each of which V1/V14/V36 left open because the catalog was global:
--
--   * `products.category_id` was a plain FK (V36) *because* a product had no company, so a product
--     of company A could be classified under a category of company B. V36's header says so and
--     derives the product's company from its category; V39 stores the owner instead, which is what
--     makes a composite FK expressible at all.
--   * `products.sku` (V1) and `product_variants.bar_code` (V14) were GLOBALLY unique, so two separate
--     businesses could not both sell SKU `TENIS-01`, nor a barcode that legitimately repeats across
--     them — the same argument V14 used to make the barcode global, applied one tenant further out.
--   * `product_variants.product_id` was a bare FK (V1), so a variant could name a product of another
--     company and the barcode key would then be honest about the wrong owner.
--
-- WHY THE TRUNCATE
-- ----------------
-- `company_id` cannot become `NOT NULL` while rows exist that hold `NULL`, and there is no backfill
-- by decision (S2): a row nobody can attribute is discarded rather than guessed. Truncating only
-- `products` is NOT enough — `product_variants`, `product_suppliers` (V1), `purchase_order_details`
-- (V1) reference it, and the variants carry the whole operational chain: `sales_order_items` (V1),
-- `goods_receipt_items` (V12), `purchase_order_details.product_variant_id` (V9),
-- `product_variant_locations` and `inventory_movements` (V10), `product_variant_store_stock` (V13).
-- The statement below clears the chain in one shot, in V14's shape and for V14's reason: no orphan
-- row survives the foreign keys and no document HEADER is left standing without its lines.
-- `sales_orders`, `purchase_orders` and `goods_receipts` are therefore named explicitly — a
-- `CASCADE` from the lines' side would not reach them, because they are the referenced end.
--
-- WHY `UNIQUE (company_id, id)` ON BOTH TABLES
-- --------------------------------------------
-- V31 already had to add `uq_product_categories_company_id UNIQUE (company_id, id)`, and for one
-- reason: a composite foreign key's TARGET must be unique over exactly the referenced columns.
-- `products` and `product_variants` become such targets here (the category link and the
-- variant→product link), so they get the same key. `id` alone is still the primary key; this key
-- exists to be addressable as a pair.
--
-- WHY NO `idx_*(company_id)`
-- -------------------------
-- The same argument V34 and V37 both recorded: a unique constraint's LEADING column already serves
-- the lookup by that column, so a plain `idx_products_company_id` / `idx_product_variants_company_id`
-- next to `UNIQUE (company_id, …)` would be a second structure answering the same read and paying
-- write cost for nothing. What stays is what predates this slice and still answers a real query:
-- `idx_products_sku`, `idx_products_name`, `idx_products_attributes` (V1) and, on the variants,
-- `idx_product_variants_product`, `idx_product_variants_enabled` (V1) and
-- `idx_product_variants_measure_unit` (V29).
--
-- `MATCH SIMPLE` (the default) IS THE POINT OF THE CATEGORY FK
-- -----------------------------------------------------------
-- `fk_products_category_same_company` is `(company_id, category_id) -> product_categories
-- (company_id, id)` with no match type, so a NULL `category_id` — an unclassified product, which is
-- a legal row — skips the check entirely, exactly as V31 documents for `parent_category_id`. A
-- `MATCH FULL` would demand both columns be NULL together and would therefore make classification
-- mandatory; `MATCH PARTIAL` is not implemented by PostgreSQL. The variant link needs no such
-- nuance: both of its columns are `NOT NULL`.
--
-- Both composite FKs are NAMED, like `fk_product_categories_parent_same_company` (V31) and unlike
-- `V9`..`V29`, which leave FKs unnamed: a violation has to name the rule it broke, or the operator
-- reads a generic constraint error and loses the tenancy context.
--
-- WHAT THIS SCHEMA STILL CANNOT EXPRESS
-- ------------------------------------
-- "A variant of company A stocked in a store of company B" stays unrepresentable by design and
-- unreachable by accident at the same time: `company_stores` carries no `company_id` (the company is
-- derived through zone → region → country, `V1:127-136`), so there is no column pair to key a
-- composite FK on. `ProductVariantService` states that rule and its store-scoped reads are predicated
-- on the store's company (`VariantSearchCompanyScopeIntegrationTest` proves the predicate is a query
-- filter). The store stock WRITE that pairs the two is still accepted, and that is recorded as an
-- open, known limit rather than implied to be closed here.
--
-- Forward-only. Undoing it by hand means dropping the four new constraints, re-adding
-- `products_sku_key`, `uq_product_variants_bar_code`, `fk_products_category` and the unnamed
-- `product_variants_product_id_fkey`, and dropping the two `company_id` columns — but the rows this
-- migration discarded do not come back, which is why the banner is at the top and not in a footnote.
-- ============================================

TRUNCATE TABLE sales_order_items, sales_orders, goods_receipt_items, goods_receipts, inventory_movements, product_variant_locations, product_variant_store_stock, purchase_order_details, purchase_orders, product_suppliers, product_variants, products CASCADE;

-- The owner is a fact of the row, not an optional annotation: a product nobody can attribute has no
-- owner, and that state stops being representable here.
ALTER TABLE products ALTER COLUMN company_id SET NOT NULL;
ALTER TABLE product_variants ALTER COLUMN company_id SET NOT NULL;

-- The composite-FK targets. A target must be unique over exactly the referenced columns.
ALTER TABLE products ADD CONSTRAINT uq_products_company_id UNIQUE (company_id, id);
ALTER TABLE product_variants ADD CONSTRAINT uq_product_variants_company_id UNIQUE (company_id, id);

-- The category link becomes company-scoped: V36's plain FK let a product of company A be classified
-- under a category of company B, and this is the constraint that makes it unreachable. The NULL
-- branch is preserved on purpose — see `MATCH SIMPLE` in the header.
ALTER TABLE products DROP CONSTRAINT fk_products_category;
ALTER TABLE products
    ADD CONSTRAINT fk_products_category_same_company
    FOREIGN KEY (company_id, category_id)
    REFERENCES product_categories (company_id, id);

-- The SKU identifies a product inside ONE catalog. The global key V1 created is what stopped two
-- businesses from both selling `TENIS-01`.
ALTER TABLE products DROP CONSTRAINT products_sku_key;
ALTER TABLE products ADD CONSTRAINT uq_products_company_sku UNIQUE (company_id, sku);

-- The barcode identifies the sellable thing inside ONE catalog, for the same reason V14 made it
-- global one tenant too broadly: the same physical product legitimately reaches two businesses.
ALTER TABLE product_variants DROP CONSTRAINT uq_product_variants_bar_code;
ALTER TABLE product_variants
    ADD CONSTRAINT uq_product_variants_company_bar_code UNIQUE (company_id, bar_code);

-- A variant copies its product's company (V39 denormalizes it because the barcode key needs the
-- owner on the row it covers). This is the constraint that keeps the copy from being a second source
-- of truth: a disagreeing pair cannot be written.
ALTER TABLE product_variants DROP CONSTRAINT product_variants_product_id_fkey;
ALTER TABLE product_variants
    ADD CONSTRAINT fk_product_variants_product_same_company
    FOREIGN KEY (company_id, product_id)
    REFERENCES products (company_id, id);

-- `uq_product_variants_product_variant_name` (V14) stays exactly as it is: the size is still free
-- text in `variant_name`, and the same product still cannot carry it twice. It is already scoped to
-- one product, and a product belongs to one company, so no company column is needed there.
