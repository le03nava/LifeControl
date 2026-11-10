-- ============================================
-- V93 — a purchase order states its own money: currency, subtotal, tax_total, total_amount
--        (S1, S3 and S4 of odd/tasks/purchase-order-money.md, T2)
-- ============================================
-- `purchase_orders` has carried no money column at all since `V1:317-329` — id, order_number, supplier_id,
-- company_store_id, payment_method_id, status_id, comments, enabled, created_at, updated_at — and every later
-- `ALTER` (`V41`–`V72`) added company, the supplier FK, document identity, typed status and RLS, never money.
-- The only amount in the module is the line's, so nothing can answer what a document costs; the screen
-- answers it by summing rows in the browser with a `'MXN'` literal, a number the server never states and no
-- other client can reproduce. `V92` gave the line one definition at four decimals; this file gives the
-- document the four columns, and the derivation that keeps them equal to the lines under it.
--
-- WHY THE DERIVATION IS A TRIGGER AND NOT A SERVICE METHOD. Sales recomputes its document from
-- `SalesOrderService#recomputeBreakdown` under the order's row lock, and that writer is right there because a
-- sale has a service path that owns the whole mutation. Purchases has no equivalent: three routes insert and
-- update lines (`PurchaseOrderService:243`, `:333`, `:446`, `:491`), one soft-deletes them (`:516-521`), and a
-- Java recompute would be the only thing standing between a line mutation and a stale document — the silent
-- defect shape this unit exists to end. A trigger cannot be forgotten by the next route that adds a line.
--
-- WHY IT LOCKS THE PARENT BEFORE IT SUMS. Under `READ COMMITTED` the sum is taken from the snapshot the
-- statement opened with, so two concurrent line writes on one order would each compute a total missing the
-- other's line and the second writer would overwrite the first with the wrong number. `SELECT … FOR UPDATE` on
-- the parent, **before** the aggregate, serializes the pair: the second writer waits for the first to commit
-- and then reads a sum that includes it. That is M10's shape in sales (`SalesOrderService:422-426`), which is
-- why the lock statement comes first and not after the numbers are known.
--
-- WHY THE SUM FILTERS `enabled`. `deletePurchaseOrderDetail` is a **soft** delete — `detail.setEnabled(false)`
-- at `PurchaseOrderService:517-518` — so a line a user removed still owns its row. Summing without that filter
-- would keep charging the document for a deleted line, which is a wrong total nobody would look for.
-- `SalesOrderItemRepository.sumMoneyParts:41-46` sums `i.enabled = true` for the same reason.
--
-- WHY THE CURRENCY IS FROZEN ON INSERT AND NEVER RE-DERIVED. `V80` decided this for a sale: an order that
-- recomputed its currency from its company on every read would restate a historical document the moment the
-- company changed its currency — the shape `V57` and issue #829 already had to repair once. So the trigger is
-- `BEFORE INSERT` and nothing else, and an `UPDATE` that names `currency` is not intercepted. There is no
-- `DEFAULT`: a default is the silent `MXN` ADR-0002 decision 2 (D-c) forbids, one layer below the screen.
--
-- WHY TWO WINDOWS AND NOT ONE. The currency backfill **reads `companies`** as well as writing
-- `purchase_orders`, and both are armed AND FORCEd — `V53:108` arms `companies` by its own `id`, `V53:126`
-- arms `purchase_orders`, and `V52`'s `apply_tenant_isolation` sets `FORCE`. Under `FORCE` the owner — the role
-- delivery migrates as — reads zero rows, so the join would update nothing and the `SET NOT NULL` below would
-- fail with a message about a column instead of about the window. Opening both windows is what makes the
-- backfill reach the rows it means to, and the `SET NOT NULL` plus the two `CHECK`s are the assertion that it
-- did. `V64:47`, `V66:58`, `V69:54` and `V72:73` open and close this same pair for the same reason.
--
-- WHY THE TWO CHECKS ARE ADDED INSIDE THE WINDOW. A `CHECK` added to a populated table validates every row,
-- and that scan runs with the policy the role has; under `FORCE` an owner-only scan sees nothing, so the check
-- would be installed having judged no row — the shape `V64:30-42` records for `products`, where «read zero
-- rows» and «nothing wrong» print the same thing. Importing the constraint while the window is still open
-- makes the validating scan and the backfill run under one visibility. This is the only ordering difference
-- from `V84`, which re-forces before it adds its equality.
--
-- WHAT THIS FILE DOES NOT DO, named so it is not discovered in the diff:
--   * **No `discount_total`.** A sale carries one because a sale has discounts; a purchase order has no
--     discount concept in the tree, and a column with no writer is the defect `AGENTS.md` names
--     (`variant_listing.enabled`). D4 states it as a decision.
--   * **No tax.** S7: `tax_total` exists from here because `ck_po_order_total` needs it, and it derives to 0 in
--     this slice. `purchase-order-line-tax` adds a term to `purchase_order_money()` and fills it in place
--     instead of a second migration re-deriving money that was already derived once.
--   * **No policy change.** `purchase_orders` is armed since `V53` and this file only opens and closes the
--     `FORCE` flag around its own statements, exactly as `V64` does; no `CREATE POLICY`, no key column.
--   * **No change to `enabled`, the Draft guard, `received_quantity` or any route.** S8: the only observable
--     changes this unit makes are the four response fields and a line `total` that carries four decimals.

-- ─── 1. the four columns ────────────────────────────────────────────────────────────────────────
-- `currency` is added nullable and becomes `NOT NULL` once the backfill has stated it: adding it `NOT NULL`
-- with no default is impossible on a populated table, and adding it with a default is the silent currency this
-- file just refused. The three money columns are `NOT NULL DEFAULT 0`, so the `ALTER` rewrites nothing and the
-- backfill below is what gives them a number.
ALTER TABLE purchase_orders
    ADD COLUMN currency     CHAR(3) REFERENCES currency (code),
    ADD COLUMN subtotal     NUMERIC(19, 4) NOT NULL DEFAULT 0,
    ADD COLUMN tax_total    NUMERIC(19, 4) NOT NULL DEFAULT 0,
    ADD COLUMN total_amount NUMERIC(19, 4) NOT NULL DEFAULT 0;

-- ─── 2. the two windows ────────────────────────────────────────────────────────────────────────
ALTER TABLE purchase_orders NO FORCE ROW LEVEL SECURITY;
ALTER TABLE companies       NO FORCE ROW LEVEL SECURITY;

-- ─── 3. the currency each order was already written with ───────────────────────────────────────
-- The company's currency today, and it is the best answer available: a purchase order never recorded one, so
-- «the currency the company states now» is the currency it was written with unless the company changed it
-- since — a divergence no row of this schema can date. Stated rather than hidden, because a backfill that
-- claims to know a historical fact it cannot know is worse than one that says which fact it used.
UPDATE purchase_orders po
   SET currency = c.currency
  FROM companies c
 WHERE c.id = po.company_id;

-- ─── 4. the money each order already has, from its own enabled lines ──────────────────────────
-- `SUM` over no rows is `NULL`, and an order with no lines legitimately costs nothing, so the coalesce is the
-- statement and not a guard around it. `tax_total` is left at the `0` the column was added with: this slice
-- states no tax, and writing it here would be a second place that has to know.
UPDATE purchase_orders po
   SET subtotal     = parts.subtotal,
       total_amount = parts.subtotal
  FROM (SELECT d.purchase_order_id AS order_id,
               COALESCE(SUM(d.subtotal), 0) AS subtotal
          FROM purchase_order_details d
         WHERE d.enabled = true
         GROUP BY d.purchase_order_id) parts
 WHERE parts.order_id = po.id;

-- ─── 5. the deferred judgements run now, while both windows are open ──────────────────────────
-- `trg_purchase_orders_transition_recorded` (`V69:79-82`) is `DEFERRABLE INITIALLY DEFERRED`, so the two
-- `UPDATE`s above queued one event per row and PostgreSQL refuses an `ALTER TABLE` while any are pending
-- (`SQLSTATE 55006`, measured in `V80`, `V84` and `V85` for this same statement shape). The judgement itself
-- changes nothing — the state did not move, and `assert_document_transition_recorded` returns early on a
-- state-preserving `UPDATE` (`V68:99-101`) — but it has to be discharged before the DDL below regardless.
SET CONSTRAINTS ALL IMMEDIATE;

-- ─── 6. the promise the backfill just made, and the two checks ────────────────────────────────
ALTER TABLE purchase_orders
    ALTER COLUMN currency SET NOT NULL;

ALTER TABLE purchase_orders
    ADD CONSTRAINT ck_po_order_total
        CHECK (total_amount = subtotal + tax_total),
    ADD CONSTRAINT ck_po_order_parts_not_negative
        CHECK (subtotal >= 0 AND tax_total >= 0 AND total_amount >= 0);

COMMENT ON CONSTRAINT ck_po_order_total ON purchase_orders IS
    'S3 of odd/tasks/purchase-order-money.md: the document states its parts and the two of them add up to '
    'total_amount exactly. Purchases has no discount, so unlike ck_order_total on sales_orders there is no '
    'third term and no rounding adjustment: a difference of a ten-thousandth here is a bug, not an artifact.';

COMMENT ON CONSTRAINT ck_po_order_parts_not_negative ON purchase_orders IS
    'S3: every part of a purchase order''s money is a non-negative number. It is what makes the derivation '
    'unable to state a negative document from non-negative lines.';

-- ─── 7. the currency, derived on insert and frozen afterwards ─────────────────────────────────
CREATE FUNCTION purchase_order_currency() RETURNS trigger AS $$
BEGIN
    NEW.currency := (SELECT c.currency FROM companies c WHERE c.id = NEW.company_id);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- `BEFORE INSERT` and nothing else, so no `UPDATE` can restate a document's currency (S4). Name order costs
-- nothing: this is the only BEFORE trigger `purchase_orders` carries — `trg_purchase_orders_status_bridge`
-- (`V66`) was dropped by `V71` with the column it bridged from — and the one AFTER trigger,
-- `trg_purchase_orders_transition_recorded`, is a different timing.
CREATE TRIGGER trg_purchase_orders_currency
    BEFORE INSERT ON purchase_orders
    FOR EACH ROW EXECUTE FUNCTION purchase_order_currency();

COMMENT ON FUNCTION purchase_order_currency() IS
    'S4: currency = the company''s currency at the instant the order is created, derived by the schema so no '
    'client and no service can state one. A later change of the company''s currency does not rewrite it, which '
    'is why the trigger does not listen to UPDATE.';

-- ─── 8. the document follows its lines ────────────────────────────────────────────────────────
CREATE FUNCTION purchase_order_money() RETURNS trigger AS $$
DECLARE
    v_order_id uuid;
    v_subtotal NUMERIC(19, 4);
    v_tax      NUMERIC(19, 4);
BEGIN
    -- One expression for both events: a delete has no NEW, an insert has no OLD.
    v_order_id := COALESCE(NEW.purchase_order_id, OLD.purchase_order_id);

    -- The lock, before the aggregate that depends on it. See the header.
    PERFORM 1 FROM purchase_orders WHERE id = v_order_id FOR UPDATE;

    SELECT COALESCE(SUM(d.subtotal), 0)
      INTO v_subtotal
      FROM purchase_order_details d
     WHERE d.purchase_order_id = v_order_id
       AND d.enabled = true;

    -- The successor's term goes here: `+ COALESCE(SUM(d.tax_amount), 0)` over the same lines
    -- (odd/tasks/purchase-order-line-tax.md). It is 0 today because this slice states no tax.
    v_tax := 0;

    UPDATE purchase_orders
       SET subtotal     = v_subtotal,
           tax_total    = v_tax,
           total_amount = v_subtotal + v_tax
     WHERE id = v_order_id;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- `AFTER` and not `BEFORE`, and on every event, because what it derives is the **parent** row: a BEFORE trigger
-- on the line could not see a delete's row at all, and a soft delete (`enabled = false`) is an `UPDATE` this
-- list must not narrow away. `AFTER` also means the line's own `BEFORE` derivation (`V92`) has already written
-- `subtotal` when this reads it, whatever the name order does.
CREATE TRIGGER trg_purchase_order_details_document_money
    AFTER INSERT OR UPDATE OR DELETE ON purchase_order_details
    FOR EACH ROW EXECUTE FUNCTION purchase_order_money();

COMMENT ON FUNCTION purchase_order_money() IS
    'S3: recomputes subtotal = sum(details.subtotal), tax_total = 0 (the successor adds the term) and '
    'total_amount = subtotal + tax_total on the parent order, under that order''s row lock taken before the '
    'aggregate so two concurrent line writers cannot each compute a total missing the other''s line. Only '
    'enabled lines count, because deletePurchaseOrderDetail is a soft delete.';

-- ─── 9. the self-check: does every document state the sum of its enabled lines? ───────────────
-- Inside the window, and that is the whole point: this question is a **scan**, and this is the only moment
-- the owner can make one. Under `FORCE` the same query reads zero rows and answers «nothing wrong» — the
-- defect `V64:30-42` records for `products`, where «read nothing» and «nothing wrong» print the same thing.
-- The `FORCE` flag asserted back in the next section is what proves this scan had the visibility it was
-- written for.
DO $$
DECLARE
    v_bad_id uuid;
    v_parts  NUMERIC(19, 4);
    v_lines  NUMERIC(19, 4);
BEGIN
    SELECT po.id, po.subtotal, COALESCE(SUM(d.subtotal), 0)
      INTO v_bad_id, v_parts, v_lines
      FROM purchase_orders po
      LEFT JOIN purchase_order_details d
             ON d.purchase_order_id = po.id
            AND d.enabled = true
     GROUP BY po.id, po.subtotal, po.total_amount
    HAVING po.subtotal <> COALESCE(SUM(d.subtotal), 0)
        OR po.total_amount <> po.subtotal
     LIMIT 1;

    IF v_bad_id IS NOT NULL THEN
        RAISE EXCEPTION
            'V93 read order % stating subtotal = % and a total_amount that is not the same number, while the '
            'sum of its enabled lines is %. The backfill states that sum directly, so a row here can only mean '
            'the UPDATE reached no row.', v_bad_id, v_parts, v_lines;
    END IF;
END $$;

-- ─── 10. the windows close, and both flags are asserted back ─────────────────────────────────
ALTER TABLE purchase_orders FORCE ROW LEVEL SECURITY;
ALTER TABLE companies       FORCE ROW LEVEL SECURITY;

-- Not a formality: a `NO FORCE` an aborted deploy leaves open lets the owner of every armed table read
-- across tenants, and `V64:50-58` records that this window fails silently by construction — so the state is
-- read back from the catalog instead of trusted to the statement above.
DO $$
DECLARE
    v_open text;
BEGIN
    SELECT string_agg(c.relname, ', ' ORDER BY c.relname)
      INTO v_open
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE c.relkind = 'r'
       AND n.nspname = current_schema()
       AND c.relname IN ('purchase_orders', 'companies')
       AND NOT c.relforcerowsecurity;

    IF v_open IS NOT NULL THEN
        RAISE EXCEPTION
            'V93 found % without FORCE ROW LEVEL SECURITY after the backfill. The window this file opens is a '
            'NO FORCE, and leaving it open lets the owner of every armed table read across tenants.', v_open;
    END IF;
END $$;
