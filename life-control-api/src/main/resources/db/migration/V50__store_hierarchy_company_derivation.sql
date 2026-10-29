-- ============================================
-- V50 — The store hierarchy's derivation moves into the database
-- ============================================
--
--  WHY THIS MIGRATION EXISTS AT ALL, MEASURED
--
--  V45, V47 and V49 made `company_id` mandatory on the three levels, so *something* has to write it on
--  every insert. The first attempt did it in the mapping: a `@PrePersist` callback on each entity read
--  the owner from the parent association the caller had already set. It worked for every service path and
--  for 4141 of 4142 tests — and it broke the two-thousand-and-first case, which is the one that matters:
--  `PurchaseOrderDetailVariantIntegrationTest` saves a store while holding a *detached* reference to its
--  zone (a plain `findById(...).getCompanyZone()` outside a transaction), and a callback that calls
--  `zone.getCompany()` initializes that proxy, which throws `LazyInitializationException` with no session.
--
--  Saving a child that names a detached parent is legitimate JPA, not a test smell: the child needs the
--  parent's identity, which the reference already carries, and nothing should force the caller to load the
--  parent's own associations first. So the derivation cannot live in the entity's lifecycle. It moves
--  here, one level down, where it costs no session, no proxy read and no caller discipline — and where it
--  covers writers that never go through JPA at all.
--
--  WHAT CHANGES IN THE MAPPING, AND WHY THAT IS THE HONEST SHAPE
--
--  The three entities keep the association — it is how a read answers "which company owns this row"
--  without walking — but map it `insertable = false, updatable = false`: the application does not write
--  this column, the database does. That is not a workaround for the callback; it is the statement that
--  `company_id` has exactly one author, and that a caller cannot disagree with it because a caller is
--  never consulted.
--
--  WHY A TRIGGER, WHEN THIS SCHEMA HAS NONE
--  ----------------------------------------
--  Every other rule in this axis is a constraint, and constraints cannot derive. The alternatives were
--  worse: a callback reads associations on paths that must not read them (measured above), a `DEFAULT`
--  cannot reference another row, and a generated column cannot reference another table. A `BEFORE INSERT`
--  trigger is the only mechanism that runs before the `NOT NULL` it must satisfy, needs no session, and
--  applies to every writer the same way — the service, the repository, a fixture, an operator's script.
--  It also gives this unit the property V40's header asks for in one sentence: a later code change cannot
--  reopen the gap by forgetting a line.
--
--  WHAT IT DOES NOT DO
--  -------------------
--  It does not decide anything the composite keys do not already say. Each function reads the parent's
--  stored owner — the same value `CompanyStoreOwnerResolver` walks to at runtime — and only when the row
--  arrives without one; an explicit `company_id` is left untouched, which is what keeps V1-era fixtures
--  and hand-written inserts working. A row whose explicit owner disagrees with its parent's is still
--  refused by `fk_company_regions_country_same_company`, `fk_company_zones_region_same_company` and
--  `fk_company_stores_zone_same_company`: the trigger fills an absence, it never overrules a disagreement.
--
--  `BEFORE UPDATE` is deliberately NOT created. A row cannot move to another company: the composite keys
--  refuse it, so there is no parent change to re-derive from and nothing for an update trigger to do. An
--  update trigger here would be a rule that can never fire, which is the shape this repository keeps
--  refusing.
--
--  Forward-only. Undoing it by hand means dropping the three triggers and the three functions; the columns
--  and their keys belong to V44-V49 and are untouched. No row is read, derived or lost by this migration
--  itself, which is why it needs no data consequence statement of its own — the derivations for the rows
--  that already existed ran in V45, V47 and V49.
-- ============================================

-- ─── level 1: a region's owner is its country's ──────────────────────────────
CREATE OR REPLACE FUNCTION derive_company_region_from_country() RETURNS trigger AS $$
BEGIN
    IF NEW.company_id IS NULL THEN
        SELECT cc.company_id INTO NEW.company_id
        FROM company_countries cc
        WHERE cc.id = NEW.company_country_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_company_regions_derive_company
    BEFORE INSERT ON company_regions
    FOR EACH ROW
    EXECUTE FUNCTION derive_company_region_from_country();

-- ─── level 2: a zone's owner is its region's ────────────────────────────────
CREATE OR REPLACE FUNCTION derive_company_zone_from_region() RETURNS trigger AS $$
BEGIN
    IF NEW.company_id IS NULL THEN
        SELECT r.company_id INTO NEW.company_id
        FROM company_regions r
        WHERE r.id = NEW.company_region_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_company_zones_derive_company
    BEFORE INSERT ON company_zones
    FOR EACH ROW
    EXECUTE FUNCTION derive_company_zone_from_region();

-- ─── level 3: a store's owner is its zone's ─────────────────────────────────
CREATE OR REPLACE FUNCTION derive_company_store_from_zone() RETURNS trigger AS $$
BEGIN
    IF NEW.company_id IS NULL THEN
        SELECT z.company_id INTO NEW.company_id
        FROM company_zones z
        WHERE z.id = NEW.company_zone_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_company_stores_derive_company
    BEFORE INSERT ON company_stores
    FOR EACH ROW
    EXECUTE FUNCTION derive_company_store_from_zone();
