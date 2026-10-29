-- ============================================
-- V43 — The two consumers of the supplier catalog: the company contract
-- ============================================
--
--  NOTHING IS DISCARDED HERE, AND THIS HEADER HAS TO SAY WHY
--
--  Unlike V40 and V42, this migration removes no row. Those two discarded because no existing row
--  carried an owner at all and one could not be invented (S2, S5). Here every existing row <b>does</b>
--  have an owner: it is a fact of the row's own parent, and the two `UPDATE`s below read it from
--  there. That is the whole reason this migration can be honest and non-destructive at the same
--  time — and why a reviewer should read those `UPDATE`s as derivations, not as backfills pretending
--  a decision was never needed.
--
--  They are not dead code even though V42's `TRUNCATE suppliers CASCADE` emptied both of these
--  tables one migration ago: a database the API has written to since (or one an operator filled by
--  hand) reaches this migration with rows, and those rows are exactly the ones the `NOT NULL` below
--  would otherwise reject. Deleting the `UPDATE`s because "the tables are empty" is the change that
--  breaks on the only database that matters.
--
-- ============================================
--
-- WHY
-- ---
-- V41's header named the two places that cannot ask who owns the supplier they name: `purchase_orders
-- .supplier_id` (V1:320) and `product_suppliers.supplier_id` (V1:207) are plain foreign keys, so a
-- purchase order of company A could name a supplier only company B created, and a product→supplier
-- link was scoped only *indirectly*, through the product's company. V42's header then deferred their
-- composite keys to this migration, because a composite key needs a `company_id` column on each of
-- these two tables to key on and neither carried one. This migration adds both columns and both keys.
--
-- The service already refuses both pairs today: `ProductSupplierService.addSupplierToProduct` resolves
-- the supplier inside the product's company, and `PurchaseOrderService` resolves the supplier inside
-- the company of the order's store, before anything is written. Both are one edit away from being
-- forgotten — the shape `V40`'s header calls "a later service change cannot reopen a cross-tenant hole
-- by forgetting one of them". That is what these constraints fix: the rule stops living only in the
-- service.
--
-- WHY EACH OWNER COMES FROM WHERE IT DOES
-- ---------------------------------------
-- `product_suppliers.company_id` is the **product's** company. The relation is an attribute of the
-- product — "this product may be bought from this supplier, at this cost" — so it belongs to whoever
-- owns the product, and the product's own company is already `NOT NULL` and already the target of
-- `uq_products_company_id` (V40). The supplier half is the one being constrained: it has to coincide.
--
-- `purchase_orders.company_id` is the **store's** company. The store is the only thing a purchase
-- order carries that answers which tenant the order belongs to, and `PurchaseOrderService` already
-- orders its validation that way for exactly this reason (store first, supplier second, never the
-- reverse). The derivation is the same walk `CompanyStoreOwnerResolver` performs in code — store →
-- zone → region → country → company, because `company_stores` carries no `company_id` (V1:127-136).
--
-- WHY THE PLAIN FOREIGN KEYS GO
-- ------------------------------
-- Each constraint is checked on its own, so a plain `FOREIGN KEY (supplier_id)` standing beside
-- `FOREIGN KEY (company_id, supplier_id)` would still admit the row the composite one refuses: the
-- plain key only proves the supplier exists, not that it belongs to this company. Dropping it is what
-- makes the composite key the **only** path to `suppliers`, which is the difference between a rule and
-- a suggestion. Same for the product link on `product_suppliers`.
--
-- WHY NO SEPARATE `fk_*_company`
-- -------------------------------
-- The composite keys already make `company_id` a real company — it has to match the company of a row
-- of `products` or `suppliers`, both of which carry their own `company_id` foreign key. A third
-- constraint to `companies(id)` would answer the same question twice and pay write cost for nothing,
-- which is the argument V34, V37 and V40 all record for indexes.
--
-- WHAT THIS SCHEMA STILL CANNOT EXPRESS
-- ------------------------------------
-- "A purchase order of a store of company A carrying `company_id` of company B" stays representable,
-- and it is the one part of this rule that the database does not close. The composite key that would
-- close it — `(company_id, company_store_id) -> company_stores (company_id, id)` — needs a
-- `company_id` on `company_stores` and a `uq_company_stores_company_id` target to reference, and
-- `company_stores` has neither: its company is derived through zone → region → country (V1:127-136),
-- which is exactly the limit V40's header records for the store stock write. Giving that table an
-- owner of its own is a store-tenancy unit, not a line in this migration.
--
-- So the store → company agreement remains a **service** rule, enforced by `PurchaseOrderService`
-- through `CompanyStoreOwnerResolver` in the same breath as the supplier lookup, and the backfill
-- above derives the column the same way. What the database now guarantees is the half that was
-- missing: whatever `company_id` an order holds, the supplier it names is one of that company's.
--
-- Forward-only. Undoing it by hand means dropping `fk_product_suppliers_product_same_company`,
-- `fk_product_suppliers_supplier_same_company` and `fk_purchase_orders_supplier_same_company`,
-- re-adding `product_suppliers_product_id_fkey`, `product_suppliers_supplier_id_fkey` (both measured,
-- not guessed) and `purchase_orders_supplier_id_fkey`, and dropping the two `company_id` columns. No
-- row is lost either way, which is the difference between this migration and the two before it.
-- ============================================

-- ─── product_suppliers: the owner is the product's company ─────────────
ALTER TABLE product_suppliers ADD COLUMN company_id UUID;

UPDATE product_suppliers ps
SET company_id = p.company_id
FROM products p
WHERE p.id = ps.product_id;

ALTER TABLE product_suppliers ALTER COLUMN company_id SET NOT NULL;

-- A plain FK beside a composite one still admits the pair the composite refuses.
ALTER TABLE product_suppliers DROP CONSTRAINT product_suppliers_product_id_fkey;
ALTER TABLE product_suppliers DROP CONSTRAINT product_suppliers_supplier_id_fkey;

ALTER TABLE product_suppliers
    ADD CONSTRAINT fk_product_suppliers_product_same_company
    FOREIGN KEY (company_id, product_id)
    REFERENCES products (company_id, id);

ALTER TABLE product_suppliers
    ADD CONSTRAINT fk_product_suppliers_supplier_same_company
    FOREIGN KEY (company_id, supplier_id)
    REFERENCES suppliers (company_id, id);

-- ─── purchase_orders: the owner is the store's company ─────────────────
ALTER TABLE purchase_orders ADD COLUMN company_id UUID;

-- The same walk `CompanyStoreOwnerResolver` performs in code: store → zone → region → country →
-- company. Read from the row's own store, never from the supplier it names: the supplier is the thing
-- being constrained, so taking the owner from it would make the composite key a tautology.
UPDATE purchase_orders po
SET company_id = cc.company_id
FROM company_stores cs
JOIN company_zones cz ON cz.id = cs.company_zone_id
JOIN company_regions cr ON cr.id = cz.company_region_id
JOIN company_countries cc ON cc.id = cr.company_country_id
WHERE cs.id = po.company_store_id;

ALTER TABLE purchase_orders ALTER COLUMN company_id SET NOT NULL;

ALTER TABLE purchase_orders DROP CONSTRAINT purchase_orders_supplier_id_fkey;

ALTER TABLE purchase_orders
    ADD CONSTRAINT fk_purchase_orders_supplier_same_company
    FOREIGN KEY (company_id, supplier_id)
    REFERENCES suppliers (company_id, id);

-- `purchase_orders_company_store_id_fkey` stays exactly as V1 created it, and so does
-- `product_suppliers_product_id_supplier_id_key` (V1:213): the pair a relation cannot repeat is still
-- (product, supplier), and it is already scoped to one product, which belongs to one company.
