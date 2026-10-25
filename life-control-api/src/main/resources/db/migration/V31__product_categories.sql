-- ============================================
-- V31 — Product categories (company-scoped category tree)
-- ============================================
-- `product_categories` is the classification tree for the product catalog: one row per category,
-- `parent_category_id` pointing at its parent and NULL marking a root, so the depth is data rather
-- than a set of columns. The tree is company-scoped (decision D1 of
-- `odd/tasks/product-categories-backend.md`): the categories belong to a company even though
-- `products` itself is still global.
--
-- The tenancy guarantee is structural, not a service promise. `UNIQUE (company_id, id)` makes the
-- pair addressable, and the composite foreign key
-- `(company_id, parent_category_id) -> (company_id, id)` is what a cross-company parent has to
-- violate: with `MATCH SIMPLE` (the default) a NULL `parent_category_id` — a root — skips the check
-- entirely, and a non-NULL parent has to live in the same company. The service also validates it,
-- but a future service change cannot disable this.
--
-- That composite key is why `fk_product_categories_parent_same_company` is the one NAMED foreign key
-- in this schema, where `V9`..`V29` leave FKs unnamed: a violation has to name the rule it broke, or
-- the operator reads a generic constraint error and loses the tenancy context.
--
-- `uq_product_categories_sibling_name` is an EXPRESSION index and not a table constraint, and that is
-- the point: `UNIQUE (company_id, parent_category_id, lower(category_name))` would not stop two ROOTS
-- with the same name, because `NULL <> NULL` in SQL — the constraint is satisfied whenever both rows
-- have no parent. `COALESCE(parent_category_id, <zero uuid>)` turns the missing parent into an
-- ordinary value, so roots are siblings of each other and are guarded like any other level.
--
-- The depth ceiling (3 levels: category -> subcategory -> sub-subcategory, decision D2) is NOT here:
-- a depth CHECK cannot be expressed without a trigger. The schema resolved exactly this problem the
-- same way once before (`positions.reports_to_position_id`, V19): the CHECK closes the trivial cycle
-- — a row cannot be its own parent — and the service closes the deep one (a node cannot be moved
-- under its own descendant) and the ceiling.
--
-- No `version` column: a catalog carries no optimistic-locking precondition, the same decision
-- `departments` records. No seed and no backfill: the feature starts from an empty tree.
--
-- Forward-only and additive: no existing table is altered, so nothing has to be rolled back if this
-- migration is reverted by hand — dropping the table drops the whole feature.
-- ============================================

CREATE TABLE product_categories (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id UUID NOT NULL REFERENCES companies(id),
    parent_category_id UUID,
    category_name VARCHAR(100) NOT NULL,
    description VARCHAR(255),
    display_order INTEGER NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_product_categories_company_id UNIQUE (company_id, id),
    CONSTRAINT ck_product_categories_not_self_parent
        CHECK (parent_category_id IS NULL OR parent_category_id <> id),
    CONSTRAINT fk_product_categories_parent_same_company
        FOREIGN KEY (company_id, parent_category_id)
        REFERENCES product_categories (company_id, id)
);

-- Sibling names are unique per company, roots included: see the header.
CREATE UNIQUE INDEX uq_product_categories_sibling_name
    ON product_categories (
        company_id,
        COALESCE(parent_category_id, '00000000-0000-0000-0000-000000000000'::uuid),
        lower(category_name)
    );

CREATE INDEX idx_product_categories_company_id ON product_categories(company_id);
CREATE INDEX idx_product_categories_parent_category_id ON product_categories(parent_category_id);
