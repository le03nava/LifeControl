-- ============================================
-- V101 — Seed Data: ACCOUNTING, the process the derived book's report endpoints derive
-- ============================================
-- The activity audit aspect derives the process name from the controller's package segment after
-- `com.lifecontrol.api.` (uppercased) — `accounting.controller` -> ACCOUNTING — and
-- `ActivityLogService.save` discards any entry whose process is absent from `activity_processes`, with
-- only a WARN. A discarded entry is indistinguishable from one that was never produced, which is why the
-- seed is not optional.
--
--   ACCOUNTING -> `accounting/controller` (the read side of the derived book: the sales journal, the trial
--                 balance and the gross margin by document and by store, under
--                 `/api/companies/{companyId}/accounting`), added by `B4` — T9 and T10 of
--                 `odd/tasks/derived-accounting.md`, S18.
--
-- This is the third time the same guard caught the same omission, and the third time it was right: `V78`
-- for the currency catalog, `V91` for the tax-code catalog, and this one. `ActivityLogIntegrationTest`
-- scans the production `@RestController` packages instead of listing them, so a controller new enough to
-- have no row goes red at the guard before anything else notices — first as "activity process
-- 'ACCOUNTING' should be seeded", then as the count assertion in
-- `an entry for every derived process is persisted, never discarded`. The full `life-control-api` suite is
-- what surfaces it: the accounting suites alone are green, because none of them loads the audit guard, and
-- that is exactly how it appeared here.
--
-- It is a separate file from the migrations that create and seed the book for the reason `V19`/`V35` are
-- separate from theirs: what an environment needs is the vocabulary of the process, not the accounting
-- slice's schema, and a deployment that already ran `V97`–`V100` still needs this row.
--
-- Idempotent: guarded on the natural key (name), the same shape `V4`, `V16`, `V27`, `V78` and `V91` use.
-- ============================================

INSERT INTO activity_processes (id, name, created_at)
SELECT gen_random_uuid(), 'ACCOUNTING', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM activity_processes WHERE name = 'ACCOUNTING');
