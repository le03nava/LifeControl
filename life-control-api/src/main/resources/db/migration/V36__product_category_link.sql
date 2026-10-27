-- ============================================
-- V36 — Products: the link to the company's product category
-- ============================================
-- A product carries `sat_code` as free text since V1 and had no link to `product_categories` at all,
-- so the classification the catalog encodes (V31, `sat_code` per node since V34) was unreachable from
-- the entity that needs it: the fiscal key was retyped per product instead of being suggested by the
-- category the operator already picked.
--
-- Shape, and each half is a decision:
--
--   * ONE nullable FK to the deepest selected node, **not three columns** (`categoria`,
--     `subcategoria`, `sub-subcategoria`). Three columns make states the tree forbids representable —
--     a level-2 id that is not the child of the stored level-1 id — and policing that on every write
--     would be an application rule where a structural one is available. With one link the path is
--     read by walking `parent_category_id` up, which is the same thing the API already answers with
--     `depth` and `parentCategoryId`.
--   * Any level is a legal stopping point. A root with no children is a complete classification, so
--     what gets stored is the deepest *selected* node, not a required three-deep path.
--   * The FK is **not** company-scoped, because `products` has no `company_id` (V1): a product is
--     global while a category belongs to a company. The link therefore points at exactly one real row
--     of exactly one company — cross-tenant state is structurally impossible without a claim to check
--     — and the company a product is classified under is derived from its category, not stored twice.
--   * No `ON DELETE` action. `DELETE /product-categories/{id}` is a soft delete (V31: `enabled`), so no
--     hard delete of a category exists in the API; the default `NO ACTION` is the guard that keeps a
--     hypothetical direct SQL delete from orphaning a product's classification.
--   * **No index on `category_id`.** The two sides of a foreign key need different things, and only one
--     of them is missing: the *referenced* side needs uniqueness to be a legal target, and
--     `product_categories.id` is already that table's primary key (V31); the *referencing* side is what
--     a delete or a key update on the parent has to scan, so an index would speed up
--     `DELETE FROM product_categories`. It is skipped because no such delete exists: the category API
--     soft-deletes (`enabled = false`), and the raw-SQL path is exactly what `NO ACTION` above is meant
--     to refuse, not to make fast. Nothing reads products by category yet either. The same reasoning V34
--     used to skip an index on `product_categories.sat_code` applies — a second structure to keep honest
--     for no query it serves is a cost, not a feature — and it arrives with the first query that filters
--     by it, or with the first hard delete, whichever comes first.
--
-- Forward-only and additive: `ADD COLUMN` on a table whose every existing row keeps `NULL`, which is
-- the same state a product that nobody classified is in. Dropping the column restores V1's shape for
-- `products` exactly, so no row has to be re-read to undo it.
-- ============================================

ALTER TABLE products ADD COLUMN category_id UUID;

ALTER TABLE products
    ADD CONSTRAINT fk_products_category
    FOREIGN KEY (category_id) REFERENCES product_categories (id);
