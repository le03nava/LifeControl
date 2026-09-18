-- ============================================
-- V8 — Optimistic locking for the store tree
-- ============================================
-- Adds the JPA `@Version` column to the four mutable store-tree tables
-- (company_stores, store_areas, store_zones, store_locations) so a concurrent
-- update fails with an optimistic-lock conflict instead of silently winning
-- the last write. Additive only: the NOT NULL DEFAULT 0 backfills existing
-- rows, matching the primitive `long version` entity mapping.
-- ============================================

ALTER TABLE company_stores ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE store_areas ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE store_zones ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE store_locations ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
