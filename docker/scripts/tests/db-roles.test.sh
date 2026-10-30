#!/bin/bash
# ============================================
# LifeControl - the two database roles and the app credential (ADR-0005, T1)
# ============================================
# ADR-0005 decision 3 splits the database credential in two, and the reason is one PostgreSQL rule: a
# **superuser always bypasses Row-Level Security**, so policies installed over a connection made as a
# superuser would enforce nothing. `lifecontrol_owner` owns every object and runs Flyway;
# `lifecontrol_app` owns nothing, may not bypass RLS, and is the role the pool will connect as (that
# switch is the next slice, which is why no Java is touched here).
#
# Pinned here, in the order a reader needs them:
#
#   1. the SQL is one body, it renames the pre-ADR role instead of creating a new one (ownership must
#      survive), the extension is created *before* the superuser flag is dropped (V21 needs superuser),
#      and the app role is exactly `LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS`;
#   2. the SQL grants CONNECT and USAGE and nothing else: table, sequence and ALTER DEFAULT PRIVILEGES
#      grants belong to the migration that creates the policies, and asserting their absence is how
#      this slice's boundary stays closed;
#   3. the credential is a secret file, read inside the container — never an argv, never an env value,
#      never a `.env` line, never a literal in the SQL;
#   4. the init wrapper refuses an absent, empty or CHANGEME credential, and resolves which role can act
#      as the owner instead of trusting a `POSTGRES_USER` that a rename made stale;
#   5. db-roles.sh execs that same wrapper in the postgres container (one body of SQL, one entry point
#      for a volume that already holds data), reports an env file still naming the old role, and never
#      writes the env file it read;
#   6. `deploy.sh <env> start` starts postgres alone, applies the roles, and only then starts the rest —
#      failing the start when the roles are not in place;
#   7. both compose files wire the init script, the SQL and the app secret, and every consumer of the
#      role name (postgres, the API, and `postgres-exporter-lifecontrol`, the second consumer of the
#      same credential) moved to the new name in the same change;
#   8. the API wrapper exports both credentials and still launches the same command.
#
# No Docker daemon, no network, no psql, no repository file written: the wrapper cases run against
# throwaway trees under `mktemp -d` with a stub `psql` on PATH, and db-roles.sh runs against a stub
# `docker` that records the command it was given.
#
# Run: ./docker/scripts/tests/db-roles.test.sh
# Exits 0 only when every check passes.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../_common.sh
source "$SCRIPT_DIR/../_common.sh"

DOCKER_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
SQL_FILE="$DOCKER_DIR/postgres-init/lifecontrol-roles.sql"
ROLE_WRAPPER="$DOCKER_DIR/postgres-init/10-lifecontrol-roles.sh"
DB_ROLES_SH="$DOCKER_DIR/scripts/db-roles.sh"
DEPLOY_SH="$DOCKER_DIR/scripts/deploy.sh"
COMPOSE_BASE="$DOCKER_DIR/docker-compose.yml"
COMPOSE_PROD="$DOCKER_DIR/docker-compose.prod.yml"
ENTRYPOINT="$DOCKER_DIR/entrypoints/lifecontrol-app-entrypoint.sh"
SECRET_TEMPLATE="$DOCKER_DIR/secrets/lifecontrol_app_password.template"

PASS_COUNT=0
FAIL_COUNT=0

TMP_BASE="$(mktemp -d)"
# shellcheck disable=SC2329  # invoked by the EXIT trap below, which shellcheck cannot trace
cleanup() {
	rm -rf "$TMP_BASE"
}
trap cleanup EXIT

pass() {
	printf 'PASS: %s\n' "$1"
	PASS_COUNT=$((PASS_COUNT + 1))
}

fail() {
	printf 'FAIL: %s\n' "$1"
	FAIL_COUNT=$((FAIL_COUNT + 1))
}

check_equal() {
	if [ "$2" = "$3" ]; then
		pass "$1 ($3)"
	else
		fail "$1 (expected $2, got $3)"
	fi
}

assert_contains() {
	if printf '%s' "$2" | grep -qF -- "$3"; then
		pass "$1"
	else
		fail "$1 (the output never mentioned '$3')"
		printf '%s\n' "$2" | sed 's/^/        /' | head -10
	fi
}

assert_absent() {
	if printf '%s' "$2" | grep -qF -- "$3"; then
		fail "$1 ('$3' appeared)"
		printf '%s\n' "$2" | sed 's/^/        /' | head -10
	else
		pass "$1"
	fi
}

# assert_file_contains <description> <file> <needle>
assert_file_contains() {
	if [ ! -f "$2" ]; then
		fail "$1 (missing ${2#"$DOCKER_DIR"/})"
	elif grep -qF -- "$3" "$2" >/dev/null; then
		pass "$1"
	else
		fail "$1 (${2#"$DOCKER_DIR"/} never mentions '$3')"
	fi
}

# assert_file_absent <description> <file> <needle>
assert_file_absent() {
	if [ ! -f "$2" ]; then
		fail "$1 (missing ${2#"$DOCKER_DIR"/})"
	elif grep -qF -- "$3" "$2" >/dev/null; then
		fail "$1 (${2#"$DOCKER_DIR"/} mentions '$3')"
	else
		pass "$1"
	fi
}

# sql_code <file>: the SQL with its `--` comment lines removed. The file explains the very statements
# the negative checks look for (a comment names ALTER DEFAULT PRIVILEGES to say it is deferred), so a
# check that read the comments would report the explanation as the defect.
sql_code() {
	awk '!/^[[:space:]]*--/' "$1"
}

# service_block <compose file> <service name>
service_block() {
	awk -v svc="$2" '
		$0 == "  " svc ":" { inside = 1; print; next }
		inside && /^  [a-zA-Z0-9_-]+:$/ { inside = 0 }
		inside { print }
	' "$1"
}

# ------------------------------------------
# 1. One body of SQL: the rename, the extension ordering and the two role shapes.
# ------------------------------------------
echo "== 1. roles: rename keeps ownership, extension before NOSUPERUSER, app owns and bypasses nothing"

if [ ! -f "$SQL_FILE" ]; then
	fail "missing docker/postgres-init/lifecontrol-roles.sql"
else
	code="$(sql_code "$SQL_FILE")"
	assert_contains "the pre-ADR role is renamed, not recreated" "$code" \
		"ALTER ROLE lifecontrol_user RENAME TO lifecontrol_owner"
	assert_contains "the rename is guarded by an existence check on the old name" "$code" \
		"pg_roles WHERE rolname = 'lifecontrol_user'"
	assert_contains "the rename is guarded by an existence check on the new name" "$code" \
		"pg_roles WHERE rolname = 'lifecontrol_owner'"

	# The one ordering that makes a fresh volume work: V21 needs superuser, so the extension is created
	# while the bootstrap session still has it.
	extension_line="$(printf '%s\n' "$code" | grep -n 'CREATE EXTENSION IF NOT EXISTS btree_gist' | cut -d: -f1)"
	nosuper_line="$(printf '%s\n' "$code" | grep -n 'ALTER ROLE lifecontrol_owner NOSUPERUSER' | cut -d: -f1)"
	if [ -n "$extension_line" ] && [ -n "$nosuper_line" ] && [ "$extension_line" -lt "$nosuper_line" ]; then
		pass "the extension is created before the owner loses superuser (line $extension_line < $nosuper_line)"
	else
		fail "the extension must be created BEFORE 'ALTER ROLE lifecontrol_owner NOSUPERUSER' (found $extension_line / $nosuper_line)"
	fi
	assert_file_contains "the file says why that order is not cosmetic" "$SQL_FILE" \
		"V21__employee_contracts.sql:27"

	# The SQL needs superuser on EVERY run, not only the first: the owner is NOSUPERUSER on purpose, and
	# initdb creates exactly one superuser (POSTGRES_USER) with no `postgres` role to fall back to. Without
	# a role of its own, the second run cannot ALTER ROLE at all.
	bootstrap_line="$(printf '%s\n' "$code" | grep -n 'CREATE ROLE lifecontrol_bootstrap' | cut -d: -f1)"
	if [ -n "$bootstrap_line" ] && [ -n "$extension_line" ] && [ "$bootstrap_line" -lt "$extension_line" ]; then
		pass "the bootstrap superuser is created before the extension step (line $bootstrap_line < $extension_line)"
	else
		fail "lifecontrol_bootstrap must be created BEFORE the extension step (found '$bootstrap_line' / '$extension_line')"
	fi
	assert_contains "the bootstrap role is a superuser that can log in" "$code" \
		"CREATE ROLE lifecontrol_bootstrap SUPERUSER LOGIN"
	bootstrap_with_password="$(printf '%s\n' "$code" | grep 'lifecontrol_bootstrap' | grep -c 'PASSWORD' || true)"
	check_equal "the bootstrap role is created with no password (the local socket is trust, TCP needs scram)" \
		"0" "$bootstrap_with_password"
	assert_file_contains "the SQL says why the bootstrap role exists" "$SQL_FILE" \
		"superuser on every run"

	# The read-back stays scoped to the two roles it names. lifecontrol_bootstrap IS a superuser by
	# design, so demanding "not superuser" of it would raise on every run.
	readback="$(sed -n '/read the end state back/,$p' "$SQL_FILE" | grep -v '^[[:space:]]*--')"
	assert_absent "the read-back demands nothing of the bootstrap role" "$readback" "lifecontrol_bootstrap"

	assert_contains "the app role is created" "$code" "CREATE ROLE lifecontrol_app"
	assert_contains "the app role converges to the ADR's attribute set" "$code" \
		"ALTER ROLE lifecontrol_app WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS PASSWORD :'app_password'"
	assert_absent "the owner is not left as a superuser" "$code" "ALTER ROLE lifecontrol_owner SUPERUSER"

	assert_contains "the app role gets CONNECT on the database it lives in" "$code" \
		"GRANT CONNECT ON DATABASE %I TO lifecontrol_app"
	assert_contains "the app role gets USAGE on schema public" "$code" \
		"GRANT USAGE ON SCHEMA public TO lifecontrol_app"

	# This slice's boundary, asserted as absence: the tables do not exist yet, so a table grant here
	# would be a no-op on a first install and a real grant on the second.
	assert_absent "no table grant is written before the tables exist" "$code" "ON ALL TABLES IN SCHEMA public"
	assert_absent "no sequence grant is written before the sequences exist" "$code" "ON ALL SEQUENCES IN SCHEMA public"
	assert_absent "no default privileges are written here (they belong to the policy migration)" "$code" \
		"ALTER DEFAULT PRIVILEGES"
	assert_absent "no policy is created here" "$code" "CREATE POLICY"

	# The password never becomes a literal: psql reads it from the mounted secret, and the value is
	# substituted by psql, so the file that lands in git carries no credential.
	assert_contains "the password is read from the mounted secret by psql itself" "$code" \
		'cat /run/secrets/lifecontrol_app_password'
	assert_absent "the SQL never holds a password literal" "$code" "PASSWORD '"

	# The exit code is not the evidence: the file reads the end state back and raises.
	assert_contains "the SQL verifies the end state instead of trusting the exit code" "$code" \
		"role attributes did not converge"
fi

# ------------------------------------------
# 2. The init wrapper: credential refusals and superuser resolution, driven with a stub psql.
# ------------------------------------------
echo "== 2. init wrapper: refuses a bad credential, resolves the owner role, runs the one SQL file"

WRAP_DIR="$TMP_BASE/wrapper"
mkdir -p "$WRAP_DIR/bin"
printf 'role sql\n' >"$WRAP_DIR/roles.sql"
printf 'app-secret\n' >"$WRAP_DIR/password"
printf 'CHANGEME\n' >"$WRAP_DIR/changeme"
: >"$WRAP_DIR/empty"

cat >"$WRAP_DIR/bin/psql" <<'STUB'
#!/bin/sh
username=""
query=""
previous=""
for argument in "$@"; do
	if [ "$previous" = "--username" ]; then username="$argument"; fi
	if [ "$previous" = "--command" ]; then query="$argument"; fi
	previous="$argument"
done
printf 'username=%s args=%s\n' "$username" "$*" >>"$PSQL_LOG"

# A candidate whose role does not exist, or cannot authenticate, at all.
case " ${UNREACHABLE_USERS:-} " in
*" $username "*) exit 1 ;;
esac

# The probe: one row only for a role that is a superuser. Anything else prints no row and exits 0, which
# is the shape of a role that connects but may not ALTER ROLE -- the defect this stub exists to model.
case "$query" in
*rolsuper*)
	case " ${SUPERUSERS:-} " in
	*" $username "*) printf '1\n' ;;
	esac
	exit 0
	;;
esac

exit 0
STUB
chmod +x "$WRAP_DIR/bin/psql"

# run_wrapper <password file> <sql file> [extra env assignments...]
run_wrapper() {
	local password_file="$1" sql="$2"
	shift 2
	: >"$WRAP_DIR/psql.log"
	(
		PATH="$WRAP_DIR/bin:$PATH" PSQL_LOG="$WRAP_DIR/psql.log" \
			LIFECONTROL_APP_PASSWORD_FILE="$password_file" LIFECONTROL_ROLES_SQL="$sql" \
			POSTGRES_DB="lifecontrol" "$@" "$ROLE_WRAPPER"
	) >"$WRAP_DIR/out.log" 2>&1
	LAST_RC=$?
	LAST_TEXT="$(cat "$WRAP_DIR/out.log")"
	LAST_CALLS="$(cat "$WRAP_DIR/psql.log")"
}

LAST_RC=0
LAST_TEXT=""
LAST_CALLS=""

# The order is frozen: the maintenance role first, then the image's default superuser, then the declared
# one, then the two names the owner can have. A candidate appended anywhere else would be a role nobody
# reviewed.
candidate_order="$(sed -n 's/^for candidate in \(.*\); do$/\1/p' "$ROLE_WRAPPER" | head -1)"
check_equal "the candidate order is bootstrap, postgres, POSTGRES_USER, owner, legacy" \
	'lifecontrol_bootstrap postgres "${POSTGRES_USER:-}" lifecontrol_owner lifecontrol_user' "$candidate_order"

# A fresh volume: initdb made POSTGRES_USER the only superuser and lifecontrol_bootstrap does not exist
# yet, so the bootstrap candidate misses and the declared role is used.
run_wrapper "$WRAP_DIR/password" "$WRAP_DIR/roles.sql" env POSTGRES_USER=lifecontrol_owner SUPERUSERS=lifecontrol_owner
check_equal "a fresh volume connects as POSTGRES_USER" "0" "$LAST_RC"
assert_contains "it runs the SQL file it was pointed at" "$LAST_CALLS" "--file $WRAP_DIR/roles.sql"
assert_contains "it stops on the first SQL error" "$LAST_CALLS" "--set ON_ERROR_STOP=1"
assert_contains "the probe asks pg_roles for rolsuper, not merely for connectivity" "$LAST_CALLS" "rolsuper"
assert_contains "the probe is asked about current_user" "$LAST_CALLS" "current_user"
assert_contains "on a fresh volume it uses the declared bootstrap role" "$LAST_CALLS" "username=lifecontrol_owner"

# The second run, which is what this correction is about: the owner exists and connects, but it is
# NOSUPERUSER on purpose, so the maintenance role is the only one that may ALTER ROLE from now on.
run_wrapper "$WRAP_DIR/password" "$WRAP_DIR/roles.sql" env POSTGRES_USER=lifecontrol_owner SUPERUSERS=lifecontrol_bootstrap
check_equal "the second run succeeds" "0" "$LAST_RC"
assert_contains "the second run connects as lifecontrol_bootstrap" "$LAST_CALLS" "username=lifecontrol_bootstrap"

# A candidate that connects but is not a superuser must be skipped instead of used.
run_wrapper "$WRAP_DIR/password" "$WRAP_DIR/roles.sql" env POSTGRES_USER=lifecontrol_app SUPERUSERS=lifecontrol_owner
check_equal "a connectable but non-superuser candidate does not stop the run" "0" "$LAST_RC"
assert_contains "the non-superuser POSTGRES_USER really was tried" "$LAST_CALLS" "username=lifecontrol_app"
assert_contains "it skips it and uses the superuser owner instead" "$LAST_CALLS" "username=lifecontrol_owner"

# An existing volume whose .env still says lifecontrol_user: that role was renamed, so the declaration is
# stale AND not a superuser. Both have to be skipped.
run_wrapper "$WRAP_DIR/password" "$WRAP_DIR/roles.sql" env POSTGRES_USER=lifecontrol_user UNREACHABLE_USERS=lifecontrol_user SUPERUSERS=lifecontrol_owner
check_equal "a stale POSTGRES_USER does not stop the run" "0" "$LAST_RC"
assert_contains "the wrapper falls back to the renamed owner" "$LAST_CALLS" "username=lifecontrol_owner"

# No superuser at all: the deployment must say so where an operator can read it, instead of failing
# inside the SQL with a permission error that names a statement nobody can act on.
run_wrapper "$WRAP_DIR/password" "$WRAP_DIR/roles.sql" env POSTGRES_USER=lifecontrol_owner SUPERUSERS=""
check_equal "no superuser fails the run" "1" "$LAST_RC"
assert_contains "the failure says no superuser could connect" "$LAST_TEXT" "no superuser"
assert_contains "the failure names the role an operator has to look at" "$LAST_TEXT" "lifecontrol_bootstrap"

run_wrapper "$WRAP_DIR/missing" "$WRAP_DIR/roles.sql" env POSTGRES_USER=lifecontrol_owner
check_equal "an absent app credential fails" "1" "$LAST_RC"
assert_contains "the failure says the file is missing" "$LAST_TEXT" "missing or empty"
check_equal "an absent credential never reaches psql" "" "$LAST_CALLS"

run_wrapper "$WRAP_DIR/empty" "$WRAP_DIR/roles.sql" env POSTGRES_USER=lifecontrol_owner
check_equal "an empty app credential fails" "1" "$LAST_RC"

run_wrapper "$WRAP_DIR/changeme" "$WRAP_DIR/roles.sql" env POSTGRES_USER=lifecontrol_owner
check_equal "a CHANGEME app credential fails" "1" "$LAST_RC"
assert_contains "the failure names CHANGEME" "$LAST_TEXT" "CHANGEME"

run_wrapper "$WRAP_DIR/password" "$WRAP_DIR/no-such.sql" env POSTGRES_USER=lifecontrol_owner
check_equal "an absent SQL file fails" "1" "$LAST_RC"
assert_contains "the failure names the SQL file" "$LAST_TEXT" "$WRAP_DIR/no-such.sql"

# ------------------------------------------
# 3. db-roles.sh: one exec into the container, the stale-name report, no write to the env file.
# ------------------------------------------
echo "== 3. db-roles.sh execs the same wrapper, reports a stale role name, never writes the env file"

WORK="$TMP_BASE/db-roles"
mkdir -p "$WORK/scripts" "$WORK/bin"
cp "$DOCKER_DIR/scripts/db-roles.sh" "$DOCKER_DIR/scripts/_common.sh" "$WORK/scripts/"

cat >"$WORK/bin/docker" <<'STUB'
#!/bin/sh
printf '%s\n' "$*" >>"$DOCKER_LOG"
if [ -n "${FAIL_ON:-}" ]; then
	case "$*" in
	*"$FAIL_ON"*) exit 1 ;;
	esac
fi
exit 0
STUB
chmod +x "$WORK/bin/docker"

printf 'LIFECONTROL_POSTGRES_USER=lifecontrol_user\nLIFECONTROL_DATABASE_USERNAME=lifecontrol_user\n' >"$WORK/.env.dev"

run_db_roles() {
	: >"$WORK/docker.log"
	(
		PATH="$WORK/bin:$PATH" DOCKER_LOG="$WORK/docker.log" "$@" \
			"$WORK/scripts/db-roles.sh" dev
	) >"$WORK/out.log" 2>&1
	DB_RC=$?
	DB_TEXT="$(sed 's/\x1b\[[0-9;]*m//g' "$WORK/out.log")"
	DB_CALLS="$(cat "$WORK/docker.log")"
}

DB_RC=0
DB_TEXT=""
DB_CALLS=""

env_before="$(cksum <"$WORK/.env.dev")"
run_db_roles env
check_equal "db-roles.sh succeeds when the container accepts the roles" "0" "$DB_RC"
assert_contains "it starts the postgres service's container before exec'ing into it" "$DB_CALLS" \
	"exec -T lifecontrol-postgres"
assert_contains "it runs the init wrapper, not a second copy of the SQL" "$DB_CALLS" \
	"/docker-entrypoint-initdb.d/10-lifecontrol-roles.sh"
assert_contains "it waits for the database, not for the container to merely exist" "$DB_CALLS" "pg_isready"
check_equal "it never writes the env file it read" "$env_before" "$(cksum <"$WORK/.env.dev")"
assert_contains "it reports the key still naming the old role" "$DB_TEXT" \
	"LIFECONTROL_POSTGRES_USER=lifecontrol_user is stale"
assert_contains "it reports the API username the same way" "$DB_TEXT" \
	"LIFECONTROL_DATABASE_USERNAME=lifecontrol_user is stale"
assert_contains "it names the replacement role" "$DB_TEXT" "lifecontrol_owner"
assert_contains "it names the exporter as the other consumer that breaks" "$DB_TEXT" "postgres exporter"

run_db_roles env FAIL_ON="/docker-entrypoint-initdb.d/10-lifecontrol-roles.sh"
check_equal "a failing role script fails db-roles.sh" "1" "$DB_RC"
assert_contains "the failure says the roles are not in place" "$DB_TEXT" "the two roles are not in place"

# ------------------------------------------
# 4. deploy.sh wires it between postgres and the application, and aborts on failure.
# ------------------------------------------
echo "== 4. deploy.sh start: postgres, then roles, then everything else — aborting on failure"

start_body="$(
	awk '/^start_services\(\) \{/ { inside = 1 } inside { print } inside && /^}$/ { exit }' "$DEPLOY_SH"
)"
postgres_up_line="$(printf '%s\n' "$start_body" | grep -n 'compose_run up -d lifecontrol-postgres' | cut -d: -f1 | head -1)"
roles_line="$(printf '%s\n' "$start_body" | grep -n '\"\$SCRIPT_DIR/db-roles.sh\"' | cut -d: -f1 | head -1)"
all_up_line="$(printf '%s\n' "$start_body" | grep -n 'compose_run up -d$' | cut -d: -f1 | head -1)"
if [ -n "$postgres_up_line" ] && [ -n "$roles_line" ] && [ -n "$all_up_line" ] &&
	[ "$postgres_up_line" -lt "$roles_line" ] && [ "$roles_line" -lt "$all_up_line" ]; then
	pass "postgres starts alone ($postgres_up_line), then the roles ($roles_line), then the stack ($all_up_line)"
else
	fail "start_services must start postgres, apply the roles, then start the stack (found $postgres_up_line / $roles_line / $all_up_line)"
fi
assert_contains "the roles are applied for the environment being started" "$start_body" 'db-roles.sh" "$ENV"'
assert_contains "a failed role setup aborts the start" "$start_body" "aborting start"
assert_absent "the prod validation gate did not move" "$start_body" "validate-env.sh\" dev"

# `up` and `restart` both reach start_services, so the roles are applied on every entry that starts
# containers -- one place, not three call sites.
up_site="$(
	awk '/^up\)$/ { inside = 1 } inside { print } inside && /;;/ { exit }' "$DEPLOY_SH"
)"
assert_contains "the 'up' command goes through start_services" "$up_site" "start_services"
assert_file_contains "restart reuses start_services" "$DEPLOY_SH" "restart_services() {"

# ------------------------------------------
# 5. compose wiring, in both files, and the second consumer of the same role name.
# ------------------------------------------
echo "== 5. compose: init script, SQL and app secret mounted; every consumer uses the new role name"

for compose in "$COMPOSE_BASE" "$COMPOSE_PROD"; do
	file="$(basename "$compose")"
	postgres="$(service_block "$compose" lifecontrol-postgres)"
	api="$(service_block "$compose" lifecontrol-api)"

	assert_contains "$file: postgres runs the init wrapper from the image's init directory" "$postgres" \
		"./postgres-init/10-lifecontrol-roles.sh:/docker-entrypoint-initdb.d/10-lifecontrol-roles.sh:ro"
	assert_contains "$file: the SQL is mounted outside the init directory (the glob would run it twice)" \
		"$postgres" "./postgres-init/lifecontrol-roles.sql:/opt/lifecontrol/lifecontrol-roles.sql:ro"
	assert_contains "$file: postgres mounts the app credential read-only to create the role" "$postgres" \
		"./secrets/lifecontrol_app_password:/run/secrets/lifecontrol_app_password:ro"
	assert_contains "$file: the API mounts the pool credential read-only" "$api" \
		"./secrets/lifecontrol_app_password:/run/secrets/lifecontrol_app_password:ro"
	assert_file_contains "$file: the postgres service defaults to the owner role" "$compose" "LIFECONTROL_POSTGRES_USER:-lifecontrol_owner"
	assert_file_absent "$file: the pre-ADR owner never survives as a default" "$compose" "lifecontrol_user"
done

exporter_block="$(service_block "$COMPOSE_BASE" postgres-exporter-lifecontrol)"
assert_contains "the LifeControl exporter follows the rename (it reads the same credential)" \
	"$exporter_block" 'DATA_SOURCE_USER: ${LIFECONTROL_POSTGRES_USER:-lifecontrol_owner}'
prod_exporter_block="$(service_block "$COMPOSE_PROD" postgres-exporter-lifecontrol)"
assert_contains "the prod exporter follows it too" "$prod_exporter_block" \
	'DATA_SOURCE_USER: ${LIFECONTROL_POSTGRES_USER:-lifecontrol_owner}'

assert_file_contains "the template declares the owner name a fresh volume creates" \
	"$DOCKER_DIR/.env.template" "LIFECONTROL_POSTGRES_USER=lifecontrol_owner"
assert_file_contains "the template points the API at the owner until the pool switch" \
	"$DOCKER_DIR/.env.template" "LIFECONTROL_DATABASE_USERNAME=lifecontrol_owner"

# ------------------------------------------
# 6. The credential is a secret file, and the API wrapper exports both.
# ------------------------------------------
echo "== 6. the credential travels as a secret file and the API wrapper exports both halves"

if [ ! -f "$SECRET_TEMPLATE" ]; then
	fail "missing docker/secrets/lifecontrol_app_password.template"
else
	assert_contains "the template carries the CHANGEME placeholder setup-env materializes" \
		"$(cat "$SECRET_TEMPLATE")" "CHANGEME"
	non_comment="$(grep -vE '^[[:space:]]*(#|$)' "$SECRET_TEMPLATE")"
	check_equal "the materialized value is one line" "CHANGEME" "$non_comment"
fi

assert_file_contains "the API wrapper reads the pool credential" "$ENTRYPOINT" "/run/secrets/lifecontrol_app_password"
assert_file_contains "it exports it as DATABASE_APP_PASSWORD" "$ENTRYPOINT" "DATABASE_APP_PASSWORD="
assert_file_contains "it still exports the owner credential as DATABASE_PASSWORD" "$ENTRYPOINT" "DATABASE_PASSWORD="
assert_file_contains "it still refuses a missing owner credential" "$ENTRYPOINT" "FATAL: \$secret_file is missing or empty"
assert_file_contains "the launch command is unchanged" "$ENTRYPOINT" "exec java"
assert_absent "no compose file or template holds the app password as a value" \
	"$(cat "$COMPOSE_BASE" "$COMPOSE_PROD" "$DOCKER_DIR/.env.template")" "lifecontrol_app_password="

# ------------------------------------------
# 7. The maintenance superuser never becomes an application credential.
#    It exists because the owner is NOSUPERUSER on purpose and the role SQL needs superuser on every run.
#    Its credential must not be reachable from anything the stack runs: no compose file mounts or exports
#    it, the API wrapper never names it, and no secret file carries it.
# ------------------------------------------
echo "== 7. lifecontrol_bootstrap is a maintenance role, never a credential a service holds"

for compose in "$COMPOSE_BASE" "$COMPOSE_PROD" "$DOCKER_DIR/docker-compose.override.yml"; do
	[ -f "$compose" ] || continue
	assert_file_absent "$(basename "$compose") never names the bootstrap role" "$compose" "lifecontrol_bootstrap"
done
assert_file_absent "the API wrapper never names the bootstrap role" "$ENTRYPOINT" "lifecontrol_bootstrap"
assert_file_absent "the env template declares no bootstrap role or credential" \
	"$DOCKER_DIR/.env.template" "lifecontrol_bootstrap"
assert_file_absent "db-roles.sh needs no knowledge of the bootstrap role" "$DB_ROLES_SH" "lifecontrol_bootstrap"
bootstrap_secrets=0
for secret in "$DOCKER_DIR/secrets"/*; do
	[ -e "$secret" ] || continue
	case "$(basename "$secret")" in
		*bootstrap*) bootstrap_secrets=$((bootstrap_secrets + 1)) ;;
	esac
done
check_equal "no secret file exists for the bootstrap role" "0" "$bootstrap_secrets"

# ------------------------------------------
# Summary
# ------------------------------------------
echo "------------------------------------------"
printf 'db-roles: %s passed, %s failed\n' "$PASS_COUNT" "$FAIL_COUNT"
if [ "$FAIL_COUNT" -ne 0 ]; then
	exit 1
fi
exit 0
