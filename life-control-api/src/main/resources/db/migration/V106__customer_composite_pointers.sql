-- ============================================
-- V106 — The composite customer pointers
-- ============================================
--
-- WHAT THIS MIGRATION IS
--
-- `V105` gave `customers` a `company_id` and a policy. A policy protects the rows of `customers`; it does
-- **not** protect a pointer *to* one, because a referential check bypasses row-level security by
-- PostgreSQL rule. So a table that names a customer by id alone lets one tenant write a row that is
-- legitimately its own and point it at another tenant's customer: the row passes the policy and the
-- foreign key both. The fix is the pointer, not another policy — `(company_id, customer_id)` referenced
-- against `customers (company_id, id)`, which is `derived-accounting` S7's rule and this record's `S5`.
--
-- `MATCH SIMPLE`, AND WHY NULL STAYS LEGAL
--
-- A composite foreign key defaults to `MATCH SIMPLE`, which means a key with a `NULL` in **any** column is
-- not checked at all. That is exactly what keeps a counter sale legal after `V105` made
-- `sales_orders.customer_id` nullable: a sale with no receiver states `customer_id IS NULL`, the key is
-- skipped, and the schema says the same thing the business does. It is written explicitly below so the
-- behaviour is a decision in the file and not a default somebody has to look up.
--
-- WHAT THE FILE MEASURES INSTEAD OF ASSUMING
--
-- The record names `sales_orders` first and says "whatever else the migration measures by querying
-- `pg_constraint` instead of assuming", so this file does exactly that: it reads every foreign key whose
-- referenced table is `customers`, and it decides per row. Measured on this tree:
--
--   * `sales_orders.customer_id` — `V1__baseline_schema.sql:471`, `NOT NULL` until `V105`. The table
--     carries `company_id` since `V64__document_identity_per_tenant.sql:142`, so it takes the composite
--     pointer and that is the pointer this file exists for.
--   * `scheduling_appointments.customer_id` — `V18__scheduling_appointments.sql:37`, nullable. The table
--     carries **no `company_id`**: its tenant is derived from `company_store_id`, and it is not armed by
--     `V53` or by anything since. The composite pointer is therefore not expressible there today, and this
--     file does not invent a second tenant axis to force it. It states the exception, names the table, and
--     asserts that the table is still unarmed — so the omission is a measured fact with a reason, not a
--     silent skip. Arming the scheduling tables is its own unit; when it lands, its migration converts this
--     pointer the same way this one converts `sales_orders`.
--
-- Anything else found with an id-only pointer refuses the file by name. That is the point of asking the
-- catalog: a third table added between `V101` and here cannot slip past as "the two we remembered".
--
-- WHAT THIS FILE DOES NOT DO
--
-- It writes no row and opens no `NO FORCE` window: it changes constraints only, the delete/update rules are
-- copied from the ones already there rather than chosen again, and the existing `(company_id, id)` unique
-- key `V105` added is the only thing a composite pointer needs. It adds no index on the referencing side —
-- PostgreSQL indexes the referenced columns only, and `idx_so_customer` (`V1:485`) already answers the
-- delete-side scan by `customer_id`.
-- ============================================

-- ─── 0. the window the validation reads through ────────────────────────────────────────────────
-- `ADD CONSTRAINT … FOREIGN KEY` checks the rows that already exist, and that check reads both sides of the
-- key. `customers` is forced since `V105` and `sales_orders` since `V65`, and Flyway states no scope — so
-- without this window the referenced side reads **zero** customers and every sale with a customer looks
-- orphaned: the constraint refuses the migration and blames the data for a policy. The window is opened before
-- the first `ALTER`, asserted (a bare `NO FORCE` statement says nothing), and closed at the end.
ALTER TABLE customers NO FORCE ROW LEVEL SECURITY;
ALTER TABLE sales_orders NO FORCE ROW LEVEL SECURITY;

DO $$
DECLARE
    v_open integer;
BEGIN
    SELECT count(*) INTO v_open
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public'
       AND c.relkind = 'r'
       AND c.relname IN ('customers', 'sales_orders')
       AND NOT c.relforcerowsecurity;

    IF v_open <> 2 THEN
        RAISE EXCEPTION
            'V106 could not open its NO FORCE window on both sides of the pointer it converts (it found % of 2 '
            'without FORCE). Under the policy the referenced side reads no customer, so the constraint would '
            'refuse every existing sale as an orphan — a refusal about the policy and not about the data.', v_open;
    END IF;
END $$;

-- ─── 1. the inventory, read from the catalog and frozen before anything is dropped ──────────────
CREATE TEMPORARY TABLE measured_customer_fk ON COMMIT DROP AS
SELECT con.conname                                   AS constraint_name,
       con.conrelid::regclass::text                  AS table_name,
       con.confdeltype                               AS delete_rule,
       con.confupdtype                               AS update_rule,
       EXISTS (SELECT 1
                 FROM pg_attribute a
                WHERE a.attrelid = con.conrelid
                  AND a.attname = 'company_id'
                  AND NOT a.attisdropped)                AS carries_company_id
  FROM pg_constraint con
 WHERE con.contype = 'f'
   AND con.confrelid = 'customers'::regclass;

-- ─── 2. one refusal for a pointer this file does not know how to convert ───────────────────────
DO $$
DECLARE
    v_unknown_shape text;
    v_unknown text;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM measured_customer_fk) THEN
        RAISE EXCEPTION 'V106 measured no foreign key referencing `customers`: the catalog query found '
            'nothing, which means it asked the wrong question and would convert nothing.';
    END IF;

    SELECT string_agg(m.table_name || '.' || m.constraint_name, ', ' ORDER BY m.table_name) INTO v_unknown
      FROM measured_customer_fk m
     WHERE NOT m.carries_company_id
       AND m.table_name <> 'scheduling_appointments';

    IF v_unknown IS NOT NULL THEN
        RAISE EXCEPTION 'V106 found a foreign key to `customers` on a table with no `company_id` that it '
            'does not know about: %. The one such pointer this tree has is `scheduling_appointments`, whose '
            'tenant is derived from its store and which no policy arms yet; a new one is a new tenant axis '
            'that has to be decided, not skipped.', v_unknown;
    END IF;

    SELECT string_agg(c.conrelid::regclass::text || '.' || c.conname, ', ' ORDER BY c.conrelid::regclass::text)
      INTO v_unknown_shape
      FROM pg_constraint c
     WHERE c.contype = 'f'
       AND c.confrelid = 'customers'::regclass
       AND (SELECT array_agg(a.attname::text ORDER BY k.ord)
              FROM unnest(c.conkey) WITH ORDINALITY AS k(attnum, ord)
              JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum)
           NOT IN (ARRAY['customer_id'], ARRAY['company_id', 'customer_id']);

    IF v_unknown_shape IS NOT NULL THEN
        RAISE EXCEPTION 'V106 found a foreign key to `customers` whose local columns are neither '
            '`customer_id` nor `(company_id, customer_id)`: %. Only those two shapes exist in this tree, and '
            'a third one would be a pointer nobody decided.', v_unknown_shape;
    END IF;
END $$;

-- ─── 3. every convertible pointer, in one pass over the measured inventory ─────────────────────
DO $$
DECLARE
    v_fk record;
    v_delete_rule text;
    v_update_rule text;
    v_local text[];
BEGIN
    FOR v_fk IN
        SELECT m.constraint_name, m.table_name, m.delete_rule, m.update_rule
          FROM measured_customer_fk m
         WHERE m.carries_company_id
         ORDER BY m.table_name, m.constraint_name
    LOOP
        SELECT array_agg(a.attname::text ORDER BY k.ord) INTO v_local
          FROM pg_constraint c
          CROSS JOIN LATERAL unnest(c.conkey) WITH ORDINALITY AS k(attnum, ord)
          JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum
         WHERE c.conrelid = v_fk.table_name::regclass
           AND c.conname = v_fk.constraint_name;

        -- Already converted (a replay, or a hand-applied equivalent): nothing to drop.
        IF v_local = ARRAY['company_id', 'customer_id'] THEN
            CONTINUE;
        END IF;

        v_delete_rule := CASE v_fk.delete_rule
                              WHEN 'a' THEN 'NO ACTION'
                              WHEN 'r' THEN 'RESTRICT'
                              WHEN 'c' THEN 'CASCADE'
                              WHEN 'n' THEN 'SET NULL'
                              WHEN 'd' THEN 'SET DEFAULT'
                         END;
        v_update_rule := CASE v_fk.update_rule
                              WHEN 'a' THEN 'NO ACTION'
                              WHEN 'r' THEN 'RESTRICT'
                              WHEN 'c' THEN 'CASCADE'
                              WHEN 'n' THEN 'SET NULL'
                              WHEN 'd' THEN 'SET DEFAULT'
                         END;

        IF v_delete_rule IS NULL OR v_update_rule IS NULL THEN
            RAISE EXCEPTION 'V106 does not know how to preserve the delete/update rule of % on %: it copies '
                'the existing rule instead of choosing one, so an unknown code is a refusal and not a guess.',
                v_fk.constraint_name, v_fk.table_name;
        END IF;

        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', v_fk.table_name, v_fk.constraint_name);

        EXECUTE format(
            'ALTER TABLE %s ADD CONSTRAINT %I FOREIGN KEY (company_id, customer_id) '
            'REFERENCES customers (company_id, id) MATCH SIMPLE ON DELETE %s ON UPDATE %s',
            v_fk.table_name, v_fk.constraint_name, v_delete_rule, v_update_rule);
    END LOOP;
END $$;

-- ─── 4. the windows close, and a forgotten close fails here instead of the next reader ─────────
ALTER TABLE customers FORCE ROW LEVEL SECURITY;
ALTER TABLE sales_orders FORCE ROW LEVEL SECURITY;

-- ─── 5. the read-back: the pointers are composite, the rules are the ones that were there ──────
DO $$
DECLARE
    v_broken text;
    v_exception_armed boolean;
    v_exception_columns text[];
    v_left_open text;
BEGIN
    SELECT string_agg(c.relname, ', ') INTO v_left_open
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public'
       AND c.relkind = 'r'
       AND c.relname IN ('customers', 'sales_orders')
       AND NOT c.relforcerowsecurity;

    IF v_left_open IS NOT NULL THEN
        RAISE EXCEPTION 'V106 opened a NO FORCE window and did not close it on: %. That table is now readable '
            'by its owner without its policy, which is the weakening V55 exists to refuse.', v_left_open;
    END IF;

    SELECT string_agg(m.table_name || '.' || m.constraint_name, ', ' ORDER BY m.table_name) INTO v_broken
      FROM measured_customer_fk m
     WHERE m.carries_company_id
       AND NOT EXISTS (
            SELECT 1
              FROM pg_constraint c
             WHERE c.contype = 'f'
               AND c.conrelid = m.table_name::regclass
               AND c.conname = m.constraint_name
               AND c.confrelid = 'customers'::regclass
               -- the local half: (company_id, customer_id)
               AND (SELECT array_agg(a.attname::text ORDER BY k.ord)
                      FROM unnest(c.conkey) WITH ORDINALITY AS k(attnum, ord)
                      JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum)
                   = ARRAY['company_id', 'customer_id']
               -- the referenced half: (company_id, id)
               AND (SELECT array_agg(a.attname::text ORDER BY k.ord)
                      FROM unnest(c.confkey) WITH ORDINALITY AS k(attnum, ord)
                      JOIN pg_attribute a ON a.attrelid = c.confrelid AND a.attnum = k.attnum)
                   = ARRAY['company_id', 'id']
               -- MATCH SIMPLE is what keeps a counter sale (customer_id IS NULL) legal.
               AND c.confmatchtype = 's'
               AND c.confdeltype = m.delete_rule
               AND c.confupdtype = m.update_rule);

    IF v_broken IS NOT NULL THEN
        RAISE EXCEPTION 'V106 left a pointer to `customers` that is not (company_id, customer_id) -> '
            '(company_id, id) with MATCH SIMPLE and its original delete/update rules: %. An id-only pointer '
            'lets one tenant name another tenant''s customer while the row it writes is its own, because a '
            'referential check bypasses row-level security (S5).', v_broken;
    END IF;

    -- The stated exception: the one measured pointer that has no company to pair with, still id-only, on a
    -- table no policy arms. Both halves are asserted so the reason stays true instead of becoming a comment
    -- somebody removed a migration later.
    SELECT c.relrowsecurity INTO v_exception_armed
      FROM pg_class c
     WHERE c.oid = 'scheduling_appointments'::regclass;

    SELECT array_agg(a.attname::text ORDER BY k.ord) INTO v_exception_columns
      FROM pg_constraint c
      CROSS JOIN LATERAL unnest(c.conkey) WITH ORDINALITY AS k(attnum, ord)
      JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum
     WHERE c.contype = 'f'
       AND c.conrelid = 'scheduling_appointments'::regclass
       AND c.confrelid = 'customers'::regclass;

    IF coalesce(v_exception_armed, false) THEN
        RAISE EXCEPTION 'V106 left `scheduling_appointments` with an id-only pointer to `customers` **and** '
            'armed: an armed table with an id-only pointer is exactly the hole S5 closes, so arming it needs '
            'its own migration that converts this pointer.';
    END IF;

    IF v_exception_columns IS DISTINCT FROM ARRAY['customer_id'] THEN
        RAISE EXCEPTION 'V106 expected `scheduling_appointments` to keep the id-only pointer to `customers` '
            'and read %. Its tenant is derived from `company_store_id` and it carries no `company_id`, so the '
            'composite form is not expressible there; if that changed, convert it here instead.', v_exception_columns;
    END IF;
END $$;
