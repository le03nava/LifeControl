-- ============================================
-- V44 — Store hierarchy, level 1 of 3, step 1 of 2: the region's company column
-- ============================================
-- `company_stores`, `company_zones` and `company_regions` have derived their owner since V1: a store
-- hangs off a zone, a zone off a region, a region off a country, and only `company_countries` stores a
-- `company_id`. Three migrations already record what that costs. V40:84-86 states the limit from the
-- product catalog's side ("`company_stores` carries no `company_id` … so there is no column pair to key
-- a composite FK on"); V41 and V42 work around it; and V43:70-71 states the sharpest consequence — an
-- order's company can be tied to its SUPPLIER by the database, but to its STORE only by
-- `PurchaseOrderService`.
--
-- The derivation itself is correct: `CompanyStoreOwnerResolver` walks store → zone → region → country →
-- company and every service path asks it, so no API path reaches the gap today. The gap is structural,
-- not behavioral: any writer that goes through the repositories instead of the service can store a row
-- whose company disagrees with the parent it names, and no constraint objects. That is the asymmetry
-- this repository has been closing one level at a time, and the store hierarchy is the last level of it.
--
-- This migration is the EXPAND step of the first level, and it does three things and nothing else:
--
--   * `company_countries` gains `UNIQUE (company_id, id)` — the TARGET the region's composite key needs
--     one migration from now. A composite foreign key must reference a unique column pair, which is why
--     V40:105 and V41 added the same key to `products` and `suppliers` before their children keyed
--     against them.
--   * `company_regions` gains a NULLABLE `company_id`.
--   * `company_regions` gains `UNIQUE (company_id, id)` — the target the ZONE's key will need one level
--     down.
--
-- WHY NULLABLE, FOR EXACTLY ONE SLICE: the reason V39 and V41 state in their own headers. A `NOT NULL`
-- column cannot arrive before the write surface knows where the company comes from. It knows as of this
-- commit — `CompanyRegion` derives the owner from its country in a `@PrePersist` callback — but the
-- suite has to be green after every slice, and a migration is not the place to assume a service or
-- mapping change landed. Here the column is optional, every existing row keeps `NULL`, and nothing reads
-- it yet.
--
-- WHY NO BACKFILL HERE: the `UPDATE` that derives the owner from the parent is the CONTRACT step's own
-- work, because it is what makes the `NOT NULL` possible. Splitting it the other way — fill here,
-- constrain there — would leave a slice whose column is populated by a rule that nothing yet enforces.
--
-- WHY `UNIQUE (company_id, id)` AND NOT A SEPARATE INDEX: a unique constraint's leading column already
-- serves the lookup by company, so a second `idx_company_regions_company_id` would answer the same read
-- twice — the cost V34, V37 and V40 all refuse. V1's `idx_cr_company_country` stays, and so does
-- `idx_cc_company` on the parent: the composite key leads with `company_id`, so a lookup by PARENT is a
-- different read and still needs its own index.
--
-- Forward-only and additive: one `ADD COLUMN` and two constraints. Dropping the three restores V1's
-- shape exactly, and no row is lost in either direction.
-- ============================================

-- ─── company_countries: the unique target the region's composite key needs ───
ALTER TABLE company_countries ADD CONSTRAINT uq_company_countries_company_id UNIQUE (company_id, id);

-- ─── company_regions: the owner, optional for one slice ───────────────────────
ALTER TABLE company_regions ADD COLUMN company_id UUID;

-- The target a zone's composite key will reference, and the lookup by company in one structure.
ALTER TABLE company_regions ADD CONSTRAINT uq_company_regions_company_id UNIQUE (company_id, id);
