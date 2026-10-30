-- ============================================
-- Test container — the two roles ADR-0005 separates, created once per container
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
-- Creating these two roles is all this file does; nothing in the 64 classes that extend the base is
-- affected by their presence.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'lifecontrol_owner') THEN
        CREATE ROLE lifecontrol_owner LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'lifecontrol_app') THEN
        CREATE ROLE lifecontrol_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
    END IF;
END $$;
