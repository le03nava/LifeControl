-- ============================================
-- V111 — Seed Data: FISCAL, the process the reprint endpoint derives
-- ============================================
-- The activity audit aspect derives the process name from the controller's package segment after
-- `com.lifecontrol.api.` (uppercased) — `fiscal.controller` -> FISCAL — and `ActivityLogService.save`
-- discards any entry whose process is absent from `activity_processes`, with only a WARN. A discarded
-- entry is indistinguishable from one that was never produced, which is why this seed is not optional.
--
--   FISCAL -> `fiscal/controller` (the read side of the fiscal document: the reprint that returns the
--             XML the authority witnessed, under `/api/fiscal-documents`), added by the first half of
--             ADR-0007's A4 slice — `odd/tasks/cfdi-fiscal-document.md`, `S10`.
--
-- This is the **fourth** time the same guard caught the same omission, and the fourth time it was right:
-- `V78` for the currency catalog, `V91` for the tax-code catalog, `V101` for the accounting book, and
-- this one. `ActivityLogIntegrationTest` scans the production `@RestController` packages instead of
-- listing them, so a controller new enough to have no row goes red at the guard before anything else
-- notices.
--
-- **It went red in CI and not locally, and that is the part worth remembering.** The fiscal slice's own
-- suites were green — they filter `'*Fiscal*'`, and none of them loads the audit guard — and the full
-- API suite is what surfaced it. The same run had already caught the mirror-image omission one module
-- away: a controller with no gateway route (`GatewayRouteCoverageTests`, `L17`). Both are inventory
-- guards, and both exist because a new endpoint is never only its own code.
--
-- It is a separate file from the migrations that create the fiscal tables for the reason `V19`/`V35` are
-- separate from theirs: what an environment needs is the vocabulary of the process, not the slice's
-- schema, and a deployment that already ran `V108`–`V110` still needs this row.
--
-- Idempotent: guarded on the natural key (name), the same shape `V4`, `V16`, `V27`, `V78`, `V91` and
-- `V101` use.
-- ============================================

INSERT INTO activity_processes (id, name, created_at)
SELECT gen_random_uuid(), 'FISCAL', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM activity_processes WHERE name = 'FISCAL');
