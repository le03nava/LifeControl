-- ============================================
-- V24 — Access provisioning retry deadline (next_attempt_at and its due index)
-- ============================================
-- The durable deadline of a retry (decision T42). Before this migration "retry with backoff"
-- (decisions T10/T13) was a policy with nowhere to live: V22 declared no due-time column and
-- `attempts` was compared against nothing, so a worker had no query to run. The column is a plain
-- `TIMESTAMP` with no time zone, mirroring `requested_at` and `applied_at` in V22 — Flyway writes
-- every timestamp in this schema as `timestamp without time zone`, and `next_attempt_at` is set from
-- a `LocalDateTime` the same way those two are.
-- The invariant, and it is load-bearing: `next_attempt_at` is written only on the `FAILED →
-- PENDING` edge (the reschedule, T46) and is cleared by the claim (`PENDING → RUNNING`). A
-- `RUNNING` row therefore carries no deadline, and a set value means "PENDING and waiting until
-- then". The candidate query's `NULL`-or-due test reads a task that has never failed (`NULL`) as due
-- immediately, without a second column or a sentinel value.
-- The index is partial on `status = 'PENDING'`, the only status the worker claims (T18), and keys on
-- `next_attempt_at`: the deadline is the selective predicate a queue drain applies (`IS NULL OR
-- <= now`, both indexable by a btree), while the partial predicate keeps every non-candidate row out
-- of the index. The `ORDER BY requested_at` the query carries is a sort over the already-filtered
-- candidate set, which is small because at most one open task per employee can exist
-- (`uq_access_provisioning_tasks_open`, V22). NOT in this migration: any backfill (`NULL` is already
-- the correct value for every existing row) and any index on `status` alone.
-- ============================================

ALTER TABLE access_provisioning_tasks ADD COLUMN next_attempt_at TIMESTAMP;

CREATE INDEX idx_access_provisioning_tasks_due ON access_provisioning_tasks(next_attempt_at)
    WHERE status = 'PENDING';
