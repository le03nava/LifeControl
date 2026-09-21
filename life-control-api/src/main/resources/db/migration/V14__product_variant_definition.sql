-- ============================================
-- V14 — product_variants becomes the GLOBAL variant definition
-- ============================================
--
--  !!!  DESTRUCTIVE MIGRATION — DATA DISCARD BY DECISION D5  !!!
--
--  This migration TRUNCATES the operational history covered below and must NOT be run
--  against any database whose sales orders, purchase orders, goods receipts, inventory
--  movements or location balances are worth keeping. It is safe only because the
--  variant-identity refactor was explicitly authorized to discard that history instead
--  of backfilling it (decision D5, 2026-09-20). There is no undo.
--
-- ============================================
--
-- WHY
-- ---
-- `product_variants` conflated two different concepts in one table:
--
--   1. the sellable DEFINITION (product, `variant_name`, `bar_code`), which is global, and
--   2. the per-store OPERATIONAL row (`company_store_id`, `stock`, `list_price`,
--      `cost_price`), which is not.
--
-- The mismatch was visible in the schema itself: `idx_product_variants_bar_code` (V1) was a
-- GLOBAL unique index over a STORE-SCOPED table, so the same physical barcode — the same
-- shoe in the same size — could be registered in exactly one store, and selling it in
-- three stores required three duplicated rows. This migration makes the table the global
-- definition it should always have been, and the store-scoped half lives in
-- `product_variant_store_stock` (V13), unique per `(product_variant_id, company_store_id)`.
--
-- Because the row this migration leaves behind is GLOBAL, a global `UNIQUE(bar_code)` is now
-- CORRECT: the barcode identifies the sellable thing itself, not its presence in a store.
-- `UNIQUE(product_id, variant_name)` (D3) closes the second gap: the size is still free text
-- in `variant_name`, but the same product can no longer carry it twice.
--
-- The five foreign keys that reference `product_variants` are deliberately NOT re-pointed,
-- because `product_variants` STAYS the referenced table — only columns are dropped from it:
--   `sales_order_items.product_variant_id`          V1:499
--   `purchase_order_details.product_variant_id`     V9:14
--   `goods_receipt_items.product_variant_id`        V12:43
--   `product_variant_locations.product_variant_id`  V10:20
--   `inventory_movements.product_variant_id`        V10:32
-- Foreign keys are unnamed, matching the baseline and the V9/V10/V12/V13 style.
--
-- WHY THE TRUNCATE
-- ----------------
-- Setting `bar_code` NOT NULL and adding its unique index cannot succeed against legacy rows
-- that hold NULLs or duplicate barcodes, and there is no backfill by decision. Truncating only
-- `product_variants` is NOT enough: `product_variant_locations`, `inventory_movements`,
-- `goods_receipt_items`, `sales_order_items` and the `purchase_order_details.product_variant_id`
-- link (V9) all reference variants, so a partial truncate would either leave orphan rows or fail
-- on the foreign keys. The statement below clears the whole operational chain in one shot so no
-- orphan row survives and no document header is left without its lines.
-- `product_variant_store_stock` is emptied as well, through the `product_variants` CASCADE.
-- ============================================

TRUNCATE TABLE sales_order_items, sales_orders, goods_receipt_items, goods_receipts, inventory_movements, product_variant_locations, purchase_order_details, purchase_orders, product_variants CASCADE;

-- The store-scoped indexes die with their columns; `idx_product_variants_store` and
-- `idx_product_variants_sku` become meaningless once `company_store_id`/`sku` are gone.
DROP INDEX idx_product_variants_bar_code;
DROP INDEX idx_product_variants_store;
DROP INDEX idx_product_variants_sku;

-- `product_variants` is now the global definition: `company_store_id`, `sku`, `stock`,
-- `list_price` and `cost_price` move to `product_variant_store_stock` or disappear (D1, D2).
ALTER TABLE product_variants
    DROP COLUMN company_store_id,
    DROP COLUMN sku,
    DROP COLUMN stock,
    DROP COLUMN list_price,
    DROP COLUMN cost_price,
    ALTER COLUMN bar_code SET NOT NULL,
    ALTER COLUMN variant_name SET NOT NULL,
    ADD CONSTRAINT uq_product_variants_bar_code UNIQUE (bar_code),
    ADD CONSTRAINT uq_product_variants_product_variant_name UNIQUE (product_id, variant_name);

-- `idx_product_variants_product` (non-unique) and `idx_product_variants_enabled` stay: both
-- predicates remain valid on the definition row.

-- A purchase-order line always names a variant in the current contract
-- (`PurchaseOrderDetailRequest.productVariantId` is `@NotNull`); the column was only nullable
-- because V9 shipped the link before the picker existed. The data is discarded, so the legacy
-- null tolerance is dropped with it and the database now matches the DTO.
ALTER TABLE purchase_order_details ALTER COLUMN product_variant_id SET NOT NULL;
