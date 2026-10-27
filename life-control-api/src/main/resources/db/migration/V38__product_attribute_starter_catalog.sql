-- ============================================
-- V38 — Product attribute catalog: the starter profiles
-- ============================================
-- Four profiles, one per block the user listed, attached to eleven of the twelve enabled roots
-- `V35__product_category_starter_catalog.sql` seeds: ferretería (Ferretería, Materiales de
-- Construcción, Plomería, Baños y Cocinas), electro (Electrodomésticos, Electrónica y TV), abarrotes
-- (Alimentos) and calzado (the four Calzado roots). The twelfth enabled root, "Accesorios y Cuidado",
-- is deliberately left unattached: it is a mixed bag of accessories and cleaning products and a profile
-- invented for it would be a guess. The endpoint answers "no profile" for it, which is a legitimate
-- answer, and the same is true of every category a company builds by hand.
--
-- Two migrations, not one, for the same reason V34/V35 are two: V37 is schema and this one writes
-- data, and a reviewer reads them as separate questions.
--
-- **This catalog has no Java twin, and that is the difference with V35.** V35 needed one because a
-- category row belongs to a company, so a company created tomorrow is seeded by
-- `StarterProductCategoryApplier` and reads its copy of the list from Java. Here the catalog is global
-- (`company_id IS NULL`) and the attachment is by root NAME
-- (`product_attribute_profile_roots`), so a company created tomorrow resolves against these same rows
-- with no code and no listener. The catalog therefore exists once, and there is no parity test to
-- write because there are not two copies to keep in step.
--
-- The seed is idempotent on all four levels, which matters because a restored dump or a manual re-run
-- can reach it twice: a profile is skipped when a global profile already owns its key, a definition
-- when its profile already owns its code, a value when its definition already owns its code, and a
-- root attachment when the same owner already names that root. The comparisons use `lower(...)` on
-- both sides because the uniqueness indexes are expression indexes over `lower(...)` (V37) — a
-- case-sensitive guard would attempt an insert the index rejects.
--
-- One rule about codes, so the seed reads consistently: **a code names one concept across profiles,
-- and its allowed values may still differ per branch.** `color` is the same idea in calzado and in
-- electro, with a palette each. `acabado` (the finish of a shoe) and `acabado_superficial` (the finish
-- of a metal part) are two codes because they are two concepts, and merging them would put "Charol"
-- and "Galvanizado" in one report column.
--
-- The user's own examples are the vocabulary, split where the example mixed two facts: "Negro Mate"
-- and "Charol Rojo" are a `color` plus an `acabado`, and "Café Oxford" is why `oxford` is a value of
-- `acabado` rather than a colour. "Alnico" is absent (it is a magnet material, not a ferretería
-- category) and "Fecha de caducidad" is absent on purpose: it belongs to a lot, not to a catalog row
-- (record L5).
-- ============================================

CREATE TEMPORARY TABLE seed_attribute_profile (
    profile_key VARCHAR(50),
    label VARCHAR(100),
    description VARCHAR(255)
);

INSERT INTO seed_attribute_profile (profile_key, label, description) VALUES
    ('calzado', 'Calzado', 'Atributos de zapatería y calzado en general'),
    ('ferreteria', 'Ferretería y Plomería', 'Herramienta, materiales de construcción, plomería y baños'),
    ('electro', 'Electrodomésticos y Tecnología', 'Aparatos electrodomésticos, cómputo y pantallas'),
    ('abarrotes', 'Abarrotes y Alimentos', 'Alimentos y bebidas empacados');

CREATE TEMPORARY TABLE seed_attribute_definition (
    profile_key VARCHAR(50),
    attribute_code VARCHAR(50),
    label VARCHAR(100),
    attribute_level VARCHAR(10),
    kind VARCHAR(20),
    unit_label VARCHAR(20),
    required BOOLEAN,
    display_order INTEGER
);

INSERT INTO seed_attribute_definition
    (profile_key, attribute_code, label, attribute_level, kind, unit_label, required, display_order) VALUES
    -- calzado
    ('calzado', 'material_externo', 'Material Externo', 'PRODUCT', 'ENUM', NULL, true, 10),
    ('calzado', 'tipo_ajuste', 'Tipo de Ajuste', 'PRODUCT', 'ENUM', NULL, true, 20),
    ('calzado', 'genero', 'Género', 'PRODUCT', 'ENUM', NULL, true, 30),
    ('calzado', 'tipo_suela', 'Tipo de Suela', 'PRODUCT', 'ENUM', NULL, false, 40),
    ('calzado', 'altura_tacon', 'Altura del Tacón', 'PRODUCT', 'MEASUREMENT', 'cm', false, 50),
    ('calzado', 'color', 'Color', 'VARIANT', 'ENUM', NULL, true, 10),
    ('calzado', 'acabado', 'Acabado', 'VARIANT', 'ENUM', NULL, false, 20),
    -- ferreteria
    ('ferreteria', 'material', 'Material', 'PRODUCT', 'ENUM', NULL, true, 10),
    ('ferreteria', 'medida_nominal', 'Medida / Dimensión', 'PRODUCT', 'MEASUREMENT', 'mm', false, 20),
    ('ferreteria', 'sistema_rosca', 'Sistema de Rosca', 'PRODUCT', 'ENUM', NULL, false, 30),
    ('ferreteria', 'calibre_awg', 'Calibre', 'PRODUCT', 'MEASUREMENT', 'AWG', false, 40),
    ('ferreteria', 'piezas_por_empaque', 'Piezas por Empaque', 'PRODUCT', 'MEASUREMENT', 'pza', false, 50),
    ('ferreteria', 'marca', 'Marca', 'PRODUCT', 'TEXT', NULL, false, 60),
    ('ferreteria', 'modelo', 'Modelo / Número de Parte', 'PRODUCT', 'TEXT', NULL, false, 70),
    ('ferreteria', 'acabado_superficial', 'Acabado Superficial', 'VARIANT', 'ENUM', NULL, false, 10),
    -- electro
    ('electro', 'voltaje', 'Voltaje', 'PRODUCT', 'ENUM', NULL, true, 10),
    ('electro', 'potencia', 'Potencia', 'PRODUCT', 'MEASUREMENT', 'W', false, 20),
    ('electro', 'capacidad', 'Capacidad', 'PRODUCT', 'MEASUREMENT', 'L', false, 30),
    ('electro', 'conectividad', 'Conectividad', 'PRODUCT', 'MULTI_ENUM', NULL, false, 40),
    ('electro', 'eficiencia_energetica', 'Eficiencia Energética', 'PRODUCT', 'ENUM', NULL, false, 50),
    ('electro', 'garantia', 'Garantía', 'PRODUCT', 'MEASUREMENT', 'mes', false, 60),
    ('electro', 'color', 'Color', 'VARIANT', 'ENUM', NULL, false, 10),
    -- abarrotes
    ('abarrotes', 'contenido_neto', 'Contenido Neto / Peso', 'PRODUCT', 'MEASUREMENT', 'g', true, 10),
    ('abarrotes', 'temperatura_conservacion', 'Temperatura de Conservación', 'PRODUCT', 'ENUM', NULL, true, 20),
    ('abarrotes', 'alergenos', 'Alérgenos', 'PRODUCT', 'MULTI_ENUM', NULL, false, 30),
    ('abarrotes', 'marca', 'Marca', 'PRODUCT', 'TEXT', NULL, false, 40),
    ('abarrotes', 'sabor', 'Sabor', 'VARIANT', 'TEXT', NULL, false, 10);

CREATE TEMPORARY TABLE seed_attribute_value (
    profile_key VARCHAR(50),
    attribute_code VARCHAR(50),
    value_code VARCHAR(50),
    label VARCHAR(100),
    display_order INTEGER
);

INSERT INTO seed_attribute_value (profile_key, attribute_code, value_code, label, display_order) VALUES
    ('calzado', 'material_externo', 'piel_vacuno', 'Piel Vacuno', 10),
    ('calzado', 'material_externo', 'sintetico', 'Sintético', 20),
    ('calzado', 'material_externo', 'gamuza', 'Gamuza', 30),
    ('calzado', 'material_externo', 'lona', 'Lona', 40),
    ('calzado', 'material_externo', 'textil', 'Textil', 50),
    ('calzado', 'material_externo', 'piel_exotica', 'Piel Exótica', 60),
    ('calzado', 'tipo_ajuste', 'agujeta', 'Agujeta', 10),
    ('calzado', 'tipo_ajuste', 'velcro', 'Velcro', 20),
    ('calzado', 'tipo_ajuste', 'elastico', 'Elástico', 30),
    ('calzado', 'tipo_ajuste', 'slip_on', 'Slip-on', 40),
    ('calzado', 'tipo_ajuste', 'hebilla', 'Hebilla', 50),
    ('calzado', 'tipo_ajuste', 'cierre', 'Cierre', 60),
    ('calzado', 'genero', 'dama', 'Dama', 10),
    ('calzado', 'genero', 'caballero', 'Caballero', 20),
    ('calzado', 'genero', 'unisex', 'Unisex', 30),
    ('calzado', 'genero', 'infantil', 'Infantil', 40),
    ('calzado', 'genero', 'bebe', 'Bebé', 50),
    ('calzado', 'tipo_suela', 'hule', 'Hule', 10),
    ('calzado', 'tipo_suela', 'cuero', 'Cuero', 20),
    ('calzado', 'tipo_suela', 'tpu', 'TPU', 30),
    ('calzado', 'tipo_suela', 'eva', 'EVA', 40),
    ('calzado', 'tipo_suela', 'yute', 'Yute', 50),
    ('calzado', 'color', 'negro', 'Negro', 10),
    ('calzado', 'color', 'cafe', 'Café', 20),
    ('calzado', 'color', 'rojo', 'Rojo', 30),
    ('calzado', 'color', 'blanco', 'Blanco', 40),
    ('calzado', 'color', 'azul', 'Azul', 50),
    ('calzado', 'color', 'beige', 'Beige', 60),
    ('calzado', 'color', 'vino', 'Vino', 70),
    ('calzado', 'color', 'gris', 'Gris', 80),
    ('calzado', 'color', 'rosa', 'Rosa', 90),
    ('calzado', 'color', 'multicolor', 'Multicolor', 100),
    ('calzado', 'acabado', 'mate', 'Mate', 10),
    ('calzado', 'acabado', 'charol', 'Charol', 20),
    ('calzado', 'acabado', 'gamuzado', 'Gamuzado', 30),
    ('calzado', 'acabado', 'brillante', 'Brillante', 40),
    ('calzado', 'acabado', 'oxford', 'Oxford', 50),
    ('calzado', 'acabado', 'texturizado', 'Texturizado', 60),

    ('ferreteria', 'material', 'pvc', 'PVC', 10),
    ('ferreteria', 'material', 'cobre', 'Cobre', 20),
    ('ferreteria', 'material', 'acero_galvanizado', 'Acero Galvanizado', 30),
    ('ferreteria', 'material', 'acero_inoxidable', 'Acero Inoxidable', 40),
    ('ferreteria', 'material', 'bronce', 'Bronce', 50),
    ('ferreteria', 'material', 'laton', 'Latón', 60),
    ('ferreteria', 'material', 'hierro_fundido', 'Hierro Fundido', 70),
    ('ferreteria', 'material', 'plastico', 'Plástico', 80),
    ('ferreteria', 'material', 'madera', 'Madera', 90),
    ('ferreteria', 'material', 'aluminio', 'Aluminio', 100),
    ('ferreteria', 'sistema_rosca', 'npt', 'NPT', 10),
    ('ferreteria', 'sistema_rosca', 'metrico', 'Métrica', 20),
    ('ferreteria', 'sistema_rosca', 'unificado', 'Unificada', 30),
    ('ferreteria', 'sistema_rosca', 'ninguno', 'Sin Rosca', 40),
    ('ferreteria', 'acabado_superficial', 'cromado', 'Cromado', 10),
    ('ferreteria', 'acabado_superficial', 'negro_mate', 'Negro Mate', 20),
    ('ferreteria', 'acabado_superficial', 'laton_natural', 'Latón Natural', 30),
    ('ferreteria', 'acabado_superficial', 'galvanizado', 'Galvanizado', 40),
    ('ferreteria', 'acabado_superficial', 'pintado', 'Pintado', 50),

    ('electro', 'voltaje', '110v', '110V', 10),
    ('electro', 'voltaje', '220v', '220V', 20),
    ('electro', 'voltaje', '110_220v', '110V / 220V', 30),
    ('electro', 'voltaje', 'ninguno', 'Sin Conexión Eléctrica', 40),
    ('electro', 'conectividad', 'wifi', 'Wi-Fi', 10),
    ('electro', 'conectividad', 'bluetooth', 'Bluetooth', 20),
    ('electro', 'conectividad', 'ethernet', 'Ethernet', 30),
    ('electro', 'conectividad', 'usb', 'USB', 40),
    ('electro', 'conectividad', 'smart', 'Smart', 50),
    ('electro', 'conectividad', 'ninguna', 'Ninguna', 60),
    ('electro', 'eficiencia_energetica', 'clase_a', 'Clase A', 10),
    ('electro', 'eficiencia_energetica', 'clase_b', 'Clase B', 20),
    ('electro', 'eficiencia_energetica', 'clase_c', 'Clase C', 30),
    ('electro', 'eficiencia_energetica', 'clase_d', 'Clase D', 40),
    ('electro', 'eficiencia_energetica', 'no_aplica', 'No Aplica', 50),
    ('electro', 'color', 'negro', 'Negro', 10),
    ('electro', 'color', 'blanco', 'Blanco', 20),
    ('electro', 'color', 'gris', 'Gris', 30),
    ('electro', 'color', 'acero_inoxidable', 'Acero Inoxidable', 40),
    ('electro', 'color', 'azul', 'Azul', 50),
    ('electro', 'color', 'rojo', 'Rojo', 60),

    ('abarrotes', 'temperatura_conservacion', 'ambiente', 'Temperatura Ambiente', 10),
    ('abarrotes', 'temperatura_conservacion', 'refrigerado', 'Refrigerado', 20),
    ('abarrotes', 'temperatura_conservacion', 'congelado', 'Congelado', 30),
    ('abarrotes', 'alergenos', 'gluten', 'Gluten', 10),
    ('abarrotes', 'alergenos', 'lacteos', 'Lácteos', 20),
    ('abarrotes', 'alergenos', 'huevo', 'Huevo', 30),
    ('abarrotes', 'alergenos', 'soya', 'Soya', 40),
    ('abarrotes', 'alergenos', 'cacahuate', 'Cacahuate', 50),
    ('abarrotes', 'alergenos', 'nuez', 'Nuez', 60),
    ('abarrotes', 'alergenos', 'mariscos', 'Mariscos', 70),
    ('abarrotes', 'alergenos', 'ninguno', 'Ninguno', 80);

CREATE TEMPORARY TABLE seed_attribute_root (
    profile_key VARCHAR(50),
    root_name VARCHAR(100)
);

INSERT INTO seed_attribute_root (profile_key, root_name) VALUES
    ('ferreteria', 'Ferretería'),
    ('ferreteria', 'Materiales de Construcción'),
    ('ferreteria', 'Plomería'),
    ('ferreteria', 'Baños y Cocinas'),
    ('electro', 'Electrodomésticos'),
    ('electro', 'Electrónica y TV'),
    ('abarrotes', 'Alimentos'),
    ('calzado', 'Calzado para Dama'),
    ('calzado', 'Calzado para Caballero'),
    ('calzado', 'Calzado Deportivo y Confort'),
    ('calzado', 'Calzado Infantil (Niños y Bebés)');

INSERT INTO product_attribute_profiles (company_id, profile_key, label, description)
SELECT NULL, s.profile_key, s.label, s.description
FROM seed_attribute_profile s
WHERE NOT EXISTS (
    SELECT 1 FROM product_attribute_profiles p
    WHERE p.company_id IS NULL
      AND lower(p.profile_key) = lower(s.profile_key)
);

INSERT INTO product_attribute_definitions
    (profile_id, attribute_code, label, attribute_level, kind, unit_label, required, display_order)
SELECT p.id, s.attribute_code, s.label, s.attribute_level, s.kind, s.unit_label, s.required, s.display_order
FROM seed_attribute_definition s
JOIN product_attribute_profiles p
    ON p.company_id IS NULL
   AND lower(p.profile_key) = lower(s.profile_key)
WHERE NOT EXISTS (
    SELECT 1 FROM product_attribute_definitions d
    WHERE d.profile_id = p.id
      AND d.attribute_code = s.attribute_code
);

INSERT INTO product_attribute_values (definition_id, value_code, label, display_order)
SELECT d.id, s.value_code, s.label, s.display_order
FROM seed_attribute_value s
JOIN product_attribute_profiles p
    ON p.company_id IS NULL
   AND lower(p.profile_key) = lower(s.profile_key)
JOIN product_attribute_definitions d
    ON d.profile_id = p.id
   AND d.attribute_code = s.attribute_code
WHERE NOT EXISTS (
    SELECT 1 FROM product_attribute_values v
    WHERE v.definition_id = d.id
      AND v.value_code = s.value_code
);

INSERT INTO product_attribute_profile_roots (profile_id, company_id, root_name)
SELECT p.id, NULL, s.root_name
FROM seed_attribute_root s
JOIN product_attribute_profiles p
    ON p.company_id IS NULL
   AND lower(p.profile_key) = lower(s.profile_key)
WHERE NOT EXISTS (
    SELECT 1 FROM product_attribute_profile_roots r
    WHERE r.company_id IS NULL
      AND lower(r.root_name) = lower(s.root_name)
);

DROP TABLE seed_attribute_root;
DROP TABLE seed_attribute_value;
DROP TABLE seed_attribute_definition;
DROP TABLE seed_attribute_profile;
