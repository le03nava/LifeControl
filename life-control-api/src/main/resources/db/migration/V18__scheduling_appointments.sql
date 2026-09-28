-- ============================================
-- V18 — Scheduling: appointments and the booking lifecycle (W4)
-- ============================================
-- `scheduling_appointments` is one booking of a slot: who attends (optional `user_id`), for whom
-- (optional `customer_id`) and how far it got (`status_id`). `slot_id`, `activity_id` and
-- `company_store_id` are denormalized plain UUID foreign keys: the service derives the activity and
-- the store from the locked slot at booking time, so the appointment carries its own store without a
-- join and no lazy association is reachable from the entity. `activity_id` is denormalized for the
-- same reason `company_store_id` is — the slot already points at its activity, but an appointment
-- read must not need that second hop to know which service was booked.
--
-- There is DELIBERATELY no UNIQUE constraint on `slot_id`. A slot's `capacity` may be greater than
-- one, so several appointments legitimately share the same slot and their count is what `booked`
-- tracks. The capacity invariant is owned by the application (W4): the booking path takes a
-- PESSIMISTIC_WRITE lock on the slot row and moves `booked` on exactly the two lifecycle edges, so
-- two concurrent bookings of a full slot serialize on that row instead of racing a database unique
-- key that cannot express "at most capacity rows".
--
-- `user_id` is the Keycloak `sub` of the attendee and is nullable: an unassigned booking stays
-- unassigned (D30) and is never back-filled with the caller. `customer_id` is optional too; when
-- present the service validates it exists, and the reference is unnamed like the rest.
--
-- The `APPOINTMENT` status family is seeded with the V12 idempotent idiom: `Scheduled` is the status
-- every booking is created with, and the transition map (Scheduled -> Confirmed/Completed/Cancelled/
-- NoShow, Confirmed -> Completed/Cancelled/NoShow, and the three terminal statuses) lives in the
-- service, keyed by status name.
--
-- Foreign keys are unnamed, matching the baseline and the V9..V17 style.
-- ============================================

CREATE TABLE scheduling_appointments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slot_id UUID NOT NULL REFERENCES scheduling_slots(id),
    activity_id UUID NOT NULL REFERENCES scheduling_activities(id),
    company_store_id UUID NOT NULL REFERENCES company_stores(id),
    user_id VARCHAR(255),
    customer_id UUID REFERENCES customers(id),
    status_id UUID NOT NULL REFERENCES statuses(id),
    notes TEXT,
    enabled BOOLEAN NOT NULL DEFAULT true,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_scheduling_appointments_slot_id ON scheduling_appointments(slot_id);
CREATE INDEX idx_scheduling_appointments_company_store_id ON scheduling_appointments(company_store_id);
CREATE INDEX idx_scheduling_appointments_activity_id ON scheduling_appointments(activity_id);
CREATE INDEX idx_scheduling_appointments_user_id ON scheduling_appointments(user_id);

-- ============================================
-- Appointment status family (mirrors the V12 natural-key-guard pattern)
-- ============================================

INSERT INTO status_types (id, status_type_name, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'APPOINTMENT', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM status_types WHERE LOWER(status_type_name) = LOWER('APPOINTMENT'));

INSERT INTO statuses (id, status_name, status_type_id, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'Scheduled', st.id, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM status_types st
WHERE LOWER(st.status_type_name) = LOWER('APPOINTMENT')
  AND NOT EXISTS (SELECT 1 FROM statuses s WHERE s.status_type_id = st.id AND LOWER(s.status_name) = LOWER('Scheduled'));

INSERT INTO statuses (id, status_name, status_type_id, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'Confirmed', st.id, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM status_types st
WHERE LOWER(st.status_type_name) = LOWER('APPOINTMENT')
  AND NOT EXISTS (SELECT 1 FROM statuses s WHERE s.status_type_id = st.id AND LOWER(s.status_name) = LOWER('Confirmed'));

INSERT INTO statuses (id, status_name, status_type_id, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'Completed', st.id, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM status_types st
WHERE LOWER(st.status_type_name) = LOWER('APPOINTMENT')
  AND NOT EXISTS (SELECT 1 FROM statuses s WHERE s.status_type_id = st.id AND LOWER(s.status_name) = LOWER('Completed'));

INSERT INTO statuses (id, status_name, status_type_id, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'Cancelled', st.id, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM status_types st
WHERE LOWER(st.status_type_name) = LOWER('APPOINTMENT')
  AND NOT EXISTS (SELECT 1 FROM statuses s WHERE s.status_type_id = st.id AND LOWER(s.status_name) = LOWER('Cancelled'));

INSERT INTO statuses (id, status_name, status_type_id, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'NoShow', st.id, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM status_types st
WHERE LOWER(st.status_type_name) = LOWER('APPOINTMENT')
  AND NOT EXISTS (SELECT 1 FROM statuses s WHERE s.status_type_id = st.id AND LOWER(s.status_name) = LOWER('NoShow'));
