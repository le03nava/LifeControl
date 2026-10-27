-- ============================================
-- V37 — Product attribute catalog (definitions, allowed values, branch attachment)
-- ============================================
-- `products.attributes` has existed since V1 as a free-form `jsonb` and it is write-only: the GIN
-- index `idx_products_attributes` is read by no query, and the only writer is the product form's raw
-- JSON textarea. The operator is therefore the schema. This migration is the schema.
--
-- Four tables, and each one answers a question the other three cannot:
--
--   * `product_attribute_profiles` — a named set of attributes ("calzado", "ferreteria"). GLOBAL by
--     default (`company_id IS NULL`) and company-owned when `company_id` is set. That nullable column
--     is the whole per-company override seam: it ships unused, and a company that needs its own
--     vocabulary adds one row instead of a new table. No rule of this schema reads it yet, so the
--     seeded path is the only path, and it costs one NULL.
--   * `product_attribute_definitions` — one attribute of a profile: the CODE that goes in the JSON, the
--     LABEL the operator reads, the LEVEL it applies to and the KIND of value it holds.
--   * `product_attribute_values` — the allowed set of an `ENUM` / `MULTI_ENUM`. A child table and not a
--     `jsonb` array: the form renders these in `display_order`, the API validates membership by
--     equality, and a value can be retired (`enabled = false`) without editing every profile that
--     mentions it.
--   * `product_attribute_profile_roots` — which profile a COMPANY'S category root uses, matched by
--     name. This is the seam that makes a global catalog attachable at all: `product_categories` rows
--     are per company (`company_id NOT NULL`, V31) and grow by one copy per company (V35), so a
--     definition cannot hold a category id — it would have to hold one per tenant, and a new company
--     would need new definitions. Matching on the root's name keeps the catalog global and needs no
--     change to `StarterProductCategoryApplier`, so a company created tomorrow is covered by the same
--     rows. The cost is stated rather than hidden: renaming a root stops resolving, and the endpoint
--     answers "no profile" instead of inventing one.
--
-- Codes, not labels, are the storage key: `{"material_externo": "piel_vacuno"}`, never
-- `{"Material Externo": "Piel Vacuno"}`. A label is presentation and can be reworded; a report filters
-- on the code. This is the single rule that decides whether these attributes are queryable later or
-- are another textarea with extra steps.
--
-- The five KINDS, and why not text everywhere:
--
--   TEXT        free string. No allowed set, no format. The fallback, not the default.
--   ENUM        one `product_attribute_values.value_code`.
--   MULTI_ENUM  an array of them, at least one. "Conectividad" is the case: Wi-Fi AND Bluetooth.
--   MEASUREMENT `{ "value": <number>, "unit": "<code>" }`. Six of the attributes the user listed —
--               medida, capacidad, contenido neto, calibre, potencia, espesor — are this shape, and
--               "1/2 pulgada" stored as a string can never be compared, sorted or converted.
--   BOOLEAN     a flag.
--
-- `attribute_level` (`PRODUCT` | `VARIANT`) is what lets ONE profile serve both screens: a shoe's
-- `material_externo` belongs to the product, its `color` to the variant, because two colours are two
-- barcodes, two stock rows and two prices. Without the level column the catalog would have to be built
-- twice, and the two copies would drift.
--
-- `unit_label` is an `ENUM`-style hint for a `MEASUREMENT` ("cm", "kg", "W") so the form can print the
-- unit next to the input before the operator types anything. The CHECK below refuses it on any other
-- kind, because a unit on a TEXT attribute is a unit nobody validates.
--
-- `required` is enforced by the service and not by the database: it depends on the profile that
-- resolves for a category, which is a join and a walk up the tree, and a CHECK cannot express that.
--
-- No `ON DELETE CASCADE` anywhere: profiles, definitions and values are retired with
-- `enabled = false`, the same soft-deletion the category tree and every other catalog here use. A hard
-- delete is not offered because it would silently blank the meaning of stored product JSON.
--
-- No GIN index on `product_variants.attributes`. `products.attributes` has one from V1 and no query
-- reads it; adding a second unread index would be a write cost on every variant save buying nothing.
-- The index belongs in the migration that introduces the first reader.
--
-- Forward-only and additive: four new tables and one nullable column, so nothing existing changes shape
-- and a rollback by hand is one `DROP COLUMN` plus four `DROP TABLE`s.
-- ============================================

CREATE TABLE product_attribute_profiles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id UUID REFERENCES companies(id),
    profile_key VARCHAR(50) NOT NULL,
    label VARCHAR(100) NOT NULL,
    description VARCHAR(255),
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- The key is unique per owner, and "global" has to be a value for that to be expressible: `NULL <> NULL`
-- would let two global profiles both claim `calzado`, the same trap V31's
-- `uq_product_categories_sibling_name` closes with a COALESCE.
CREATE UNIQUE INDEX uq_product_attribute_profiles_owner_key
    ON product_attribute_profiles (
        COALESCE(company_id, '00000000-0000-0000-0000-000000000000'::uuid),
        lower(profile_key)
    );

CREATE INDEX idx_product_attribute_profiles_company_id ON product_attribute_profiles(company_id);

CREATE TABLE product_attribute_definitions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    profile_id UUID NOT NULL REFERENCES product_attribute_profiles(id),
    attribute_code VARCHAR(50) NOT NULL,
    label VARCHAR(100) NOT NULL,
    attribute_level VARCHAR(10) NOT NULL,
    kind VARCHAR(20) NOT NULL,
    unit_label VARCHAR(20),
    required BOOLEAN NOT NULL DEFAULT false,
    display_order INTEGER NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_product_attribute_definitions_profile_code UNIQUE (profile_id, attribute_code),
    CONSTRAINT ck_product_attribute_definitions_level
        CHECK (attribute_level IN ('PRODUCT', 'VARIANT')),
    CONSTRAINT ck_product_attribute_definitions_kind
        CHECK (kind IN ('TEXT', 'ENUM', 'MULTI_ENUM', 'MEASUREMENT', 'BOOLEAN')),
    -- A unit on a kind that never validates one is a unit nobody enforces.
    CONSTRAINT ck_product_attribute_definitions_unit_is_measurement
        CHECK (unit_label IS NULL OR kind = 'MEASUREMENT'),
    -- The code is the JSON key: it has to be addressable without quoting games in a report query.
    CONSTRAINT ck_product_attribute_definitions_code_format
        CHECK (attribute_code ~ '^[a-z][a-z0-9_]{1,49}$')
);

CREATE INDEX idx_product_attribute_definitions_profile_id
    ON product_attribute_definitions(profile_id);

CREATE TABLE product_attribute_values (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    definition_id UUID NOT NULL REFERENCES product_attribute_definitions(id),
    value_code VARCHAR(50) NOT NULL,
    label VARCHAR(100) NOT NULL,
    display_order INTEGER NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_product_attribute_values_definition_code UNIQUE (definition_id, value_code),
    CONSTRAINT ck_product_attribute_values_code_format
        CHECK (value_code ~ '^[a-z0-9][a-z0-9_]{0,49}$')
);

CREATE INDEX idx_product_attribute_values_definition_id
    ON product_attribute_values(definition_id);

CREATE TABLE product_attribute_profile_roots (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    profile_id UUID NOT NULL REFERENCES product_attribute_profiles(id),
    company_id UUID REFERENCES companies(id),
    root_name VARCHAR(100) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- One attachment per root name per owner, and a company row overrides the global one — that is the
-- whole override mechanism, so the uniqueness has to be per owner rather than global, with the same
-- COALESCE treatment the profile key uses.
CREATE UNIQUE INDEX uq_product_attribute_profile_roots_owner_root
    ON product_attribute_profile_roots (
        COALESCE(company_id, '00000000-0000-0000-0000-000000000000'::uuid),
        lower(root_name)
    );

CREATE INDEX idx_product_attribute_profile_roots_profile_id
    ON product_attribute_profile_roots(profile_id);

ALTER TABLE product_variants ADD COLUMN attributes JSONB;
