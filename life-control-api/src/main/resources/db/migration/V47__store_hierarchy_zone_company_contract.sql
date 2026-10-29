-- ============================================
-- V47 — Store hierarchy, level 2 of 3, step 2 of 2: the zone's company contract
-- ============================================
--
--  NOTHING IS DISCARDED HERE, AND THIS HEADER HAS TO SAY WHY
--
--  Every zone already carries an owner: `company_regions.company_id` became `NOT NULL` in V45, and
--  `company_zones.company_region_id` is a `NOT NULL` foreign key to it. The `UPDATE` below is therefore a
--  DERIVATION of a fact the row holds — the second hop of the walk `CompanyStoreOwnerResolver` performs at
--  runtime — and not a backfill inventing a decision nobody took.
--
--  A zone that could not be attributed to a company through its region is UNREPRESENTABLE: it would need
--  a `company_region_id` that no `company_regions` row answers, and the foreign key this migration
--  replaces forbids exactly that. That is why this level needs no destructive authorization either, and
--  why its `NOT NULL` finds no row it must reject.
--
--  The `UPDATE` is not dead code where the table is empty: an operator's database, or one this API has
--  written to since V1, reaches this migration with rows, and those are exactly the rows the `NOT NULL`
--  would otherwise refuse (V43's and V45's headers make the same argument).
--
-- ============================================
--
-- WHY
-- ---
-- V46 added `company_zones.company_id` as a NULLABLE column and stopped there on purpose. The mapping
-- carries the value now — `CompanyZone.deriveCompanyFromRegion()` reads it from the region at persist time
-- — and this migration turns that mapping detail into a rule the database enforces, so a later service or
-- mapping change cannot reopen the gap by forgetting it.
--
-- WHY THE COMPOSITE KEY IS THE POINT
-- ----------------------------------
-- `(company_id, company_region_id) -> company_regions (company_id, id)` makes a disagreeing pair
-- unrepresentable, and like V45 it is also the target that lets the STORE enforce the same rule one level
-- down, through `uq_company_zones_company_id` (V46). With the three levels in place the owner of a store is
-- pinned by the database to the chain above it, which is what makes a stored owner unable to go stale.
--
-- WHY THE PLAIN FOREIGN KEY GOES
-- ------------------------------
-- Each constraint is checked on its own, so `company_zones_company_region_id_fkey` beside
-- `fk_company_zones_region_same_company` would still admit the pair the composite one refuses: the plain
-- key only proves the region exists, not that it belongs to this company. Dropping it makes the composite
-- key the ONLY path from a zone to a company region — V43, V45's sibling argument.
--
-- WHY NO SEPARATE `fk_company_zones_company` TO `companies`
-- --------------------------------------------------------
-- The composite key already makes `company_id` a real company: it has to equal the company of a row of
-- `company_regions`, which carries its own composite key to `company_countries` and from there a foreign
-- key to `companies`. A fourth constraint would answer the same question twice (V43).
--
-- Forward-only. Undoing it by hand means dropping `fk_company_zones_region_same_company`, re-adding
-- `company_zones_company_region_id_fkey` (measured, not guessed: the name V1's inline `REFERENCES`
-- produced), and dropping the column with `uq_company_zones_company_id`. No row is lost in either
-- direction.
-- ============================================

-- ─── the owner is a fact of the row's own region ─────────────────────────────
UPDATE company_zones z
SET company_id = r.company_id
FROM company_regions r
WHERE r.id = z.company_region_id;

ALTER TABLE company_zones ALTER COLUMN company_id SET NOT NULL;

-- A plain FK beside a composite one still admits the pair the composite refuses.
ALTER TABLE company_zones DROP CONSTRAINT company_zones_company_region_id_fkey;

ALTER TABLE company_zones
    ADD CONSTRAINT fk_company_zones_region_same_company
    FOREIGN KEY (company_id, company_region_id)
    REFERENCES company_regions (company_id, id);
