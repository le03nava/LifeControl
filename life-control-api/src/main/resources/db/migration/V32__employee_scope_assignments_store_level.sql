-- ============================================
-- V32 — The store becomes a level of employee_scope_assignments
-- ============================================
-- Why: the per-level scope screen and the store screen were two halves of one question ("what tenancy
-- is this person bounded by?"), so the same class of fact lived in two tables with the same shape — a
-- node, a `[)` date range, an `enabled` flag and a close-instead-of-edit history. V26 already declared
-- the level to be the row's shape; this migration widens that shape from three node columns to four,
-- the fourth being the store, and copies every live store assignment into it.
--
-- `employee_store_assignments` (V23) is NOT dropped here. Keeping it for one more migration is what
-- lets this branch stay deployable at every commit: the store HTTP surface keeps answering from it
-- until the application reads and writes only this table, and the drop is its own reviewed step
-- (V33). Nothing writes to the old table after the application change lands, so the two copies cannot
-- diverge — the copy below is the source's last word.
--
-- The backfill **preserves the row id**, its dates, its `enabled` flag and its close state verbatim: a
-- row keeps the identity it had, which makes the copy auditable against the source and keeps a support
-- reference to a row valid. `created_at`/`updated_at` are copied too, so the history does not claim
-- the migration's timestamp.
--
-- Fail closed: the guard compares the number of store rows the new table holds against the number of
-- rows the old table holds and raises, aborting the migration, when they differ. A partial copy is a
-- silent loss of an authorization input — the worst failure this table can have — so it must fail the
-- deploy instead of succeeding quietly. The count is read from `company_store_id IS NOT NULL`, not
-- through a join, because the source table permits two *disabled* rows for one `(employee, store)`
-- pair at the same start date (its exclusion constraint is partial on `enabled`) and a join would
-- count them twice.
--
-- The fourth exclusion constraint repeats V26's idiom and names its level, like the other three: the
-- invariant is per `(employee, node)` and never per employee, several stores at once are legal, and
-- `WHERE (enabled AND company_store_id IS NOT NULL)` keeps a soft-deleted row from blocking its
-- replacement and keeps the other levels' NULLs out of this level's index.
-- ============================================

ALTER TABLE employee_scope_assignments ADD COLUMN company_store_id UUID REFERENCES company_stores(id);

ALTER TABLE employee_scope_assignments DROP CONSTRAINT ck_employee_scope_assignments_one_node;
ALTER TABLE employee_scope_assignments
    ADD CONSTRAINT ck_employee_scope_assignments_one_node
    CHECK (num_nonnulls(company_country_id, company_region_id, company_zone_id, company_store_id) = 1);

ALTER TABLE employee_scope_assignments
    ADD CONSTRAINT ex_employee_scope_assignments_store_no_overlap
    EXCLUDE USING gist (employee_id WITH =, company_store_id WITH =, daterange(valid_from, valid_to, '[)') WITH &&)
    WHERE (enabled AND company_store_id IS NOT NULL);

CREATE INDEX idx_employee_scope_assignments_company_store_id ON employee_scope_assignments(company_store_id);

DO $$
DECLARE
    source_rows BIGINT;
    copied_rows BIGINT;
BEGIN
    SELECT count(*) INTO source_rows FROM employee_store_assignments;

    INSERT INTO employee_scope_assignments (
        id, employee_id, company_store_id, valid_from, valid_to, enabled, created_at, updated_at)
    SELECT id, employee_id, company_store_id, valid_from, valid_to, enabled, created_at, updated_at
    FROM employee_store_assignments;

    SELECT count(*) INTO copied_rows
    FROM employee_scope_assignments
    WHERE company_store_id IS NOT NULL;

    IF copied_rows <> source_rows THEN
        RAISE EXCEPTION
            'employee_store_assignments backfill is incomplete: % source rows, % store rows in employee_scope_assignments',
            source_rows, copied_rows;
    END IF;
END
$$;
