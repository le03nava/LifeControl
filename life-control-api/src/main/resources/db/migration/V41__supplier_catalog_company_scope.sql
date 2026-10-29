-- ============================================
-- V41 — Supplier catalog, step 1 of 2: the company column
-- ============================================
-- `suppliers` has been GLOBAL since V1 and nothing has ever narrowed it, while `products` completed the
-- same move in V39/V40 and `product_categories` became company-scoped in V31. Every cost V39 names in
-- its own header applies here unchanged, and each one is measured:
--
--   * `GET /api/suppliers` has no company segment and no `verifyCompanyAccess`: both reads are
--     `@PreAuthorize("isAuthenticated()")`, so any authenticated user of any tenant lists and reads
--     every tenant's suppliers, and creates, edits or deletes them holding `lc-admin` or
--     `lc-product-supplier`.
--   * `suppliers.rfc` (V1) is globally unique, so two separate businesses cannot both register the same
--     RFC. That is the same collision V39 names for `products.sku` and `product_variants.bar_code`.
--   * The two places that NAME a supplier cannot ask who owns it: `purchase_orders.supplier_id` (V1) and
--     `product_suppliers.supplier_id` (V1) are plain foreign keys, so a purchase order of company A can
--     name a supplier only company B created, and a product→supplier link is scoped only *indirectly*,
--     through the product's company.
--
-- This migration is the EXPAND step: it adds the column, its foreign key and the unique target a
-- composite key will need, and nothing else. The column is NULLABLE here and becomes `NOT NULL` in V42,
-- together with the composite foreign keys and the per-company unique key — because, as V39 states, a
-- `NOT NULL` column cannot arrive before the write surface knows where the company comes from: a create
-- path that sets nothing would turn every existing supplier write into a 500, and the suite is green
-- after every slice of this unit or the slice is not finished.
--
-- NO BACKFILL, by decision: there is no production database to preserve, and a supplier row that cannot
-- be attributed to a company is discarded rather than given an invented owner. That discard is a
-- `TRUNCATE` and it belongs to V42, where the constraint that makes it necessary is asserted; putting it
-- here would delete rows one slice before anything requires them to be gone.
--
-- WHY `UNIQUE (company_id, id)`: it is the TARGET of the composite foreign keys V42 adds
-- (`purchase_orders` and `product_suppliers` must not be able to name another company's supplier), and a
-- target must be unique. `product_categories` needed the same key in V31 for the same reason, and a
-- unique constraint's leading column already serves the lookup by company, so no separate
-- `idx_suppliers_company_id` is added: a second structure answering the same read is the cost V34 and
-- V37 both refused to pay.
--
-- Forward-only and additive: one nullable `ADD COLUMN` plus two constraints. Every existing row keeps
-- `NULL`, which is what an unattributed supplier is for exactly one slice, and dropping the column
-- restores V1's shape exactly.
-- ============================================

ALTER TABLE suppliers ADD COLUMN company_id UUID;

ALTER TABLE suppliers
    ADD CONSTRAINT fk_suppliers_company FOREIGN KEY (company_id) REFERENCES companies(id);

ALTER TABLE suppliers
    ADD CONSTRAINT uq_suppliers_company_id UNIQUE (company_id, id);
