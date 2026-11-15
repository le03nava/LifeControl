-- ============================================
-- V108 — the fiscal document's own row set: the three tables, their policies, the append-only pair, the
--        state machine and the two unique indexes (S5, S6 and S7 of `odd/tasks/cfdi-fiscal-document.md`;
--        T4, decisions L9(a)–L9(d) and L9(j) of the same record)
-- ============================================
-- WHY THE DOCUMENT IS NOT A SET OF COLUMNS ON `sales_orders`. `docs/adr/0007-fiscal-document-is-separate.md`
-- states the ordering fact of the whole unit, and it is the argument for this file:
--
--   «the sale is mutable and the invoice cannot be. An order is editable until it reaches a terminal state, its
--     lines are replaced wholesale by the `PUT`, and a deleted order can be re-enabled. A fiscal document whose
--     contents could follow those edits would be a document that changes after the authority witnessed it. So
--     the fiscal document cannot be a set of columns on `sales_orders` — it has to be a separate row set that
--     is written once, from a snapshot, and never re-derived.» (ADR-0007, `## Context`)
--
-- And decision 1 adds the second half: a sale may end up with **more than one** fiscal document — a cancelled
-- one and its replacement — so the link is neither a column on the order nor a one-to-one. The sale is named by
-- `(source_type, source_id)`, which is the same polymorphic pair `account_entry` uses (`V97:220-233`) and for
-- the same reason: `source_type` names the table and `source_id` the row, so a second source kind (a credit
-- note, one day) is a value of the vocabulary and not a second pair of columns. There is deliberately **no**
-- foreign key on that pair: a key to one table cannot describe a column that may name another, and the
-- pointer is checked where it is read — by the writer that already loaded the sale.
--
-- WHAT THIS FILE IS, AND WHAT IT IS NOT (L9(a)). `V108` creates the columns that make a document **exist as a
-- history** — identity, state, the authority's UUID, the cancellation pointers — and arms them. `V109` adds
-- every column that says **what the document states** and the constraint trigger that keeps it honest against
-- the sale. Nothing can stamp between the two, because no writer exists until A3: a table that cannot yet hold
-- a complete document is a state no caller can reach, and `V109`'s `NOT NULL` additions land on empty tables.
-- The split is the two task titles verbatim — T4 is the document, T5 is the frozen snapshot.
--
-- WHY THE VOCABULARIES ARE NAMED `CHECK`s AND NOT ENUMS (L9(i)). `status` and `source_type` are closed here,
-- in named constraints, and the enums arrive with A3's builder — their first reader. Five enums that nothing
-- reads would be exactly the unused code the audit in `docs/` already flags; a named `CHECK` is verified where
-- the vocabulary actually lives, against `pg_constraint`, and the parity test travels with the enum.
--
-- WHY THERE IS NO `NO FORCE` WINDOW. The three tables are created empty by this file and nothing is
-- backfilled into them, so there is no cross-tenant read to unblock: the state ADR-0005 correction 3 refuses is
-- a window left open on a table that already holds rows. `V108` opens none, and the self-check at the end
-- asserts that all three are `ENABLE` **and `FORCE`** when it finishes.
--
-- WHAT THIS FILE DOES NOT DO, named so it is not discovered in the diff:
--   * **No frozen snapshot.** No `clave_prod_serv`, no money, no receiver identity — that is `V109` (T5).
--   * **No writer of any kind.** No Java entity, repository, service or controller: A3's stamping port is the
--     first thing that writes a row, and `V108` is the schema it will find.
--   * **No cancellation motives.** `cancellation_motive` is two characters and its vocabulary is the
--     authority's catalog, which decision 11 keeps out of the schema; the codes land with the first writer.
--   * **No draft purge.** A failed stamp leaves a `DRAFT` rather than garbage nobody can delete (L9(b)), and
--     purging abandoned drafts is a decision nobody has taken.
--   * **No screen.** No catalog of this tree has an ABM of UI, and this is not a catalog.
-- ============================================

-- ─── 1. the document: identity, state and the pointers a cancellation needs ────────────────────────
-- `uq_fiscal_document_company_id` is not a new fact — `id` is already the primary key — it is the companion
-- every composite pointer in this tree needs, the shape `V88:51-52` wrote for `tax_rate`, `V97` for
-- `departments` and `V106` for `customers`. A referential check **bypasses row security**, so an id-only
-- pointer lets one tenant's document name another tenant's and no policy can see it; the pair is what makes
-- that a refusal instead of a hidden row.
--
-- `uuid_fiscal` is the identity that survives everything else (ADR-0007 decision 2): the authority's schema
-- makes `serie` and `folio` optional, so they are written because humans read them and never because a stamp
-- requires them. Both stay nullable here and nothing derives them.
CREATE TABLE fiscal_document (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id                  UUID NOT NULL REFERENCES companies (id),
    source_type                 VARCHAR(40) NOT NULL,
    source_id                   UUID NOT NULL,
    status                      VARCHAR(20) NOT NULL,
    serie                       VARCHAR(25),
    folio                       VARCHAR(40),
    uuid_fiscal                 UUID,
    stamped_at                  TIMESTAMPTZ,
    cancels_fiscal_document_id  UUID,
    cancellation_motive         VARCHAR(2),
    replaces_fiscal_document_id UUID,
    issued_by                   VARCHAR(255) NOT NULL,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- The target of the two composite pointers below, and of every pointer a later slice adds to this table.
    CONSTRAINT uq_fiscal_document_company_id UNIQUE (company_id, id),
    CONSTRAINT fk_fiscal_document_cancels FOREIGN KEY (company_id, cancels_fiscal_document_id)
        REFERENCES fiscal_document (company_id, id),
    CONSTRAINT fk_fiscal_document_replaces FOREIGN KEY (company_id, replaces_fiscal_document_id)
        REFERENCES fiscal_document (company_id, id),
    -- S12/L9(i): the closed vocabulary of what a document derives from. One value today because a sale is the
    -- only thing this tree issues against; a credit note is a value here and not a second column pair.
    CONSTRAINT ck_fiscal_document_source_type CHECK (source_type IN ('SALES_ORDER')),
    -- S7/L9(b): the state machine, and the only three states it has. `DRAFT` is the only mutable one.
    CONSTRAINT ck_fiscal_document_status CHECK (status IN ('DRAFT', 'STAMPED', 'CANCELLED')),
    -- F3, first half: the authority's UUID exists exactly on a stamped row. The equality is written both ways
    -- on purpose — a `DRAFT` carrying a UUID is as wrong as a `STAMPED` row without one, and the second is
    -- the shape a provider that answered without a UUID would leave behind.
    CONSTRAINT ck_fiscal_document_uuid_matches_status CHECK ((status = 'STAMPED') = (uuid_fiscal IS NOT NULL)),
    -- The moment of the stamp, with the same both-ways rule: a stamped document states when, and nothing else
    -- states it. It is not `created_at`, which is when the draft was written.
    CONSTRAINT ck_fiscal_document_stamped_at CHECK ((status = 'STAMPED') = (stamped_at IS NOT NULL)),
    -- F4/L9(c): the three cancellation columns are one fact with the state, so a named `CHECK` couples them.
    -- A row that names a cancelled document **is** `CANCELLED`, carries the motive, and — because
    -- `ck_fiscal_document_uuid_matches_status` above is an equality — carries no `uuid_fiscal` of its own: the
    -- UUID the authority cancels belongs to the cancelled row. A replacement is legal only on such a row.
    CONSTRAINT ck_fiscal_document_cancellation CHECK (
        (cancels_fiscal_document_id IS NULL
            AND cancellation_motive IS NULL
            AND replaces_fiscal_document_id IS NULL
            AND status <> 'CANCELLED')
        OR (cancels_fiscal_document_id IS NOT NULL
            AND cancellation_motive IS NOT NULL
            AND status = 'CANCELLED')),
    -- A document is cancelled once. PostgreSQL treats `NULL`s as distinct in a unique constraint, so the
    -- drafts and the stamps — every row whose `cancels_fiscal_document_id` is null — are unaffected by it.
    CONSTRAINT uq_fiscal_document_cancels UNIQUE (cancels_fiscal_document_id)
);

COMMENT ON TABLE fiscal_document IS
    'ADR-0007 decisions 1–3: the fiscal document as a history of its own, deriving from a sale and never a '
    'state of one. A cancelled document and its replacement are two rows; a stamped row refuses UPDATE and '
    'DELETE by trigger and by revoked privilege.';
COMMENT ON COLUMN fiscal_document.source_type IS
    'The table `source_id` names — `SALES_ORDER` today, closed by ck_fiscal_document_source_type (S12). '
    'Deliberately no foreign key: the pointer is polymorphic, the shape account_entry already uses, and the '
    'writer that loaded the sale is what checks it.';
COMMENT ON COLUMN fiscal_document.source_id IS
    'The id of that sale. With `source_type` it is the identity the partial unique index of F1 reads: at most '
    'one live stamped document per sale, and a cancelled one beside it is legal.';
COMMENT ON COLUMN fiscal_document.status IS
    'DRAFT, STAMPED or CANCELLED, closed by ck_fiscal_document_status. DRAFT is the only mutable state (L9(b)): '
    'the transition out of it is the single legitimate UPDATE, which is why that privilege is granted below.';
COMMENT ON COLUMN fiscal_document.uuid_fiscal IS
    'The authority''s UUID: the identity that survives everything else, unique when present (F3). `serie` and '
    '`folio` beside it are an optional local convention, because the authority''s schema makes both optional.';
COMMENT ON COLUMN fiscal_document.cancels_fiscal_document_id IS
    'The document this row cancels, non-null exactly on a CANCELLED row and unique, because a document is '
    'cancelled once. Composite with `company_id` (fk_fiscal_document_cancels) so one tenant cannot name '
    'another''s document — a referential check bypasses row security.';
COMMENT ON COLUMN fiscal_document.replaces_fiscal_document_id IS
    'The folio that replaces the cancelled one, when the motive is a substitution (ADR-0007 decision 3). Legal '
    'only on a CANCELLED row, and composite with `company_id` for the same reason as the pointer above.';
COMMENT ON COLUMN fiscal_document.issued_by IS
    'Who issued the document, as the tree records an actor elsewhere. Not a fiscal fact: the issuer''s identity '
    'is the company''s, and its frozen copy arrives with V109.';

-- ─── 2. F1 and F3: the two unique indexes ──────────────────────────────────────────────────────────
-- F1, as a **partial** unique index and not a constraint: only the stamped rows compete for the key, so two
-- drafts of the same sale are legal (a retry is a draft), a cancelled document beside its stamped original is
-- legal (S6), and a replacement is a third row that is the only stamped one once the original is cancelled.
-- A plain unique key would forbid exactly the pair S6 exists to allow.
CREATE UNIQUE INDEX uq_fiscal_document_one_stamped ON fiscal_document (company_id, source_type, source_id)
    WHERE status = 'STAMPED';

-- F3, second half. Partial for the same reason the `CHECK` is an equality: every draft carries no UUID, and a
-- plain unique index over a nullable column is legal but says nothing about the nulls it never checks.
CREATE UNIQUE INDEX uq_fiscal_document_uuid_fiscal ON fiscal_document (uuid_fiscal)
    WHERE uuid_fiscal IS NOT NULL;

COMMENT ON INDEX uq_fiscal_document_one_stamped IS
    'F1: at most one live stamped document per sale. Partial on purpose — a cancelled document and its '
    'replacement are two rows and the pair is legal (S6).';

-- ─── 3. the line: which sale line this document states ────────────────────────────────────────────
-- `V108` gives the line its identity and its two pointers and nothing else. Every column that says *what* the
-- line states — the fiscal key, the unit key, the description, the quantity, the values, the tax — arrives
-- with `V109` (T5), because a line that states money is the frozen snapshot and the snapshot is the other
-- task.
--
-- `sales_order_item_id` is `NOT NULL` and keyed: S8 compares the document's line with the sale line it names,
-- so an invoice line that names no sale line is a line nobody could compare. That is the reason it is required
-- here and not added later.
--
-- `uq_fiscal_document_line_item` — one line per sale line — is what makes T5's comparison unambiguous: two
-- document lines naming one sale line would make "the document's amount for this line" depend on which row
-- the comparison read first.
CREATE TABLE fiscal_document_line (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id          UUID NOT NULL REFERENCES companies (id),
    fiscal_document_id  UUID NOT NULL,
    sales_order_item_id UUID NOT NULL REFERENCES sales_order_items (id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_fiscal_document_line_document FOREIGN KEY (company_id, fiscal_document_id)
        REFERENCES fiscal_document (company_id, id),
    CONSTRAINT uq_fiscal_document_line_item UNIQUE (fiscal_document_id, sales_order_item_id)
);

COMMENT ON TABLE fiscal_document_line IS
    'ADR-0007 decision 4: one line of the document per sale line, written once and never amended. V109 adds '
    'every fact the line states; this file gives it its identity and its two pointers.';
COMMENT ON COLUMN fiscal_document_line.sales_order_item_id IS
    'The sale line this document line states, required: S8 compares the two, and a line that names no sale '
    'line is a line nobody could compare. Unique with the document (uq_fiscal_document_line_item).';

-- ─── 4. the artefact: the bytes that were stamped ─────────────────────────────────────────────────
-- The primary key **is** the document, on purpose (L9(d), ADR-0007's schema block): two artefacts for one
-- document would be two candidate originals and no way to say which one the authority witnessed. A replacement
-- document gets its own artefact, because it is its own row.
--
-- `xml` is `NOT NULL` and `pdf` is not: the authority holds the XML, and a PDF is a rendering of it that a
-- reprint may or may not need (S10). The bytes are never regenerated — that is what the append-only pair below
-- enforces.
CREATE TABLE fiscal_document_artifact (
    fiscal_document_id UUID PRIMARY KEY,
    company_id         UUID NOT NULL REFERENCES companies (id),
    xml                TEXT NOT NULL,
    pdf                BYTEA,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_fiscal_document_artifact_document FOREIGN KEY (company_id, fiscal_document_id)
        REFERENCES fiscal_document (company_id, id)
);

COMMENT ON TABLE fiscal_document_artifact IS
    'ADR-0007 decision 3 and invariant F8: the XML that was stamped, kept byte for byte, one row per document '
    'because two artefacts would be two candidate originals. Insert-only by trigger and by revoked privilege.';
COMMENT ON COLUMN fiscal_document_artifact.fiscal_document_id IS
    'The document, and the primary key: one artefact per document, deliberately (L9(d)). A replacement '
    'document has an artefact of its own.';

-- ─── 5. tenancy, in the file that creates them ────────────────────────────────────────────────────
-- `apply_tenant_isolation` is the only way a table gets a policy (ADR-0005): it enables and forces row-level
-- security, creates `p_tenant_<table>` with the same predicate for reads and writes, and records the table in
-- `tenant_table` — the row `TenantIsolationStartupCheck` reads to find a table left armed by nobody. Every one
-- of the three carries `company_id` and states its own, instead of deriving it through the document: a column
-- is what a policy can read, and a join is what a query can forget (`V97:230-236`).
SELECT apply_tenant_isolation('fiscal_document');
SELECT apply_tenant_isolation('fiscal_document_line');
SELECT apply_tenant_isolation('fiscal_document_artifact');

-- ─── 6. append-only: the trigger, and the grant ───────────────────────────────────────────────────
-- The pair `V83` established on `sale_payment`, and for the same reason: `V52:187` and `V52:196-197` grant
-- SELECT/INSERT/UPDATE/DELETE on every table present and future, so **the absence of a grant is not a
-- restriction** and the restrictive half has to be written. The trigger is the half that refuses the owner and
-- the superuser, which no grant can.
--
-- The document's guard is not the same function as the two append-only tables', because its verdict is not the
-- same: `UPDATE` is a legitimate operation on a `DRAFT` — the transition out of it is the one update the state
-- machine has (L9(b)) — so the guard has three refusals and one acceptance, while the line and the artefact
-- refuse every operation. Two behaviours, two functions; a shared one would have to branch on the table name,
-- which is the shape `V97` avoided.
--
-- The identity columns are refused on **any** row, draft included: moving a document to another sale, another
-- source kind or another tenant is not a transition, and re-pointing a document would leave the authority's
-- UUID describing a different sale.
CREATE FUNCTION fiscal_document_guard() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'fiscal_document is append-only (ADR-0007 decision 3, invariant F2): DELETE of document % of company % is refused. A document the tax authority witnessed is cancelled by a new row that names it, never removed — the original has to be returnable byte for byte years later, and a deletion leaves no record that it ever existed',
            OLD.id, OLD.company_id;
    END IF;

    IF OLD.status IN ('STAMPED', 'CANCELLED') THEN
        RAISE EXCEPTION 'fiscal_document is append-only (ADR-0007 decision 3, invariant F2): UPDATE of the % document % is refused. Only a DRAFT is mutable — the transition out of it is the single legitimate update — and a witnessed document whose contents could follow an edit is a document that changes after the authority saw it',
            OLD.status, OLD.id;
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

CREATE TRIGGER trg_fiscal_document_guard
    BEFORE UPDATE OR DELETE ON fiscal_document
    FOR EACH ROW EXECUTE FUNCTION fiscal_document_guard();

-- One function for the two tables that refuse everything: the shape is identical and a second copy is a second
-- thing that can drift. `TG_TABLE_NAME` names which half refused, so the message is still the one an operator
-- needs, and `TG_OP` says what was attempted.
CREATE FUNCTION fiscal_document_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is append-only (ADR-0007 decisions 3 and 4, invariants F2 and F8): % is refused. The lines and the artefact of a stamped document are written once — the XML a reprint returns has to be the bytes the authority witnessed, and a line that could be edited would let the document disagree with the sale line it was compared against at stamp time',
        TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_fiscal_document_line_append_only
    BEFORE UPDATE OR DELETE ON fiscal_document_line
    FOR EACH ROW EXECUTE FUNCTION fiscal_document_append_only();

CREATE TRIGGER trg_fiscal_document_artifact_append_only
    BEFORE UPDATE OR DELETE ON fiscal_document_artifact
    FOR EACH ROW EXECUTE FUNCTION fiscal_document_append_only();

-- The narrowing, stated because the default privileges would hand all four back without a word.
--
-- `UPDATE` stays granted on `fiscal_document` **deliberately**, and that is the whole of L9(b): the one
-- legitimate update in this table is the transition out of `DRAFT` into `STAMPED`, and revoking it would make
-- the state machine unreachable — a draft that can never be stamped. The grant is safe because the trigger
-- above refuses any `UPDATE` of a row that is already `STAMPED` or `CANCELLED`, so the privilege opens the
-- transition and nothing else.
--
-- The two append-only tables lose `UPDATE` as well: a line and an artefact are written once by the stamp and
-- never touched again, so no state of theirs is mutable.
REVOKE DELETE ON fiscal_document FROM lifecontrol_app;
REVOKE UPDATE, DELETE ON fiscal_document_line, fiscal_document_artifact FROM lifecontrol_app;

-- `GRANT SELECT, INSERT` follows the `REVOKE` because a role that lost everything is not the intent: a stamp
-- is an insert and every reprint is a read.
GRANT SELECT, INSERT ON fiscal_document TO lifecontrol_app;
GRANT SELECT, INSERT ON fiscal_document_line TO lifecontrol_app;
GRANT SELECT, INSERT ON fiscal_document_artifact TO lifecontrol_app;

-- ─── 7. the self-check: the shape everything after this file depends on, read back ─────────────────
-- Catalog reads only, and deliberately so: the three tables are `FORCE`d, Flyway runs as their owner, and the
-- policy filters the owner too (`V54:50-58`, `V64`) — a row count here would answer zero for the wrong reason
-- and report «nothing wrong» over none of the rows. Every fact asserted below lives in `pg_catalog`,
-- `pg_policies` or `tenant_table`, where no policy applies. This file writes no row of any of the three, so
-- there is no window to open and none is left behind.
DO $$
DECLARE
    v_names       text[] := ARRAY['fiscal_document', 'fiscal_document_line', 'fiscal_document_artifact'];
    v_missing     text;
    v_unarmed     text;
    v_unlisted    text;
    v_unprotected text;
    v_privileges  text;
    v_triggers    text;
BEGIN
    -- The three tables, by name. A later edit that renames one is a rename of the policy, the pointers and
    -- every reader, and this is the only place that would say so.
    SELECT string_agg(t, ', ') INTO v_missing
      FROM unnest(v_names) AS t
     WHERE to_regclass('public.' || t) IS NULL;

    IF v_missing IS NOT NULL THEN
        RAISE EXCEPTION
            'V108 did not find % in the schema right after creating it. The document is these three tables; '
            'without them every pointer and every policy below describes something that does not exist.',
            v_missing;
    END IF;

    -- Armament, both halves plus the single policy. `relrowsecurity` alone would let the owner — the role that
    -- runs every migration — read and write across tenants, and two policies would AND a predicate nobody
    -- wrote.
    SELECT string_agg(c.relname, ', ' ORDER BY c.relname) INTO v_unarmed
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public'
       AND c.relname = ANY (v_names)
       AND c.relkind = 'r'
       AND (NOT c.relrowsecurity
            OR NOT c.relforcerowsecurity
            OR (SELECT count(*) FROM pg_policies p
                 WHERE p.schemaname = 'public'
                   AND p.tablename = c.relname
                   AND p.policyname = 'p_tenant_' || c.relname) <> 1);

    IF v_unarmed IS NOT NULL THEN
        RAISE EXCEPTION
            'V108 left % without ENABLE + FORCE and exactly one `p_tenant_<table>` policy. A table with the '
            'column and no forced policy reads every tenant''s rows for the role that owns it and reports as '
            'empty for the one that does not — both silently, and a fiscal document is the last table in this '
            'tree that may be read across tenants.', v_unarmed;
    END IF;

    -- And the registry row `apply_tenant_isolation` writes, which is what the startup assertion consumes: a
    -- table armed but not listed is a table the assertion re-reports forever, and a listed one with the wrong
    -- kind or key is a policy filtering the wrong column.
    SELECT string_agg(c.relname, ', ' ORDER BY c.relname) INTO v_unlisted
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public'
       AND c.relname = ANY (v_names)
       AND NOT EXISTS (
           SELECT 1 FROM tenant_table t
            WHERE t.table_name = c.oid
              AND t.policy_kind = 'tenant'
              AND t.key_column = 'company_id');

    IF v_unlisted IS NOT NULL THEN
        RAISE EXCEPTION
            'V108 did not record % in tenant_table as tenant/company_id. `apply_tenant_isolation` writes that '
            'row; a missing one leaves the table outside the list `TenantIsolationStartupCheck` and every '
            'inventory test read, so the policy exists and nothing knows it should.', v_unlisted;
    END IF;

    -- The same fact from the other side, scoped to this file's tables: `unprotected_tenant_tables()` is the
    -- query the startup check refuses to boot on, so a table it names is a migration omission that becomes a
    -- boot failure in every environment at once (L9(j)).
    SELECT string_agg(u::text, ', ') INTO v_unprotected
      FROM unprotected_tenant_tables() u
     WHERE u::text = ANY (v_names);

    IF v_unprotected IS NOT NULL THEN
        RAISE EXCEPTION
            'V108 left % carrying company_id with no policy. unprotected_tenant_tables() is the answer '
            'TenantIsolationStartupCheck refuses to boot on, so this is a database that never starts.', v_unprotected;
    END IF;

    -- The grant set, read back instead of trusted: the default privileges hand the pool all four on every new
    -- table, so a `REVOKE` a later edit drops would come back with no error anywhere. `UPDATE` is expected on
    -- `fiscal_document` and refused on the two append-only tables (L9(b)).
    SELECT string_agg(c.relname, ', ' ORDER BY c.relname) INTO v_privileges
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public'
       AND c.relname = ANY (v_names)
       AND (NOT has_table_privilege('lifecontrol_app', c.oid, 'SELECT')
            OR NOT has_table_privilege('lifecontrol_app', c.oid, 'INSERT')
            OR has_table_privilege('lifecontrol_app', c.oid, 'DELETE')
            OR (c.relname <> 'fiscal_document' AND has_table_privilege('lifecontrol_app', c.oid, 'UPDATE')));

    IF v_privileges IS NOT NULL THEN
        RAISE EXCEPTION
            'V108 read the wrong grant set on: %. The pool must hold SELECT and INSERT on all three, UPDATE on '
            'fiscal_document only — the transition out of DRAFT is the one legitimate update — and DELETE '
            'nowhere. V52:187 and :196-197 grant all four on every new table, so both halves are written here '
            'and this check is what keeps them.', v_privileges;
    END IF;

    -- The three triggers, by name, on the right table, enabled, calling the right function. A trigger that a
    -- later edit disables (`tgenabled = 'D'`) enforces nothing and reports as present in every listing.
    SELECT string_agg(e.name, ', ' ORDER BY e.name) INTO v_triggers
      FROM (VALUES
            ('trg_fiscal_document_guard', 'fiscal_document', 'fiscal_document_guard'),
            ('trg_fiscal_document_line_append_only', 'fiscal_document_line', 'fiscal_document_append_only'),
            ('trg_fiscal_document_artifact_append_only', 'fiscal_document_artifact', 'fiscal_document_append_only')
           ) AS e(name, relation_name, function_name)
     WHERE NOT EXISTS (
           SELECT 1
             FROM pg_trigger t
            WHERE t.tgname = e.name
              AND t.tgrelid = ('public.' || e.relation_name)::regclass
              AND NOT t.tgisinternal
              AND t.tgenabled <> 'D'
              AND t.tgfoid = ('public.' || e.function_name)::regproc);

    IF v_triggers IS NOT NULL THEN
        RAISE EXCEPTION
            'V108 reads % as missing, disabled, or pointing at another function. The trigger is the half of the '
            'append-only pair that refuses the owner and the superuser, which the grant cannot; without it a '
            'stamped document is protected only from the application role.', v_triggers;
    END IF;

    -- F1, read from the index and not from its name: a plain unique key under the same name would forbid the
    -- cancellation pair S6 exists to allow, and it would pass every reader that only looked for the name.
    IF NOT EXISTS (
        SELECT 1
          FROM pg_index i
          JOIN pg_class c ON c.oid = i.indexrelid
         WHERE c.relname = 'uq_fiscal_document_one_stamped'
           AND i.indrelid = 'fiscal_document'::regclass
           AND i.indisunique
           AND i.indpred IS NOT NULL) THEN
        RAISE EXCEPTION
            'V108 reads uq_fiscal_document_one_stamped as missing, as not unique, or as not partial. F1 is one '
            'live stamped document per sale; the partiality is what lets a cancelled document and its '
            'replacement be two rows (S6), so a full key under this name is the invariant inverted.';
    END IF;

    -- And the same for the two closed vocabularies, which are read from the constraint definition rather than
    -- counted by name: a `CHECK` under the right name holding the wrong values passes every reader and refuses
    -- nothing useful (L9(i)).
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'ck_fiscal_document_status'
           AND conrelid = 'fiscal_document'::regclass
           AND contype = 'c'
           AND pg_get_constraintdef(oid) LIKE '%DRAFT%'
           AND pg_get_constraintdef(oid) LIKE '%STAMPED%'
           AND pg_get_constraintdef(oid) LIKE '%CANCELLED%') THEN
        RAISE EXCEPTION
            'V108 could not read ck_fiscal_document_status admitting DRAFT, STAMPED and CANCELLED. The state '
            'machine is these three values: a vocabulary missing one refuses a legal transition, and one '
            'admitting a fourth is a state no trigger of this file knows how to guard.';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'ck_fiscal_document_source_type'
           AND conrelid = 'fiscal_document'::regclass
           AND contype = 'c'
           AND pg_get_constraintdef(oid) LIKE '%SALES_ORDER%') THEN
        RAISE EXCEPTION
            'V108 could not read ck_fiscal_document_source_type admitting SALES_ORDER. `source_type` names the '
            'table `source_id` points at, and an open vocabulary there lets a row name a table no writer of '
            'this tree loads.';
    END IF;
END $$;
