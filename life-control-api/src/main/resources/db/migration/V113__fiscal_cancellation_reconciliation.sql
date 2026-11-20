-- ============================================
-- V113 — the fourth state: a submitted cancellation is remembered, and the row says what the authority answered
--        (S2 and S3 of `odd/tasks/fiscal-cancellation-reconciliation.md`; T1, decisions L2(a)–L2(e) of the same
--        record)
-- ============================================
-- WHY A FOURTH STATE AND NOT `CANCELLED`. `V108` closes the vocabulary of `fiscal_document.status` at three values
-- and states the argument for a closed set: a state no trigger knows how to guard is a row the state machine cannot
-- describe. `L20(d)` of `odd/tasks/cfdi-fiscal-document.md` then wrote the consequence into the cancellation service
-- — a `211` from the authority writes **nothing**, because «a submitted request is not a cancelled document and
-- `ck_fiscal_document_status` has no state for it» — and the consequence of *that* is `S1` of this record: a
-- submitted cancellation was forgotten until a human asked the PAC again. The fourth state is the missing word, not
-- a relaxation: `CANCELLATION_PENDING` is the only state that says "the authority has this request and has not
-- decided", and it is deliberately **not** `CANCELLED` because a request the receiver has not answered is not a
-- document the SAT has cancelled.
--
-- WHY THE MIGRATION IS THE WHOLE UNIT OF `T1`. Four facts are one fact and cannot land in two commits: the
-- vocabulary, the two constraints coupled to it by equality (`ck_fiscal_document_cancellation` names the status, and
-- `ck_fiscal_document_uuid_matches_status` / `ck_fiscal_document_stamped_at` are equalities over it), the columns
-- that will hold the answer, and the trigger whose default verdict is "mutable". A vocabulary admitting
-- `CANCELLATION_PENDING` under `V108`'s guard would be a row that could be edited into anything.
--
-- WHAT THE ROW SHAPE IS, AND WHY (L2(a)–L2(d)):
--   (a) `CANCELLATION_PENDING` is twenty characters, exactly the width `fiscal_document.status` declares
--       (`VARCHAR(20)`) — the longest value the vocabulary can hold without retyping the column.
--   (b) the pending row **is** the cancellation row in waiting: it carries `cancels_fiscal_document_id`,
--       `cancellation_motive` and `replaces_fiscal_document_id`, and the constraint below admits
--       `status IN ('CANCELLED', 'CANCELLATION_PENDING')` with its logic otherwise unchanged. Those are the three
--       facts a second submission needs, and `uq_fiscal_document_cancels` — already unique on the pointer — is what
--       makes the second cancellation impossible **at the schema level** rather than in a service that could skip it.
--   (c) it carries **no** `uuid_fiscal` and **no** `stamped_at`: `ck_fiscal_document_uuid_matches_status` and
--       `ck_fiscal_document_stamped_at` are equalities and stay exactly as `V108` states them. The UUID the
--       reconciliation asks the authority about is the pointed document's, reached through the composite foreign key
--       — a pending row with a UUID of its own would be a row claiming the authority witnessed **it**.
--   (d) three columns record the answer: `cancellation_status_uuid`, `cancellation_message` and
--       `cancellation_settled_at`. They are legal only on a cancellation row, and a timestamp requires a code.
--       **Settled is `cancellation_settled_at IS NOT NULL`**: a rejection keeps the row at `CANCELLATION_PENDING` and
--       is settled by that timestamp, which is what lets a second pass skip it without asking the authority again.
--   (e) the guard gains one arm: a `CANCELLATION_PENDING` row may only stay `CANCELLATION_PENDING` (recording an
--       answer) or become `CANCELLED`, and while it waits its three own facts — the pointer, the motive and the
--       substituted folio — are frozen exactly as the identity columns are. `STAMPED` and `CANCELLED` keep refusing
--       every `UPDATE` and `DELETE` keeps refusing every row.
--
-- WHAT THIS FILE DOES NOT DO, named so it is not discovered in the diff:
--   * **No reconciliation.** Nothing here settles a row; the service, the port and the adapter are `T3`, and a second
--     run writing nothing is `T4`.
--   * **No writer and no endpoint.** `T2` writes the pending row and `T8` drives the pass; this file only makes both
--     possible.
--   * **No scheduler.** It is the one thing the record defers: `fiscal_document` is armed and **forced**
--     (`V108:218`), so no scheduled process can enumerate which companies hold a pending cancellation (`L5`).
--   * **No `NO FORCE` window.** This file adds no row and backfills nothing: every assertion below lives in
--     `pg_catalog`, where no policy applies (`V108` §7 states the same, and the reason).
--   * **No second index.** `uq_fiscal_document_cancels` is already the lookup a second submission is refused by, and
--     the reconciliation reads the pending rows of one tenant by `status`, which the table's own size does not yet
--     justify indexing.
--
-- Forward-only and additive in the only sense this vocabulary has: dropping the fourth token would have to refuse
-- every pending row first, which is a decision of its own and never a silent `ALTER`.
-- ============================================

-- ─── 1. the fourth state ──────────────────────────────────────────────────────────────────────────
-- Replaced, not extended: PostgreSQL cannot add a value to a `CHECK`, and a second constraint on the same column
-- would AND a predicate with the first and admit nothing new. `NOT VALID` is deliberately not used — the table holds
-- at most the three old states today, so there is nothing for the validation pass to find, and a constraint that is
-- present but unvalidated refuses new rows while reporting as satisfied.
ALTER TABLE fiscal_document DROP CONSTRAINT ck_fiscal_document_status;
ALTER TABLE fiscal_document ADD CONSTRAINT ck_fiscal_document_status
    CHECK (status IN ('DRAFT', 'STAMPED', 'CANCELLED', 'CANCELLATION_PENDING'));

COMMENT ON COLUMN fiscal_document.status IS
    'DRAFT, STAMPED, CANCELLED or CANCELLATION_PENDING, closed by ck_fiscal_document_status. DRAFT is the only '
    'state mutable on its own; the transition out of it is the single legitimate UPDATE of a draft (L9(b)), and a '
    'CANCELLATION_PENDING row has exactly one transition of its own — to CANCELLED, written once the authority '
    'accepts (L2(e)). CANCELLATION_PENDING is NOT CANCELLED: it says the authority holds a request it has not '
    'decided, so no reader of CANCELLED may count it.';

COMMENT ON COLUMN fiscal_document.cancels_fiscal_document_id IS
    'The document this row cancels, non-null exactly on a CANCELLED or CANCELLATION_PENDING row and unique, because '
    'a document is cancelled once — a submitted cancellation is already "in flight" and the pointer is what makes the '
    'second submission impossible (uq_fiscal_document_cancels). Composite with `company_id` (fk_fiscal_document_cancels) '
    'so one tenant cannot name another''s document — a referential check bypasses row security.';

-- ─── 2. the cancellation constraint, coupled to two states instead of one (L2(b)) ────────────────
-- The shape is `V108`'s with `status = 'CANCELLED'` widened to the two states that name a cancelled document and a
-- cancellation waiting to be one. `replaces_fiscal_document_id` stays legal inside that arm and null outside it: a
-- substituting folio belongs to a cancellation and to nothing else, and the one-way rule that motive `01` requires
-- one lives in `FiscalCancellationMotive` and the service, which can name the field in a 400 (`V112` states why the
-- reverse direction is not encoded).
ALTER TABLE fiscal_document DROP CONSTRAINT ck_fiscal_document_cancellation;
ALTER TABLE fiscal_document ADD CONSTRAINT ck_fiscal_document_cancellation CHECK (
    (cancels_fiscal_document_id IS NULL
        AND cancellation_motive IS NULL
        AND replaces_fiscal_document_id IS NULL
        AND status NOT IN ('CANCELLED', 'CANCELLATION_PENDING'))
    OR (cancels_fiscal_document_id IS NOT NULL
        AND cancellation_motive IS NOT NULL
        AND status IN ('CANCELLED', 'CANCELLATION_PENDING')));

COMMENT ON COLUMN fiscal_document.replaces_fiscal_document_id IS
    'The folio that replaces the cancelled one, when the motive is a substitution (ADR-0007 decision 3). Legal only on '
    'a cancellation row — the CANCELLED one and the CANCELLATION_PENDING one, which is the same fact before the '
    'authority answered — and composite with `company_id` for the same reason as the pointer above.';

-- ─── 3. the authority's answer (L2(d)) ───────────────────────────────────────────────────────────
-- `cancellation_status_uuid` is `VARCHAR(4)` because the vendor's catalogue states four-digit codes (`200`, `204`,
-- `211`, `213`, `701`, `1801`) and the code is compared and quoted, never parsed. `cancellation_message` is `TEXT`
-- for the reason `V108` gives the artefact's `xml`: the authority's sentence is not a short string and it is kept
-- untranslated. `cancellation_settled_at` is the moment a pass decided the row was finished — a **rejection** keeps
-- the row at `CANCELLATION_PENDING` and is settled by this timestamp, which is what makes a second pass able to skip
-- it without a second provider call.
ALTER TABLE fiscal_document ADD COLUMN cancellation_status_uuid VARCHAR(4);
ALTER TABLE fiscal_document ADD COLUMN cancellation_message TEXT;
ALTER TABLE fiscal_document ADD COLUMN cancellation_settled_at TIMESTAMPTZ;

COMMENT ON COLUMN fiscal_document.cancellation_status_uuid IS
    'S1/L2(d): the authority''s code for the answer it gave this cancellation — the submission''s own (`211` in '
    'process) or the reconciliation''s (`204`, `213`, or the accepted `200`). Kept untranslated, because it is what an '
    'operator looks up in the vendor''s catalogue. Null on every row that is not a cancellation row.';
COMMENT ON COLUMN fiscal_document.cancellation_message IS
    'S1/L2(d): the authority''s sentence for that code, untranslated. Null on every row that is not a cancellation '
    'row, and legal without a code only in the sense the CHECK below refuses: a message with no verdict is a row '
    'nobody can act on.';
COMMENT ON COLUMN fiscal_document.cancellation_settled_at IS
    'S1/L2(d): when a pass decided this cancellation was finished — **settled is this column, not a state**. A '
    'rejection keeps the row at CANCELLATION_PENDING and is settled here, which is exactly what lets a second pass '
    'skip it without asking the authority again (S6). Requires cancellation_status_uuid: a timestamp with no verdict '
    'says something finished without saying how.';

-- One named `CHECK` for the three columns and their two rules, because they are one fact: an answer belongs to a
-- cancellation row, and a finish time belongs to an answer. Split in two, a row could carry the message of a
-- settlement with neither, and each half would report as satisfied on its own.
ALTER TABLE fiscal_document ADD CONSTRAINT ck_fiscal_document_cancellation_answer CHECK (
    (status IN ('CANCELLED', 'CANCELLATION_PENDING')
        OR (cancellation_status_uuid IS NULL
            AND cancellation_message IS NULL
            AND cancellation_settled_at IS NULL))
    AND (cancellation_settled_at IS NULL OR cancellation_status_uuid IS NOT NULL));

-- ─── 4. the guard, with the fourth state's arm (L2(e)) ───────────────────────────────────────────
-- `CREATE OR REPLACE` and not a second trigger: the function already is the document's verdict, and the trigger
-- (`trg_fiscal_document_guard`, `V108`) points at this function's oid, so replacing the body moves the verdict
-- without touching the trigger, its enablement or the grant the transition out of DRAFT needs.
--
-- Every earlier sentence is kept verbatim, because none of them stopped being true: `DELETE` is refused on any row, a
-- `STAMPED` or `CANCELLED` row refuses every `UPDATE`, and the identity columns are refused on any row. The new arm
-- is the only verdict the fourth state has, and it is stated as an allow-list rather than as one forbidden
-- transition: a pending row may stay pending (recording the answer the reconciliation read) or become `CANCELLED`,
-- and every other target — `STAMPED`, which would be a second stamp of a document the authority still holds, and
-- `DRAFT`, which would return a witnessed document to mutable — is refused.
--
-- The arm also freezes the pending row's **own** three facts, for the reason the identity arm below states one level
-- up: a `CANCELLATION_PENDING` row is a state that says *which* document the authority was asked about. The pointer
-- is what the reconciliation reaches the cancelled document's `uuid_fiscal` through, `uq_fiscal_document_cancels`
-- is what a second submission is refused by, and the motive (with its substitution rule) is what the authority was
-- told. A pointer, a motive or a substituted folio that could move while the row waited would leave the row
-- describing a request nobody made: the pass would settle a cancellation against a document the authority never
-- judged, and the `CANCELLED` state — where the same three columns are frozen by the blanket refusal above — says
-- that write is not a transition. Three sentences instead of one, so each refusal names the column that moved.
CREATE OR REPLACE FUNCTION fiscal_document_guard() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'fiscal_document is append-only (ADR-0007 decision 3, invariant F2): DELETE of document % of company % is refused. A document the tax authority witnessed is cancelled by a new row that names it, never removed — the original has to be returnable byte for byte years later, and a deletion leaves no record that it ever existed',
            OLD.id, OLD.company_id;
    END IF;

    IF OLD.status IN ('STAMPED', 'CANCELLED') THEN
        RAISE EXCEPTION 'fiscal_document is append-only (ADR-0007 decision 3, invariant F2): UPDATE of the % document % is refused. Only a DRAFT is mutable — the transition out of it is the single legitimate update — and a witnessed document whose contents could follow an edit is a document that changes after the authority saw it',
            OLD.status, OLD.id;
    END IF;

    IF OLD.status = 'CANCELLATION_PENDING' AND NEW.status NOT IN ('CANCELLATION_PENDING', 'CANCELLED') THEN
        RAISE EXCEPTION 'fiscal_document refuses this transition (L2(e) of odd/tasks/fiscal-cancellation-reconciliation.md): document % of company % would move from CANCELLATION_PENDING to %. A submitted cancellation is waiting for the authority''s answer: it stays CANCELLATION_PENDING while its answer is recorded, or becomes CANCELLED once the answer accepted it. Becoming STAMPED would be a second stamp of a document the authority still holds, and becoming DRAFT would return a witnessed document to mutable',
            OLD.id, OLD.company_id, NEW.status;
    END IF;

    IF OLD.status = 'CANCELLATION_PENDING' THEN
        IF NEW.cancels_fiscal_document_id IS DISTINCT FROM OLD.cancels_fiscal_document_id THEN
            RAISE EXCEPTION 'fiscal_document refuses a change of the cancellation pointer (L2(b) and L2(e) of odd/tasks/fiscal-cancellation-reconciliation.md): document % of company % would move cancels_fiscal_document_id from % to %. A pending cancellation names the document the authority was asked about — the reconciliation asks the authority about that document''s UUID — so a pointer that moved while the row waited would settle a cancellation against a document the authority never judged. Cancelling another document is a second submission, never an edit of this one',
                OLD.id, OLD.company_id, OLD.cancels_fiscal_document_id, NEW.cancels_fiscal_document_id;
        END IF;

        IF NEW.cancellation_motive IS DISTINCT FROM OLD.cancellation_motive THEN
            RAISE EXCEPTION 'fiscal_document refuses a change of the cancellation motive (L2(b) and L2(e) of odd/tasks/fiscal-cancellation-reconciliation.md): document % of company % would move cancellation_motive from % to %. The motive is what the submission declared to the authority, and the pointer above it is the document that declaration is about: an edit here would leave the row stating a reason the authority was never told, which is the same rewrite a CANCELLED row refuses',
                OLD.id, OLD.company_id, OLD.cancellation_motive, NEW.cancellation_motive;
        END IF;

        IF NEW.replaces_fiscal_document_id IS DISTINCT FROM OLD.replaces_fiscal_document_id THEN
            RAISE EXCEPTION 'fiscal_document refuses a change of the substituted folio (L2(b) and L2(e) of odd/tasks/fiscal-cancellation-reconciliation.md): document % of company % would move replaces_fiscal_document_id from % to %. A substitution names the folio the cancelled document is replaced by, and it is a fact of the submission the authority holds — the third thing a second submission reads. A pending row that could re-point it would describe a substitution nobody asked for',
                OLD.id, OLD.company_id, OLD.replaces_fiscal_document_id, NEW.replaces_fiscal_document_id;
        END IF;
    END IF;

    IF NEW.company_id IS DISTINCT FROM OLD.company_id
       OR NEW.source_type IS DISTINCT FROM OLD.source_type
       OR NEW.source_id IS DISTINCT FROM OLD.source_id THEN
        RAISE EXCEPTION 'fiscal_document refuses a change of identity (ADR-0007 decision 1): document % would move from company % / % / % to company % / % / %. A document derives from the sale it names, and re-pointing it at another sale or another tenant is not a transition — it would leave the authority''s UUID describing a different sale',
            OLD.id, OLD.company_id, OLD.source_type, OLD.source_id,
            NEW.company_id, NEW.source_type, NEW.source_id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ─── 5. the self-check: the vocabulary and the verdict, read back ────────────────────────────────
-- Catalog reads only, and for `V108`'s reason: `fiscal_document` is armed and **forced**, Flyway runs as its owner,
-- and the policy filters the owner too (`V54:50-58`, `V64`) — a row count here would answer zero for the wrong reason
-- and report «nothing wrong» over none of the rows. What is asserted is everything this file changed, and it is
-- asserted as the *set* PostgreSQL rendered rather than by name: a `CHECK` under the right name holding the wrong
-- values passes every reader that only looks for the name (`V112` states the same argument for its motive set).
DO $$
DECLARE
    v_admitted   TEXT;
    v_pointer    TEXT;
    v_answer     TEXT;
    v_guard      TEXT;
    v_columns    TEXT;
BEGIN
    -- The vocabulary, token by token and in the order the constraint states them. Exactly four, and
    -- `CANCELLATION_PENDING` last: the three `V108` wrote keep their places, and a fifth token — or a fourth the
    -- trigger does not know — fails here instead of reaching a row.
    SELECT string_agg(m[1], ',' ORDER BY ord) INTO v_admitted
      FROM pg_constraint c,
           LATERAL regexp_matches(pg_get_constraintdef(c.oid), '''([A-Z_]+)''', 'g') WITH ORDINALITY AS r(m, ord)
     WHERE c.conrelid = 'fiscal_document'::regclass
       AND c.conname = 'ck_fiscal_document_status';

    IF v_admitted IS DISTINCT FROM 'DRAFT,STAMPED,CANCELLED,CANCELLATION_PENDING' THEN
        RAISE EXCEPTION
            'V113 reads ck_fiscal_document_status admitting [%] instead of [DRAFT,STAMPED,CANCELLED,'
            'CANCELLATION_PENDING]. The fourth state is this migration''s whole point (S2): without it a submitted '
            'cancellation has nowhere to live, and a token the guard does not know is a row the state machine cannot '
            'describe.', coalesce(v_admitted, 'none');
    END IF;

    -- The pointer constraint, read as its admitted set: a `CHECK` that kept `status = ''CANCELLED''` would refuse
    -- every pending row, and one that dropped the motive would admit a cancellation that does not say why.
    SELECT string_agg(DISTINCT m[1], ',' ORDER BY m[1]) INTO v_pointer
      FROM pg_constraint c,
           LATERAL regexp_matches(pg_get_constraintdef(c.oid), '''([A-Z_]+)''', 'g') AS m
     WHERE c.conrelid = 'fiscal_document'::regclass
       AND c.conname = 'ck_fiscal_document_cancellation';

    IF v_pointer IS DISTINCT FROM 'CANCELLATION_PENDING,CANCELLED' THEN
        RAISE EXCEPTION
            'V113 reads ck_fiscal_document_cancellation naming [%] instead of [CANCELLATION_PENDING,CANCELLED]. '
            'L2(b) makes the pending row the cancellation row in waiting, so the constraint has to admit both and '
            'nothing else.', coalesce(v_pointer, 'none');
    END IF;

    -- The three answer columns, and only as far as this file can state them: the type each one carries is the fact
    -- that the code is four characters, the message is unbounded text and the settle stamp is a moment.
    SELECT string_agg(column_name || ':' || data_type, ',' ORDER BY column_name) INTO v_columns
      FROM information_schema.columns
     WHERE table_schema = 'public'
       AND table_name = 'fiscal_document'
       AND column_name IN ('cancellation_status_uuid', 'cancellation_message', 'cancellation_settled_at');

    IF v_columns IS DISTINCT FROM 'cancellation_message:text,cancellation_settled_at:timestamp with time zone,'
        || 'cancellation_status_uuid:character varying' THEN
        RAISE EXCEPTION
            'V113 reads the answer columns as [%] instead of [cancellation_message:text,'
            'cancellation_settled_at:timestamp with time zone,cancellation_status_uuid:character varying]. L2(d) '
            'gives the authority''s answer its three columns, and a settle stamp that is not a moment cannot be '
            'compared with the instant it claims.', coalesce(v_columns, 'none');
    END IF;

    -- And their constraint, read from its definition: it has to couple all three columns, because a version that
    -- named only one of them would pass every reader that looked for the name alone.
    SELECT pg_get_constraintdef(c.oid) INTO v_answer
      FROM pg_constraint c
     WHERE c.conrelid = 'fiscal_document'::regclass
       AND c.conname = 'ck_fiscal_document_cancellation_answer'
       AND c.contype = 'c';

    IF v_answer IS NULL
       OR v_answer NOT LIKE '%cancellation_status_uuid%'
       OR v_answer NOT LIKE '%cancellation_message%'
       OR v_answer NOT LIKE '%cancellation_settled_at%' THEN
        RAISE EXCEPTION
            'V113 reads ck_fiscal_document_cancellation_answer as missing or as not naming all three answer '
            'columns. L2(d) is one rule with two halves — an answer belongs to a cancellation row, and a settle '
            'stamp requires a code — and half of it admits the rows the other half exists to refuse.';
    END IF;

    -- The verdict itself: the function's body names the fourth state, and it still refuses DELETE. A `CREATE OR
    -- REPLACE` that dropped an earlier sentence would refuse nothing there, and the trigger would report as present
    -- in every listing because it is.
    SELECT pg_get_functiondef('fiscal_document_guard'::regproc) INTO v_guard;

    IF v_guard IS NULL OR v_guard NOT LIKE '%CANCELLATION_PENDING%' THEN
        RAISE EXCEPTION
            'V113 reads fiscal_document_guard as not naming CANCELLATION_PENDING. The fourth state without its arm '
            'is a row that may be edited into anything: the guard''s default verdict is "mutable", which is what '
            'makes the arm part of the vocabulary and not a later hardening (L2(e)).';
    END IF;

    IF v_guard NOT LIKE '%DELETE%' OR v_guard NOT LIKE '%STAMPED%' THEN
        RAISE EXCEPTION
            'V113 reads fiscal_document_guard as having lost a sentence it had before: DELETE is refused on any row '
            'and STAMPED refuses every UPDATE (ADR-0007 decision 3, invariant F2). Replacing the body is how the '
            'fourth state arrives and it must not be how the earlier refusals leave.';
    END IF;

    -- And the three columns the pending arm freezes, so a `CREATE OR REPLACE` that dropped one of those sentences
    -- fails here instead of leaving a pending row whose pointer may move under a pass that is about to read it.
    IF v_guard NOT LIKE '%cancels_fiscal_document_id%'
       OR v_guard NOT LIKE '%cancellation_motive%'
       OR v_guard NOT LIKE '%replaces_fiscal_document_id%' THEN
        RAISE EXCEPTION
            'V113 reads fiscal_document_guard as not freezing the pending row''s own facts. A CANCELLATION_PENDING '
            'row states which document the authority was asked about, under which motive and with which '
            'substituted folio, and the pass that settles it reads that pointer: one that could move while the row '
            'waited would settle a cancellation against a document the authority never judged (L2(b), L2(e)).';
    END IF;
END $$;
