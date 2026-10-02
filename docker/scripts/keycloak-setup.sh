#!/bin/bash
# ============================================
# LifeControl - Keycloak realm & client setup
# ============================================
# Provisions the realm, clients and roles required by lifecontrol-api and
# life-control-app-angular using the Admin REST API via kcadm.sh (run inside
# the running keycloak container). Idempotent: existing entities are kept.
#
# Usage:
#   ./docker/scripts/keycloak-setup.sh [dev|staging|prod]
#
# Requires:
#   - Keycloak container up (run ./docker/scripts/deploy.sh <env> start first)
#   - docker/secrets/keycloak_admin_password and
#     docker/secrets/keycloak_admin_client_secret materialized (setup-env.sh)
#
# This script contains NO secret content.
# ============================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DOCKER_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SECRETS_DIR="$DOCKER_DIR/secrets"

ENV="${1:-dev}"
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
CONTAINER="${COMPOSE_PROJECT_NAME}-keycloak"
KC_ADMIN_USER="$(get_env KC_ADMIN_USERNAME admin)"
REALM="$(get_env KEYCLOAK_REALM life-control-realm)"
APP_CLIENT="$(get_env KEYCLOAK_CLIENT_ID life-control-client)"
ADMIN_CLIENT="$(get_env KEYCLOAK_ADMIN_CLIENT_ID life-control-admin-client)"
KC_BASE="http://localhost:$(get_env KC_HTTP_PORT 8080)"
APP_ORIGIN="$(get_env WEB_APP_URL "http://localhost:$(get_env WEB_APP_PORT 4200)")"

print_status() { echo -e "\033[0;34m[INFO]\033[0m $*"; }
print_success() { echo -e "\033[0;32m[SUCCESS]\033[0m $*"; }
print_error() { echo -e "\033[0;31m[ERROR]\033[0m $*"; }

kcadm() {
	docker exec "$CONTAINER" /opt/keycloak/bin/kcadm.sh "$@"
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

if ! docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
	print_error "Container $CONTAINER is not running."
	print_error "Start it first: ./docker/scripts/deploy.sh $ENV up"
	exit 1
fi

# ---- Credentials from secrets (no plaintext in env files) ----
KC_ADMIN_PASS_FILE="$SECRETS_DIR/keycloak_admin_password"
ADM_CLIENT_SECRET_FILE="$SECRETS_DIR/keycloak_admin_client_secret"

for f in "$KC_ADMIN_PASS_FILE" "$ADM_CLIENT_SECRET_FILE"; do
	if [ ! -s "$f" ]; then
		print_error "Missing or empty secret file $f — run ./docker/scripts/setup-env.sh $ENV"
		exit 1
	fi
	if grep -q "CHANGEME" "$f"; then
		print_error "Secret $f still holds the CHANGEME placeholder — set a real value first"
		exit 1
	fi
done

KC_ADMIN_PASS="$(cat "$KC_ADMIN_PASS_FILE")"
ADM_CLIENT_SECRET="$(cat "$ADM_CLIENT_SECRET_FILE")"

print_status "Authenticating kcadm as $KC_ADMIN_USER against $CONTAINER..."
kcadm config credentials --server "$KC_BASE" --realm master --user "$KC_ADMIN_USER" --password "$KC_ADMIN_PASS" >/dev/null

# ---- Realm ----
if kcadm_get "realms/$REALM"; then
	print_success "Realm $REALM already exists"
else
	print_status "Creating realm $REALM..."
	kcadm create realms -s realm="$REALM" -s enabled=true >/dev/null
	print_success "Realm $REALM created"
fi

# ---- Public client (frontend) ----
if client_exists "$APP_CLIENT"; then
	resolve_client_id "$APP_CLIENT" || exit 1
	APP_CID="$CLIENT_ID"
	print_success "Client $APP_CLIENT already exists ($APP_CID)"
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
            lc-country lc-status lc-status-type lc-payment-method lc-measure-unit \
            lc-product-supplier lc-sales lc-scheduling lc-scheduling-read \
            lc-department lc-position lc-seniority-level; do
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

# ---- Realm roles (legacy features) ----
for role in life-control-admin life-control-country admin; do
	if realm_role_exists "$role"; then
		print_success "Realm role $role exists"
	else
		kcadm create roles -r "$REALM" -s name="$role" -s description="LifeControl realm role $role" >/dev/null
		print_success "Realm role $role created"
	fi
done

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