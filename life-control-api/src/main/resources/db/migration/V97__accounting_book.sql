-- ============================================
-- V97 — the accounting book: six tenant tables, four tenant-safe pointers and two partial keys
--        (S1, S7 and S8 of odd/tasks/derived-accounting.md, T1)
-- ============================================
-- `docs/adr/0008-accounting-is-a-projection.md`, accepted 2026-11-12, decides that accounting in this
-- system is a **projection derived in the database** from the append-only facts the tree already writes —
-- `status_transition`, `sale_payment`, `stock_allocation` — and never a set of documents a person captures
-- and edits (decisions 1 and 3). This file creates the place that projection lands in: the chart of
-- accounts, the cost-center catalog, the period, the rules, and the entry with its lines. No posting rule
-- runs yet, so the book starts empty and stays empty until a later slice fires; what this file makes true is
-- that once something does post, it cannot be written across tenants. That it also cannot commit out of
-- balance, or be amended, is the rules slice's half (S2–S6) and arrives with it.
--
-- WHY EVERY TABLE CARRIES `company_id` AND IS ARMED IN THIS FILE (S1, ADR decision 9). The lesson is already
-- measured in this tree: `sales_order_items` has no `company_id` and therefore no policy, so its tenancy is
-- derived through a parent that a query has to remember to join. Accounting does not repeat it — every one
-- of the six tables carries the column and is armed by `apply_tenant_isolation` in the file that creates it,
-- and `account_entry_line` in particular states its own company instead of deriving it from its entry, which
-- is what makes A7 (`V52`'s policy) rather than a join the thing that refuses a foreign line.
--
-- WHY THE POINTERS ARE COMPOSITE (S7, invariants A7/A9/A11). A referential check **bypasses row security**,
-- so an id-only pointer lets one tenant's row name another's and no policy can see it: the referring row is
-- legitimately its own. Four pointers are closed here — `cost_center` → `departments`, and
-- `account_entry_line` → `account_entry`, `account` and `cost_center` — each through a composite foreign key
-- over a `UNIQUE (company_id, id)` on the target, the shape `V88:51-52` wrote for `tax_rate`. The spec named
-- the department and the cost center; `account_entry_line`'s entry and account are the same premise over the
-- same kind of column, and closing two of four would leave the hole open exactly where a report joins.
-- `cost_center` → `company_stores` is the fifth, composite for the same reason:
-- `uq_company_stores_company_id` (`V48:44`) already existed, so the target cost nothing.
--
-- WHY THE COST CENTER'S UNIQUENESS IS TWO PARTIAL INDEXES (S8, invariant A10). A center is a store plus a
-- department, several centers belong to one store, and the store is nullable — so one composite key over
-- `(company_id, company_store_id, department_id)` would admit a **second storeless center** for the same
-- department, because PostgreSQL treats NULLs as distinct. That is the exact defect `V95:84-96` closed for a
-- `tax_rate` whose region is null, and the catalog inherits the decision instead of rediscovering it.
--
-- WHY THE DEPARTMENT CATALOG IS NOT CREATED HERE. The ADR's `## Schema` block created a `department` table
-- and its `## Amendments after acceptance` (2026-11-12) replaced it with the catalog the tree already has:
-- `departments` (`V19:15-30`), company-scoped, unique by code and by name within the company, armed by
-- `V53:132`, with a live CRUD. The one thing a composite pointer needs and V19 does not state is the
-- redundant key, so this file adds `uq_departments_company_id` and nothing else to another unit's table.
-- A second catalog with the same meaning is what ADR decision 10 argues against one layer up: an employee
-- would hang from `departments` and a cost center from `department`, and nothing would make them agree.
--
-- WHAT THIS FILE DOES **NOT** DO, named so it is not discovered in the diff. The unit continues in later
-- slices and later migrations; these are the parts of the ADR that this file deliberately leaves out:
--   * **No seed, no posting rule and no period close (S9–S18).** The catalogs are empty on purpose: the
--     starter chart of accounts is T4 and the rules are T6–T8, so a tenant today has a book with no rows,
--     which is the only honest state for a slice that posts nothing.
--   * **No append-only pair, no balance and no one-posting-per-source key (S2–S6).** The two entry tables
--     are created and armed here, and the rules they cannot break arrive in the slice that follows: T2 adds
--     the append-only trigger and its grant, T3 the deferred balance and minimum-two-lines triggers, the
--     row `CHECK`s and the source key.
--
-- ─── 1. the key a composite pointer needs on the catalog that already exists ────────────────────────
-- Not a new fact: `id` is the primary key, so the pair is unique by construction. It exists because
-- `REFERENCES` has no way to say "the id, and also the column beside it" — the sentence V88 wrote for
-- `tax_rate`. Renaming the column, not the constraint, is what would break it.
ALTER TABLE departments
    ADD CONSTRAINT uq_departments_company_id UNIQUE (company_id, id);

COMMENT ON CONSTRAINT uq_departments_company_id ON departments IS
    'Target of fk_cost_center_department (S7 of odd/tasks/derived-accounting.md). `id` is already unique; the '
    'pair exists so a cost center cannot name another tenant''s department, which a referential check would '
    'otherwise allow because it bypasses row security.';

-- ─── 2. the chart of accounts ─────────────────────────────────────────────────────────────────────
-- Editable on purpose and armed like the rest: it is a tenant's catalog, not user-invented text, and the
-- starter rows arrive from the seed (T4). `kind` is closed to the five classes a chart of accounts has,
-- because a sixth value is a decision and not a typo.
CREATE TABLE account (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id  UUID NOT NULL REFERENCES companies (id),
    code        VARCHAR(20)  NOT NULL,
    name        VARCHAR(120) NOT NULL,
    kind        VARCHAR(10)  NOT NULL,
    enabled     BOOLEAN NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_account_company_code UNIQUE (company_id, code),
    -- The target of fk_account_entry_line_account. `posting_rule`'s two account pointers stay id-only in
    -- the accepted schema; S7 does not name them, so this file does not change them.
    CONSTRAINT uq_account_company_id UNIQUE (company_id, id),
    CONSTRAINT ck_account_kind CHECK (kind IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE'))
);

COMMENT ON TABLE account IS
    'ADR-0008 decision 1: one row of the chart of accounts. Seeded per tenant (T4) and editable, because a '
    'chart of accounts is a catalog — the book itself, which is never edited, is account_entry.';
COMMENT ON COLUMN account.code IS
    'The key a report filters and a posting rule names, never the label: a name can be rewritten, a code is '
    'what an operator recognises a line by. Unique per company, not globally — two tenants name their own '
    'accounts.';
COMMENT ON COLUMN account.kind IS
    'The five classes of a double-entry chart. Closed by CHECK on purpose: a sixth value is a decision about '
    'the book, and a typo that produced an unclassifiable account would surface in a trial balance nobody '
    'could subtotal.';

-- ─── 3. the cost center: a store plus a department, and the one axis a person selects ──────────────
-- `company_store_id` is NULL for the center that belongs to no store, and NULL is the value a single
-- composite key cannot police — which is why the two partial indexes below exist instead of a third unique
-- constraint. `department_id` is `NOT NULL`: a center with no department would be a store under another
-- name, and the ADR's second correction is precisely that a store is not a center.
CREATE TABLE cost_center (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id       UUID NOT NULL REFERENCES companies (id),
    code             VARCHAR(20)  NOT NULL,
    name             VARCHAR(120) NOT NULL,
    company_store_id UUID,
    department_id    UUID NOT NULL,
    enabled          BOOLEAN NOT NULL DEFAULT true,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_cost_center_company_code UNIQUE (company_id, code),
    -- The target of fk_account_entry_line_cost_center.
    CONSTRAINT uq_cost_center_company_id UNIQUE (company_id, id),
    CONSTRAINT fk_cost_center_department FOREIGN KEY (company_id, department_id)
        REFERENCES departments (company_id, id),
    -- Composite for the reason S7 gives for all of them: an id-only pointer to another tenant's store
    -- bypasses the policy that would hide it. The target is `uq_company_stores_company_id` (V48), so this
    -- costs no key of its own.
    CONSTRAINT fk_cost_center_store FOREIGN KEY (company_id, company_store_id)
        REFERENCES company_stores (company_id, id)
);

COMMENT ON TABLE cost_center IS
    'ADR-0008 decision 10: the analytic axis catalog. A center is a store plus a department and several '
    'centers belong to one store, so the pair is the identity — a store is not a center. The axis is '
    'pre-filled from the store the source fact already carries and the department is chosen by a person, '
    'which is the one fact on this book a human supplies (S14).';
COMMENT ON COLUMN cost_center.company_store_id IS
    'The store this center belongs to, or NULL when it belongs to none (head office, a shared unit). The '
    'store is the usual value and the axis is pre-filled from the store the source fact already carries '
    '(S14); the department is the one value a person supplies.';
COMMENT ON COLUMN cost_center.department_id IS
    'A row of the existing `departments` catalog, never free text: a typo in a text column does not fail, it '
    'creates a second center and splits one report in two with nothing raising (ADR decision 10). Composite '
    'with company_id so another tenant''s department is unreachable even by id.';

-- ─── 4. the two partial keys S8 states: one per scope, because NULLs are distinct ─────────────────
-- The store scope. Two centers for the same store and department are two answers to one question, and the
-- report that groups by center would split one unit into two rows.
CREATE UNIQUE INDEX uq_cost_center_store_department
    ON cost_center (company_id, company_store_id, department_id)
    WHERE company_store_id IS NOT NULL;

-- The storeless scope, and the reason this is an index and not a constraint: `UNIQUE (company_id,
-- company_store_id, department_id)` would admit a second row with `company_store_id IS NULL` for the same
-- department, because PostgreSQL treats NULLs as distinct values. Stated as its own partial key, the
-- storeless center is unique by department alone, which is what the ADR means.
CREATE UNIQUE INDEX uq_cost_center_company_department
    ON cost_center (company_id, department_id)
    WHERE company_store_id IS NULL;

-- ─── 5. the period: a month per tenant, closed by a state and never by a delete ────────────────────
-- `period` is the first day of the month, so "the period of a date" is `date_trunc('month', …)` and the
-- same row answers every date inside it without a range scan. `closed_at` is a timestamp and not a boolean
-- because a close is a fact with a moment and an author, and re-opening one is a later decision this file
-- does not make.
CREATE TABLE accounting_period (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id  UUID NOT NULL REFERENCES companies (id),
    period      DATE NOT NULL,
    closed_at   TIMESTAMPTZ,
    closed_by   VARCHAR(255),
    CONSTRAINT uq_accounting_period_company_period UNIQUE (company_id, period)
);

COMMENT ON TABLE accounting_period IS
    'ADR-0008 decision 8: one row per tenant and month. An entry whose business date falls in a closed '
    'period is refused (S16, T11); a correction lands in the first open period and names the entry it '
    'reverses. Closing is a state — there is no delete.';
COMMENT ON COLUMN accounting_period.period IS
    'The first day of the month this row governs, so the lookup is equality and not a range.';
COMMENT ON COLUMN accounting_period.closed_at IS
    'NULL while the period is open. A timestamp rather than a flag because a close has a moment and an '
    'author, and the trigger of S16 reads exactly this column.';

-- ─── 6. the rules, declared as data ────────────────────────────────────────────────────────────────
-- ADR decision 4: `posting_rule` maps a source type plus a tenant to the debit account, the credit account
-- and the posting kind. It is a table and not code because a money-moving table nobody posts has to be
-- refused at startup (S10, T5), and that assertion reads rows, not a classpath.
CREATE TABLE posting_rule (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id        UUID NOT NULL REFERENCES companies (id),
    source_type       VARCHAR(40) NOT NULL,
    posting           VARCHAR(40) NOT NULL,
    debit_account_id  UUID NOT NULL REFERENCES account (id),
    credit_account_id UUID NOT NULL REFERENCES account (id),
    enabled           BOOLEAN NOT NULL DEFAULT true,
    CONSTRAINT uq_posting_rule_company_source_posting UNIQUE (company_id, source_type, posting)
);

COMMENT ON TABLE posting_rule IS
    'ADR-0008 decision 4: the posting rules as data. Editable on purpose — this catalog is one of the two '
    'the ADR exempts from the append-only pair, because a rule is a configuration and an entry is a fact.';
COMMENT ON COLUMN posting_rule.posting IS
    'Which posting of a source this rule produces: REVENUE, COLLECTION, COST_OF_SALES or REVERSAL. Together '
    'with source_type it is the rule''s identity within a tenant, so one source type posts each of its '
    'pieces once.';

-- ─── 7. the entry: the book's only unit, and the source it derives from ─────────────────────────────
-- The columns after `entry_date` are the anchor ADR decision 3 requires: `(source_type, source_id)` names
-- the append-only fact this entry derives from — a transition, a payment row, an allocation — and
-- `posting` names which piece of it. `reverses_entry_id` exists from the first slice because a book that
-- cannot express its own correction is the book decision 2 refuses.
CREATE TABLE account_entry (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id        UUID NOT NULL REFERENCES companies (id),
    entry_date        DATE NOT NULL,
    source_type       VARCHAR(40) NOT NULL,
    source_id         UUID NOT NULL,
    posting           VARCHAR(40) NOT NULL,
    reverses_entry_id UUID REFERENCES account_entry (id),
    memo              TEXT,
    actor             VARCHAR(255) NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- The target of fk_account_entry_line_entry.
    CONSTRAINT uq_account_entry_company_id UNIQUE (company_id, id)
);

COMMENT ON TABLE account_entry IS
    'ADR-0008 decisions 1 and 3: one entry, derived from one append-only source. An entry is never edited — '
    'a correction is a new entry that reverses it — and it carries the document''s business date, not the '
    'moment the row was written, so a late posting still reports in its own month.';
COMMENT ON COLUMN account_entry.entry_date IS
    'The business date the entry belongs to: the sales document''s date for revenue, the payment''s for '
    'collection. Not `created_at`, which would report a whole historical backfill in the month it ran.';
COMMENT ON COLUMN account_entry.source_type IS
    'The append-only fact this entry derives from — a status transition, a `sale_payment` row, a '
    '`stock_allocation`. The book never posts from a mutable document (ADR decision 3).';
COMMENT ON COLUMN account_entry.source_id IS
    'The id of that fact. Paired with `source_type` and `posting` it is how a replay finds the entry that '
    'already exists instead of writing a second one; the key that refuses the second one is the rules '
    'slice''s (S6).';
COMMENT ON COLUMN account_entry.reverses_entry_id IS
    'The entry this one reverses, NULL for an original posting. A reversal is a new entry, never an edit: '
    'the shape `sale_payment_void` established for payments (ADR decision 2).';

-- ─── 8. the line: the money, the account and the one axis a person supplies ───────────────────────
-- `company_id` is stated on the line and not derived through the entry (A7): a column is what a policy can
-- read, and a join is what a query can forget. `cost_center_id` is nullable because a posting whose source
-- fact carries no store leaves it null instead of guessing (S14).
CREATE TABLE account_entry_line (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id     UUID NOT NULL REFERENCES companies (id),
    entry_id       UUID NOT NULL,
    account_id     UUID NOT NULL,
    cost_center_id UUID,
    debit          NUMERIC(19,4) NOT NULL DEFAULT 0,
    credit         NUMERIC(19,4) NOT NULL DEFAULT 0,
    currency       CHAR(3) NOT NULL REFERENCES currency (code),
    CONSTRAINT fk_account_entry_line_entry FOREIGN KEY (company_id, entry_id)
        REFERENCES account_entry (company_id, id),
    CONSTRAINT fk_account_entry_line_account FOREIGN KEY (company_id, account_id)
        REFERENCES account (company_id, id),
    CONSTRAINT fk_account_entry_line_cost_center FOREIGN KEY (company_id, cost_center_id)
        REFERENCES cost_center (company_id, id)
);

COMMENT ON TABLE account_entry_line IS
    'ADR-0008 decisions 1 and 10: the debit or the credit of one entry against one account, with the '
    'analytic axis on the line and never on the header — one entry splits across units and a header cannot '
    'grow that later.';
COMMENT ON COLUMN account_entry_line.company_id IS
    'The line''s own tenant, never derived through its entry (invariant A7). This is the column the policy '
    'reads and the leading column of all three composite keys, so a line of one company cannot name another '
    'company''s entry, account or cost center.';
COMMENT ON COLUMN account_entry_line.cost_center_id IS
    'The analytic axis, nullable: the store the fact already carries is pre-filled and the department is '
    'chosen by a person (S14). A posting whose source fact carries no store leaves it NULL rather than '
    'guessing, which is why the key tolerates an absence.';
COMMENT ON COLUMN account_entry_line.debit IS
    'The debit side, `0` when the line is a credit. Four decimals because that is the scale every money part '
    'of this tree carries since `V80`; the balance rule that sums this pair against `credit` arrives with '
    'the rules slice (S3).';
COMMENT ON COLUMN account_entry_line.credit IS
    'The credit side, `0` when the line is a debit. The CHECKs that keep a line to one side, and to an '
    'amount that is money, arrive with the rules slice (S5).';
COMMENT ON COLUMN account_entry_line.currency IS
    'The currency ADR-0002 put on the document. One document, one currency: this book does no functional-'
    'currency translation and holds no exchange rate (ADR-0008 `## Migration`, one currency).';

-- ─── 9. the indexes the slices after this one read by ─────────────────────────────────────────────
-- The unique keys above answer the catalog reads and the pointer targets. What is left are the four reads a
-- report does, and each one leads with `company_id` because the policy filters on it: a date range over the
-- entries, the lines of one entry, the cost of one center, and the balance of one account.
CREATE INDEX idx_account_entry_company_date ON account_entry (company_id, entry_date);
CREATE INDEX idx_account_entry_line_entry ON account_entry_line (entry_id);
CREATE INDEX idx_account_entry_line_company_cost_center ON account_entry_line (company_id, cost_center_id);

-- The trial balance groups the lines of a period by account, so the account is the read's second column.
-- No other key leads with `(company_id, account_id)`: `fk_account_entry_line_account`'s target is the
-- catalog, and a foreign key never indexes the side that points.
CREATE INDEX idx_account_entry_line_company_account ON account_entry_line (company_id, account_id);

-- ─── 10. tenancy, in the file that creates them ───────────────────────────────────────────────────
-- `apply_tenant_isolation` is the only way a table gets a policy (ADR-0005): it enables and forces row-level
-- security, creates `p_tenant_<table>` with the same predicate for reads and writes, and records the table in
-- `tenant_table` — which is what the startup assertion reads and what `TenantIsolationStartupCheck` uses to
-- find a table that was left armed by nobody. No `GRANT` line here: `V52:196-199`'s ALTER DEFAULT PRIVILEGES
-- already hands the pool's role all four privileges on every table created afterwards.
SELECT apply_tenant_isolation('account');
SELECT apply_tenant_isolation('cost_center');
SELECT apply_tenant_isolation('accounting_period');
SELECT apply_tenant_isolation('posting_rule');
SELECT apply_tenant_isolation('account_entry');
SELECT apply_tenant_isolation('account_entry_line');

-- ─── 11. the self-check: the shape the policies, the pointers and the rules depend on, read back ────
-- Catalog reads only, and deliberately so: the six tables are `FORCE`d, and Flyway runs as their owner, whom
-- the policy also filters (`V54:50-58`, `V64`) — a row count here would answer zero for the wrong reason and
-- report «nothing wrong» over none of the rows. Every fact asserted below lives in `pg_catalog` or in
-- `tenant_table`, where no policy applies. Nothing in this file writes a row of any accounting table, so
-- there is no `NO FORCE` window to open and none is left behind.
DO $$
DECLARE
    v_names      text[] := ARRAY[
                           'account', 'cost_center', 'accounting_period',
                           'posting_rule', 'account_entry', 'account_entry_line'];
    v_missing    text;
    v_unarmed    text;
    v_unlisted   text;
    v_stray      text;
    v_department bigint;
    v_fk_missing text;
    v_store_key  boolean;
    v_bare_key   boolean;
BEGIN
    -- The six tables, by name. A later edit that renames one is a rename of the policy, the keys and every
    -- report that reads it, and this is the only place that would say so.
    SELECT string_agg(t, ', ') INTO v_missing
      FROM unnest(v_names) AS t
     WHERE to_regclass('public.' || t) IS NULL;

    IF v_missing IS NOT NULL THEN
        RAISE EXCEPTION
            'V97 did not find % in the schema right after creating it. The book is these six tables; without '
            'them every pointer and every policy below is describing something that does not exist.',
            v_missing;
    END IF;

    -- The seventh catalog of the ADR's schema is NOT created here, because the tree already has
    -- `departments` and the amendment of 2026-11-12 chose it. A `department` table appearing would be the
    -- second catalog decision 10 refuses, so it is a failure and not a curiosity.
    IF to_regclass('public.department') IS NOT NULL THEN
        RAISE EXCEPTION
            'V97 reads a relation named `department`. The ADR''s amendment reuses `departments` (V19), which '
            'is company-scoped and already armed; a second catalog with the same meaning splits a report '
            'between the department an employee hangs from and the department a cost center names, with '
            'nothing raising.';
    END IF;

    -- Armament, both halves plus the single policy. `relrowsecurity` alone would let the owner — the role
    -- that runs every migration — read and write across tenants, and two policies would AND a predicate
    -- nobody wrote.
    SELECT string_agg(c.relname, ', ') INTO v_unarmed
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public'
       AND c.relname = ANY (v_names)
       AND (NOT c.relrowsecurity
            OR NOT c.relforcerowsecurity
            OR (SELECT count(*) FROM pg_policies p
                 WHERE p.schemaname = 'public' AND p.tablename = c.relname
                   AND p.policyname = 'p_tenant_' || c.relname) <> 1);

    IF v_unarmed IS NOT NULL THEN
        RAISE EXCEPTION
            'V97 left % without ENABLE + FORCE and the one `p_tenant_<table>` policy. A table with the column '
            'and no forced policy reads every tenant''s rows for the role that owns it, and reports as empty '
            'for the one that does not — both silently.', v_unarmed;
    END IF;

    -- And the registry row `apply_tenant_isolation` writes, which is what the startup assertion consumes: a
    -- table armed but not listed is a table the assertion re-reports forever, and a listed one with the
    -- wrong kind or key is a policy filtering the wrong column.
    SELECT string_agg(c.relname, ', ') INTO v_unlisted
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
            'V97 did not record % in tenant_table as tenant/company_id. `apply_tenant_isolation` writes that '
            'row; a missing one leaves the table outside the list the startup assertion and every inventory '
            'test read, so the policy exists and nothing knows it should.', v_unlisted;
    END IF;

    -- The whole book is company-scoped, so a table created here carrying no `company_id` is the shape
    -- `sales_order_items` has and S1 exists to avoid.
    SELECT string_agg(c.relname, ', ') INTO v_stray
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public'
       AND c.relname = ANY (v_names)
       AND NOT EXISTS (
           SELECT 1 FROM pg_attribute a
            WHERE a.attrelid = c.oid
              AND a.attname = 'company_id'
              AND a.attnum > 0
              AND NOT a.attisdropped);

    IF v_stray IS NOT NULL THEN
        RAISE EXCEPTION
            'V97 created % without a company_id column. Every table of the book carries its own tenant; the '
            'one that derives it through a parent is the hole S1 closes.', v_stray;
    END IF;

    -- The redundant key on the catalog that already existed. Without it the whole of S7 has no target and
    -- the migration would fail instead at the CREATE TABLE — but a silent DROP after the fact would take the
    -- key away from a pointer that still exists, so it is read back here.
    SELECT count(*) INTO v_department
      FROM pg_constraint k
     WHERE k.conrelid = 'departments'::regclass
       AND k.conname = 'uq_departments_company_id'
       AND k.contype = 'u'
       AND strpos(pg_get_constraintdef(k.oid), '(company_id, id)') > 0;

    IF v_department <> 1 THEN
        RAISE EXCEPTION
            'V97 reads % of a uq_departments_company_id UNIQUE (company_id, id) on departments and expects '
            'one. It is the target fk_cost_center_department points at; without it a cost center could only '
            'name a department by id, which a referential check resolves across tenants.', v_department;
    END IF;

    -- The five composite pointers, each with the column pair it states and the table it points at. Counted
    -- by name and read back from `pg_get_constraintdef` because a foreign key over the wrong pair is exactly
    -- the defect S7 describes: it exists, it is enforced, and it does not carry the tenant.
    SELECT string_agg(e.name, ', ') INTO v_fk_missing
      FROM (VALUES
            ('fk_cost_center_department', 'cost_center', 'department_id', 'departments'),
            ('fk_cost_center_store', 'cost_center', 'company_store_id', 'company_stores'),
            ('fk_account_entry_line_entry', 'account_entry_line', 'entry_id', 'account_entry'),
            ('fk_account_entry_line_account', 'account_entry_line', 'account_id', 'account'),
            ('fk_account_entry_line_cost_center', 'account_entry_line', 'cost_center_id', 'cost_center')
           ) AS e(name, relname, column_name, target)
     WHERE NOT EXISTS (
           SELECT 1
             FROM pg_constraint k
            WHERE k.conname = e.name
              AND k.conrelid = e.relname::regclass
              AND k.contype = 'f'
              AND k.confrelid = e.target::regclass
              AND strpos(pg_get_constraintdef(k.oid),
                         'FOREIGN KEY (company_id, ' || e.column_name || ')') > 0);

    IF v_fk_missing IS NOT NULL THEN
        RAISE EXCEPTION
            'V97 reads % as missing or stated over the wrong columns. Every pointer of the book is composite '
            'over (company_id, <the id>): a referential check bypasses row security, so an id-only pointer '
            'lets one tenant name another''s row and no policy can refuse it (S7).', v_fk_missing;
    END IF;

    -- The two partial keys of S8, each read as the unique partial index it must be, over the columns it
    -- states, with the predicate that makes the scope. The predicate is compared as an `IS NULL` /
    -- `IS NOT NULL` fragment so the check does not depend on how the server spaces the expression.
    SELECT i.indisunique
           AND i.indpred IS NOT NULL
           AND strpos(pg_get_expr(i.indpred, i.indrelid), 'company_store_id IS NOT NULL') > 0
           AND strpos(pg_get_indexdef(i.indexrelid),
                      '(company_id, company_store_id, department_id)') > 0
      INTO v_store_key
      FROM pg_index i
     WHERE i.indexrelid = to_regclass('uq_cost_center_store_department');

    IF v_store_key IS NULL OR NOT v_store_key THEN
        RAISE EXCEPTION
            'V97 left uq_cost_center_store_department as something other than the store key it declares: a '
            'unique, partial index on (company_id, company_store_id, department_id) over company_store_id IS '
            'NOT NULL. Without it one store states the same department twice and a report by center splits '
            'one unit into two rows.';
    END IF;

    SELECT i.indisunique
           AND i.indpred IS NOT NULL
           AND strpos(pg_get_expr(i.indpred, i.indrelid), 'company_store_id IS NULL') > 0
           AND strpos(pg_get_indexdef(i.indexrelid), '(company_id, department_id)') > 0
      INTO v_bare_key
      FROM pg_index i
     WHERE i.indexrelid = to_regclass('uq_cost_center_company_department');

    IF v_bare_key IS NULL OR NOT v_bare_key THEN
        RAISE EXCEPTION
            'V97 left uq_cost_center_company_department as something other than the storeless key it '
            'declares: a unique, partial index on (company_id, department_id) over company_store_id IS NULL. '
            'PostgreSQL treats NULLs as distinct, so without this half a second storeless center for the '
            'same department is accepted — the defect V95 closed for a tax rate whose region is null.';
    END IF;
END $$;
