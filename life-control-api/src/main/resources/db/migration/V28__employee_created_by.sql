-- ============================================
-- V28 — employees.created_by: who created the employee
-- ============================================
-- The column answers exactly one question: who created this employee. The employee-create path
-- (EmployeeService.createEmployee) writes the authenticated actor's username into it — the same
-- actor source and representation the codebase already uses for inventory_movements.created_by
-- (CurrentUserContext.getUsername()).
--
-- It is written ONCE, at creation, and never updated. There is no update path: the employee edit,
-- the enable route and the status writes leave it untouched, and the entity maps the column with
-- updatable = false, so Hibernate cannot emit it in an UPDATE even by accident. No request record
-- carries the field either, so the value is readable and never typable.
--
-- Nullable with no default, on purpose: every row that existed before this migration keeps NULL,
-- because its creator was never recorded. Nothing is backfilled and no value is invented — a
-- guessed creator is worse than an honest NULL, and only the create path can ever fill it.
-- ============================================

ALTER TABLE employees ADD COLUMN created_by VARCHAR(255);
