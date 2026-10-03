-- ============================================
-- V21 — Employee contract history (employee_contracts, the first exclusion constraint)
-- ============================================
-- This migration creates the schema's FIRST PostgreSQL extension and its FIRST exclusion
-- constraint. `btree_gist` supplies the `=` operator class for `uuid` inside a GiST index, which
-- is what lets the constraint index `employee_id` alongside the date range at all.
-- `employee_contracts` is the history: no two ENABLED contracts of one employee may cover the same
-- day. That invariant is the constraint below, and it subsumes "at most one current contract"
-- without a NULL-means-current convention.
-- The constraint is PARTIAL (`WHERE (enabled)`) so a soft-deleted contract stops blocking its
-- replacement. A plain partial INDEX would not do it: an index only makes lookups fast, while the
-- EXCLUDE constraint is what refuses the write, and with the predicate it refuses only the enabled
-- rows.
-- The range is `daterange(start_date, end_date, '[)')`, so the end date is EXCLUSIVE: a contract
-- whose end_date equals the next contract's start_date does not overlap it, and touching ranges are
-- legal. A closed `'[]'` range would make every ordinary hand-off conflict with itself.
-- "Current contract" means the row whose range contains today (record T11), which is exactly why
-- `end_date IS NULL` does NOT mean current: a fixed-term contract with a known end date is still
-- the current one until that date passes.
-- Contracts carry NO `version` column (record T2): their only mutation is being closed once, so
-- there is no optimistic-locking precondition to protect. `employees`, the edited aggregate with a
-- form, is the table that carries it.
-- Foreign keys are unnamed, matching the baseline and the V9..V20 style. The CHECK, UNIQUE and
-- EXCLUDE constraints are named so a violation identifies the rule it broke.
-- ============================================

CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE employee_contracts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_id UUID NOT NULL REFERENCES employees(id),
    position_id UUID NOT NULL REFERENCES positions(id),
    seniority_level_id UUID NOT NULL REFERENCES seniority_levels(id),
    contract_type VARCHAR(30) NOT NULL,
    monthly_salary DECIMAL(12,2) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE,
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_employee_contracts_salary CHECK (monthly_salary >= 0),
    CONSTRAINT ck_employee_contracts_dates CHECK (end_date IS NULL OR end_date >= start_date),
    CONSTRAINT ex_employee_contracts_no_overlap
        EXCLUDE USING gist (employee_id WITH =, daterange(start_date, end_date, '[)') WITH &&)
        WHERE (enabled)
);
CREATE INDEX idx_employee_contracts_employee_id ON employee_contracts(employee_id);
CREATE INDEX idx_employee_contracts_position_id ON employee_contracts(position_id);
CREATE INDEX idx_employee_contracts_seniority_level_id ON employee_contracts(seniority_level_id);
