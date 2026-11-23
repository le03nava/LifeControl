#!/bin/bash
# ============================================
# LifeControl - Keycloak realm & client setup
# ============================================
# Provisions the realm, clients and roles required by lifecontrol-api and
# life-control-app-angular using the Admin REST API via kcadm.sh (run inside
# the running keycloak container). Idempotent: existing entities are kept.
#
# Usage:
#   sudo ./docker/scripts/keycloak-setup.sh [dev|staging|prod]
#   ./docker/scripts/keycloak-setup.sh --ensure-audience [dev|staging|prod]
#   ./docker/scripts/keycloak-setup.sh --verify-audience [dev|staging|prod]
#
# The plain invocation provisions everything. The two narrow modes touch ONE artifact, the platform
# audience mapper on the public client, because `deploy.sh <env> start` has to converge it and
# `deploy.sh <env> health` has to read it without a deploy rewriting the SMTP server, the registered
# origins, the user-profile policy or the client roles as a side effect (odd/tasks/deploy-realm-gate.md).
#
# They also read a different COPY of the admin credential, and that is deliberate: the plain run reads
# docker/secrets/keycloak_admin_password from this host and therefore needs root, while the narrow modes
# expand the password inside the keycloak container from the copy compose mounts there. That is what keeps
# the deploy path (`start` and `health`) usable by the operator who could not read a 0400 host file. See
# configure_kcadm for the two sources and why only one of them costs a root shell.
#
# Root is required, not a preference. The three credential files this script reads from the HOST
# (`keycloak_admin_password`, `keycloak_admin_client_secret` and `smtp_password`) are 0400 with the
# container uid that reads them as owner -- 1000 for keycloak and grafana, 1001 for the api -- so the
# operator's own account cannot read any of them (odd/tasks/docker-stack-hardening.md L21, and the
# `### Secretos` table in AGENTS.md). A credential an operator account could read is a credential any
# process that account runs could read, which is the whole point of the change; root is the only
# account that reads every one of them without widening a mode.
#
# Requires:
#   - root
#   - Keycloak container up (run ./docker/scripts/deploy.sh <env> start first)
#   - docker/secrets/keycloak_admin_password,
#     docker/secrets/keycloak_admin_client_secret and (with SMTP_AUTH=true)
#     docker/secrets/smtp_password materialized (setup-env.sh materializes the first two; the SMTP
#     credential is created by hand because it has no .template)
#
# This script contains NO secret content.
# ============================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DOCKER_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SECRETS_DIR="$DOCKER_DIR/secrets"

# ---- Modes ----
# The narrowing is a mode, not a second script: the declaration of the audience contract lives here
# (API_AUDIENCE, AUDIENCE_MAPPER, verify_audience_mapper) and both modes reuse it verbatim, so a drift
# check cannot exist in two copies and disagree with itself (D-b of odd/tasks/deploy-realm-gate.md).
usage() {
	echo "Usage: $0 [dev|staging|prod]"
	echo "       $0 --ensure-audience [dev|staging|prod]"
	echo "       $0 --verify-audience [dev|staging|prod]"
	echo ""
	echo "Modes:"
	echo "  (no flag)          - Provision the realm, the clients, the roles, the tenancy mappers, the"
	echo "                       invitation environment and the audience mapper"
	echo "  --ensure-audience  - Only the platform audience mapper: create it when it is absent, verify it when"
	echo "                       it is present, and fail on drift. What deploy.sh <env> start runs."
	echo "  --verify-audience  - Read-only: exit 0 when the mapper exists and matches the declared contract, 1"
	echo "                       when it is absent or drifted, and write nothing. What deploy.sh <env> health runs."
	echo ""
	echo "Credential:"
	echo "  (no flag) reads keycloak_admin_password and keycloak_admin_client_secret from docker/secrets/ on"
	echo "  the HOST, so it requires root: those files are 0400, owned by the container uid that reads them."
	echo "  --ensure-audience and --verify-audience read the admin password INSIDE the keycloak container, from"
	echo "  the copy compose already mounts under /run/secrets/, so they need no root and read no host secret."
	echo ""
	echo "Environments: dev, staging, prod (default: dev)."
}

MODE="all"
case "${1:-}" in
--ensure-audience)
	MODE="ensure-audience"
	ENV="${2:-dev}"
	;;
--verify-audience)
	MODE="verify-audience"
	ENV="${2:-dev}"
	;;
-h | --help)
	usage
	exit 0
	;;
-*)
	echo "ERROR: unknown option: $1" >&2
	usage >&2
	exit 1
	;;
"") ENV="dev" ;;
*) ENV="$1" ;;
esac

ENV_FILE="$DOCKER_DIR/.env.$ENV"

if [ ! -f "$ENV_FILE" ]; then
	echo "ERROR: $ENV_FILE not found — run ./docker/scripts/setup-env.sh $ENV first" >&2
	exit 1
fi

get_env() {
	local key="$1"
	local default="${2:-}"
	local value
	value="$(grep -E "^${key}=" "$ENV_FILE" | tail -1 | cut -d= -f2-)"
	echo "${value:-$default}"
}

COMPOSE_PROJECT_NAME="$(get_env COMPOSE_PROJECT_NAME "lifecontrol")"
# The keycloak container this project runs, and NOT a name built from COMPOSE_PROJECT_NAME.
#
# Only the dev override declares `container_name` for keycloak (docker/docker-compose.override.yml), so
# in staging and prod compose names the container `<project>-keycloak-1` (and `-2` in prod, which runs two
# replicas), and the composed name below would address a container that does not exist. compose is the
# only thing that knows how a project names what it runs, so the id is asked for instead -- see
# resolve_container, called before any container is touched.
CONTAINER=""
KEYCLOAK_SERVICE="keycloak"
# The compose files this environment loads, in the order compose merges them. Spelled here because this
# script does not source _common.sh; it is the same list `resolve_compose_env` builds there, and the
# realm-container-identity suite asserts the argv per environment.
COMPOSE_FILE_ARGS=(-f "$DOCKER_DIR/docker-compose.yml")
if [ "$ENV" = "dev" ]; then
	COMPOSE_FILE_ARGS+=(-f "$DOCKER_DIR/docker-compose.override.yml")
fi
if [ "$ENV" = "prod" ]; then
	COMPOSE_FILE_ARGS+=(-f "$DOCKER_DIR/docker-compose.prod.yml")
fi
KC_ADMIN_USER="$(get_env KC_ADMIN_USERNAME admin)"
REALM="$(get_env KEYCLOAK_REALM life-control-realm)"
APP_CLIENT="$(get_env KEYCLOAK_CLIENT_ID life-control-client)"
ADMIN_CLIENT="$(get_env KEYCLOAK_ADMIN_CLIENT_ID life-control-admin-client)"
KC_BASE="http://localhost:$(get_env KC_HTTP_PORT 8080)"
APP_ORIGIN="$(get_env WEB_APP_URL "http://localhost:$(get_env WEB_APP_PORT 4200)")"
# Keycloak's own host-reachable public base URL. This is the value written as the
# realm's frontendUrl (attributes.frontendUrl) below, and it is DELIBERATELY not
# APP_ORIGIN: frontendUrl fixes the realm issuer that the whole stack validates
# (KEYCLOAK_ISSUER_URI) and the host of the action-token link, while APP_ORIGIN
# is where the public client's redirectUris/webOrigins point and where the person
# lands after the action token is consumed. The fallback port is the one
# .env.template declares for KEYCLOAK_PORT (measured 2026-10-06: KEYCLOAK_PORT=8181
# and KEYCLOAK_URL=http://localhost:8181).
KEYCLOAK_URL="$(get_env KEYCLOAK_URL "http://localhost:$(get_env KEYCLOAK_PORT 8181)")"

print_status() { echo -e "\033[0;34m[INFO]\033[0m $*"; }
print_success() { echo -e "\033[0;32m[SUCCESS]\033[0m $*"; }
print_error() { echo -e "\033[0;31m[ERROR]\033[0m $*"; }

kcadm() {
	docker exec "$CONTAINER" /opt/keycloak/bin/kcadm.sh "$@"
}

# resolve_container: sets CONTAINER to the id of the keycloak container this project runs, resolved THROUGH
# COMPOSE, or aborts naming the cause.
#
# The first id is enough and it is deliberate: prod runs two replicas (docker/docker-compose.prod.yml sets
# `deploy.replicas: 2` for keycloak) and they share ONE realm database, so provisioning the realm through
# either of them provisions it for both.
#
# A missing container is reported as a missing container, never as a start that is merely slow: an
# operator fixes "compose has no keycloak container" by starting the stack, and "it never became healthy"
# by reading its logs, and one message for both sends them to the wrong place.
resolve_container() {
	local out
	CONTAINER=""
	if ! out="$(docker compose "${COMPOSE_FILE_ARGS[@]}" --env-file "$ENV_FILE" ps -q "$KEYCLOAK_SERVICE" 2>/dev/null)"; then
		print_error "Could not ask compose for the $KEYCLOAK_SERVICE container of the $ENV project ($COMPOSE_PROJECT_NAME)."
		print_error "Check the stack: ./docker/scripts/deploy.sh $ENV status"
		exit 1
	fi
	CONTAINER="$(printf '%s\n' "$out" | head -1 | tr -d '\r')"
	if [ -z "$CONTAINER" ]; then
		print_error "There is no $KEYCLOAK_SERVICE container in the $ENV project ($COMPOSE_PROJECT_NAME), so its realm cannot be provisioned."
		print_error "This is not a slow start — there is nothing to converge. Start the stack first: ./docker/scripts/deploy.sh $ENV up"
		exit 1
	fi
}

# ---- Fail-closed reads ----
# `kcadm get` exits 1 both when a resource is genuinely absent and when the Admin
# API did not answer at all (measured 2026-10-02: an absent resource prints
# "Resource not found for url: ...", while an unreachable Admin API prints
# "HTTP request error: ..."). Reading the second as "absent" is how the script
# would provision on top of a dead Admin API, so the only discriminator is that
# diagnostic text.
#
# The read helper passes its output through the global KCADM_GET_OUT and never
# through stdout captured by the caller: `exit 1` inside a command substitution or
# a pipeline element runs in a subshell and kills only that subshell, which would
# turn a hard abort back into a plain non-zero exit the caller reads as "absent".
# Call sites therefore invoke it directly (`kcadm_get ... || return 1`), never as
# `$(kcadm_get ...)` and never as `kcadm_get ... | grep ...`. The helpers below
# follow the same rule and report through their own globals (KCADM_GET_OUT,
# MAPPER_ID, CLIENT_ID) instead of echoing, so no caller has to capture them.
KCADM_GET_OUT=""
kcadm_get() {
	local out status
	if out="$(kcadm get "$@" 2>&1)"; then
		KCADM_GET_OUT="$out"
		return 0
	else
		status=$?
	fi
	if printf '%s' "$out" | grep -q "Resource not found"; then
		KCADM_GET_OUT=""
		return 1
	fi
	print_error "The Admin API did not answer the read of '$*' (exit $status):" >&2
	print_error "  $out" >&2
	print_error "Refusing to read an unanswered Admin API call as \"not found\"." >&2
	exit 1
}

client_exists() {
	local client_id="$1"
	local count
	kcadm_get clients -r "$REALM" -q clientId="$client_id" --fields id --format csv --noquotes || return 1
	count="$(printf '%s\n' "$KCADM_GET_OUT" | tr -d '\r' | grep -c . || true)"
	[ "$count" -gt 0 ]
}

# Resolves a client's id into CLIENT_ID. It reports through the global rather than
# through stdout, for the same reason kcadm_get does: its callers run under
# `set -e`, but a substitution that is merely *wrapped* in a condition, placed
# behind `||`, or run with errexit off would swallow the failure and continue
# with an empty id — the silent misread this block exists to remove.
CLIENT_ID=""
resolve_client_id() {
	local client_id="$1"
	CLIENT_ID=""
	if ! kcadm_get clients -r "$REALM" -q clientId="$client_id" --fields id --format csv --noquotes; then
		print_error "Client $client_id is absent from realm $REALM, so its id cannot be resolved." >&2
		return 1
	fi
	CLIENT_ID="$(printf '%s\n' "$KCADM_GET_OUT" | tr -d '\r' | tr -d '"' | head -1)"
	if [ -z "$CLIENT_ID" ]; then
		print_error "Client $client_id resolved to an empty id in realm $REALM; refusing to continue silently." >&2
		return 1
	fi
	return 0
}

client_role_exists() {
	local cid="$1"
	local role="$2"
	kcadm_get "clients/$cid/roles/$role" -r "$REALM"
}

# Resolves the id of a claim's protocol mapper on the app client. Sets MAPPER_ID
# (empty when the claim has no mapper) and returns non-zero when the read failed
# or the claim has no mapper. `-q name=...` is NOT used: measured 2026-10-02, it
# ignores the query and returns the whole list, so the name is selected
# client-side from the id,name CSV.
MAPPER_ID=""
find_mapper_id() {
	local claim="$1"
	MAPPER_ID=""
	kcadm_get "clients/$APP_CID/protocol-mappers/models" -r "$REALM" --fields id,name --format csv --noquotes || return 1
	MAPPER_ID="$(printf '%s\n' "$KCADM_GET_OUT" | tr -d '\r' | grep -E "^[^,]*,${claim}$" | head -1 | cut -d, -f1)"
	[ -n "$MAPPER_ID" ]
}

realm_role_exists() {
	local role="$1"
	kcadm_get "roles/$role" -r "$REALM"
}

# json_array_values <field> <json> -> one array value per line. Used by the
# additive convergence of the public client's registered origins. The single
# read is pretty-printed, so newlines are flattened first; the values are
# selected client-side with POSIX grep/tr because the Keycloak image ships no
# jq/python/awk (the parsing runs on the host).
json_array_values() {
	local field="$1" json="$2"
	printf '%s' "$json" | tr -d '\n' \
		| grep -o "\"$field\"[[:space:]]*:[[:space:]]*\[[^]]*\]" \
		| grep -o '"[^"]*"' | tail -n +2 | tr -d '"'
}

# build_json_array <values, one per line> -> one compact JSON array argument.
# Passing the full merged list (rather than kcadm's `+=`, which this version
# rejects for an array literal) is what makes the preserved set explicit.
build_json_array() {
	local values="$1" out="[" sep="" value
	while IFS= read -r value; do
		[ -z "$value" ] && continue
		out="$out$sep\"$value\""
		sep=","
	done <<<"$values"
	printf '%s]' "$out"
}

# ---- The audience mapper: the one artifact the deploy path touches ----
#
# An issuer allowlist answers "which realm signed this", never "who was this minted for". The API and
# the gateway both validate `aud` (JwtAudienceValidator in each module), so the realm has to emit the
# platform audience: without this mapper Keycloak emits `aud: ["account"]` and every real token would be
# rejected with 401. One fixed custom audience, on the public client, and the same value the two
# services default to (`keycloak.allowed-audiences` / `lifecontrol.gateway.allowed-audiences`).
#
# It sits OUTSIDE the tenancy loop on purpose: KeycloakClaimMapperCoverageTest bounds that loop to
# TENANCY_CLAIMS and asserts its body binds a user attribute per claim, so a second mapper inside it
# would be verified against the wrong contract. It sits ABOVE the container check so that the two narrow
# modes below can reach it without running the rest of this script.
API_AUDIENCE="$(get_env KEYCLOAK_API_AUDIENCE life-control-api)"
AUDIENCE_MAPPER="api-audience"

# Verifies the audience mapper's JSON against the contract the decoders enforce. Same shape as
# verify_tenancy_mapper: whitespace stripped from the pretty-printed read-back, then fixed-string match.
verify_audience_mapper() {
	local json="$1"
	local compact requirement
	compact="$(printf '%s' "$json" | tr -d ' \n\t')"
	for requirement in \
		"\"protocolMapper\":\"oidc-audience-mapper\"" \
		"\"included.custom.audience\":\"$API_AUDIENCE\"" \
		"\"access.token.claim\":\"true\"" \
		"\"id.token.claim\":\"false\""; do
		if ! printf '%s' "$compact" | grep -qF "$requirement"; then
			print_error "Protocol mapper $APP_CLIENT/$AUDIENCE_MAPPER is missing $requirement." >&2
			print_error "$APP_CLIENT declares oidc-audience-mapper with included.custom.audience=$API_AUDIENCE, access.token.claim=true and id.token.claim=false." >&2
			print_error "The script fails rather than repairs a drifted mapper; an operator must fix the authorization artifact deliberately." >&2
			exit 1
		fi
	done
}

# The converging half -- what the plain invocation and `--ensure-audience` both run: create the mapper
# when it is absent, verify it when it is present, and refuse a drifted one instead of repairing it.
ensure_audience_mapper() {
	if find_mapper_id "$AUDIENCE_MAPPER"; then
		# Re-read the existing mapper by id instead of trusting its name in the list.
		kcadm_get "clients/$APP_CID/protocol-mappers/models/$MAPPER_ID" -r "$REALM" || {
			print_error "Protocol mapper $APP_CLIENT/$AUDIENCE_MAPPER exists but could not be read back by id $MAPPER_ID." >&2
			exit 1
		}
		verify_audience_mapper "$KCADM_GET_OUT"
		print_success "Protocol mapper $APP_CLIENT/$AUDIENCE_MAPPER exists and matches the declared aud"
	else
		print_status "Creating protocol mapper $APP_CLIENT/$AUDIENCE_MAPPER..."
		kcadm create "clients/$APP_CID/protocol-mappers/models" -r "$REALM" \
			-s name="$AUDIENCE_MAPPER" \
			-s protocol=openid-connect \
			-s protocolMapper=oidc-audience-mapper \
			-s "config.\"included.custom.audience\"=$API_AUDIENCE" \
			-s "config.\"access.token.claim\"=true" \
			-s "config.\"id.token.claim\"=false" >/dev/null
		# Do not trust the create's exit code: read the new mapper back and verify what it means.
		if ! find_mapper_id "$AUDIENCE_MAPPER"; then
			print_error "Protocol mapper $APP_CLIENT/$AUDIENCE_MAPPER was created but is absent from the mapper list read back." >&2
			exit 1
		fi
		kcadm_get "clients/$APP_CID/protocol-mappers/models/$MAPPER_ID" -r "$REALM" || {
			print_error "Protocol mapper $APP_CLIENT/$AUDIENCE_MAPPER was created but could not be read back by id $MAPPER_ID." >&2
			exit 1
		}
		verify_audience_mapper "$KCADM_GET_OUT"
		print_success "Protocol mapper $APP_CLIENT/$AUDIENCE_MAPPER created and verified"
	fi
}

# The read-only half -- what `--verify-audience` runs: 0 when the mapper exists and matches the declared
# contract, 1 naming the mapper and what is missing when it does not. It answers and never writes, because
# a health check that repairs what it is asked to report hides the state it was supposed to surface (D-d).
audience_mapper_is_declared() {
	if ! find_mapper_id "$AUDIENCE_MAPPER"; then
		print_error "Protocol mapper $APP_CLIENT/$AUDIENCE_MAPPER is absent from realm $REALM." >&2
		print_error "Without it Keycloak does not mint aud=$API_AUDIENCE, and both JWT decoders reject every token." >&2
		return 1
	fi
	kcadm_get "clients/$APP_CID/protocol-mappers/models/$MAPPER_ID" -r "$REALM" || {
		print_error "Protocol mapper $APP_CLIENT/$AUDIENCE_MAPPER exists but could not be read back by id $MAPPER_ID." >&2
		return 1
	}
	verify_audience_mapper "$KCADM_GET_OUT"
}

# Resolves the public client of an ALREADY provisioned realm into APP_CID. Read-only: the narrow modes
# refuse an unprovisioned realm instead of creating it, because the realm, its clients and its roles are
# the rest of this script -- exactly what a deploy must not converge as a side effect (D-a).
resolve_provisioned_client() {
	APP_CID=""
	if ! kcadm_get "realms/$REALM"; then
		print_error "Realm $REALM is absent, so it has no public client to carry the audience mapper." >&2
		print_error "Provision the realm first: sudo ./docker/scripts/keycloak-setup.sh $ENV" >&2
		exit 1
	fi
	if ! client_exists "$APP_CLIENT"; then
		print_error "Client $APP_CLIENT is absent from realm $REALM, so it cannot carry the audience mapper." >&2
		print_error "Provision the clients first: sudo ./docker/scripts/keycloak-setup.sh $ENV" >&2
		exit 1
	fi
	resolve_client_id "$APP_CLIENT" || exit 1
	APP_CID="$CLIENT_ID"
}

resolve_container
print_status "Using the $KEYCLOAK_SERVICE container compose reports for the $ENV project: $CONTAINER"

# ---- Credentials from secrets (no plaintext in env files) ----
KC_ADMIN_PASS_FILE="$SECRETS_DIR/keycloak_admin_password"
ADM_CLIENT_SECRET_FILE="$SECRETS_DIR/keycloak_admin_client_secret"
# The SAME admin credential as it exists inside the keycloak container: compose mounts every file of
# docker/secrets/ read-only under /run/secrets/<name>, and the container's own wrapper already reads this
# one there (docker/entrypoints/keycloak-entrypoint.sh exports the bootstrap admin password from it). The
# name is derived from the host file it pairs with, so the two cannot be renamed apart.
KC_ADMIN_PASS_SECRET="$(basename "$KC_ADMIN_PASS_FILE")"
KC_ADMIN_PASS_IN_CONTAINER="/run/secrets/$KC_ADMIN_PASS_SECRET"

# Readable, not merely present: the host copies are 0400 with their container uid as owner, so a non-root
# run reaches `cat` with `Permission denied` under `set -e`, which names neither the reason nor the fix.
# This turns that into one sentence that does. It runs in the CURRENT shell and never in a command
# substitution: an `exit 1` inside one kills only the subshell, which would turn this abort into a plain
# non-zero exit its caller could read as "absent".
require_host_secret() {
	local file="$1"
	if [ -s "$file" ] && [ ! -r "$file" ]; then
		print_error "Cannot read $file as uid $(id -u): the file is owned by the container uid that reads it, mode 0400."
		print_error "Run this script as root: sudo ./docker/scripts/keycloak-setup.sh $ENV"
		exit 1
	fi
	if [ ! -s "$file" ]; then
		print_error "Missing or empty secret file $file — run ./docker/scripts/setup-env.sh $ENV"
		exit 1
	fi
	if grep -q "CHANGEME" "$file"; then
		print_error "Secret $file still holds the CHANGEME placeholder — set a real value first"
		exit 1
	fi
}

# configure_kcadm <host|container>
#
# Logs kcadm in for THIS run from whichever copy of the admin credential the mode is allowed to read. ONE
# helper, because the two modes differ only in WHERE the secret comes from, never in what is done with it:
# a second copy of the login is how the two sources would drift apart.
#
#   host       the full run, and the only mode that needs root. The password is read on this host and
#              passed as an argv of this process, exactly as it always has been; the 0400/gid model in
#              `### Secretos` is what makes that a root operation, and it is deliberately unchanged here.
#   container  the two narrow modes -- what `deploy.sh <env> start` and `deploy.sh <env> health` run. The
#              container's own shell expands the password from the copy compose mounts there, so it never
#              reaches this host process, this script's environment, its argv or `docker inspect`, and an
#              operator who cannot read a 0400 host file can still deploy and read the health verdict.
#
# The inner script receives the path and the two non-secret values as positional arguments ($1..$3)
# instead of being assembled by string interpolation, so no command line ever carries the secret.
configure_kcadm() {
	local credential_source="$1"
	print_status "Authenticating kcadm as $KC_ADMIN_USER against $CONTAINER ($credential_source credential)..."
	if [ "$credential_source" = "container" ]; then
		docker exec "$CONTAINER" /bin/sh -c \
			'/opt/keycloak/bin/kcadm.sh config credentials --server "$2" --realm master --user "$3" --password "$(cat "$1")" >/dev/null' \
			kcadm-credential "$KC_ADMIN_PASS_IN_CONTAINER" "$KC_BASE" "$KC_ADMIN_USER"
		return 0
	fi
	kcadm config credentials --server "$KC_BASE" --realm master --user "$KC_ADMIN_USER" --password "$KC_ADMIN_PASS" >/dev/null
}

if [ "$MODE" = "all" ]; then
	# The full run reads both credentials from the HOST and therefore keeps its root requirement. Nothing
	# here is relaxed: the model in `### Secretos` is that a credential an operator account can read is a
	# credential any process of that account can read, so the full run stays a root operation.
	for f in "$KC_ADMIN_PASS_FILE" "$ADM_CLIENT_SECRET_FILE"; do
		require_host_secret "$f"
	done
	KC_ADMIN_PASS="$(cat "$KC_ADMIN_PASS_FILE")"
	ADM_CLIENT_SECRET="$(cat "$ADM_CLIENT_SECRET_FILE")"
	configure_kcadm host
else
	# No host secret is read in the narrow modes, and none has to be: the audience mapper needs neither the
	# admin client's secret nor the SMTP credential. Reading them here is what would put root back in the
	# way of `deploy.sh <env> start` and `deploy.sh <env> health`.
	configure_kcadm container
fi

# ---- Mode dispatch: the narrow modes end here ----
# `--ensure-audience` converges the one artifact a deploy has to leave in place; `--verify-audience` reads
# it and writes nothing. Both refuse a realm that was never provisioned and name the script to run.
if [ "$MODE" != "all" ]; then
	resolve_provisioned_client
	if [ "$MODE" = "ensure-audience" ]; then
		ensure_audience_mapper
	else
		audience_mapper_is_declared || exit 1
		print_success "Protocol mapper $APP_CLIENT/$AUDIENCE_MAPPER exists and matches the declared aud"
	fi
	exit 0
fi

# ---- SMTP relay configuration (the invitation environment) ----
# The realm's SMTP server is declared by the environment, with the DEV mail
# container as the default. Outside dev that default must never survive: a
# staging or prod realm silently relaying every invitation to a local mailbox is
# a delivery failure no Java test can see. `get_env` returns the default for an
# absent, commented or empty value, so ONE comparison covers all three ways the
# dev value arrives by accident. Dev may deliberately point at a real relay;
# only the non-dev refusal is enforced.
SMTP_HOST="$(get_env SMTP_HOST mailpit)"
SMTP_PORT="$(get_env SMTP_PORT 1025)"
SMTP_FROM="$(get_env SMTP_FROM "no-reply@lifecontrol.local")"
SMTP_STARTTLS="$(get_env SMTP_STARTTLS false)"
SMTP_AUTH="$(get_env SMTP_AUTH false)"
SMTP_USER="$(get_env SMTP_USER "")"
SMTP_PASSWORD_FILE="$SECRETS_DIR/smtp_password"

DEV_SMTP_HOST="mailpit"
if [ "$ENV" != "dev" ] && [ "$SMTP_HOST" = "$DEV_SMTP_HOST" ]; then
	print_error "SMTP_HOST resolves to '$SMTP_HOST', the dev mail container's service name, in the $ENV environment." >&2
	print_error "That would relay every $ENV email to the development mailbox. Set SMTP_HOST in $ENV_FILE to a real relay." >&2
	exit 1
fi

# ---- Keycloak public URL (frontendUrl) fail-closed outside dev ----
# frontendUrl is Keycloak's own host-reachable public URL (KEYCLOAK_URL), not the
# app origin. A wrong value silently changes the realm ISSUER — measured
# 2026-10-06: frontendUrl=http://localhost:4200 made the issuer
# http://localhost:4200/realms/<realm> while the stack validates
# http://localhost:8181/realms/<realm>, so every authenticated call answered 401
# on a valid token. Worse than the SMTP default, so the same fail-closed rule
# applies: outside dev a value that still resolves to a localhost/dev default
# must never survive. `get_env` returns the fallback for an absent, commented or
# empty value, so ONE comparison covers all three ways the dev value arrives by
# accident. The internal compose service name (`keycloak`) is also refused: it is
# not host-reachable, which is exactly the wrong address the mechanism avoids.
frontend_url_is_dev_default() {
	local url="$1" host
	host="${url#*://}"
	host="${host%%/*}"
	host="${host%%:*}"
	case "$host" in
		localhost | 127.0.0.1 | keycloak) return 0 ;;
		*) return 1 ;;
	esac
}
if [ "$ENV" != "dev" ] && frontend_url_is_dev_default "$KEYCLOAK_URL"; then
	print_error "KEYCLOAK_URL resolves to '$KEYCLOAK_URL', a localhost/dev default, in the $ENV environment." >&2
	print_error "frontendUrl is Keycloak's own public URL: it sets the realm issuer the whole stack validates (KEYCLOAK_ISSUER_URI) and the host of the invitation link." >&2
	print_error "Set KEYCLOAK_URL in $ENV_FILE to a reachable Keycloak base URL." >&2
	exit 1
fi

# The SMTP credential is read directly from docker/secrets/smtp_password the way
# keycloak_admin_password is. It DELIBERATELY has no .template: setup-env.sh
# materializes every template and validate-env.sh rejects a CHANGEME value, so a
# template would either ship a placeholder or fail every environment that does
# not relay mail. Required only when SMTP_AUTH=true.
SMTP_PASSWORD=""
if [ "$SMTP_AUTH" = "true" ]; then
	if [ -z "$SMTP_USER" ]; then
		print_error "SMTP_AUTH=true requires a non-empty SMTP_USER in $ENV_FILE." >&2
		exit 1
	fi
	if [ -s "$SMTP_PASSWORD_FILE" ] && [ ! -r "$SMTP_PASSWORD_FILE" ]; then
		print_error "Cannot read $SMTP_PASSWORD_FILE as uid $(id -u): it is 0400, owned by the uid that reads it (grafana, 1000)." >&2
		print_error "Run this script as root: sudo ./docker/scripts/keycloak-setup.sh $ENV" >&2
		exit 1
	fi
	if [ ! -s "$SMTP_PASSWORD_FILE" ]; then
		print_error "SMTP_AUTH=true but the credential file $SMTP_PASSWORD_FILE is missing or empty." >&2
		print_error "Create it by hand; it has no .template on purpose (see docker/.env.template)." >&2
		exit 1
	fi
	if grep -q "CHANGEME" "$SMTP_PASSWORD_FILE"; then
		print_error "SMTP_AUTH=true but $SMTP_PASSWORD_FILE still holds the CHANGEME placeholder." >&2
		exit 1
	fi
	SMTP_PASSWORD="$(cat "$SMTP_PASSWORD_FILE")"
fi

print_status "SMTP relay: $SMTP_HOST:$SMTP_PORT from $SMTP_FROM (starttls=$SMTP_STARTTLS, auth=$SMTP_AUTH)"

# ---- Realm ----
if kcadm_get "realms/$REALM"; then
	print_success "Realm $REALM already exists"
else
	print_status "Creating realm $REALM..."
	kcadm create realms -s realm="$REALM" -s enabled=true >/dev/null
	print_success "Realm $REALM created"
fi

# ---- Realm invitation environment: frontendUrl + SMTP server ----
# frontendUrl is Keycloak's OWN host-reachable public base URL (KEYCLOAK_URL), NOT
# the app origin. It fixes the realm ISSUER: measured 2026-10-06 on Keycloak
# 26.2.1, setting frontendUrl=http://localhost:4200 turned the issuer into
# http://localhost:4200/realms/<realm>, while the stack validates
# KEYCLOAK_ISSUER_URI=http://localhost:8181/realms/<realm>, so every
# authenticated call failed with HTTP 401 on a valid token. It is also the base
# of the action-token link, which must resolve to Keycloak. Leaving it unset is
# wrong too: the app triggers the email through the Admin API at
# http://keycloak:8080, so the link would be generated as
# http://localhost:8080/... — a port nothing serves on the host. The person
# returns to the app afterwards through the token's redirect_uri claim, which is
# why execute-actions-email is passed APP_ORIGIN separately.
#
# frontendUrl is a realm ATTRIBUTE, not a top-level RealmRepresentation field:
# measured 2026-10-06, `-s frontendUrl=...` is rejected with
# `Unrecognized field "frontendUrl"`, while `-s attributes.frontendUrl=...`
# lands. The action email sent by execute-actions-email points at
# <frontendUrl>/realms/<realm>/login-actions/action-token?key=...
#
# The smtpServer map is written whole (the Admin API replaces it: measured, a
# write without `password` clears a previously stored one) and read back from the
# FULL realm representation: `--fields smtpServer` answers `{ }` on this version,
# so a projection-based read would compare against nothing. The read-back masks
# `password` as `**********`, so presence is verifiable and the value is not.
smtp_json="{\"host\":\"$SMTP_HOST\",\"port\":\"$SMTP_PORT\",\"from\":\"$SMTP_FROM\",\"starttls\":\"$SMTP_STARTTLS\",\"auth\":\"$SMTP_AUTH\""
if [ "$SMTP_AUTH" = "true" ]; then
	smtp_json="$smtp_json,\"user\":\"$SMTP_USER\",\"password\":\"$SMTP_PASSWORD\""
fi
smtp_json="$smtp_json}"

# smtp_fields lists the non-secret fields whose exact value the read-back can and
# does assert.
smtp_fields=(host port from starttls auth)
if [ "$SMTP_AUTH" = "true" ]; then
	smtp_fields+=(user)
fi

# realm_converged <realm-json> -> 0 when the current representation already
# declares this frontendUrl and every expected SMTP field. Keycloak normalises
# the map to strings, so the comparison is against the values as written.
realm_converged() {
	local compact block field expected
	compact="$(printf '%s' "$1" | tr -d ' \n\t')"
	if ! printf '%s' "$compact" | grep -qF "\"frontendUrl\":\"$KEYCLOAK_URL\""; then
		return 1
	fi
	block="$(printf '%s' "$compact" | grep -o '"smtpServer":{[^}]*}' | head -1)"
	if [ -z "$block" ]; then
		return 1
	fi
	for field in "${smtp_fields[@]}"; do
		case "$field" in
			host) expected="$SMTP_HOST" ;;
			port) expected="$SMTP_PORT" ;;
			from) expected="$SMTP_FROM" ;;
			starttls) expected="$SMTP_STARTTLS" ;;
			auth) expected="$SMTP_AUTH" ;;
			user) expected="$SMTP_USER" ;;
		esac
		if ! printf '%s' "$block" | grep -qF "\"$field\":\"$expected\""; then
			return 1
		fi
	done
	if [ "$SMTP_AUTH" = "true" ]; then
		printf '%s' "$block" | grep -qF '"password":"' || return 1
	fi
	return 0
}

# Read the realm from the FULL representation (see the --fields note above) and
# converge only what does not already match, then verify the read-back.
kcadm_get "realms/$REALM" || {
	print_error "Could not read realm $REALM to converge its invitation environment." >&2
	exit 1
}
if realm_converged "$KCADM_GET_OUT"; then
	print_success "Realm $REALM frontendUrl and smtpServer already match the declared configuration"
else
	print_status "Converging realm $REALM frontendUrl and smtpServer..."
	kcadm update "realms/$REALM" \
		-s "attributes.frontendUrl=$KEYCLOAK_URL" \
		-s "smtpServer=$smtp_json" >/dev/null
	kcadm_get "realms/$REALM" || {
		print_error "Realm $REALM could not be read back after the invitation-environment update." >&2
		exit 1
	}
	if ! realm_converged "$KCADM_GET_OUT"; then
		print_error "Realm $REALM frontendUrl or smtpServer does not match the declared configuration after the update; refusing to continue on a write nobody verified." >&2
		exit 1
	fi
	print_success "Realm $REALM frontendUrl verified: $KEYCLOAK_URL"
	print_success "Realm $REALM smtpServer verified: $SMTP_HOST:$SMTP_PORT from $SMTP_FROM (starttls=$SMTP_STARTTLS, auth=$SMTP_AUTH)"
fi

# ---- Public client (frontend) ----
if client_exists "$APP_CLIENT"; then
	resolve_client_id "$APP_CLIENT" || exit 1
	APP_CID="$CLIENT_ID"
	print_success "Client $APP_CLIENT already exists ($APP_CID)"

	# ---- Additive convergence of the public client's registered origins ----
	# Cross-origin login breaks for one origin at a time when a redirect URI is
	# missing, so this converges ADDITIVELY: every value already registered is
	# preserved and only an absent app origin is added. It is deliberately not the
	# fail-closed treatment the tenancy mappers get — a missing mapper is an
	# authorization hole, a missing redirect URI is a login broken for one origin.
	#
	# `kcadm update -s "redirectUris+=[...]"` does NOT work on this version
	# (measured 2026-10-06: an array literal answers `Cannot parse the JSON
	# [unknown_error]` / `A redirect URI is not a valid URI`; only a single bare
	# value appends), so this does read-merge-write, which also makes the preserved
	# set explicit and verifiable in one read-back.
	expected_redirect="${APP_ORIGIN}/*"
	expected_origin="$APP_ORIGIN"

	# client_lists_match <client-json> -> 0 when both lists already hold the app
	# origin. Reads through the global-free json_array_values helper.
	client_lists_match() {
		local json="$1"
		json_array_values redirectUris "$json" | grep -qxF "$expected_redirect" &&
			json_array_values webOrigins "$json" | grep -qxF "$expected_origin"
	}

	kcadm_get "clients/$APP_CID" -r "$REALM" --fields redirectUris,webOrigins || {
		print_error "Could not read the redirect URIs of client $APP_CLIENT ($APP_CID)." >&2
		exit 1
	}
	if client_lists_match "$KCADM_GET_OUT"; then
		print_success "Client $APP_CLIENT redirectUris/webOrigins already include $APP_ORIGIN"
	else
		print_status "Converging $APP_CLIENT redirectUris/webOrigins to include $APP_ORIGIN..."
		redirects="$(json_array_values redirectUris "$KCADM_GET_OUT")"
		origins="$(json_array_values webOrigins "$KCADM_GET_OUT")"
		if ! printf '%s\n' "$redirects" | grep -qxF "$expected_redirect"; then
			redirects="${redirects}${redirects:+
}$expected_redirect"
		fi
		if ! printf '%s\n' "$origins" | grep -qxF "$expected_origin"; then
			origins="${origins}${origins:+
}$expected_origin"
		fi
		redirect_arg="$(build_json_array "$redirects")"
		origin_arg="$(build_json_array "$origins")"
		kcadm update "clients/$APP_CID" -r "$REALM" \
			-s "redirectUris=$redirect_arg" \
			-s "webOrigins=$origin_arg" >/dev/null
		kcadm_get "clients/$APP_CID" -r "$REALM" --fields redirectUris,webOrigins || {
			print_error "Client $APP_CLIENT could not be read back after the redirect-URI update." >&2
			exit 1
		}
		if ! client_lists_match "$KCADM_GET_OUT"; then
			print_error "Client $APP_CLIENT redirectUris/webOrigins do not contain $APP_ORIGIN after the update; refusing to continue on a write nobody verified." >&2
			exit 1
		fi
		print_success "Client $APP_CLIENT redirectUris/webOrigins include $APP_ORIGIN (existing entries preserved)"
	fi
else
	print_status "Creating public client $APP_CLIENT..."
	kcadm create clients -r "$REALM" \
		-s clientId="$APP_CLIENT" \
		-s enabled=true \
		-s publicClient=true \
		-s standardFlowEnabled=true \
		-s directAccessGrantsEnabled=true \
		-s "redirectUris=[\"${APP_ORIGIN}/*\"]" \
		-s "webOrigins=[\"${APP_ORIGIN}\"]" >/dev/null
	resolve_client_id "$APP_CLIENT" || exit 1
	APP_CID="$CLIENT_ID"
	print_success "Client $APP_CLIENT created ($APP_CID)"
fi

# ---- Confidencial client (backend admin / service account) ----
if client_exists "$ADMIN_CLIENT"; then
	resolve_client_id "$ADMIN_CLIENT" || exit 1
	ADM_CID="$CLIENT_ID"
	print_success "Client $ADMIN_CLIENT already exists ($ADM_CID)"
else
	print_status "Creating confidential client $ADMIN_CLIENT (service account)..."
	kcadm create clients -r "$REALM" \
		-s clientId="$ADMIN_CLIENT" \
		-s enabled=true \
		-s publicClient=false \
		-s standardFlowEnabled=false \
		-s serviceAccountsEnabled=true \
		-s secret="$ADM_CLIENT_SECRET" >/dev/null
	resolve_client_id "$ADMIN_CLIENT" || exit 1
	ADM_CID="$CLIENT_ID"
	print_success "Client $ADMIN_CLIENT created ($ADM_CID)"
fi

# ---- Client roles (hierarchical flat set, frontend RBAC) ----
for role in lc-admin lc-company lc-company-read lc-company-country lc-company-country-read \
            lc-company-region lc-company-region-read lc-company-zone lc-company-zone-read \
            lc-company-store lc-company-store-read lc-receiving \
            lc-country lc-status lc-status-type lc-payment-method lc-measure-unit lc-currency \
            lc-product-supplier lc-sales lc-scheduling lc-scheduling-read \
            lc-tax lc-department lc-position lc-seniority-level lc-employee lc-employee-access; do
	if client_role_exists "$APP_CID" "$role"; then
		print_success "Client role $APP_CLIENT/$role exists"
	else
		kcadm create "clients/$APP_CID/roles" -r "$REALM" -s name="$role" -s description="LifeControl $role" >/dev/null
		print_success "Client role $APP_CLIENT/$role created"
	fi
done

# ---- User profile: let administrators store unmanaged attributes ----
# The tenancy attributes (company_id ... company_store_id) travel as Keycloak user
# attributes, but this realm has the declarative user profile enabled with only
# username, email, firstName and lastName declared. With the unmanaged-attribute
# policy unset, writing company_id through the admin REST API still answers 204
# and the attribute is silently DISCARDED: reading it back yields null, the
# protocol mappers below emit no claim, and every scoped caller is denied — a
# failure no Java test can see, because it happens inside Keycloak. ADMIN_EDIT
# (never ENABLED) lets the administrator endpoints — the path an access
# projection writes through — manage unmanaged attributes, while keeping the
# subject from assigning itself a tenancy. This step runs before the mapper
# block below because the attributes have to be storable for the mappers to mean
# anything; KeycloakClaimMapperCoverageTest pins the setting.
if ! kcadm_get users/profile -r "$REALM" --fields unmanagedAttributePolicy --format csv --noquotes; then
	print_error "Could not read the unmanaged attribute policy of realm $REALM; refusing to guess." >&2
	exit 1
fi
if [ "$(printf '%s\n' "$KCADM_GET_OUT" | tr -d '\r')" = "ADMIN_EDIT" ]; then
	print_success "User profile unmanaged attribute policy already ADMIN_EDIT"
else
	print_status "Setting user profile unmanaged attribute policy to ADMIN_EDIT..."
	kcadm update users/profile -r "$REALM" -s unmanagedAttributePolicy=ADMIN_EDIT >/dev/null
	# Read the policy back: an update nobody verifies is how the tenancy attributes
	# would be silently DISCARDED, which no Java test can observe.
	if ! kcadm_get users/profile -r "$REALM" --fields unmanagedAttributePolicy --format csv --noquotes; then
		print_error "Could not read back the unmanaged attribute policy of realm $REALM." >&2
		exit 1
	fi
	if [ "$(printf '%s\n' "$KCADM_GET_OUT" | tr -d '\r')" = "ADMIN_EDIT" ]; then
		print_success "User profile unmanaged attribute policy set and verified ADMIN_EDIT"
	else
		print_error "User profile unmanaged attribute policy is not ADMIN_EDIT after the update; the tenancy attributes would be silently DISCARDED, so the script refuses to continue." >&2
		exit 1
	fi
fi

# ---- Tenancy claim protocol mappers (must match ScopeLevel.claim()) ----
# A scoped caller is authorized by the top-level JWT claims company_id,
# company_country_id, company_region_id, company_zone_id and company_store_id,
# read by CurrentUserContext.extractUuidSetFromClaim. An attribute alone puts
# nothing in the token: each claim needs its own mapper on the app client. The
# membership travels as multivalued Keycloak user attributes (the backend's
# PUT /api/users-admin/users/{id}/attributes/{key}), and multivalued=true is
# what makes several values arrive as a JSON array instead of a single
# collapsed value. This array is the single home of the claim names in this
# script and is pinned to ScopeLevel.claim() by
# KeycloakClaimMapperCoverageTest.
TENANCY_CLAIMS=(
	company_id
	company_country_id
	company_region_id
	company_zone_id
	company_store_id
)

# Verifies a mapper's JSON against the tenancy contract the script declares.
# The single-mapper read is pretty-printed, so whitespace is stripped before the
# fixed-string matches. The Keycloak image ships no jq, python or awk, but this
# parsing runs on the host, so POSIX grep/tr is used.
verify_tenancy_mapper() {
	local claim="$1"
	local json="$2"
	local compact requirement
	compact="$(printf '%s' "$json" | tr -d ' \n\t')"
	for requirement in \
		"\"protocolMapper\":\"oidc-usermodel-attribute-mapper\"" \
		"\"claim.name\":\"$claim\"" \
		"\"user.attribute\":\"$claim\"" \
		"\"multivalued\":\"true\"" \
		"\"access.token.claim\":\"true\"" \
		"\"id.token.claim\":\"false\"" \
		"\"userinfo.token.claim\":\"false\"" \
		"\"jsonType.label\":\"String\""; do
		if ! printf '%s' "$compact" | grep -qF "$requirement"; then
			print_error "Protocol mapper $APP_CLIENT/$claim is missing $requirement." >&2
			print_error "$APP_CLIENT declares oidc-usermodel-attribute-mapper with claim.name=$claim, user.attribute=$claim, multivalued=true, access.token.claim=true, id.token.claim=false, userinfo.token.claim=false and jsonType.label=String." >&2
			print_error "The script fails rather than repairs a drifted mapper; an operator must fix the authorization artifact deliberately." >&2
			exit 1
		fi
	done
}

for claim in "${TENANCY_CLAIMS[@]}"; do
	if find_mapper_id "$claim"; then
		# Re-read the existing mapper by id instead of trusting its name in the list.
		kcadm_get "clients/$APP_CID/protocol-mappers/models/$MAPPER_ID" -r "$REALM" || {
			print_error "Protocol mapper $APP_CLIENT/$claim exists but could not be read back by id $MAPPER_ID." >&2
			exit 1
		}
		verify_tenancy_mapper "$claim" "$KCADM_GET_OUT"
		print_success "Protocol mapper $APP_CLIENT/$claim exists and matches the declared contract"
	else
		print_status "Creating protocol mapper $APP_CLIENT/$claim..."
		kcadm create "clients/$APP_CID/protocol-mappers/models" -r "$REALM" \
			-s name="$claim" \
			-s protocol=openid-connect \
			-s protocolMapper=oidc-usermodel-attribute-mapper \
			-s "config.\"claim.name\"=$claim" \
			-s "config.\"user.attribute\"=$claim" \
			-s "config.\"multivalued\"=true" \
			-s "config.\"access.token.claim\"=true" \
			-s "config.\"id.token.claim\"=false" \
			-s "config.\"userinfo.token.claim\"=false" \
			-s "config.\"jsonType.label\"=String" >/dev/null
		# Do not trust the create's exit code: read the new mapper back and verify
		# that it means what the tenancy contract declares.
		if ! find_mapper_id "$claim"; then
			print_error "Protocol mapper $APP_CLIENT/$claim was created but is absent from the mapper list read back." >&2
			exit 1
		fi
		kcadm_get "clients/$APP_CID/protocol-mappers/models/$MAPPER_ID" -r "$REALM" || {
			print_error "Protocol mapper $APP_CLIENT/$claim was created but could not be read back by id $MAPPER_ID." >&2
			exit 1
		}
		verify_tenancy_mapper "$claim" "$KCADM_GET_OUT"
		print_success "Protocol mapper $APP_CLIENT/$claim created and verified"
	fi
done

# ---- Audience protocol mapper (the claim both JWT decoders require) ----
# The contract and both of its halves are declared once, above the container check, so the two narrow
# modes the deploy path uses reach exactly the same verification the full run applies.
ensure_audience_mapper

# ---- Realm role: the Keycloak admin gate for /api/users-admin/** ----
# `admin` is the ONLY realm role this script provisions. The legacy realm roles
# `life-control-admin` and `life-control-country` are retired and must not come
# back to this loop (odd/tasks/retire-legacy-realm-roles.md): the API accepts no
# authority derived from them, and creating them on every run would keep a
# retired authority available to be assigned by hand. Creation is not
# assignment, which is why this loop is the surface that has to stop first —
# deleting the roles from a realm while they are still provisioned here only
# lasts until the next run. `keycloak-setup.test.sh` case 15 fails if a legacy
# name returns. This is a single role and not a one-element `for` loop:
# `shellcheck --severity=warning` rejects a literal single-element loop with
# SC2043, and the CI gate runs at that severity.
role="admin"
if realm_role_exists "$role"; then
	print_success "Realm role $role exists"
else
	kcadm create roles -r "$REALM" -s name="$role" -s description="LifeControl realm role $role" >/dev/null
	print_success "Realm role $role created"
fi

# ---- Service-account roles for the backend admin client ----
print_status "Assigning realm-management roles to $ADMIN_CLIENT service account..."
SA_USER="service-account-$ADMIN_CLIENT"
for role in manage-users view-users query-users query-groups manage-realm view-realm \
            manage-clients view-clients query-clients create-client view-events manage-events; do
	assign_out="$(kcadm add-roles -r "$REALM" --uusername "$SA_USER" --cclientid realm-management --rolename "$role" 2>&1)" && \
		print_success "  realm-management/$role -> $ADMIN_CLIENT" || {
		if printf '%s' "$assign_out" | grep -q "Role not found"; then
			print_status "  realm-management/$role not present in this Keycloak version (skipped)"
		else
			print_status "  realm-management/$role already assigned"
		fi
	}
done

echo ""
print_success "Keycloak setup complete for $ENV"
print_success "  Realm:        $REALM"
print_success "  Public:       $APP_CLIENT   -> $APP_ORIGIN"
print_success "  Confidential: $ADMIN_CLIENT (service account)"