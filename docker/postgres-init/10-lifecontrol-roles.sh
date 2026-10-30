#!/bin/bash
# ============================================
# LifeControl - applies lifecontrol-roles.sql from inside the database container
# ============================================
# Two entry points, one body of SQL:
#
#   * /docker-entrypoint-initdb.d, on a fresh volume, where the image's bootstrap session is still
#     superuser;
#   * docker/scripts/db-roles.sh <env>, which execs this same script on a volume that already holds
#     data, because the image's init directory runs only once per data directory.
#
# The SQL lives in lifecontrol-roles.sql next to this file in the repository and is mounted at
# /opt/lifecontrol/lifecontrol-roles.sql: it is deliberately NOT inside the init directory, whose glob
# would run it a second time after this script already did.
#
# This wrapper owns the one thing psql cannot check by itself: that the credential it is about to set
# exists and is real.

set -euo pipefail

sql_file="${LIFECONTROL_ROLES_SQL:-/opt/lifecontrol/lifecontrol-roles.sql}"
password_file="${LIFECONTROL_APP_PASSWORD_FILE:-/run/secrets/lifecontrol_app_password}"
db_name="${POSTGRES_DB:-lifecontrol}"

if [ ! -f "$password_file" ] || [ ! -s "$password_file" ]; then
	echo "FATAL: $password_file is missing or empty — run ./docker/scripts/setup-env.sh and set the value" >&2
	exit 1
fi
if grep -q CHANGEME "$password_file"; then
	echo "FATAL: $password_file still holds CHANGEME — set the real application password" >&2
	exit 1
fi
if [ ! -f "$sql_file" ]; then
	echo "FATAL: $sql_file is missing — it is mounted from docker/postgres-init/lifecontrol-roles.sql" >&2
	exit 1
fi

# The role this SQL needs must be a SUPERUSER, not merely a role that can connect. On the second run
# every declared candidate exists and connects -- and none of them may alter roles: lifecontrol_owner is
# NOSUPERUSER on purpose, and initdb created exactly one superuser (POSTGRES_USER) with no `postgres`
# role to fall back to. The probe therefore asks pg_roles, and anything but one row is a miss, so a
# candidate that connects but cannot ALTER ROLE is skipped instead of used.
SUPERUSER_PROBE="SELECT 1 FROM pg_roles WHERE rolname = current_user AND rolsuper"

# lifecontrol_bootstrap first: it is the maintenance superuser lifecontrol-roles.sql creates precisely so
# this file can be applied again. Then the image's default superuser, then the declared one, then the two
# names the owner can have.
for candidate in lifecontrol_bootstrap postgres "${POSTGRES_USER:-}" lifecontrol_owner lifecontrol_user; do
	[ -n "$candidate" ] || continue
	if [ "$(psql --username "$candidate" --dbname "$db_name" --no-psqlrc --no-password \
		--tuples-only --no-align --command "$SUPERUSER_PROBE" 2>/dev/null)" = "1" ]; then
		exec psql --username "$candidate" --dbname "$db_name" --no-psqlrc --no-password \
			--set ON_ERROR_STOP=1 --file "$sql_file"
	fi
done

echo "FATAL: no superuser could connect to $db_name, so the role SQL cannot be applied. Tried lifecontrol_bootstrap, postgres, '${POSTGRES_USER:-}', lifecontrol_owner, lifecontrol_user. If lifecontrol_bootstrap is missing, this volume predates it: re-create the container with an empty data dir, or create that role by hand with a role that is still a superuser." >&2
exit 1
