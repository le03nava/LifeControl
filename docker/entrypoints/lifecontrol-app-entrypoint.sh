#!/bin/bash
# ============================================
# LifeControl - LifeControl API Entrypoint Wrapper
# ============================================
# Reads the app DATABASE_PASSWORD from a secret file mounted at
# /run/secrets/lifecontrol_postgres_password and the Keycloak admin client
# secret from /run/secrets/keycloak_admin_client_secret, then exports the real
# DATABASE_PASSWORD / KEYCLOAK_ADMIN_CLIENT_SECRET env vars that the Spring
# Boot app expects, and execs the image entrypoint (java -jar) so signals and
# pid-1 semantics survive.
#
# The Java launch command mirrors life-control-api/Dockerfile ENTRYPOINT.
# This wrapper contains NO secret content.
# ============================================

set -euo pipefail

secret_file="/run/secrets/lifecontrol_postgres_password"
admin_secret_file="/run/secrets/keycloak_admin_client_secret"

if [ ! -s "$secret_file" ]; then
	echo "FATAL: $secret_file is missing or empty" >&2
	exit 1
fi

export DATABASE_PASSWORD="$(cat "$secret_file")"

if [ ! -s "$admin_secret_file" ]; then
	echo "FATAL: $admin_secret_file is missing or empty" >&2
	exit 1
fi

export KEYCLOAK_ADMIN_CLIENT_SECRET="$(cat "$admin_secret_file")"

exec java ${JAVA_OPTS:-} -jar /app/app.jar