-- ============================================
-- V4 — Seed Data: Activity Processes (missing domains)
-- ============================================
-- The activity audit aspect derives the process name from the controller
-- package segment after `com.lifecontrol.api.` (uppercased), e.g.
-- `supplier.controller` -> SUPPLIER. ActivityLogService skips (with a WARN)
-- any entry whose process is not present in `activity_processes`, so every
-- such domain was silently unaudited.
--
-- V3 seeded COMPANY, ORDER, INVENTORY, PRODUCT, NOTIFICATION, AUTH, SECURITY,
-- STATUS. Of those, only COMPANY, PRODUCT and STATUS match a domain that
-- actually has a controller. This migration adds the remaining auto-derived
-- names. Idempotent: guarded on the natural key (name).
-- ============================================

INSERT INTO activity_processes (id, name, created_at)
SELECT gen_random_uuid(), 'ACTIVITY', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM activity_processes WHERE name = 'ACTIVITY');

INSERT INTO activity_processes (id, name, created_at)
SELECT gen_random_uuid(), 'COUNTRY', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM activity_processes WHERE name = 'COUNTRY');

INSERT INTO activity_processes (id, name, created_at)
SELECT gen_random_uuid(), 'CUSTOMER', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM activity_processes WHERE name = 'CUSTOMER');

INSERT INTO activity_processes (id, name, created_at)
SELECT gen_random_uuid(), 'MEASUREUNIT', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM activity_processes WHERE name = 'MEASUREUNIT');

INSERT INTO activity_processes (id, name, created_at)
SELECT gen_random_uuid(), 'PAYMENTMETHOD', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM activity_processes WHERE name = 'PAYMENTMETHOD');

INSERT INTO activity_processes (id, name, created_at)
SELECT gen_random_uuid(), 'PROFILE', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM activity_processes WHERE name = 'PROFILE');

INSERT INTO activity_processes (id, name, created_at)
SELECT gen_random_uuid(), 'PROMOTION', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM activity_processes WHERE name = 'PROMOTION');

INSERT INTO activity_processes (id, name, created_at)
SELECT gen_random_uuid(), 'PURCHASEORDER', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM activity_processes WHERE name = 'PURCHASEORDER');

INSERT INTO activity_processes (id, name, created_at)
SELECT gen_random_uuid(), 'SALESORDER', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM activity_processes WHERE name = 'SALESORDER');

INSERT INTO activity_processes (id, name, created_at)
SELECT gen_random_uuid(), 'SHIFT', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM activity_processes WHERE name = 'SHIFT');

INSERT INTO activity_processes (id, name, created_at)
SELECT gen_random_uuid(), 'STORE', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM activity_processes WHERE name = 'STORE');

INSERT INTO activity_processes (id, name, created_at)
SELECT gen_random_uuid(), 'SUPPLIER', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM activity_processes WHERE name = 'SUPPLIER');

INSERT INTO activity_processes (id, name, created_at)
SELECT gen_random_uuid(), 'USERSADMIN', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM activity_processes WHERE name = 'USERSADMIN');
