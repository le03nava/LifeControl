-- ============================================
-- V22 — Employee access provisioning tasks (access_provisioning_tasks, its first partial UNIQUE index)
-- ============================================
-- The durable intent of the employee-access flow: one row is written in the same transaction that
-- asserts an organisational fact (a contract activation, a store-assignment change, a termination)
-- and a worker resolves it by RECONCILING Keycloak against the current truth (decision T2). The row
-- is also the audit (decision T9): the existing `activity_logs` is HTTP-shaped (`http_method`,
-- `http_status`, `request_path`, `payload_json`) and cannot answer "who approved this grant for this
-- employee", so `requested_by` / `requested_at` / `decided_by` / `decided_at` / `applied_at` live
-- here.
-- The row carries a REFERENCE and never an instruction (decision T3): the employee and the kind,
-- never the role names, so whoever writes the row decides nothing. The kind is therefore immutable,
-- which is why the entity exposes no setter for it.
-- `kind` and `status` are VARCHAR columns validated by a Java enum, following the
-- `inventory_movements.movement_type` precedent: this schema has zero `CHECK (col IN ...)` and zero
-- native enums. The enum VALUES are the Java side's (W1b); no transition logic lives in the database.
-- `uq_access_provisioning_tasks_open` is this schema's FIRST partial UNIQUE index. It admits at most
-- one OPEN task per employee, so a second intent cannot race the first; `APPLIED` and `REJECTED` are
-- closed and their rows no longer occupy the slot. The status set in the predicate IS the definition
-- of "open" and must keep tracking the Java enum.
-- `access_provisioning_applied_roles` is the applied snapshot (decision T8): immutable history, one
-- row per role the task granted or removed. It is a table rather than a JSON column because the
-- allowlist test (decision T12) reads it as rows, and the repository has exactly one `jsonb` column.
-- It carries only `created_at` on purpose: an `updated_at` would suggest a mutation that must never
-- happen.
-- There is deliberately NO `version` column and no optimistic locking here. The concurrency control
-- for a worker claiming a task belongs to W4 and is a conditional UPDATE on `status` — the claim —
-- so a `version` precondition would be a second, redundant guard. `V8__store_optimistic_locking.sql`
-- is the precedent for adding one later if that decision changes.
-- Foreign keys are unnamed, matching the baseline and the V9..V21 style. Named are the child's UNIQUE
-- constraint and every index, so a violation identifies the rule it broke.
-- NOT in this migration: any seed, and any transition logic. The state machine is W1b's, the worker is
-- W4's and the gate is W5's.
-- ============================================

CREATE TABLE access_provisioning_tasks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_id UUID NOT NULL REFERENCES employees(id),
    kind VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(500),
    requested_by VARCHAR(36) NOT NULL,
    requested_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_by VARCHAR(36),
    decided_at TIMESTAMP,
    applied_at TIMESTAMP,
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_access_provisioning_tasks_employee_id ON access_provisioning_tasks(employee_id);
CREATE INDEX idx_access_provisioning_tasks_status ON access_provisioning_tasks(status);
CREATE UNIQUE INDEX uq_access_provisioning_tasks_open
    ON access_provisioning_tasks(employee_id)
    WHERE status IN ('PENDING', 'APPROVAL_PENDING', 'RUNNING', 'FAILED');

-- The applied snapshot: immutable history, one row per role the task actually granted or removed.
-- It is a table and not a JSON column because the repository has exactly one jsonb column and no
-- array columns, and because the allowlist test wants to read it as rows.
CREATE TABLE access_provisioning_applied_roles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id UUID NOT NULL REFERENCES access_provisioning_tasks(id),
    role_name VARCHAR(100) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_access_provisioning_applied_roles UNIQUE (task_id, role_name)
);
CREATE INDEX idx_access_provisioning_applied_roles_task_id ON access_provisioning_applied_roles(task_id);
