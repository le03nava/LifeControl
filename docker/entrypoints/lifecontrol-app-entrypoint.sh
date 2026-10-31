#!/bin/sh
# ============================================
# LifeControl - LifeControl API Entrypoint Wrapper
# ============================================
# Turns the mounted secrets into the two database logins ADR-0005 decision 3 requires, then execs the
# image entrypoint (java -jar) so signals and pid-1 semantics survive.
#
#   * the pool  -> DATABASE_USERNAME / DATABASE_PASSWORD -> spring.datasource
#     (life-control-api/src/main/resources/application.properties). It logs in as `lifecontrol_app`,
#     which owns nothing and carries no BYPASSRLS, so every ADR-0005 policy applies to the connection
#     the application actually uses. DATABASE_PASSWORD is docker/secrets/lifecontrol_app_password.
#   * Flyway    -> FLYWAY_USERNAME / FLYWAY_PASSWORD       -> spring.flyway (same file). It logs in as
#     `lifecontrol_owner`, the owner of every object and the only login allowed to run DDL.
#     FLYWAY_PASSWORD is docker/secrets/lifecontrol_postgres_password.
#
# The two logins are distinct on purpose. Flyway needs the owner, and the runtime must not have it: a
# pool that connects as the owner would carry the credential that owns the tables, which is the thing
# ADR-0005 removes. The role *names* come from compose (a role name is not a secret, so the template
# declares them); the passwords are read here, from the secret files, and never from a `.env`.
#
# The Keycloak admin client secret is exported the same way and for the same reason.
#
# The three secret paths are overridable through the `*_FILE` variables below, with the real mount path
# as the default: that is what lets docker/scripts/tests/app-credential-split.test.sh drive this file
# against a throwaway directory instead of the container's /run/secrets.
#
# POSIX sh on purpose: the runtime image is eclipse-temurin alpine (no bash).
# The Java launch command mirrors life-control-api/Dockerfile ENTRYPOINT.
# This wrapper contains NO secret content.
# ============================================

set -eu

owner_secret_file="${LIFECONTROL_OWNER_PASSWORD_FILE:-/run/secrets/lifecontrol_postgres_password}"
app_secret_file="${LIFECONTROL_APP_PASSWORD_FILE:-/run/secrets/lifecontrol_app_password}"
admin_secret_file="${KEYCLOAK_ADMIN_CLIENT_SECRET_FILE:-/run/secrets/keycloak_admin_client_secret}"

if [ ! -s "$owner_secret_file" ]; then
	echo "FATAL: $owner_secret_file is missing or empty" >&2
	exit 1
fi

if [ ! -s "$app_secret_file" ]; then
	echo "FATAL: $app_secret_file is missing or empty" >&2
	exit 1
fi

if [ ! -s "$admin_secret_file" ]; then
	echo "FATAL: $admin_secret_file is missing or empty" >&2
	exit 1
fi

export DATABASE_PASSWORD="$(cat "$app_secret_file")"
export FLYWAY_PASSWORD="$(cat "$owner_secret_file")"
export KEYCLOAK_ADMIN_CLIENT_SECRET="$(cat "$admin_secret_file")"

exec java ${JAVA_OPTS:-} -jar /app/app.jar
