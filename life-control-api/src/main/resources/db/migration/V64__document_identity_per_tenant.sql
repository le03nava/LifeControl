-- ============================================
-- V64 — Document identity per tenant (ADR-0003), the schema half
-- ============================================
-- A document number stops being a globally unique string produced by reading the highest number in the
-- table and adding one. ADR-0003 decision 1 puts the counter in a row per (company, doc_type, period),
-- decision 5 makes the identity a set of columns instead of a rendered string, and decision 6 lets a
-- receipt inherit its parent's frozen number.
--
-- WHY THIS FILE AND V65 SHIP TOGETHER, AND WHAT HAPPENS IF THEY DO NOT
-- -------------------------------------------------------------------
-- Step 2 below adds `company_id` to `sales_orders` and `goods_receipts`. `unprotected_tenant_tables()`
-- (V52) returns every table that carries that column and has no `tenant_table` row, and
-- `TenantIsolationStartupCheck` refuses to boot on a non-empty answer — keyed on the **column's
-- existence**, not on its `NOT NULL`. The arming is V65 step 7, in the same Flyway run. Shipping this
-- file alone is a deployment that refuses to start, which is the correct failure and is named here so it
-- is not discovered in staging.
--
-- The prose order this file follows is ADR-0003's own: write -> read -> stop writing -> drop. The render
-- that fills `doc_number` is V65 (a Java migration, because a SQL migration cannot call the application
-- renderer), so `doc_number` is added here, stays nullable here, and is constrained there. Setting it
-- `NOT NULL` before the value exists is the ordering defect ADR-0001's acceptance had to fix in its own
-- plan.
--
-- WHY THE THREE GLOBAL UNIQUE CONSTRAINTS GO
-- ------------------------------------------
-- The ADR's `Replaces` line says these migrations replace "the global `UNIQUE` on every document number".
-- Two tenants numbering from their own counters both produce `SO-202610-00001` in October, so a global
-- key would refuse the second tenant's first document of the month — the migration would fail on exactly
-- the change it exists to make. The tenant-scoped keys that replace them are created in step 9 and V65.

-- ============================================
-- 0. The NO FORCE window, opened before the first read
-- ============================================
-- `V53` armed `purchase_orders` and `V52`'s `apply_tenant_isolation` sets `FORCE ROW LEVEL SECURITY`,
-- which filters the table's **owner** — the role Flyway runs as. `V54:26` records the consequence in the
-- tree's own words: on such a table a statement matches **zero rows and reports success**. Without this
-- window the pre-flight report in step 1 reads nothing, the backfill numbers nothing, and the `NOT NULL`s
-- in step 8 succeed over a column of nulls. `NO FORCE` is the only door a `NOSUPERUSER` owner has.
--
-- `company_stores` needs it too, and that is the trap the ADR's step 0 does not name: the backfill derives
-- each sale's tenant by JOINING `company_stores`, which `V53` armed as well. A forced join reads zero rows,
-- so the `CTE` would be empty, the `UPDATE` would match nothing and every sales order would keep a NULL
-- `company_id` — while the migration reports success. The window therefore covers every armed table this
-- file reads, not only the one it writes.
--
-- `V65` opens its own window, because one that spans two files is one a crash can leave open (`V55`).
ALTER TABLE purchase_orders NO FORCE ROW LEVEL SECURITY;
ALTER TABLE company_stores  NO FORCE ROW LEVEL SECURITY;

-- The window fails silently by construction, so it is checked: `V57:85` is the same window carrying the
-- same two self-checks — this one that it took, and step 10's that the statements inside it reached rows.
DO $$
DECLARE
    v_still_forced text;
BEGIN
    SELECT string_agg(c.relname, ', ' ORDER BY c.relname) INTO v_still_forced
      FROM pg_class c
     WHERE c.relkind = 'r' AND c.relforcerowsecurity AND c.relname IN ('purchase_orders', 'company_stores');

    IF v_still_forced IS NOT NULL THEN
        RAISE EXCEPTION
            'V64 could not open its NO FORCE window on %: the pre-flight report below would read nothing, '
            'the backfill would number nothing and the NOT NULLs would pass over a column of nulls. See '
            'ADR-0005 correction 3 and ADR-0003 migration step 0.', v_still_forced;
    END IF;
END $$;

-- ============================================
-- 1. Pre-flight: the blast radius, reported and not refused
-- ============================================
-- A document whose printed number does not follow the shape the old generator promised is **renumbered**
-- by this unit, not rejected: ADR-0003 step 1 says the report exists so the count is known before the
-- deploy. It runs after the window on purpose — over a forced table "no unparseable number" and "not one
-- row visible" print the same empty output, and a report that cannot tell them apart is worse than none.
--
-- The tenant is read through the store and through the parent order, and not from the document rows: this
-- step runs **before** step 2 adds `company_id` to `sales_orders` and `goods_receipts`, so those columns do
-- not exist yet — a report that named them would abort the migration on a column that is added twenty lines
-- below it. The walk is the same one step 3's backfill uses, and neither hop can lose a row: `company_stores`
-- carries `company_id` since V48/V49 and a receipt always names its order.
DO $$
DECLARE
    r       record;
    v_total integer := 0;
BEGIN
    FOR r IN
        SELECT 'SALES_ORDER' AS doc_type, cs.company_id, count(*) AS rows
          FROM sales_orders so
          JOIN company_stores cs ON cs.id = so.company_store_id
         WHERE so.order_number !~ '^SO-[0-9]{8}-[0-9]+$'
         GROUP BY cs.company_id
        UNION ALL
        SELECT 'PURCHASE_ORDER', po.company_id, count(*)
          FROM purchase_orders po
         WHERE po.order_number !~ '^PO-[0-9]{8}-[0-9]+$'
         GROUP BY po.company_id
        UNION ALL
        SELECT 'GOODS_RECEIPT', po.company_id, count(*)
          FROM goods_receipts gr
          JOIN purchase_orders po ON po.id = gr.purchase_order_id
         WHERE gr.receipt_number !~ '-[0-9]+$'
         GROUP BY po.company_id
    LOOP
        v_total := v_total + r.rows;
        RAISE NOTICE 'V64 pre-flight: % % rows of company % are not shaped as the old generator promised '
            'and are renumbered by this unit', r.rows, r.doc_type, r.company_id;
    END LOOP;

    IF v_total = 0 THEN
        RAISE NOTICE 'V64 pre-flight: no document carries an unparseable number; every row is renumbered '
            'from its own date and store, and the count per tenant is the row count per tenant';
    END IF;
END $$;

-- ============================================
-- 2. The counter row, and the identity columns, nullable first
-- ============================================
CREATE TABLE document_sequence (
    company_id  UUID        NOT NULL,
    doc_type    VARCHAR(24) NOT NULL,
    period      VARCHAR(8)  NOT NULL,      -- 'YYYYMM' by default, configurable per doc_type
    next_value  BIGINT      NOT NULL,      -- the next value to hand out; the first handed out is 1
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_document_sequence PRIMARY KEY (company_id, doc_type, period),
    CONSTRAINT fk_document_sequence_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT ck_document_sequence_next CHECK (next_value >= 2),
    -- only the period-numbered types allocate from a counter: a derived document (a receipt) must not be
    -- able to open one, which is what decision 6 forbids
    CONSTRAINT ck_document_sequence_type CHECK (doc_type IN ('SALES_ORDER', 'PURCHASE_ORDER')),
    -- alphanumeric and short, so a doc_type may periodize differently: 'YYYYMM' is the default this ADR
    -- states, not a constraint the schema can assume
    CONSTRAINT ck_document_sequence_period CHECK (period ~ '^[A-Z0-9]{1,8}$'));

COMMENT ON TABLE document_sequence IS
    'ADR-0003 decision 1: one counter row per (company, doc_type, period). `next_value` is the NEXT value '
    'to hand out, allocated by one upsert (decision 2) and never by a read followed by a write.';

COMMENT ON COLUMN document_sequence.period IS
    'ADR-0003 decision 4: derived from the document''s own date and frozen before the allocation runs, '
    'never read from the wall clock at allocation time.';

ALTER TABLE sales_orders
    ADD COLUMN company_id UUID,            -- NOT NULL in step 8, after the backfill
    ADD COLUMN doc_type   VARCHAR(24) NOT NULL DEFAULT 'SALES_ORDER',
    ADD COLUMN seq_period VARCHAR(8),      -- the same type as document_sequence.period
    ADD COLUMN seq_value  BIGINT,
    ADD COLUMN doc_number VARCHAR(40);     -- rendered once in V65, never recomputed

ALTER TABLE purchase_orders
    ADD COLUMN doc_type   VARCHAR(24) NOT NULL DEFAULT 'PURCHASE_ORDER',
    ADD COLUMN seq_period VARCHAR(8),
    ADD COLUMN seq_value  BIGINT,
    ADD COLUMN doc_number VARCHAR(40);

ALTER TABLE goods_receipts
    ADD COLUMN company_id UUID,            -- NOT NULL in step 8, after the backfill
    ADD COLUMN doc_type   VARCHAR(24) NOT NULL DEFAULT 'GOODS_RECEIPT',
    ADD COLUMN doc_number VARCHAR(40);     -- GR-{parent.doc_number}-{NN}, rendered in V65

ALTER TABLE sales_orders    DROP CONSTRAINT IF EXISTS sales_orders_order_number_key;
ALTER TABLE purchase_orders DROP CONSTRAINT IF EXISTS purchase_orders_order_number_key;
ALTER TABLE goods_receipts  DROP CONSTRAINT IF EXISTS goods_receipts_receipt_number_key;

-- ============================================
-- 3. Backfill the parts, per tenant and period
-- ============================================
-- Ordered by `created_at`, with the printed number as a tiebreak, so the rebuilt sequence follows the
-- order the documents were written. Both statements run inside step 0's window: `purchase_orders` is
-- armed today, and `sales_orders` is not yet armed at this point — it is armed in V65 step 7, after the
-- cross-tenant writes this file and the render need.
WITH numbered AS (
    SELECT so.id,
           cs.company_id,
           to_char(so.order_date, 'YYYYMM') AS period,
           row_number() OVER (PARTITION BY cs.company_id, to_char(so.order_date, 'YYYYMM')
                              ORDER BY so.created_at, so.order_number) AS seq_value
      FROM sales_orders so
      JOIN company_stores cs ON cs.id = so.company_store_id)
UPDATE sales_orders so
   SET company_id = n.company_id,
       doc_type   = 'SALES_ORDER',
       seq_period = n.period,
       seq_value  = n.seq_value
  FROM numbered n
 WHERE so.id = n.id;

-- A purchase order's own date is its `created_at`, because the table has no `order_date` column
-- (`V1:313-325` declares none): ADR-0003 decision 4 names `purchase_orders.order_date`, and that naming is
-- the one fact of the ADR this unit could not implement literally. `created_at` is the document's own
-- frozen timestamp, which is the property decision 4 is about, so the period still comes from the document
-- and never from the allocation's clock.
WITH numbered AS (
    SELECT po.id,
           po.company_id,
           to_char(po.created_at, 'YYYYMM') AS period,
           row_number() OVER (PARTITION BY po.company_id, to_char(po.created_at, 'YYYYMM')
                              ORDER BY po.created_at, po.order_number) AS seq_value
      FROM purchase_orders po)
UPDATE purchase_orders po
   SET doc_type   = 'PURCHASE_ORDER',
       seq_period = n.period,
       seq_value  = n.seq_value
  FROM numbered n
 WHERE po.id = n.id;

-- A receipt's tenant is its order's, never its store's: the order already carries the company the composite
-- key will pin, and a receipt and its order disagreeing about the tenant is the state
-- `fk_goods_receipts_order_same_company` (step 9) refuses.
UPDATE goods_receipts gr
   SET company_id = po.company_id,
       doc_type   = 'GOODS_RECEIPT'
  FROM purchase_orders po
 WHERE po.id = gr.purchase_order_id;

-- ============================================
-- 4. Seed the counters from the rows that now carry a value
-- ============================================
-- `max(seq_value) + 1` per (company, type, period), run once and single-threaded inside the migration. Not
-- `count(*)`: a gap in the history is a value already consumed somewhere in the past, and handing it out
-- again would collide with a document that could be reprinted.
INSERT INTO document_sequence (company_id, doc_type, period, next_value)
SELECT company_id, doc_type, seq_period, max(seq_value) + 1
  FROM (SELECT company_id, doc_type, seq_period, seq_value FROM sales_orders
        UNION ALL
        SELECT company_id, doc_type, seq_period, seq_value FROM purchase_orders) seeded
 GROUP BY company_id, doc_type, seq_period;

-- ============================================
-- 5. The parts become required
-- ============================================
-- `seq_period` as well as `seq_value`, which the ADR's step 4 names implicitly: the identity CHECK in V65
-- requires the three columns together or none, so a nullable `seq_period` beside a non-null `seq_value` is
-- a state that CHECK refuses. Setting it here means the state cannot be reached at all.
ALTER TABLE sales_orders    ALTER COLUMN company_id SET NOT NULL;
ALTER TABLE sales_orders    ALTER COLUMN seq_period SET NOT NULL;
ALTER TABLE sales_orders    ALTER COLUMN seq_value  SET NOT NULL;
ALTER TABLE purchase_orders ALTER COLUMN seq_period SET NOT NULL;
ALTER TABLE purchase_orders ALTER COLUMN seq_value  SET NOT NULL;
ALTER TABLE goods_receipts  ALTER COLUMN company_id SET NOT NULL;

-- ============================================
-- 6. The keys and the pins that need the columns to be NOT NULL
-- ============================================
-- Created here and not before step 5: a unique index over a nullable `company_id` admits duplicate parts
-- for the rows where it is NULL, so the index would leave a window in which invariant N1 is not enforced.
CREATE UNIQUE INDEX uq_sales_orders_doc_identity
    ON sales_orders (company_id, doc_type, seq_period, seq_value) WHERE seq_value IS NOT NULL;
CREATE UNIQUE INDEX uq_purchase_orders_doc_identity
    ON purchase_orders (company_id, doc_type, seq_period, seq_value) WHERE seq_value IS NOT NULL;

-- The key the receipt's tenant pin needs (`V51:86-90`'s pattern: a composite key needs the pair unique at
-- the target). `purchase_orders.company_id` is V43's.
ALTER TABLE purchase_orders
    ADD CONSTRAINT uq_purchase_orders_company_id UNIQUE (company_id, id);

-- The receipt cannot name another tenant's order. With `company_id` NOT NULL the default MATCH SIMPLE is
-- enough, because there is no NULL on the child side that would skip the comparison.
ALTER TABLE goods_receipts
    ADD CONSTRAINT fk_goods_receipts_order_same_company
    FOREIGN KEY (company_id, purchase_order_id) REFERENCES purchase_orders (company_id, id);

-- ============================================
-- 7. The window closes, and the close is checked
-- ============================================
ALTER TABLE purchase_orders FORCE ROW LEVEL SECURITY;
ALTER TABLE company_stores  FORCE ROW LEVEL SECURITY;

DO $$
DECLARE
    v_not_forced text;
    v_missing    text;
    v_counters   integer;
BEGIN
    SELECT string_agg(c.relname, ', ' ORDER BY c.relname) INTO v_not_forced
      FROM pg_class c
     WHERE c.relkind = 'r' AND NOT c.relforcerowsecurity AND c.relname IN ('purchase_orders', 'company_stores');

    IF v_not_forced IS NOT NULL THEN
        RAISE EXCEPTION
            'V64 left % without FORCE ROW LEVEL SECURITY: a table whose owner is exempt from its own policy is '
            'the state ADR-0005 exists to remove. See V55, which exists for this reason.', v_not_forced;
    END IF;

    -- "The backfill reached nothing" and "the backfill reached everything" print the same green; only a
    -- question about the rows separates them.
    SELECT string_agg(k.relation::text || ' ' || k.reason, '; ')
      INTO v_missing
      FROM (
            SELECT 'sales_orders'::regclass AS relation, 'rows without a company or a sequence value' AS reason
             WHERE EXISTS (SELECT 1 FROM sales_orders WHERE company_id IS NULL OR seq_value IS NULL)
            UNION ALL
            SELECT 'purchase_orders'::regclass, 'rows without a sequence value'
             WHERE EXISTS (SELECT 1 FROM purchase_orders WHERE seq_value IS NULL)
            UNION ALL
            SELECT 'goods_receipts'::regclass, 'rows without a company'
             WHERE EXISTS (SELECT 1 FROM goods_receipts WHERE company_id IS NULL)
           ) k;

    IF v_missing IS NOT NULL THEN
        RAISE EXCEPTION
            'V64 backfilled the document identity parts and these still carry none: %. Either the NO FORCE '
            'window at the top of this file did not take, or the backfill read no row — both report success '
            'under FORCE ROW LEVEL SECURITY. See ADR-0003 migration steps 0, 3 and 5.', v_missing;
    END IF;

    SELECT count(*) INTO v_counters FROM document_sequence;
    IF v_counters = 0 AND EXISTS (SELECT 1 FROM sales_orders UNION ALL SELECT 1 FROM purchase_orders) THEN
        RAISE EXCEPTION
            'V64 numbered documents but seeded no counter row: the next allocation would start at 1 over a '
            'history that already holds those values, and invariant N1 would fail at the first document.';
    END IF;

    RAISE NOTICE 'V64 seeded % counter row(s) for % sales order(s) and % purchase order(s)',
        v_counters,
        (SELECT count(*) FROM sales_orders),
        (SELECT count(*) FROM purchase_orders);
END $$;
