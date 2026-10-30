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
-- WHY IT IS INERT FOR THE SUITES THAT DO NOT NEED IT
-- --------------------------------------------------
-- No password, no tables, no membership: `LOGIN` is stated so the role carries the production shape
-- and neither role is a superuser. A suite that needs to read or write as the application role
-- switches inside its own connection with `SET ROLE lifecontrol_app` (which is what RLS then applies
-- to) instead of opening a second connection with a credential that would have to live in the tree.
-- Creating these two roles is all this file does for the roles themselves; nothing in the 64 classes
-- that extend the base is affected by their presence.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'lifecontrol_owner') THEN
        CREATE ROLE lifecontrol_owner LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'lifecontrol_app') THEN
        CREATE ROLE lifecontrol_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
    END IF;
END $$;

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
