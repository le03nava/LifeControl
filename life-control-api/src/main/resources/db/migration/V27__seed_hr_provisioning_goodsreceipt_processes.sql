-- ============================================
-- V27 — Seed Data: the three live domains that were never audited
-- ============================================
-- The activity audit aspect derives the process name from the controller's package segment after
-- `com.lifecontrol.api.` (uppercased) — `hr.controller` -> HR — and `ActivityLogService.save`
-- discards any entry whose process is absent from `activity_processes`, with only a WARN. A
-- discarded entry is indistinguishable from one that was never produced, which is the failure mode
-- this migration closes for the three domains that had already shipped:
--
--   HR            -> the nine controllers of `hr/controller` (employees, contracts, positions,
--                    departments, seniority levels, salary bands, store and scope assignments)
--   PROVISIONING  -> `provisioning/controller` (the access-request queue and the employee access
--                    screens)
--   GOODSRECEIPT  -> `goodsreceipt/controller`
--
-- Creating an employee, and asking for a role change on one, left no row in `activity_logs` because
-- of this gap, not because of the aspect.
--
-- The guard is `ActivityLogIntegrationTest`, which derives the process names from the production
-- controller packages instead of listing them, so the next domain added without a seed turns that
-- suite red instead of going dark in silence.
--
-- Idempotent: guarded on the natural key (name), the same shape V4 and V16 use.
-- ============================================

INSERT INTO activity_processes (id, name, created_at)
SELECT gen_random_uuid(), 'HR', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM activity_processes WHERE name = 'HR');

INSERT INTO activity_processes (id, name, created_at)
SELECT gen_random_uuid(), 'PROVISIONING', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM activity_processes WHERE name = 'PROVISIONING');

INSERT INTO activity_processes (id, name, created_at)
SELECT gen_random_uuid(), 'GOODSRECEIPT', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM activity_processes WHERE name = 'GOODSRECEIPT');
