-- ============================================
-- V10 — Inventory core (per-location balances + append-only movement ledger)
-- ============================================
-- `product_variant_locations` holds the stock balance of one product variant in one
-- store location (the level-4 leaf of the store tree) and is unique per pair.
-- `inventory_movements` is the append-only ledger that explains every balance
-- change: it has no `updated_at` because a movement is never modified or deleted.
--
-- Until the sales rework (workstream W3) lands, the sales path keeps deducting from
-- `product_variants.stock` without a location, so the location balances overcount by
-- everything sold and the ledger carries only RECEIPT rows. W3 owes the reconciliation
-- and the SALE movements.
--
-- Foreign keys are unnamed, matching the baseline and V9 style. No backfill: this
-- workstream starts from empty balances and an empty ledger (D6).
-- ============================================

CREATE TABLE product_variant_locations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_variant_id UUID NOT NULL REFERENCES product_variants(id),
    store_location_id UUID NOT NULL REFERENCES store_locations(id),
    stock DECIMAL(12,2) NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(product_variant_id, store_location_id)
);
CREATE INDEX idx_product_variant_locations_variant ON product_variant_locations(product_variant_id);
CREATE INDEX idx_product_variant_locations_location ON product_variant_locations(store_location_id);

CREATE TABLE inventory_movements (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_variant_id UUID NOT NULL REFERENCES product_variants(id),
    company_store_id UUID NOT NULL REFERENCES company_stores(id),
    store_location_id UUID NOT NULL REFERENCES store_locations(id),
    movement_type VARCHAR(30) NOT NULL,
    quantity DECIMAL(12,2) NOT NULL,
    reference_type VARCHAR(40),
    reference_id UUID,
    created_by VARCHAR(255),
    occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_inventory_movements_variant ON inventory_movements(product_variant_id);
CREATE INDEX idx_inventory_movements_location ON inventory_movements(store_location_id);
CREATE INDEX idx_inventory_movements_store ON inventory_movements(company_store_id);
CREATE INDEX idx_inventory_movements_occurred_at ON inventory_movements(occurred_at);
CREATE INDEX idx_inventory_movements_reference ON inventory_movements(reference_type, reference_id);
