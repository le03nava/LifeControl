-- ============================================
-- V105 — Customer tenant isolation, and the walk-in placeholder removed
-- ============================================
--
-- WHAT THIS MIGRATION IS
--
-- `customers` was the only table in this tree that carried customer data and no tenant: no `company_id`,
-- no `policy`, no `tenant_table` row. It is absent from `V53`'s armed list on purpose — that migration
-- froze the fifteen tables S2 named, and this one was not among them — so `GET /api/customers` answered
-- **every** tenant's customers to any `lc-sales` caller, name, email, phone and `rfc` included. The
-- record is `odd/tasks/customer-tenant-isolation.md` and the unit's two decisions are its `L1`: arm the
-- table as its own unit, and let a counter sale state **no receiver** instead of the fictitious
-- `'Cliente General'` that existed to satisfy a `NOT NULL` (`sales_order/SalesOrderRequest.java:13`).
--
-- WHY V105 AND V106 AND NOT V102..V104
--
-- Those three versions belong to an unmerged branch (`feat/cfdi-fiscal-profiles`, PR #905). Two migrations
-- with one version number are a Flyway failure the day both land — `Found more than one migration with
-- version 102` — so this file and its sibling take the next free pair after `V101`.
--
-- THE ORDER IS LOAD-BEARING, AND IT IS S2's
--
--   1. `sales_orders.customer_id` becomes **nullable**. A counter sale has no receiver, and a column that
--      cannot say so is what made the placeholder necessary in the first place.
--   2. Every reference to the placeholder is nulled **first**.
--   3. The placeholder row is deleted, and the file **refuses** if a reference survived step 2 — a delete
--      that leaves a dangling pointer is the same lie in the other direction.
--   4. `customers.company_id` is added **nullable** and backfilled **by proof**: the company named by the
--      `sales_orders` that reference the customer (`S3`). A customer referenced by the orders of more than
--      one company, or by none, refuses the whole file with the count and up to five `id`/`name` samples:
--      that is the cross-tenant state the missing column allowed, and the operator decides those rows once
--      with the list in hand. A default company for orphans is refused on purpose — it is the same guess
--      that produced the defect, one layer down.
--   5. `SET NOT NULL`, the `uq_customers_company_id` unique key the composite pointers need, and
--      `apply_tenant_isolation` — the shape `V53` uses, because the helper is the only way a table gets a
--      policy (ADR-0005).
--   6. A self-check that reads every one of those facts back.
--
-- WHY THE WINDOW, AND WHY IT IS OPENED BEFORE THE FIRST READ
--
-- `V65` armed **and** FORCEd `sales_orders` (`V52`'s `apply_tenant_isolation` sets both), so the owner —
-- the role Flyway runs as — is filtered by its own policy. A migration that `UPDATE`s or `DELETE`s a row
-- there affects **0 rows and reports success**: no error, no warning, a data migration that silently did
-- nothing and a deployment that looks green. `V57:44-57`, `V54:50-58`, `V64:30-42` and `V99`'s header all
-- record the same trap from the same direction. This file therefore opens `NO FORCE` on the three tables it
-- reads and writes — `sales_orders`, `customers` and `companies`, the third because `ADD CONSTRAINT … FOREIGN
-- KEY` validates its rows by reading `companies` and the policy would hide every one of them — **asserts the
-- windows are really open** (a bare `NO FORCE` statement does not), does the work, and re-forces at the end
-- with a second assertion that each one is closed.
--
-- WHERE THE ROW QUESTIONS ARE ASKED, AND WHY IT MATTERS
--
-- "The placeholder row is gone" and "every customer names a company" are asked **before**
-- `apply_tenant_isolation` runs, while `customers` is still unarmed — that is the only place the owner can
-- see the rows at all. Under a forced policy the same questions read zero rows and pass, which is exactly
-- the failure mode that makes a silent migration look finished. The structural questions (the column's
-- `NOT NULL`, the policy's two flags, the unique key, `sales_orders.customer_id`'s nullability and every
-- window being closed) are catalog facts, so they are asked **after** the re-force, where they belong.
--
-- WHAT THIS FILE DOES NOT DO
--
-- It does not edit `V3`: a migration cannot be rewritten once applied, and running the delete here is also
-- what leaves a **fresh** install clean (the seed row exists from `V3` and no order names it, so steps 2 and
-- 3 are the honest no-ops they should be). It does not assign a default company to an orphan, and it does
-- not touch `scheduling_appointments` — see `V106` for the two tables that name a customer and for the one
-- that cannot carry the composite pointer yet.
-- ============================================

-- ─── 1. the window every read and write below needs, opened before the first one ────────────────
-- Three tables, and the third is the one that is easy to forget: `companies` is armed and forced by `V53`
-- (`id = app.company_id` for both `USING` and `WITH CHECK`), and `ALTER TABLE customers ADD CONSTRAINT
-- fk_customers_company` validates its existing rows by reading `companies`. With the policy in front of that
-- read and no scope stated — Flyway states none — every company looks absent and the constraint refuses the
-- migration with `Key (company_id)=(…) is not present in table "companies"`, which is a lie about the data.
-- `customers` is not armed yet, so its window is a no-op today; it is stated anyway so the closing assertion
-- and the row questions read the same way for all three.
ALTER TABLE sales_orders NO FORCE ROW LEVEL SECURITY;
ALTER TABLE customers NO FORCE ROW LEVEL SECURITY;
ALTER TABLE companies NO FORCE ROW LEVEL SECURITY;

DO $$
DECLARE
    v_open integer;
BEGIN
    SELECT count(*) INTO v_open
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public'
       AND c.relkind = 'r'
       AND c.relname IN ('sales_orders', 'customers', 'companies')
       AND NOT c.relforcerowsecurity;

    IF v_open <> 3 THEN
        RAISE EXCEPTION
            'V105 could not open its NO FORCE window on all three tables it reads and writes (it found % of '
            '3 without FORCE). `sales_orders` is armed and forced since V65 and `companies` since V53, so as '
            'the owner this file would read no order, null no reference, backfill no company and validate a '
            'foreign key against an empty `companies` — each of those reporting success or blaming the data '
            'while the file did nothing. See V54, V57, V64 and V99.', v_open;
    END IF;
END $$;

-- ─── 2. a counter sale states no receiver (S2) ──────────────────────────────────────────────────
ALTER TABLE sales_orders ALTER COLUMN customer_id DROP NOT NULL;

-- ─── 3. every reference to the placeholder is nulled before the row is deleted ──────────────────
UPDATE sales_orders
   SET customer_id = NULL
 WHERE customer_id = '00000000-0000-0000-0000-000000000001';

-- `V68`'s constraint triggers on this table are `DEFERRABLE INITIALLY DEFERRED`, so the `UPDATE` above queued
-- their judgement for COMMIT — and PostgreSQL refuses an `ALTER TABLE` while trigger events are pending
-- (SQLSTATE 55006, measured on this file's first run against the document suites). `SET CONSTRAINTS ALL
-- IMMEDIATE` runs them now, inside the window, which is the same step `V68` takes for its own seed and `V80`
-- for its own backfill: the checks have to see these rows, and the `FORCE` further down must be allowed.
SET CONSTRAINTS ALL IMMEDIATE;

-- ─── 4. the placeholder is deleted, and a survivor refuses the whole file ───────────────────────
DO $$
DECLARE
    v_placeholder CONSTANT uuid := '00000000-0000-0000-0000-000000000001';
    v_references bigint;
BEGIN
    SELECT count(*) INTO v_references FROM sales_orders WHERE customer_id = v_placeholder;

    IF v_references > 0 THEN
        RAISE EXCEPTION
            'V105 nulled every reference to the seeded walk-in customer % and % sales order(s) still '
            'name it. Deleting the row would leave a dangling pointer, and keeping it is the fictitious '
            'receiver S2 removes. The window at the top of this file did not take, or something wrote a '
            'reference after the UPDATE.', v_placeholder, v_references;
    END IF;
END $$;

DELETE FROM customers WHERE id = '00000000-0000-0000-0000-000000000001';

-- ─── 5. the tenant column, added nullable so it can be proven before it is required ────────────
ALTER TABLE customers ADD COLUMN company_id UUID;

-- The proof: the one company the customer's orders name, and how many they name. `sales_orders.company_id`
-- is `NOT NULL` since `V64`, so an order always states one; the question this table answers is whether the
-- customer's orders agree.
CREATE TEMPORARY TABLE customer_company_proof ON COMMIT DROP AS
SELECT so.customer_id,
       -- `min(uuid)` does not exist in PostgreSQL, so the single company is taken from the deduplicated
       -- array the same aggregate builds. Only rows with `company_count = 1` are read below.
       (array_agg(DISTINCT so.company_id))[1] AS company_id,
       count(DISTINCT so.company_id)          AS company_count
  FROM sales_orders so
 WHERE so.customer_id IS NOT NULL
 GROUP BY so.customer_id;

UPDATE customers c
   SET company_id = p.company_id
  FROM customer_company_proof p
 WHERE p.customer_id = c.id
   AND p.company_count = 1;

-- ─── 6. a customer the orders cannot place refuses the migration (S3) ──────────────────────────
DO $$
DECLARE
    v_ambiguous bigint;
    v_orphans bigint;
    v_ambiguous_samples text;
    v_orphan_samples text;
BEGIN
    SELECT count(*) INTO v_ambiguous FROM customer_company_proof WHERE company_count > 1;

    SELECT count(*) INTO v_orphans
      FROM customers c
     WHERE NOT EXISTS (SELECT 1 FROM customer_company_proof p WHERE p.customer_id = c.id);

    IF v_ambiguous > 0 OR v_orphans > 0 THEN
        SELECT string_agg(s.sample, ', ' ORDER BY s.sample) INTO v_ambiguous_samples
          FROM (
                SELECT c.id::text || ' (' || c.name || ')' AS sample
                  FROM customers c
                  JOIN customer_company_proof p ON p.customer_id = c.id
                 WHERE p.company_count > 1
                 ORDER BY c.id
                 LIMIT 5
               ) s;

        SELECT string_agg(s.sample, ', ' ORDER BY s.sample) INTO v_orphan_samples
          FROM (
                SELECT c.id::text || ' (' || c.name || ')' AS sample
                  FROM customers c
                 WHERE NOT EXISTS (SELECT 1 FROM customer_company_proof p WHERE p.customer_id = c.id)
                 ORDER BY c.id
                 LIMIT 5
               ) s;

        RAISE EXCEPTION
            'V105 refuses to assign a company to % customer(s) named by sales orders of more than one '
            'company and to % named by none. Those rows are the cross-tenant state the missing column '
            'allowed, and the operator decides each one with the list in hand — a default company here '
            'would be the same guess that produced the defect. Named by several companies: [%]. Named by '
            'no order: [%].',
            v_ambiguous, v_orphans, coalesce(v_ambiguous_samples, 'none'), coalesce(v_orphan_samples, 'none');
    END IF;
END $$;

-- ─── 7. required, and referential: the tenant of a customer is a company of this database ──────
ALTER TABLE customers ALTER COLUMN company_id SET NOT NULL;

ALTER TABLE customers
    ADD CONSTRAINT fk_customers_company FOREIGN KEY (company_id) REFERENCES companies (id);

-- ─── 8. the key a composite pointer needs: (company_id, id) ────────────────────────────────────
-- Not decoration and not a duplicate of the primary key: a foreign key may only reference columns covered
-- by a unique constraint, so `V106`'s `(company_id, customer_id) -> customers (company_id, id)` has nowhere
-- to point without this. Its index also leads `company_id`, which is the index the policy's predicate reads.
ALTER TABLE customers
    ADD CONSTRAINT uq_customers_company_id UNIQUE (company_id, id);

-- ─── 9. the row questions, asked while `customers` is still readable as the owner ──────────────
DO $$
DECLARE
    v_placeholder bigint;
    v_orphans bigint;
    v_mismatch bigint;
BEGIN
    SELECT count(*) INTO v_placeholder
      FROM customers
     WHERE id = '00000000-0000-0000-0000-000000000001';

    SELECT count(*) INTO v_orphans FROM customers WHERE company_id IS NULL;

    SELECT count(*) INTO v_mismatch
      FROM customers c
      JOIN customer_company_proof p ON p.customer_id = c.id
     WHERE c.company_id <> p.company_id;

    IF v_placeholder <> 0 THEN
        RAISE EXCEPTION
            'V105 left % row(s) of the seeded walk-in customer in `customers`. The delete matched no row, '
            'which is what a migration reports when it did nothing at all.', v_placeholder;
    END IF;

    IF v_orphans <> 0 THEN
        RAISE EXCEPTION
            'V105 backfilled a company for every customer it found but % still carry none: the UPDATE read '
            'no row, or a customer was written after it. Nothing below may assume a tenant that is not '
            'there.', v_orphans;
    END IF;

    IF v_mismatch <> 0 THEN
        RAISE EXCEPTION
            'V105 assigned % customer(s) a company their own orders do not name. The backfill and its proof '
            'disagree, which is a defect in this file and not in the data.', v_mismatch;
    END IF;
END $$;

-- ─── 10. tenancy, in the file that makes the table tenant-bearing (ADR-0005) ───────────────────
-- `apply_tenant_isolation` enables and forces row-level security, creates `p_tenant_customers` with the
-- same predicate for reads and writes, and records the table in `tenant_table`. It also re-forces the
-- table, so from here on the owner is filtered by it — which is why every row question above is asked
-- before this statement and not after.
SELECT apply_tenant_isolation('customers');

-- ─── 11. the two windows this file opened and `apply_tenant_isolation` did not close ────────────
ALTER TABLE sales_orders FORCE ROW LEVEL SECURITY;
ALTER TABLE companies FORCE ROW LEVEL SECURITY;

-- ─── 12. the structural read-back: catalog facts, after every window is shut ────────────────────
DO $$
DECLARE
    v_not_null boolean;
    v_customer_nullable boolean;
    v_flags integer;
    v_policies integer;
    v_listed integer;
    v_unique integer;
    v_composite_company bigint;
    v_left_open text;
    v_unprotected text;
BEGIN
    SELECT attnotnull INTO v_not_null
      FROM pg_attribute
     WHERE attrelid = 'customers'::regclass AND attname = 'company_id';

    IF v_not_null IS DISTINCT FROM true THEN
        RAISE EXCEPTION 'V105 left customers.company_id nullable: a tenant column that may be absent is a '
            'row no policy can place anywhere.';
    END IF;

    SELECT attnotnull INTO v_customer_nullable
      FROM pg_attribute
     WHERE attrelid = 'sales_orders'::regclass AND attname = 'customer_id';

    IF v_customer_nullable IS DISTINCT FROM false THEN
        RAISE EXCEPTION 'V105 left sales_orders.customer_id NOT NULL: a counter sale states no receiver '
            '(S2), and this is the column that could not say so.';
    END IF;

    -- ENABLE turns the policy on; FORCE is what stops the owner — the role every migration and the whole
    -- delivery's Flyway runs as — from being exempt from it (ADR decision 3).
    SELECT count(*) INTO v_flags
      FROM pg_class c
     WHERE c.oid = 'customers'::regclass
       AND c.relrowsecurity
       AND c.relforcerowsecurity;

    IF v_flags <> 1 THEN
        RAISE EXCEPTION 'V105 did not leave `customers` with ENABLE and FORCE row-level security: a table '
            'whose owner is exempt from its own policy is the state ADR-0005 exists to remove.';
    END IF;

    SELECT count(*) INTO v_policies
      FROM pg_policies
     WHERE schemaname = 'public'
       AND tablename = 'customers'
       AND policyname = 'p_tenant_customers'
       AND cmd = 'ALL';

    IF v_policies <> 1 THEN
        RAISE EXCEPTION 'V105 expected exactly one `p_tenant_customers` policy on `customers` and read %. '
            '`apply_tenant_isolation` writes that row and the tenant_table registration together, so a '
            'missing one means the call did not run.', v_policies;
    END IF;

    SELECT count(*) INTO v_listed
      FROM tenant_table t
     WHERE t.table_name = 'customers'::regclass
       AND t.policy_kind = 'tenant'
       AND t.key_column = 'company_id';

    IF v_listed <> 1 THEN
        RAISE EXCEPTION 'V105 did not record `customers` in `tenant_table` as tenant/company_id, so '
            '`unprotected_tenant_tables()` would refuse to boot this database.';
    END IF;

    SELECT count(*) INTO v_unique
      FROM pg_constraint con
     WHERE con.contype = 'u'
       AND con.conrelid = 'customers'::regclass
       AND con.conname = 'uq_customers_company_id'
       AND (SELECT array_agg(a.attname::text ORDER BY k.ord)
              FROM unnest(con.conkey) WITH ORDINALITY AS k(attnum, ord)
              JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = k.attnum) = ARRAY['company_id', 'id'];

    IF v_unique <> 1 THEN
        RAISE EXCEPTION 'V105 did not leave `uq_customers_company_id` as UNIQUE (company_id, id): V106''s '
            'composite pointer has no unique key to reference without it.';
    END IF;

    SELECT count(*) INTO v_composite_company
      FROM pg_constraint con
     WHERE con.contype = 'f'
       AND con.conrelid = 'customers'::regclass
       AND con.conname = 'fk_customers_company';

    IF v_composite_company <> 1 THEN
        RAISE EXCEPTION 'V105 did not add `fk_customers_company`: a company_id that names no company is a '
            'tenant that does not exist.';
    END IF;

    -- Every window this file opened is shut. `apply_tenant_isolation` re-forces `customers` itself; the
    -- `sales_orders` one is closed explicitly above.
    SELECT string_agg(c.relname, ', ') INTO v_left_open
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public'
       AND c.relkind = 'r'
       AND c.relname IN ('sales_orders', 'customers', 'companies')
       AND NOT c.relforcerowsecurity;

    IF v_left_open IS NOT NULL THEN
        RAISE EXCEPTION 'V105 opened a NO FORCE window and did not close it on: %. That table is now '
            'readable by its owner without its policy, which is the weakening V55 exists to refuse.',
            v_left_open;
    END IF;

    SELECT string_agg(t::text, ', ') INTO v_unprotected FROM unprotected_tenant_tables() t;

    IF v_unprotected IS NOT NULL THEN
        RAISE EXCEPTION 'V105 left a table carrying company_id with no policy: %. TenantIsolationStartupCheck '
            'refuses to boot on this answer.', v_unprotected;
    END IF;
END $$;
