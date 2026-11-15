-- ============================================
-- V110 — the attempt key: one document per attempt, and a key that names no attempt is legal
--        (S11 of `odd/tasks/cfdi-fiscal-document.md`; `T6`'s first pull request, decision L12(h) of the same
--        record)
-- ============================================
-- WHY THE KEY LIVES IN THE SCHEMA AND NOT IN A GUARD OF THE WRITER (L12(h)). «`F9` says the attempt key is
-- unique per sale, and the tree's precedent is exact: `account_entry`'s `UNIQUE (company_id, source_type,
-- source_id, posting)` with `ON CONFLICT … DO NOTHING RETURNING id` in `V100`.» A guard is a statement a
-- second writer can skip, and a replayed attempt is exactly the case where two of them race: the job that ran
-- twice, or the operator who pressed the button again while the first stamp was still going. The key is
-- therefore the database's, and what the writer does with the collision is `FiscalDocumentSnapshotWriter`.
--
-- WHAT THE CONSTRAINT ANSWERS, AND WHAT IT DELIBERATELY DOES NOT. Two rows of the same sale cannot carry one
-- key: the unique constraint refuses the second, so a replayed attempt can never leave a second document
-- behind. The writer reads its own key first and returns the document it finds (S11: «the same attempt never
-- stamps twice»), and the constraint below is the half that holds when the read raced. The refusal a client sees in that case is a named one and never a
-- `DataIntegrityViolationException`: the writer translates it.
--
-- WHY THE KEY IS NULLABLE, WHICH IS THE ONE PLACE THIS FILE DEPARTS FROM THE PRECEDENT. `account_entry.posting`
-- (`V97`, the row this constraint is shaped after) is `NOT NULL` because every entry is a posting of one kind.
-- A `fiscal_document` row is not necessarily an attempt: `S7` makes a cancellation and a replacement two new
-- rows of this table, written by `T8`, and neither is an attempt at anything — the cancelled document already
-- exists and the replacement is a new issue that states its own key or none. Requiring the column would force
-- those rows to invent a key, which is a value nobody decided being written into the column whose whole job is
-- to say which attempt a row answers.
--
-- AND `NULL` DOES NOT COMPETE FOR THE KEY, WHICH IS WHY NO `WHERE` CLAUSE IS NEEDED. PostgreSQL treats `NULL`s
-- as distinct in a unique constraint, so any number of keyless rows of one sale is legal while two rows of one
-- sale carrying the same non-null key are not. That is exactly the shape the two halves need, and it is also
-- why this is a constraint and not a partial unique index: `uq_fiscal_document_one_stamped` (`V108`) is
-- partial because its predicate is a **status**, and the keyless rows here need no predicate at all. The
-- consequence is stated so it is not read as an oversight: **a document with no attempt key is never
-- deduplicated**, which is correct — a cancellation is idempotent by its own unique pointer
-- (`uq_fiscal_document_cancels`, `V108`), not by an attempt.
--
-- THE DUPLICATE'S BEHAVIOUR, IN ONE SENTENCE. A second row of the same `(company_id, source_type, source_id)`
-- with the same non-null `attempt_key` is **refused** by the constraint and the first row stands; the same key
-- on a **different** sale is legal, because the key names an attempt of a sale and never a global attempt; and
-- a row with no key is refused by nothing.
--
-- WHAT THIS FILE DOES NOT DO, named so it is not discovered in the diff:
--   * **No column but `attempt_key`.** `cancellation_motive`'s vocabulary is `T8`'s (L12(j)), the status enum is
--     A3's builder's (L9(i)), and nothing else about the document changes here.
--   * **No backfill, and none is possible.** No writer existed before this slice, so `fiscal_document` is empty
--     in every environment by construction and a `NULL` key needs no rows to be restated.
--   * **No `NO FORCE` window.** This file adds a column and a constraint and reads no row of the table: every
--     assertion of the self-check lives in `pg_catalog`, where no policy applies.
--   * **No index beside the constraint's own.** Its btree covers `(company_id, source_type, source_id,
--     attempt_key)`, which is the writer's replay lookup and, by left prefix, every read of one sale's
--     documents. A second index over the three leading columns would be a second copy of the same ordering.
-- ============================================

-- ─── 1. the column ─────────────────────────────────────────────────────────────────────────────────
-- 64 characters, the width the attempt key's own shapes need (an idempotency key rendered by a client, a
-- timestamped attempt name) and no wider: this column is compared and never parsed, so the only fact its type
-- carries is "a short opaque string".
ALTER TABLE fiscal_document ADD COLUMN attempt_key VARCHAR(64);

COMMENT ON COLUMN fiscal_document.attempt_key IS
    'S11 / L12(h): which stamp attempt wrote this row. Nullable on purpose — a cancellation and a replacement '
    '(S7, T8) are rows of this table that are not attempts — and NULL never competes for '
    'uq_fiscal_document_attempt, because PostgreSQL treats NULLs as distinct in a unique constraint.';

-- ─── 2. the key ────────────────────────────────────────────────────────────────────────────────────
-- Same columns, same order and same reasoning as `uq_account_entry_company_source_posting` (`V97:435-439`):
-- the tenant, the polymorphic source pair and the attempt. The `company_id` is the first column for the reason
-- the accounting key has it — the same attempt key of two tenants is two attempts — and `source_type` /
-- `source_id` before the attempt is what makes the key per fact instead of global.
ALTER TABLE fiscal_document
    ADD CONSTRAINT uq_fiscal_document_attempt
        UNIQUE (company_id, source_type, source_id, attempt_key);

COMMENT ON CONSTRAINT uq_fiscal_document_attempt ON fiscal_document IS
    'S11 / L12(h): one document per attempt of one sale. A replayed attempt collides here and writes nothing — '
    'the constraint is the half of the idempotency that holds when two writers race. NULL keys do not compete '
    '(a cancellation is not an attempt), and the same key on another sale is legal.';

-- ─── 3. the self-check: the shape the writer's replay depends on, read back ────────────────────────
-- Catalog reads only, and deliberately so: `fiscal_document` is `FORCE`d since `V108` and Flyway runs as its
-- owner, whom the policy also filters (`V54:50-58`, `V64`) — a row count here would answer zero for the wrong
-- reason and report «nothing wrong» over none of the rows. Every fact asserted below lives in `pg_catalog`,
-- where no policy applies, and this file writes no row of the table, so there is no window to open and none is
-- left behind.
DO $$
DECLARE
    v_type     text;
    v_not_null boolean;
    v_columns  text;
    v_shape    text;
BEGIN
    -- The column, by type and by the nullability that is half of the rule. A `NOT NULL` added under this name
    -- by a later edit would refuse T8's cancellation row and read as a tightening, so it is asserted rather
    -- than trusted.
    SELECT format_type(a.atttypid, a.atttypmod), a.attnotnull
      INTO v_type, v_not_null
      FROM pg_attribute a
     WHERE a.attrelid = 'fiscal_document'::regclass
       AND a.attname = 'attempt_key'
       AND a.attnum > 0
       AND NOT a.attisdropped;

    IF v_type IS NULL THEN
        RAISE EXCEPTION
            'V110 did not find fiscal_document.attempt_key right after adding it. The attempt key is the whole '
            'of S11''s schema half: without the column a replayed attempt writes a second document, and there '
            'is nothing to read back.';
    END IF;

    IF v_type <> 'character varying(64)' THEN
        RAISE EXCEPTION
            'V110 reads fiscal_document.attempt_key as %, and this file states character varying(64). A wider '
            'column invites a key that is a document in itself and a narrower one truncates a client''s key — '
            'which the constraint would then treat as a different attempt.', v_type;
    END IF;

    IF v_not_null THEN
        RAISE EXCEPTION
            'V110 reads fiscal_document.attempt_key as NOT NULL, and it must admit NULL: a cancellation and a '
            'replacement (S7, T8) are rows of this table that are not attempts, and requiring a key would force '
            'them to invent one in the column whose only job is to name the attempt.';
    END IF;

    -- The constraint, and its columns **in order**, read from `pg_constraint` and not from its name: a unique
    -- key under this name over `(company_id, source_type, source_id)` alone would refuse the second attempt of
    -- a sale — the pair S6 exists to allow once a stamp exists — and every reader that only looked for the
    -- name would pass.
    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conname = 'uq_fiscal_document_attempt'
           AND conrelid = 'fiscal_document'::regclass
           AND contype = 'u') THEN
        RAISE EXCEPTION
            'V110 reads uq_fiscal_document_attempt as missing or as not a unique constraint. S11 is one '
            'document per attempt: without the key a replayed attempt leaves a second row behind and nothing '
            'refuses it.';
    END IF;

    SELECT string_agg(a.attname, ', ' ORDER BY k.position) INTO v_columns
      FROM pg_constraint c
      CROSS JOIN LATERAL unnest(c.conkey) WITH ORDINALITY AS k(attnum, position)
      JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum
     WHERE c.conname = 'uq_fiscal_document_attempt'
       AND c.conrelid = 'fiscal_document'::regclass;

    IF v_columns IS DISTINCT FROM 'company_id, source_type, source_id, attempt_key' THEN
        RAISE EXCEPTION
            'V110 reads uq_fiscal_document_attempt over (%), and the key is (company_id, source_type, '
            'source_id, attempt_key). A key that omitted the source would make one attempt name every sale of a '
            'tenant, and one that omitted the company would collide across tenants.', v_columns;
    END IF;

    -- And the partial index of `V108` still holds, because the two keys are one design: the attempt key says
    -- "this attempt wrote one document", the partial index says "one of a sale's documents is the live stamped
    -- one", and a later edit that replaced the partial index with a full key would break the cancellation pair
    -- while this file's constraint kept passing.
    SELECT string_agg(e.name, ', ') INTO v_shape
      FROM (VALUES ('uq_fiscal_document_one_stamped')) AS e(name)
     WHERE NOT EXISTS (
           SELECT 1
             FROM pg_index i
             JOIN pg_class c ON c.oid = i.indexrelid
            WHERE c.relname = e.name
              AND i.indrelid = 'fiscal_document'::regclass
              AND i.indisunique
              AND i.indpred IS NOT NULL);

    IF v_shape IS NOT NULL THEN
        RAISE EXCEPTION
            'V110 reads % as missing, as not unique, or as not partial. F1 is one live stamped document per '
            'sale; this file adds the attempt key beside it and does not change that rule.', v_shape;
    END IF;
END $$;

-- ─── 2. a not-subject concept states no factor, and the row now says so ───────────────────────────
-- `V109` made `tipo_factor` NOT NULL over `Tasa`, `Cuota` and `Exento`, which fits every *subject* line and
-- forces a not-subject one (`tax_code = 'NA'`, `tax_object = '01'`) to borrow a factor the authority never
-- states: a concept with `ObjetoImp="01"` carries no `Traslado`, and therefore no `TipoFactor`. Writing
-- `Exento` there is not a harmless approximation — it is the one value that means *subject and exempt* — and a
-- frozen row an auditor reads must not say that about an item that is not subject to the tax at all. This is
-- the same distinction `V103` existed to make possible between `EXENTO` and `TASA_0`, one level down.
--
-- So the column admits NULL exactly where the authority states no factor, and a named `CHECK` couples the two
-- columns so that neither half can be true alone. The vocabulary `CHECK` of `V109` is untouched: `IN (…)` over
-- NULL is NULL, which a `CHECK` accepts.
ALTER TABLE fiscal_document_line ALTER COLUMN tipo_factor DROP NOT NULL;

ALTER TABLE fiscal_document_line
    ADD CONSTRAINT ck_fiscal_document_line_factor_matches_object
        CHECK ((tax_object = '01') = (tipo_factor IS NULL));

-- Read back, like everything else this file writes: the nullability and the coupling are two facts, and a
-- later edit that restored NOT NULL while keeping the `CHECK` would refuse every not-subject line at insert
-- time with a message about a column instead of about the rule.
DO $$
DECLARE
    v_nullable   boolean;
    v_definition text;
BEGIN
    SELECT NOT a.attnotnull INTO v_nullable
      FROM pg_attribute a
     WHERE a.attrelid = 'fiscal_document_line'::regclass
       AND a.attname = 'tipo_factor'
       AND NOT a.attisdropped;

    IF v_nullable IS DISTINCT FROM true THEN
        RAISE EXCEPTION
            'V110 reads fiscal_document_line.tipo_factor as NOT NULL, and a concept that is not subject to the '
            'tax states no factor at all: the row would have to borrow one the authority never states.';
    END IF;

    SELECT pg_get_constraintdef(c.oid) INTO v_definition
      FROM pg_constraint c
     WHERE c.conname = 'ck_fiscal_document_line_factor_matches_object'
       AND c.conrelid = 'fiscal_document_line'::regclass
       AND c.contype = 'c';

    IF v_definition IS NULL OR v_definition NOT LIKE '%tipo_factor IS NULL%' THEN
        RAISE EXCEPTION
            'V110 reads ck_fiscal_document_line_factor_matches_object as %, and it has to couple `tax_object` '
            'with `tipo_factor` in both directions. A NULL factor on a subject line is a tax the document does '
            'not state, and a factor on a not-subject one is a tax the item does not have.',
            coalesce(v_definition, 'missing');
    END IF;
END $$;
