-- ============================================
-- V5 — Store Areas (level 2 of the store location tree)
-- ============================================
-- Store -> Area -> Zone -> Location. `store_areas` is the level-2 node,
-- a child of `company_stores`. Areas are soft-deleted via `enabled = false`.
-- `area_code` is unique per store (not globally).
-- ============================================

CREATE TABLE store_areas (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_store_id UUID NOT NULL REFERENCES company_stores(id),
    area_code VARCHAR(10) NOT NULL,
    area_name VARCHAR(100) NOT NULL,
    description VARCHAR(255),
    display_order INTEGER,
    enabled BOOLEAN DEFAULT true NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(company_store_id, area_code)
);
CREATE INDEX idx_store_areas_store ON store_areas(company_store_id);
