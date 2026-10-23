-- ============================================
-- V26 — Employee scope assignments
-- ============================================
-- The fact this table records: since when and until when a person is assigned a company-country, a
-- company-region or a company-zone — the level at which a role such as `lc-company-region` is
-- usable. Until now the five `company_*` claims had exactly one source, `employee_store_assignments`
-- (V23), through the store derivation that walks `store -> zone -> region -> country`; an employee
-- with no store assignment therefore had no country, region or zone claim at all, so a holder of a
-- scoped role saw nothing and had nowhere to be assigned.
-- The node tree answers everything else and is therefore NEVER duplicated here: a row stores the
-- assigned node and no ancestor of it. A stored ancestor could disagree with the tree after a
-- reorganisation, and the disagreement would be invisible in a column that feeds an authorization
-- decision. The derivation resolves the ancestors live, at read time.
-- The level is the row's shape, not a column: exactly one of the three node columns is set, which is
-- what `ck_employee_scope_assignments_one_node` enforces. The three columns are nullable because a row
-- at one level has nothing to say about the other two.
-- There is deliberately NO `COMPANY` level. `employees.company_id` is the company fact, one person
-- belongs to one company, and the company claim is required and exactly one; an assignable company
-- level would either duplicate that fact or force the invariant open, so it is absent rather than
-- nullable-and-unused.
-- Several nodes at once are legal (two regions, a region and an extra zone), so the invariant is per
-- (employee, level, node) and never per employee. It is THREE partial exclusion constraints and not
-- one: a single `EXCLUDE` cannot span three alternative columns — each index is built over one
-- concrete column — so each level gets its own, named so a violation names the level it broke. The
-- idiom and the `btree_gist` dependency are V21's: `daterange(valid_from, valid_to, '[)')` is
-- half-open, and `WHERE (enabled AND <node> IS NOT NULL)` keeps a soft-deleted row from blocking its
-- replacement and keeps the other levels' NULLs out of this level's index.
-- `employee_contracts`' rules apply unchanged: no `version` (the only mutation is closing), and
-- opening a new row for the same node closes the previous one the day before, in the same
-- transaction. Foreign keys are unnamed, matching the baseline and the V9..V25 style; the CHECKs and
-- the exclusion constraints are named so a violation identifies the rule it broke.
-- NOT in this migration: any seed, any backfill, any migration of `employee_store_assignments` (S5
-- keeps that table and its own derivation, and the two sources are unioned in Java), and any
-- transition logic.
-- ============================================

CREATE TABLE employee_scope_assignments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_id UUID NOT NULL REFERENCES employees(id),
    company_country_id UUID REFERENCES company_countries(id),
    company_region_id UUID REFERENCES company_regions(id),
    company_zone_id UUID REFERENCES company_zones(id),
    valid_from DATE NOT NULL,
    valid_to DATE,
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_employee_scope_assignments_dates CHECK (valid_to IS NULL OR valid_to >= valid_from),
    CONSTRAINT ck_employee_scope_assignments_one_node
        CHECK (num_nonnulls(company_country_id, company_region_id, company_zone_id) = 1),
    CONSTRAINT ex_employee_scope_assignments_country_no_overlap
        EXCLUDE USING gist (employee_id WITH =, company_country_id WITH =, daterange(valid_from, valid_to, '[)') WITH &&)
        WHERE (enabled AND company_country_id IS NOT NULL),
    CONSTRAINT ex_employee_scope_assignments_region_no_overlap
        EXCLUDE USING gist (employee_id WITH =, company_region_id WITH =, daterange(valid_from, valid_to, '[)') WITH &&)
        WHERE (enabled AND company_region_id IS NOT NULL),
    CONSTRAINT ex_employee_scope_assignments_zone_no_overlap
        EXCLUDE USING gist (employee_id WITH =, company_zone_id WITH =, daterange(valid_from, valid_to, '[)') WITH &&)
        WHERE (enabled AND company_zone_id IS NOT NULL)
);
CREATE INDEX idx_employee_scope_assignments_employee_id ON employee_scope_assignments(employee_id);
CREATE INDEX idx_employee_scope_assignments_company_country_id ON employee_scope_assignments(company_country_id);
CREATE INDEX idx_employee_scope_assignments_company_region_id ON employee_scope_assignments(company_region_id);
CREATE INDEX idx_employee_scope_assignments_company_zone_id ON employee_scope_assignments(company_zone_id);
