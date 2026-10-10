-- ============================================
-- V25 — Employee access provisioning reviewed set (access_provisioning_reviewed_roles)
-- ============================================
-- T11's frozen diff gets its own persistence (decision T71). A gated task
-- (`status = 'APPROVAL_PENDING'`) freezes, at creation, the role diff a human is asked to approve:
-- one row per role the diff touches, with its direction. V22 carries no diff column at all, and the
-- only persisted set until now was `access_provisioning_applied_roles(task_id, role_name)` — written
-- at `APPLIED` by `markApplied`, with no direction — so "the diff is computed and stored on every
-- task" was a promise the schema never carried and the Screens row's "diff frozen for review" had
-- nowhere to live.
-- The frozen set is the DIFF and not the two raw sets (decision T71): `GRANT` / `REVOKE` is what a
-- human reviews and what the apply compares against, diff-to-diff. Storing the required set and the
-- account's current set instead would double the rows to reconstruct something the comparison never
-- uses; and the comparison must not be set-to-set, because a change that does not move the diff (a
-- role that is both required and already present) must not re-gate.
-- The row is written in the same transaction as its task, so `task_id` is NOT NULL and the foreign
-- key is unnamed, matching the baseline and the V9..V24 style.
-- `direction` is a plain `VARCHAR(10)` with no constraint, exactly like `kind` and `status` in V22:
-- its value set is the Java enum `AccessProvisioningRoleDirection` under
-- `@Enumerated(EnumType.STRING)` and nothing else. V22 states this schema's stance — "zero
-- `CHECK (col IN ...)` and zero native enums" — and a diff direction is the sibling of a task
-- status, so V25 keeps `direction` on that same footing instead of making it the schema's exception.
-- The row is written once and the whole set is replaced when the task returns to the gate (decision
-- T72), so there is deliberately NO `updated_at` and no `version`: an `updated_at` would suggest a
-- mutation of a row that is never mutated, and a set replaced whole needs no optimistic-locking
-- precondition. `V8__store_optimistic_locking.sql` is the precedent for adding one later if that
-- decision changes.
-- Named are the child's UNIQUE constraint (mirroring `uq_access_provisioning_applied_roles`) and its
-- index, so a violation identifies the rule it broke.
-- NOT in this migration: any seed, any backfill (there is nothing to backfill, the table is new), and
-- any transition logic. The gate that writes these rows and the re-gate check that replaces them are
-- Java's.
-- ============================================

CREATE TABLE access_provisioning_reviewed_roles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id UUID NOT NULL REFERENCES access_provisioning_tasks(id),
    role_name VARCHAR(100) NOT NULL,
    direction VARCHAR(10) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_access_provisioning_reviewed_roles UNIQUE (task_id, role_name)
);
CREATE INDEX idx_access_provisioning_reviewed_roles_task_id ON access_provisioning_reviewed_roles(task_id);
