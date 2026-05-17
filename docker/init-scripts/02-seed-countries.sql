-- ============================================
-- Seed Data - Countries
-- ============================================
INSERT INTO countries (id, country_code, country_name, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'MX', 'México', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM countries WHERE country_code = 'MX');

INSERT INTO countries (id, country_code, country_name, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'CO', 'Colombia', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM countries WHERE country_code = 'CO');

INSERT INTO countries (id, country_code, country_name, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'US', 'Estados Unidos', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM countries WHERE country_code = 'US');
