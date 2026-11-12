-- ============================================
-- V96 — a receipt states the price the units were received at
--        (S1 and S8 of odd/tasks/purchase-cost-into-inventory.md, T1; issue #853)
-- ============================================
-- What a purchase PAID did not decide what the inventory COSTS. `goods_receipt_items` carried only
-- `quantity_received` (`V12:39-48`), so every inbound movement booked `product_variant_store_stock.cost_price`
-- (`StockLedgerEngine#bookLedgerInbound`) — the declared catalogue cost, whose only writer is the per-store
-- editor (`ProductVariantService:492-493`) — and a receipt of a line priced differently from that declaration
-- booked the old cost, silently and permanently. ADR-0001's `## Costing and valuation` already states the rule:
-- "a receipt takes the purchase line's unit cost". This column is where that number is kept as history, and the
-- movement it produces is what the ledger's valuation reads.
--
-- WHY THE NUMBER IS STORED AND NOT READ BACK FROM THE ORDER. The receipt is an immutable fact:
-- `goods_receipts.received_at` comes from `@PrePersist` and nothing in this tree edits or deletes a receipt. The
-- purchase line is not immutable: `PurchaseOrderService:583` rewrites `unit_price` on a line edit and `:385`
-- replaces every line of a document, neither of which asks whether the line was already received. A reader that
-- joined the order would therefore answer with the price the line carries TODAY, which is not what those units
-- cost — the same reason `V94` stores the resolved `tax_rate` instead of joining the catalog. The stored copy is
-- also what a later costing model consumes without re-deriving anything: the ledger movement it produced carries
-- the same number, per location.
--
-- WHY FOUR DECIMALS, AND WHY NULLABLE. `NUMERIC(19,4)` is the scale every money part of this tree has carried
-- since `V80`: `sales_order_items.subtotal`, `purchase_orders.total_amount` (`V93`), `purchase_order_details`
-- (`V92`) and `stock_movement.unit_cost` (`V60`). The purchase line's `unit_price` is `DECIMAL(12,2)`
-- (`V1:346`), so the copy fits without rounding and stays exactly comparable with the movement it produced.
-- NULL means "this receipt states no price" and is a legitimate value for the rows that already exist: the
-- number was never recorded anywhere, so the only source left is the line's current `unit_price`, which is a
-- guess — see below.
--
-- WHY THERE IS NO BACKFILL. Every receipt written before this file received its units at a price nobody wrote
-- down. Restating those rows from `purchase_order_details.unit_price` would not recover that price: the line can
-- be edited or replaced after the receipt, so the number would be today's, not the one the receipt saw. A NULL
-- says "this receipt predates the snapshot"; a guessed number would be indistinguishable from a recorded one and
-- would be trusted. Nothing is rewritten here either way, which also means the ledger's history keeps the costs
-- it already booked.
--
-- WHY THE AVERAGE DID **NOT** GET A COLUMN HERE, AND WHERE IT LIVES INSTEAD. The record proposed a
-- `product_variant_store_stock.average_cost` beside `cost_price`, and the codebase measured that proposal wrong
-- about the home of the number: `stock_valuation.avg_unit_cost` (`V60:221-230`) already is the weighted moving
-- average of one `(variant, store)`, maintained incrementally by `V61`'s `project_stock_movement` →
-- `refresh_stock_valuation` → `replay_stock_valuation` — Σ(inbound quantity × its `unit_cost`) − Σ(allocation
-- quantity × its frozen `unit_cost`), divided by the quantity on hand, rounded to four decimals. That is the same
-- quantity the record wanted to derive, computed from the ledger instead of beside it, and it is refreshed by the
-- one writer every movement passes. A second average written from Java would be a second answer to one question:
-- it would silently disagree with the projection after any `ADJUSTMENT_IN` (which books the declared cost) or any
--         consumption (which freezes a cost in `stock_allocation`), and no reader could say which one was the cost. The
-- decision is therefore to read the ledger's number, read-only, and this file adds no average column at all.
--
-- WHY THIS FILE NEEDS NO ROW-LEVEL SECURITY WINDOW. `goods_receipt_items` carries no `company_id`, so
-- `apply_tenant_isolation` (`V52`) never armed it and the owner — the role delivery migrates as — sees every
-- row. The statements below are catalogue changes that write no row, so there is nothing for a `FORCE`d policy
-- to filter even if one existed. That is also what makes the self-check at the end legitimate rather than
-- vacuous, in contrast with the trap `V64:30-42` records for a `FORCE`d table whose owner reads nothing.
--
-- WHAT THIS FILE DOES NOT DO, named so it is not discovered in the diff:
--   * **No average column, no revaluation.** The cost of the stock that is already there is what the ledger
--     already booked for it; nothing is restated, and the average a reader sees is the projection's.
--   * **No `cost_price` change.** The declared cost keeps its meaning, its `NUMERIC(12,2)` and its single manual
--     writer. It stays the fallback for a receipt that states no price, and it stays what the purchase screen
--     pre-fills (`detail-table.ts:447`).
--   * **No outbound change.** ADR-0001 states that an outbound row carries no cost of its own and that
--     `stock_allocation.unit_cost` holds what the units consumed, resolved by `companies.costing_method`; that
--     stays exactly as `V60`/`V61` wrote it.

-- ─── 1. the snapshot: one nullable number, no row touched ───────────────────────────────────────
-- `unit_cost >= 0` is the invariant the source already holds: `purchase_order_details.unit_price` carries the
-- same CHECK (`V1:346`), so a copy of a legal price can never fail it and a write that does fail it is stating
-- something no purchase line could have stated.
ALTER TABLE goods_receipt_items
    ADD COLUMN unit_cost NUMERIC(19, 4),
    ADD CONSTRAINT ck_goods_receipt_items_unit_cost CHECK (unit_cost IS NULL OR unit_cost >= 0);

COMMENT ON COLUMN goods_receipt_items.unit_cost IS
    'S1/S8 of odd/tasks/purchase-cost-into-inventory.md: the purchase price this line was received at, taken '
    'from purchase_order_details.unit_price (the base, never total) and written once — the receipt it belongs '
    'to is immutable. The same number is what the RECEIPT movement it books carries in stock_movement.unit_cost, '
    'and the weighted average a reader sees is stock_valuation.avg_unit_cost, derived by V61 from exactly these '
    'rows. NULL on the rows written before V96: that price was never recorded, and the line''s current '
    'unit_price is not it.';

-- ─── 2. the self-check: the shape the policy depends on, read back from the catalog ─────────────
-- Not a data check — this file writes no row and the table may be empty. What it verifies is the pair of
-- properties the decision needs and an edited `ALTER` could silently lose: four decimals (so the copy of a
-- two-decimal price is exact and comparable with the ledger's `unit_cost`) and nullability (so "this receipt
-- predates the snapshot" is representable instead of forcing a number nobody observed).
DO $$
DECLARE
    v_prec     integer;
    v_scale    integer;
    v_nullable text;
BEGIN
    SELECT c.numeric_precision, c.numeric_scale, c.is_nullable
      INTO v_prec, v_scale, v_nullable
      FROM information_schema.columns c
     WHERE c.table_schema = 'public'
       AND c.table_name   = 'goods_receipt_items'
       AND c.column_name  = 'unit_cost';

    IF NOT FOUND THEN
        RAISE EXCEPTION
            'V96 did not find goods_receipt_items.unit_cost right after adding it. The column is the whole '
            'point of this file, so its absence means the ALTER statement did not run against this schema.';
    END IF;

    IF v_prec <> 19 OR v_scale <> 4 THEN
        RAISE EXCEPTION
            'V96 added goods_receipt_items.unit_cost as NUMERIC(%,%), and the record states NUMERIC(19,4): the '
            'scale the ledger''s own unit_cost uses (V60), which is what makes the snapshot and the movement it '
            'produced the same number instead of two roundings of it.', v_prec, v_scale;
    END IF;

    IF v_nullable <> 'YES' THEN
        RAISE EXCEPTION
            'V96 added goods_receipt_items.unit_cost as NOT NULL, so the receipts written before this file would '
            'have no value to hold and the migration would have had to invent one. NULL is the honest value: the '
            'price those units were received at was never recorded.';
    END IF;
END $$;
