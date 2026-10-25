-- ============================================
-- V29 — a product variant declares its measure unit
-- ============================================
-- The catalog (`measure_units`, V1) held the SAT `c_ClaveUnidad` codes but had NO inbound
-- reference anywhere in the schema: nothing could say in which unit a quantity was expressed.
--
-- The relation belongs to the VARIANT and not to the product. A product is the article
-- ("Queso"), while the variant is the sellable thing that owns the barcode and the per-store
-- price row — and the same article is legitimately sold in pieces AND in kilograms. A
-- product-level column could express only one of those two, which is the case this change
-- exists for. The variant-level column also keeps the CFDI pair expressible per concept:
-- `products.sat_code` supplies `c_ClaveProdServ`, this column supplies `c_ClaveUnidad`.
--
-- Nullable with no default and no backfill, on purpose, exactly like V28's `created_by`: every
-- row that existed before this migration keeps NULL, because its unit was never declared.
-- Defaulting them to 'H87' (Pieza) would invent a fact about stock that is already stored and
-- would silently reinterpret it; an honest NULL says "not declared yet". The application treats
-- NULL as "no unit", which is the behavior every variant had until now.
--
-- The foreign key is unnamed, matching the baseline and the V9/V10/V12/V13/V14 style.
-- ============================================

ALTER TABLE product_variants
    ADD COLUMN measure_unit_id UUID REFERENCES measure_units(id);

CREATE INDEX idx_product_variants_measure_unit ON product_variants(measure_unit_id);
