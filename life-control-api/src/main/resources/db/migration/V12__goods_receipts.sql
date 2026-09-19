-- ============================================
-- V12 — Goods receipts (reception documents against purchase orders) (W2c)
-- ============================================
-- `goods_receipts` is the immutable reception document raised against a purchase order:
-- it records the order it closes against, the store, the store location where the goods
-- were received, the receipt status and who received them when.
-- `goods_receipt_items` holds its lines, one per received `purchase_order_details` row, and
-- carries the product variant and the received quantity (strictly positive, enforced by CHECK).
-- A reception adds stock through the inventory core (W2a/W2b); only the document is new here.
--
-- Foreign keys are unnamed, matching the baseline and the V9/V10/V11 style. No backfill: the
-- feature starts from an empty receipt history.
--
-- The receipt status family is seeded below with the same idempotent natural-key guard V3 uses
-- (`INSERT ... SELECT ... WHERE NOT EXISTS` over `LOWER(...)`, no hardcoded ids), so a re-run and
-- a fresh database converge on the same single `GOODS_RECEIPT` type and `Registered` status.
-- ============================================

CREATE TABLE goods_receipts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    receipt_number VARCHAR(30) NOT NULL UNIQUE,
    purchase_order_id UUID NOT NULL REFERENCES purchase_orders(id),
    company_store_id UUID NOT NULL REFERENCES company_stores(id),
    receiving_location_id UUID NOT NULL REFERENCES store_locations(id),
    status_id UUID NOT NULL REFERENCES statuses(id),
    received_by VARCHAR(255),
    received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    comments VARCHAR(500),
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_goods_receipts_purchase_order ON goods_receipts(purchase_order_id);
CREATE INDEX idx_goods_receipts_store ON goods_receipts(company_store_id);
CREATE INDEX idx_goods_receipts_receiving_location ON goods_receipts(receiving_location_id);
CREATE INDEX idx_goods_receipts_status ON goods_receipts(status_id);
CREATE INDEX idx_goods_receipts_received_at ON goods_receipts(received_at);

CREATE TABLE goods_receipt_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    goods_receipt_id UUID NOT NULL REFERENCES goods_receipts(id),
    purchase_order_detail_id UUID NOT NULL REFERENCES purchase_order_details(id),
    product_variant_id UUID NOT NULL REFERENCES product_variants(id),
    quantity_received DECIMAL(12,2) NOT NULL CHECK (quantity_received > 0),
    comments VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_goods_receipt_items_receipt ON goods_receipt_items(goods_receipt_id);
CREATE INDEX idx_goods_receipt_items_detail ON goods_receipt_items(purchase_order_detail_id);
CREATE INDEX idx_goods_receipt_items_variant ON goods_receipt_items(product_variant_id);

-- ============================================
-- Receipt status family (mirrors the V3 natural-key-guard pattern)
-- ============================================

INSERT INTO status_types (id, status_type_name, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'GOODS_RECEIPT', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM status_types WHERE LOWER(status_type_name) = LOWER('GOODS_RECEIPT'));

INSERT INTO statuses (id, status_name, status_type_id, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'Registered', st.id, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM status_types st
WHERE LOWER(st.status_type_name) = LOWER('GOODS_RECEIPT')
  AND NOT EXISTS (SELECT 1 FROM statuses s WHERE s.status_type_id = st.id AND LOWER(s.status_name) = LOWER('Registered'));
