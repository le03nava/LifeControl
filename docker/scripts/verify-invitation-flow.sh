#!/bin/bash
# ============================================
# LifeControl - invitation email flow end-to-end check
# ============================================
# Proves the invitation environment works, end to end, against a running dev
# stack: the realm's SMTP server relays an action email, the link inside it
# resolves to KEYCLOAK (not to the app), and the action token returns the person
# to the app.
#
# Manual procedure. It is deliberately NOT wired into CI: it needs Keycloak,
# Mailpit and network access, and it writes a throwaway user into the realm.
#
#   ./docker/scripts/verify-invitation-flow.sh [dev]
#
# Prerequisites (the script fails loudly, naming the missing one):
#   - Keycloak up and the realm provisioned: ./docker/scripts/keycloak-setup.sh dev
#   - Mailpit up: ./docker/scripts/deploy.sh dev up
#   - docker/secrets/keycloak_admin_client_secret materialized
#
# It obtains an admin token with a client-credentials grant, creates a throwaway
# user, calls PUT /admin/realms/<realm>/users/<id>/execute-actions-email with
# ["VERIFY_EMAIL","UPDATE_PASSWORD"], asserts through the Mailpit REST API that
# the message arrived, then makes three real assertions the old host comparison
# could not: (1) the link's base serves Keycloak (the discovery there reports the
# issuer equal to that base — at the app origin it returns the SPA HTML), (2) the
# realm issuer equals the URL the stack validates (KEYCLOAK_ISSUER_URI), and (3)
# the action token's redirect_uri claim equals the app origin. It deletes the
# throwaway user on exit and exits non-zero on any failure.
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

REALM="$(get_env KEYCLOAK_REALM life-control-realm)"
APP_CLIENT="$(get_env KEYCLOAK_CLIENT_ID life-control-client)"
ADMIN_CLIENT="$(get_env KEYCLOAK_ADMIN_CLIENT_ID life-control-admin-client)"
APP_ORIGIN="$(get_env WEB_APP_URL "http://localhost:$(get_env WEB_APP_PORT 4200)")"
KC_BASE="http://localhost:$(get_env KEYCLOAK_PORT 8181)"
# The issuer every caller validates. Read from the env so the check is coupled to
# the same value api-gateway (allowed-issuers) and life-control-api
# (keycloak.allowed-issuers) consume, not to a URL this script invents.
EXPECTED_REALM_ISSUER="$(get_env KEYCLOAK_ISSUER_URI "$KC_BASE/realms/$REALM")"
MAILPIT_BASE="http://localhost:$(get_env MAILPIT_PORT 8025)"
ADM_CLIENT_SECRET_FILE="$SECRETS_DIR/keycloak_admin_client_secret"

print_status() { echo -e "\033[0;34m[INFO]\033[0m $*"; }
print_success() { echo -e "\033[0;32m[SUCCESS]\033[0m $*"; }
print_error() { echo -e "\033[0;31m[ERROR]\033[0m $*" >&2; }

if ! command -v curl >/dev/null 2>&1; then
	print_error "curl is required for this manual procedure but was not found."
	exit 1
fi

if [ ! -s "$ADM_CLIENT_SECRET_FILE" ]; then
	print_error "Missing or empty $ADM_CLIENT_SECRET_FILE — run ./docker/scripts/setup-env.sh $ENV"
	exit 1
fi
if grep -q "CHANGEME" "$ADM_CLIENT_SECRET_FILE"; then
	print_error "$ADM_CLIENT_SECRET_FILE still holds the CHANGEME placeholder."
	exit 1
fi
ADM_CLIENT_SECRET="$(cat "$ADM_CLIENT_SECRET_FILE")"

# ============================================
# Preflight: both services answer before anything is written.
# ============================================
print_status "Checking Keycloak at $KC_BASE and Mailpit at $MAILPIT_BASE..."
if ! curl -sf "$KC_BASE/realms/$REALM/.well-known/openid-configuration" >/dev/null; then
	print_error "Keycloak realm $REALM is not reachable at $KC_BASE."
	print_error "Start it and provision the realm: ./docker/scripts/deploy.sh $ENV up && ./docker/scripts/keycloak-setup.sh $ENV"
	exit 1
fi
if ! curl -sf "$MAILPIT_BASE/api/v1/messages" >/dev/null; then
	print_error "Mailpit is not reachable at $MAILPIT_BASE."
	print_error "Start it: ./docker/scripts/deploy.sh $ENV up (mailpit is a dev-only service)."
	exit 1
fi

# ============================================
# Admin token (client-credentials, service-account admin client)
# ============================================
print_status "Requesting an admin token for $ADMIN_CLIENT..."
TOKEN_JSON="$(curl -sf -X POST "$KC_BASE/realms/$REALM/protocol/openid-connect/token" \
	-d client_id="$ADMIN_CLIENT" \
	-d client_secret="$ADM_CLIENT_SECRET" \
	-d grant_type=client_credentials)" || {
	print_error "The client-credentials grant failed for $ADMIN_CLIENT in realm $REALM."
	exit 1
}
TOKEN="$(printf '%s' "$TOKEN_JSON" | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')"
if [ -z "$TOKEN" ]; then
	print_error "No access_token in the token response (service account disabled, wrong secret?)."
	exit 1
fi
print_success "Admin token obtained (${#TOKEN} chars)"

# The throwaway user is removed on ANY exit, so a failed run never leaves one.
USER_ID=""
cleanup() {
	local rc=$?
	if [ -n "$USER_ID" ] && [ -n "${TOKEN:-}" ]; then
		curl -s -o /dev/null -X DELETE \
			"$KC_BASE/admin/realms/$REALM/users/$USER_ID" \
			-H "Authorization: Bearer $TOKEN" || true
		print_status "Removed throwaway user $USER_ID"
	fi
	exit "$rc"
}
trap cleanup EXIT

STAMP="$(date +%s)"
USERNAME="w6b-verify-$STAMP"
EMAIL="$USERNAME@lifecontrol.local"

# ============================================
# Create the throwaway user
# ============================================
print_status "Creating throwaway user $USERNAME..."
CREATE_CODE="$(curl -s -o /dev/null -w '%{http_code}' -X POST \
	"$KC_BASE/admin/realms/$REALM/users" \
	-H "Authorization: Bearer $TOKEN" \
	-H 'Content-Type: application/json' \
	-d "{\"username\":\"$USERNAME\",\"email\":\"$EMAIL\",\"enabled\":true,\"emailVerified\":false}")"
if [ "$CREATE_CODE" != "201" ]; then
	print_error "Creating the throwaway user answered HTTP $CREATE_CODE (expected 201)."
	exit 1
fi

USER_JSON="$(curl -sf "$KC_BASE/admin/realms/$REALM/users?username=$USERNAME&exact=true" \
	-H "Authorization: Bearer $TOKEN")"
USER_ID="$(printf '%s' "$USER_JSON" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')"
if [ -z "$USER_ID" ]; then
	print_error "The throwaway user was created but its id could not be read back."
	exit 1
fi
print_success "Throwaway user created ($USER_ID)"

# ============================================
# Trigger the invitation email (the action the backend will send)
# ============================================
# Percent-encode the origin for the query string: ':' and '/' alone are enough
# for an http(s) origin.
encode_origin() {
	printf '%s' "$1" | sed 's/:/%3A/g; s|/|%2F|g; s/?/%3F/g; s/&/%26/g; s/#/%23/g'
}

print_status "Requesting the action email (VERIFY_EMAIL, UPDATE_PASSWORD)..."
SEND_CODE="$(curl -s -o /dev/null -w '%{http_code}' -X PUT \
	"$KC_BASE/admin/realms/$REALM/users/$USER_ID/execute-actions-email?client_id=$APP_CLIENT&redirect_uri=$(encode_origin "$APP_ORIGIN")" \
	-H "Authorization: Bearer $TOKEN" \
	-H 'Content-Type: application/json' \
	-d '["VERIFY_EMAIL","UPDATE_PASSWORD"]')"
if [ "$SEND_CODE" != "204" ]; then
	print_error "execute-actions-email answered HTTP $SEND_CODE (expected 204)."
	print_error "If this is 500/400, the realm SMTP server is not configured: run ./docker/scripts/keycloak-setup.sh $ENV"
	exit 1
fi
print_success "Keycloak accepted the action email"

# ============================================
# Assert delivery through the Mailpit REST API
# ============================================
# find_message_id <messages-json> <email> -> the id of the first message whose
# recipient matches. Each message starts at its own "ID"; splitting there keeps
# the recipient list inside its message's chunk.
find_message_id() {
	printf '%s' "$1" \
		| sed 's/"ID":"/\n"ID":"/g' \
		| grep -F -- "$2" \
		| grep -o '"ID":"[^"]*"' | head -1 | cut -d'"' -f4
}

MID=""
for _ in $(seq 1 30); do
	MESSAGES="$(curl -sf "$MAILPIT_BASE/api/v1/messages?limit=50")"
	MID="$(find_message_id "$MESSAGES" "$EMAIL" || true)"
	if [ -n "$MID" ]; then
		break
	fi
	sleep 1
done
if [ -z "$MID" ]; then
	print_error "No message for $EMAIL arrived in Mailpit within 30s."
	print_error "The realm relayed to a different server, or the message bounced: check the Keycloak logs."
	exit 1
fi
print_success "Message arrived in Mailpit (id $MID)"

MESSAGE_JSON="$(curl -sf "$MAILPIT_BASE/api/v1/message/$MID")"

# The action-token link, read from the received body (never assumed). `&amp;` is
# unescaped because the HTML part entity-encodes it.
LINK="$(printf '%s' "$MESSAGE_JSON" \
	| grep -oE 'https?://[^"\\ ]*login-actions/action-token[^"\\ ]*' | head -1)"
if [ -z "$LINK" ]; then
	print_error "The received message carries no login-actions/action-token link."
	exit 1
fi
LINK="${LINK//&amp;/&}"

# ============================================
# Assertions: the link resolves to Keycloak, the issuer is the one the stack
# validates, and the action token returns to the app.
# ============================================
link_base() { printf '%s' "$1" | sed -E 's#^([a-zA-Z][a-zA-Z0-9+.-]*://[^/]+)/.*#\1#'; }

# json_string <key> <json> -> the string value of <key>, empty when absent. The
# discovery document and the action-token payload are flat enough for a single
# greedy match, and no host-side jq is assumed.
json_string() {
	local key="$1"
	printf '%s' "$2" | sed -n 's/.*"'"$key"'"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p'
}

# decode_jwt_payload <jwt> -> the base64url-decoded JSON payload (middle segment).
# base64url uses '-' and '_' where standard base64 uses '+' and '/', and drops
# padding; both are restored before decoding. GNU base64 reads with -d, BSD/macOS
# with -D, so both are attempted.
decode_jwt_payload() {
	local jwt="$1" payload b64
	payload="${jwt#*.}"
	payload="${payload%%.*}"
	if [ -z "$payload" ] || [ "$payload" = "$jwt" ]; then
		return 1
	fi
	b64="$(printf '%s' "$payload" | tr '_' '/' | tr '-' '+')"
	case $((${#b64} % 4)) in
		2) b64="$b64==" ;;
		3) b64="$b64=" ;;
	esac
	printf '%s' "$b64" | base64 -d 2>/dev/null || printf '%s' "$b64" | base64 -D
}

echo ""
print_success "Invitation link: $LINK"

# ---- Assertion 1: the link base actually serves Keycloak ----
# This is what replaces the old "link host matches the app origin" check. That
# one was a false green (it passed on a link pointing at localhost:4200, where
# /realms/** returns the SPA HTML) and a false red (it failed on a working link
# whose frontendUrl was unset). A base that serves Keycloak answers the discovery
# document with an issuer equal to that same base.
LINK_BASE="$(link_base "$LINK")"
if [ -z "$LINK_BASE" ]; then
	print_error "Could not derive a base URL from the invitation link."
	exit 1
fi
LINK_EXPECTED_ISSUER="$LINK_BASE/realms/$REALM"
LINK_DISCOVERY_URL="$LINK_EXPECTED_ISSUER/.well-known/openid-configuration"
print_status "Checking that the link base serves Keycloak: $LINK_DISCOVERY_URL"
LINK_DISCOVERY="$(curl -sf "$LINK_DISCOVERY_URL" 2>/dev/null)" || {
	print_error "The link base '$LINK_BASE' did not answer the Keycloak discovery document at $LINK_DISCOVERY_URL."
	print_error "The invitation link does not resolve to Keycloak; check the realm's frontendUrl."
	exit 1
}
LINK_ISSUER="$(json_string issuer "$LINK_DISCOVERY")"
if [ "$LINK_ISSUER" != "$LINK_EXPECTED_ISSUER" ]; then
	print_error "The link base '$LINK_BASE' does not serve Keycloak: its discovery reports issuer '$LINK_ISSUER', expected '$LINK_EXPECTED_ISSUER'."
	print_error "At the app origin this endpoint returns the SPA HTML, not the discovery document."
	exit 1
fi
print_success "Link base serves Keycloak (issuer $LINK_ISSUER)"

# ---- Assertion 2: the realm issuer equals the one the stack validates ----
# This is the assertion that would have caught the frontendUrl regression: while
# the realm issuer and KEYCLOAK_ISSUER_URI disagree, every authenticated call
# fails with HTTP 401 on a valid token.
print_status "Checking that the realm issuer equals $EXPECTED_REALM_ISSUER..."
REALM_DISCOVERY="$(curl -sf "$KC_BASE/realms/$REALM/.well-known/openid-configuration")" || {
	print_error "Keycloak did not answer the realm discovery at $KC_BASE."
	exit 1
}
REALM_ISSUER="$(json_string issuer "$REALM_DISCOVERY")"
if [ "$REALM_ISSUER" != "$EXPECTED_REALM_ISSUER" ]; then
	print_error "The realm discovery issuer is '$REALM_ISSUER', but the stack validates '$EXPECTED_REALM_ISSUER' (KEYCLOAK_ISSUER_URI from $ENV_FILE)."
	print_error "While they disagree, every authenticated call fails with HTTP 401 on a valid token."
	exit 1
fi
print_success "Realm issuer matches KEYCLOAK_ISSUER_URI ($REALM_ISSUER)"

# ---- Assertion 3: the action token returns to the app origin ----
# Decoded from the received token, never assumed. Keycloak 26.2.1 emits the
# redirect URI claim as `reduri` in the action token (measured 2026-10-06); the
# full name is accepted first so the check follows the semantic claim.
KEY_JWT="$(printf '%s' "$LINK" | sed -n 's/.*[?&]key=\([^&#]*\).*/\1/p')"
if [ -z "$KEY_JWT" ]; then
	print_error "The invitation link carries no key= action token to decode."
	exit 1
fi
TOKEN_PAYLOAD="$(decode_jwt_payload "$KEY_JWT")" || {
	print_error "Could not decode the action token payload from the link's key parameter."
	exit 1
}
TOKEN_REDIRECT_URI="$(json_string redirect_uri "$TOKEN_PAYLOAD")"
if [ -z "$TOKEN_REDIRECT_URI" ]; then
	TOKEN_REDIRECT_URI="$(json_string reduri "$TOKEN_PAYLOAD")"
fi
if [ "$TOKEN_REDIRECT_URI" != "$APP_ORIGIN" ]; then
	print_error "The action token's redirect_uri claim is '$TOKEN_REDIRECT_URI', not the app origin '$APP_ORIGIN'."
	print_error "The person would not return to the app after consuming the invitation."
	exit 1
fi
print_success "Action token redirect_uri returns to the app ($TOKEN_REDIRECT_URI)"
echo ""
print_success "Invitation flow verified end to end for $ENV"
