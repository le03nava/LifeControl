-- ============================================
-- V51 — The purchase-order store key: the company contract V43 could not write
-- ============================================
--
--  NOTHING IS DISCARDED AND NOTHING IS RE-DERIVED HERE, AND THIS HEADER HAS TO SAY WHY
--
--  This migration adds a key, not a derivation. V43 already derived `purchase_orders.company_id` from the
--  order's own store, with the same walk `CompanyStoreOwnerResolver` performs, so on any database this
--  codebase has written to, the column already agrees with the store it names.
--
--  An `UPDATE … FROM company_stores` was written first and then removed, because measurement showed it
--  could only ever do harm: V43 also pins the same column to the SUPPLIER's company
--  (`fk_purchase_orders_supplier_same_company`), and a supplier belongs to exactly one company. So for a
--  row whose store and supplier belong to different companies — the only row a re-derivation could
--  "repair" — setting the owner from the store produces a pair the supplier key immediately refuses, with
--  `insert or update on table "purchase_orders" violates foreign key constraint
--  "fk_purchase_orders_supplier_same_company"`. Such a row names two tenants at once; there is no value
--  of `company_id` that satisfies both keys, and repairing it would mean deciding which of the two the
--  order really belongs to. That is a human decision, not a migration's.
--
--  So the migration refuses instead of guessing, and it refuses with a readable failure rather than a raw
--  constraint error: the guard below counts the rows that name two tenants, names the count, and stops
--  before the key is added. Discarding them is also out of the question — V40 and V42 could discard
--  because their rows carried no owner at all and issue #672 authorized that; #673 authorizes nothing of
--  the kind, and a purchase order that disagrees with itself is not unattributable, it is contradictory.
--
-- ============================================
--
-- WHY
-- ---
-- V43 tied `purchase_orders.company_id` to the SUPPLIER and then wrote down what it could not do
-- (V43:70-71): the key that would pin the order to its STORE —
-- `(company_id, company_store_id) -> company_stores (company_id, id)` — needs a `company_id` on
-- `company_stores` and a `uq_company_stores_company_id` target to reference. `company_stores` had neither,
-- because its company was reached through zone → region → country.
--
-- The three levels gave it both: V48 added the column and the unique pair, V49 made the column `NOT NULL`
-- and pinned the store to its own zone. This is the line V43 could not write, and it is the last of the
-- axis: after it a purchase order's company is tied to its supplier AND its store by the database, and the
-- two checks in `PurchaseOrderService` stop being the only thing holding the rule.
--
-- WHY THE PLAIN FOREIGN KEY GOES
-- ------------------------------
-- Each constraint is checked on its own, so `purchase_orders_company_store_id_fkey` beside
-- `fk_purchase_orders_store_same_company` would still admit the pair the composite one refuses: the plain
-- key only proves the store exists, not that it belongs to this company. Dropping it makes the composite
-- key the ONLY path from an order to a company store — the move V43 made for `purchase_orders.supplier_id`.
--
-- WHY NO SEPARATE `fk_purchase_orders_company` TO `companies`
-- ----------------------------------------------------------
-- The two composite keys already make `company_id` a real company: it has to equal the company of a row of
-- `suppliers` and of a row of `company_stores`, and both carry their own path to `companies`. A third
-- constraint would answer the same question a third time (V43).
--
-- WHAT THIS LEAVES TO THE SERVICE, ON PURPOSE
-- -------------------------------------------
-- Nothing about the order's own tenancy. What stays a service rule is everything that is not tenancy —
-- that the store and the supplier are both reachable by the caller, that the payment method and the status
-- are valid transitions, and the numbering rules. Issue #673's non-goals keep those out of the schema, and
-- `companies.rfc` stays globally unique because a company has no owner company.
--
-- Forward-only. Undoing it by hand means dropping `fk_purchase_orders_store_same_company` and re-adding
-- `purchase_orders_company_store_id_fkey` (measured, not guessed: the name V1's inline `REFERENCES`
-- produced). `purchase_orders.company_id` stays either way — V43 owns it — and no row is lost in either
-- direction.
-- ============================================

-- ─── refuse a row that names two tenants, naming the count ───────────────────
DO $$
DECLARE
    contradicting bigint;
BEGIN
    SELECT count(*) INTO contradicting
    FROM purchase_orders po
    JOIN company_stores cs ON cs.id = po.company_store_id
    WHERE cs.company_id <> po.company_id;

    IF contradicting > 0 THEN
        RAISE EXCEPTION
            'V51 cannot pin purchase_orders to its store: % row(s) name a store of another company than the one their supplier requires. No owner satisfies both keys, and this migration does not decide which side is wrong. Resolve them by hand (the order''s company must equal both its store''s and its supplier''s) and re-run.',
            contradicting;
    END IF;
END $$;

-- A plain FK beside a composite one still admits the pair the composite refuses.
ALTER TABLE purchase_orders DROP CONSTRAINT purchase_orders_company_store_id_fkey;

ALTER TABLE purchase_orders
    ADD CONSTRAINT fk_purchase_orders_store_same_company
    FOREIGN KEY (company_id, company_store_id)
    REFERENCES company_stores (company_id, id);
