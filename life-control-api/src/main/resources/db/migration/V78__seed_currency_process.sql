-- ============================================
-- V78 — Seed Data: CURRENCY, the process the currency catalog endpoint derives
-- ============================================
-- The activity audit aspect derives the process name from the controller's package segment after
-- `com.lifecontrol.api.` (uppercased) — `currency.controller` -> CURRENCY — and `ActivityLogService.save`
-- discards any entry whose process is absent from `activity_processes`, with only a WARN. A discarded
-- entry is indistinguishable from one that was never produced.
--
--   CURRENCY -> `currency/controller` (the read-only platform currency catalog, `GET /api/currencies`),
--               added by `odd/tasks/company-currency-form.md` so the companies form can offer the codes a
--               company's operating currency may be picked from.
--
-- Without this row every read of the catalog would be logged and then dropped, silently. The guard is
-- `ActivityLogIntegrationTest`, which derives the process names from the production controller packages
-- instead of listing them: it went red on exactly this name before the row existed, which is the guard
-- doing the job V27 describes.
--
-- Idempotent: guarded on the natural key (name), the same shape V4, V16 and V27 use.
-- ============================================

INSERT INTO activity_processes (id, name, created_at)
SELECT gen_random_uuid(), 'CURRENCY', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM activity_processes WHERE name = 'CURRENCY');
