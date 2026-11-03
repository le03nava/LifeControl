-- ============================================
-- V72 — The document identity assertions V64 asks too late to fire (issue #756)
-- ============================================
-- `V64` re-arms `FORCE ROW LEVEL SECURITY` at `:265-266` and asks its row question in the `DO` block that
-- follows at `:268-304`. The predicate of the policy it just forced is
-- `company_id = nullif(current_setting('app.company_id', true), '')::uuid`, and a migration never sets that
-- setting, so it reads `NULL`, so the predicate matches **no row** — and the question cannot fire. The
-- consequence is the one `V64`'s own step 0 warns about: "the backfill reached every row" and "the backfill
-- reached no row" print the same green.
--
-- WHY THIS FILE AND NOT AN EDIT TO `V64`
-- --------------------------------------
-- `V64` is a merged migration: re-editing it changes what a database that already ran it holds, so the shape
-- needed the maintainer's decision (issue #756 states the constraint). A follow-up turns out to cover **both**
-- databases a fix has to cover, which is why the choice is not merely the cautious one: Flyway applies
-- `V1`→head, so a fresh database runs `V64` and then this file **in the same run**, against the data `V64`
-- just produced. Editing `V64` in place would change *which* file asks the question, never *whether* it is
-- asked. `V64` therefore stays untouched, and nothing here is a rewrite of its history.
--
-- WHY THE QUESTIONS ARE NOT THE ONES `V64` ASKS
-- ---------------------------------------------
-- "A row left without its parts" is no longer a state anything can reach: from `V64` step 5 on,
-- `sales_orders.company_id`/`seq_period`/`seq_value`, `purchase_orders.seq_period`/`seq_value` and
-- `goods_receipts.company_id` are all `NOT NULL`. A follow-up that asked `WHERE … IS NULL` would be a second
-- vacuous assertion wearing the first one's clothes — the defect this file exists to remove. What is still
-- askable, and what nothing in the tree enforces, is that **the identity agrees with the document it numbers**:
--
--   1. `sales_orders.seq_period` is the period its **own** `order_date` states (ADR-0003 decision 4). No
--      constraint can say this — `to_char(timestamptz, …)` is not immutable, so a `CHECK` is refused — and
--      `V64` step 3 computes exactly this expression, which is the property the backfill claims.
--   2. The counter is **ahead of** every value it handed out: `next_value > max(seq_value)` per bucket. This is
--      what `max(seq_value) + 1` in step 4 asserts, and the thing that goes wrong if the seed read a subset.
--
-- THE QUESTION THIS FILE DOES NOT ASK, AND THE MEASUREMENT THAT REMOVED IT
-- ----------------------------------------------------------------------
-- An earlier draft asked a third: that every document's `(company_id, doc_type, seq_period)` bucket have its
-- `document_sequence` row — `V64` step 4 seeds one per bucket, and its own last guard (`:306-309`) asks whether
-- *some* counter exists when *some* document does, which is vacuous twice over: filtered by the `FORCE`
-- re-armed above it, and blind to one missing bucket among many. The question is gone, and the reason is
-- measured rather than aesthetic: the full migration suite ran and it fired on four suites' fixtures —
-- `DocumentStateSchemaMigrationIntegrationTest`, `SalesOrderStatusHistoryMigrationIntegrationTest`,
-- `PurchaseOrderStatusHistoryMigrationIntegrationTest` and `GoodsReceiptStatusHistoryMigrationIntegrationTest`
-- — each of which migrates to a pre-head, seeds documents in the old shape, and then writes further documents
-- directly at a post-`V64` head with an explicit `seq_period`/`seq_value` and **no** counter row
-- (`SalesOrderStatusHistoryMigrationIntegrationTest:322` and `:337`). A bucket with no counter row is therefore
-- a shape this tree's own fixtures write, so a guard against it would refuse the tree's own tests instead of a
-- defect — a fixture fight, not a fix. `V64`'s intent stays covered without it: "a row without its parts"
-- cannot exist (`NOT NULL` since step 5), question 1 catches parts that came from the wrong place, and
-- question 2 catches a seed that under-counted.
--
-- `purchase_orders` is asked question 2 only, and the absent question 1 is a deliberate omission with a
-- defect behind it: `PurchaseOrderService:195` derives the period from `LocalDate.now()` rather than from the
-- document's own date, so a purchase order created at a month
-- boundary can carry a `seq_period` that genuinely disagrees with its own `created_at`. Asserting the
-- agreement there would abort a deploy over legitimate rows; the writer is the thing that is wrong, and fixing
-- it is not this file's job. `goods_receipts` is asked nothing at all: a receipt whose `company_id` is not its
-- order's cannot exist, because `fk_goods_receipts_order_same_company` (`V64` step 6) refuses it at write time.
--
-- WHY A WINDOW AND NOT A PER-TENANT SCOPE
-- ---------------------------------------
-- Setting `app.company_id` per company would make rows visible without touching `FORCE`, and it was rejected:
-- it depends on every armed table's predicate having that exact shape, and it inspects only the companies a
-- loop can enumerate, so a document naming a company that no longer exists would stay invisible in the check
-- that exists to see it. The `NO FORCE` window is the mechanism `V57:85`, `V64` and `V66` already use, it is
-- transactional (a crash rolls it back), and it is closed by an assertion instead of by good intentions.

-- ============================================
-- 0. The NO FORCE window, opened before the first read
-- ============================================
-- Three tables: `purchase_orders` armed since `V53:126`, and `sales_orders` and `document_sequence` since
-- `V65`. `goods_receipts` is armed too and is deliberately absent, because no question below reads it —
-- a window over a table nothing reads is surface without a reason.
ALTER TABLE purchase_orders   NO FORCE ROW LEVEL SECURITY;
ALTER TABLE sales_orders      NO FORCE ROW LEVEL SECURITY;
ALTER TABLE document_sequence NO FORCE ROW LEVEL SECURITY;

-- The window fails silently by construction, so it is checked before it is relied on: a window that did not
-- take would answer every question below with zero rows and pass, which is the defect this file is about.
DO $$
DECLARE
    v_still_forced text;
BEGIN
    SELECT string_agg(c.relname, ', ' ORDER BY c.relname) INTO v_still_forced
      FROM pg_class c
     WHERE c.relkind = 'r' AND c.relforcerowsecurity
       AND c.relname IN ('purchase_orders', 'sales_orders', 'document_sequence');

    IF v_still_forced IS NOT NULL THEN
        RAISE EXCEPTION
            'V72 could not open its NO FORCE window on %: every question below would read no row and pass, '
            'which is exactly the defect this file exists to remove. See issue #756 and ADR-0005 '
            'correction 3.', v_still_forced;
    END IF;
END $$;

-- ============================================
-- 1. The two questions, asked while the window is open
-- ============================================
-- One `DO` block and one exception, so a database that fails several questions is told all of them at once
-- instead of one per deploy. Each row of the answer names the relation and the reason, which is what makes
-- the abort actionable without a query.
DO $$
DECLARE
    v_findings text;
BEGIN
    SELECT string_agg(k.relation::text || ' ' || k.reason, '; ') INTO v_findings
      FROM (
            -- 1. The period comes from the document's own date (ADR-0003 decision 4), and `V64` step 3
            --    computes it with this expression. Written by hand as well as by the backfill, an order's
            --    `seq_period` is derived from `order_date` and that column is never re-written afterwards.
            SELECT 'sales_orders'::regclass AS relation,
                   format('%s row(s) whose seq_period (%) is not the period their own order_date states (%s)',
                          count(*),
                          string_agg(DISTINCT seq_period, ', '),
                          string_agg(DISTINCT to_char(order_date, 'YYYYMM'), ', ')) AS reason
              FROM sales_orders
             WHERE seq_period <> to_char(order_date, 'YYYYMM')
            HAVING count(*) > 0
            UNION ALL
            -- 2. The counter is ahead of every value it handed out. `next_value` is the NEXT value, so the
            --    highest document of a bucket must sit strictly below it; equal means the next allocation
            --    hands out a number a document already prints (invariant N1).
            SELECT 'document_sequence'::regclass,
                   format('%s/%s says next_value %s but a document already holds %s',
                          s.doc_type, s.period, s.next_value, m.highest)
              FROM document_sequence s
              JOIN (SELECT company_id, doc_type, seq_period, max(seq_value) AS highest
                      FROM (SELECT company_id, doc_type, seq_period, seq_value FROM sales_orders
                            UNION ALL
                            SELECT company_id, doc_type, seq_period, seq_value FROM purchase_orders) d
                     GROUP BY company_id, doc_type, seq_period) m
                ON m.company_id  = s.company_id
               AND m.doc_type    = s.doc_type
               AND m.seq_period  = s.period
             WHERE s.next_value <= m.highest
           ) k;

    IF v_findings IS NOT NULL THEN
        RAISE EXCEPTION
            'V72 found document identities that disagree with the documents they number: %. ADR-0003 '
            'decision 4 says the period comes from the document''s own date and decision 2 says the counter '
            'hands out a value no document holds, so each of these is a document whose printed number is not '
            'the one its own data states. Fix the rows or the counters before deploying: nothing in the '
            'schema can enforce either property, which is why this file asks.', v_findings;
    END IF;
END $$;

-- ============================================
-- 2. The window closes, and the close is checked
-- ============================================
-- After the questions and never before them: `FORCE ROW LEVEL SECURITY` filters the owner too, so a question
-- placed below these three `ALTER`s reads zero rows and passes — `V64`'s defect, in one sentence.
ALTER TABLE purchase_orders   FORCE ROW LEVEL SECURITY;
ALTER TABLE sales_orders      FORCE ROW LEVEL SECURITY;
ALTER TABLE document_sequence FORCE ROW LEVEL SECURITY;

DO $$
DECLARE
    v_not_forced text;
BEGIN
    SELECT string_agg(c.relname, ', ' ORDER BY c.relname) INTO v_not_forced
      FROM pg_class c
     WHERE c.relkind = 'r' AND NOT c.relforcerowsecurity
       AND c.relname IN ('purchase_orders', 'sales_orders', 'document_sequence');

    IF v_not_forced IS NOT NULL THEN
        RAISE EXCEPTION
            'V72 left % without FORCE ROW LEVEL SECURITY: a table whose owner is exempt from its own policy '
            'is the state ADR-0005 exists to remove, and a window left open weakens the table for everything '
            'that runs after it (V55).', v_not_forced;
    END IF;
END $$;
