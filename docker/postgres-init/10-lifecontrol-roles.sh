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
# This wrapper owns the one thing psql cannot check by itself: that the two credentials it is about to
# set exist and are real. The owner's password is no longer a side effect of initdb -- initdb's role is
# the maintenance one now (POSTGRES_USER=lifecontrol_bootstrap), so lifecontrol_owner is created and given
# a password by the SQL, from the secret this wrapper validates first.

set -euo pipefail

sql_file="${LIFECONTROL_ROLES_SQL:-/opt/lifecontrol/lifecontrol-roles.sql}"
app_password_file="${LIFECONTROL_APP_PASSWORD_FILE:-/run/secrets/lifecontrol_app_password}"
owner_password_file="${LIFECONTROL_OWNER_PASSWORD_FILE:-/run/secrets/lifecontrol_postgres_password}"
db_name="${POSTGRES_DB:-lifecontrol}"

# require_secret <file> <what>: an absent or empty file would reach psql as an empty password, and a
# CHANGEME would reach it as a working credential that anybody who read the template also knows. Both
# are refused here, before psql runs, because psql cannot tell either of them from a real one.
require_secret() {
	local file="$1" what="$2"
	if [ ! -f "$file" ] || [ ! -s "$file" ]; then
		echo "FATAL: $file is missing or empty — $what" >&2
		echo "FATAL: run ./docker/scripts/setup-env.sh and set the value" >&2
		exit 1
	fi
	if grep -q CHANGEME "$file"; then
		echo "FATAL: $file still holds CHANGEME — $what" >&2
		exit 1
	fi
}

require_secret "$app_password_file" "the application role's password (ADR-0005, the pool's credential)"
require_secret "$owner_password_file" "the owner role's password (ADR-0005, the credential Flyway logs in with)"

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

# lifecontrol_bootstrap first: it is the maintenance superuser, and since the arrangement was corrected it
# is also what initdb creates, so on a fresh volume the first candidate and the declared one are the same
# role. It stays first because it is the one role that may ALTER ROLE on the second run. Then the image's
# default superuser, then the declared one, then the two names the owner can have -- a volume initialized
# before the correction has lifecontrol_user renamed, and one initialized while the owner was POSTGRES_USER
# still has the owner carrying the flag, so both remain reachable candidates.
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
