-- ============================================
-- V7 — Store Locations (level 4 of the store location tree)
-- ============================================
-- Store -> Area -> Zone -> Location. `store_locations` is the level-4 node,
-- a child of `store_zones`. Locations are soft-deleted via `enabled = false`.
-- `location_code` is unique per zone (not per area or store): two zones of the
-- same area may reuse the same code.
-- ============================================

CREATE TABLE store_locations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_zone_id UUID NOT NULL REFERENCES store_zones(id),
    location_code VARCHAR(10) NOT NULL,
    location_name VARCHAR(100) NOT NULL,
    description VARCHAR(255),
    display_order INTEGER,
    enabled BOOLEAN DEFAULT true NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(store_zone_id, location_code)
);
CREATE INDEX idx_store_locations_zone ON store_locations(store_zone_id);
