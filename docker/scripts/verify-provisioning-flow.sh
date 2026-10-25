#!/bin/bash
# ============================================
# LifeControl - access-provisioning flow end-to-end check
# ============================================
# Answers, in one run, the question the incident of 2026-10-19 needed four SQL
# queries and a Mailpit lookup to answer: "I created an employee and assigned a
# contract — was the activation email sent?" It is the sibling of
# verify-invitation-flow.sh: that one proves the invitation *environment*, this
# one proves the provisioning *flow* that sends the invitation.
#
# Manual procedure. It is deliberately NOT wired into CI: it needs Keycloak,
# Mailpit, the API, the gateway and the provisioning worker running, and it
# writes a throwaway employee, contract, store-level scope assignment and Keycloak account
# into the realm.
#
#   ./docker/scripts/verify-provisioning-flow.sh [dev]
#
# Prerequisites (the script fails loudly, naming the missing one):
#   - the stack up: ./docker/scripts/deploy.sh dev up
#   - the realm provisioned: ./docker/scripts/keycloak-setup.sh dev
#   - docker/secrets/keycloak_admin_client_secret materialized
#   - the provisioning worker ON. In dev that is the default
#     (docker-compose.override.yml: WORKER_ENABLED=${WORKER_ENABLED:-true}); the
#     script reads the *effective* switch from the API's own `workerEnabled`
#     (GET .../access) and refuses to wait on a worker that is off, naming the
#     key and the report that shows it — waiting 90s for a task nobody will
#     claim is exactly the silent state this feature exists to remove.
#   - Mailpit, which is a dev-only service, so any other environment is refused
#     by name instead of reporting a flow whose mail nobody can observe.
#
# WHAT IT EXERCISES, in order, through the real producers and never a shortcut:
#
#   1. POST /api/companies/{id}/employees and POST .../contracts with an
#      operator JWT carrying `lc-admin` and the `company_id` claim — the flow's
#      real entry point: those writes are gated on the role, and
#      CurrentUserContext resolves tenancy from the claims.
#   2. the ACTIVATE task AccessProvisioningProducer enqueues inside the contract
#      transaction (produced by the real producer, never inserted by hand).
#   3. the worker claiming and applying it: PENDING -> RUNNING -> APPLIED.
#   4. the invitation in Mailpit's REST API, with its action-token link, and the
#      assertion that the link's base serves Keycloak (its discovery reports the
#      issuer of that base; at the app origin that path answers the SPA HTML).
#   5. the Keycloak account: the employee's linked id, its `requiredActions`,
#      the roles the position template declares (read from the overview's own
#      derivation, then compared with the account in both directions) and the
#      five `company_*` claim attributes — present exactly for the levels the
#      derivation holds, absent for the levels it does not.
#
# THE OPERATOR IDENTITY, measured rather than assumed (2026-10-19). No dev user's
# credential is obtainable from a script: the realm holds exactly two users
# carrying the five `company_*` attributes — `smoke-admin` (also `lc-admin`) and
# `e2e-probe-dept` (no `lc-admin`) — and both passwords were set operationally
# and deliberately not recorded in this repository; the admin client's service
# account carries realm-management roles, no `lc-admin` and no `impersonation`
# role, so neither impersonation nor a claim-bearing client-credentials token is
# available either. The script therefore offers the two honest options instead of
# inventing a credential:
#
#   (a) default — it provisions its OWN DISPOSABLE OPERATOR: a password generated
#       here with $RANDOM, never printed and never written to disk, `lc-admin`
#       granted on the app client, the `company_id` attribute set to the chosen
#       company and read back, and the user DELETED on exit. Nothing is hardcoded
#       and nothing survives the run; the admin client secret this script already
#       reads can create such a user anyway, so this adds no authority it did not
#       have.
#   (b) export PROVISIONING_VERIFY_TOKEN=<jwt> — an operator token supplied by a
#       human (an `lc-admin` token from a real login). This mode writes NO
#       identity into the realm: no user, no role, no attribute, no password. It
#       still reads the account back through the admin client, which is what lets
#       the roles and the five claims be asserted at all.
#
# WHAT IT DOES NOT CLAIM. It exercises the API the screens call, not the screens.
# It asserts the role set the position template of the chosen position declares,
# so on a position that declares no roles the run proves the account carries
# none, and the output says which case it measured. The four deeper claim levels
# (country/region/zone/store) are exercised only when the company has a store
# chain this script can resolve; when it has none, the run proves the
# company-level claim and the output says the deeper levels were not exercised.
#
# CLEANUP. The throwaway data is closed on ANY exit, in this order: the store
# assignment is closed, the contract is closed, the employee is soft-deleted
# through its own DELETE route (the repository's contract for an employee: the
# row is kept and disabled), the Keycloak account is deleted, and — only when the
# script created it — the disposable operator is deleted. What remains is
# history, not access: a disabled employee row, a closed contract, a closed
# assignment and the task history. The identifiers are printed as they are
# created, so a human can finish by hand if a SIGKILL skipped the trap.
#
# This script contains NO secret content.
# ============================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=docker/scripts/_common.sh
source "$SCRIPT_DIR/_common.sh"

resolve_compose_env "${1:-dev}"

SECRETS_DIR="$DOCKER_DIR/secrets"

# The env file is a prerequisite, not an input this script may invent: the ports,
# the realm and the worker's interval all come from it.
if [ ! -f "$ENV_FILE" ]; then
	print_error "$ENV_FILE not found — run ./docker/scripts/setup-env.sh $ENV first"
	exit 1
fi

# Read a key from the env file with a default, on top of _common.sh's reader
# (get_env_var never fails under set -e, so a missing key falls back here).
get_env() {
	local key="$1"
	local default="${2:-}"
	local value
	value="$(get_env_var "$key")"
	printf '%s' "${value:-$default}"
}

# Mailpit is a dev-only service (docker-compose.override.yml), so the flow's
# observation point only exists there. Refusing by name beats reporting a flow
# whose mail nobody can read.
if [ "$ENV" != "dev" ]; then
	print_error "This procedure observes the invitation in Mailpit, which only exists in dev."
	print_error "It cannot verify the provisioning flow for '$ENV'."
	exit 1
fi

REALM="$(get_env KEYCLOAK_REALM life-control-realm)"
APP_CLIENT="$(get_env KEYCLOAK_CLIENT_ID life-control-client)"
ADMIN_CLIENT="$(get_env KEYCLOAK_ADMIN_CLIENT_ID life-control-admin-client)"
KC_BASE="$(get_env KEYCLOAK_URL "http://localhost:$(get_env KEYCLOAK_PORT 8181)")"
API_BASE="${API_BASE_URL:-$(get_env API_GATEWAY_URL "http://localhost:$(get_env API_GATEWAY_PORT 9000)")}"
MAILPIT_BASE="http://localhost:$(get_env MAILPIT_PORT 8025)"
WORKER_INTERVAL="$(get_env WORKER_INTERVAL_SECONDS 60)"
ADM_CLIENT_SECRET_FILE="$SECRETS_DIR/keycloak_admin_client_secret"
OPERATOR_TOKEN="${PROVISIONING_VERIFY_TOKEN:-}"

# The interval is only used for arithmetic; anything that is not a number falls
# back to the same default the API uses, instead of failing inside $(( )).
case "$WORKER_INTERVAL" in
*[!0-9]* | '' | 0) WORKER_INTERVAL=60 ;;
esac

if ! command -v curl >/dev/null 2>&1; then
	print_error "curl is required for this manual procedure but was not found."
	exit 1
fi

# The admin client is read-only in the supplied-token mode, and it is what lets the
# script read the account back, so its secret is required either way.
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
# JSON helpers. No jq is assumed, and every pipeline ends in a command that
# succeeds: with `set -o pipefail`, a grep that matches nothing would otherwise
# abort the run.
# ============================================
flatten() { tr -d '\n\r'; }

# json_string <key> <json> -> first string value of <key>; empty when absent.
json_string() {
	printf '%s' "$2" | flatten \
		| grep -o "\"$1\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" | head -1 | cut -d'"' -f4 || true
}

# json_bool <key> <json> -> "true"/"false"; empty when absent.
json_bool() {
	printf '%s' "$2" | flatten \
		| grep -o "\"$1\"[[:space:]]*:[[:space:]]*\(true\|false\)" | head -1 \
		| grep -o '\(true\|false\)$' || true
}

# json_array <key> <json> -> one value per line for a flat array of strings.
json_array() {
	printf '%s' "$2" | flatten \
		| grep -o "\"$1\"[[:space:]]*:[[:space:]]*\[[^]]*\]" | head -1 \
		| grep -o '"[^"]*"' | tail -n +2 | tr -d '"' || true
}

# json_strings <key> <json> -> every string value of <key>, one per line, sorted.
json_strings() {
	printf '%s' "$2" | flatten \
		| grep -o "\"$1\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" | cut -d'"' -f4 | sort || true
}

# json_first_id <json> -> the first "id" value in document order.
json_first_id() {
	printf '%s' "$1" | flatten \
		| grep -o '"id"[[:space:]]*:[[:space:]]*"[^"]*"' | head -1 | cut -d'"' -f4 || true
}

# json_task_status <overview> -> the status of the employee's newest task.
# `openTask` is serialized before `history` (record component order), so the first
# status in the document is the open task's when one is open and the newest
# history entry's when none is.
json_task_status() {
	printf '%s' "$1" | flatten \
		| grep -o '"status"[[:space:]]*:[[:space:]]*"[A-Z_]*"' | head -1 | cut -d'"' -f4 || true
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

# expect_code <expected> <actual> <what>
expect_code() {
	if [ "$2" != "$1" ]; then
		print_error "$3 answered HTTP $2 (expected $1)."
		return 1
	fi
}

# ============================================
# Preflight: every service we will talk to answers before anything is written.
# ============================================
print_status "Checking Keycloak at $KC_BASE, the API at $API_BASE and Mailpit at $MAILPIT_BASE..."
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
# An unauthenticated call answers 401 at the gateway, which is what proves the
# route is alive; 000 is the only answer that means "nothing is listening".
API_CODE="$(curl -s -o /dev/null -w '%{http_code}' "$API_BASE/api/companies" || true)"
if [ "$API_CODE" = "000" ] || [ -z "$API_CODE" ]; then
	print_error "The API gateway is not reachable at $API_BASE."
	print_error "Start it: ./docker/scripts/deploy.sh $ENV up"
	exit 1
fi

# ============================================
# Admin token (client-credentials, service-account admin client). It is what reads
# the account, its roles and its attributes back; in the supplied-token mode it
# performs no write at all.
# ============================================
print_status "Requesting an admin token for $ADMIN_CLIENT..."
TOKEN_JSON="$(curl -sf -X POST "$KC_BASE/realms/$REALM/protocol/openid-connect/token" \
	-d client_id="$ADMIN_CLIENT" \
	-d client_secret="$ADM_CLIENT_SECRET" \
	-d grant_type=client_credentials)" || {
	print_error "The client-credentials grant failed for $ADMIN_CLIENT in realm $REALM."
	exit 1
}
ADMIN_TOKEN="$(printf '%s' "$TOKEN_JSON" | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')"
if [ -z "$ADMIN_TOKEN" ]; then
	print_error "No access_token in the token response (service account disabled, wrong secret?)."
	exit 1
fi
print_success "Admin token obtained (${#ADMIN_TOKEN} chars)"

# kc_admin <method> <path> [body] -> the Admin REST API, body on stdout.
# It is defined before its first use on purpose: bash resolves a command at call
# time, so an APP_CID read placed above this definition fails with
# `kc_admin: command not found`, and the empty result then surfaces two lines
# later as `$1: unbound variable` inside json_first_id.
kc_admin() {
	local method="$1"
	local path="$2"
	local body="${3:-}"
	if [ -n "$body" ]; then
		curl -s -X "$method" -H "Authorization: Bearer $ADMIN_TOKEN" \
			-H 'Content-Type: application/json' -d "$body" "$KC_BASE/admin/realms/$REALM$path"
	else
		curl -s -X "$method" -H "Authorization: Bearer $ADMIN_TOKEN" "$KC_BASE/admin/realms/$REALM$path"
	fi
}

# kc_admin_code <method> <path> [body] -> only the HTTP status.
kc_admin_code() {
	local method="$1"
	local path="$2"
	local body="${3:-}"
	if [ -n "$body" ]; then
		curl -s -o /dev/null -w '%{http_code}' -X "$method" -H "Authorization: Bearer $ADMIN_TOKEN" \
			-H 'Content-Type: application/json' -d "$body" "$KC_BASE/admin/realms/$REALM$path"
	else
		curl -s -o /dev/null -w '%{http_code}' -X "$method" -H "Authorization: Bearer $ADMIN_TOKEN" \
			"$KC_BASE/admin/realms/$REALM$path"
	fi
}

# The app client's internal id is needed by the role assertion in both modes. It is
# read here, after kc_admin exists, for the reason that function's own comment gives.
APP_CID="$(json_first_id "$(kc_admin GET "/clients?clientId=$APP_CLIENT")")"
if [ -z "$APP_CID" ]; then
	print_error "Client $APP_CLIENT is absent from realm $REALM; run ./docker/scripts/keycloak-setup.sh $ENV"
	exit 1
fi

# obtain_operator_token <username> <password> -> an access token from the public
# client's password grant (directAccessGrantsEnabled, which keycloak-setup.sh sets).
# Its own failures go to stderr: a message captured into the variable would be read
# as the token itself.
obtain_operator_token() {
	local username="$1"
	local password="$2"
	local json token
	json="$(curl -sf -X POST "$KC_BASE/realms/$REALM/protocol/openid-connect/token" \
		-d client_id="$APP_CLIENT" -d grant_type=password \
		-d username="$username" -d password="$password")" || {
		print_error "The password grant failed for $username against client $APP_CLIENT." >&2
		print_error "If this is 400/401, the public client does not allow direct access grants: run ./docker/scripts/keycloak-setup.sh $ENV" >&2
		return 1
	}
	token="$(printf '%s' "$json" | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')"
	if [ -z "$token" ]; then
		print_error "No access_token in the token response for $username." >&2
		return 1
	fi
	printf '%s' "$token"
}

# ============================================
# The throwaway data, and the cleanup that closes it on ANY exit.
# ============================================
STAMP="$(date +%s)"
OPERATOR_ID=""
OPERATOR_USERNAME=""
OPERATOR_PASSWORD=""
EMPLOYEE_ID=""
EMPLOYEE_EMAIL=""
EMPLOYEE_KC_ID=""
CONTRACT_ID=""
ASSIGNMENT_ID=""
COMPANY_ID=""

cleanup() {
	local rc=$?
	if [ -n "$COMPANY_ID" ] && [ -n "$EMPLOYEE_ID" ] && [ -n "${OPERATOR_TOKEN:-}" ]; then
		if [ -n "$ASSIGNMENT_ID" ]; then
			curl -s -o /dev/null -X PATCH -H "Authorization: Bearer $OPERATOR_TOKEN" \
				"$API_BASE/api/companies/$COMPANY_ID/employees/$EMPLOYEE_ID/scope-assignments/$ASSIGNMENT_ID/close" ||
				true
			print_status "Closed the throwaway store-level scope assignment $ASSIGNMENT_ID"
		fi
		if [ -n "$CONTRACT_ID" ]; then
			curl -s -o /dev/null -X PATCH -H "Authorization: Bearer $OPERATOR_TOKEN" \
				"$API_BASE/api/companies/$COMPANY_ID/employees/$EMPLOYEE_ID/contracts/$CONTRACT_ID/close" ||
				true
			print_status "Closed the throwaway contract $CONTRACT_ID"
		fi
		# The employee DELETE route is the repository's own soft delete: the row is
		# kept and disabled. That is the contract for an employee, so the remaining
		# row is declared residue rather than a cleanup failure.
		curl -s -o /dev/null -X DELETE -H "Authorization: Bearer $OPERATOR_TOKEN" \
			"$API_BASE/api/companies/$COMPANY_ID/employees/$EMPLOYEE_ID" || true
		print_status "Soft-deleted the throwaway employee $EMPLOYEE_ID (the row is kept, disabled)"
	fi
	if [ -n "$EMPLOYEE_KC_ID" ] && [ -n "$ADMIN_TOKEN" ]; then
		curl -s -o /dev/null -X DELETE -H "Authorization: Bearer $ADMIN_TOKEN" \
			"$KC_BASE/admin/realms/$REALM/users/$EMPLOYEE_KC_ID" || true
		print_status "Removed the throwaway Keycloak account $EMPLOYEE_KC_ID"
	fi
	if [ -n "$OPERATOR_ID" ] && [ -n "$ADMIN_TOKEN" ]; then
		curl -s -o /dev/null -X DELETE -H "Authorization: Bearer $ADMIN_TOKEN" \
			"$KC_BASE/admin/realms/$REALM/users/$OPERATOR_ID" || true
		print_status "Removed the disposable operator $OPERATOR_USERNAME ($OPERATOR_ID)"
	fi
	exit "$rc"
}
trap cleanup EXIT

# ============================================
# The operator identity: supplied by a human, or provisioned as its own
# disposable object. The provisioned one is created without attributes, because
# the company the flow will use can only be read with a token; the attribute is
# written and read back once the company is known.
# ============================================
if [ -z "$OPERATOR_TOKEN" ]; then
	OPERATOR_USERNAME="w6b-provision-ops-$STAMP"
	OPERATOR_EMAIL="$OPERATOR_USERNAME@lifecontrol.local"
	# Generated here, never printed, never written to disk: the credential of an
	# identity that exists for the length of this run only.
	OPERATOR_PASSWORD="W6b-Prv-$STAMP-$RANDOM-Aa1!"

	# A leftover operator from a killed run is named rather than reused or
	# deleted: deleting by name prefix would be this script destroying identities
	# it cannot prove it created.
	LEFTOVERS="$(json_strings username "$(kc_admin GET '/users?search=w6b-provision-ops-&max=20')")"
	if [ -n "$LEFTOVERS" ]; then
		print_warning "The realm already holds w6b-provision-ops-* users (a previous run may have been killed):"
		while IFS= read -r leftover; do
			print_warning "  $leftover"
		done <<<"$LEFTOVERS"
		print_warning "This run leaves them alone; delete them by hand if they are stale."
	fi

	print_status "Creating the disposable operator $OPERATOR_USERNAME..."
	# firstName and lastName are not decoration: the realm's user profile declares email,
	# firstName and lastName as required for the `user` role
	# (`GET /admin/realms/<realm>/users/profile`), and a user missing one of them
	# authenticates to `invalid_grant` / "Account is not fully set up" even with
	# emailVerified=true and an empty requiredActions. Without them the password grant
	# below cannot succeed in this realm.
	OPERATOR_BODY="{\"username\":\"$OPERATOR_USERNAME\",\"email\":\"$OPERATOR_EMAIL\",\"firstName\":\"Provisioning\",\"lastName\":\"Verifier\",\"enabled\":true,\"emailVerified\":true}"
	OP_CODE="$(kc_admin_code POST /users "$OPERATOR_BODY")"
	expect_code 201 "$OP_CODE" "Creating the disposable operator" || exit 1
	OPERATOR_ID="$(json_first_id "$(kc_admin GET "/users?username=$OPERATOR_USERNAME&exact=true")")"
	if [ -z "$OPERATOR_ID" ]; then
		print_error "The disposable operator was created but its id could not be read back."
		exit 1
	fi
	print_success "Disposable operator created ($OPERATOR_USERNAME, $OPERATOR_ID)"

	print_status "Granting lc-admin to the disposable operator..."
	ADMIN_ROLE_ID="$(json_first_id "$(kc_admin GET "/clients/$APP_CID/roles/lc-admin")")"
	if [ -z "$ADMIN_ROLE_ID" ]; then
		print_error "Client role lc-admin is absent from client $APP_CLIENT; run ./docker/scripts/keycloak-setup.sh $ENV"
		exit 1
	fi
	GRANT_CODE="$(kc_admin_code POST "/users/$OPERATOR_ID/role-mappings/clients/$APP_CID" \
		"[{\"id\":\"$ADMIN_ROLE_ID\",\"name\":\"lc-admin\"}]")"
	expect_code 204 "$GRANT_CODE" "Granting lc-admin to the disposable operator" || exit 1

	print_status "Setting the disposable operator's password..."
	PW_CODE="$(kc_admin_code PUT "/users/$OPERATOR_ID/reset-password" \
		"{\"type\":\"password\",\"value\":\"$OPERATOR_PASSWORD\",\"temporary\":false}")"
	expect_code 204 "$PW_CODE" "Setting the disposable operator's password" || exit 1

	OPERATOR_TOKEN="$(obtain_operator_token "$OPERATOR_USERNAME" "$OPERATOR_PASSWORD")"
	print_success "Operator token obtained (${#OPERATOR_TOKEN} chars)"
else
	print_status "Using the operator token supplied in PROVISIONING_VERIFY_TOKEN (no identity is written to the realm)"
fi

# ============================================
# API helpers (operator token)
# ============================================
# api_write <method> <path> [body] -> sets W_CODE and W_BODY. The status and the
# body come back from one request, so a write's answer is never re-read from a
# list (which could only prove that *some* row exists).
api_write() {
	local method="$1"
	local path="$2"
	local body="${3:-}"
	local raw
	if [ -n "$body" ]; then
		raw="$(curl -s -X "$method" -H "Authorization: Bearer $OPERATOR_TOKEN" \
			-H 'Content-Type: application/json' -d "$body" -w '\n%{http_code}' "$API_BASE$path")"
	else
		raw="$(curl -s -X "$method" -H "Authorization: Bearer $OPERATOR_TOKEN" \
			-w '\n%{http_code}' "$API_BASE$path")"
	fi
	W_CODE="${raw##*$'\n'}"
	W_BODY="${raw%$'\n'*}"
}

# ============================================
# Resolve the company and the facts the writes need.
# ============================================
print_status "Resolving a company with a configured email domain..."
api_write GET /api/companies
if [ "$W_CODE" != "200" ]; then
	print_error "GET /api/companies answered HTTP $W_CODE."
	print_error "An 401/403 here means the operator token lacks lc-admin, or its issuer is not the one this stack"
	print_error "accepts (KEYCLOAK_ISSUER_URI). A supplied PROVISIONING_VERIFY_TOKEN must come from this realm."
	exit 1
fi
COMPANY_IDS="$(printf '%s' "$W_BODY" | flatten | grep -o '"id"[[:space:]]*:[[:space:]]*"[^"]*"' | cut -d'"' -f4 || true)"
if [ -z "$COMPANY_IDS" ]; then
	print_error "The API answered no companies. Create one in the UI first: this procedure creates its employee"
	print_error "inside an existing company and never creates the company itself."
	exit 1
fi
EMAIL_DOMAIN=""
while IFS= read -r candidate; do
	[ -n "$candidate" ] || continue
	api_write GET "/api/companies/$candidate"
	[ "$W_CODE" = "200" ] || continue
	DOMAIN="$(json_string emailDomain "$W_BODY")"
	ENABLED="$(json_bool enabled "$W_BODY")"
	if [ -n "$DOMAIN" ] && [ "$ENABLED" != "false" ]; then
		COMPANY_ID="$candidate"
		EMAIL_DOMAIN="$DOMAIN"
		break
	fi
done <<<"$COMPANY_IDS"
if [ -z "$COMPANY_ID" ]; then
	print_error "No enabled company declares an email_domain, and the employee write fails closed without one."
	print_error "Set the company's domain in the UI (or via PUT /api/companies/{id}) and run this again."
	exit 1
fi
print_success "Company $COMPANY_ID (email domain $EMAIL_DOMAIN)"

print_status "Resolving a position and a seniority level..."
api_write GET "/api/companies/$COMPANY_ID/positions"
POSITION_ID="$(json_first_id "$W_BODY")"
if [ -z "$POSITION_ID" ]; then
	print_error "Company $COMPANY_ID has no enabled position, and the contract needs one."
	print_error "Create a position in the UI (HR > Positions) and run this again."
	exit 1
fi
api_write GET /api/seniority-levels
SENIORITY_ID="$(json_first_id "$W_BODY")"
if [ -z "$SENIORITY_ID" ]; then
	print_error "No seniority level exists, and the contract needs one."
	exit 1
fi
print_success "Position $POSITION_ID, seniority level $SENIORITY_ID"

# ============================================
# The operator's own claims, reported so a 401/403 below is explained instead of
# mysterious. The provisioned operator gets its company_id attribute here, once
# the company is known, and the token is re-obtained so it carries it.
# ============================================
if [ -n "$OPERATOR_ID" ]; then
	print_status "Writing the disposable operator's company_id attribute and reading it back..."
	ATTR_CODE="$(kc_admin_code PUT "/users/$OPERATOR_ID" \
		"{\"username\":\"$OPERATOR_USERNAME\",\"email\":\"$OPERATOR_EMAIL\",\"firstName\":\"Provisioning\",\"lastName\":\"Verifier\",\"enabled\":true,\"emailVerified\":true,\"attributes\":{\"company_id\":[\"$COMPANY_ID\"]}}")"
	expect_code 204 "$ATTR_CODE" "Writing the disposable operator's attributes" || exit 1
	# READ BACK, never trust the write: this is the trap AGENTS.md documents —
	# with the default user-profile policy Keycloak answers 204 and stores nothing,
	# so an attribute that did not stick is the difference between a scoped token
	# and a 403 nobody can explain.
	READ_BACK="$(json_array company_id "$(kc_admin GET "/users/$OPERATOR_ID")")"
	if [ "$READ_BACK" != "$COMPANY_ID" ]; then
		print_error "The operator's company_id attribute did not persist (read back '${READ_BACK:-<empty>}')."
		print_error "Keycloak discards unmanaged user attributes silently unless the realm's user profile allows them:"
		print_error "run ./docker/scripts/keycloak-setup.sh $ENV, which sets unmanagedAttributePolicy=ADMIN_EDIT and verifies it."
		exit 1
	fi
	OPERATOR_TOKEN="$(obtain_operator_token "$OPERATOR_USERNAME" "$OPERATOR_PASSWORD")"
fi

OPERATOR_PAYLOAD="$(decode_jwt_payload "$OPERATOR_TOKEN" || true)"
if [ -z "$OPERATOR_PAYLOAD" ]; then
	print_error "The operator token's payload could not be decoded; it is not a JWT this script can read."
	exit 1
fi
OPERATOR_CLAIMS="$(json_array company_id "$OPERATOR_PAYLOAD")"
if [ -z "$OPERATOR_CLAIMS" ]; then
	print_warning "The operator token carries no company_id claim. An lc-admin caller is not scope-limited, so the"
	print_warning "writes below still work — but the flow this script verifies is the one whose entry point carries"
	print_warning "it (AGENTS.md, 'Claims de tenancy'), so a supplied token should carry it too."
elif [ -n "$COMPANY_ID" ] && [ "$OPERATOR_CLAIMS" != "$COMPANY_ID" ]; then
	print_warning "The operator token's company_id is '$OPERATOR_CLAIMS', not the company this run uses ($COMPANY_ID)."
else
	print_success "Operator token carries company_id=$OPERATOR_CLAIMS"
fi

# ============================================
# The throwaway employee, written through the API the screens call.
# ============================================
TODAY="$(date +%F)"
EMPLOYEE_NUMBER="W6B-$STAMP"
EMPLOYEE_EMAIL="w6b-provision-$STAMP@$EMAIL_DOMAIN"
EMPLOYEE_PATH="/api/companies/$COMPANY_ID/employees"

print_status "Creating throwaway employee $EMPLOYEE_NUMBER <$EMPLOYEE_EMAIL>..."
EMPLOYEE_BODY="{\"employeeNumber\":\"$EMPLOYEE_NUMBER\",\"firstName\":\"W6b\",\"paternalLastName\":\"Provision\",\"email\":\"$EMPLOYEE_EMAIL\",\"birthDate\":\"1990-01-01\",\"hireDate\":\"$TODAY\"}"
api_write POST "$EMPLOYEE_PATH" "$EMPLOYEE_BODY"
expect_code 201 "$W_CODE" "Creating the throwaway employee" || {
	print_error "A 409 here means the employeeNumber or the email is already taken; a 400 means the email's domain"
	print_error "does not match the company's declared domain ($EMAIL_DOMAIN), or a field is invalid."
	exit 1
}
EMPLOYEE_ID="$(json_first_id "$W_BODY")"
if [ -z "$EMPLOYEE_ID" ]; then
	print_error "The employee was created (HTTP 201) but no id came back in the response."
	exit 1
fi
print_success "Employee created ($EMPLOYEE_ID)"

ACCESS_PATH="/api/companies/$COMPANY_ID/employees/$EMPLOYEE_ID/access"

# The worker's effective switch, read from the API's own field (record S3-D). A
# task nobody will claim is exactly the incident's silent state, so the script
# refuses to wait for it and names the key instead.
api_write GET "$ACCESS_PATH"
expect_code 200 "$W_CODE" "Reading the employee's access overview" || exit 1
WORKER_ENABLED="$(json_bool workerEnabled "$W_BODY")"
if [ -z "$WORKER_ENABLED" ]; then
	# An absent field is not read as "off" (record S6-C4's fail-safe): a backend
	# older than the additive field answers no workerEnabled, and claiming it is
	# off would be the script inventing a fact. The wait below is bounded anyway.
	print_warning "The API does not report workerEnabled (a build older than the 2026-10-19 additive field),"
	print_warning "so this run cannot tell from the API whether the worker is on. It waits anyway, bounded."
fi
if [ "$WORKER_ENABLED" = "false" ]; then
	print_error "The access-provisioning worker is OFF in this stack (workerEnabled=false), so the task this run"
	print_error "would enqueue would never be claimed — exactly the state the 2026-10-19 incident was in."
	print_error "WORKER_ENABLED is not declared as true for this environment. In dev the default lives in"
	print_error "docker/docker-compose.override.yml (WORKER_ENABLED=\${WORKER_ENABLED:-true}) and an explicit"
	print_error "WORKER_ENABLED=false in $ENV_FILE wins over it. ./docker/scripts/validate-env.sh reports the keys"
	print_error "the env file is missing and the compose default each one falls back to."
	exit 1
fi
print_success "The worker is enabled in this stack (workerEnabled=true)"

# A store-level scope assignment, when the company has a chain to resolve: it is what makes
# the run exercise the four deeper claim levels instead of the company one alone. The store is one
# level of the scope surface (`/scope-assignments`), not a route of its own, so the payload names the
# store node exactly as the level dialog does.
print_status "Resolving a store chain for the throwaway employee..."
STORE_ID=""
api_write GET "/api/companies/$COMPANY_ID/countries"
COUNTRY_ID="$(json_first_id "$W_BODY")"
if [ -n "$COUNTRY_ID" ]; then
	api_write GET "/api/companies/$COMPANY_ID/countries/$COUNTRY_ID/regions"
	REGION_ID="$(json_first_id "$W_BODY")"
	if [ -n "$REGION_ID" ]; then
		api_write GET "/api/companies/$COMPANY_ID/countries/$COUNTRY_ID/regions/$REGION_ID/zones"
		ZONE_ID="$(json_first_id "$W_BODY")"
		if [ -n "$ZONE_ID" ]; then
			api_write GET "/api/companies/$COMPANY_ID/countries/$COUNTRY_ID/regions/$REGION_ID/zones/$ZONE_ID/stores"
			STORE_ID="$(json_first_id "$W_BODY")"
		fi
	fi
fi
STORE_LEVELS="company level only"
if [ -n "$STORE_ID" ]; then
	print_status "Assigning the throwaway employee to store $STORE_ID..."
	api_write POST "$EMPLOYEE_PATH/$EMPLOYEE_ID/scope-assignments" \
		"{\"companyStoreId\":\"$STORE_ID\",\"validFrom\":\"$TODAY\"}"
	expect_code 201 "$W_CODE" "Creating the throwaway store-level scope assignment" || exit 1
	ASSIGNMENT_ID="$(json_first_id "$W_BODY")"
	STORE_LEVELS="company, country, region, zone and store"
	print_success "Store-level scope assignment created (${ASSIGNMENT_ID:-no id returned}): the account will publish all five claim levels"
else
	print_warning "Company $COMPANY_ID has no store chain this script could resolve, so this run proves the"
	print_warning "company-level claim only. The country/region/zone/store claim levels are NOT exercised by it."
fi

# The contract is the fact that produces the intent: the real producer enqueues
# ACTIVATE inside this write's transaction.
print_status "Opening the contract (this is what enqueues the ACTIVATE task)..."
api_write POST "$EMPLOYEE_PATH/$EMPLOYEE_ID/contracts" \
	"{\"positionId\":\"$POSITION_ID\",\"seniorityLevelId\":\"$SENIORITY_ID\",\"contractType\":\"PERMANENT\",\"monthlySalary\":1000.00,\"startDate\":\"$TODAY\"}"
expect_code 201 "$W_CODE" "Opening the contract" || exit 1
CONTRACT_ID="$(json_first_id "$W_BODY")"
print_success "Contract created (${CONTRACT_ID:-no id returned}) — the producer enqueued the ACTIVATE task in the same transaction"

# ============================================
# Wait for the worker to apply the task. The wait is bounded by the worker's own
# configured interval, so a small interval is not punished and a large one is not
# trusted blindly.
# ============================================
POLL_TIMEOUT=$((WORKER_INTERVAL * 2 + 60))
if [ "$POLL_TIMEOUT" -gt 600 ]; then
	POLL_TIMEOUT=600
fi
print_status "Waiting up to ${POLL_TIMEOUT}s for the task to reach APPLIED (worker interval ${WORKER_INTERVAL}s)..."
DEADLINE=$(($(date +%s) + POLL_TIMEOUT))
STATE=""
while :; do
	api_write GET "$ACCESS_PATH"
	if [ "$W_CODE" != "200" ]; then
		print_error "GET $ACCESS_PATH answered HTTP $W_CODE while waiting; the run cannot observe the task."
		print_error "A 401 here usually means the operator token expired between writes."
		exit 1
	fi
	STATE="$(json_task_status "$W_BODY")"
	case "$STATE" in
	APPLIED)
		break
		;;
	FAILED)
		print_error "The task FAILED. The Access section shows the reason; the API reports:"
		print_error "  lastError=$(json_string lastError "$W_BODY")"
		print_error "  attempts=$(printf '%s' "$W_BODY" | flatten | grep -o '"attempts"[[:space:]]*:[[:space:]]*[0-9]*' | head -1 | cut -d: -f2 || true)"
		exit 1
		;;
	APPROVAL_PENDING)
		print_error "The task is APPROVAL_PENDING: the position template declares roles outside the store-scoped"
		print_error "auto-apply set, so a human must release it — and this script cannot approve its own request,"
		print_error "because self-approval is refused by design (decision O3 of employee-access-provisioning)."
		print_error "What a human still has to do: choose a position whose template declares only store-scoped"
		print_error "roles, or approve the request in the Access section as a second operator, and run this"
		print_error "procedure against a company where the diff is inside that set."
		exit 1
		;;
	REJECTED)
		print_error "The task was REJECTED by a reviewer; nothing was applied."
		exit 1
		;;
	esac
	if [ "$(date +%s)" -ge "$DEADLINE" ]; then
		print_error "The task did not reach APPLIED within ${POLL_TIMEOUT}s (last state: ${STATE:-unknown})."
		print_error "Check the worker: ./docker/scripts/deploy.sh $ENV logs (service lifecontrol-api), that"
		print_error "WORKER_ENABLED is true and WORKER_INTERVAL_SECONDS is ${WORKER_INTERVAL} or smaller, and that the"
		print_error "task's next_attempt_at is not in the future (backoff after a failure)."
		exit 1
	fi
	sleep 2
done
print_success "Task reached APPLIED"

# ============================================
# The account, as the API reports it
# ============================================
api_write GET "$ACCESS_PATH"
OVERVIEW="$W_BODY"
ACCOUNT_LINKED="$(json_bool accountLinked "$OVERVIEW")"
EMPLOYEE_KC_ID="$(json_string keycloakUserId "$OVERVIEW")"
if [ "$ACCOUNT_LINKED" != "true" ] || [ -z "$EMPLOYEE_KC_ID" ]; then
	print_error "The task is APPLIED but the employee has no linked account (accountLinked=$ACCOUNT_LINKED)."
	exit 1
fi
print_success "Employee linked to Keycloak account $EMPLOYEE_KC_ID"

REQUIRED_ROLES="$(json_array requiredRoles "$OVERVIEW" | sort)"
if [ -z "$REQUIRED_ROLES" ]; then
	print_warning "The position template declares no roles, so this run proves the account carries none."
fi

# ============================================
# The invitation, through the Mailpit REST API
# ============================================
# find_message_id <messages-json> <email> -> the id of the first message whose
# recipient matches. Each message starts at its own "ID"; splitting there keeps
# the recipient list inside its message's chunk.
find_message_id() {
	printf '%s' "$1" \
		| sed 's/"ID":"/\n"ID":"/g' \
		| grep -F -- "$2" \
		| grep -o '"ID":"[^"]*"' | head -1 | cut -d'"' -f4 || true
}

print_status "Looking for the invitation to $EMPLOYEE_EMAIL in Mailpit..."
MID=""
for _ in $(seq 1 30); do
	MESSAGES="$(curl -sf "$MAILPIT_BASE/api/v1/messages?limit=50")"
	MID="$(find_message_id "$MESSAGES" "$EMPLOYEE_EMAIL")"
	if [ -n "$MID" ]; then
		break
	fi
	sleep 1
done
if [ -z "$MID" ]; then
	print_error "No message for $EMPLOYEE_EMAIL arrived in Mailpit within 30s, yet the task is APPLIED."
	print_error "The account was created and the send returned, so the realm relayed to a different SMTP server:"
	print_error "run ./docker/scripts/keycloak-setup.sh $ENV (SMTP_HOST/SMTP_PORT must point at mailpit in dev)."
	exit 1
fi
print_success "Invitation arrived in Mailpit (id $MID)"

MESSAGE_JSON="$(curl -sf "$MAILPIT_BASE/api/v1/message/$MID")"
LINK="$(printf '%s' "$MESSAGE_JSON" \
	| grep -oE 'https?://[^"\\ ]*login-actions/action-token[^"\\ ]*' | head -1 || true)"
if [ -z "$LINK" ]; then
	print_error "The received message carries no login-actions/action-token link."
	print_error "What arrived is not an activation link: check the realm's required actions and its action emails."
	exit 1
fi
LINK="${LINK//&amp;/&}"
echo ""
print_success "Invitation link: $LINK"

# The link must resolve to Keycloak: a base that serves Keycloak answers the
# discovery document with an issuer equal to that same base, while the app origin
# answers the SPA HTML.
link_base() { printf '%s' "$1" | sed -E 's#^([a-zA-Z][a-zA-Z0-9+.-]*://[^/]+)/.*#\1#'; }
LINK_BASE="$(link_base "$LINK")"
LINK_EXPECTED_ISSUER="$LINK_BASE/realms/$REALM"
LINK_DISCOVERY="$(curl -sf "$LINK_EXPECTED_ISSUER/.well-known/openid-configuration" 2>/dev/null)" || {
	print_error "The link base '$LINK_BASE' did not answer the Keycloak discovery document at"
	print_error "$LINK_EXPECTED_ISSUER/.well-known/openid-configuration."
	print_error "The invitation does not resolve to Keycloak; check the realm's frontendUrl (keycloak-setup.sh)."
	exit 1
}
LINK_ISSUER="$(json_string issuer "$LINK_DISCOVERY")"
if [ "$LINK_ISSUER" != "$LINK_EXPECTED_ISSUER" ]; then
	print_error "The link base '$LINK_BASE' does not serve Keycloak: its discovery reports issuer"
	print_error "'$LINK_ISSUER', expected '$LINK_EXPECTED_ISSUER'."
	exit 1
fi
print_success "Link base serves Keycloak (issuer $LINK_ISSUER)"

# ============================================
# The Keycloak account: requiredActions, the template's roles and the five claims
# ============================================
ACCOUNT_JSON="$(kc_admin GET "/users/$EMPLOYEE_KC_ID")"
ACCOUNT_EMAIL="$(json_string email "$ACCOUNT_JSON")"
ACCOUNT_USERNAME="$(json_string username "$ACCOUNT_JSON")"
if [ -z "$ACCOUNT_JSON" ] || [ -z "$ACCOUNT_EMAIL" ]; then
	print_error "The linked Keycloak account $EMPLOYEE_KC_ID could not be read back."
	exit 1
fi
if [ "$ACCOUNT_EMAIL" != "$EMPLOYEE_EMAIL" ]; then
	print_error "The linked account's email is '$ACCOUNT_EMAIL', not the employee's '$EMPLOYEE_EMAIL'."
	print_error "The link rule must bind the account to this person's address (records T5/T27)."
	exit 1
fi
print_success "Account username/email is the employee's ($ACCOUNT_USERNAME)"

ACCOUNT_ENABLED="$(json_bool enabled "$ACCOUNT_JSON")"
if [ "$ACCOUNT_ENABLED" != "true" ]; then
	print_error "The account exists but is not enabled; the invitation could not be completed."
	exit 1
fi

REQUIRED_ACTIONS="$(json_array requiredActions "$ACCOUNT_JSON" | sort)"
EXPECTED_ACTIONS="$(printf 'UPDATE_PASSWORD\nVERIFY_EMAIL\n' | sort)"
if [ "$REQUIRED_ACTIONS" != "$EXPECTED_ACTIONS" ]; then
	print_error "The account's requiredActions are '$(printf '%s' "$REQUIRED_ACTIONS" | tr '\n' ' ')', expected"
	print_error "'UPDATE_PASSWORD VERIFY_EMAIL' (decisions O1/T14): the invitation would not force verification and a"
	print_error "password change."
	exit 1
fi
print_success "requiredActions are exactly VERIFY_EMAIL + UPDATE_PASSWORD"

ROLE_MAPPINGS="$(kc_admin GET "/users/$EMPLOYEE_KC_ID/role-mappings/clients/$APP_CID")"
CURRENT_ROLES="$(json_strings name "$ROLE_MAPPINGS")"
if [ -z "$REQUIRED_ROLES" ]; then
	REQUIRED_LABEL="(none: the position template declares no roles)"
else
	REQUIRED_LABEL="$(printf '%s' "$REQUIRED_ROLES" | tr '\n' ' ')"
fi
if [ -z "$CURRENT_ROLES" ]; then
	CURRENT_LABEL="(none)"
else
	CURRENT_LABEL="$(printf '%s' "$CURRENT_ROLES" | tr '\n' ' ')"
fi
if [ "$CURRENT_ROLES" != "$REQUIRED_ROLES" ]; then
	print_error "The account's client roles do not match what the position template requires."
	print_error "  required:      $REQUIRED_LABEL"
	print_error "  on the account: $CURRENT_LABEL"
	exit 1
fi
print_success "Account roles match the position template: $CURRENT_LABEL"

# overview_claim_key <attribute key> -> the key the derivation's own record uses.
# The token and the account spells them snake_case (`company_store_id`, the shape
# ScopeLevel.claim() and the mappers use); AccessProvisioningClaims, whose value
# this compares against, exposes them camelCase. The mapping is written out rather
# than derived so a rename on either side fails loudly here instead of silently
# comparing a value with nothing.
overview_claim_key() {
	case "$1" in
	company_id) printf 'companyId' ;;
	company_country_id) printf 'companyCountryId' ;;
	company_region_id) printf 'companyRegionId' ;;
	company_zone_id) printf 'companyZoneId' ;;
	company_store_id) printf 'companyStoreId' ;;
	*) printf '%s' "$1" ;;
	esac
}

# check_claim <attribute key>
# The projection converges "the key present <=> the level is held", so both
# directions are asserted: a level the derivation holds must be on the account
# with those values, and a level it does not hold must have no attribute at all
# (an empty list would claim a level with no values).
check_claim() {
	local key="$1"
	local expected actual
	expected="$(json_array "$(overview_claim_key "$key")" "$OVERVIEW" | sort)"
	actual="$(json_array "$key" "$ACCOUNT_JSON" | sort)"
	if [ "$actual" != "$expected" ]; then
		if [ -z "$expected" ]; then
			print_error "The account publishes $key ('$(printf '%s' "$actual" | tr '\n' ' ')') but the derivation holds nothing at that level."
		else
			print_error "The account's $key is '$(printf '%s' "$actual" | tr '\n' ' ')', the derivation says '$(printf '%s' "$expected" | tr '\n' ' ')'."
		fi
		return 1
	fi
	if [ -z "$actual" ]; then
		print_status "  $key: not held (no attribute), as the derivation says"
	else
		print_status "  $key: $(printf '%s' "$actual" | tr '\n' ' ')"
	fi
}

print_status "Asserting the five company_* claim attributes against the derivation..."
check_claim company_id || exit 1
check_claim company_country_id || exit 1
check_claim company_region_id || exit 1
check_claim company_zone_id || exit 1
check_claim company_store_id || exit 1
print_success "All five company_* attributes agree with the derivation"

# ============================================
# Verdict
# ============================================
echo ""
print_success "Access-provisioning flow verified end to end for $ENV"
print_status "Covered: employee + contract write -> ACTIVATE produced by the real producer -> worker claim ->"
print_status "         APPLIED -> invitation in Mailpit with its Keycloak link -> account linked with its"
print_status "         requiredActions, the position template's roles and the five company_* claims."
print_status "Claim levels exercised: $STORE_LEVELS."
print_status "Not covered: the UI screens (this drives the API they call), and a position template that declares"
print_status "         no roles is only proven to grant none."
print_status "Cleanup on exit: the employee is soft-deleted (the API's DELETE keeps the row), the contract and the"
print_status "         store-level scope assignment are closed, and the Keycloak accounts of the employee and of the disposable"
print_status "         operator are deleted."
