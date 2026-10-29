-- ============================================
-- V49 — Store hierarchy, level 3 of 3, step 2 of 2: the store's company contract
-- ============================================
--
--  NOTHING IS DISCARDED HERE, AND THIS HEADER HAS TO SAY WHY
--
--  Every store already carries an owner: `company_zones.company_id` became `NOT NULL` in V47, and
--  `company_stores.company_zone_id` is a `NOT NULL` foreign key to it. The `UPDATE` below is therefore a
--  DERIVATION of a fact the row holds — the last hop of the walk `CompanyStoreOwnerResolver` performs at
--  runtime — and not a backfill inventing a decision nobody took.
--
--  A store that could not be attributed to a company through its zone is UNREPRESENTABLE: it would need a
--  `company_zone_id` that no `company_zones` row answers, and the foreign key this migration replaces
--  forbids exactly that. So this level needs no destructive authorization either, and its `NOT NULL`
--  finds no row it must reject. That is the whole difference between this chain and V40/V42, whose tables
--  carried no owner at all.
--
--  The `UPDATE` is not dead code where the table is empty: an operator's database, or one this API has
--  written to since V1, reaches this migration with rows — inventory, receipts, store settings and
--  scheduling rows all hang off the stores it holds — and those are exactly the rows the `NOT NULL` would
--  otherwise refuse. Deleting the `UPDATE` because "there is nothing to derive" is the change that breaks
--  on the only database that matters (V43, V45 and V47's headers make the same argument).
--
-- ============================================
--
-- WHY
-- ---
-- V48 added `company_stores.company_id` as a NULLABLE column and stopped there on purpose. The mapping
-- carries the value now — `CompanyStore.deriveCompanyFromZone()` reads it from the zone at persist time —
-- and this migration turns that mapping detail into a rule the database enforces, so a later service or
-- mapping change cannot reopen the gap by forgetting it. With it, the three levels pin each other: a store
-- cannot name another company's zone, a zone another company's region, a region another company's country.
--
-- WHY THIS IS THE LEVEL THAT CLOSES THE AXIS
-- ------------------------------------------
-- `uq_company_stores_company_id` (V48) plus this key are the pair V43:70-71 says the database lacks:
-- with them, `fk_purchase_orders_store_same_company` `(company_id, company_store_id) -> company_stores
-- (company_id, id)` becomes expressible, so a purchase order's company is pinned to its store by the
-- schema and not only by `PurchaseOrderService`. That migration is V50, on purpose one step away: this one
-- leaves the store consistent with its own parent first.
--
-- WHY THE PLAIN FOREIGN KEY GOES
-- ------------------------------
-- Each constraint is checked on its own, so `company_stores_company_zone_id_fkey` beside
-- `fk_company_stores_zone_same_company` would still admit the pair the composite one refuses: the plain
-- key only proves the zone exists, not that it belongs to this company. Dropping it makes the composite
-- key the ONLY path from a store to a company zone (V43, V45 and V47's siblings).
--
-- WHICH FOREIGN KEYS POINTING AT THIS TABLE ARE NOT TOUCHED
-- --------------------------------------------------------
-- `purchase_orders.company_store_id` is V50's, and only V50's. Every other table that names a store —
-- inventory (V10), store inventory settings (V11), goods receipts (V12), product variant store stock
-- (V13), the scheduling tables (V16, V18) and the employee scope assignments (V32) — keeps its plain key:
-- their tenancy is established through the store, and re-keying them here would widen this unit past what
-- issue #673 asks for.
--
-- Forward-only. Undoing it by hand means dropping `fk_company_stores_zone_same_company`, re-adding
-- `company_stores_company_zone_id_fkey` (measured, not guessed: the name V1's inline `REFERENCES`
-- produced), and dropping `company_stores.company_id` with `uq_company_stores_company_id`. No row is lost
-- in either direction.
-- ============================================

-- ─── the owner is a fact of the row's own zone ───────────────────────────────
UPDATE company_stores s
SET company_id = z.company_id
FROM company_zones z
WHERE z.id = s.company_zone_id;

ALTER TABLE company_stores ALTER COLUMN company_id SET NOT NULL;

-- A plain FK beside a composite one still admits the pair the composite refuses.
ALTER TABLE company_stores DROP CONSTRAINT company_stores_company_zone_id_fkey;

ALTER TABLE company_stores
    ADD CONSTRAINT fk_company_stores_zone_same_company
    FOREIGN KEY (company_id, company_zone_id)
    REFERENCES company_zones (company_id, id);
