-- ============================================
-- V16 — Scheduling: bookable activities (W1)
-- ============================================
-- `scheduling_activities` is the catalogue of what can be booked at a store: the service
-- name, how long it lasts (`duration_minutes`) and how many appointments fit in one slot
-- (`capacity_per_slot`, both strictly positive). `user_id` is the Keycloak `sub` of the
-- employee who attends the activity, nullable because the catalogue entry can exist before
-- someone is assigned — the same convention `shifts.user_id` (V1) already uses.
-- `UNIQUE(company_store_id, activity_name)` keeps one name per store.
--
-- `scheduling_availability`, `scheduling_slots` and `scheduling_appointments` are NOT here:
-- schema travels with the slice that uses it (V11..V15), so they land in V17 (W3) and V18 (W4)
-- together with the `APPOINTMENT` status family.
--
-- Foreign keys are unnamed, matching the baseline and the V9..V15 style.
--
-- The `SCHEDULING` row in `activity_processes` is mandatory: `ActivityLogService` derives the
-- process from the controller's package segment and silently skips (WARN only) any domain whose
-- process is absent, so without this seed the whole scheduling domain would be unaudited. The
-- guard is the same idempotent `INSERT ... SELECT ... WHERE NOT EXISTS` idiom V3/V4/V12 use.
-- ============================================

CREATE TABLE scheduling_activities (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_store_id UUID NOT NULL REFERENCES company_stores(id),
    user_id VARCHAR(255),
    activity_name VARCHAR(150) NOT NULL,
    description TEXT,
    duration_minutes INTEGER NOT NULL CHECK (duration_minutes > 0),
    capacity_per_slot INTEGER NOT NULL DEFAULT 1 CHECK (capacity_per_slot > 0),
    enabled BOOLEAN NOT NULL DEFAULT true,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_scheduling_activities_store_name UNIQUE (company_store_id, activity_name)
);

CREATE INDEX idx_scheduling_activities_company_store_id ON scheduling_activities(company_store_id);
CREATE INDEX idx_scheduling_activities_user_id ON scheduling_activities(user_id);

-- ============================================
-- Audit process for the scheduling domain (mirrors the V4 natural-key-guard pattern)
-- ============================================

INSERT INTO activity_processes (id, name, created_at)
SELECT gen_random_uuid(), 'SCHEDULING', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM activity_processes WHERE name = 'SCHEDULING');
