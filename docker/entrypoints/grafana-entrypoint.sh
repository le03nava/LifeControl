#!/bin/bash
# ============================================
# LifeControl - Grafana Entrypoint Wrapper
# ============================================
# Reads the admin password from a secret file mounted at /run/secrets/
# grafana_admin_password and exports the real GF_SECURITY_ADMIN_PASSWORD env
# var that the Grafana image entrypoint expects, then execs /run.sh so signals
# and pid-1 semantics survive.
#
# This wrapper contains NO secret content.
# ============================================

set -euo pipefail

admin_secret="/run/secrets/grafana_admin_password"

if [ ! -s "$admin_secret" ]; then
	echo "FATAL: $admin_secret is missing or empty" >&2
	exit 1
fi

export GF_SECURITY_ADMIN_PASSWORD="$(cat "$admin_secret")"

exec /run.sh "$@"
