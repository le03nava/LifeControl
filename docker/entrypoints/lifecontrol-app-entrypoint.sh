#!/bin/sh
# ============================================
# LifeControl - LifeControl API Entrypoint Wrapper
# ============================================
# Reads the app DATABASE_PASSWORD from a secret file mounted at
# /run/secrets/lifecontrol_postgres_password, the pool credential from
# /run/secrets/lifecontrol_app_password and the Keycloak admin client secret from
# /run/secrets/keycloak_admin_client_secret, then exports the real
# DATABASE_PASSWORD / DATABASE_APP_PASSWORD / KEYCLOAK_ADMIN_CLIENT_SECRET env vars
# that the Spring Boot app expects, and execs the image entrypoint (java -jar) so
# signals and pid-1 semantics survive.
#
# Two database credentials, two jobs (ADR-0005, decision 3): the owner credential
# owns the objects and runs Flyway, the app credential owns nothing and is what the
# connection pool must connect with. Both are exported here and the launch path is
# unchanged: which one feeds spring.datasource and which one feeds spring.flyway is
# the next slice, not this wrapper's decision.
#
# POSIX sh on purpose: the runtime image is eclipse-temurin alpine (no bash).
# The Java launch command mirrors life-control-api/Dockerfile ENTRYPOINT.
# This wrapper contains NO secret content.
# ============================================

set -eu

secret_file="/run/secrets/lifecontrol_postgres_password"
app_secret_file="/run/secrets/lifecontrol_app_password"
admin_secret_file="/run/secrets/keycloak_admin_client_secret"

if [ ! -s "$secret_file" ]; then
	echo "FATAL: $secret_file is missing or empty" >&2
	exit 1
fi

export DATABASE_PASSWORD="$(cat "$secret_file")"

# Not consumed yet, and required anyway: an API that starts without the pool credential would ship the
# owner's identity to every connection, which is the thing ADR-0005 removes.
if [ ! -f "$app_secret_file" ] || [ ! -s "$app_secret_file" ]; then
	echo "FATAL: $app_secret_file is missing or empty" >&2
	exit 1
fi

export DATABASE_APP_PASSWORD="$(cat "$app_secret_file")"

if [ ! -s "$admin_secret_file" ]; then
	echo "FATAL: $admin_secret_file is missing or empty" >&2
	exit 1
fi

export KEYCLOAK_ADMIN_CLIENT_SECRET="$(cat "$admin_secret_file")"

exec java ${JAVA_OPTS:-} -jar /app/app.jar