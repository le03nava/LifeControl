-- ============================================
-- V23 — Employee store assignments
-- ============================================
-- The fact this table records: since when and until when a person works in a store. The store tree
-- answers everything else (company, country, region, zone) and is therefore NEVER duplicated here:
-- a stored ancestor could disagree with the tree after a reorganisation, and the disagreement would
-- be invisible in a column that feeds an authorization decision.
-- Several stores at once are legal (a supervisor, a relief cashier), so the invariant is per
-- (employee, store) and not per employee — see T2. It reuses the partial-exclusion idiom of
-- V21__employee_contracts.sql, and therefore depends on the `btree_gist` extension that migration
-- creates; V20 creates neither the extension nor `employee_contracts`.
-- `employee_contracts`' rules apply unchanged: no `version` (the only mutation is closing), and
-- opening a new row for the same store closes the previous one the day before, in the same
-- transaction. Foreign keys are unnamed, matching the baseline and the V9..V22 style; the CHECK and
-- the exclusion constraint are named so a violation identifies the rule it broke.
-- ============================================

CREATE TABLE employee_store_assignments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_id UUID NOT NULL REFERENCES employees(id),
    company_store_id UUID NOT NULL REFERENCES company_stores(id),
    valid_from DATE NOT NULL,
    valid_to DATE,
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_employee_store_assignments_dates CHECK (valid_to IS NULL OR valid_to >= valid_from),
    CONSTRAINT ex_employee_store_assignments_no_overlap
        EXCLUDE USING gist (employee_id WITH =, company_store_id WITH =, daterange(valid_from, valid_to, '[)') WITH &&)
        WHERE (enabled)
);
CREATE INDEX idx_employee_store_assignments_employee_id ON employee_store_assignments(employee_id);
CREATE INDEX idx_employee_store_assignments_company_store_id ON employee_store_assignments(company_store_id);
