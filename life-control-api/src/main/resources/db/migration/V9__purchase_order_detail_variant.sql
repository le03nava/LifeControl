-- ============================================
-- V9 — Purchase order line -> product variant link
-- ============================================
-- Adds an optional `product_variant_id` to `purchase_order_details` so a line
-- can pin the exact variant (size/SKU) that is ordered and, later, received.
-- The column is nullable on purpose and there is no backfill: existing lines
-- keep `NULL`, and this slice keeps the field optional until the Angular picker
-- (workstream W1b) ships and flips it to required. The goods-receipt workstream
-- (W2) reads the line's variant to register the received quantity against it.
-- The FK has no ON DELETE action: a variant referenced by a line must not be
-- hard-deleted, matching the unnamed-FK style of the baseline schema.
-- ============================================

ALTER TABLE purchase_order_details ADD COLUMN product_variant_id UUID REFERENCES product_variants(id);
CREATE INDEX idx_pod_variant ON purchase_order_details(product_variant_id);
