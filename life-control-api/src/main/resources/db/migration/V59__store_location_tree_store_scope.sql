-- ============================================
-- V59 — ADR-0001 D1.a: the store location tree carries its store, at every level
-- ============================================
--
-- WHAT THIS MIGRATION IS, MEASURED IN THE TREE
--
--  ADR-0001 (`docs/adr/0001-inventory-ledger-single-source.md`, accepted 2026-11-02) opens with two
--  facts it has to be able to state, and neither is expressible today:
--
--    * a movement's store IS its location's store. `inventory_movements` (`V10:32-38`) carries
--      `company_store_id` and `store_location_id` as two independent foreign keys, and the location
--      reaches its store only through `store_locations -> store_zones -> store_areas -> company_stores`.
--      A movement in store A naming a location of store B is writable today. ADR invariant I3 forbids it,
--      and `stock_movement`'s `fk_movement_location` is the composite key that makes it unwritable.
--    * `(company_store_id, store_location_id)` is a pair an operational row can hold. It cannot reference
--      anything until `store_locations` carries the store in a key.
--
--  `V44`–`V50` closed this axis for the company hierarchy (`company_countries` -> `company_regions` ->
--  `company_zones` -> `company_stores`) and stopped there on purpose: `store_areas` (`V5`) already carried
--  `company_store_id` because it is the child of a store, while `store_zones` (`V6`) and `store_locations`
--  (`V7`) carried only their parent's id. Those two are this migration.
--
-- WHY D1.a RUNS BEFORE THE LEDGER TABLES AND NOT BESIDE THEM
-- ----------------------------------------------------------
--  `stock_movement` declares `FOREIGN KEY (company_store_id, store_location_id) REFERENCES
--  store_locations (company_store_id, id)`, and that target does not exist without this file. A key added
--  afterwards would rewrite the very table the ledger already points at, so the order is not stylistic:
--  it is what makes the next migration compilable at all. That is also why ADR-0001's acceptance moved
--  D1.a from a sentence about the hierarchy to step 1 of its migration (its `## Corrections applied before
--  acceptance`, item 3).
--
-- WHAT IS DERIVED, AND WHY BY A TRIGGER RATHER THAN BY THE APPLICATION
-- -------------------------------------------------------------------
--  `store_areas`' store is written by the application — it is the foreign key the area is created with.
--  The two levels below it are different: their store is a fact of their parent, so the only honest author
--  is the database. `V50` made exactly this argument one level up and it holds here unchanged: a
--  `@PrePersist` callback reads associations on paths that must not read them (that is the
--  `LazyInitializationException` `V50`'s header measures), a `DEFAULT` cannot reference another row, and a
--  generated column cannot reference another table. A `BEFORE INSERT` trigger runs before the `NOT NULL` it
--  must satisfy, needs no session, and covers every writer — the service, a repository, a fixture, an
--  operator's script.
--
--  `BEFORE UPDATE` is deliberately NOT created. A zone cannot move to another store: the composite key
--  refuses the pair, so there is nothing to re-derive from and an update trigger would be a rule that can
--  never fire. `V50` refused the same shape for the same reason.
--
-- WHY THE PLAIN FOREIGN KEYS GO
-- -----------------------------
--  Each constraint is checked on its own, so `store_zones_store_area_id_fkey` beside
--  `fk_store_zones_area_same_store` would still admit the pair the composite key refuses: the plain key
--  only proves the area exists, not that it belongs to this store. Dropping it makes the composite key the
--  only path from a zone to an area, and from a location to a zone — the same move `V49` made for
--  `company_stores.company_zone_id`.
--
--  The constraint names are measured, not guessed: `V6` and `V7` declare their parent with an inline
--  `REFERENCES`, so PostgreSQL generated `store_zones_store_area_id_fkey` and
--  `store_locations_store_zone_id_fkey`.
--
-- WHY `store_areas` GAINS A KEY AND NO COLUMN
-- -------------------------------------------
--  A composite foreign key needs a unique target, so `store_areas` needs `UNIQUE (company_store_id, id)` —
--  the same key `V40` gave `product_variants`, `V48` gave `company_stores` and `V49` gave `company_zones`.
--  It has the column already; only the key was missing.
--
-- WHAT THIS MIGRATION DOES NOT DO
-- -------------------------------
--  It does not arm `store_zones` or `store_locations` on `tenant_table`. ADR-0005's scope is the tables
--  that carry `company_id` (`odd/tasks/tenant-isolation-rls.md` S2, decided by the maintainer), and these
--  two carry the store, exactly as `store_areas` does — and `store_areas` carries no policy today. Their
--  tenancy is enforced through `company_stores`, which is armed, plus the composite keys that pin each
--  level to its parent. Widening the armed set here would be a different decision, not this slice.
--
--  It does not touch `store_areas.company_store_id`, `V5`'s key on `(company_store_id, area_code)`, any
--  service, or any response shape: the derivation is invisible to the API, which is the point.
--
-- Forward-only. Undoing it by hand means dropping the two triggers and their functions, the two composite
-- keys, re-adding the two plain keys, and dropping `company_store_id` from the two tables together with
-- their unique keys. No row is lost in either direction: the backfill writes a value each row already
-- implied through its parent, and the `NOT NULL` below finds no row it must reject because a row that
-- could not be attributed to a store would need a parent the foreign key already refused.
-- ============================================

-- ─── the key the two levels below reference ─────────────────────────────────
ALTER TABLE store_areas ADD CONSTRAINT uq_store_areas_company_store_id UNIQUE (company_store_id, id);

-- ─── store_zones: level 3 ───────────────────────────────────────────────────
ALTER TABLE store_zones ADD COLUMN company_store_id UUID;

UPDATE store_zones z
SET company_store_id = a.company_store_id
FROM store_areas a
WHERE a.id = z.store_area_id;

-- The backfill reports instead of trusting its own UPDATE: a row the walk could not reach is a row whose
-- area is missing, and `NOT NULL` would say so with a message that names no level.
DO $$
DECLARE
    v_unattributed INTEGER;
BEGIN
    SELECT COUNT(*) INTO v_unattributed FROM store_zones WHERE company_store_id IS NULL;
    IF v_unattributed > 0 THEN
        RAISE EXCEPTION 'V59 could not attribute % store_zone row(s) to a store: their store_area_id resolves to no store_areas row, which store_zones_store_area_id_fkey should already forbid', v_unattributed;
    END IF;
END $$;

ALTER TABLE store_zones ALTER COLUMN company_store_id SET NOT NULL;

ALTER TABLE store_zones ADD CONSTRAINT uq_store_zones_company_store_id UNIQUE (company_store_id, id);

ALTER TABLE store_zones DROP CONSTRAINT store_zones_store_area_id_fkey;

ALTER TABLE store_zones
    ADD CONSTRAINT fk_store_zones_area_same_store
    FOREIGN KEY (company_store_id, store_area_id)
    REFERENCES store_areas (company_store_id, id);

-- ─── store_locations: level 4 ───────────────────────────────────────────────
ALTER TABLE store_locations ADD COLUMN company_store_id UUID;

UPDATE store_locations l
SET company_store_id = z.company_store_id
FROM store_zones z
WHERE z.id = l.store_zone_id;

DO $$
DECLARE
    v_unattributed INTEGER;
BEGIN
    SELECT COUNT(*) INTO v_unattributed FROM store_locations WHERE company_store_id IS NULL;
    IF v_unattributed > 0 THEN
        RAISE EXCEPTION 'V59 could not attribute % store_location row(s) to a store: their store_zone_id resolves to no store_zones row, which store_locations_store_zone_id_fkey should already forbid', v_unattributed;
    END IF;
END $$;

ALTER TABLE store_locations ALTER COLUMN company_store_id SET NOT NULL;

ALTER TABLE store_locations ADD CONSTRAINT uq_store_locations_company_store_id UNIQUE (company_store_id, id);

ALTER TABLE store_locations DROP CONSTRAINT store_locations_store_zone_id_fkey;

ALTER TABLE store_locations
    ADD CONSTRAINT fk_store_locations_zone_same_store
    FOREIGN KEY (company_store_id, store_zone_id)
    REFERENCES store_zones (company_store_id, id);

-- ─── the store of a zone is its area's, for every writer ────────────────────
CREATE OR REPLACE FUNCTION derive_store_zone_store_from_area() RETURNS trigger AS $$
BEGIN
    IF NEW.company_store_id IS NULL THEN
        SELECT a.company_store_id INTO NEW.company_store_id
        FROM store_areas a
        WHERE a.id = NEW.store_area_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_store_zones_derive_store
    BEFORE INSERT ON store_zones
    FOR EACH ROW
    EXECUTE FUNCTION derive_store_zone_store_from_area();

-- ─── the store of a location is its zone's, for every writer ────────────────
CREATE OR REPLACE FUNCTION derive_store_location_store_from_zone() RETURNS trigger AS $$
BEGIN
    IF NEW.company_store_id IS NULL THEN
        SELECT z.company_store_id INTO NEW.company_store_id
        FROM store_zones z
        WHERE z.id = NEW.store_zone_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_store_locations_derive_store
    BEFORE INSERT ON store_locations
    FOR EACH ROW
    EXECUTE FUNCTION derive_store_location_store_from_zone();
