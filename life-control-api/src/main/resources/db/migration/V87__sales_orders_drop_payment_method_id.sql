-- ============================================
-- V87 — sales_orders.payment_method_id is dropped (ADR-0002 step 6)
-- ============================================
-- ADR-0002 step 6, verbatim: «Drop `sales_orders.payment_method_id` once the payment rows are populated and
-- every reader has moved.»
--
-- Both conditions are facts about this tree, and both are measured elsewhere:
--
--   * **the readers moved.** The column had exactly one writer and one reader. `T6a` wrote it from the first
--     `CHARGE` row of the charge (`SalesOrderService:458`) and `T7a` reported it
--     (`SalesOrderService#toResponse` → `SalesOrderResponse.paymentMethodId`), which is the single tender a
--     document was allowed to have. `T7a` answers the document's payments instead, `T7b`'s charge appends one
--     row per tender, and `T8` made the POS read the rows — so what the column stated is now stated once, in
--     the ledger `V83` created, and keeping the column would be a second answer to the same question.
--   * **the rows are populated**, by `V86` — for every completed sale that carried a method and a positive
--     total. That is why this file follows `V86` and not the other way round.
--
-- WHY A GUARD, WHEN `V86` JUST RAN. «Once the payment rows are populated» is a statement about the dataset,
-- not about a file: it is what makes the drop safe on a tree this repository has never seen — a restored
-- dump, a hot-fix deploy that skipped a release, a database whose `V86` was executed by hand. On any of
-- those the column may be the only record that a sale took money, and `V86` names the two sets it is not
-- allowed to state (a completed sale with no method, one with a total of 0) with `RAISE NOTICE` and leaves
-- them unpaid on purpose. Dropping the column under them would erase the last trace of how those documents
-- were paid while a re-run of `V86` could no longer recover it. So the file refuses, names the count and the
-- rows, and leaves the decision where `V86`'s report and `D-a` put it: per row, by an operator.
--
-- WHAT A PAYMENT ROW MEANS HERE. `V83`'s void is its own appended row and never a status column, so the
-- guard asks for a payment that is not voided — the same shape `SalePaymentRepository.netPaid` sums and the
-- charge's own M3 guard reads, so this file and the application cannot disagree about whether a document has
-- been paid.
--
-- WHY THE DROP IS IRREVERSIBLE, AND WHY THAT IS STILL THE RIGHT DIRECTION. `ALTER TABLE ... DROP COLUMN`
-- discards the values: no later migration can reconstruct which tender a document stated before `V86` wrote
-- it, and `V83`'s append-only grant refuses the `UPDATE` that would put one back. That is exactly why the
-- guard precedes it rather than following it, and why this file moves no row of `sale_payment`: it removes a
-- column that is now redundant, it does not restate a payment.
--
-- THE WINDOW. The guard reads `sales_orders` and `sale_payment` across tenants, and both are `ENABLE` +
-- `FORCE` (`V65`, `V83`) — so without the window the owner sees zero completed orders, the guard counts
-- zero unpaid, and the drop proceeds on exactly the dataset it was written to refuse. That is the silent
-- failure the window exists to remove, and the block after it fails this file if the window did not take.
-- The `FORCE` ALTERs at the end are the other half of the pair.

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
            'V87 could not open its NO FORCE window on: %. The guard below would count zero completed sales, '
            'find zero unpaid ones, and drop the column on precisely the dataset it exists to refuse — with no '
            'error anywhere. See the window V80:57-78 opens, and ADR-0005 correction 3.', v_forced;
    END IF;
END $$;

-- `V68`'s two constraint triggers on `sales_orders` are `DEFERRABLE INITIALLY DEFERRED`, and PostgreSQL
-- refuses an `ALTER TABLE` while a trigger event from this transaction is pending (SQLSTATE 55006 — the
-- failure `V80:86-92` measured and states). This file writes no row, so nothing can be pending here and the
-- statement does nothing today; it stays so that the two files read the same and the failure cannot come
-- back through a later write in this file.
SET CONSTRAINTS ALL IMMEDIATE;

-- ─── 2. step 6's own condition, as a refusal instead of an assumption ─────────────────────────────
-- A completed sale with no non-voided payment row is the one state in which this column still carries
-- information nothing else does. The count, the order number and the id travel in the message because the
-- operator decides per row: either name the method those sales took (and let `V86` state them once the
-- column can answer), or accept that the document's payment history is unknown before the drop.
DO $$
DECLARE
    v_unpaid bigint;
    v_sample text;
BEGIN
    SELECT count(*) INTO v_unpaid
      FROM sales_orders o
     WHERE o.status = 'COMPLETED'
       AND NOT EXISTS (
           SELECT 1
             FROM sale_payment p
            WHERE p.company_id = o.company_id
              AND p.sales_order_id = o.id
              AND NOT EXISTS (
                  SELECT 1
                    FROM sale_payment_void v
                   WHERE v.payment_id = p.id
                     AND v.company_id = p.company_id));

    IF v_unpaid > 0 THEN
        SELECT string_agg(s.order_number || ' = ' || s.id, ', ' ORDER BY s.order_number)
          INTO v_sample
          FROM (SELECT o.order_number, o.id
                  FROM sales_orders o
                 WHERE o.status = 'COMPLETED'
                   AND NOT EXISTS (
                       SELECT 1
                         FROM sale_payment p
                        WHERE p.company_id = o.company_id
                          AND p.sales_order_id = o.id
                          AND NOT EXISTS (
                              SELECT 1
                                FROM sale_payment_void v
                               WHERE v.payment_id = p.id
                                 AND v.company_id = p.company_id))
                 ORDER BY o.order_number
                 LIMIT 20) s;

        RAISE EXCEPTION
            'V87 refuses to drop sales_orders.payment_method_id: % completed sale(s) have no payment row '
            '(first 20, number = id: %). ADR-0002 step 6 drops the column «once the payment rows are '
            'populated», and that is a fact about this dataset and not about this file: the column is the only '
            'record that these sales took money, and no later migration can reconstruct it (the drop is '
            'irreversible and sale_payment is append-only). V86 stated the rows it can — a completed sale with '
            'a method and a positive total — and named the rest in its notices: a sale with no method, or with '
            'a total of 0. Decide those per row, then run this file again.', v_unpaid, v_sample;
    END IF;
END $$;

-- ─── 3. the drop ─────────────────────────────────────────────────────────────────────────────────
-- The index goes explicitly although `DROP COLUMN` removes it anyway: `idx_so_payment_method` is `V1:489`,
-- and naming it states the two files' worth of schema this file is undoing instead of leaving it to be
-- inferred from the column list.
DROP INDEX IF EXISTS idx_so_payment_method;

ALTER TABLE sales_orders DROP COLUMN payment_method_id;

-- ─── 4. the window closes, and a forgotten close fails this file instead of the next one ─────────
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
            'V87 opened a NO FORCE window and did not close it on: %. That table is readable and writable by '
            'its owner without a tenant scope, which is the state ADR-0005 correction 3 refuses.', v_unarmed;
    END IF;
END $$;
