-- ============================================
-- V68 — The sales order family: its history is seeded, and its state cannot move without a row (ADR-0004 T5)
-- ============================================
-- ADR-0004 decision 5's side A — `assert_document_transition_recorded()` and one `DEFERRABLE INITIALLY
-- DEFERRED` constraint trigger per document table — lands here for the sales family, and so does the seed of
-- one creation row per sales order **and per sales order line** already in the database. `V67` created the
-- table, its append-only pair and decision 5's **side B** (a row cannot claim a state its document does not
-- hold) and said in its header why it created none of the five side-A pairs there: side A refuses **every
-- existing writer** of a document table until that family's writers append their rows, so a pair created
-- before the family moved would break the tree between two commits instead of at the next slice.
-- `SalesOrderService` is that family's writer, and it lands in the same slice.
--
-- WHAT THE THREE THINGS THIS FILE STATES ARE
-- ------------------------------------------
--   1. What it seeds: one `status_transition` row per existing `sales_orders` row and one per existing
--      `sales_order_items` row — `from_status` NULL, `to_status` the document's own state, `occurred_at` its
--      own `created_at`, `actor = 'unknown:migration'`, `reason = 'MIGRATION'`. That is ADR-0004 migration
--      step 6, and it is a record of what the row already said, not a reconstruction of how it got there:
--      inventing transitions would fabricate evidence.
--   2. Why the `NO FORCE` window below covers **`status_transition`** as well as **`sales_orders`**. Two
--      armed tables are in the way of this file's own writes and reads, and neither is optional:
--        * `sales_orders` is armed since `V65` and FORCEd, so the owner — the role Flyway runs as — reads
--          **zero rows** from it (`V54:26`: a statement over such a table matches zero rows and reports
--          success). The seed's `INSERT … SELECT` would number nothing, the "every document has its row"
--          assertion would read nothing, and decision 6's trigger would fail closed on every row it cannot
--          derive a tenant for. `NO FORCE` is the only door a `NOSUPERUSER` owner has (`V57:85`, `V66` step
--          0), and it is what makes this file's own questions real.
--        * `status_transition` is armed since `V67` and FORCEd, and a policy's `WITH CHECK` applies to
--          whoever writes — the owner included. Without the window the seed's `INSERT`s are refused by the
--          policy's own predicate, which is the ordering `V67`'s header named when it put
--          `apply_tenant_isolation` last: "a later seed of this table — T5's, in `V68` — is the write the
--          ordering protects".
--   3. Why `sales_order_items` needs no window: it carries no `company_id`, so it has no policy at all
--      (`unprotected_tenant_tables()` is about tables that **carry** the column). It is also why the seed
--      takes a line's tenant from its parent order and why side A cannot read `NEW.company_id` — see step 1.
--
-- WHY THE WINDOW ALSO HAS TO OUTLIVE THE SEED'S OWN DEFERRED CHECKS
-- ----------------------------------------------------------------
-- `V67`'s `trg_status_transition_matches_state` is `DEFERRABLE INITIALLY DEFERRED`, so the seed's rows are
-- judged **at commit** — and a commit happens after every statement in this file, including the `FORCE`
-- ALTERs that close the window. A trigger that fires there reads `sales_orders` again, and over a FORCEd
-- table it would read no rows, conclude the document carries no state and refuse the migration it is
-- supposed to validate. Step 3 is the statement that keeps the checks where the window is open
-- (`SET CONSTRAINTS ALL IMMEDIATE`), and it is the one piece of this file that is not in the ADR's prose:
-- the ADR's step 7 closes the window and step 6's rows are deferred, and those two facts cannot both be
-- true inside one transaction without it. Documented here, with the invariant it protects: **the seed is
-- validated by the same two functions that will validate every writer after it** (ADR step 5's reason for
-- creating the triggers before the seed), and it is validated while the owner can still see the rows.

-- ============================================
-- 0. The NO FORCE window, opened before the first write and before the first read
-- ============================================
-- Two tables, and the second one is the subject of half of this file: `status_transition` is a tenant
-- table since `V67` (`tenant_table` says `tenant:company_id`) and `sales_orders` since `V65`. The window
-- fails silently by construction — an ALTER that did not take leaves the statements below matching nothing
-- and printing green — so it is checked, the way `V66` step 0 and `V57:85` check theirs.
ALTER TABLE status_transition NO FORCE ROW LEVEL SECURITY;
ALTER TABLE sales_orders     NO FORCE ROW LEVEL SECURITY;

DO $$
DECLARE
    v_still_forced text;
BEGIN
    SELECT string_agg(c.relname, ', ' ORDER BY c.relname) INTO v_still_forced
      FROM pg_class c
     WHERE c.relkind = 'r' AND c.relforcerowsecurity
       AND c.relname IN ('status_transition', 'sales_orders');

    IF v_still_forced IS NOT NULL THEN
        RAISE EXCEPTION
            'V68 could not open its NO FORCE window on %: the seed below would insert nothing, the seed''s own '
            'assertion would read nothing, and both would report success over a history nobody wrote. See '
            'ADR-0004 migration step 0 and ADR-0005 correction 3.', v_still_forced;
    END IF;
END $$;

-- ============================================
-- 1. Decision 5, side A: a state cannot move without its row, and nothing is born without one
-- ============================================
-- Deferred to commit on purpose: the order of the two writes inside the transaction stops mattering, which
-- is what makes this enforceable instead of a convention about call order. `TG_ARGV[0]` is the family, and
-- the family is exactly what `document_type` stores.
--
-- **The tenant is derived, and that is a correction of the ADR's draft body.** ADR-0004's `## Schema` block
-- reads `NEW.company_id`, which is right for the three document tables and impossible here:
-- `sales_order_items` carries **no** `company_id` column, so `NEW.company_id` raises “record NEW has no
-- field company_id” and the trigger would refuse every line write with a message about a column instead of
-- about a missing row. `document_tenant_and_state()` is already the one place a `(document_type,
-- document_id)` pair becomes a tenant and a state (`V67` step 4), so the function asks it, and one body
-- serves a document and a line. It also fails **closed**: a document the resolver cannot see under the
-- caller's policy yields no row, and the refusal says so instead of claiming the row is missing —
-- a wrong tenant is hidden by the policy, not rejected by it (ADR-0004 decision 6).
CREATE FUNCTION assert_document_transition_recorded() RETURNS trigger AS $$
DECLARE
    d record;
BEGIN
    -- A write that leaves the state alone is not this trigger's business, and it is not a row that must be
    -- recorded: a quantity edit on a line writes the row and touches no history.
    IF TG_OP = 'UPDATE' AND NEW.status IS NOT DISTINCT FROM OLD.status THEN
        RETURN NULL;
    END IF;

    SELECT * INTO d FROM document_tenant_and_state(TG_ARGV[0], NEW.id);
    IF d.company_id IS NULL THEN
        RAISE EXCEPTION
            '% % is not visible to this writer, so its history cannot be checked: a document of another '
            'tenant is hidden by the policy instead of refused (ADR-0004 decision 6)', TG_ARGV[0], NEW.id;
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF NOT EXISTS (
            SELECT 1 FROM status_transition t
            WHERE t.document_type = TG_ARGV[0] AND t.document_id = NEW.id
              AND t.company_id = d.company_id
              AND t.from_status IS NULL AND t.to_status = NEW.status)
        THEN
            RAISE EXCEPTION '% % was created without its status_transition row', TG_ARGV[0], NEW.id;
        END IF;
    ELSIF NEW.status IS DISTINCT FROM OLD.status THEN
        IF NOT EXISTS (
            SELECT 1 FROM status_transition t
            WHERE t.document_type = TG_ARGV[0] AND t.document_id = NEW.id
              AND t.company_id = d.company_id
              AND t.from_status = OLD.status AND t.to_status = NEW.status)
        THEN
            RAISE EXCEPTION '% % moved % -> % without its status_transition row',
                TG_ARGV[0], NEW.id, OLD.status, NEW.status;
        END IF;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION assert_document_transition_recorded() IS
    'ADR-0004 decision 5, side A: refuses a commit in which a document was created without its creation row, '
    'or moved without the row of that move. Deferred, so the order of the two writes inside the transaction '
    'stops mattering. The tenant comes from document_tenant_and_state() and not from NEW.company_id, because '
    'sales_order_items has no company_id column.';

-- The pair for the sales family, and only for it: the other three tables' pairs belong to T6 and T7, which
-- move their own writers in the same slice (V69, V70). The five-table assertion that every document table
-- carries its pair belongs with the fifth pair, for the reason `V67`'s header gives.
CREATE CONSTRAINT TRIGGER trg_sales_orders_transition_recorded
    AFTER INSERT OR UPDATE ON sales_orders
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_document_transition_recorded('SALES_ORDER');

CREATE CONSTRAINT TRIGGER trg_sales_order_items_transition_recorded
    AFTER INSERT OR UPDATE ON sales_order_items
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_document_transition_recorded('SALES_ORDER_ITEM');

-- ============================================
-- 2. The seed: one creation row per existing document and per existing line
-- ============================================
-- ADR-0004 migration step 6. `from_status` is NULL because the history before this migration is one initial
-- row and not a reconstruction; `occurred_at` is the document's own `created_at` and not `now()`, or every
-- document in the database would claim to have been born in the same instant; `to_status` is the state the
-- row already carries, which `V66` backfilled from the catalog and the bridge keeps coherent.
--
-- The actor is a literal because the column is `NOT NULL` and a backfill is not allowed to invent a person.
-- `'unknown:migration'` is the ADR's own literal for history the migration did not observe, and
-- `StatusTransitionRecorder` refuses to write it on a live request for the same reason.
--
-- The `NOT EXISTS` guard is not decoration: `V67` and this file are two migrations, so a database that
-- served the application in between may already hold a creation row for a document — written by the
-- recorder, through the trigger, in the shape this file would otherwise duplicate. `uq_status_transition_creation`
-- allows exactly one per document, so the seed takes the documents that have none and leaves the rest alone.
INSERT INTO status_transition
    (company_id, document_type, document_id, from_status, to_status, actor, occurred_at, reason)
SELECT so.company_id, 'SALES_ORDER', so.id, NULL, so.status, 'unknown:migration', so.created_at, 'MIGRATION'
  FROM sales_orders so
 WHERE NOT EXISTS (
    SELECT 1 FROM status_transition t
    WHERE t.document_type = 'SALES_ORDER' AND t.document_id = so.id AND t.from_status IS NULL);

-- The lines: `sales_order_items` carries no `company_id`, so the tenant is the parent order's, joined here
-- and never guessed. Decision 6's `BEFORE INSERT` trigger checks that derivation row by row — it resolves
-- `('SALES_ORDER_ITEM', id)` through the same join and refuses a `company_id` that does not match — so a
-- line seeded under the wrong tenant cannot be inserted, only refused.
INSERT INTO status_transition
    (company_id, document_type, document_id, from_status, to_status, actor, occurred_at, reason)
SELECT o.company_id, 'SALES_ORDER_ITEM', i.id, NULL, i.status, 'unknown:migration', i.created_at, 'MIGRATION'
  FROM sales_order_items i
  JOIN sales_orders o ON o.id = i.sales_order_id
 WHERE NOT EXISTS (
    SELECT 1 FROM status_transition t
    WHERE t.document_type = 'SALES_ORDER_ITEM' AND t.document_id = i.id AND t.from_status IS NULL);

-- ============================================
-- 3. The seed's deferred checks run while the window is still open
-- ============================================
-- Read the header before changing the position of this statement. `V67`'s side B trigger is
-- `DEFERRABLE INITIALLY DEFERRED`, so it judges the seed's rows at COMMIT — after the `FORCE` ALTERs of step
-- 5 have put the policy back in front of the owner's own reads. There it would read no document, conclude
-- that the latest row names a state nothing holds, and refuse the migration over rows that are correct.
-- `SET CONSTRAINTS ALL IMMEDIATE` executes the pending checks **now**, inside the window, so the seed is
-- validated by the same two functions that validate every writer after it — which is ADR-0004 step 5's
-- reason for creating both pairs before the seed — and not by a version of them that cannot see the rows.
--
-- `ALL` rather than a name: the only deferrable constraints in this schema are these constraint triggers,
-- and naming them one by one would be a list that a later migration has to remember to extend.
SET CONSTRAINTS ALL IMMEDIATE;

-- ============================================
-- 4. Did the seed reach every row?
-- ============================================
-- Asked **before** step 5's `FORCE` ALTERs, and the order is the whole point: over a forced table this query
-- matches no row and reports the same green as a seed that reached every one. `V66` step 6's header records
-- the defect that order avoids — `V64:265-266` forces two tables and asks its row question at `:268-304`,
-- so its second self-check is vacuous — and this file is not the place to repeat it.
--
-- "Exactly one" and not "at least one": the unique index on the creation row makes two impossible, so the
-- question is asked as a count that would look identical if the seed inserted twice as many rows or none.
DO $$
DECLARE
    v_documents bigint;
    v_lines bigint;
    v_missing_documents bigint;
    v_missing_lines bigint;
    v_creation_rows bigint;
BEGIN
    SELECT count(*) INTO v_documents FROM sales_orders;
    SELECT count(*) INTO v_lines FROM sales_order_items;

    SELECT count(*) INTO v_creation_rows FROM status_transition WHERE from_status IS NULL;

    SELECT count(*) INTO v_missing_documents
      FROM sales_orders so
     WHERE NOT EXISTS (
        SELECT 1 FROM status_transition t
        WHERE t.document_type = 'SALES_ORDER' AND t.document_id = so.id AND t.from_status IS NULL);

    SELECT count(*) INTO v_missing_lines
      FROM sales_order_items i
     WHERE NOT EXISTS (
        SELECT 1 FROM status_transition t
        WHERE t.document_type = 'SALES_ORDER_ITEM' AND t.document_id = i.id AND t.from_status IS NULL);

    IF v_missing_documents > 0 OR v_missing_lines > 0 THEN
        RAISE EXCEPTION
            'V68 seeded % creation row(s) for % sales order(s) and % line(s): % document(s) and % line(s) are '
            'left without one. A seed that reached nothing and a seed that reached everything both report '
            'success, and only this count separates them. See ADR-0004 migration step 6.',
            v_creation_rows, v_documents, v_lines, v_missing_documents, v_missing_lines;
    END IF;

    IF v_creation_rows <> v_documents + v_lines THEN
        RAISE EXCEPTION
            'V68 left % creation row(s) for % sales order(s) and % line(s): the difference is a row for a '
            'document that does not exist. The seed writes one row per document and none for anything else.',
            v_creation_rows, v_documents, v_lines;
    END IF;
END $$;

-- ============================================
-- 5. The window closes, and the close is checked
-- ============================================
-- A window left open is invisible in every other assertion this file makes, and it weakens the table for
-- everything that runs after it (`V55:100`, `V66` step 6). Both tables, including `status_transition`: the
-- policy was only stepped out of the way so this file could seed history, not because the table is any less
-- of a tenant table.
ALTER TABLE status_transition FORCE ROW LEVEL SECURITY;
ALTER TABLE sales_orders     FORCE ROW LEVEL SECURITY;

DO $$
DECLARE
    v_not_forced text;
BEGIN
    SELECT string_agg(c.relname, ', ' ORDER BY c.relname) INTO v_not_forced
      FROM pg_class c
     WHERE c.relkind = 'r' AND NOT c.relforcerowsecurity
       AND c.relname IN ('status_transition', 'sales_orders');

    IF v_not_forced IS NOT NULL THEN
        RAISE EXCEPTION
            'V68 left % without FORCE ROW LEVEL SECURITY: a table whose owner is exempt from its own policy is '
            'the state ADR-0005 exists to remove, and a window left open weakens the table for everything that '
            'runs after it (V55).', v_not_forced;
    END IF;
END $$;
