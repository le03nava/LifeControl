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
-- switch is the next slice). A superuser ALWAYS bypasses RLS, so dropping that attribute from the
-- owner is the whole reason this file exists.
--
-- Table, sequence and ALTER DEFAULT PRIVILEGES grants are deliberately absent: they belong to the
-- migration that creates the policies (ADR-0005 `## Schema`), because on a fresh database the tables
-- do not exist yet and a grant here would be a no-op on the first install and a real one on the second.

-- ─── 1. the owner: rename the pre-ADR role instead of creating a new one ─────────────────────────
-- Renaming keeps ownership: the role keeps every object it already owns, so an existing volume does
-- not need a re-own of a single table. Guarded so a second run is a no-op, in either direction.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'lifecontrol_user')
       AND NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'lifecontrol_owner') THEN
        ALTER ROLE lifecontrol_user RENAME TO lifecontrol_owner;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'lifecontrol_owner') THEN
        RAISE EXCEPTION 'lifecontrol_owner does not exist: on a fresh volume POSTGRES_USER must be lifecontrol_owner (see docker/.env.template)';
    END IF;
END $$;

-- ─── 2. the maintenance superuser: this file needs superuser on every run, not only the first ─────
-- Dropping SUPERUSER from the owner (step 3) leaves this database with no superuser at all: initdb
-- creates exactly one, POSTGRES_USER, and there is no `postgres` role to fall back to. Every later run
-- of this file needs one — altering another role and dropping a superuser flag both require it — and so
-- does any future migration that creates an extension, a shape this tree already has
-- (V21__employee_contracts.sql:27). Without this role the second run cannot work at all.
-- It exists for nothing else: no password, because the container's local socket is `trust` and a role
-- without a password cannot authenticate over TCP, where the image requires scram-sha-256, and no grant
-- on any table. It must never appear in a compose file, in the API wrapper or as an exporter credential.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'lifecontrol_bootstrap') THEN
        CREATE ROLE lifecontrol_bootstrap SUPERUSER LOGIN;
    END IF;
END $$;

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

-- ─── 6. read the end state back ─────────────────────────────────────────────────────────────────
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
