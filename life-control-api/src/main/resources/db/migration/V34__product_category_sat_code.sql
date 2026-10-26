-- ============================================
-- V34 — Product categories: the optional SAT product-service key
-- ============================================
-- `sat_code` is the SAT `ClaveProdServ` a CFDI line needs. It lives on the category as well as on the
-- product because the category is what an operator classifies with: asking for the fiscal key at the
-- classification level is what makes it possible to prefill it per product instead of retyping the
-- same eight digits (the product -> category bridge is still its own deferred slice).
--
-- Shape, and each half is a decision:
--
--   * NULLABLE, unlike `measure_units.sat_code` (which is `NOT NULL UNIQUE`). A measure unit *is* a SAT
--     catalog entry, so its key is its identity. A category is a company's own classification that
--     *may* name a fiscal key: a category created by hand before the operator looks the key up has to
--     stay savable, so the column is optional and the API validates the shape only when it is present.
--   * NOT UNIQUE. The SAT catalog legitimately repeats a key across levels — `53110000` is the whole
--     footwear segment and every footwear branch carries it — so a unique constraint would reject the
--     very starter catalog V35 inserts.
--   * `VARCHAR(8)` plus a named `CHECK`, not `CHAR(8)`. `CHAR` pads on read, and the `CHECK` is what
--     makes "optional but validated" hold for any writer that bypasses the API, which is exactly the
--     failure the `CHECK` on `positions.reports_to_parent` already closes. The constraint is NAMED so a
--     violation names the rule it broke, like `fk_product_categories_parent_same_company` in V31.
--
-- No index: the company tree is bounded at three levels and a handful of rows per company, so a lookup
-- by `sat_code` is a sequential scan over a table that fits in one page. An index here would be a
-- second structure to keep honest for no read it serves. No `NOT NULL`, no default and no backfill: an
-- existing row keeps `NULL`, which is the same state a new category without a key is in.
--
-- Forward-only and additive: `ADD COLUMN` plus a validating `CHECK` on a table whose every existing row
-- has no key. Dropping the column restores V31's shape exactly, so nothing has to be re-read to undo it.
-- ============================================

ALTER TABLE product_categories ADD COLUMN sat_code VARCHAR(8);

ALTER TABLE product_categories
    ADD CONSTRAINT ck_product_categories_sat_code_format
    CHECK (sat_code IS NULL OR sat_code ~ '^[0-9]{8}$');
