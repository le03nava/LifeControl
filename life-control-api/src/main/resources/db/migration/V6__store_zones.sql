-- ============================================
-- V6 — Store Zones (level 3 of the store location tree)
-- ============================================
-- Store -> Area -> Zone -> Location. `store_zones` is the level-3 node,
-- a child of `store_areas`. Zones are soft-deleted via `enabled = false`.
-- `zone_code` is unique per area (not per store): two areas of the same
-- store may reuse the same code.
-- ============================================

CREATE TABLE store_zones (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_area_id UUID NOT NULL REFERENCES store_areas(id),
    zone_code VARCHAR(10) NOT NULL,
    zone_name VARCHAR(100) NOT NULL,
    description VARCHAR(255),
    display_order INTEGER,
    enabled BOOLEAN DEFAULT true NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(store_area_id, zone_code)
);
CREATE INDEX idx_store_zones_area ON store_zones(store_area_id);
