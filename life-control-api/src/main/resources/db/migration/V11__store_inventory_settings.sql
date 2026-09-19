-- ============================================
-- V11 — Per-store inventory settings (W2b)
-- ============================================
-- Each company store points at the store location where goods are received
-- (`receiving_location_id`) and at the store location sales will later deduct
-- from (`sales_location_id`, stored now and consumed by the sales rework, W3).
--
-- The store is the primary key: one row per store, no separate id column.
-- `version` matches the optimistic-locking shape introduced by
-- V8__store_optimistic_locking.sql (NOT NULL DEFAULT 0 backfilling the primitive
-- `long version` entity mapping).
--
-- The two location columns are foreign keys, but the FK only proves that the
-- location exists: `store_locations` hangs off `store_zones`, which hangs off
-- `store_areas`, which hangs off `company_stores`, so a location of ANY store
-- satisfies the constraint. "This location belongs to this store" follows from
-- that foreign-key chain and is enforced in the application (see
-- StoreInventorySettingsService), not by the schema.
-- ============================================

CREATE TABLE store_inventory_settings (
    company_store_id      UUID PRIMARY KEY REFERENCES company_stores(id),
    receiving_location_id UUID NOT NULL REFERENCES store_locations(id),
    sales_location_id     UUID NOT NULL REFERENCES store_locations(id),
    version    BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
