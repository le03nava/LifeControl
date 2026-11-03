-- ============================================
-- V66 — Document state is typed (ADR-0004), the schema half
-- ============================================
-- The five document families stop storing a pointer to a catalog row and start storing a state the
-- compiler knows. The row gets `status VARCHAR(24) NOT NULL` with a `CHECK` listing its enum's constants —
-- **not** a foreign key to `statuses`, which can be edited at runtime and says nothing to the compiler
-- (decision 1). The catalog does not disappear: it keeps its rows and gains a `code` (the enum constant)
-- for the five families that have one, because a label is translation and renaming one changes what the
-- screen says, not what the row means (decision 7).
--
-- WHY THIS FILE IS A BRIDGE AND NOT THE WHOLE STORY
-- -------------------------------------------------
-- `status_id` still exists: it is today's authority, every writer sets it, and it dies in step 9 of the
-- ADR — task T8 of this delivery, which drops the column, its four indexes and the trigger this file
-- creates. So the column added here is `NOT NULL` while the only column the application writes is
-- `status_id`, and those two facts cannot both be true without something in between. That something is
-- `sync_document_status_from_catalog()`, created in step 5: it derives `status` from the catalog's code
-- on insert, refuses a row whose stored state disagrees with that code, and leaves `status` NULL when the
-- catalog row has no code — which the `NOT NULL` then refuses, fail closed.
--
-- **The trigger is temporary and it is named here so nobody has to guess.** T8 drops it with `status_id`,
-- in the same file, and the function's own catalog comment says so too. A bridge left behind after its
-- column is gone would be a second source of truth forever.
--
-- WHY THE ORDER IS THE ADR'S, AND WHY THE FIRST STEP IS A WINDOW
-- -------------------------------------------------------------
-- Step 0 opens a `NO FORCE ROW LEVEL SECURITY` window on the three armed document tables, and step 6
-- closes it. `V52`'s `apply_tenant_isolation` sets `FORCE ROW LEVEL SECURITY`, which filters the table's
-- **owner** — the role Flyway runs as — and `V54:26` records the consequence in the tree's own words: on
-- such a table a statement matches **zero rows and reports success**. Without the window the backfill of
-- step 2 numbers nothing and the assertions of steps 3 and 6 read nothing, so "the backfill reached every
-- row" and "the backfill reached no row" print the same green. `NO FORCE` is the only door a `NOSUPERUSER`
-- owner has. The window carries two self-checks and this file orders them the way `V57:85` does: that the
-- window took, and that a statement inside it reached a row — the second one asked **before** the `FORCE`
-- ALTERs of step 6, because after them the owner reads zero rows and that assertion cannot fire at all.
-- `V64` gets the first half right and not the second: it forces `purchase_orders` and `company_stores` at
-- `:265-266` and then asks the row question in the `DO` block that follows at `:268-304`, so its second
-- self-check is vacuous over a forced table. That is a pre-existing finding against `V64`, recorded here
-- because this file must not repeat it; it belongs to `V64`'s own follow-up and is **not** something this
-- migration fixes — `V64` is not edited by this unit.
--
-- WHY `statuses.code` IS NULLABLE
-- -------------------------------
-- `statuses` is one catalog for seven families and this ADR puts two of them out of scope **by decision**:
-- `APPOINTMENT` (`V18:59-86`, five rows) and employment (`V20:60-78`, four). Those rows have no domain
-- enum, so a code for them would be a constant that does not exist, and the parity test — which fails on a
-- row without a constant — would then fail on them. `NOT NULL` here and "out of scope" there cannot both
-- be true. What replaces it is scope, in two places: the parity test joins only the five in-scope families,
-- and step 4 asserts in the migration that every row of those five has a code. Nine rows stay without one
-- on purpose, and that is the scope decision visible in the data instead of only in prose.

-- ============================================
-- 0. The NO FORCE window, opened before the first read
-- ============================================
-- Three tables, because three are armed once ADR-0003 lands: `purchase_orders` since `V53:126`, and
-- `sales_orders` and `goods_receipts` since `V65`. `sales_order_items` and `purchase_order_details` are
-- deliberately absent — they carry no `company_id` and therefore no policy, so there is no window to open.
ALTER TABLE purchase_orders NO FORCE ROW LEVEL SECURITY;
ALTER TABLE sales_orders    NO FORCE ROW LEVEL SECURITY;
ALTER TABLE goods_receipts  NO FORCE ROW LEVEL SECURITY;

-- The window fails silently by construction, so it is checked, and both questions are asked while it is
-- still open. `V57:85` is the shape this file follows: one `DO` block that verifies the window took, and the
-- row question asked inside the same window, before the closing `FORCE`. `V64` carries the same first check
-- but asks its row question only after its own `FORCE` (`:265-266` then `:268-304`), which is the defect this
-- file avoids on purpose — see the header. Step 6's two `DO` blocks are the same two questions at the other
-- end, and step 3 asks the row one a third time, all of them inside the window.
DO $$
DECLARE
    v_still_forced text;
BEGIN
    SELECT string_agg(c.relname, ', ' ORDER BY c.relname) INTO v_still_forced
      FROM pg_class c
     WHERE c.relkind = 'r' AND c.relforcerowsecurity
       AND c.relname IN ('purchase_orders', 'sales_orders', 'goods_receipts');

    IF v_still_forced IS NOT NULL THEN
        RAISE EXCEPTION
            'V66 could not open its NO FORCE window on %: the backfill below would map no row, the null '
            'assertion would read nothing, and the NOT NULLs would pass over a column of nulls. See ADR-0005 '
            'correction 3 and ADR-0004 migration step 0.', v_still_forced;
    END IF;
END $$;

-- ============================================
-- 1. The typed column, nullable first
-- ============================================
-- Added without its constraint on purpose: a `CHECK` and a `NOT NULL` stated here would refuse the very
-- rows step 2 has to rewrite. The five tables are the five families ADR-0004 types, and `status_id` stays
-- beside `status` until T8.
ALTER TABLE sales_orders          ADD COLUMN status VARCHAR(24);
ALTER TABLE sales_order_items     ADD COLUMN status VARCHAR(24);
ALTER TABLE purchase_orders       ADD COLUMN status VARCHAR(24);
ALTER TABLE purchase_order_details ADD COLUMN status VARCHAR(24);
ALTER TABLE goods_receipts        ADD COLUMN status VARCHAR(24);

-- ============================================
-- 2. The backfill, an explicit mapping per family
-- ============================================
-- Not a derivation: the migration names each catalog value and the constant it becomes, so a rename can
-- only fail loudly (the assertions below) instead of silently producing a value the enum lacks. Not a
-- default either — writing the family's first constant into every row would turn a stored fact into a
-- guess and pass every constraint this file adds.
--
-- The family filter is not decoration. `Rejected`, `In Transit`, `Cancelled` and `Received` are names that
-- two or more families share, and `statuses` is one table: the join on `status_id` alone would let a
-- document that points at another family's catalog row map through the wrong `VALUES` list — the exact
-- cross-match this filter forbids. A row left unmapped by it is reported by the assertion at the end.

-- SALES_ORDER — five states (SalesOrderState)
UPDATE sales_orders d
   SET status = m.enum_value
  FROM statuses s
  JOIN status_types st ON st.id = s.status_type_id
  JOIN (VALUES ('Draft', 'DRAFT'), ('Active', 'ACTIVE'), ('Pending', 'PENDING'),
               ('Completed', 'COMPLETED'), ('Cancelled', 'CANCELLED'))
       AS m (catalog_name, enum_value) ON m.catalog_name = s.status_name
 WHERE s.id = d.status_id
   AND st.status_type_name = 'SALES_ORDER';

-- SALES_ORDER_ITEM — three states (SalesOrderItemState)
UPDATE sales_order_items d
   SET status = m.enum_value
  FROM statuses s
  JOIN status_types st ON st.id = s.status_type_id
  JOIN (VALUES ('Pending', 'PENDING'), ('Added', 'ADDED'), ('Cancelled', 'CANCELLED'))
       AS m (catalog_name, enum_value) ON m.catalog_name = s.status_name
 WHERE s.id = d.status_id
   AND st.status_type_name = 'SALES_ORDER_ITEM';

-- PURCHASE_ORDER — eight states (PurchaseOrderState)
UPDATE purchase_orders d
   SET status = m.enum_value
  FROM statuses s
  JOIN status_types st ON st.id = s.status_type_id
  JOIN (VALUES ('Draft', 'DRAFT'), ('Sent', 'SENT'), ('Accepted', 'ACCEPTED'),
               ('In Transit', 'IN_TRANSIT'), ('Received', 'RECEIVED'), ('Billed', 'BILLED'),
               ('Closed', 'CLOSED'), ('Rejected', 'REJECTED'))
       AS m (catalog_name, enum_value) ON m.catalog_name = s.status_name
 WHERE s.id = d.status_id
   AND st.status_type_name = 'PURCHASE_ORDER';

-- PURCHASE_ORDER_DETAIL — SEVEN states (PurchaseOrderDetailState), and they are not the item states:
-- the detail machine has its own tail (`In Process`, `Partial Received`) and no `ADDED`. Copying the item
-- set here would both omit five real states and admit one no purchase-order detail has ever held.
UPDATE purchase_order_details d
   SET status = m.enum_value
  FROM statuses s
  JOIN status_types st ON st.id = s.status_type_id
  JOIN (VALUES ('Pending', 'PENDING'), ('In Process', 'IN_PROCESS'), ('In Transit', 'IN_TRANSIT'),
               ('Partial Received', 'PARTIAL_RECEIVED'), ('Received', 'RECEIVED'),
               ('Rejected', 'REJECTED'), ('Cancelled', 'CANCELLED'))
       AS m (catalog_name, enum_value) ON m.catalog_name = s.status_name
 WHERE s.id = d.status_id
   AND st.status_type_name = 'PURCHASE_ORDER_DETAIL';

-- GOODS_RECEIPT — one state (`V12:58-64` seeds exactly one), typed without inventing a cancellation the
-- product has not asked for.
UPDATE goods_receipts d
   SET status = m.enum_value
  FROM statuses s
  JOIN status_types st ON st.id = s.status_type_id
  JOIN (VALUES ('Registered', 'REGISTERED')) AS m (catalog_name, enum_value)
       ON m.catalog_name = s.status_name
 WHERE s.id = d.status_id
   AND st.status_type_name = 'GOODS_RECEIPT';

-- The assertion step 1 above is worthless without: **per table**, every catalog name that stayed unmapped.
-- A renamed catalog value produces a NULL like an empty table does, and only a question about the rows
-- separates the two — a migration that mapped nothing and one that mapped everything both report success.
--
-- It runs inside step 0's window on purpose. Over a forced table this query matches no row and the
-- migration would report a clean backfill over a column of nulls.
DO $$
DECLARE
    v_unmapped text;
BEGIN
    SELECT string_agg(k.relation || ' (' || k.names || ')', '; ' ORDER BY k.relation) INTO v_unmapped
      FROM (SELECT 'sales_orders' AS relation,
                   string_agg(s.status_name, ', ' ORDER BY s.status_name) AS names
              FROM sales_orders d JOIN statuses s ON s.id = d.status_id
             WHERE d.status IS NULL
            UNION ALL
            SELECT 'sales_order_items',
                   string_agg(s.status_name, ', ' ORDER BY s.status_name)
              FROM sales_order_items d JOIN statuses s ON s.id = d.status_id
             WHERE d.status IS NULL
            UNION ALL
            SELECT 'purchase_orders',
                   string_agg(s.status_name, ', ' ORDER BY s.status_name)
              FROM purchase_orders d JOIN statuses s ON s.id = d.status_id
             WHERE d.status IS NULL
            UNION ALL
            SELECT 'purchase_order_details',
                   string_agg(s.status_name, ', ' ORDER BY s.status_name)
              FROM purchase_order_details d JOIN statuses s ON s.id = d.status_id
             WHERE d.status IS NULL
            UNION ALL
            SELECT 'goods_receipts',
                   string_agg(s.status_name, ', ' ORDER BY s.status_name)
              FROM goods_receipts d JOIN statuses s ON s.id = d.status_id
             WHERE d.status IS NULL) k;

    IF v_unmapped IS NOT NULL THEN
        RAISE EXCEPTION
            'V66 could not map these catalog names to a constant: %. A row there kept a NULL status, which '
            'means either the catalog name was renamed or the document points at another family''s status '
            'row — the cross-match the family filter exists to refuse. The mapping is step 2 of ADR-0004 and '
            'it is the only place that correspondence is written down; a rename means editing it, never a '
            'derivation at run time.', v_unmapped;
    END IF;
END $$;

-- ============================================
-- 3. The state becomes required, and the enum becomes a constraint
-- ============================================
-- The order inside this step is the whole step: a constraint stated before the backfill would refuse the
-- rows it must accept, and a `NOT NULL` before the backfill would succeed over the empty string of an
-- `UPDATE` that reached no row.
DO $$
DECLARE
    v_missing text;
BEGIN
    SELECT string_agg(k.relation::text || ' ' || k.reason, '; ') INTO v_missing
      FROM (SELECT 'sales_orders'::regclass AS relation, 'rows without a status' AS reason
             WHERE EXISTS (SELECT 1 FROM sales_orders WHERE status IS NULL)
            UNION ALL
            SELECT 'sales_order_items'::regclass, 'rows without a status'
             WHERE EXISTS (SELECT 1 FROM sales_order_items WHERE status IS NULL)
            UNION ALL
            SELECT 'purchase_orders'::regclass, 'rows without a status'
             WHERE EXISTS (SELECT 1 FROM purchase_orders WHERE status IS NULL)
            UNION ALL
            SELECT 'purchase_order_details'::regclass, 'rows without a status'
             WHERE EXISTS (SELECT 1 FROM purchase_order_details WHERE status IS NULL)
            UNION ALL
            SELECT 'goods_receipts'::regclass, 'rows without a status'
             WHERE EXISTS (SELECT 1 FROM goods_receipts WHERE status IS NULL)) k;

    IF v_missing IS NOT NULL THEN
        RAISE EXCEPTION
            'V66 backfilled the typed state and these still carry none: %. Either the NO FORCE window at the '
            'top of this file did not take, or the VALUES mapping matched no catalog name — both report '
            'success under FORCE ROW LEVEL SECURITY. See ADR-0004 migration steps 0, 2 and 3.', v_missing;
    END IF;
END $$;

-- The value lists are the enums' constant names, verbatim from the ADR's `## Schema` block. They are one
-- of the two copies the parity test couples (the other is the enum itself): a constant added to the enum
-- and not here is refused by the database, and one added here and not there is refused by the same test.
ALTER TABLE sales_orders
    ADD CONSTRAINT ck_sales_orders_status CHECK (status IN (
        'DRAFT', 'ACTIVE', 'PENDING', 'COMPLETED', 'CANCELLED')),
    ALTER COLUMN status SET NOT NULL;

ALTER TABLE sales_order_items
    ADD CONSTRAINT ck_sales_order_items_status CHECK (status IN (
        'PENDING', 'ADDED', 'CANCELLED')),
    ALTER COLUMN status SET NOT NULL;

ALTER TABLE purchase_orders
    ADD CONSTRAINT ck_purchase_orders_status CHECK (status IN (
        'DRAFT', 'SENT', 'ACCEPTED', 'IN_TRANSIT', 'RECEIVED', 'BILLED', 'CLOSED', 'REJECTED')),
    ALTER COLUMN status SET NOT NULL;

-- Seven states, and this is the table where a copy of the item set is a real defect instead of a typo:
-- `IN_PROCESS` and `PARTIAL_RECEIVED` exist only here, and `ADDED` is refused here.
ALTER TABLE purchase_order_details
    ADD CONSTRAINT ck_purchase_order_details_status CHECK (status IN (
        'PENDING', 'IN_PROCESS', 'IN_TRANSIT', 'PARTIAL_RECEIVED', 'RECEIVED', 'REJECTED', 'CANCELLED')),
    ALTER COLUMN status SET NOT NULL;

ALTER TABLE goods_receipts
    ADD CONSTRAINT ck_goods_receipts_status CHECK (status IN ('REGISTERED')),
    ALTER COLUMN status SET NOT NULL;

-- ============================================
-- 4. The catalog gains a code, inside the scope the ADR claims
-- ============================================
-- The mapping is step 2's, read from the other side: one row per constant, keyed by the family it belongs
-- to. `status_type_name` is part of the key and not context, because `statuses` is one table for seven
-- families and four names are shared.
ALTER TABLE statuses ADD COLUMN code VARCHAR(24);

UPDATE statuses s
   SET code = m.enum_value
  FROM status_types st,
       (VALUES ('SALES_ORDER', 'Draft', 'DRAFT'), ('SALES_ORDER', 'Active', 'ACTIVE'),
               ('SALES_ORDER', 'Pending', 'PENDING'), ('SALES_ORDER', 'Completed', 'COMPLETED'),
               ('SALES_ORDER', 'Cancelled', 'CANCELLED'),
               ('SALES_ORDER_ITEM', 'Pending', 'PENDING'), ('SALES_ORDER_ITEM', 'Added', 'ADDED'),
               ('SALES_ORDER_ITEM', 'Cancelled', 'CANCELLED'),
               ('PURCHASE_ORDER', 'Draft', 'DRAFT'), ('PURCHASE_ORDER', 'Sent', 'SENT'),
               ('PURCHASE_ORDER', 'Accepted', 'ACCEPTED'), ('PURCHASE_ORDER', 'In Transit', 'IN_TRANSIT'),
               ('PURCHASE_ORDER', 'Received', 'RECEIVED'), ('PURCHASE_ORDER', 'Billed', 'BILLED'),
               ('PURCHASE_ORDER', 'Closed', 'CLOSED'), ('PURCHASE_ORDER', 'Rejected', 'REJECTED'),
               ('PURCHASE_ORDER_DETAIL', 'Pending', 'PENDING'),
               ('PURCHASE_ORDER_DETAIL', 'In Process', 'IN_PROCESS'),
               ('PURCHASE_ORDER_DETAIL', 'In Transit', 'IN_TRANSIT'),
               ('PURCHASE_ORDER_DETAIL', 'Partial Received', 'PARTIAL_RECEIVED'),
               ('PURCHASE_ORDER_DETAIL', 'Received', 'RECEIVED'),
               ('PURCHASE_ORDER_DETAIL', 'Rejected', 'REJECTED'),
               ('PURCHASE_ORDER_DETAIL', 'Cancelled', 'CANCELLED'),
               ('GOODS_RECEIPT', 'Registered', 'REGISTERED'))
       AS m (type_name, catalog_name, enum_value)
 WHERE st.id = s.status_type_id
   AND st.status_type_name = m.type_name
   AND s.status_name = m.catalog_name;

-- `UNIQUE` rather than the table's habit of a plain constraint: NULLs are distinct in a B-tree, so the nine
-- out-of-scope rows cost nothing and the in-scope pairs are still one-to-one. Without it a second row of
-- one family could carry the code its enum already has elsewhere, and a document would have two labels.
ALTER TABLE statuses
    ADD CONSTRAINT uq_statuses_type_code UNIQUE (status_type_id, code);

-- The assertion, and it is the one that replaces `NOT NULL`: every row of the five in-scope families has a
-- code. An out-of-scope row is allowed to have none, by the scope decision at the top of this file — and
-- `APPOINTMENT`/`EMPLOYEE_STATUS` rows are therefore not named here, which is the decision, not an
-- omission. A family name spelled differently in `status_types` would make this block pass with zero rows
-- matched; step 2's assertion is what covers the spelling from the other side, and the parity test covers
-- it from the enum's.
DO $$
DECLARE
    uncoded TEXT;
BEGIN
    SELECT string_agg(s.status_name, ', ' ORDER BY s.status_name) INTO uncoded
      FROM statuses s JOIN status_types st ON st.id = s.status_type_id
     WHERE st.status_type_name IN ('SALES_ORDER', 'SALES_ORDER_ITEM', 'PURCHASE_ORDER',
                                   'PURCHASE_ORDER_DETAIL', 'GOODS_RECEIPT')
       AND s.code IS NULL;
    IF uncoded IS NOT NULL THEN
        RAISE EXCEPTION 'statuses.code did not reach: %', uncoded;
    END IF;
END $$;

-- ============================================
-- 5. The bridge from status_id to status — TEMPORARY, and T8 drops it
-- ============================================
-- This function and its five triggers exist for exactly the window in which `status_id` and `status` are
-- both live: created here, dropped by task T8 together with `status_id` and its four indexes. **While the
-- column is gone, a bridge that derives one from the other is no longer a bridge — it is a second source
-- of truth, which is the defect decision 1 exists to remove.**
--
-- What it buys, and why the `NOT NULL` of step 3 is legal at all: every writer today sets `status_id` and
-- nothing else, and without this trigger each of those writes would be refused the moment the column
-- became required. It buys that with three rules and no fourth one:
--
--   1. `status_id IS NULL` → the row is left exactly as the writer wrote it. Nothing to derive from.
--   2. `status IS NULL` → derive it from the catalog's code. This is the path every existing writer takes.
--   3. `status` present and different from that code → refuse, naming the table and both values. A silent
--      divergence must be impossible while both columns exist, because after T8 the wrong one would be the
--      only one left and nothing would have said so.
--
-- A `status_id` whose catalog row carries no code (an out-of-scope family, or a row created at runtime)
-- leaves `status` NULL by rule 2 and the `NOT NULL` refuses the row: fail closed, in the database, rather
-- than a state nobody derived.
CREATE FUNCTION sync_document_status_from_catalog() RETURNS trigger AS $$
DECLARE
    v_code VARCHAR(24);
BEGIN
    IF NEW.status_id IS NULL THEN
        RETURN NEW;
    END IF;

    SELECT s.code INTO v_code FROM statuses s WHERE s.id = NEW.status_id;

    IF NEW.status IS NULL THEN
        NEW.status := v_code;
        RETURN NEW;
    END IF;

    IF NEW.status IS DISTINCT FROM v_code THEN
        RAISE EXCEPTION
            '%: stored status % disagrees with the catalog code % for status_id %',
            TG_TABLE_NAME, NEW.status, coalesce(v_code, '(no code)'), NEW.status_id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Said in the catalog too, and not only in this file: `\df+` is where somebody looks when the trigger
-- surprises them, and "temporary" is the one thing they need to know about it.
COMMENT ON FUNCTION sync_document_status_from_catalog() IS
    'TEMPORARY (ADR-0004 task T2): derives document.status from statuses.code while status_id still exists, '
    'and refuses a stored state that disagrees with it. Dropped by task T8 together with status_id — it is '
    'not a second source of truth and must not outlive the column it bridges from.';

CREATE TRIGGER trg_sales_orders_status_bridge
    BEFORE INSERT OR UPDATE ON sales_orders
    FOR EACH ROW EXECUTE FUNCTION sync_document_status_from_catalog();

CREATE TRIGGER trg_sales_order_items_status_bridge
    BEFORE INSERT OR UPDATE ON sales_order_items
    FOR EACH ROW EXECUTE FUNCTION sync_document_status_from_catalog();

CREATE TRIGGER trg_purchase_orders_status_bridge
    BEFORE INSERT OR UPDATE ON purchase_orders
    FOR EACH ROW EXECUTE FUNCTION sync_document_status_from_catalog();

CREATE TRIGGER trg_purchase_order_details_status_bridge
    BEFORE INSERT OR UPDATE ON purchase_order_details
    FOR EACH ROW EXECUTE FUNCTION sync_document_status_from_catalog();

CREATE TRIGGER trg_goods_receipts_status_bridge
    BEFORE INSERT OR UPDATE ON goods_receipts
    FOR EACH ROW EXECUTE FUNCTION sync_document_status_from_catalog();

-- The assertion: "the same bridge for the other four" cannot stay a comment. A table missing its trigger
-- refuses every writer of today at the `NOT NULL` of step 3 with a message about nulls instead of a
-- message about the bridge, and the four services that write it would each discover it separately.
DO $$
DECLARE
    v_missing TEXT;
BEGIN
    SELECT string_agg(t, ', ') INTO v_missing
      FROM unnest(ARRAY['sales_orders', 'sales_order_items', 'purchase_orders',
                        'purchase_order_details', 'goods_receipts']) AS t
     WHERE NOT EXISTS (
        SELECT 1 FROM pg_trigger g
        WHERE g.tgrelid = t::regclass
          AND NOT g.tgisinternal
          AND g.tgfoid = 'sync_document_status_from_catalog'::regproc);
    IF v_missing IS NOT NULL THEN
        RAISE EXCEPTION
            'document table(s) without the temporary status bridge: %. Every writer today sets status_id '
            'and nothing else, so without the bridge the NOT NULL of step 3 refuses each one. See ADR-0004 '
            'task T2 step 5.', v_missing;
    END IF;
END $$;

-- ============================================
-- 6. The window closes, and the close is checked
-- ============================================
-- The row question comes FIRST, and that is not cosmetic: `FORCE ROW LEVEL SECURITY` filters the owner
-- too, so a query over these tables placed after the three ALTERs below matches zero rows and reports the
-- same green as a backfill that reached every one. It is asked while the window is still open, which is
-- the only place it can answer; ADR-0004's step 0 names this as the second consequence of the window, and
-- step 3 already asks it about all five tables — this is the re-check the ADR wants at the close, and it
-- is worth nothing after the `FORCE`s.
DO $$
DECLARE
    v_missing text;
BEGIN
    SELECT string_agg(k.relation::text || ' ' || k.reason, '; ') INTO v_missing
      FROM (SELECT 'sales_orders'::regclass AS relation, 'rows without a status' AS reason
             WHERE EXISTS (SELECT 1 FROM sales_orders WHERE status IS NULL)
            UNION ALL
            SELECT 'sales_order_items'::regclass, 'rows without a status'
             WHERE EXISTS (SELECT 1 FROM sales_order_items WHERE status IS NULL)
            UNION ALL
            SELECT 'purchase_orders'::regclass, 'rows without a status'
             WHERE EXISTS (SELECT 1 FROM purchase_orders WHERE status IS NULL)
            UNION ALL
            SELECT 'purchase_order_details'::regclass, 'rows without a status'
             WHERE EXISTS (SELECT 1 FROM purchase_order_details WHERE status IS NULL)
            UNION ALL
            SELECT 'goods_receipts'::regclass, 'rows without a status'
             WHERE EXISTS (SELECT 1 FROM goods_receipts WHERE status IS NULL)) k;

    IF v_missing IS NOT NULL THEN
        RAISE EXCEPTION
            'V66 is about to close the NO FORCE window over documents that carry no typed state: %. A file '
            'that backfilled nothing and a file that backfilled everything print the same green, and only '
            'this question separates them. See ADR-0004 migration step 7.', v_missing;
    END IF;
END $$;

ALTER TABLE purchase_orders FORCE ROW LEVEL SECURITY;
ALTER TABLE sales_orders    FORCE ROW LEVEL SECURITY;
ALTER TABLE goods_receipts  FORCE ROW LEVEL SECURITY;

DO $$
DECLARE
    v_not_forced text;
BEGIN
    SELECT string_agg(c.relname, ', ' ORDER BY c.relname) INTO v_not_forced
      FROM pg_class c
     WHERE c.relkind = 'r' AND NOT c.relforcerowsecurity
       AND c.relname IN ('purchase_orders', 'sales_orders', 'goods_receipts');

    IF v_not_forced IS NOT NULL THEN
        RAISE EXCEPTION
            'V66 left % without FORCE ROW LEVEL SECURITY: a table whose owner is exempt from its own policy '
            'is the state ADR-0005 exists to remove, and a window left open weakens the table for everything '
            'that runs after it (V55).', v_not_forced;
    END IF;
END $$;
