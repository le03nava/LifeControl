-- ============================================
-- V69 — The purchase order family: its history is seeded, and its state cannot move without a row (ADR-0004 T6)
-- ============================================
-- ADR-0004 decision 5's side A — `assert_document_transition_recorded()` and one `DEFERRABLE INITIALLY
-- DEFERRED` constraint trigger per document table — lands here for the purchase family, and so does the seed of
-- one creation row per purchase order **and per purchase order line** already in the database. The function
-- itself is not created here and must not be: `V68` created it for the sales family, with the tenant derived
-- from `document_tenant_and_state()` because `sales_order_items` carries no `company_id`, and one body serves
-- both families through its `TG_ARGV[0]`. A second copy of it in this file would be a second validator whose
-- two halves could disagree, which is exactly what decision 5 is not.
--
-- WHAT THE THREE THINGS THIS FILE STATES ARE
-- ------------------------------------------
--   1. What it seeds: one `status_transition` row per existing `purchase_orders` row and one per existing
--      `purchase_order_details` row — `from_status` NULL, `to_status` the document's own state, `occurred_at`
--      its own `created_at`, `actor = 'unknown:migration'`, `reason = 'MIGRATION'`. That is ADR-0004 migration
--      step 6, and it is a record of what the row already said, not a reconstruction of how it got there:
--      inventing transitions would fabricate evidence.
--   2. Why the `NO FORCE` window below covers **`status_transition`** as well as **`purchase_orders`**. Two
--      armed tables are in the way of this file's own writes and reads, and neither is optional:
--        * `purchase_orders` is armed since `V53` and FORCEd, so the owner — the role Flyway runs as — reads
--          **zero rows** from it (`V54:26`: a statement over such a table matches zero rows and reports
--          success). The seed's `INSERT … SELECT` would number nothing, the "every document has its row"
--          assertion would read nothing, and decision 6's `BEFORE INSERT` trigger on `status_transition` would
--          fail closed on every row it cannot derive a tenant for. `NO FORCE` is the only door a `NOSUPERUSER`
--          owner has (`V57:85`, `V66` step 0, `V68` step 0), and it is what makes this file's own questions
--          real.
--        * `status_transition` is armed since `V67` and FORCEd by `V68`, and a policy's `WITH CHECK` applies to
--          whoever writes — the owner included. Without the window the seed's `INSERT`s are refused by the
--          policy's own predicate.
--   3. Why `purchase_order_details` needs no window: it carries no `company_id`, so it has no policy at all
--      (`unprotected_tenant_tables()` is about tables that **carry** the column). It is also why the seed takes
--      a line's tenant from its parent order and why side A cannot read `NEW.company_id`.
--
-- WHY THE WINDOW ALSO HAS TO OUTLIVE THE SEED'S OWN DEFERRED CHECKS
-- ----------------------------------------------------------------
-- `V67`'s `trg_status_transition_matches_state` and the pair created below are `DEFERRABLE INITIALLY
-- DEFERRED`, so the seed's rows are judged **at commit** — and a commit happens after every statement in this
-- file, including the `FORCE` ALTERs that close the window. A trigger that fires there reads
-- `purchase_orders` again, and over a FORCEd table it would read no rows, conclude the document carries no
-- state and refuse the migration it is supposed to validate. The `SET CONSTRAINTS ALL IMMEDIATE` of step 3 is
-- the statement that keeps the checks where the window is open (`V68` states the same reasoning at length),
-- and with it **the seed is validated by the same two functions that will validate every writer after it**
-- (ADR step 5's reason for creating the triggers before the seed), while the owner can still see the rows.

-- ============================================
-- 0. The NO FORCE window, opened before the first write and before the first read
-- ============================================
-- Two tables: `status_transition` is a tenant table since `V67` (`tenant_table` says `tenant:company_id`) and
-- `purchase_orders` since `V53`. The window fails silently by construction — an ALTER that did not take
-- leaves the statements below matching nothing and printing green — so it is checked, the way `V66` step 0 and
-- `V57:85` check theirs.
ALTER TABLE status_transition NO FORCE ROW LEVEL SECURITY;
ALTER TABLE purchase_orders  NO FORCE ROW LEVEL SECURITY;

DO $$
DECLARE
    v_still_forced text;
BEGIN
    SELECT string_agg(c.relname, ', ' ORDER BY c.relname) INTO v_still_forced
      FROM pg_class c
     WHERE c.relkind = 'r' AND c.relforcerowsecurity
       AND c.relname IN ('status_transition', 'purchase_orders');

    IF v_still_forced IS NOT NULL THEN
        RAISE EXCEPTION
            'V69 could not open its NO FORCE window on %: the seed below would insert nothing, the seed''s own '
            'assertion would read nothing, and both would report success over a history nobody wrote. See '
            'ADR-0004 migration step 0 and ADR-0005 correction 3.', v_still_forced;
    END IF;
END $$;

-- ============================================
-- 1. Decision 5, side A, for this family: a state cannot move without its row
-- ============================================
-- The function already exists (`V68` step 1) and is deliberately not redefined. Each family gets its own pair
-- of deferred triggers, created **before** the seed so the same function validates the migration's own rows:
-- a trigger created after the seed would leave the one write in this file that nobody checked.
CREATE CONSTRAINT TRIGGER trg_purchase_orders_transition_recorded
    AFTER INSERT OR UPDATE ON purchase_orders
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_document_transition_recorded('PURCHASE_ORDER');

CREATE CONSTRAINT TRIGGER trg_purchase_order_details_transition_recorded
    AFTER INSERT OR UPDATE ON purchase_order_details
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_document_transition_recorded('PURCHASE_ORDER_DETAIL');

-- The pair above is this family's whole contribution to decision 5. `goods_receipts` is **not** here: its pair
-- belongs to T7 and lands with the writer that appends for it (`V70`), for the reason `V67`'s header gives —
-- side A refuses every existing writer of a document table until that family's writers append their rows, so a
-- pair created before the family moved would break the tree between two commits. The five-table assertion that
-- every document table carries its pair belongs with the fifth pair, also T7.

-- ============================================
-- 2. The seed: one creation row per existing document and per existing line
-- ============================================
-- ADR-0004 migration step 6. `from_status` is NULL because the history before this migration is one initial row
-- and not a reconstruction; `occurred_at` is the document's own `created_at` and not `now()`, or every document
-- in the database would claim to have been born in the same instant; `to_status` is the state the row already
-- carries, which `V66` backfilled from the catalog and the bridge keeps coherent.
--
-- The actor is a literal because the column is `NOT NULL` and a backfill is not allowed to invent a person.
-- `'unknown:migration'` is the ADR's own literal for history the migration did not observe, and
-- `StatusTransitionRecorder` refuses to write it on a live request for the same reason.
--
-- The `NOT EXISTS` guard is not decoration: `V68` and this file are two migrations, so a database that served
-- the application in between may already hold a creation row for a document — written by the recorder, through
-- the trigger, in the shape this file would otherwise duplicate. `uq_status_transition_creation` allows exactly
-- one per document, so the seed takes the documents that have none and leaves the rest alone.
INSERT INTO status_transition
    (company_id, document_type, document_id, from_status, to_status, actor, occurred_at, reason)
SELECT po.company_id, 'PURCHASE_ORDER', po.id, NULL, po.status, 'unknown:migration', po.created_at, 'MIGRATION'
  FROM purchase_orders po
 WHERE NOT EXISTS (
    SELECT 1 FROM status_transition t
    WHERE t.document_type = 'PURCHASE_ORDER' AND t.document_id = po.id AND t.from_status IS NULL);

-- The lines: `purchase_order_details` carries no `company_id`, so the tenant is the parent order's, joined here
-- and never guessed. Decision 6's `BEFORE INSERT` trigger checks that derivation row by row — it resolves
-- `('PURCHASE_ORDER_DETAIL', id)` through the same join and refuses a `company_id` that does not match — so a
-- line seeded under the wrong tenant cannot be inserted, only refused.
INSERT INTO status_transition
    (company_id, document_type, document_id, from_status, to_status, actor, occurred_at, reason)
SELECT po.company_id, 'PURCHASE_ORDER_DETAIL', d.id, NULL, d.status, 'unknown:migration', d.created_at, 'MIGRATION'
  FROM purchase_order_details d
  JOIN purchase_orders po ON po.id = d.purchase_order_id
 WHERE NOT EXISTS (
    SELECT 1 FROM status_transition t
    WHERE t.document_type = 'PURCHASE_ORDER_DETAIL' AND t.document_id = d.id AND t.from_status IS NULL);

-- ============================================
-- 3. The seed's deferred checks run while the window is still open
-- ============================================
-- Read `V68` step 3 before moving this statement. `V67`'s side B trigger and the pair created in step 1 are
-- `DEFERRABLE INITIALLY DEFERRED`, so they judge the seed's rows at COMMIT — after the `FORCE` ALTERs of step 5
-- have put the policy back in front of the owner's own reads. There they would read no document, conclude that
-- the latest row names a state nothing holds, and refuse the migration over rows that are correct.
-- `SET CONSTRAINTS ALL IMMEDIATE` executes the pending checks **now**, inside the window.
--
-- `ALL` rather than a name: the only deferrable constraints in this schema are these constraint triggers, and
-- naming them one by one would be a list that a later migration has to remember to extend.
SET CONSTRAINTS ALL IMMEDIATE;

-- ============================================
-- 4. Did the seed reach every row?
-- ============================================
-- Asked **before** step 5's `FORCE` ALTERs, and the order is the whole point: over a forced table this query
-- matches no row and reports the same green as a seed that reached every one. `V66` step 6's header records the
-- defect that order avoids — `V64:265-266` forces two tables and asks its row question at `:268-304`, so its
-- second self-check is vacuous — and this file is not the place to repeat it.
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
    SELECT count(*) INTO v_documents FROM purchase_orders;
    SELECT count(*) INTO v_lines FROM purchase_order_details;

    SELECT count(*) INTO v_creation_rows
      FROM status_transition
     WHERE from_status IS NULL
       AND document_type IN ('PURCHASE_ORDER', 'PURCHASE_ORDER_DETAIL');

    SELECT count(*) INTO v_missing_documents
      FROM purchase_orders po
     WHERE NOT EXISTS (
        SELECT 1 FROM status_transition t
        WHERE t.document_type = 'PURCHASE_ORDER' AND t.document_id = po.id AND t.from_status IS NULL);

    SELECT count(*) INTO v_missing_lines
      FROM purchase_order_details d
     WHERE NOT EXISTS (
        SELECT 1 FROM status_transition t
        WHERE t.document_type = 'PURCHASE_ORDER_DETAIL' AND t.document_id = d.id AND t.from_status IS NULL);

    IF v_missing_documents > 0 OR v_missing_lines > 0 THEN
        RAISE EXCEPTION
            'V69 seeded % creation row(s) for % purchase order(s) and % line(s): % document(s) and % line(s) are '
            'left without one. A seed that reached nothing and a seed that reached everything both report '
            'success, and only this count separates them. See ADR-0004 migration step 6.',
            v_creation_rows, v_documents, v_lines, v_missing_documents, v_missing_lines;
    END IF;

    IF v_creation_rows <> v_documents + v_lines THEN
        RAISE EXCEPTION
            'V69 left % creation row(s) for % purchase order(s) and % line(s): the difference is a row for a '
            'document that does not exist. The seed writes one row per document and none for anything else.',
            v_creation_rows, v_documents, v_lines;
    END IF;
END $$;

-- ============================================
-- 5. The window closes, and the close is checked
-- ============================================
-- A window left open is invisible in every other assertion this file makes, and it weakens the table for
-- everything that runs after it (`V55:100`, `V66` step 6). Both tables, including `status_transition`: the
-- policy was only stepped out of the way so this file could seed history, not because the table is any less of
-- a tenant table.
ALTER TABLE status_transition FORCE ROW LEVEL SECURITY;
ALTER TABLE purchase_orders  FORCE ROW LEVEL SECURITY;

DO $$
DECLARE
    v_not_forced text;
BEGIN
    SELECT string_agg(c.relname, ', ' ORDER BY c.relname) INTO v_not_forced
      FROM pg_class c
     WHERE c.relkind = 'r' AND NOT c.relforcerowsecurity
       AND c.relname IN ('status_transition', 'purchase_orders');

    IF v_not_forced IS NOT NULL THEN
        RAISE EXCEPTION
            'V69 left % without FORCE ROW LEVEL SECURITY: a table whose owner is exempt from its own policy is '
            'the state ADR-0005 exists to remove, and a window left open weakens the table for everything that '
            'runs after it (V55).', v_not_forced;
    END IF;
END $$;
