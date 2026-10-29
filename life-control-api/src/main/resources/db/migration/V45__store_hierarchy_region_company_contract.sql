-- ============================================
-- V45 — Store hierarchy, level 1 of 3, step 2 of 2: the region's company contract
-- ============================================
--
--  NOTHING IS DISCARDED HERE, AND THIS HEADER HAS TO SAY WHY
--
--  V40 and V42 discarded rows, because nothing in either table carried an owner and none could be
--  invented. Here every row already carries one: `company_countries.company_id` has been `NOT NULL`
--  since V1, and `company_regions.company_country_id` is a `NOT NULL` foreign key pointing at it. So the
--  `UPDATE` below is a DERIVATION of a fact the row holds — the same fact `CompanyStoreOwnerResolver`
--  reads at runtime — and not a backfill inventing a decision nobody took.
--
--  A region that could not be attributed to a company through its country is not merely unwanted, it is
--  UNREPRESENTABLE: it would need a `company_country_id` that no `company_countries` row answers, and the
--  foreign key this migration replaces forbids exactly that. That is why this unit needed no
--  destructive authorization, and why its `NOT NULL` finds no row it must reject.
--
--  The `UPDATE` is not dead code even where the table is empty. A database an operator filled by hand,
--  or one this API has written to since V1, reaches this migration with rows, and those rows are exactly
--  the ones the `NOT NULL` below would otherwise refuse. Deleting the `UPDATE` because "there is nothing
--  to derive" is the change that breaks on the only database that matters — the argument V43's header
--  makes for its own two derivations.
--
-- ============================================
--
-- WHY
-- ---
-- V44 added `company_regions.company_id` as a NULLABLE column and stopped there on purpose: a `NOT NULL`
-- column cannot arrive before the write surface carries the value. The mapping carries it now —
-- `CompanyRegion.deriveCompanyFromCountry()` reads it from the country at persist time — and this
-- migration turns that from a mapping detail into a rule the database enforces, so a later service or
-- mapping change cannot reopen the gap by forgetting it.
--
-- WHY THE COMPOSITE KEY IS THE POINT
-- ----------------------------------
-- `(company_id, company_country_id) -> company_countries (company_id, id)` makes a disagreeing pair
-- unrepresentable, and it does something the plain key could not: it is the target that lets the ZONE
-- enforce the same rule one level down, through `uq_company_regions_company_id` (V44). Without it, a
-- zone could point at a region of another company and nothing would notice; with it, the chain pins every
-- level to the one above.
--
-- WHY THE PLAIN FOREIGN KEY GOES
-- ------------------------------
-- Each constraint is checked on its own, so `company_regions_company_country_id_fkey` standing beside
-- `fk_company_regions_country_same_company` would still admit the pair the composite one refuses: the
-- plain key only proves the country exists, not that it belongs to this company. Dropping it makes the
-- composite key the ONLY path from a region to a company country, which is the difference between a rule
-- and a suggestion. V43 makes the same argument for `purchase_orders.supplier_id` and
-- `product_suppliers.supplier_id`.
--
-- WHY NO SEPARATE `fk_company_regions_company` TO `companies`
-- -----------------------------------------------------------
-- The composite key already makes `company_id` a real company: it has to equal the company of a row of
-- `company_countries`, which carries its own `company_id` foreign key to `companies`. A third constraint
-- would answer the same question twice and pay write cost for it — V43 refuses the same redundancy for
-- the same reason.
--
-- Forward-only. Undoing it by hand means dropping `fk_company_regions_country_same_company`, re-adding
-- `company_regions_company_country_id_fkey` (measured, not guessed: that is the name V1's inline
-- `REFERENCES` produced), and dropping `company_regions.company_id` with `uq_company_regions_company_id`.
-- No row is lost in either direction.
-- ============================================

-- ─── the owner is a fact of the row's own country ────────────────────────────
UPDATE company_regions r
SET company_id = cc.company_id
FROM company_countries cc
WHERE cc.id = r.company_country_id;

ALTER TABLE company_regions ALTER COLUMN company_id SET NOT NULL;

-- A plain FK beside a composite one still admits the pair the composite refuses.
ALTER TABLE company_regions DROP CONSTRAINT company_regions_company_country_id_fkey;

ALTER TABLE company_regions
    ADD CONSTRAINT fk_company_regions_country_same_company
    FOREIGN KEY (company_id, company_country_id)
    REFERENCES company_countries (company_id, id);
