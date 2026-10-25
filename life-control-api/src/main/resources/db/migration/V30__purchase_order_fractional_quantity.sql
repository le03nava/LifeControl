-- ============================================
-- V30 — a purchase order line carries a fractional quantity
-- ============================================
-- V29 put the unit of measure on the variant, which made the quantity of an article mean
-- something: 2 Kgm of cheese is 2 kilograms. What V29 could not do was EXPRESS a fraction. The
-- sales side, the ledger and both stock balances were already DECIMAL(12,2) — a variant measured
-- in kilograms could be sold and stocked as 2.5 — while the purchase order line and its received
-- quantity were INTEGER, and the receipt boundary rejected any non-whole quantity with a 400.
-- A warehouse could therefore ship half a kilo and never order it or receive it.
--
-- These two columns become DECIMAL(12,2), the same precision and scale as
-- `goods_receipt_items.quantity_received` and both stock columns, so a quantity means the same
-- thing everywhere it is stored. No row is rewritten and no value changes: every INTEGER converts
-- exactly, which is why no backfill and no `USING` clause is needed.
--
-- The two CHECK constraints are not dropped and not recreated: PostgreSQL revalidates them when
-- the column type changes, so `quantity > 0` and `received_quantity >= 0` keep guarding the column
-- under its new type. Recreating them would only risk a window without the constraint on a table
-- that already holds rows.
ALTER TABLE purchase_order_details
    ALTER COLUMN quantity TYPE DECIMAL(12, 2),
    ALTER COLUMN received_quantity TYPE DECIMAL(12, 2);
