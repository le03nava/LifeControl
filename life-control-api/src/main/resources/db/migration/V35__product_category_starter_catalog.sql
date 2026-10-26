-- ============================================
-- V35 — Product categories: the starter catalog
-- ============================================
-- Two migrations, not one, and the split is the point. V34 is the `sat_code` column and its `CHECK`:
-- schema, additive, reversible by dropping a column. This one writes data. A reviewer reads them as
-- separate questions — "is the column right?" and "is this the catalog the user asked for?" — and a
-- catalogue of 57 rows in the middle of a column change buries the first question.
--
-- The seed is PER COMPANY, not global. `product_categories` is company-scoped (V31 header, decision
-- D1): a category row belongs to a company, so a single set of shared rows is impossible and every
-- company that exists when this migration runs gets its own copy of the catalog. That is also why this
-- runs as a `CROSS JOIN` over `companies` instead of an `INSERT ... VALUES` with hardcoded ids.
--
-- Idempotency guard, and there are two of them because there are two levels:
--
--   * A ROOT is skipped when the company already has a root whose name matches ignoring case. A
--     migration runs once per database, but this catalog is also applied to every company created
--     afterwards by `StarterProductCategoryApplier`, and a company can be reached by both paths (a
--     replay, a restored dump, a manual re-run of the applier). The guard is what makes the second
--     write a no-op instead of a violation of `uq_product_categories_sibling_name`.
--   * A CHILD is skipped when its parent already has a child whose name matches ignoring case. Same
--     reason, one level down: a company that already built "Ferretería > Herramientas Manuales" by
--     hand keeps its row and only receives the siblings it is missing.
--
-- The comparison is `lower(...)` on both sides and not `=` : the unique index is an expression index
-- over `lower(category_name)` (V31), so a case-sensitive guard would attempt an insert the index
-- rejects, turning a no-op into an error.
--
-- The same catalog also lives in Java, as `StarterProductCategoryCatalog`, because this migration only
-- reaches companies that already exist: a company created tomorrow is seeded by
-- `StarterProductCategoryListener` on `CompanyCreatedEvent`. The two copies are pinned to each other by
-- `StarterProductCategoryCatalogTest`, which parses the value rows below and compares them to the Java
-- list in both directions — the same coupling `KeycloakClaimMapperCoverageTest` applies to a shell
-- script. Editing one without the other fails that test.
--
-- Shape:
--
--   * One TEMPORARY table holds the catalog so the two inserts can read the same rows: the values are
--     written once, not once per insert. It is dropped at the end of this script, and it is a plain
--     `CREATE TEMPORARY TABLE` rather than `... ON COMMIT DROP` on purpose: `ON COMMIT DROP` only
--     fires if the migration runs inside a transaction, and Flyway's transactional mode is a
--     configuration detail this file must not depend on. An explicit `DROP TABLE` is true in both
--     modes.
--   * `parent_name` is NULL for a root, the parent's name otherwise, so the table describes the tree
--     exactly as the user wrote it — one row per line, parent first, its children after it.
--   * `sat_code` is NULL for the disabled proposals. The user delegated which extra branches to ship
--     (L2 of `odd/tasks/product-category-sat-code.md`) but not invented fiscal keys: a guessed
--     `ClaveProdServ` is worse than an absent one, and V34 made the column optional precisely so a
--     category can exist before its key is verified. They ship `enabled = false` for the same reason —
--     nothing depends on them until someone reviews the codes.
--   * The twelve enabled branches, their names and their SAT keys are the user's, verbatim (L1, S2).
--     The emoji and the "(Categoría Raíz)" marker are formatting, not part of a name. The parenthetical
--     the user wrote after each key becomes the row's `description`.
--
-- Forward-only: there is no `DELETE` to undo it. Deleting the seeded rows would also delete the
-- children a company added under them, so undoing means an operator deciding per row, not a script.
-- ============================================

CREATE TEMPORARY TABLE starter_catalog (
    parent_name VARCHAR(100),
    category_name VARCHAR(100) NOT NULL,
    description VARCHAR(255),
    display_order INTEGER NOT NULL,
    sat_code VARCHAR(8),
    enabled BOOLEAN NOT NULL
);

-- The catalog. One row per line, roots and their children interleaved as the user listed them:
-- `parent_name, category_name, description, display_order, sat_code, enabled`.
INSERT INTO starter_catalog (parent_name, category_name, description, display_order, sat_code, enabled)
VALUES
    (NULL, 'Ferretería', 'Herramientas de mano', 10, '27110000', true),
    ('Ferretería', 'Herramientas Manuales', 'Herramientas de mano no asistidas / Martillos, pinzas', 10, '27111600', true),
    ('Ferretería', 'Herramientas Eléctricas', 'Herramientas eléctricas / Taladros, esmeriles', 20, '27112700', true),
    (NULL, 'Materiales de Construcción', 'Concreto, cemento y yeso', 20, '30110000', true),
    ('Materiales de Construcción', 'Fijaciones y Tornillería', 'Tornillos', 10, '31161500', true),
    ('Materiales de Construcción', 'Cerraduras y Herrajes', 'Candados y cerraduras', 20, '31162400', true),
    (NULL, 'Plomería', 'Distribución de fluidos y gas', 30, '40140000', true),
    ('Plomería', 'Tubos y Conexiones', 'Tuberías y tubos', 10, '40141700', true),
    ('Plomería', 'Válvulas y Llaves', 'Válvulas', 20, '40141600', true),
    (NULL, 'Baños y Cocinas', 'Instalaciones de fontanería', 40, '30180000', true),
    ('Baños y Cocinas', 'Muebles de Baño', 'Inodoros, lavabos y tinas', 10, '30181500', true),
    ('Baños y Cocinas', 'Grifería y Mezcladoras', 'Grifos y tarjas', 20, '30181700', true),
    (NULL, 'Electrodomésticos', 'Aparatos electrodomésticos', 50, '52140000', true),
    ('Electrodomésticos', 'Lavadoras y Secadoras', 'Aparatos de lavandería domésticos', 10, '52141600', true),
    ('Electrodomésticos', 'Cocina (Microondas/Licuadoras)', 'Aparatos de cocina domésticos', 20, '52141500', true),
    (NULL, 'Electrónica y TV', 'Equipos de TI y telecomunicaciones', 60, '43220000', true),
    ('Electrónica y TV', 'Pantallas y Video', 'Televisión y video', 10, '45111800', true),
    ('Electrónica y TV', 'Cómputo y Accesorios', 'Computadoras e impresoras', 20, '43211500', true),
    (NULL, 'Alimentos', 'Alimentos, Bebidas y Tabaco', 70, '50000000', true),
    ('Alimentos', 'Panadería y Dulces', 'Pan dulce, galletas y bollería', 10, '50181900', true),
    ('Alimentos', 'Lácteos y Huevos', 'Productos de leche y mantequilla', 20, '50131700', true),
    (NULL, 'Calzado para Dama', 'Calzado', 80, '53110000', true),
    ('Calzado para Dama', 'Zapatos de Tacón y Plataformas', 'Calzado de etiqueta / Zapatos de vestir y tacones', 10, '53111600', true),
    ('Calzado para Dama', 'Baletas y Flats', 'Calzado informal / Zapatos de piso y casuales', 20, '53111500', true),
    ('Calzado para Dama', 'Botas y Botines', 'Botas', 30, '53111800', true),
    ('Calzado para Dama', 'Sandalias y Pantuflas', 'Sandalias y pantuflas', 40, '53112100', true),
    (NULL, 'Calzado para Caballero', 'Calzado', 90, '53110000', true),
    ('Calzado para Caballero', 'Zapatos de Vestir (Oxford, Mocasines)', 'Calzado de etiqueta', 10, '53111600', true),
    ('Calzado para Caballero', 'Calzado Casual y Clásico', 'Calzado informal', 20, '53111500', true),
    ('Calzado para Caballero', 'Botas Casuales o de Trabajo', 'Botas', 30, '53111800', true),
    (NULL, 'Calzado Deportivo y Confort', 'Calzado', 100, '53110000', true),
    ('Calzado Deportivo y Confort', 'Tenis para Correr / Entrenamiento', 'Calzado deportivo / Sneakers, running', 10, '53111900', true),
    ('Calzado Deportivo y Confort', 'Tenis Casuales (Urbano)', 'Calzado informal', 20, '53111500', true),
    (NULL, 'Calzado Infantil (Niños y Bebés)', 'Calzado', 110, '53110000', true),
    ('Calzado Infantil (Niños y Bebés)', 'Calzado Escolar', 'Calzado informal', 10, '53111500', true),
    ('Calzado Infantil (Niños y Bebés)', 'Zapatitos para Bebé', 'Calzado informal', 20, '53111500', true),
    (NULL, 'Accesorios y Cuidado', NULL, 120, '53000000', true),
    ('Accesorios y Cuidado', 'Bolsos y Carteras', 'Maletas, bolsos y mochilas', 10, '53121600', true),
    ('Accesorios y Cuidado', 'Limpieza y Cuidado del Calzado', 'Accesorios para calzado / Agujetas, plantillas, cremas, cepillos', 20, '53112300', true),
    (NULL, 'Limpieza y Químicos del Hogar', 'Productos de limpieza para el hogar', 130, NULL, false),
    ('Limpieza y Químicos del Hogar', 'Detergentes y Limpiadores', 'Detergentes, jabones y limpiadores', 10, NULL, false),
    ('Limpieza y Químicos del Hogar', 'Escobas y Cepillos', 'Escobas, trapeadores y cepillos', 20, NULL, false),
    (NULL, 'Papelería y Oficina', 'Útiles escolares y de oficina', 140, NULL, false),
    ('Papelería y Oficina', 'Artículos de Escritorio', 'Organizadores, grapas y accesorios', 10, NULL, false),
    ('Papelería y Oficina', 'Escritura y Corrección', 'Plumas, lápices y correctores', 20, NULL, false),
    (NULL, 'Eléctrico e Iluminación', 'Material eléctrico, cables y luminarias', 150, NULL, false),
    ('Eléctrico e Iluminación', 'Cables y Conductores', 'Cables, conectores e interruptores', 10, NULL, false),
    ('Eléctrico e Iluminación', 'Focos y Luminarias', 'Focos, lámparas y paneles', 20, NULL, false),
    (NULL, 'Jardinería y Exteriores', 'Herramientas y accesorios de jardín', 160, NULL, false),
    ('Jardinería y Exteriores', 'Herramientas de Jardín', 'Palas, rastrillos y mangueras', 10, NULL, false),
    ('Jardinería y Exteriores', 'Macetas y Sustratos', 'Macetas, tierra y fertilizantes', 20, NULL, false),
    (NULL, 'Automotriz', 'Accesorios y mantenimiento para automóvil', 170, NULL, false),
    ('Automotriz', 'Accesorios para Auto', 'Tapetes, fundas y refacciones', 10, NULL, false),
    ('Automotriz', 'Aceites y Fluidos', 'Aceites, anticongelantes y líquido limpiaparabrisas', 20, NULL, false),
    (NULL, 'Mascotas', 'Alimento y accesorios para mascotas', 180, NULL, false),
    ('Mascotas', 'Alimento para Mascotas', 'Croquetas y alimento húmedo', 10, NULL, false),
    ('Mascotas', 'Accesorios para Mascotas', 'Correas, platos y juguetes', 20, NULL, false);

-- Roots first: a child's insert joins its parent by name, so the parent row has to exist already.
INSERT INTO product_categories (company_id, parent_category_id, category_name, description, display_order, sat_code, enabled)
SELECT c.id, NULL, s.category_name, s.description, s.display_order, s.sat_code, s.enabled
FROM companies c
JOIN starter_catalog s ON s.parent_name IS NULL
WHERE NOT EXISTS (
    SELECT 1 FROM product_categories pc
    WHERE pc.company_id = c.id
      AND pc.parent_category_id IS NULL
      AND lower(pc.category_name) = lower(s.category_name)
);

-- Then the children, each one joined to its company's root by name. A child whose parent was skipped
-- as a duplicate is still inserted when that pre-existing parent is the same root: the guard is on the
-- child's own name, not on whether its parent came from this migration.
--
-- `enabled` is composed with the parent's: a company that already had "Ferretería" disabled keeps the
-- row and its children arrive disabled too, because an enabled child under a disabled root is the state
-- the service's cascade exists to prevent — unreachable through the tree and still offered as a valid
-- classification.
INSERT INTO product_categories (company_id, parent_category_id, category_name, description, display_order, sat_code, enabled)
SELECT c.id, p.id, s.category_name, s.description, s.display_order, s.sat_code, (s.enabled AND p.enabled)
FROM companies c
JOIN starter_catalog s ON s.parent_name IS NOT NULL
JOIN product_categories p
    ON p.company_id = c.id
   AND p.parent_category_id IS NULL
   AND lower(p.category_name) = lower(s.parent_name)
WHERE NOT EXISTS (
    SELECT 1 FROM product_categories child
    WHERE child.company_id = c.id
      AND child.parent_category_id = p.id
      AND lower(child.category_name) = lower(s.category_name)
);

DROP TABLE starter_catalog;
