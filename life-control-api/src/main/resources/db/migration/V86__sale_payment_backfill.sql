-- ============================================
-- V86 — the payments a completed sale already took (ADR-0002 step 4; T5's owed backfill)
-- ============================================
-- ADR-0002 step 4, verbatim: «**For orders already `Completed`, insert one `sale_payment` per order** with
-- `amount = total_amount`, `direction = 'CHARGE'`, the order's `payment_method_id` and `paid_at =
-- coalesce(o.updated_at, o.order_date)` — **not `now()`**, which would date every historical payment the
-- day of the deploy and make every cash report by date wrong from that minute on. The `actor` is the
-- order's `user_id`, or the literal `'unknown:migration'` where it is null, because the column is
-- `NOT NULL` and a backfill is not allowed to invent a person. Nothing else is invented: the row says "one
-- payment, whole amount, method as recorded".»
--
-- WHY THIS FILE EXISTS. `V83` created `sale_payment` empty, and `T6a`'s `chargeSalesOrder` is the only
-- thing that has written one since. Every document that reached `COMPLETED` before that charge wrote its
-- row is therefore a sale that took money and has no row saying it was paid: its whole record of how it was
-- paid is the nullable `sales_orders.payment_method_id` column (`V1:478`). This file reads that column into
-- the ledger the rest of ADR-0002 is built on, and `V87` can then drop it.
--
-- WHY IT IS ONE ROW PER ORDER AND NOT ONE PER LINE OR ONE PER TENDER. The old column stated exactly one
-- method for the whole document, so one row stating the whole amount through that method is the whole of
-- what the tree knows. Splitting the total across rows would invent a split nobody recorded; leaving the
-- tender pair null is the same restraint (nobody counted what the customer handed over, and
-- `ck_payment_tender` accepts the pair being absent together), and so is writing no `reference`.
--
-- WHAT IT REFUSES, AND WHY THAT IS THE DECISION AND NOT AN OMISSION (D-a; correction (c) of
-- `odd/tasks/money-first-class.md`). Two sets of completed documents cannot be represented by a row, and
-- neither is decided here:
--
--   1. `payment_method_id IS NULL`, because `sale_payment.method_id` is `NOT NULL` (`V83:79`). A sentinel
--      method in a global catalog would be visible to every tenant and would state how a sale was paid when
--      the data never said — the same reasoning `D-a` records for step 4, and the set
--      `docker/scripts/preflight-payment-methods.sh` measures before a deploy: «Skip the order and report
--      it — and measure before deciding», and at a non-zero count «it is taken per row with the list in
--      hand».
--   2. `total_amount = 0`, because `sale_payment.amount` carries `CHECK (amount > 0)` (`V83:83`). The only
--      row that would fit is a ten-thousandth that is not money, or a row stating what nobody paid.
--
-- Both are counted and named with `RAISE NOTICE`, with their ids, before this file finishes: a row that
-- says "unknown" is true, and an invented one states a fact the data never did. Neither aborts the deploy,
-- because neither is a reason the other rows cannot be stated — but the set they name is a decision an
-- operator takes per row, and `V87` will refuse to drop the column while any of them is still unpaid.
--
-- WHY THE INSERT IS IDEMPOTENT. `sale_payment` is append-only twice over — the header's fourth claim
-- (`V83:19-29`) and the trigger plus the `REVOKE`/`GRANT` pair that implements it (`V83:143-168`): a trigger
-- refuses `UPDATE` and `DELETE` for every role, including the owner, and the app role holds `SELECT, INSERT`
-- only. A row written twice can never be corrected, so the `NOT EXISTS` is what makes a re-run — a retried
-- deploy, a restored dump replayed, or this file executed a second time — append nothing instead of stating
-- the sale twice. The closing self-check holds the result to exactly one row per document rather than to at
-- least one.
--
-- THE WINDOW, AND WHY IT IS TWO TABLES. `V65` armed **and** FORCEd `sales_orders`, and `V83` armed
-- `sale_payment` the same way, so the owner — the role Flyway runs as, and the role that holds the tables —
-- reads and writes nothing under either policy. Both windows are load-bearing and both fail silently: with
-- the first closed the `INSERT ... SELECT` matches no order, with the second closed it writes no row, and
-- either way the statement reports success and every completed sale stays unstated. The two `FORCE` ALTERs
-- at the end are the other half of the pair, and the last block fails this file if they did not land.
--
-- WHAT THIS FILE DOES NOT DO. It does not move a stored amount: `total_amount` and the four parts are read,
-- never written, because ADR-0002 step 3's «`total_amount` keeps its value» is the property a financial
-- migration may not lose. It does not drop the column either — that is step 6, and `V87` guards it.

-- ─── 1. the window ────────────────────────────────────────────────────────────────────────────────
ALTER TABLE sales_orders NO FORCE ROW LEVEL SECURITY;
ALTER TABLE sale_payment NO FORCE ROW LEVEL SECURITY;

DO $$
DECLARE
    v_forced text;
BEGIN
    SELECT string_agg(c.relname, ', ' ORDER BY c.relname)
      INTO v_forced
      FROM pg_class c
     WHERE c.relname IN ('sales_orders', 'sale_payment')
       AND c.relkind = 'r'
       AND c.relforcerowsecurity;

    IF v_forced IS NOT NULL THEN
        RAISE EXCEPTION
            'V86 could not open its NO FORCE window on: %. The INSERT below would match no order or write no '
            'row, and both would report success — leaving every completed sale of this tree with nothing that '
            'says it was paid. See the window V80:57-78 opens, and ADR-0005 correction 3.', v_forced;
    END IF;
END $$;

-- ─── 2. the row each completed sale gets, which is the whole of what the tree knows ──────────────
-- Every column is either read from the document or the one literal ADR-0002 step 4 names. The five
-- predicates are the row's own justification, one at a time:
--
--   * `status = 'COMPLETED'` is what "already Completed" means in this schema: `V66` typed the column and
--     `ck_sales_orders_status` holds it to the five codes of `SalesOrderState`, so the comparison is against
--     a constant and not against a catalog label that a rename could move. `enabled` is deliberately not
--     filtered: a document deleted logically still took the money (the same reading
--     `preflight-payment-methods.sh` states).
--   * `payment_method_id IS NOT NULL` is the set this file can state; the rest is reported in step 3.
--   * `total_amount > 0` is the same boundary from the other side.
--   * `NOT EXISTS` is the idempotence.
DO $$
DECLARE
    v_written bigint;
BEGIN
    INSERT INTO sale_payment (
            company_id, sales_order_id, method_id, direction, amount,
            tendered_amount, change_amount, reference, paid_at, actor)
    SELECT o.company_id,
           o.id,
           o.payment_method_id,
           'CHARGE',
           o.total_amount,
           NULL,
           NULL,
           NULL,
           coalesce(o.updated_at, o.order_date),
           coalesce(o.user_id, 'unknown:migration')
      FROM sales_orders o
     WHERE o.status = 'COMPLETED'
       AND o.payment_method_id IS NOT NULL
       AND o.total_amount > 0
       AND NOT EXISTS (
           SELECT 1
             FROM sale_payment p
            WHERE p.company_id = o.company_id
              AND p.sales_order_id = o.id);

    -- Written from inside the block so the number is this statement's own: `ROW_COUNT` states what the last
    -- SQL command of the current function processed, and asking for it anywhere else answers about another
    -- statement — a report that names the wrong count is worse than no report.
    GET DIAGNOSTICS v_written = ROW_COUNT;

    RAISE NOTICE
        'V86: % payment row(s) written for completed sales that carried a method and a positive total '
        '(ADR-0002 step 4). Each states one payment of the whole amount, dated by the document and not by '
        'this deploy.', v_written;
END $$;

COMMENT ON COLUMN sale_payment.actor IS
    'Who took the money. A backfilled row states the document''s user_id, or the literal ''unknown:migration'' '
    'where the document names nobody — the column is NOT NULL and a backfill is not allowed to invent a person '
    '(ADR-0002 step 4).';

-- `V68`'s two constraint triggers on `sales_orders` are `DEFERRABLE INITIALLY DEFERRED`, and PostgreSQL
-- refuses an `ALTER TABLE` while a trigger event from this transaction is pending (SQLSTATE 55006 — the
-- failure `V80:86-92` measured and states). The `FORCE` ALTERs at the end of this file are `ALTER TABLE`s
-- and the block above writes rows, so the statement stays: a trigger added to either table later would put
-- the 55006 back in front of them without moving a line of this file. With nothing pending it does nothing.
SET CONSTRAINTS ALL IMMEDIATE;

-- ─── 3. the two sets this file cannot state, counted and named instead of invented ──────────────
-- The two sets are disjoint on purpose: an order with no method is named by the first report whatever its
-- total, and the second report names only the ones that do have a method. Their two counts therefore add up
-- to the number of completed documents this file left without a payment row, and no order is named twice —
-- which is what makes the pair usable as the per-row list the operator decides from.
DO $$
DECLARE
    v_no_method  bigint;
    v_zero_total bigint;
    v_sample     text;
BEGIN
    SELECT count(*) INTO v_no_method
      FROM sales_orders
     WHERE status = 'COMPLETED'
       AND payment_method_id IS NULL;

    IF v_no_method > 0 THEN
        SELECT string_agg(s.id::text, ', ' ORDER BY s.order_number)
          INTO v_sample
          FROM (SELECT order_number, id
                  FROM sales_orders
                 WHERE status = 'COMPLETED'
                   AND payment_method_id IS NULL
                 ORDER BY order_number
                 LIMIT 20) s;

        RAISE NOTICE
            'V86: % completed sale(s) state no payment method and were not backfilled (first 20 ids, by '
            'number: %). sale_payment.method_id is NOT NULL, and a backfill may not choose a method for a sale '
            '— a sentinel in the global catalog would state how it was paid when the data never did (ADR-0002 '
            'step 4, correction (c), D-a). Decide these per row before V87 can drop the column.',
            v_no_method, v_sample;
    END IF;

    SELECT count(*) INTO v_zero_total
      FROM sales_orders
     WHERE status = 'COMPLETED'
       AND payment_method_id IS NOT NULL
       AND total_amount = 0;

    IF v_zero_total > 0 THEN
        SELECT string_agg(s.id::text, ', ' ORDER BY s.order_number)
          INTO v_sample
          FROM (SELECT order_number, id
                  FROM sales_orders
                 WHERE status = 'COMPLETED'
                   AND payment_method_id IS NOT NULL
                   AND total_amount = 0
                 ORDER BY order_number
                 LIMIT 20) s;

        RAISE NOTICE
            'V86: % completed sale(s) carry a total of 0 and were not backfilled (first 20 ids, by number: %). '
            'sale_payment holds CHECK (amount > 0), and a row of zero — or a ten-thousandth that stands in for '
            'one — would state money nobody paid. A completed sale with no total is a document whose total was '
            'never stated, which is a decision per row and not this file''s.',
            v_zero_total, v_sample;
    END IF;
END $$;

-- ─── 4. the self-check: the backfill is what it claims, or this file fails ────────────────────────
-- Exactly one row per document, not "at least one": the insert is idempotent, so a second row would mean the
-- `NOT EXISTS` is not doing its work, and no row means the window did not take. Both report success on their
-- own, which is why the question is asked rather than assumed.
DO $$
DECLARE
    v_missing bigint;
    v_sample  text;
BEGIN
    SELECT count(*) INTO v_missing
      FROM sales_orders o
     WHERE o.status = 'COMPLETED'
       AND o.payment_method_id IS NOT NULL
       AND o.total_amount > 0
       AND (SELECT count(*)
              FROM sale_payment p
             WHERE p.company_id = o.company_id
               AND p.sales_order_id = o.id) <> 1;

    IF v_missing > 0 THEN
        SELECT string_agg(s.id::text, ', ' ORDER BY s.order_number)
          INTO v_sample
          FROM (SELECT order_number, id
                  FROM sales_orders o
                 WHERE o.status = 'COMPLETED'
                   AND o.payment_method_id IS NOT NULL
                   AND o.total_amount > 0
                   AND (SELECT count(*)
                          FROM sale_payment p
                         WHERE p.company_id = o.company_id
                           AND p.sales_order_id = o.id) <> 1
                 ORDER BY order_number
                 LIMIT 20) s;

        RAISE EXCEPTION
            'V86 ran and % completed sale(s) that stated a method and a positive total do not have exactly one '
            'payment row (first 20 ids, by number: %). Either the NO FORCE window above did not take — in which '
            'case the INSERT matched no order or wrote no row and said nothing — or the row count is above one, '
            'which would mean the idempotence predicate is not doing its work on a table that cannot be '
            'corrected afterwards. Neither is a state to leave behind.', v_missing, v_sample;
    END IF;
END $$;

-- ─── 5. the window closes, and a forgotten close fails this file instead of the next one ─────────
ALTER TABLE sales_orders FORCE ROW LEVEL SECURITY;
ALTER TABLE sale_payment FORCE ROW LEVEL SECURITY;

DO $$
DECLARE
    v_unarmed text;
BEGIN
    SELECT string_agg(c.relname, ', ' ORDER BY c.relname)
      INTO v_unarmed
      FROM pg_class c
     WHERE c.relname IN ('sales_orders', 'sale_payment')
       AND c.relkind = 'r'
       AND NOT (c.relrowsecurity AND c.relforcerowsecurity);

    IF v_unarmed IS NOT NULL THEN
        RAISE EXCEPTION
            'V86 opened a NO FORCE window and did not close it on: %. That table is readable and writable by '
            'its owner without a tenant scope, which is the state ADR-0005 correction 3 refuses.', v_unarmed;
    END IF;
END $$;
