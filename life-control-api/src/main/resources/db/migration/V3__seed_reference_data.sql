-- ============================================
-- V3 — Seed Data: Reference Domains
-- ============================================
-- Idempotent: only inserts rows that do not already exist (natural-key guards).
-- Order matters only inside the status domain: types -> legacy rename -> statuses.
-- ============================================

-- ============================================
-- 1. Status Types
-- ============================================
INSERT INTO status_types (id, status_type_name, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'PURCHASE_ORDER', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM status_types WHERE LOWER(status_type_name) = LOWER('PURCHASE_ORDER'));

INSERT INTO status_types (id, status_type_name, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'PURCHASE_ORDER_DETAIL', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM status_types WHERE LOWER(status_type_name) = LOWER('PURCHASE_ORDER_DETAIL'));

INSERT INTO status_types (id, status_type_name, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'SALES_ORDER', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM status_types WHERE LOWER(status_type_name) = LOWER('SALES_ORDER'));

INSERT INTO status_types (id, status_type_name, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'SALES_ORDER_ITEM', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM status_types WHERE LOWER(status_type_name) = LOWER('SALES_ORDER_ITEM'));

-- ============================================
-- 2. Legacy Status Name Rename (deterministic order)
--    Replicates SalesOrderStatusInitializer MIGRATIONS pairs for BOTH
--    SALES_ORDER and SALES_ORDER_ITEM. Skip when target name already exists
--    to never violate UNIQUE(status_type_id, status_name).
-- ============================================

-- SALES_ORDER
UPDATE statuses s SET status_name = 'Draft'
WHERE s.status_type_id = (SELECT id FROM status_types WHERE LOWER(status_type_name) = LOWER('SALES_ORDER'))
  AND LOWER(s.status_name) = LOWER('Borrador')
  AND NOT EXISTS (SELECT 1 FROM statuses s2
                  WHERE s2.status_type_id = s.status_type_id
                    AND LOWER(s2.status_name) = LOWER('Draft'));

UPDATE statuses s SET status_name = 'Pending'
WHERE s.status_type_id = (SELECT id FROM status_types WHERE LOWER(status_type_name) = LOWER('SALES_ORDER'))
  AND LOWER(s.status_name) = LOWER('Enviada')
  AND NOT EXISTS (SELECT 1 FROM statuses s2
                  WHERE s2.status_type_id = s.status_type_id
                    AND LOWER(s2.status_name) = LOWER('Pending'));

UPDATE statuses s SET status_name = 'Completed'
WHERE s.status_type_id = (SELECT id FROM status_types WHERE LOWER(status_type_name) = LOWER('SALES_ORDER'))
  AND LOWER(s.status_name) = LOWER('Cerrada')
  AND NOT EXISTS (SELECT 1 FROM statuses s2
                  WHERE s2.status_type_id = s.status_type_id
                    AND LOWER(s2.status_name) = LOWER('Completed'));

UPDATE statuses s SET status_name = 'Cancelled'
WHERE s.status_type_id = (SELECT id FROM status_types WHERE LOWER(status_type_name) = LOWER('SALES_ORDER'))
  AND LOWER(s.status_name) = LOWER('Cancelada')
  AND NOT EXISTS (SELECT 1 FROM statuses s2
                  WHERE s2.status_type_id = s.status_type_id
                    AND LOWER(s2.status_name) = LOWER('Cancelled'));

UPDATE statuses s SET status_name = 'Pending'
WHERE s.status_type_id = (SELECT id FROM status_types WHERE LOWER(status_type_name) = LOWER('SALES_ORDER'))
  AND LOWER(s.status_name) = LOWER('Pendiente')
  AND NOT EXISTS (SELECT 1 FROM statuses s2
                  WHERE s2.status_type_id = s.status_type_id
                    AND LOWER(s2.status_name) = LOWER('Pending'));

UPDATE statuses s SET status_name = 'Added'
WHERE s.status_type_id = (SELECT id FROM status_types WHERE LOWER(status_type_name) = LOWER('SALES_ORDER'))
  AND LOWER(s.status_name) = LOWER('Agregado')
  AND NOT EXISTS (SELECT 1 FROM statuses s2
                  WHERE s2.status_type_id = s.status_type_id
                    AND LOWER(s2.status_name) = LOWER('Added'));

UPDATE statuses s SET status_name = 'Cancelled'
WHERE s.status_type_id = (SELECT id FROM status_types WHERE LOWER(status_type_name) = LOWER('SALES_ORDER'))
  AND LOWER(s.status_name) = LOWER('Cancelado')
  AND NOT EXISTS (SELECT 1 FROM statuses s2
                  WHERE s2.status_type_id = s.status_type_id
                    AND LOWER(s2.status_name) = LOWER('Cancelled'));

-- SALES_ORDER_ITEM
UPDATE statuses s SET status_name = 'Draft'
WHERE s.status_type_id = (SELECT id FROM status_types WHERE LOWER(status_type_name) = LOWER('SALES_ORDER_ITEM'))
  AND LOWER(s.status_name) = LOWER('Borrador')
  AND NOT EXISTS (SELECT 1 FROM statuses s2
                  WHERE s2.status_type_id = s.status_type_id
                    AND LOWER(s2.status_name) = LOWER('Draft'));

UPDATE statuses s SET status_name = 'Pending'
WHERE s.status_type_id = (SELECT id FROM status_types WHERE LOWER(status_type_name) = LOWER('SALES_ORDER_ITEM'))
  AND LOWER(s.status_name) = LOWER('Enviada')
  AND NOT EXISTS (SELECT 1 FROM statuses s2
                  WHERE s2.status_type_id = s.status_type_id
                    AND LOWER(s2.status_name) = LOWER('Pending'));

UPDATE statuses s SET status_name = 'Completed'
WHERE s.status_type_id = (SELECT id FROM status_types WHERE LOWER(status_type_name) = LOWER('SALES_ORDER_ITEM'))
  AND LOWER(s.status_name) = LOWER('Cerrada')
  AND NOT EXISTS (SELECT 1 FROM statuses s2
                  WHERE s2.status_type_id = s.status_type_id
                    AND LOWER(s2.status_name) = LOWER('Completed'));

UPDATE statuses s SET status_name = 'Cancelled'
WHERE s.status_type_id = (SELECT id FROM status_types WHERE LOWER(status_type_name) = LOWER('SALES_ORDER_ITEM'))
  AND LOWER(s.status_name) = LOWER('Cancelada')
  AND NOT EXISTS (SELECT 1 FROM statuses s2
                  WHERE s2.status_type_id = s.status_type_id
                    AND LOWER(s2.status_name) = LOWER('Cancelled'));

UPDATE statuses s SET status_name = 'Pending'
WHERE s.status_type_id = (SELECT id FROM status_types WHERE LOWER(status_type_name) = LOWER('SALES_ORDER_ITEM'))
  AND LOWER(s.status_name) = LOWER('Pendiente')
  AND NOT EXISTS (SELECT 1 FROM statuses s2
                  WHERE s2.status_type_id = s.status_type_id
                    AND LOWER(s2.status_name) = LOWER('Pending'));

UPDATE statuses s SET status_name = 'Added'
WHERE s.status_type_id = (SELECT id FROM status_types WHERE LOWER(status_type_name) = LOWER('SALES_ORDER_ITEM'))
  AND LOWER(s.status_name) = LOWER('Agregado')
  AND NOT EXISTS (SELECT 1 FROM statuses s2
                  WHERE s2.status_type_id = s.status_type_id
                    AND LOWER(s2.status_name) = LOWER('Added'));

UPDATE statuses s SET status_name = 'Cancelled'
WHERE s.status_type_id = (SELECT id FROM status_types WHERE LOWER(status_type_name) = LOWER('SALES_ORDER_ITEM'))
  AND LOWER(s.status_name) = LOWER('Cancelado')
  AND NOT EXISTS (SELECT 1 FROM statuses s2
                  WHERE s2.status_type_id = s.status_type_id
                    AND LOWER(s2.status_name) = LOWER('Cancelled'));

-- ============================================
-- 3. Statuses
-- ============================================

-- PURCHASE_ORDER
INSERT INTO statuses (id, status_name, status_type_id, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'Draft', st.id, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM status_types st
WHERE LOWER(st.status_type_name) = LOWER('PURCHASE_ORDER')
  AND NOT EXISTS (SELECT 1 FROM statuses s WHERE s.status_type_id = st.id AND LOWER(s.status_name) = LOWER('Draft'));

INSERT INTO statuses (id, status_name, status_type_id, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'Sent', st.id, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM status_types st
WHERE LOWER(st.status_type_name) = LOWER('PURCHASE_ORDER')
  AND NOT EXISTS (SELECT 1 FROM statuses s WHERE s.status_type_id = st.id AND LOWER(s.status_name) = LOWER('Sent'));

INSERT INTO statuses (id, status_name, status_type_id, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'Accepted', st.id, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM status_types st
WHERE LOWER(st.status_type_name) = LOWER('PURCHASE_ORDER')
  AND NOT EXISTS (SELECT 1 FROM statuses s WHERE s.status_type_id = st.id AND LOWER(s.status_name) = LOWER('Accepted'));

INSERT INTO statuses (id, status_name, status_type_id, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'In Transit', st.id, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM status_types st
WHERE LOWER(st.status_type_name) = LOWER('PURCHASE_ORDER')
  AND NOT EXISTS (SELECT 1 FROM statuses s WHERE s.status_type_id = st.id AND LOWER(s.status_name) = LOWER('In Transit'));

INSERT INTO statuses (id, status_name, status_type_id, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'Received', st.id, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM status_types st
WHERE LOWER(st.status_type_name) = LOWER('PURCHASE_ORDER')
  AND NOT EXISTS (SELECT 1 FROM statuses s WHERE s.status_type_id = st.id AND LOWER(s.status_name) = LOWER('Received'));

INSERT INTO statuses (id, status_name, status_type_id, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'Billed', st.id, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM status_types st
WHERE LOWER(st.status_type_name) = LOWER('PURCHASE_ORDER')
  AND NOT EXISTS (SELECT 1 FROM statuses s WHERE s.status_type_id = st.id AND LOWER(s.status_name) = LOWER('Billed'));

INSERT INTO statuses (id, status_name, status_type_id, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'Closed', st.id, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM status_types st
WHERE LOWER(st.status_type_name) = LOWER('PURCHASE_ORDER')
  AND NOT EXISTS (SELECT 1 FROM statuses s WHERE s.status_type_id = st.id AND LOWER(s.status_name) = LOWER('Closed'));

INSERT INTO statuses (id, status_name, status_type_id, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'Rejected', st.id, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM status_types st
WHERE LOWER(st.status_type_name) = LOWER('PURCHASE_ORDER')
  AND NOT EXISTS (SELECT 1 FROM statuses s WHERE s.status_type_id = st.id AND LOWER(s.status_name) = LOWER('Rejected'));

-- PURCHASE_ORDER_DETAIL
INSERT INTO statuses (id, status_name, status_type_id, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'Pending', st.id, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM status_types st
WHERE LOWER(st.status_type_name) = LOWER('PURCHASE_ORDER_DETAIL')
  AND NOT EXISTS (SELECT 1 FROM statuses s WHERE s.status_type_id = st.id AND LOWER(s.status_name) = LOWER('Pending'));

INSERT INTO statuses (id, status_name, status_type_id, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'In Process', st.id, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM status_types st
WHERE LOWER(st.status_type_name) = LOWER('PURCHASE_ORDER_DETAIL')
  AND NOT EXISTS (SELECT 1 FROM statuses s WHERE s.status_type_id = st.id AND LOWER(s.status_name) = LOWER('In Process'));

INSERT INTO statuses (id, status_name, status_type_id, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'In Transit', st.id, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM status_types st
WHERE LOWER(st.status_type_name) = LOWER('PURCHASE_ORDER_DETAIL')
  AND NOT EXISTS (SELECT 1 FROM statuses s WHERE s.status_type_id = st.id AND LOWER(s.status_name) = LOWER('In Transit'));

INSERT INTO statuses (id, status_name, status_type_id, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'Partial Received', st.id, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM status_types st
WHERE LOWER(st.status_type_name) = LOWER('PURCHASE_ORDER_DETAIL')
  AND NOT EXISTS (SELECT 1 FROM statuses s WHERE s.status_type_id = st.id AND LOWER(s.status_name) = LOWER('Partial Received'));

INSERT INTO statuses (id, status_name, status_type_id, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'Received', st.id, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM status_types st
WHERE LOWER(st.status_type_name) = LOWER('PURCHASE_ORDER_DETAIL')
  AND NOT EXISTS (SELECT 1 FROM statuses s WHERE s.status_type_id = st.id AND LOWER(s.status_name) = LOWER('Received'));

INSERT INTO statuses (id, status_name, status_type_id, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'Rejected', st.id, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM status_types st
WHERE LOWER(st.status_type_name) = LOWER('PURCHASE_ORDER_DETAIL')
  AND NOT EXISTS (SELECT 1 FROM statuses s WHERE s.status_type_id = st.id AND LOWER(s.status_name) = LOWER('Rejected'));

INSERT INTO statuses (id, status_name, status_type_id, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'Cancelled', st.id, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM status_types st
WHERE LOWER(st.status_type_name) = LOWER('PURCHASE_ORDER_DETAIL')
  AND NOT EXISTS (SELECT 1 FROM statuses s WHERE s.status_type_id = st.id AND LOWER(s.status_name) = LOWER('Cancelled'));

-- SALES_ORDER
INSERT INTO statuses (id, status_name, status_type_id, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'Draft', st.id, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM status_types st
WHERE LOWER(st.status_type_name) = LOWER('SALES_ORDER')
  AND NOT EXISTS (SELECT 1 FROM statuses s WHERE s.status_type_id = st.id AND LOWER(s.status_name) = LOWER('Draft'));

INSERT INTO statuses (id, status_name, status_type_id, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'Active', st.id, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM status_types st
WHERE LOWER(st.status_type_name) = LOWER('SALES_ORDER')
  AND NOT EXISTS (SELECT 1 FROM statuses s WHERE s.status_type_id = st.id AND LOWER(s.status_name) = LOWER('Active'));

INSERT INTO statuses (id, status_name, status_type_id, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'Pending', st.id, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM status_types st
WHERE LOWER(st.status_type_name) = LOWER('SALES_ORDER')
  AND NOT EXISTS (SELECT 1 FROM statuses s WHERE s.status_type_id = st.id AND LOWER(s.status_name) = LOWER('Pending'));

INSERT INTO statuses (id, status_name, status_type_id, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'Completed', st.id, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM status_types st
WHERE LOWER(st.status_type_name) = LOWER('SALES_ORDER')
  AND NOT EXISTS (SELECT 1 FROM statuses s WHERE s.status_type_id = st.id AND LOWER(s.status_name) = LOWER('Completed'));

INSERT INTO statuses (id, status_name, status_type_id, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'Cancelled', st.id, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM status_types st
WHERE LOWER(st.status_type_name) = LOWER('SALES_ORDER')
  AND NOT EXISTS (SELECT 1 FROM statuses s WHERE s.status_type_id = st.id AND LOWER(s.status_name) = LOWER('Cancelled'));

-- SALES_ORDER_ITEM
INSERT INTO statuses (id, status_name, status_type_id, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'Pending', st.id, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM status_types st
WHERE LOWER(st.status_type_name) = LOWER('SALES_ORDER_ITEM')
  AND NOT EXISTS (SELECT 1 FROM statuses s WHERE s.status_type_id = st.id AND LOWER(s.status_name) = LOWER('Pending'));

INSERT INTO statuses (id, status_name, status_type_id, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'Added', st.id, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM status_types st
WHERE LOWER(st.status_type_name) = LOWER('SALES_ORDER_ITEM')
  AND NOT EXISTS (SELECT 1 FROM statuses s WHERE s.status_type_id = st.id AND LOWER(s.status_name) = LOWER('Added'));

INSERT INTO statuses (id, status_name, status_type_id, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'Cancelled', st.id, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM status_types st
WHERE LOWER(st.status_type_name) = LOWER('SALES_ORDER_ITEM')
  AND NOT EXISTS (SELECT 1 FROM statuses s WHERE s.status_type_id = st.id AND LOWER(s.status_name) = LOWER('Cancelled'));

-- ============================================
-- 4. Activity Processes
-- ============================================
INSERT INTO activity_processes (id, name, created_at)
SELECT gen_random_uuid(), 'COMPANY', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM activity_processes WHERE name = 'COMPANY');

INSERT INTO activity_processes (id, name, created_at)
SELECT gen_random_uuid(), 'ORDER', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM activity_processes WHERE name = 'ORDER');

INSERT INTO activity_processes (id, name, created_at)
SELECT gen_random_uuid(), 'INVENTORY', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM activity_processes WHERE name = 'INVENTORY');

INSERT INTO activity_processes (id, name, created_at)
SELECT gen_random_uuid(), 'PRODUCT', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM activity_processes WHERE name = 'PRODUCT');

INSERT INTO activity_processes (id, name, created_at)
SELECT gen_random_uuid(), 'NOTIFICATION', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM activity_processes WHERE name = 'NOTIFICATION');

INSERT INTO activity_processes (id, name, created_at)
SELECT gen_random_uuid(), 'AUTH', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM activity_processes WHERE name = 'AUTH');

INSERT INTO activity_processes (id, name, created_at)
SELECT gen_random_uuid(), 'SECURITY', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM activity_processes WHERE name = 'SECURITY');

INSERT INTO activity_processes (id, name, created_at)
SELECT gen_random_uuid(), 'STATUS', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM activity_processes WHERE name = 'STATUS');

-- ============================================
-- 5. Activity Events
-- ============================================
INSERT INTO activity_events (id, name, created_at)
SELECT gen_random_uuid(), 'CREATE', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM activity_events WHERE name = 'CREATE');

INSERT INTO activity_events (id, name, created_at)
SELECT gen_random_uuid(), 'READ', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM activity_events WHERE name = 'READ');

INSERT INTO activity_events (id, name, created_at)
SELECT gen_random_uuid(), 'UPDATE', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM activity_events WHERE name = 'UPDATE');

INSERT INTO activity_events (id, name, created_at)
SELECT gen_random_uuid(), 'DELETE', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM activity_events WHERE name = 'DELETE');

-- ============================================
-- 6. Measure Units (guard on sat_code; enabled=true)
-- ============================================
INSERT INTO measure_units (id, measure_unit_name, measure_unit_short_name, unit_type, sat_code, description, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'Pieza', 'Pieza', 'PRODUCT', 'H87', 'Para artículos individuales: ropa, electrónicos, juguetes, etc.', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM measure_units WHERE sat_code = 'H87');

INSERT INTO measure_units (id, measure_unit_name, measure_unit_short_name, unit_type, sat_code, description, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'Kilogramo', 'Kg', 'PRODUCT', 'KGM', 'Para alimentos, materiales a granel, insumos industriales.', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM measure_units WHERE sat_code = 'KGM');

INSERT INTO measure_units (id, measure_unit_name, measure_unit_short_name, unit_type, sat_code, description, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'Litro', 'L', 'PRODUCT', 'LTR', 'Para líquidos como bebidas, aceites, productos químicos.', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM measure_units WHERE sat_code = 'LTR');

INSERT INTO measure_units (id, measure_unit_name, measure_unit_short_name, unit_type, sat_code, description, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'Metro', 'M', 'PRODUCT', 'MTR', 'Para textiles, cableado, materiales de construcción.', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM measure_units WHERE sat_code = 'MTR');

INSERT INTO measure_units (id, measure_unit_name, measure_unit_short_name, unit_type, sat_code, description, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'Caja', 'Caja', 'PRODUCT', 'XBX', 'Para productos empacados en cajas: botellas, empaques, etc.', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM measure_units WHERE sat_code = 'XBX');

INSERT INTO measure_units (id, measure_unit_name, measure_unit_short_name, unit_type, sat_code, description, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'Docena', 'Docena', 'PRODUCT', 'DZN', 'Para productos que se venden en múltiplos de 12.', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM measure_units WHERE sat_code = 'DZN');

INSERT INTO measure_units (id, measure_unit_name, measure_unit_short_name, unit_type, sat_code, description, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'Paquete', 'Paquete', 'PRODUCT', 'XPK', 'Cuando se vende un conjunto de artículos como una sola unidad.', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM measure_units WHERE sat_code = 'XPK');

INSERT INTO measure_units (id, measure_unit_name, measure_unit_short_name, unit_type, sat_code, description, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'Unidad de servicio', 'Servicio', 'SERVICE', 'E48', 'Para servicios en general: consultoría, diseño, mantenimiento.', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM measure_units WHERE sat_code = 'E48');

INSERT INTO measure_units (id, measure_unit_name, measure_unit_short_name, unit_type, sat_code, description, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'Hora', 'Hora', 'SERVICE', 'HUR', 'Servicios por tiempo: clases, asesorías, soporte técnico.', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM measure_units WHERE sat_code = 'HUR');

INSERT INTO measure_units (id, measure_unit_name, measure_unit_short_name, unit_type, sat_code, description, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'Día', 'Día', 'SERVICE', 'DAY', 'Servicios que se cobran por jornada: renta de equipo, hospedaje.', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM measure_units WHERE sat_code = 'DAY');

INSERT INTO measure_units (id, measure_unit_name, measure_unit_short_name, unit_type, sat_code, description, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'Mes', 'Mes', 'SERVICE', 'MON', 'Servicios por periodo mensual: suscripciones, arrendamientos.', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM measure_units WHERE sat_code = 'MON');

-- ============================================
-- 7. Payment Methods (guard on LOWER(name))
-- ============================================
INSERT INTO payment_methods (id, payment_method_name, payment_method_short_name, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'Efectivo', 'EFECTIVO', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM payment_methods WHERE LOWER(payment_method_name) = LOWER('Efectivo'));

INSERT INTO payment_methods (id, payment_method_name, payment_method_short_name, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'Tarjeta Crédito', 'TC', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM payment_methods WHERE LOWER(payment_method_name) = LOWER('Tarjeta Crédito'));

INSERT INTO payment_methods (id, payment_method_name, payment_method_short_name, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'Tarjeta Débito', 'TD', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM payment_methods WHERE LOWER(payment_method_name) = LOWER('Tarjeta Débito'));

INSERT INTO payment_methods (id, payment_method_name, payment_method_short_name, enabled, created_at, updated_at)
SELECT gen_random_uuid(), 'Transferencia', 'TRANSFERENCIA', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM payment_methods WHERE LOWER(payment_method_name) = LOWER('Transferencia'));

-- ============================================
-- 8. Default Customer "Cliente General" (guarded on FIXED id)
-- ============================================
INSERT INTO customers (id, name, sales_channel, enabled, created_at, updated_at)
SELECT '00000000-0000-0000-0000-000000000001', 'Cliente General', 'TODOS', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM customers WHERE id = '00000000-0000-0000-0000-000000000001');
