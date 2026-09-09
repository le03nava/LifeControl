#!/bin/bash
# ============================================
# LifeControl - Keycloak Entrypoint Wrapper
# ============================================
# Keycloak 26.2.1 ignores *_FILE env vars, so this wrapper cat's the DB and
# admin passwords from secret files mounted at /run/secrets/<name> and exports
# the real env vars (KC_DB_PASSWORD, KC_BOOTSTRAP_ADMIN_PASSWORD) that kc.sh
# supports. Then execs the image entrypoint so signals/pid-1 survive.
#
# This wrapper contains NO secret content.
# ============================================

set -euo pipefail

db_secret="/run/secrets/keycloak_postgres_password"
admin_secret="/run/secrets/keycloak_admin_password"

if [ ! -s "$db_secret" ]; then
	echo "FATAL: $db_secret is missing or empty" >&2
	exit 1
fi
if [ ! -s "$admin_secret" ]; then
	echo "FATAL: $admin_secret is missing or empty" >&2
	exit 1
fi

export KC_DB_PASSWORD="$(cat "$db_secret")"
export KC_BOOTSTRAP_ADMIN_PASSWORD="$(cat "$admin_secret")"

exec /opt/keycloak/bin/kc.sh "$@"
