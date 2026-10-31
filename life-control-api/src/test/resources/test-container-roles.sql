-- ============================================
-- Test container — the two roles ADR-0005 separates and the tenant resolver they unlock
-- ============================================
--
-- Runs once per Testcontainers instance, while the postgres image is still bootstrapping and long
-- before Flyway: AbstractPostgresIntegrationTest starts the container with
-- `withInitScript("test-container-roles.sql")`, so every suite that extends it sees the roles.
--
-- WHY THIS FILE EXISTS
-- --------------------
-- `lifecontrol` — the container's user — is a SUPERUSER, and a superuser ALWAYS bypasses Row-Level
-- Security, whatever the policies say. `V52__tenant_isolation_machinery.sql` therefore grants the
-- application privileges to `lifecontrol_app`, and those grants fail with
-- `role "lifecontrol_app" does not exist` on a container that never ran
-- `docker/postgres-init/lifecontrol-roles.sql`. Creating the role inside the migration is not an
-- option: a migration would put a role in version control without the credential that makes it
-- usable, and the SQL that owns the role shape is the one the delivery uses.
--
-- WHY `lifecontrol_app` CARRIES A PASSWORD (record T6b)
-- ------------------------------------------------------
-- Because the pool connects as it. Before T6b the suite reached the database as `lifecontrol`, the
-- container's SUPERUSER, and a superuser ALWAYS bypasses Row-Level Security — so every one of the
-- sixteen ADR-0005 policies was installed and not one of them was ever observed by a test. S5 (#696)
-- asks for the suite to pass *under* the policies, and that needs the pool to log in as the role the
-- policies apply to; a pool logs in with a credential, not with `SET ROLE`.
--
-- The password is a literal in a test fixture on purpose and is not a secret: the container lives for
-- one JVM, listens on a random loopback port, is created per run and is torn down at the end. Nothing
-- in delivery reads this file; there the credential is `docker/secrets/lifecontrol_app_password`.
-- `ALTER ROLE ... PASSWORD` after the `CREATE` keeps the file idempotent when it is replayed against a
-- container that already has the role.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'lifecontrol_owner') THEN
        CREATE ROLE lifecontrol_owner LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'lifecontrol_app') THEN
        CREATE ROLE lifecontrol_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
    END IF;
END $$;

ALTER ROLE lifecontrol_app PASSWORD 'lifecontrol_app';

-- WHY THE APPLICATION ROLE MAY CREATE IN `public` HERE, AND MUST NOT IN DELIVERY
-- ----------------------------------------------------------------------------
-- Three suites own the DDL they assert on: `TenantScopeFunctionsIntegrationTest` and
-- `TenantScopeBoundaryIntegrationTest` create `probe_*` tables to call `apply_tenant_isolation` on them,
-- and `TenantIsolationStartupCheckIntegrationTest` creates one to prove the startup refusal names the
-- unlisted table. The pool is `lifecontrol_app` from T6b on, so without this grant those twelve-plus
-- cases fail with `permission denied for schema public` — a fixture limit, not a policy finding.
--
-- Delivery must NOT grant this, and the reason is the shape of the credential, not the size of the
-- privilege: an application role that can create tables can build structures no row of `tenant_table`
-- knows about, i.e. tables that carry the policies' key column and none of the policies. The startup
-- assertion (`TenantIsolationStartupCheck`) exists to refuse exactly that, and it reads a list a role
-- with DDL rights could outrun between two boots. Granting it in the container only keeps the fixture
-- honest: the probe tables are created, armed and dropped inside one test, and nothing that survives the
-- container is ever owned by `lifecontrol_app`.
GRANT CREATE ON SCHEMA public TO lifecontrol_app;

-- WHY IT ALSO CREATES THE TENANT RESOLVER
-- --------------------------------------
-- `task_employee_company(uuid)` mirrors `docker/postgres-init/lifecontrol-roles.sql` (step 6 there),
-- because this container never runs that script and the API calls this function as its only PostgreSQL
-- path to a provisioning task's tenant. Without it here, every PostgreSQL suite that runs the worker
-- would fail or — worse, before the resolver became loud — read zero rows.
--
-- `LANGUAGE plpgsql` for the same reason as the roles script: this file runs BEFORE Flyway, so the
-- tables the body names do not exist yet, and a `LANGUAGE sql` body would be parse-analysed at creation
-- time and fail. The owner is the container's own user, which is a SUPERUSER — the same attribute
-- `lifecontrol_bootstrap` carries in delivery, and ownership is what grants the bypass a policy would
-- otherwise deny.
--
-- Creating an object here is what `spring.flyway.baseline-version=0` in `application-test.properties`
-- exists for: a non-empty `public` before Flyway makes `baseline-on-migrate` skip V1, and the comment
-- there records the measurement.
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

-- The same privileges as the roles script: the bypass is one query, not one role.
REVOKE ALL ON FUNCTION task_employee_company(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION task_employee_company(uuid) TO lifecontrol_app;
