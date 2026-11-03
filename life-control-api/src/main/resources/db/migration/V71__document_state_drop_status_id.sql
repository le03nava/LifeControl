-- ============================================
-- V71 — status_id leaves the five document tables (ADR-0004, migration step 9)
-- ============================================
-- This is the last step of ADR-0004 and the one that makes decision 1 true instead of aspirational: the
-- typed `status` column of `V66` stops being one of two columns and becomes the only one. `status_id`, its
-- indexes, its foreign key and the temporary bridge of `V66` go together, because a bridge with nothing to
-- bridge from is not a bridge — it is a second source of truth, which is the defect decision 1 exists to
-- remove.
--
-- WHAT DIES, AND WHY EACH PIECE DIES WITH THE OTHERS
-- --------------------------------------------------
-- `status_id` is today's authority: every writer set it and every reader joined it for a label. `V66` gave
-- the row a typed state beside it and a trigger that derived the state from the catalog's `code` while both
-- columns lived, so the two could not disagree. Every writer moved to the enum in `V68`, `V69` and `V70`
-- (tasks T5–T7), and every reader — the three services and their response mappers — projects the pair from
-- the code instead of from the stored pointer. What is left is the column, its five indexes, its five
-- foreign keys and the bridge, and none of them has a reader.
--
-- **The foreign key goes with the column, and that is the point of the ADR's step 9.** A foreign key to
-- `statuses` was the schema's way of saying "the catalog is the authority": it made a document unable to
-- name a state the catalog did not carry, which is exactly the coupling decision 1 removes. The state's
-- closed set is now the `CHECK` of `V66` — derived from the enum, refused by the compiler's own list — and
-- the catalog keeps its rows as the *label* of a code (decision 7).
--
-- **The indexes are dropped by name before the column, not left to the cascade.** PostgreSQL drops an index
-- whose column disappears, so the `DROP INDEX` statements below are redundant in effect and deliberate in
-- form: they are the four the ADR names (`V1:334`, `V1:488`, `V1:514`, `V12:36`) plus `V1:358`, and naming
-- them is what makes the file answer "were they dropped" without reading `pg_index` afterwards. **The list
-- in the ADR is one short**: `purchase_order_details` carries its own `idx_pod_status` at `V1:358` and the
-- ADR's line references skip it while citing `V12:36` for `goods_receipts`. Five document tables carry one
-- index each, so five are dropped; a file that dropped four would leave the fifth to the cascade and the
-- list would still look complete.
--
-- WHAT DOES NOT DIE, AND WHY THE FILE SAYS SO
-- -------------------------------------------
-- **`scheduling_appointments.status_id` and `employees.employment_status_id` stay untouched.** ADR-0004
-- names them out of scope *by decision* (`## Context`, invariant S1): an appointment and an employment
-- status are not commerce documents, so they have no domain enum, no closed transition set and no
-- `status_transition` row. Their `status_id` is not a leftover of this migration — it is the schema saying
-- which two families this ADR did not touch, and dropping it here would be the scope decision reversed by
-- a side effect. The two are therefore asserted **present** at the bottom of this file, so a later cleanup
-- cannot quietly take them either.
--
-- **`statuses` and `status_types` keep their rows, their codes and their names.** They are the label the
-- screens render (decision 7), which is why `V66` gave the five in-scope families a `code` and left the two
-- out-of-scope ones without one. A drop of `status_id` that also took the catalog would leave every screen
-- with a code and no label to show, and `DocumentStateCatalog` would have nothing to read a request's
-- `statusId` through. The bottom of this file asserts the catalog is intact from both sides: every constant
-- of the five enums still has its row with its code, and the two out-of-scope families still carry the rows
-- `V18` and `V20` seeded.
--
-- WHY THE ASSERTIONS ARE TWO `DO` BLOCKS AND NOT ONE
-- --------------------------------------------------
-- The first answers "did the drop reach exactly the five tables it was aimed at" in both directions — the
-- five must be gone, the two must be there — and that is the question a half-applied file would fail. The
-- second answers "is the catalog still the label", which is the question a drop that cascaded further than
-- intended would fail. They are separate because they fail for unrelated reasons and a single message would
-- name only one of them.

-- ============================================
-- 1. The bridge of V66, dropped with the column it bridges from
-- ============================================
-- Five triggers and one function. The order matters only for readability: a trigger whose function is gone
-- is dropped with the function, but naming both is what makes the intent legible in `git log -p`.
DROP TRIGGER IF EXISTS trg_sales_orders_status_bridge ON sales_orders;
DROP TRIGGER IF EXISTS trg_sales_order_items_status_bridge ON sales_order_items;
DROP TRIGGER IF EXISTS trg_purchase_orders_status_bridge ON purchase_orders;
DROP TRIGGER IF EXISTS trg_purchase_order_details_status_bridge ON purchase_order_details;
DROP TRIGGER IF EXISTS trg_goods_receipts_status_bridge ON goods_receipts;

DROP FUNCTION IF EXISTS sync_document_status_from_catalog();

-- ============================================
-- 2. The five indexes, then the five columns
-- ============================================
-- `idx_pod_status` and `idx_goods_receipts_status` are in this list on purpose even though the ADR's line
-- references name only four of the five: every document table that carried `status_id` carried an index on
-- it, and the list below is the schema's, not the prose's.
DROP INDEX IF EXISTS idx_so_status;
DROP INDEX IF EXISTS idx_soi_status;
DROP INDEX IF EXISTS idx_po_status;
DROP INDEX IF EXISTS idx_pod_status;
DROP INDEX IF EXISTS idx_goods_receipts_status;

-- `NOT NULL` and the foreign key to `statuses` go with the column; neither needs a statement of its own.
ALTER TABLE sales_orders           DROP COLUMN status_id;
ALTER TABLE sales_order_items      DROP COLUMN status_id;
ALTER TABLE purchase_orders        DROP COLUMN status_id;
ALTER TABLE purchase_order_details DROP COLUMN status_id;
ALTER TABLE goods_receipts         DROP COLUMN status_id;

-- ============================================
-- 3. The drop reached exactly five tables, and no others
-- ============================================
-- Asked in both directions because the two failure modes are opposite and both silent. A column left behind
-- keeps a second source of truth alive with nothing writing it, and a column dropped from the two
-- out-of-scope tables is the ADR's scope decision reversed by a side effect — the schema would no longer
-- show which families this ADR touched. `information_schema.columns` answers both, for the `public` schema
-- only, so an unrelated schema cannot make the question pass.
DO $$
DECLARE
    v_problem text;
BEGIN
    SELECT string_agg(k.table_name || ' ' || k.reason, '; ' ORDER BY k.table_name) INTO v_problem
      FROM (SELECT t AS table_name, 'still carries status_id' AS reason
              FROM unnest(ARRAY['sales_orders', 'sales_order_items', 'purchase_orders',
                                'purchase_order_details', 'goods_receipts']) AS t
             WHERE EXISTS (SELECT 1 FROM information_schema.columns c
                            WHERE c.table_schema = 'public'
                              AND c.table_name = t
                              AND c.column_name = 'status_id')
            UNION ALL
            SELECT t.table_name, 'lost ' || t.column_name
              FROM (VALUES ('scheduling_appointments', 'status_id'),
                           ('employees', 'employment_status_id')) AS t (table_name, column_name)
             WHERE NOT EXISTS (SELECT 1 FROM information_schema.columns c
                                WHERE c.table_schema = 'public'
                                  AND c.table_name = t.table_name
                                  AND c.column_name = t.column_name)) k;

    IF v_problem IS NOT NULL THEN
        RAISE EXCEPTION
            'V71 dropped status_id from the wrong set of tables: %. The five commerce document families lose '
            'the pointer because the enum is their authority now (ADR-0004 decision 1 and migration step 9); '
            'scheduling_appointments and employees keep theirs because they are out of scope by decision '
            '(## Context, S1), and that is the scope decision visible in the schema.', v_problem;
    END IF;
END $$;

-- The bridge is asserted gone by name, and the pair that must stay is asserted present in the same block:
-- `V68`–`V70`'s deferred triggers are what makes "a state change with no history row cannot commit" true,
-- and a file that dropped a `trg_%_status_bridge` pattern too broadly would take them with it.
DO $$
DECLARE
    v_left text;
    v_missing text;
    v_functions text;
BEGIN
    SELECT string_agg(p.proname, ', ' ORDER BY p.proname) INTO v_functions
      FROM pg_proc p
     WHERE p.proname IN ('sync_document_status_from_catalog');

    IF v_functions IS NOT NULL THEN
        RAISE EXCEPTION
            'V71 left the temporary bridge function behind: %. It derives one column from another and has '
            'nothing to derive from once status_id is gone. See V66 step 5.', v_functions;
    END IF;

    SELECT string_agg(g.tgname, ', ' ORDER BY g.tgname) INTO v_left
      FROM pg_trigger g
     WHERE NOT g.tgisinternal
       AND g.tgname IN ('trg_sales_orders_status_bridge', 'trg_sales_order_items_status_bridge',
                        'trg_purchase_orders_status_bridge', 'trg_purchase_order_details_status_bridge',
                        'trg_goods_receipts_status_bridge');

    IF v_left IS NOT NULL THEN
        RAISE EXCEPTION
            'V71 left the temporary bridge behind: %. A bridge that derives one column from another outlives '
            'its column as a second source of truth, which is the defect ADR-0004 decision 1 exists to '
            'remove. See V66 step 5.', v_left;
    END IF;

    SELECT string_agg(t, ', ') INTO v_missing
      FROM unnest(ARRAY['sales_orders', 'sales_order_items', 'purchase_orders',
                        'purchase_order_details', 'goods_receipts']) AS t
     WHERE NOT EXISTS (SELECT 1 FROM pg_trigger g
                        WHERE g.tgrelid = t::regclass
                          AND NOT g.tgisinternal
                          AND g.tgdeferrable
                          AND g.tgfoid = 'assert_document_transition_recorded'::regproc);

    IF v_missing IS NOT NULL THEN
        RAISE EXCEPTION
            'V71 removed the deferred transition pair from: %. That pair is what refuses a commit in which a '
            'state moved with no status_transition row (ADR-0004 decision 5), and it belongs to the state, '
            'not to the pointer this file drops.', v_missing;
    END IF;
END $$;

-- ============================================
-- 4. The catalog is the label, and it is not a casualty of the drop
-- ============================================
-- The five in-scope families keep one row per constant, with its code: that is the row every response
-- projects `statusId`/`statusName` from now that no column stores the pointer, so a row lost here turns a
-- read into a response with no label at all. The two out-of-scope families keep their rows too — `V18`
-- seeds five appointment states and `V20` four employment ones — because the same table is their catalog
-- and this migration has no business in it.
DO $$
DECLARE
    v_uncoded text;
    v_missing text;
    v_out_of_scope integer;
BEGIN
    SELECT string_agg(st.status_type_name || '/' || coalesce(s.code, s.status_name), ', '
                      ORDER BY st.status_type_name, s.status_name) INTO v_uncoded
      FROM statuses s JOIN status_types st ON st.id = s.status_type_id
     WHERE st.status_type_name IN ('SALES_ORDER', 'SALES_ORDER_ITEM', 'PURCHASE_ORDER',
                                   'PURCHASE_ORDER_DETAIL', 'GOODS_RECEIPT')
       AND s.code IS NULL;

    IF v_uncoded IS NOT NULL THEN
        RAISE EXCEPTION 'V71 found in-scope catalog rows without a code: %', v_uncoded;
    END IF;

    -- One row per constant, in the family the constant belongs to. A family with no row at all matches
    -- nothing in the query above, so this is the half that catches a deleted family instead of a deleted
    -- code.
    SELECT string_agg(f.family || ' is missing ' || f.constant, ', ' ORDER BY f.family, f.constant)
      INTO v_missing
      FROM (VALUES ('SALES_ORDER', 'DRAFT'), ('SALES_ORDER', 'ACTIVE'), ('SALES_ORDER', 'PENDING'),
                   ('SALES_ORDER', 'COMPLETED'), ('SALES_ORDER', 'CANCELLED'),
                   ('SALES_ORDER_ITEM', 'PENDING'), ('SALES_ORDER_ITEM', 'ADDED'),
                   ('SALES_ORDER_ITEM', 'CANCELLED'),
                   ('PURCHASE_ORDER', 'DRAFT'), ('PURCHASE_ORDER', 'SENT'), ('PURCHASE_ORDER', 'ACCEPTED'),
                   ('PURCHASE_ORDER', 'IN_TRANSIT'), ('PURCHASE_ORDER', 'RECEIVED'),
                   ('PURCHASE_ORDER', 'BILLED'), ('PURCHASE_ORDER', 'CLOSED'), ('PURCHASE_ORDER', 'REJECTED'),
                   ('PURCHASE_ORDER_DETAIL', 'PENDING'), ('PURCHASE_ORDER_DETAIL', 'IN_PROCESS'),
                   ('PURCHASE_ORDER_DETAIL', 'IN_TRANSIT'), ('PURCHASE_ORDER_DETAIL', 'PARTIAL_RECEIVED'),
                   ('PURCHASE_ORDER_DETAIL', 'RECEIVED'), ('PURCHASE_ORDER_DETAIL', 'REJECTED'),
                   ('PURCHASE_ORDER_DETAIL', 'CANCELLED'),
                   ('GOODS_RECEIPT', 'REGISTERED'))
           AS f (family, constant)
     WHERE NOT EXISTS (SELECT 1
                         FROM statuses s JOIN status_types st ON st.id = s.status_type_id
                        WHERE st.status_type_name = f.family
                          AND s.code = f.constant);

    IF v_missing IS NOT NULL THEN
        RAISE EXCEPTION
            'V71 would leave the catalog unable to label a state: %. `statuses` is the label of the code '
            '(ADR-0004 decision 7), and every response reads its statusId/statusName from these rows now '
            'that no column stores the pointer.', v_missing;
    END IF;

    SELECT count(*) INTO v_out_of_scope
      FROM statuses s JOIN status_types st ON st.id = s.status_type_id
     WHERE st.status_type_name IN ('APPOINTMENT', 'EMPLOYEE_STATUS');

    IF v_out_of_scope <> 9 THEN
        RAISE EXCEPTION
            'V71 expected the two out-of-scope families to still carry their nine seeded rows (V18 seeds '
            'five appointment states, V20 four employment ones) and found %. Those rows are the catalog of '
            'two families this ADR does not type, and this migration has no business in them.', v_out_of_scope;
    END IF;
END $$;
