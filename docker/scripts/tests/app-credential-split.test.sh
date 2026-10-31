#!/bin/bash
# ============================================
# LifeControl - the delivery credential split (ADR-0005, decision 3, T6c)
# ============================================
# ADR-0005 decision 3 gives the database two logins with two jobs: `lifecontrol_owner` owns every object
# and runs Flyway, `lifecontrol_app` owns nothing, cannot bypass Row-Level Security, and is the only role
# the connection pool connects as. Until this slice the *suite* proved that and *delivery* did not: the
# wrapper handed the owner's own password to whatever `spring.datasource` reads, so every policy the test
# container enforced was enforced nowhere a customer could see it.
#
# This suite is the gate on that split, and it fails if it regresses in any of the ways it can regress:
#
#   1. the wrapper is *executed* against a throwaway secrets directory (its three mount paths are
#      overridable, which is the only reason this is a run and not a grep), and the environment the Java
#      process would receive is asserted: DATABASE_PASSWORD is the app secret, FLYWAY_PASSWORD is the
#      owner's, and neither is the other. Swapping the two files back — the exact regression this unit
#      exists to prevent — fails here and nowhere else;
#   2. `application.properties` binds those two pairs to the two consumers, pool -> spring.datasource and
#      owner -> spring.flyway, and each pair is asserted *not* to name the other's variable;
#   3. compose gives the container both role NAMES (never a password) and the two secret files, in the
#      base file and in prod, and the defaults compose uses agree with the defaults the application falls
#      back to when it runs outside a container;
#   4. `.env.template` declares the role the pool must log in as, and no password for either role.
#
# No Docker daemon, no network, no Java, no repository file written: the wrapper cases run against a
# tree under `mktemp -d` with a stub `java` on PATH.
#
# Run: ./docker/scripts/tests/app-credential-split.test.sh
# Exits 0 only when every check passes.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../_common.sh
source "$SCRIPT_DIR/../_common.sh"

DOCKER_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
REPO_DIR="$(cd "$DOCKER_DIR/.." && pwd)"
ENTRYPOINT="$DOCKER_DIR/entrypoints/lifecontrol-app-entrypoint.sh"
APP_PROPERTIES="$REPO_DIR/life-control-api/src/main/resources/application.properties"
COMPOSE_BASE="$DOCKER_DIR/docker-compose.yml"
COMPOSE_PROD="$DOCKER_DIR/docker-compose.prod.yml"
ENV_TEMPLATE="$DOCKER_DIR/.env.template"

OWNER_ROLE="lifecontrol_owner"
APP_ROLE="lifecontrol_app"

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

# check_equal <description> <expected> <actual>
check_equal() {
	if [ "$2" = "$3" ]; then
		pass "$1 ($3)"
	else
		fail "$1 (expected '$2', got '$3')"
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
		fail "$1 (missing ${2#"$REPO_DIR"/})"
	elif grep -qF -- "$3" "$2" >/dev/null; then
		pass "$1"
	else
		fail "$1 (${2#"$REPO_DIR"/} never mentions '$3')"
	fi
}

# assert_file_absent <description> <file> <needle>
assert_file_absent() {
	if [ ! -f "$2" ]; then
		fail "$1 (missing ${2#"$REPO_DIR"/})"
	elif grep -qF -- "$3" "$2" >/dev/null; then
		fail "$1 (${2#"$REPO_DIR"/} mentions '$3')"
	else
		pass "$1"
	fi
}

# property_value <properties file> <property name>: everything after the first `=`.
property_value() {
	grep -m1 "^$2=" "$1" 2>/dev/null | cut -d= -f2-
}

# placeholder_default <string>: the default of a ${VAR:default} placeholder, empty when there is none.
placeholder_default() {
	printf '%s' "$1" | sed -n 's/^\${[A-Z_]*:\(.*\)}$/\1/p'
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
# 1. The wrapper, executed: which secret reaches which consumer.
# ------------------------------------------
echo "== 1. the wrapper hands the app secret to the pool and the owner's secret to Flyway"

SANDBOX="$TMP_BASE/sandbox"
mkdir -p "$SANDBOX/bin" "$SANDBOX/secrets"
OWNER_SECRET='owner-secret-1'
APP_SECRET='app-secret-2'
ADMIN_SECRET='admin-secret-3'
printf '%s\n' "$OWNER_SECRET" >"$SANDBOX/secrets/lifecontrol_postgres_password"
printf '%s\n' "$APP_SECRET" >"$SANDBOX/secrets/lifecontrol_app_password"
printf '%s\n' "$ADMIN_SECRET" >"$SANDBOX/secrets/keycloak_admin_client_secret"

# A stub `java` that records the environment the real process would have received. This is the only way
# to observe the split: the wrapper's whole output IS the env it execs with.
cat >"$SANDBOX/bin/java" <<'STUB'
#!/bin/sh
{
	printf 'DATABASE_USERNAME=%s\n' "${DATABASE_USERNAME-<unset>}"
	printf 'DATABASE_PASSWORD=%s\n' "${DATABASE_PASSWORD-<unset>}"
	printf 'FLYWAY_USERNAME=%s\n' "${FLYWAY_USERNAME-<unset>}"
	printf 'FLYWAY_PASSWORD=%s\n' "${FLYWAY_PASSWORD-<unset>}"
	printf 'DATABASE_APP_PASSWORD=%s\n' "${DATABASE_APP_PASSWORD-<unset>}"
	printf 'KEYCLOAK_ADMIN_CLIENT_SECRET=%s\n' "${KEYCLOAK_ADMIN_CLIENT_SECRET-<unset>}"
	printf 'ARGV=%s\n' "$*"
} >"$JAVA_LOG"
STUB
chmod +x "$SANDBOX/bin/java"

LAST_RC=0
LAST_ENV=""
LAST_TEXT=""

# run_wrapper [extra env assignments...]
run_wrapper() {
	: >"$SANDBOX/java.log"
	(
		PATH="$SANDBOX/bin:$PATH" JAVA_LOG="$SANDBOX/java.log" \
			LIFECONTROL_OWNER_PASSWORD_FILE="${OWNER_FILE:-$SANDBOX/secrets/lifecontrol_postgres_password}" \
			LIFECONTROL_APP_PASSWORD_FILE="${APP_FILE:-$SANDBOX/secrets/lifecontrol_app_password}" \
			KEYCLOAK_ADMIN_CLIENT_SECRET_FILE="${ADMIN_FILE:-$SANDBOX/secrets/keycloak_admin_client_secret}" \
			"$@" "$ENTRYPOINT"
	) >"$SANDBOX/out.log" 2>&1
	LAST_RC=$?
	LAST_TEXT="$(cat "$SANDBOX/out.log")"
	LAST_ENV="$(cat "$SANDBOX/java.log")"
}

# The role names are compose's, not the wrapper's: a sentinel proves it passes them through untouched.
run_wrapper env DATABASE_USERNAME="$APP_ROLE" FLYWAY_USERNAME="$OWNER_ROLE"
check_equal "the wrapper runs the application" "0" "$LAST_RC"
assert_contains "the pooled credential is the application's secret, not the owner's" \
	"$LAST_ENV" "DATABASE_PASSWORD=$APP_SECRET"
assert_contains "the Flyway credential is the owner's secret" "$LAST_ENV" "FLYWAY_PASSWORD=$OWNER_SECRET"
assert_absent "the owner's secret never reaches the pool" "$LAST_ENV" "DATABASE_PASSWORD=$OWNER_SECRET"
assert_absent "the app's secret never reaches Flyway" "$LAST_ENV" "FLYWAY_PASSWORD=$APP_SECRET"
pool_password="$(printf '%s\n' "$LAST_ENV" | sed -n 's/^DATABASE_PASSWORD=//p')"
flyway_password="$(printf '%s\n' "$LAST_ENV" | sed -n 's/^FLYWAY_PASSWORD=//p')"
if [ -n "$pool_password" ] && [ "$pool_password" != "$flyway_password" ]; then
	pass "the two database logins do not share a credential"
else
	fail "the two database logins must not share a credential (got '$pool_password' and '$flyway_password')"
fi
assert_contains "the pool connects as the application role" "$LAST_ENV" "DATABASE_USERNAME=$APP_ROLE"
assert_contains "Flyway connects as the owner" "$LAST_ENV" "FLYWAY_USERNAME=$OWNER_ROLE"
assert_absent "the second name for the pool credential is gone (one name per job)" \
	"$LAST_ENV" "DATABASE_APP_PASSWORD=app-secret"
assert_contains "the Keycloak admin client secret is still exported" \
	"$LAST_ENV" "KEYCLOAK_ADMIN_CLIENT_SECRET=$ADMIN_SECRET"
assert_contains "the Java launch command is the image's" "$LAST_ENV" "-jar /app/app.jar"

# A wrapper that exported a literal would be a secret in the repository. Every credential assignment it
# makes therefore has to come from a file read, and the fake values above must appear nowhere in it.
assert_file_absent "the wrapper carries no secret content" "$ENTRYPOINT" "$OWNER_SECRET"
assert_file_absent "the wrapper carries no secret content" "$ENTRYPOINT" "$APP_SECRET"
exported_passwords="$(grep -cE '^export [A-Z_]*PASSWORD=' "$ENTRYPOINT")"
check_equal "the wrapper exports exactly the two database credentials" "2" "$exported_passwords"
while IFS= read -r export_line; do
	assert_contains "every credential the wrapper exports is read from a file" "$export_line" '$(cat '
done < <(grep -E '^export [A-Z_]*PASSWORD=' "$ENTRYPOINT")
assert_file_contains "the owner secret path defaults to the mount the compose files declare" \
	"$ENTRYPOINT" 'LIFECONTROL_OWNER_PASSWORD_FILE:-/run/secrets/lifecontrol_postgres_password'
assert_file_contains "the app secret path defaults to the mount the compose files declare" \
	"$ENTRYPOINT" 'LIFECONTROL_APP_PASSWORD_FILE:-/run/secrets/lifecontrol_app_password'

# Fail closed: an API that starts without either credential ships the wrong identity to every connection.
mkdir -p "$SANDBOX/without-owner" "$SANDBOX/without-app"
OWNER_FILE="$SANDBOX/without-owner/lifecontrol_postgres_password"
run_wrapper env DATABASE_USERNAME="$APP_ROLE" FLYWAY_USERNAME="$OWNER_ROLE"
check_equal "an absent owner credential fails the start" "1" "$LAST_RC"
assert_contains "the failure names the owner's file" "$LAST_TEXT" "lifecontrol_postgres_password"
check_equal "it never reaches the application" "" "$LAST_ENV"

OWNER_FILE=""
APP_FILE="$SANDBOX/without-app/lifecontrol_app_password"
run_wrapper env DATABASE_USERNAME="$APP_ROLE" FLYWAY_USERNAME="$OWNER_ROLE"
check_equal "an absent pool credential fails the start" "1" "$LAST_RC"
assert_contains "the failure names the pool's file" "$LAST_TEXT" "lifecontrol_app_password"
check_equal "it never reaches the application either" "" "$LAST_ENV"
APP_FILE=""

# ------------------------------------------
# 2. application.properties: which pair feeds which consumer.
# ------------------------------------------
echo "== 2. application.properties binds the pool to spring.datasource and the owner to spring.flyway"

if [ ! -f "$APP_PROPERTIES" ]; then
	fail "missing life-control-api/src/main/resources/application.properties"
else
	check_equal "the pool's username is the application role" \
		'${DATABASE_USERNAME:'"$APP_ROLE"'}' \
		"$(property_value "$APP_PROPERTIES" spring.datasource.username)"
	check_equal "the pool's password is DATABASE_PASSWORD (the app secret)" \
		'${DATABASE_PASSWORD}' \
		"$(property_value "$APP_PROPERTIES" spring.datasource.password)"
	check_equal "Flyway's username is the owner" \
		'${FLYWAY_USERNAME:'"$OWNER_ROLE"'}' \
		"$(property_value "$APP_PROPERTIES" spring.flyway.user)"
	check_equal "Flyway's password is FLYWAY_PASSWORD (the owner's secret)" \
		'${FLYWAY_PASSWORD:}' \
		"$(property_value "$APP_PROPERTIES" spring.flyway.password)"

	# Boot builds Flyway's connection from url/user/password together, so the url has to be there too: a
	# user without a url would leave Flyway on the pool's datasource, and then "Flyway runs as the owner"
	# is a claim about a connection that does not exist.
	check_equal "Flyway connects to the same database as the pool" \
		"$(property_value "$APP_PROPERTIES" spring.datasource.url)" \
		"$(property_value "$APP_PROPERTIES" spring.flyway.url)"

	pool_lines="$(grep -E '^spring\.datasource\.(username|password)=' "$APP_PROPERTIES")"
	flyway_lines="$(grep -E '^spring\.flyway\.(user|password)=' "$APP_PROPERTIES")"
	assert_absent "the pool's pair never reads the owner's variable" "$pool_lines" "FLYWAY_"
	assert_absent "Flyway's pair never reads the pool's password variable" "$flyway_lines" "DATABASE_PASSWORD"

	# The two roles must not be the same role, in the defaults as much as in delivery.
	pool_role_default="$(placeholder_default "$(property_value "$APP_PROPERTIES" spring.datasource.username)")"
	owner_role_default="$(placeholder_default "$(property_value "$APP_PROPERTIES" spring.flyway.user)")"
	check_equal "a bare run outside a container still connects as the application role" \
		"$APP_ROLE" "$pool_role_default"
	check_equal "a bare run still migrates as the owner" "$OWNER_ROLE" "$owner_role_default"
	if [ -n "$pool_role_default" ] && [ "$pool_role_default" = "$owner_role_default" ]; then
		fail "the pool and Flyway must not default to the same role ('$pool_role_default')"
	else
		pass "the pool and Flyway never default to the same role"
	fi
fi

# ------------------------------------------
# 3. compose: both role names in, two secret files in, no password value anywhere.
# ------------------------------------------
echo "== 3. compose passes the two role names and mounts the two secrets, base and prod"

base_api="$(service_block "$COMPOSE_BASE" lifecontrol-api)"
prod_api="$(service_block "$COMPOSE_PROD" lifecontrol-api)"

assert_contains "the base compose gives the pool the application role" "$base_api" \
	'DATABASE_USERNAME=${LIFECONTROL_DATABASE_USERNAME:-'"$APP_ROLE"'}'
assert_contains "the base compose gives Flyway the owner role" "$base_api" \
	'FLYWAY_USERNAME=${LIFECONTROL_POSTGRES_USER:-'"$OWNER_ROLE"'}'
assert_contains "the base compose mounts the owner's credential" "$base_api" \
	'./secrets/lifecontrol_postgres_password:/run/secrets/lifecontrol_postgres_password:ro'
assert_contains "the base compose mounts the pool's credential" "$base_api" \
	'./secrets/lifecontrol_app_password:/run/secrets/lifecontrol_app_password:ro'
assert_absent "the base compose never sends a password as a value" "$base_api" 'DATABASE_PASSWORD='
assert_absent "the base compose never sends the owner's password as a value" "$base_api" 'FLYWAY_PASSWORD='

assert_contains "prod mounts the owner's credential for Flyway" "$prod_api" \
	'./secrets/lifecontrol_postgres_password:/run/secrets/lifecontrol_postgres_password:ro'
assert_contains "prod mounts the pool's credential" "$prod_api" \
	'./secrets/lifecontrol_app_password:/run/secrets/lifecontrol_app_password:ro'
assert_absent "prod never re-points the pool at another role" "$prod_api" 'DATABASE_USERNAME='
assert_absent "prod never re-points Flyway at another role" "$prod_api" 'FLYWAY_USERNAME='
assert_absent "prod sends no password as a value either" "$prod_api" 'PASSWORD='

# The defaults have to agree in both places or the container and a bare `bootRun` connect as different
# roles, which is the kind of divergence nobody notices until a policy behaves differently in one of them.
check_equal "compose and the application agree on the pool role" \
	"$(placeholder_default "$(property_value "$APP_PROPERTIES" spring.datasource.username)")" \
	"$(printf '%s\n' "$base_api" | sed -n 's/^.*DATABASE_USERNAME=.*:-\([a-z_]*\)}$/\1/p')"
check_equal "compose and the application agree on Flyway's role" \
	"$(placeholder_default "$(property_value "$APP_PROPERTIES" spring.flyway.user)")" \
	"$(printf '%s\n' "$base_api" | sed -n 's/^.*FLYWAY_USERNAME=.*:-\([a-z_]*\)}$/\1/p')"

for compose in "$COMPOSE_BASE" "$COMPOSE_PROD"; do
	file="$(basename "$compose")"
	assert_file_absent "$file declares no database password key" "$compose" "LIFECONTROL_DATABASE_PASSWORD="
	assert_file_absent "$file declares no app password key" "$compose" "LIFECONTROL_APP_PASSWORD="
done

# ------------------------------------------
# 4. The template: the role names are declared, the passwords never are.
# ------------------------------------------
echo "== 4. .env.template declares which role the pool logs in as, and no credential"

if [ ! -f "$ENV_TEMPLATE" ]; then
	fail "missing docker/.env.template"
else
	assert_file_contains "the template points the pool at the application role" "$ENV_TEMPLATE" \
		"LIFECONTROL_DATABASE_USERNAME=$APP_ROLE"
	assert_file_absent "the template never puts the owner back into the pool" "$ENV_TEMPLATE" \
		"LIFECONTROL_DATABASE_USERNAME=$OWNER_ROLE"
	assert_file_contains "the template still declares the owner, which Flyway logs in as" "$ENV_TEMPLATE" \
		"LIFECONTROL_POSTGRES_USER=$OWNER_ROLE"
	assert_file_absent "the template carries no app password" "$ENV_TEMPLATE" "lifecontrol_app_password="
	assert_file_absent "the template carries no owner password" "$ENV_TEMPLATE" \
		"lifecontrol_postgres_password="
fi

# ------------------------------------------
# Summary
# ------------------------------------------
echo "------------------------------------------"
printf 'app-credential-split: %s passed, %s failed\n' "$PASS_COUNT" "$FAIL_COUNT"
if [ "$FAIL_COUNT" -ne 0 ]; then
	exit 1
fi
exit 0
