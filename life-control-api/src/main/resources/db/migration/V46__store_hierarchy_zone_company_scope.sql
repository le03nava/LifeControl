-- ============================================
-- V46 — Store hierarchy, level 2 of 3, step 1 of 2: the zone's company column
-- ============================================
-- Level 1 is complete: `company_regions.company_id` is `NOT NULL` (V45) and paired with
-- `fk_company_regions_country_same_company`, so a region cannot disagree with the country it names. This
-- migration is the same EXPAND step one level down, and it is two statements because the TARGET it needs
-- already landed: `uq_company_regions_company_id` (V44) is what the zone's composite foreign key will
-- reference, so nothing new has to be made unique above it.
--
--   * `company_zones` gains a NULLABLE `company_id`.
--   * `company_zones` gains `UNIQUE (company_id, id)` — the target the STORE's composite key will need,
--     one level down.
--
-- WHY NULLABLE, FOR EXACTLY ONE SLICE: the reason V39, V41 and V44 state in their own headers. A
-- `NOT NULL` column cannot arrive before the write surface carries it, and the write surface for this
-- level is `CompanyZone`'s mapping, which derives the owner from the region it already names. The mapping
-- lands with this migration, but the suite has to be green after every slice and a migration is not the
-- place to assume a mapping change landed.
--
-- WHY NO BACKFILL HERE: as in V44, the `UPDATE` that derives the owner from the parent belongs to the
-- CONTRACT step, because it is what makes the `NOT NULL` possible. Filling here would leave a slice whose
-- column is populated by a rule nothing enforces yet.
--
-- WHY `UNIQUE (company_id, id)` AND NOT A SEPARATE INDEX: a unique constraint's leading column already
-- serves the lookup by company, so a second `idx_company_zones_company_id` would answer the same read
-- twice (V34, V37, V40). V1's `idx_cz_region` stays: a lookup by PARENT leads with a different column.
--
-- Forward-only and additive: one `ADD COLUMN` and one constraint; dropping both restores V1's shape, and
-- every existing row keeps `company_id = NULL`, which is what an unattributed zone is for exactly one
-- slice.
-- ============================================

-- ─── company_zones: the owner, optional for one slice ────────────────────────
ALTER TABLE company_zones ADD COLUMN company_id UUID;

-- The target a store's composite key will reference, and the lookup by company in one structure.
ALTER TABLE company_zones ADD CONSTRAINT uq_company_zones_company_id UNIQUE (company_id, id);
