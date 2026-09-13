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

client_exists() {
	local client_id="$1"
	local count
	count="$(kcadm get clients -r "$REALM" -q clientId="$client_id" --fields id --format csv --noquotes | tr -d '\r' | grep -c . || true)"
	[ "$count" -gt 0 ]
}

get_client_id() {
	kcadm get clients -r "$REALM" -q clientId="$1" --fields id --format csv --noquotes | tr -d '\r' | tr -d '"' | head -1
}

client_role_exists() {
	local cid="$1"
	local role="$2"
	kcadm get "clients/$cid/roles/$role" -r "$REALM" >/dev/null 2>&1
}

realm_role_exists() {
	local role="$1"
	kcadm get "roles/$role" -r "$REALM" >/dev/null 2>&1
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
if kcadm get "realms/$REALM" >/dev/null 2>&1; then
	print_success "Realm $REALM already exists"
else
	print_status "Creating realm $REALM..."
	kcadm create realms -s realm="$REALM" -s enabled=true >/dev/null
	print_success "Realm $REALM created"
fi

# ---- Public client (frontend) ----
if client_exists "$APP_CLIENT"; then
	APP_CID="$(get_client_id "$APP_CLIENT")"
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
	APP_CID="$(get_client_id "$APP_CLIENT")"
	print_success "Client $APP_CLIENT created ($APP_CID)"
fi

# ---- Confidencial client (backend admin / service account) ----
if client_exists "$ADMIN_CLIENT"; then
	ADM_CID="$(get_client_id "$ADMIN_CLIENT")"
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
	ADM_CID="$(get_client_id "$ADMIN_CLIENT")"
	print_success "Client $ADMIN_CLIENT created ($ADM_CID)"
fi

# ---- Client roles (hierarchical flat set, frontend RBAC) ----
for role in lc-admin lc-company lc-company-read lc-company-country lc-company-country-read \
            lc-company-region lc-company-region-read lc-company-zone lc-company-zone-read \
            lc-company-store lc-company-store-read \
            lc-country lc-status lc-status-type lc-payment-method lc-measure-unit \
            lc-product-supplier lc-sales; do
	if client_role_exists "$APP_CID" "$role"; then
		print_success "Client role $APP_CLIENT/$role exists"
	else
		kcadm create "clients/$APP_CID/roles" -r "$REALM" -s name="$role" -s description="LifeControl $role" >/dev/null
		print_success "Client role $APP_CLIENT/$role created"
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