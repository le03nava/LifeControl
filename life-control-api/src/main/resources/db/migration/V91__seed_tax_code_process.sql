-- ============================================
-- V91 — Seed Data: TAX, the process the tax-code catalog endpoint derives
-- ============================================
-- The activity audit aspect derives the process name from the controller's package segment after
-- `com.lifecontrol.api.` (uppercased) — `tax.controller` -> TAX — and `ActivityLogService.save` discards
-- any entry whose process is absent from `activity_processes`, with only a WARN. A discarded entry is
-- indistinguishable from one that was never produced, which is why the seed is not optional.
--
--   TAX -> `tax/controller` (the read-only platform tax-code catalog, `GET /api/tax-codes`), added by
--          `odd/tasks/product-tax-code.md` T2 so the product form can offer the fiscal codes a product may
--          declare.
--
-- This is `V78`'s situation one catalog over, and the same guard caught it the same way:
-- `ActivityLogIntegrationTest#ActivityProcessSeedTests` scans the production `@RestController` packages
-- instead of listing them, and it went red on this name before this row existed — first as "activity
-- process 'TAX' should be seeded", then as the count assertion in
-- `an entry for every derived process is persisted, never discarded`, which is the pair V27 describes.
-- The full `life-control-api` suite was the thing that surfaced it: the focused tag and product suites are
-- green, because neither of them loads the audit guard.
--
-- Idempotent: guarded on the natural key (name), the same shape V4, V16, V27 and V78 use.
-- ============================================

INSERT INTO activity_processes (id, name, created_at)
SELECT gen_random_uuid(), 'TAX', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM activity_processes WHERE name = 'TAX');
