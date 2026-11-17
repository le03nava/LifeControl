#!/usr/bin/env bash
# ============================================
# LifeControl - fiscal provider wiring (issue #930, odd/tasks/fiscal-deploy-wiring.md)
# ============================================
# The fiscal provider is the first capability whose credentials the compose files mount without a
# `.template` behind them, so nothing materializes them and nothing structurally requires them. What makes
# that safe is a chain of four links, and this suite pins each one offline:
#
#   1. the switch defaults to off, so a stack that does not invoice is not asked for anything (S4) --
#      in the compose default, in Spring, and in the template a brand-new environment is born from;
#   2. every non-secret setting the provider needs is interpolated by compose with the default
#      `application.properties` declares, so the two cannot drift (S2);
#   3. base and prod mount the four credentials read-only, and the wrapper reads them only while the
#      switch is on, refusing the shapes Docker leaves for a source that does not exist (S1, S4);
#   4. validate-env.sh refuses the two configurations that would look green and stamp nothing: a switch
#      turned on with a setting or a credential missing, and the vendor's public testing host outside dev
#      (S3, S5).
#
# Nothing here starts a container: the wrapper is run against a stub `java` that records the environment
# the real process would have received, which is the only way to observe what the wrapper does, and
# validate-env's fiscal check is extracted and driven with stubs because the script itself also probes
# Docker and ports and cannot run inside a suite.
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DOCKER_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
REPO_ROOT="$(cd "$DOCKER_DIR/.." && pwd)"

ENTRYPOINT="$DOCKER_DIR/entrypoints/lifecontrol-app-entrypoint.sh"
COMPOSE_BASE="$DOCKER_DIR/docker-compose.yml"
COMPOSE_PROD="$DOCKER_DIR/docker-compose.prod.yml"
VALIDATE_SH="$DOCKER_DIR/scripts/validate-env.sh"
APP_PROPERTIES="$REPO_ROOT/life-control-api/src/main/resources/application.properties"

PASS_COUNT=0
FAIL_COUNT=0

TMP_BASE="$(mktemp -d)"

# shellcheck disable=SC2329  # invoked by the EXIT trap below, which shellcheck cannot trace
cleanup() {
	if [ -n "${TMP_BASE:-}" ] && [ -d "$TMP_BASE" ]; then
		rm -rf "$TMP_BASE"
	fi
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
		pass "$1"
	else
		fail "$1 (expected '$2', got '$3')"
	fi
}

assert_contains() {
	case "$2" in
	*"$3"*) pass "$1" ;;
	*) fail "$1 (missing '$3' in: ${2:-<empty>})" ;;
	esac
}

assert_absent() {
	case "$2" in
	*"$3"*) fail "$1 (unexpectedly found '$3')" ;;
	*) pass "$1" ;;
	esac
}

assert_file_contains() {
	if [ ! -f "$2" ]; then
		fail "$1 (missing file $2)"
		return 0
	fi
	assert_contains "$1" "$(cat "$2")" "$3"
}

assert_file_absent() {
	if [ ! -f "$2" ]; then
		fail "$1 (missing file $2)"
		return 0
	fi
	assert_absent "$1" "$(cat "$2")" "$3"
}

# The ${KEY:-default} a compose file declares for a key, first occurrence, without the wrapper syntax.
compose_default_of() {
	sed -n 's/.*\${'"$2"':-\([^}]*\)}.*/\1/p' "$1" | head -1
}

# The line the API service's environment block declares for a key, or nothing when it declares none.
compose_env_line() {
	grep -E "^[[:space:]]*- $2=" "$1" | head -1
}

# The line that binds a host path to a container path for a named secret, or nothing.
mount_line() {
	grep -E "^[[:space:]]*- \./secrets/$2:" "$1" | head -1
}

# ------------------------------------------
# 1. The switch defaults to off in the compose files that deploy the API.
# ------------------------------------------
echo "== 1. the fiscal provider is off unless a deployment says otherwise"

ENABLED_DEFAULT="$(compose_default_of "$COMPOSE_BASE" FISCAL_PROVIDER_ENABLED)"
check_equal "the base compose defaults the provider off" "false" "$ENABLED_DEFAULT"
assert_contains "the compose block says why it is off" \
	"$(cat "$COMPOSE_BASE")" "Fiscal provider (CFDI stamping): OFF in every environment by default"
assert_file_contains "the API's Spring default is off too, so compose only has to agree with it" \
	"$APP_PROPERTIES" 'app.fiscal.provider.enabled=${FISCAL_PROVIDER_ENABLED:false}'

# Prod inherits the base environment block, so a second declaration there would be a second source of
# truth free to drift. The mounts are the half prod does have to repeat.
assert_file_absent "prod does not re-declare the switch (it inherits the base block)" \
	"$COMPOSE_PROD" "FISCAL_PROVIDER_ENABLED="

# ------------------------------------------
# 1b. The template every new environment is materialized from carries those same two answers.
# ------------------------------------------
# `setup-env.sh` copies the tracked template verbatim into `docker/.env.<env>` when that file does not
# exist, and nothing re-reads it afterwards: so a value in the template is not a suggestion, it is the
# value a brand-new staging or production environment is born with. Both rules below already live in
# `validate-env.sh`, which runs as a gate on prod and as a manual run everywhere else -- the point of
# pinning them here is that a deployment is not one operator remembering to run a script.
echo "== 1b. the template a new environment is born from is off and does not point at the sandbox"

ENV_TEMPLATE="$DOCKER_DIR/.env.template"

# The value the template declares for a key, last occurrence, or nothing when it declares none.
template_value_of() {
	sed -n "s/^$1=//p" "$ENV_TEMPLATE" 2>/dev/null | tail -1
}

# Every line of the template that names $1, numbered, or nothing. Whole-file on purpose, so an endpoint
# parked in a commented-out line is caught too: it is one uncomment away from being a value.
template_lines_naming() {
	grep -n -F -- "$1" "$ENV_TEMPLATE" 2>/dev/null | tr '\n' ' ' || true
}

if [ ! -f "$ENV_TEMPLATE" ]; then
	fail "the tracked env template is missing ($ENV_TEMPLATE): it is what setup-env.sh materializes every docker/.env.<env> from, so a new environment has nothing to be born from"
fi

# A template that defaults the provider on asks every brand-new environment for a CSD it does not have,
# and boots a container that stamps documents the authority never saw.
check_equal "the template a new environment is born from declares the provider off -- keep FISCAL_PROVIDER_ENABLED=false in the template, and set true only in the deployment that invoices, in its own untracked docker/.env.<env>" \
	"false" "$(template_value_of FISCAL_PROVIDER_ENABLED)"

# S5's other half: a value naming the vendor's sandbox makes a brand-new staging or production
# environment stamp against a host the authority never saw, which is exactly what validate-env refuses.
# The comment that documents the production endpoint is fine; a testing hostname in a value is not.
TESTING_HOST_LINES="$(template_lines_naming 'testing.solucionfactible.com')"
if [ -z "$TESTING_HOST_LINES" ]; then
	pass "the template never names the vendor's testing host, so no environment is born pointed at the sandbox"
else
	fail "the template never names the vendor's testing host, and names it here: ${TESTING_HOST_LINES}-- keep FISCAL_SF_ENDPOINT empty in the template, and put the endpoint that deployment stamps against (the testing host in dev, the production host otherwise) in its own untracked docker/.env.<env>"
fi

# ------------------------------------------
# 2. Every non-secret setting compose passes has the default Spring declares.
# ------------------------------------------
echo "== 2. compose settings mirror the defaults application.properties declares"

check_compose_mirrors_properties() {
	local key="$1" expected="$2"
	local line actual
	line="$(compose_env_line "$COMPOSE_BASE" "$key")"
	if [ -z "$line" ]; then
		fail "the API's environment block does not declare $key"
		return 1
	fi
	actual="$(compose_default_of "$COMPOSE_BASE" "$key")"
	check_equal "compose defaults $key to the value application.properties declares" "$expected" "$actual"
	# The property names the placeholder, so the variable is followed by `:` and never by `=`.
	assert_file_contains "$key still reaches Spring from the same property" "$APP_PROPERTIES" "\${$key:"
}

check_compose_mirrors_properties FISCAL_PROVIDER_ENABLED "false"
check_compose_mirrors_properties FISCAL_TIMEZONE "America/Mexico_City"
check_compose_mirrors_properties FISCAL_SF_TIMEOUT_SECONDS "30"

# The two the vendor needs have no working default anywhere, and an empty compose default is how that is
# said: application.properties declares them empty, SolucionFactibleProperties is @NotBlank on both, so an
# enabled deployment that leaves them alone fails its own boot instead of stamping against a guess.
assert_file_contains "the endpoint has no working default in Spring" "$APP_PROPERTIES" 'app.fiscal.provider.solucion-factible.endpoint=${FISCAL_SF_ENDPOINT:}'
assert_file_contains "the vendor's user has no working default in Spring" "$APP_PROPERTIES" 'app.fiscal.provider.solucion-factible.usuario=${FISCAL_SF_USUARIO:}'
check_equal "compose passes the endpoint with an empty default" "" "$(compose_default_of "$COMPOSE_BASE" FISCAL_SF_ENDPOINT)"
check_equal "compose passes the vendor's user with an empty default" "" "$(compose_default_of "$COMPOSE_BASE" FISCAL_SF_USUARIO)"

# ------------------------------------------
# 3. The four credentials are mounts, never values, in both compose files.
# ------------------------------------------
echo "== 3. the four fiscal credentials are mounted read-only and never valued"

for credential in fiscal_csd_certificado fiscal_csd_llave fiscal_csd_password fiscal_sf_password; do
	for file in "$COMPOSE_BASE" "$COMPOSE_PROD"; do
		line="$(mount_line "$file" "$credential")"
		if [ -z "$line" ]; then
			fail "${file##*/} does not mount $credential"
			continue
		fi
		assert_contains "${file##*/} mounts $credential read-only" "$line" ":/run/secrets/$credential:ro"
	done
	# A value in an environment entry is the thing this whole design refuses: it would be readable in
	# `docker inspect` and printed by `compose config`, which is why the repository's own rule is a file.
	assert_absent "$credential is never an environment value in the base file" \
		"$(cat "$COMPOSE_BASE")" "- $credential="
	assert_absent "$credential is never an environment value in prod" \
		"$(cat "$COMPOSE_PROD")" "- $credential="
	# Spring binds it from the environment with an empty default (`${VAR:}`). A value between the colon
	# and the brace would be a credential in this repository, which is the one thing the design refuses.
	assert_file_contains "$credential is bound from the environment with no baked default" \
		"$APP_PROPERTIES" "\${${credential^^}:}"
done

# ------------------------------------------
# 4. The wrapper: gated on the switch, fail-closed while on, tolerant of the optional key password and
# of how a hand-written password file happens to end.
# ------------------------------------------
echo "== 4. the entrypoint wrapper reads the credentials only while the provider is on"

SANDBOX="$TMP_BASE/wrapper"
mkdir -p "$SANDBOX/bin" "$SANDBOX/secrets"

# The three credentials the wrapper already required, so every case below reaches the fiscal block
# instead of failing on the database split this suite is not testing.
printf 'owner-secret\n' >"$SANDBOX/secrets/owner"
printf 'app-secret\n' >"$SANDBOX/secrets/app"
printf 'admin-secret\n' >"$SANDBOX/secrets/admin"

# The CSD material as an operator would produce it: `openssl base64` wraps at 64 columns, and the value
# CsdProperties binds is continuous.
printf 'CERTIFICADO\nLINEA2\n' >"$SANDBOX/secrets/cert"
printf 'LLAVE\nLINEA2\n' >"$SANDBOX/secrets/key"
printf 'llave-password\n' >"$SANDBOX/secrets/key-password"
printf 'pac-password\n' >"$SANDBOX/secrets/pac-password"

cat >"$SANDBOX/bin/java" <<'STUB'
#!/bin/sh
{
	printf 'FISCAL_PROVIDER_ENABLED=%s\n' "${FISCAL_PROVIDER_ENABLED-<unset>}"
	printf 'FISCAL_CSD_CERTIFICADO=%s\n' "${FISCAL_CSD_CERTIFICADO-<unset>}"
	printf 'FISCAL_CSD_LLAVE=%s\n' "${FISCAL_CSD_LLAVE-<unset>}"
	printf 'FISCAL_CSD_PASSWORD=%s\n' "${FISCAL_CSD_PASSWORD-<unset>}"
	printf 'FISCAL_SF_PASSWORD=%s\n' "${FISCAL_SF_PASSWORD-<unset>}"
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
			LIFECONTROL_OWNER_PASSWORD_FILE="$SANDBOX/secrets/owner" \
			LIFECONTROL_APP_PASSWORD_FILE="$SANDBOX/secrets/app" \
			KEYCLOAK_ADMIN_CLIENT_SECRET_FILE="$SANDBOX/secrets/admin" \
			FISCAL_CSD_CERTIFICADO_FILE="${CERT_FILE:-$SANDBOX/secrets/cert}" \
			FISCAL_CSD_LLAVE_FILE="${KEY_FILE:-$SANDBOX/secrets/key}" \
			FISCAL_CSD_PASSWORD_FILE="${KEY_PASSWORD_FILE:-$SANDBOX/secrets/key-password}" \
			FISCAL_SF_PASSWORD_FILE="${PAC_PASSWORD_FILE:-$SANDBOX/secrets/pac-password}" \
			"$@" "$ENTRYPOINT"
	) >"$SANDBOX/out.log" 2>&1
	LAST_RC=$?
	LAST_TEXT="$(cat "$SANDBOX/out.log")"
	LAST_ENV="$(cat "$SANDBOX/java.log")"
}

# 4a. Off: the wrapper must be invisible. This is S4's real assertion -- not that the provider defaults
# off in a file, but that a stack without a CSD reaches the application with no fiscal variable at all.
CERT_FILE="" KEY_FILE="" KEY_PASSWORD_FILE="" PAC_PASSWORD_FILE=""
run_wrapper env
check_equal "with the provider off the wrapper still runs the application" "0" "$LAST_RC"
# The stub always prints a line per variable it observes, so an absent export reads as `<unset>`; a value
# after the `=` is what "the wrapper exported it" looks like.
assert_contains "no fiscal credential is exported while the provider is off" "$LAST_ENV" \
	"FISCAL_CSD_CERTIFICADO=<unset>"
assert_contains "the vendor's password is not exported while the provider is off" "$LAST_ENV" \
	"FISCAL_SF_PASSWORD=<unset>"
assert_contains "the switch itself is not invented by the wrapper" "$LAST_ENV" "FISCAL_PROVIDER_ENABLED=<unset>"
check_equal "and the wrapper says nothing at all while off" "" "$LAST_TEXT"

run_wrapper env FISCAL_PROVIDER_ENABLED=false
check_equal "an explicit false is the same as an absent switch" "0" "$LAST_RC"
assert_contains "an explicit false exports no fiscal credential either" "$LAST_ENV" "FISCAL_CSD_LLAVE=<unset>"

# 4b. On with everything present: all four values reach the process, and the base 64 is continuous.
CERT_FILE="$SANDBOX/secrets/cert" KEY_FILE="$SANDBOX/secrets/key"
KEY_PASSWORD_FILE="$SANDBOX/secrets/key-password" PAC_PASSWORD_FILE="$SANDBOX/secrets/pac-password"
run_wrapper env FISCAL_PROVIDER_ENABLED=true
check_equal "with the provider on and the material mounted the application runs" "0" "$LAST_RC"
assert_contains "the certificate reaches the sealer" "$LAST_ENV" "FISCAL_CSD_CERTIFICADO=CERTIFICADOLINEA2"
assert_contains "the private key reaches the sealer" "$LAST_ENV" "FISCAL_CSD_LLAVE=LLAVELINEA2"
assert_contains "the key's password reaches the sealer" "$LAST_ENV" "FISCAL_CSD_PASSWORD=llave-password"
assert_contains "the vendor's password reaches the adapter" "$LAST_ENV" "FISCAL_SF_PASSWORD=pac-password"

# 4c. The key password is optional: absent, the key is read as unprotected, which CsdProperties documents
# as the same fact as a blank password. An empty file is NOT the way to say it -- validate-env.sh refuses
# an empty secret -- so the absence is the shape that has to work.
KEY_PASSWORD_FILE="$SANDBOX/secrets/unprotected-key-password"
run_wrapper env FISCAL_PROVIDER_ENABLED=true
check_equal "an unprotected CSD key does not stop the start" "0" "$LAST_RC"
assert_contains "the key still reaches the sealer" "$LAST_ENV" "FISCAL_CSD_LLAVE=LLAVELINEA2"
assert_contains "no key password is exported when none is mounted" "$LAST_ENV" "FISCAL_CSD_PASSWORD=<unset>"
KEY_PASSWORD_FILE="$SANDBOX/secrets/key-password"

# 4d. Fail closed on the three that are required, naming the file rather than the switch.
for required_file in cert key pac-password; do
	case "$required_file" in
	cert) CERT_FILE="$SANDBOX/secrets/missing" ;;
	key) KEY_FILE="$SANDBOX/secrets/missing" ;;
	pac-password) PAC_PASSWORD_FILE="$SANDBOX/secrets/missing" ;;
	esac
	run_wrapper env FISCAL_PROVIDER_ENABLED=true
	check_equal "an absent $required_file fails the start while the provider is on" "1" "$LAST_RC"
	check_equal "and the application is never reached" "" "$LAST_ENV"
	# The refusal has to name the switch, not just a path: the operator's question is why a file that
	# was never required yesterday is required now.
	assert_contains "the refusal names the switch that made the file required" "$LAST_TEXT" \
		"FISCAL_PROVIDER_ENABLED=true requires it"
	CERT_FILE="$SANDBOX/secrets/cert" KEY_FILE="$SANDBOX/secrets/key"
	PAC_PASSWORD_FILE="$SANDBOX/secrets/pac-password"
done

# The shape Docker leaves for a bind source that does not exist: a directory. `test -s` is true for one,
# which is how a check that only tested emptiness passed over a credential that was never mounted.
mkdir -p "$SANDBOX/secrets/directory-shaped"
CERT_FILE="$SANDBOX/secrets/directory-shaped"
run_wrapper env FISCAL_PROVIDER_ENABLED=true
check_equal "a directory where the certificate belongs fails the start" "1" "$LAST_RC"
check_equal "and nothing is exported" "" "$LAST_ENV"
CERT_FILE="$SANDBOX/secrets/cert"

# 4e. Byte-exactness of the two hand-made password files. Nothing materializes fiscal_sf_password or
# fiscal_csd_password from a tracked template -- an operator writes them -- so the trailing byte is
# whatever their tool wrote: `echo` leaves a newline, a file saved on Windows leaves a CRLF, and a
# heredoc or a `printf` may leave none at all. The vendor's rejection names neither the file nor the
# byte, so the export has to be the password and only the password in all three shapes. These compare
# the whole value, not a substring: `assert_contains` would accept `llave-password\r` as a match for
# the password it visibly is not.
env_value() {
	printf '%s\n' "$LAST_ENV" | sed -n "s/^$1=//p"
}

for shape in lf no-newline crlf; do
	case "$shape" in
	lf) terminator='\n' label='newline' ;;
	no-newline) terminator='' label='terminator at all' ;;
	crlf) terminator='\r\n' label='CRLF' ;;
	esac
	printf "llave-password${terminator}" >"$SANDBOX/secrets/key-password-$shape"
	printf "pac-password${terminator}" >"$SANDBOX/secrets/pac-password-$shape"
	KEY_PASSWORD_FILE="$SANDBOX/secrets/key-password-$shape"
	PAC_PASSWORD_FILE="$SANDBOX/secrets/pac-password-$shape"
	run_wrapper env FISCAL_PROVIDER_ENABLED=true
	check_equal "a key password file ending in $label still starts the application" "0" "$LAST_RC"
	check_equal "the key's password is exported without a trailing $label" \
		"llave-password" "$(env_value FISCAL_CSD_PASSWORD)"
	check_equal "the vendor's password is exported without a trailing $label" \
		"pac-password" "$(env_value FISCAL_SF_PASSWORD)"
done
KEY_PASSWORD_FILE="$SANDBOX/secrets/key-password"
PAC_PASSWORD_FILE="$SANDBOX/secrets/pac-password"

assert_file_absent "the wrapper carries no password material" "$ENTRYPOINT" "pac-password"
assert_file_absent "the wrapper carries no key material" "$ENTRYPOINT" "llave-password"

# ------------------------------------------
# 5. validate-env.sh: refuses what would look green and stamp nothing, and says nothing while off.
# ------------------------------------------
echo "== 5. validate-env refuses the incomplete and the testing-host configuration"

VALIDATE_ENV_DIR="$TMP_BASE/validate"
mkdir -p "$VALIDATE_ENV_DIR/secrets"

# The fiscal check is extracted and driven with stubs: validate-env.sh also probes Docker and host ports,
# so running it end to end would test the machine rather than the rule. The function body is the real one.
cat >"$VALIDATE_ENV_DIR/harness.sh" <<'HARNESS'
set -u
ENV="$1"
ENV_FILE="$2"
DOCKER_DIR="$3"
ERRORS=0
OUT=""
print_error() { OUT="$OUT|error:$1"; }
print_status() { OUT="$OUT|status:$1"; }
print_success() { OUT="$OUT|success:$1"; }
get_env_var() { sed -n "s/^$1=//p" "$ENV_FILE" | tail -1; }
HARNESS
sed -n '/^check_fiscal_configuration()/,/^}$/p' "$VALIDATE_SH" >>"$VALIDATE_ENV_DIR/harness.sh"
cat >>"$VALIDATE_ENV_DIR/harness.sh" <<'HARNESS'
check_fiscal_configuration
printf 'ERRORS=%s\n' "$ERRORS"
printf 'OUT=%s\n' "$OUT"
HARNESS

run_fiscal_check() {
	# $1 environment, $2 env-file contents
	printf '%s\n' "$2" >"$VALIDATE_ENV_DIR/env.file"
	bash "$VALIDATE_ENV_DIR/harness.sh" "$1" "$VALIDATE_ENV_DIR/env.file" "$VALIDATE_ENV_DIR"
}

# Off is the default and must be silent: a stack that does not invoice cannot be refused for a capability
# it never asked for, and the production start gate runs this on every deploy.
OUT="$(run_fiscal_check prod 'FISCAL_PROVIDER_ENABLED=false')"
assert_contains "with the provider off the check passes" "$OUT" "ERRORS=0"
assert_contains "and it says so instead of staying silent about a whole capability" "$OUT" "Fiscal provider off"
OUT="$(run_fiscal_check prod '')"
assert_contains "an env file without the key is off as well" "$OUT" "ERRORS=0"
assert_absent "prod with the provider off and no endpoint is not asked for one" "$OUT" "must both be set"
# Dev's exemption from the host rule has to survive the reordering below: it belongs to the environment
# name, not to the switch.
OUT="$(run_fiscal_check dev 'FISCAL_PROVIDER_ENABLED=false
FISCAL_SF_ENDPOINT=https://testing.solucionfactible.com/ws/services/Timbrado')"
assert_contains "dev with the provider off and the testing host still passes with zero errors" "$OUT" "ERRORS=0"

# On, complete, in dev: nothing to refuse.
printf 'csd\n' >"$VALIDATE_ENV_DIR/secrets/fiscal_csd_certificado"
printf 'csd\n' >"$VALIDATE_ENV_DIR/secrets/fiscal_csd_llave"
printf 'pac\n' >"$VALIDATE_ENV_DIR/secrets/fiscal_sf_password"
OUT="$(run_fiscal_check dev 'FISCAL_PROVIDER_ENABLED=true
FISCAL_SF_ENDPOINT=https://testing.solucionfactible.com/ws/services/Timbrado
FISCAL_SF_USUARIO=someone')"
assert_contains "the testing host is accepted in dev, where a stamp is only a test" "$OUT" "ERRORS=0"

# The same configuration in prod is the failure the gate exists for.
OUT="$(run_fiscal_check prod 'FISCAL_PROVIDER_ENABLED=true
FISCAL_SF_ENDPOINT=https://testing.solucionfactible.com/ws/services/Timbrado
FISCAL_SF_USUARIO=someone')"
assert_contains "the testing host is refused outside dev" "$OUT" "error:FISCAL_SF_ENDPOINT is the public testing host"
assert_contains "and the refusal counts as a validation error" "$OUT" "ERRORS=1"

# S5 is written about the environment file, not about one environment: staging is refused too, and so is
# an environment name that never resolved -- fail closed rather than being read as dev.
OUT="$(run_fiscal_check staging 'FISCAL_PROVIDER_ENABLED=true
FISCAL_SF_ENDPOINT=https://testing.solucionfactible.com/ws/services/Timbrado
FISCAL_SF_USUARIO=someone')"
assert_contains "staging refuses the testing host as well, not only prod" "$OUT" "error:FISCAL_SF_ENDPOINT is the public testing host"
assert_contains "and the refusal names the environment it was judged in" "$OUT" "in staging"
assert_contains "and staging counts it as an error" "$OUT" "ERRORS=1"

OUT="$(run_fiscal_check '' 'FISCAL_PROVIDER_ENABLED=true
FISCAL_SF_ENDPOINT=https://testing.solucionfactible.com/ws/services/Timbrado
FISCAL_SF_USUARIO=someone')"
assert_contains "an environment name that never resolved is refused, not exempted as dev" "$OUT" "error:FISCAL_SF_ENDPOINT is the public testing host"
assert_contains "and it is counted as an error rather than a report" "$OUT" "ERRORS=1"

# The finding an independent verification left behind, and the reason the host is judged before the switch
# is read: with the provider off the old early return made a production env file pointing at the sandbox
# pass, and no stamp could expose it until somebody flipped the switch and inherited the endpoint.
OUT="$(run_fiscal_check prod 'FISCAL_PROVIDER_ENABLED=false
FISCAL_SF_ENDPOINT=https://testing.solucionfactible.com/ws/services/Timbrado')"
assert_contains "prod with the provider off and the testing host is refused: the file is judged, not the switch" "$OUT" "error:FISCAL_SF_ENDPOINT is the public testing host"
assert_contains "and the switch being off does not exempt the file" "$OUT" "ERRORS=1"
assert_contains "while the off line still prints, so the report keeps its report-only shape" "$OUT" "Fiscal provider off"
assert_absent "the refusal no longer offers the switch as a way out, because turning it off is what left the file dirty" "$OUT" "set FISCAL_PROVIDER_ENABLED=false in"
assert_contains "the remedy is about the endpoint instead" "$OUT" "Set the production endpoint in"

# The switch on with the vendor's settings still empty, which stays this function's business only while
# the switch is on: both are legitimately empty otherwise.
OUT="$(run_fiscal_check prod 'FISCAL_PROVIDER_ENABLED=true
FISCAL_SF_TIMEOUT_SECONDS=30')"
assert_contains "an enabled provider with no endpoint is refused" "$OUT" "error:FISCAL_SF_ENDPOINT and FISCAL_SF_USUARIO must both be set"

# The switch on with a credential missing: one error per absent file, and the password that protects the
# key is the one absence that is legal.
OUT="$(run_fiscal_check prod 'FISCAL_PROVIDER_ENABLED=true
FISCAL_SF_ENDPOINT=https://solucionfactible.com/ws/services/Timbrado
FISCAL_SF_USUARIO=someone')"
assert_contains "a complete production configuration passes" "$OUT" "ERRORS=0"
assert_contains "and the absent key password is reported as the legal shape it is" "$OUT" "fiscal_csd_password absent"

rm -f "$VALIDATE_ENV_DIR/secrets/fiscal_sf_password"
OUT="$(run_fiscal_check prod 'FISCAL_PROVIDER_ENABLED=true
FISCAL_SF_ENDPOINT=https://solucionfactible.com/ws/services/Timbrado
FISCAL_SF_USUARIO=someone')"
assert_contains "an enabled provider with no vendor credential is refused" "$OUT" "error:fiscal_sf_password is required"

# ------------------------------------------
# Result
# ------------------------------------------
echo ""
echo "=========================================="
if [ "$FAIL_COUNT" -gt 0 ]; then
	printf 'FAILED: %s assertion(s) passed, %s failed\n' "$PASS_COUNT" "$FAIL_COUNT"
	exit 1
fi
printf 'OK: %s assertion(s) passed\n' "$PASS_COUNT"
exit 0
