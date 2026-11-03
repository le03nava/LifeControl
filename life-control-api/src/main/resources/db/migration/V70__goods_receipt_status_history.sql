-- ============================================
-- V70 — The goods receipt family: its history is seeded, its state cannot move without a row, and the five
--       tables are finally asserted together (ADR-0004 T7)
-- ============================================
-- This is the last pair of ADR-0004 decision 5's side A. `assert_document_transition_recorded()` was created by
-- `V68` for the sales family and reused by `V69` for the purchase family; it is **not** redefined here, and the
-- reason is the one `V69`'s header gives: a second copy would be a second validator, and two validators whose
-- halves can disagree are exactly what decision 5 is not. What this file adds is the fifth `DEFERRABLE INITIALLY
-- DEFERRED` trigger, the seed of one creation row per goods receipt already in the database, and — because
-- `goods_receipts` is the last of the five — the ADR's own assertion that **every** document table carries its
-- pair.
--
-- WHAT THE THREE THINGS THIS FILE STATES ARE
-- ------------------------------------------
--   1. What it seeds: one `status_transition` row per existing `goods_receipts` row — `from_status` NULL,
--      `to_status` the receipt's own state, `occurred_at` its own `created_at`, `actor = 'unknown:migration'`,
--      `reason = 'MIGRATION'`. ADR-0004 migration step 6, and it is a record of what the row already said, not a
--      reconstruction of how it got there: inventing transitions would fabricate evidence.
--   2. Why there is **one** creation row per receipt and never a second one to seed. `GoodsReceiptState` is a
--      single terminal constant (`REGISTERED`) and stays that way: a receipt is written by the receipt flow and
--      never moved, so its creation row **is** its whole history. `V66`'s `CHECK` admits no other value and this
--      file seeds no other value — a second state here would be a lifecycle the product never asked for.
--   3. Why the `NO FORCE` window below covers **`status_transition`** as well as **`goods_receipts`**. Two armed
--      tables are in the way of this file's own writes and reads, and neither is optional:
--        * `goods_receipts` is armed since `V65` and FORCEd, so the owner — the role Flyway runs as — reads
--          **zero rows** from it (`V54:26`: a statement over such a table matches zero rows and reports
--          success). The seed's `INSERT … SELECT` would number nothing, the "every receipt has its row"
--          assertion would read nothing, and decision 6's `BEFORE INSERT` trigger on `status_transition` would
--          fail closed on every row it cannot derive a tenant for. `NO FORCE` is the only door a `NOSUPERUSER`
--          owner has (`V57:85`, `V66` step 0, `V68` step 0, `V69` step 0), and it is what makes this file's own
--          questions real.
--        * `status_transition` is armed since `V67` and FORCEd by `V68`, and a policy's `WITH CHECK` applies to
--          whoever writes — the owner included. Without the window the seed's `INSERT`s are refused by the
--          policy's own predicate.
--
-- WHY THE WINDOW ALSO HAS TO OUTLIVE THE SEED'S OWN DEFERRED CHECKS
-- ----------------------------------------------------------------
-- `V67`'s `trg_status_transition_matches_state` and the trigger created below are `DEFERRABLE INITIALLY
-- DEFERRED`, so the seed's rows are judged **at commit** — and a commit happens after every statement in this
-- file, including the `FORCE` ALTERs that close the window. A trigger that fires there reads `goods_receipts`
-- again, and over a FORCEd table it would read no rows, conclude the receipt carries no state and refuse the
-- migration it is supposed to validate. `SET CONSTRAINTS ALL IMMEDIATE` is the statement that keeps the checks
-- where the window is open (`V68` states the same reasoning at length, `V69` step 3 applies it), and with it
-- **the seed is validated by the same two functions that will validate every writer after it** (ADR step 5's
-- reason for creating the triggers before the seed), while the owner can still see the rows.

-- ============================================
-- 0. The NO FORCE window, opened before the first write and before the first read
-- ============================================
-- Two tables: `status_transition` is a tenant table since `V67` (`tenant_table` says `tenant:company_id`) and
-- `goods_receipts` since `V65`. The window fails silently by construction — an ALTER that did not take leaves
-- the statements below matching nothing and printing green — so it is checked, the way `V66` step 0 and
-- `V57:85` check theirs.
ALTER TABLE status_transition NO FORCE ROW LEVEL SECURITY;
ALTER TABLE goods_receipts   NO FORCE ROW LEVEL SECURITY;

DO $$
DECLARE
    v_still_forced text;
BEGIN
    SELECT string_agg(c.relname, ', ' ORDER BY c.relname) INTO v_still_forced
      FROM pg_class c
     WHERE c.relkind = 'r' AND c.relforcerowsecurity
       AND c.relname IN ('status_transition', 'goods_receipts');

    IF v_still_forced IS NOT NULL THEN
        RAISE EXCEPTION
            'V70 could not open its NO FORCE window on %: the seed below would insert nothing, the seed''s own '
            'assertion would read nothing, and both would report success over a history nobody wrote. See '
            'ADR-0004 migration step 0 and ADR-0005 correction 3.', v_still_forced;
    END IF;
END $$;

-- ============================================
-- 1. Decision 5, side A, for this family: a receipt cannot be born without its row
-- ============================================
-- The function already exists (`V68` step 1) and is deliberately not redefined. The pair is created **before**
-- the seed so the same function validates the migration's own rows: a trigger created after the seed would
-- leave the one write in this file that nobody checked.
--
-- `AFTER INSERT OR UPDATE` and not `AFTER INSERT`: the receipt table keeps the UPDATE arm for the same reason
-- the other four do — a state that moves with no row must be refused — even though `GoodsReceiptState` declares
-- no successor today. The arm costs nothing while the machine has one state, and leaving it out would mean a
-- future second state lands with a trigger that silently does not check it.
CREATE CONSTRAINT TRIGGER trg_goods_receipts_transition_recorded
    AFTER INSERT OR UPDATE ON goods_receipts
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_document_transition_recorded('GOODS_RECEIPT');

-- ============================================
-- 2. The seed: one creation row per existing receipt
-- ============================================
-- ADR-0004 migration step 6. `from_status` is NULL because the history before this migration is one initial row
-- and not a reconstruction; `occurred_at` is the receipt's own `created_at` and not `now()`, or every receipt in
-- the database would claim to have been born in the same instant; `to_status` is the state the row already
-- carries, which `V66` backfilled from the catalog and the bridge keeps coherent.
--
-- The actor is a literal because the column is `NOT NULL` and a backfill is not allowed to invent a person.
-- `'unknown:migration'` is the ADR's own literal for history the migration did not observe, and
-- `StatusTransitionRecorder` refuses to write it on a live request for the same reason.
--
-- The `NOT EXISTS` guard is not decoration: `V69` and this file are two migrations, so a database that served
-- the application in between may already hold a creation row for a receipt — written by the recorder, through
-- the trigger, in the shape this file would otherwise duplicate. `uq_status_transition_creation` allows exactly
-- one per document, so the seed takes the receipts that have none and leaves the rest alone.
INSERT INTO status_transition
    (company_id, document_type, document_id, from_status, to_status, actor, occurred_at, reason)
SELECT gr.company_id, 'GOODS_RECEIPT', gr.id, NULL, gr.status, 'unknown:migration', gr.created_at, 'MIGRATION'
  FROM goods_receipts gr
 WHERE NOT EXISTS (
    SELECT 1 FROM status_transition t
    WHERE t.document_type = 'GOODS_RECEIPT' AND t.document_id = gr.id AND t.from_status IS NULL);

-- ============================================
-- 3. The seed's deferred checks run while the window is still open
-- ============================================
-- Read `V68` step 3 before moving this statement. `V67`'s side B trigger and the trigger created in step 1 are
-- `DEFERRABLE INITIALLY DEFERRED`, so they judge the seed's rows at COMMIT — after the `FORCE` ALTERs of step 6
-- have put the policy back in front of the owner's own reads. There they would read no receipt, conclude that
-- the latest row names a state nothing holds, and refuse the migration over rows that are correct.
-- `SET CONSTRAINTS ALL IMMEDIATE` executes the pending checks **now**, inside the window.
--
-- `ALL` rather than a name: the only deferrable constraints in this schema are these constraint triggers, and
-- naming them one by one would be a list that a later migration has to remember to extend.
SET CONSTRAINTS ALL IMMEDIATE;

-- ============================================
-- 4. The assertion: every document table of the five carries its deferred pair
-- ============================================
-- This is ADR-0004's own block from `## Schema`, run once and here, because `goods_receipts` is the **last** of
-- the five pairs: before this file the block could not pass, and a block that was written early and never run
-- would be the same comment the ADR refuses ("'the same pair for the other four' cannot stay a comment",
-- migration step 5). It is the shape ADR-0005 uses for its policy list — a `DO` that names what is missing — and
-- it is the only statement in the delivery that would catch a family whose pair was forgotten.
--
-- It asks about `pg_trigger` and not about the files: a pair created in one migration and dropped in another, a
-- trigger that lost `DEFERRABLE`, or a family that was never given one all leave four green migrations and a
-- table whose state can move with no history. A trigger that points at some other function is not this pair
-- either, so the validator is part of the question.
DO $$
DECLARE
    v_missing text;
BEGIN
    SELECT string_agg(t, ', ' ORDER BY t) INTO v_missing
      FROM unnest(ARRAY['sales_orders', 'sales_order_items', 'purchase_orders',
                        'purchase_order_details', 'goods_receipts']) AS t
     WHERE NOT EXISTS (
        SELECT 1 FROM pg_trigger g
        WHERE g.tgrelid = t::regclass
          AND g.tgdeferrable AND NOT g.tgisinternal
          AND g.tgfoid = 'assert_document_transition_recorded'::regproc);

    IF v_missing IS NOT NULL THEN
        RAISE EXCEPTION
            'document table(s) without the deferred transition trigger: % (ADR-0004 decision 5, side A). A '
            'document table without its pair is a table whose state can move with no history, and its writer '
            'would commit that move.', v_missing;
    END IF;
END $$;

-- ============================================
-- 5. Did the seed reach every row?
-- ============================================
-- Asked **before** step 6's `FORCE` ALTERs, and the order is the whole point: over a forced table this query
-- matches no row and reports the same green as a seed that reached every one. `V66` step 6's header records the
-- defect that order avoids — `V64:265-266` forces two tables and asks its row question at `:268-304`, so its
-- second self-check is vacuous — and this file is not the place to repeat it.
--
-- "Exactly one" and not "at least one": the unique index on the creation row makes two impossible, so the
-- question is asked as a count that would look identical if the seed inserted twice as many rows or none.
DO $$
DECLARE
    v_documents bigint;
    v_missing bigint;
    v_creation_rows bigint;
BEGIN
    SELECT count(*) INTO v_documents FROM goods_receipts;

    SELECT count(*) INTO v_creation_rows
      FROM status_transition
     WHERE from_status IS NULL AND document_type = 'GOODS_RECEIPT';

    SELECT count(*) INTO v_missing
      FROM goods_receipts gr
     WHERE NOT EXISTS (
        SELECT 1 FROM status_transition t
        WHERE t.document_type = 'GOODS_RECEIPT' AND t.document_id = gr.id AND t.from_status IS NULL);

    IF v_missing > 0 THEN
        RAISE EXCEPTION
            'V70 seeded % creation row(s) for % goods receipt(s): % receipt(s) are left without one. A seed that '
            'reached nothing and a seed that reached everything both report success, and only this count '
            'separates them. See ADR-0004 migration step 6.',
            v_creation_rows, v_documents, v_missing;
    END IF;

    IF v_creation_rows <> v_documents THEN
        RAISE EXCEPTION
            'V70 left % creation row(s) for % goods receipt(s): the difference is a row for a document that does '
            'not exist. The seed writes one row per receipt and none for anything else.',
            v_creation_rows, v_documents;
    END IF;
END $$;

-- ============================================
-- 6. The window closes, and the close is checked
-- ============================================
-- A window left open is invisible in every other assertion this file makes, and it weakens the table for
-- everything that runs after it (`V55:100`, `V66` step 6). Both tables, including `status_transition`: the
-- policy was only stepped out of the way so this file could seed history, not because the table is any less of
-- a tenant table.
ALTER TABLE status_transition FORCE ROW LEVEL SECURITY;
ALTER TABLE goods_receipts   FORCE ROW LEVEL SECURITY;

DO $$
DECLARE
    v_not_forced text;
BEGIN
    SELECT string_agg(c.relname, ', ' ORDER BY c.relname) INTO v_not_forced
      FROM pg_class c
     WHERE c.relkind = 'r' AND NOT c.relforcerowsecurity
       AND c.relname IN ('status_transition', 'goods_receipts');

    IF v_not_forced IS NOT NULL THEN
        RAISE EXCEPTION
            'V70 left % without FORCE ROW LEVEL SECURITY: a table whose owner is exempt from its own policy is '
            'the state ADR-0005 exists to remove, and a window left open weakens the table for everything that '
            'runs after it (V55).', v_not_forced;
    END IF;
END $$;
