#!/bin/bash
# ============================================
# LifeControl - PostgreSQL Entrypoint Wrapper
# ============================================
# Reads the DB password from a secret file mounted at /run/secrets/<name>
# (selected via the LC_SECRET_NAME env var), exports the real POSTGRES_PASSWORD
# env var that the postgres image entrypoint expects, then execs it so signals
# and pid-1 semantics survive.
#
# This wrapper contains NO secret content.
# ============================================

set -euo pipefail

secret_name="${LC_SECRET_NAME:?LC_SECRET_NAME must be set}"
secret_file="/run/secrets/${secret_name}"

if [ ! -s "$secret_file" ]; then
	echo "FATAL: $secret_file is missing or empty" >&2
	exit 1
fi

export POSTGRES_PASSWORD="$(cat "$secret_file")"
unset POSTGRES_PASSWORD_FILE

exec /usr/local/bin/docker-entrypoint.sh "$@"
