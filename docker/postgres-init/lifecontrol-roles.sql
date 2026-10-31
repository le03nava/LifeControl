-- ============================================
-- LifeControl - the two database roles and their grants (ADR-0005, decision 3)
-- ============================================
-- This is the ONE body of SQL for the role split. Two entry points apply it, and both run it with
-- psql inside the database container:
--
--   * /docker-entrypoint-initdb.d/10-lifecontrol-roles.sh, on a fresh volume, while the postgres
--     image's bootstrap session is still superuser;
--   * docker/scripts/db-roles.sh <env>, on a volume that already holds data: the image runs the init
--     directory only while initializing an EMPTY data directory, and dev already has a filled one.
--
-- Everything here is idempotent, because the second entry point applies it on every deploy.
--
-- Which role does what: lifecontrol_owner owns every object and runs Flyway; lifecontrol_app owns
-- nothing, may not bypass Row-Level Security and is the role the connection pool connects as (that
-- switch is the next slice); lifecontrol_bootstrap is the maintenance superuser, and it is the one role
-- initdb creates (POSTGRES_USER). A superuser ALWAYS bypasses RLS, so dropping that attribute from the
-- owner is the whole reason this file exists -- and PostgreSQL refuses to drop it from the role initdb
-- created, which is why the owner is created here instead of being expected to already exist.
--
-- The arrangement in one line: POSTGRES_USER is the maintenance role, the owner is created by step 1, and
-- the two are never the same row. Anything else fails step 3 on every database, fresh or existing.
--
-- Table, sequence and ALTER DEFAULT PRIVILEGES grants are deliberately absent: they belong to the
-- migration that creates the policies (ADR-0005 `## Schema`), because on a fresh database the tables
-- do not exist yet and a grant here would be a no-op on the first install and a real one on the second.
--
-- This file owns exactly ONE schema object, and step 6 says why: the tenant resolver function exists
-- for the asynchronous path that has no request to take a tenant from, and its bypass comes from being
-- owned by the maintenance superuser, which only this file can do.

-- ─── 1. the owner: rename the pre-ADR role, or create it — never require initdb to have made it ─────
-- Renaming first keeps ownership: the pre-ADR role keeps every object it already owns, so an existing
-- volume does not need a re-own of a single table. Guarded so a second run is a no-op, in either
-- direction.
--
-- Creating it is the half that was missing, and its absence is what broke every install. POSTGRES_USER is
-- lifecontrol_bootstrap (see docker/docker-compose.yml), because initdb creates exactly one role and
-- PostgreSQL refuses to take SUPERUSER away from that role -- "The bootstrap superuser must have the
-- SUPERUSER attribute". Requiring the owner to already exist here (a RAISE, which is what this step used
-- to do) therefore made step 3 fail on a fresh volume exactly as on an old one, with "permission denied
-- to alter role": the ADR needs two rows, and the arrangement left them as one.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'lifecontrol_user')
       AND NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'lifecontrol_owner') THEN
        ALTER ROLE lifecontrol_user RENAME TO lifecontrol_owner;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'lifecontrol_owner') THEN
        CREATE ROLE lifecontrol_owner LOGIN NOSUPERUSER;
    END IF;
END $$;

-- Converged on every run, like the application role in step 4: Flyway logs in as this role over TCP,
-- where the image requires scram-sha-256, so it needs a password -- and initdb no longer gives it one,
-- because initdb's role is the maintenance one now. The value is read by psql's own shell escape from the
-- mounted secret, so it never reaches an argv, an environment variable, a .env file or a compose file.
-- 10-lifecontrol-roles.sh has already refused to get here when that file is missing, empty or still holds
-- CHANGEME. The SUPERUSER flag is step 3's job and not this statement's: one place owns that transition.
\set owner_password `cat /run/secrets/lifecontrol_postgres_password`
ALTER ROLE lifecontrol_owner WITH LOGIN PASSWORD :'owner_password';

-- The database, and through it the `public` schema, belongs to whoever initdb made: `pg_database_owner`
-- resolves to POSTGRES_USER, and that is the maintenance role now. Without this statement the owner could
-- not create its first table -- V1__baseline_schema.sql would fail with "permission denied for schema
-- public" on every fresh volume -- so "the owner owns every object" would be true of nothing. On a
-- volume that already ran this file the owner is already the database owner, and this is a no-op.
DO $$
BEGIN
    EXECUTE format('ALTER DATABASE %I OWNER TO lifecontrol_owner', current_database());
END $$;

-- ─── 2. the maintenance superuser: this file needs superuser on every run, not only the first ─────
-- Dropping SUPERUSER from the owner (step 3) leaves this database with no superuser at all unless a
-- second one exists. initdb creates exactly one, POSTGRES_USER, and there is no `postgres` role to fall
-- back to -- so on a fresh volume this role already exists and the CREATE below is a no-op, while on a
-- volume initialized before this arrangement it is the only thing that creates it. Every later run of
-- this file needs it — altering another role and dropping a superuser flag both require superuser — and
-- so does any future migration that creates an extension, a shape this tree already has
-- (V21__employee_contracts.sql:27). Without this role the second run cannot work at all.
-- It exists for nothing else: no password and no grant on any table. It must never appear in the API
-- wrapper or as an exporter credential.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'lifecontrol_bootstrap') THEN
        CREATE ROLE lifecontrol_bootstrap SUPERUSER LOGIN;
    END IF;
END $$;

-- And its password is dropped on every run, which is what keeps the ADR true now that initdb creates this
-- role: the postgres image hands POSTGRES_USER the value of POSTGRES_PASSWORD, which is the OWNER's own
-- secret, so without this line the maintenance superuser would be reachable over TCP with the credential
-- Flyway uses -- a superuser login nobody asked for and no document declares. A role with no password
-- cannot authenticate over TCP at all, where the image requires scram-sha-256; the container's local
-- socket is `trust`, and that is this role's only way in. On a volume where the role was created by the
-- CREATE above it already has no password and this is a no-op.
ALTER ROLE lifecontrol_bootstrap PASSWORD NULL;

-- ─── 3. the extension, BEFORE the superuser flag is dropped ─────────────────────────────────────
-- V21__employee_contracts.sql:27 runs `CREATE EXTENSION IF NOT EXISTS btree_gist`, which needs
-- superuser. On a fresh volume this file runs before Flyway ever connects as the owner, so the
-- extension is installed here. These two statements are in this order on purpose: dropping the flag
-- first would fail the first migration of every fresh database with "permission denied to create
-- extension". On an existing volume the extension is already installed, and the IF NOT EXISTS turns
-- the statement into a notice. The two ALTER ROLE statements below still need superuser on every
-- run, which is exactly what lifecontrol_bootstrap is for.
CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER ROLE lifecontrol_owner NOSUPERUSER;

-- ─── 4. the application role: owns nothing, bypasses nothing ────────────────────────────────────
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'lifecontrol_app') THEN
        CREATE ROLE lifecontrol_app;
    END IF;
END $$;

-- Converged, not created-once: every attribute is restated on every run, so a role edited by hand
-- goes back to this shape instead of keeping it. NOINHERIT so the pool carries only what the app was
-- granted, NOBYPASSRLS so a missing tenant scope filters rows instead of leaking them.
-- The password is read by psql's own shell escape from the mounted secret: the value never reaches an
-- argv, an environment variable, a .env file or a compose file. 10-lifecontrol-roles.sh has already
-- refused to get here when that file is missing, empty or still holds CHANGEME.
\set app_password `cat /run/secrets/lifecontrol_app_password`
ALTER ROLE lifecontrol_app WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS PASSWORD :'app_password';

-- ─── 5. CONNECT on the database and USAGE on the schema, nothing else ───────────────────────────
DO $$
BEGIN
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO lifecontrol_app', current_database());
END $$;

GRANT USAGE ON SCHEMA public TO lifecontrol_app;

-- ─── 6. the tenant resolver: the one fixed query the asynchronous path may bypass a policy for ────
-- `access_provisioning_tasks` carries no `company_id` (V22) and its due-task scan is cross-tenant on
-- purpose, so the access-provisioning worker cannot derive a task's tenant from a tenant table: the
-- tenant lives in `employees`, which carries a policy. This function is the narrow escape ADR-0005's
-- 2026-10-30 amendment decides: one fixed join, one uuid back, no row visibility, `EXECUTE` for the
-- application role and for nothing else. The bypass is one query, not one credential.
--
-- It lives HERE and not in a migration, and the reason is ownership: `SECURITY DEFINER` runs the body
-- with the rights of the function's OWNER, so the owner is what grants the bypass. Flyway runs as
-- `lifecontrol_owner`, which is NOSUPERUSER on purpose (step 3), so it cannot create an object owned by
-- `lifecontrol_bootstrap` -- and the owner is exactly the role this function must not run as. This file
-- runs as the maintenance superuser, and `db-roles.sh` re-applies it on every deploy.
--
-- `LANGUAGE plpgsql` and not `sql`, deliberately: on a fresh volume this file runs from
-- /docker-entrypoint-initdb.d BEFORE Flyway, so `employees` and `access_provisioning_tasks` do not exist
-- yet. A `LANGUAGE sql` body is parse-analysed at creation time and would fail with `relation
-- "access_provisioning_tasks" does not exist`; plpgsql resolves names on first execution, which is what
-- makes this file legal before the tables and keeps it idempotent after them. `STABLE` because it only
-- reads, and `SET search_path` pins name resolution on the definer's side, which is the standard
-- hardening for a function that runs with its owner's rights.
CREATE OR REPLACE FUNCTION task_employee_company(task uuid) RETURNS uuid
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public, pg_temp
    AS $$
BEGIN
    RETURN (
        SELECT e.company_id
          FROM access_provisioning_tasks t
          JOIN employees e ON e.id = t.employee_id
         WHERE t.id = task
    );
END
$$;

ALTER FUNCTION task_employee_company(uuid) OWNER TO lifecontrol_bootstrap;
REVOKE ALL ON FUNCTION task_employee_company(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION task_employee_company(uuid) TO lifecontrol_app;

-- Read back, for the same reason as step 7: a function that was created but not owned by the bootstrap
-- role is a function whose `SECURITY DEFINER` runs as somebody subject to the policies, so it answers
-- `NULL` under a policy in silence -- the failure mode this whole slice exists to remove. `grantee = 0`
-- is PUBLIC in an aclitem, and a NULL acl means "the default", which grants EXECUTE to PUBLIC.
DO $$
DECLARE
    v_owner  text;
    v_secdef boolean;
    v_acl    aclitem[];
BEGIN
    SELECT pg_get_userbyid(p.proowner), p.prosecdef, p.proacl
      INTO v_owner, v_secdef, v_acl
      FROM pg_proc p
     WHERE p.oid = to_regprocedure('public.task_employee_company(uuid)');
    IF v_owner IS NULL THEN
        RAISE EXCEPTION 'task_employee_company(uuid) was not created';
    END IF;
    IF v_owner <> 'lifecontrol_bootstrap' THEN
        RAISE EXCEPTION 'task_employee_company(uuid) must be owned by lifecontrol_bootstrap: ownership is what grants the bypass, found %', v_owner;
    END IF;
    IF NOT v_secdef THEN
        RAISE EXCEPTION 'task_employee_company(uuid) must be SECURITY DEFINER: an invoker-rights body reads employees through the caller, where the policy hides the row';
    END IF;
    IF v_acl IS NULL OR EXISTS (SELECT 1 FROM aclexplode(v_acl) a WHERE a.grantee = 0) THEN
        RAISE EXCEPTION 'task_employee_company(uuid) must not be executable by PUBLIC: the bypass is one query, not one role';
    END IF;
    IF NOT has_function_privilege('lifecontrol_app', 'public.task_employee_company(uuid)', 'EXECUTE') THEN
        RAISE EXCEPTION 'lifecontrol_app cannot execute task_employee_company(uuid), so the worker would resolve no tenant at all';
    END IF;
END $$;

-- ─── 7. read the end state back ─────────────────────────────────────────────────────────────────
-- psql's exit code is not the evidence: this raises when the roles are not what was just written, so
-- a silent no-op cannot pass as success. Through db-roles.sh a raise here aborts `deploy.sh <env> start`.
DO $$
DECLARE
    wrong text;
BEGIN
    SELECT string_agg(rolname, ', ') INTO wrong
      FROM pg_roles
     WHERE rolname IN ('lifecontrol_owner', 'lifecontrol_app')
       AND (rolsuper OR rolbypassrls OR NOT rolcanlogin);
    IF wrong IS NOT NULL THEN
        RAISE EXCEPTION 'role attributes did not converge (superuser, bypassrls or cannot login): %', wrong;
    END IF;
    IF (SELECT count(*) FROM pg_roles WHERE rolname IN ('lifecontrol_owner', 'lifecontrol_app')) <> 2 THEN
        RAISE EXCEPTION 'both lifecontrol_owner and lifecontrol_app must exist after this file runs';
    END IF;
END $$;
