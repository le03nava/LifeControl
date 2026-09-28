-- ============================================
-- V17 — Scheduling: availability template and bookable slots (W3)
-- ============================================
-- `scheduling_availability` is the recurring template a store sets per activity: on which weekday,
-- between which wall-clock times and for which calendar range the activity can be booked. One row
-- is one window; several windows on the same day are allowed as long as they do not overlap (the
-- service rejects an overlap before writing, and `UNIQUE(activity_id, day_of_week, start_time)`
-- still stops two identical windows at the database).
--
-- `day_of_week` is ISO-8601: MONDAY = 1 … SUNDAY = 7, enforced by CHECK. No day-of-week convention
-- existed anywhere in this repo before this migration, so the numbering is declared here and not
-- inherited from anywhere: 1 is Monday, 7 is Sunday, and 0/8 are invalid.
--
-- `start_time`/`end_time` and `valid_from`/`valid_to` are the first `TIME` and `DATE` columns in
-- this schema — a grep over db/migration/** is negative for both types. They are deliberate: the
-- template is store-local wall-clock with no time-zone conversion (D12), so a `TIME` without time
-- zone plus a plain `DATE` state exactly that, with no offset to interpret.
--
-- `scheduling_slots` is the bookable instance materialized from the template (D10). It has NO JPA
-- entity in this work unit: the entity lands in W3b together with the range expansion and the
-- idempotent upsert on `UNIQUE(activity_id, start_at)`. `status` is written by W4's booking path;
-- W3 only inserts its default (`Available`), and `booked` starts at 0 with CHECKs keeping it inside
-- `[0, capacity]`.
--
-- Foreign keys are unnamed, matching the baseline and the V9..V16 style. The CHECKs and the unique
-- keys are named so a violation identifies the rule it broke. `scheduling_slots.version` exists so
-- the W3b entity maps onto this table without a follow-up migration.
-- ============================================

CREATE TABLE scheduling_availability (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    activity_id UUID NOT NULL REFERENCES scheduling_activities(id),
    day_of_week SMALLINT NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    valid_from DATE NOT NULL,
    valid_to DATE NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT true,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_scheduling_availability_day_of_week CHECK (day_of_week BETWEEN 1 AND 7),
    CONSTRAINT ck_scheduling_availability_window CHECK (end_time > start_time),
    CONSTRAINT ck_scheduling_availability_validity CHECK (valid_from <= valid_to),
    CONSTRAINT uq_scheduling_availability_activity_day_start UNIQUE (activity_id, day_of_week, start_time)
);
CREATE INDEX idx_scheduling_availability_activity_id ON scheduling_availability(activity_id);

CREATE TABLE scheduling_slots (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    activity_id UUID NOT NULL REFERENCES scheduling_activities(id),
    start_at TIMESTAMP NOT NULL,
    end_at TIMESTAMP NOT NULL,
    capacity INTEGER NOT NULL,
    booked INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(50) NOT NULL DEFAULT 'Available',
    enabled BOOLEAN NOT NULL DEFAULT true,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_scheduling_slots_capacity CHECK (capacity > 0),
    CONSTRAINT ck_scheduling_slots_booked CHECK (booked >= 0),
    CONSTRAINT ck_scheduling_slots_room CHECK (booked <= capacity),
    CONSTRAINT ck_scheduling_slots_window CHECK (end_at > start_at),
    CONSTRAINT uq_scheduling_slots_activity_start UNIQUE (activity_id, start_at)
);
CREATE INDEX idx_scheduling_slots_activity_id_start_at ON scheduling_slots(activity_id, start_at);
