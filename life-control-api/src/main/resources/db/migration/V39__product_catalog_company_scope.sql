-- ============================================
-- V39 — Product catalog, step 1 of 2: the company column
-- ============================================
-- `products` has been GLOBAL since V1 and `product_variants` was made the GLOBAL sellable definition
-- on purpose by V14 (D2/D5), while the classification tree it is filed under became company-scoped in
-- V31. The two halves of the same aggregate therefore disagree about who owns a product, and each
-- disagreement has a measured cost:
--
--   * `GET /api/products` has no company segment and no `verifyCompanyAccess`, so a role is the only
--     gate and one tenant's catalog operator reads another tenant's products.
--   * `products.sku` (V1) and `product_variants.bar_code` (V14) are globally unique, so two separate
--     businesses cannot both sell SKU `TENIS-01`, nor a barcode that legitimately repeats across them.
--   * `products.category_id` (V36) is a plain FK chosen deliberately *because* the product had no
--     company, so a product of company A can currently be classified under a category of company B.
--
-- This migration is the EXPAND step: it adds the column and nothing else. It is nullable here and
-- becomes `NOT NULL` in V40, together with the composite foreign keys and the per-company unique keys,
-- because a `NOT NULL` column cannot arrive before the write surface knows where the company comes
-- from — a create path that sets nothing would turn every existing product write into a 500, and the
-- suite is green after every slice of this unit or the slice is not finished.
--
-- NO BACKFILL, by decision: there is no production database to preserve, and a row that cannot be
-- attributed to a company is discarded rather than invented. That discard is a `TRUNCATE` and it
-- belongs to V40, where the constraint that makes it necessary is asserted; putting it here would
-- delete rows one slice before anything requires them to be gone.
--
-- Indexes: NONE, and the reason is V31's. `product_categories` needed `UNIQUE (company_id, id)` because
-- it is the TARGET of a composite foreign key, and a target must be unique; `products` and
-- `product_variants` get the same key in V40 for the same reason, and a unique constraint's leading
-- column already serves the lookup by company. A plain `idx_...(company_id)` next to it would be a
-- second structure answering the same read, which is the cost V34 and V37 both refused to pay.
--
-- Forward-only and additive: two `ADD COLUMN`s and two nullable foreign keys. Every existing row keeps
-- `NULL`, which is the state an unclassified product is already in, and dropping the two columns
-- restores V1's and V14's shapes exactly.
-- ============================================

ALTER TABLE products ADD COLUMN company_id UUID REFERENCES companies(id);

ALTER TABLE product_variants ADD COLUMN company_id UUID REFERENCES companies(id);
