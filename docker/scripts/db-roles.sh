#!/bin/bash
# ============================================
# LifeControl - Database roles and grants (ADR-0005)
# ============================================
# Applies docker/postgres-init/lifecontrol-roles.sql to a database that already exists.
#
# The postgres image runs /docker-entrypoint-initdb.d only while it initializes an EMPTY data
# directory, and dev already has a filled one: without this script the two roles would exist on every
# fresh machine and on none of the existing ones. It execs the same init wrapper inside the container,
# so there is one body of SQL and one place where the roles are defined — no duplication between the
# fresh-volume path and this one.
#
# Idempotent by construction: the SQL renames only an existing lifecontrol_user, restates every role
# attribute and reads the end state back with a raise, so a second run is a no-op.
#
# `deploy.sh <env> start` calls this between "postgres is up" and "the application starts", and aborts
# the start when it fails: an application that connects without these roles cannot enforce a tenant.
#
# Run: ./docker/scripts/db-roles.sh [dev|staging|prod]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/_common.sh"

resolve_compose_env "${1:-dev}"

POSTGRES_SERVICE="lifecontrol-postgres"
ROLE_SCRIPT="/docker-entrypoint-initdb.d/10-lifecontrol-roles.sh"
WAIT_ATTEMPTS=${DB_ROLES_WAIT_ATTEMPTS:-60}
WAIT_SECONDS=${DB_ROLES_WAIT_SECONDS:-5}

# The role each key of the env file must name, and the role names that are stale in it. The rename and the
# pool switch are the SQL's and the API's job; what neither can do is edit a materialized `.env.<env>`,
# which nothing reconciles (`setup-env.sh` only creates the file when it is missing). A key holding a role
# name that no longer exists — or that puts the owner back into the pool, which silently disables every
# policy the owner is not exempt from — is reported here instead of failing later as an opaque
# authentication error, or succeeding as the wrong login.
OWNER_ROLE_NAME="lifecontrol_owner"
APP_ROLE_NAME="lifecontrol_app"
LEGACY_ROLE_NAME="lifecontrol_user"
# `LIFECONTROL_POSTGRES_USER` is the role the database is initialized with and the one Flyway logs in as;
# `LIFECONTROL_DATABASE_USERNAME` is the role the connection pool logs in as, and it must never be the
# owner (ADR-0005, decision 3).
ROLE_NAME_KEYS="LIFECONTROL_POSTGRES_USER LIFECONTROL_DATABASE_USERNAME"

print_status "Applying database roles and grants for $ENV (ADR-0005)..."

wait_for_postgres() {
	local attempt=1
	while [ "$attempt" -le "$WAIT_ATTEMPTS" ]; do
		# exec also fails while the container is not running yet, so one probe covers both "started"
		# and "accepting connections". `docker compose up -d <service>` has already returned.
		if compose_run exec -T "$POSTGRES_SERVICE" pg_isready -q >/dev/null 2>&1; then
			return 0
		fi
		print_status "Waiting for $POSTGRES_SERVICE ($attempt/$WAIT_ATTEMPTS)..."
		sleep "$WAIT_SECONDS"
		attempt=$((attempt + 1))
	done
	print_error "$POSTGRES_SERVICE did not accept connections after $WAIT_ATTEMPTS attempts"
	return 1
}

expected_role_for_key() {
	case "$1" in
	LIFECONTROL_DATABASE_USERNAME) printf '%s' "$APP_ROLE_NAME" ;;
	*) printf '%s' "$OWNER_ROLE_NAME" ;;
	esac
}

report_stale_role_names() {
	local key value expected reported=0
	for key in $ROLE_NAME_KEYS; do
		value="$(get_env_var "$key")"
		expected="$(expected_role_for_key "$key")"
		[ "$value" = "$expected" ] && continue
		# A custom role name is the operator's business; the three names this repository knows are
		# reported, because each of them is a known consumer pointed at the wrong login.
		case "$value" in
		"$LEGACY_ROLE_NAME" | "$OWNER_ROLE_NAME" | "$APP_ROLE_NAME") ;; *) continue ;; esac
		print_warning "$key=$value is stale: that key must name $expected"
		print_warning "  fix it with: sed -i 's/^$key=$value\$/$key=$expected/' $ENV_FILE"
		reported=1
	done
	if [ "$reported" -eq 1 ]; then
		print_warning "A consumer that logs in as a role other than the one its key names fails authentication, or succeeds as the wrong login: it is the postgres exporter (Prometheus target down) and, for LIFECONTROL_DATABASE_USERNAME, the API's pool, which must connect as $APP_ROLE_NAME or no policy applies to it."
	fi
}

main() {
	if ! wait_for_postgres; then
		return 1
	fi

	# The wrapper refuses an absent/CHANGEME credential and resolves which role can act as the owner,
	# so a failure here is either "the secret was never materialized" or a real SQL error — never a
	# silent no-op.
	if ! compose_run exec -T "$POSTGRES_SERVICE" "$ROLE_SCRIPT"; then
		print_error "Applying $ROLE_SCRIPT failed: the two roles are not in place."
		print_error "Read the psql output above; the SQL is docker/postgres-init/lifecontrol-roles.sql."
		return 1
	fi

	report_stale_role_names
	print_success "Database roles applied: $OWNER_ROLE_NAME (NOSUPERUSER) owns, lifecontrol_app (NOBYPASSRLS) connects"
	return 0
}

main "$@"
