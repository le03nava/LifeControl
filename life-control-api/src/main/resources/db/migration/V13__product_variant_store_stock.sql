-- ============================================
-- V13 — Per-store stock and pricing for a product variant
-- ============================================
-- `product_variants` carries two different concepts today: the sellable definition
-- (product, variant name, identifier) which is global, and the per-store operational row
-- (`company_store_id`, `stock`, `list_price`, `cost_price`) which is not. One table cannot
-- express both, and the mismatch is visible: `idx_product_variants_bar_code` (V1) is a
-- GLOBAL unique index on a store-scoped table, so the same physical barcode — the same
-- shoe in the same size — cannot be registered in a second store.
--
-- This migration adds the per-store row as its own table, unique per
-- `(product_variant_id, company_store_id)`, mirroring the proven scope-per-row shape of
-- `product_variant_locations` (V10). `product_variants` is left untouched here on purpose:
-- the legacy columns are dropped only after every reader has moved (V14), so each step of
-- the refactor keeps the application compiling.
--
-- No backfill: the historical data is discarded by decision (D5), so the table starts empty
-- and the legacy columns are the only populated copy until the cutover completes.
--
-- Foreign keys are unnamed, matching the baseline and V9/V10/V12 style.
-- ============================================

CREATE TABLE product_variant_store_stock (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_variant_id UUID NOT NULL REFERENCES product_variants(id),
    company_store_id UUID NOT NULL REFERENCES company_stores(id),
    stock DECIMAL(12,2) NOT NULL DEFAULT 0,
    list_price DECIMAL(12,2),
    cost_price DECIMAL(12,2),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(product_variant_id, company_store_id)
);
CREATE INDEX idx_product_variant_store_stock_variant ON product_variant_store_stock(product_variant_id);
CREATE INDEX idx_product_variant_store_stock_store ON product_variant_store_stock(company_store_id);
