-- ============================================
-- V52 — ADR-0005's machinery: the tenant scope, the list, the helper and the assertion's query
-- ============================================
--
-- WHAT THIS MIGRATION IS, AND THE THREE THINGS IT DELIBERATELY IS NOT
--
-- It is the machinery only: the two scope settings a transaction states before its first read, the
-- closed list of tables that will carry a policy, the one function that arms a table, the privileges
-- the application role needs to reach those tables, and the query the startup assertion runs — as a
-- function, so the test and the assertion share one definition instead of two that can drift.
--
--   1. IT CREATES NO POLICY ON A REAL TABLE. Not one row of `tenant_table` is written here. The
--      policies land in T5, group by group, because a policy on a table whose scope nobody sets yet
--      reads ZERO rows: arming `products` before the transaction scope exists (T4) would take a running
--      application to "every tenant table is empty" and look like data loss.
--   2. IT ENFORCES NOTHING IN JAVA. `app.company_id` and `app.user_id` are set by T4 at the transaction
--      boundary. Until then these functions exist and are called by nobody in production code.
--   3. THE GRANT TO `lifecontrol_app` IS STAGED ON PURPOSE. The pool still connects as the owner (the
--      switch is T1's declared slice and T4's runtime one), so these privileges are unused for as long
--      as no policy exists and no connection is made with that role. Granting them here rather than
--      with the first policy is what keeps "a new table is a list entry" true for the application's
--      access as well as for the policy: a table created by a later migration inherits the grant from
--      the ALTER DEFAULT PRIVILEGES pair at the bottom, and a table nobody granted is a runtime
--      `permission denied` that no policy can explain.
--
-- Everything below is idempotent — `CREATE OR REPLACE`, `IF NOT EXISTS`, `DROP POLICY IF EXISTS` before
-- `CREATE POLICY`, and upserts instead of inserts — because `docker/scripts/db-roles.sh` re-applies its
-- sibling SQL on every deploy, and a migration replayed against a database that already ran it (Flyway
-- `repair`, a restored dump) must converge instead of failing on its own leftovers.
--
-- THE THREE KINDS, AND WHY THE KIND IS A COLUMN AND NOT THREE SPECIAL CASES IN PROSE
-- ---------------------------------------------------------------------------------
--   * `tenant`  — the row belongs to exactly one company, compared to `app.company_id`.
--   * `shared`  — a NULL owner means "the global row": readable by every tenant, by its owner when it
--                 is an override. `product_attribute_profiles` and `product_attribute_profile_roots`
--                 (~V37) are the two rows of the tree that behave this way.
--   * `subject` — the row belongs to a person, not a company, compared to `app.user_id`.
--                 `user_preferences` is read during authentication, before any company is known, so a
--                 tenant predicate would empty it exactly when it is needed (S3 of
--                 `odd/tasks/tenant-isolation-rls.md`).
--
-- The ADR's `## Schema` block expressed the second kind as a `shared_rows BOOLEAN`; it cannot express the
-- third, so the list carries the closed vocabulary in a column with a CHECK instead — three values the
-- helper refuses anything outside of.
--
-- ============================================

-- ─── 1. the tenant scope of one transaction ─────────────────────────────────────────────────────
-- `set_config(..., true)` is SET LOCAL: the value dies with the transaction, so a pooled connection
-- cannot carry one tenant's scope into the next request (invariant T6).
--
-- **The predicate reads the setting through `nullif(..., '')`, and that is not cosmetic.** Measured on
-- postgres:16-alpine: in a session that never set the parameter, `current_setting('app.company_id',
-- true)` is NULL and a bare `::uuid` cast is NULL — the rows filter out, which is what ADR decision 2
-- promises. After ONE local set and its COMMIT, the same session's parameter exists and equals the empty
-- string, so the bare cast raises `invalid input syntax for type uuid: ""` (SQLSTATE 22P02) instead of
-- returning zero rows. That is the pooled connection, the normal case after the first request, and it
-- turns fail-closed into fail-loud. `set_user_scope` needs no equivalent: its consumer compares a
-- VARCHAR, and `''` already matches no row.
CREATE OR REPLACE FUNCTION set_tenant_scope(company uuid) RETURNS void
LANGUAGE sql AS $$
    SELECT set_config('app.company_id', company::text, true);
$$;

-- ─── 2. the subject scope, for the table that is not isolated by tenant ─────────────────────────
-- A separate setting and not a second argument to the first: `user_preferences` is read before a
-- company is known, so the two facts are independent and a request may legitimately carry one alone.
CREATE OR REPLACE FUNCTION set_user_scope(subject text) RETURNS void
LANGUAGE sql AS $$
    SELECT set_config('app.user_id', subject, true);
$$;

-- ─── 3. the list: a policy is a row, and a row is written by the call that creates the policy ───
-- `regclass` and not `text`, so a rename follows the table and a dropped table cannot leave a name
-- behind that silently means something else. The row is deliberately NOT pre-populated: writing it
-- from `apply_tenant_isolation` is what keeps "listed" and "protected" one fact instead of two that
-- can disagree, which is the failure the assertion exists to catch.
CREATE TABLE IF NOT EXISTS tenant_table (
    table_name  regclass PRIMARY KEY,
    policy_kind text NOT NULL CONSTRAINT tenant_table_policy_kind_closed
                CHECK (policy_kind IN ('tenant', 'shared', 'subject')),
    key_column  text NOT NULL DEFAULT 'company_id'
);

COMMENT ON TABLE tenant_table IS
    'ADR-0005: every table carrying a row-level policy, with the kind of predicate it carries. A row is '
    'written by apply_tenant_isolation() and by nothing else.';

-- ─── 4. the one way to arm a table ──────────────────────────────────────────────────────────────
-- ENABLE turns policies on; FORCE makes them apply to the table's owner as well, which is the whole
-- point when the owner is also the migration credential (ADR decision 3, invariant T1/T5). One policy
-- named after the table, FOR ALL, with the same predicate as USING and WITH CHECK: a write cannot name
-- a tenant the reader could not see.
--
-- Identifiers are interpolated with %I / regclass %s and never concatenated by hand. The parameter
-- names are the public contract (a caller may use named notation), so the body reads them through $n
-- aliases: PL/pgSQL resolves an identifier that matches both a parameter and a column of the statement
-- to the COLUMN, and `key_column` is a column of `tenant_table` — the alias removes the ambiguity
-- instead of relying on `#variable_conflict`.
CREATE OR REPLACE FUNCTION apply_tenant_isolation(
        target regclass, kind text DEFAULT 'tenant', key_column text DEFAULT 'company_id')
RETURNS void
LANGUAGE plpgsql AS $$
DECLARE
    v_target    regclass := $1;
    v_kind      text     := $2;
    v_key       text     := $3;
    v_relname   text;
    v_policy    text;
    v_predicate text;
BEGIN
    IF v_target IS NULL THEN
        RAISE EXCEPTION 'apply_tenant_isolation: no table given; a policy needs a table';
    END IF;

    IF v_kind IS NULL OR v_kind NOT IN ('tenant', 'shared', 'subject') THEN
        RAISE EXCEPTION 'apply_tenant_isolation: unknown policy kind % (expected one of tenant, shared, subject)',
            v_kind;
    END IF;

    -- Refused BEFORE anything is altered or listed. A policy on a column that does not exist does not
    -- fail: it matches nothing, in silence, and the table reads as empty forever — the exact failure
    -- mode ADR-0005 exists to remove. This is also what catches the case the ADR's own helper could not
    -- express, `companies` isolated by its `id`.
    IF NOT EXISTS (
        SELECT 1
          FROM pg_attribute a
         WHERE a.attrelid = v_target
           AND a.attname = v_key
           AND a.attnum > 0
           AND NOT a.attisdropped
    ) THEN
        RAISE EXCEPTION 'apply_tenant_isolation: % has no column % (kind %), refusing to create a policy that would match nothing',
            v_target, v_key, v_kind;
    END IF;

    SELECT c.relname INTO v_relname FROM pg_class c WHERE c.oid = v_target;
    v_policy := 'p_tenant_' || v_relname;

    IF v_kind = 'tenant' THEN
        v_predicate := format('%I = nullif(current_setting(''app.company_id'', true), '''')::uuid',
                              v_key);
    ELSIF v_kind = 'shared' THEN
        v_predicate := format('%I IS NULL OR %I = nullif(current_setting(''app.company_id'', true), '''')::uuid',
                              v_key, v_key);
    ELSE
        -- No cast: `user_preferences.keycloak_user_id` is a VARCHAR, and `::uuid` would make the read
        -- during authentication fail instead of filter.
        v_predicate := format('%I = current_setting(''app.user_id'', true)', v_key);
    END IF;

    EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', v_target);
    EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', v_target);
    -- DROP before CREATE: CREATE POLICY refuses a second policy of the same name, and a caller that
    -- re-applies a table (a replay, a changed key_column) has to converge instead of stacking two
    -- predicates nobody wrote.
    EXECUTE format('DROP POLICY IF EXISTS %I ON %s', v_policy, v_target);
    EXECUTE format('CREATE POLICY %I ON %s FOR ALL USING (%s) WITH CHECK (%s)',
                   v_policy, v_target, v_predicate, v_predicate);

    INSERT INTO tenant_table AS t (table_name, policy_kind, key_column)
    SELECT v_target, v_kind, v_key
    ON CONFLICT (table_name) DO UPDATE
       SET policy_kind = EXCLUDED.policy_kind,
           key_column   = EXCLUDED.key_column;
END $$;

COMMENT ON FUNCTION apply_tenant_isolation(regclass, text, text) IS
    'ADR-0005: enables and forces row-level security on one table, creates its single p_tenant_<name> '
    'policy for the given kind, and records the table in tenant_table. The only way a table gets a policy.';

-- ─── 5. the privileges the application role needs to use those tables at all ────────────────────
-- Deliberately staged, and the header says why: the pool does not connect as `lifecontrol_app` yet, so
-- nothing depends on these before T4 installs the scope. What breaks without them is not a policy but
-- the first INSERT: `permission denied for sequence` (ADR correction 2, invariant T8).
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'lifecontrol_app') THEN
        RAISE EXCEPTION 'lifecontrol_app does not exist: apply docker/postgres-init/lifecontrol-roles.sql '
                        '(docker/scripts/db-roles.sh <env>) before this migration grants to it';
    END IF;
END $$;

-- Restated from the role SQL on purpose: a database whose roles were created by that script already has
-- it, and the test container's roles are created before Flyway by a script that does not grant it.
GRANT USAGE ON SCHEMA public TO lifecontrol_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO lifecontrol_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO lifecontrol_app;

-- FOR ROLE CURRENT_USER and not the literal `lifecontrol_owner`: the default privileges are stored
-- against whichever role creates the object, so they must be recorded for the role that will create it.
-- In delivery that is `lifecontrol_owner` (Flyway runs as it), and in the test container it is
-- `lifecontrol`, the image's own superuser. The ADR writes `lifecontrol_owner` because it states the
-- delivery shape; a literal here would grant nothing in the container — the ALTER would succeed and the
-- grant would be inert, which is worse than a failure because no test could see it.
ALTER DEFAULT PRIVILEGES FOR ROLE CURRENT_USER IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO lifecontrol_app;
ALTER DEFAULT PRIVILEGES FOR ROLE CURRENT_USER IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO lifecontrol_app;

-- ─── 6. the assertion, as a query both the test and the startup check consume ───────────────────
-- Every table in `public` that carries an effective `company_id` column and is not on the list. The
-- project rule is the same fact from the other side: while the policies are still to come, this returns
-- all fifteen tables S2 names, and once T5 arms them it returns nothing. The startup refusal built on it
-- (T3) lands last, over a tree that already returns no row.
--
-- `regclass` in and out, so a caller compares relation identity and not spelling, and `relkind = 'r'`
-- because RLS is a table feature: a view or a foreign table has no policy to be missing.
CREATE OR REPLACE FUNCTION unprotected_tenant_tables() RETURNS SETOF regclass
LANGUAGE sql STABLE AS $$
    SELECT c.oid::regclass
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public'
       AND c.relkind = 'r'
       AND EXISTS (SELECT 1
                     FROM pg_attribute a
                    WHERE a.attrelid = c.oid
                      AND a.attname = 'company_id'
                      AND a.attnum > 0
                      AND NOT a.attisdropped)
       AND NOT EXISTS (SELECT 1 FROM tenant_table t WHERE t.table_name = c.oid)
     ORDER BY c.relname;
$$;

COMMENT ON FUNCTION unprotected_tenant_tables() IS
    'ADR-0005: the tables that carry company_id and have no policy. Non-empty is a startup failure (T3); '
    'while the policies are still to come (T2) it lists exactly the fifteen tables of S2.';
