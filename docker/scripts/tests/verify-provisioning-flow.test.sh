#!/bin/bash
# ============================================
# LifeControl - verify-provisioning-flow.sh offline behaviour tests
# ============================================
# Executes docker/scripts/verify-provisioning-flow.sh offline: no Docker daemon,
# no Keycloak, no API gateway, no Mailpit, no network. The script resolves its
# own paths from BASH_SOURCE and exposes no override, so the only way to give it
# a runnable environment without changing it is to COPY it byte-for-byte into a
# synthetic layout under mktemp -d (with _common.sh beside it, which is what it
# sources) and put a fake `curl` first on PATH. Both copies are asserted
# identical with `cmp -s` before the case runs, so the bytes under test are the
# repository's bytes.
#
# The seam is `curl`: verify-provisioning-flow.sh reaches Keycloak's Admin REST
# API, both token endpoints, the API gateway and Mailpit exclusively through it,
# so a PATH shim named `curl` intercepts 100% of the run. The shim logs every
# call (`<METHOD> <path>`, plus the claim it wrote and whether the token it
# minted carried it), which is what makes ORDER assertable — and order is the
# whole subject of this suite.
#
# The defect under test (odd/tasks/dev-edge-false-404.md, L8): the default mode
# created its disposable operator WITHOUT attributes, then listed companies. But
# CompanyService.claimedCompaniesPage answers an EMPTY page for a token with no
# company_id claim — "isAdmin() is not consulted here at all: the role guards the
# endpoint, it does not widen the tenant" — and the script wrote the operator's
# company_id only AFTER resolving a company. The dependency was inverted, so the
# default mode could not work. Measured both ways: no claim -> totalElements 0;
# same operator with attributes.company_id -> totalElements 1.
#
# The shim models exactly that: it mints the operator's JWTs, decodes the bearer
# token of every API call, answers the empty page to a token without the claim
# and 403 on a scoped company read. A run that inverts the ordering therefore
# FAILS this suite instead of passing it on a typo.
#
# Nothing is written inside the repository tree, and no repository .env.<env> or
# materialised secret is read: this worktree has none and CI has none.
#
# Run: ./docker/scripts/tests/verify-provisioning-flow.test.sh
# Exits 0 only when every case passes.

# No `set -e`: the cases assert non-zero exit codes, so every status is captured
# and compared explicitly (keycloak-setup.test.sh does the same). `set -u` keeps
# the harness honest about typos.
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERIFY_SH="$SCRIPT_DIR/../verify-provisioning-flow.sh"
COMMON_SH="$SCRIPT_DIR/../_common.sh"

PASS_COUNT=0
FAIL_COUNT=0

# The synthetic environment's values. The realm and client names are the
# defaults keycloak-setup.sh and the compose file use.
ENV_REALM="life-control-realm"
ENV_APP_CLIENT="life-control-client"
ENV_ADMIN_CLIENT="life-control-admin-client"
ENV_KC_PORT="8181"
ENV_AG_PORT="9000"
ENV_MAILPIT_PORT="8025"

COMPANY_ID="co-1"
COMPANY_DOMAIN="verifyflow.test"
ADMIN_PATH="/admin/realms/$ENV_REALM"

# The environment the harness must not inherit: API_BASE_URL would point the run
# at a live gateway, and the two PROVISIONING_VERIFY_* inputs are what each case
# sets on purpose.
unset API_BASE_URL PROVISIONING_VERIFY_TOKEN PROVISIONING_VERIFY_COMPANY_ID

TMP_BASE="$(mktemp -d)"

# shellcheck disable=SC2329  # invoked by the EXIT trap below, which shellcheck cannot trace
cleanup() {
	if [ -n "${TMP_BASE:-}" ] && [ -d "$TMP_BASE" ]; then
		rm -rf "$TMP_BASE"
	fi
}
trap cleanup EXIT

new_root() {
	mktemp -d "$TMP_BASE/case.XXXXXX"
}

pass() {
	printf 'PASS: %s\n' "$1"
	PASS_COUNT=$((PASS_COUNT + 1))
}

fail() {
	printf 'FAIL: %s\n' "$1"
	FAIL_COUNT=$((FAIL_COUNT + 1))
}

# assert_rc <name> <expected> <actual> <output>
assert_rc() {
	local name="$1" expected="$2" actual="$3" output="$4"
	if [ "$actual" -eq "$expected" ]; then
		pass "$name (rc=$actual)"
	else
		fail "$name (expected rc=$expected, got rc=$actual)"
		printf '%s\n' "$output" | sed 's/^/        /'
	fi
}

# assert_contains <name> <needle> <haystack>
assert_contains() {
	local name="$1" needle="$2" haystack="$3"
	if printf '%s' "$haystack" | grep -qF -- "$needle"; then
		pass "$name"
	else
		fail "$name (missing output: $needle)"
		printf '%s\n' "$haystack" | sed 's/^/        /'
	fi
}

# assert_not_contains <name> <needle> <haystack>
assert_not_contains() {
	local name="$1" needle="$2" haystack="$3"
	if printf '%s' "$haystack" | grep -qF -- "$needle"; then
		fail "$name (unexpected output: $needle)"
		printf '%s\n' "$haystack" | sed 's/^/        /'
	else
		pass "$name"
	fi
}

# assert_file_line <name> <line> <file> — the log holds <line> exactly, so a
# value that is merely a prefix of another fails.
assert_file_line() {
	local name="$1" line="$2" file="$3"
	if [ -f "$file" ] && grep -qxF -- "$line" "$file"; then
		pass "$name"
	else
		fail "$name (missing exact line in $file: $line)"
		if [ -f "$file" ]; then
			sed 's/^/        /' "$file"
		fi
	fi
}

# assert_file_has_no_match <name> <needle> <file>
# Fails when the file is MISSING as well as when it matches: a negative assertion
# must not be satisfiable by the evidence file never appearing.
assert_file_has_no_match() {
	local name="$1" needle="$2" file="$3"
	if [ ! -f "$file" ]; then
		fail "$name (evidence file is missing: $file)"
		return 0
	fi
	if grep -qF -- "$needle" "$file"; then
		fail "$name (unexpected match: $needle)"
		sed 's/^/        /' "$file"
	else
		pass "$name"
	fi
}

# assert_file_has_no_exact_line <name> <line> <file>
assert_file_has_no_exact_line() {
	local name="$1" line="$2" file="$3"
	if [ ! -f "$file" ]; then
		fail "$name (evidence file is missing: $file)"
		return 0
	fi
	if grep -qxF -- "$line" "$file"; then
		fail "$name (unexpected exact line: $line)"
		sed 's/^/        /' "$file"
	else
		pass "$name"
	fi
}

# assert_file_absent <name> <file> — the negative form of "it was created".
assert_file_absent() {
	local name="$1" file="$2"
	if [ -e "$file" ]; then
		fail "$name (file still exists: $file)"
	else
		pass "$name"
	fi
}

# line_of <line> <file> -> 1-based first exact match, else 0.
line_of() {
	local needle="$1" file="$2" n
	if [ ! -f "$file" ]; then
		printf '0\n'
		return 0
	fi
	n="$(grep -nxF -- "$needle" "$file" | head -1 | cut -d: -f1)"
	printf '%s\n' "${n:-0}"
}

# assert_before <name> <first> <second> <file>
# Both lines must exist AND the first must appear earlier. Without the existence
# half, two absent lines (0 and 0) would be indistinguishable from a wrong order.
assert_before() {
	local name="$1" first="$2" second="$3" file="$4" a b
	a="$(line_of "$first" "$file")"
	b="$(line_of "$second" "$file")"
	if [ "$a" -gt 0 ] && [ "$b" -gt 0 ] && [ "$a" -lt "$b" ]; then
		pass "$name (line $a before line $b)"
	else
		fail "$name (line of '$first'=$a, line of '$second'=$b)"
		if [ -f "$file" ]; then
			sed 's/^/        /' "$file"
		fi
	fi
}

# make_jwt <company-id> -> a JWT whose payload carries that company_id, i.e. the
# shape a human's real login token has (the five claims live at the root).
# base64url, because that is what decode_jwt_payload in the script reads.
make_jwt() {
	local payload b64
	payload="{\"sub\":\"offline-token\",\"company_id\":[\"$1\"]}"
	b64="$(printf '%s' "$payload" | base64 | tr -d '\n' | tr '+/' '-_' | tr -d '=')"
	printf 'eyJhbGciOiJub25lIn0.%s.offline' "$b64"
}

# write_shim <path>
# One heredoc, so this stays a single new file: no fixture and no helper script
# is added to the repository.
write_shim() {
	cat >"$1" <<'SHIM'
#!/bin/bash
# ============================================
# Offline `curl` shim for verify-provisioning-flow.test.sh (written at run time)
# ============================================
# It stands in for the real `curl` and answers every request
# verify-provisioning-flow.sh makes: Keycloak's discovery document and Admin REST
# API, the client-credentials and password grants, the API gateway's company
# surface and Mailpit's health endpoint.
#
# Every call is appended to $VPF_SHIM_STATE_DIR/calls.log as `<METHOD> <path>`,
# plus two notes the ordering assertions need:
#   ` company_id=<value>`                on the attribute write (PUT /users/<id>)
#   ` operator-token=claimed|unclaimed`  on every password grant, saying whether
#                                        the JWT it minted carries the claim.
#
# The claim is never a test-side flag: the shim MINTS the operator's JWTs and
# DECODES the bearer token of every API call, so the API answers from the claim
# the script actually sent. Without it the company list is the empty page and a
# scoped company read is 403 — the two behaviours measured live
# (odd/tasks/dev-edge-false-404.md L8, AGENTS.md "Claims de tenancy").
#
# `curl` argument handling is deliberately narrow: only the forms this script
# uses. `-o /dev/null` means "only the -w status is observable"; `-w` without it
# means "body, newline, status", which is exactly api_write's shape.
set -u

STATE="${VPF_SHIM_STATE_DIR:?VPF_SHIM_STATE_DIR must be set}"
mkdir -p "$STATE"

method="GET"
url=""
fmt=""
out=""
headers=()
data=()
fail_on_error=0

while [ "$#" -gt 0 ]; do
	case "$1" in
	-X) method="$2"; shift 2 ;;
	-H) headers+=("$2"); shift 2 ;;
	-d) data+=("$2"); shift 2 ;;
	-o) out="$2"; shift 2 ;;
	-w) fmt="$2"; shift 2 ;;
	http://* | https://*) url="$1"; shift ;;
	-*)
		case "$1" in *f*) fail_on_error=1 ;; esac
		shift
		;;
	*)
		printf 'shim: unsupported curl argument: %s\n' "$1" >&2
		exit 64
		;;
	esac
done

req=""
for d in "${data[@]+"${data[@]}"}"; do
	req="${req:+$req&}$d"
done

rest="${url#*://}"
case "$rest" in
*/*) path="/${rest#*/}" ;;
*) path="/" ;;
esac

read_state() {
	if [ -s "$STATE/$1" ]; then cat "$STATE/$1"; fi
}

cid="$(read_state company_id)"
domain="$(read_state company_domain)"
enabled="$(read_state company_enabled)"
[ -n "$enabled" ] || enabled=true

# mint_token <company-id or ""> -> the token response Keycloak would send.
mint_token() {
	local payload b64
	if [ -n "${1:-}" ]; then
		payload="{\"sub\":\"offline-shim\",\"company_id\":[\"$1\"]}"
	else
		payload='{"sub":"offline-shim"}'
	fi
	b64="$(printf '%s' "$payload" | base64 | tr -d '\n' | tr '+/' '-_' | tr -d '=')"
	printf '{"access_token":"eyJhbGciOiJub25lIn0.%s.offline","expires_in":300,"token_type":"Bearer"}' "$b64"
}

# bearer_claim -> the company_id the request's own bearer token carries, if any.
bearer_claim() {
	local h tok payload b64
	tok=""
	for h in "${headers[@]+"${headers[@]}"}"; do
		case "$h" in
		"Authorization: Bearer "*) tok="${h#Authorization: Bearer }" ;;
		esac
	done
	[ -n "$tok" ] || return 0
	payload="${tok#*.}"
	payload="${payload%%.*}"
	[ -n "$payload" ] && [ "$payload" != "$tok" ] || return 0
	b64="$(printf '%s' "$payload" | tr '_' '/' | tr '-' '+')"
	case $((${#b64} % 4)) in
	2) b64="${b64}==" ;;
	3) b64="${b64}=" ;;
	esac
	printf '%s' "$b64" | base64 -d 2>/dev/null \
		| grep -o '"company_id"[[:space:]]*:[[:space:]]*\[[^]]*\]' \
		| grep -o '"[^"]*"' | tail -n +2 | tr -d '"' || true
}

# has_bearer -> 0 when the request carries an Authorization header.
has_bearer() {
	local h
	for h in "${headers[@]+"${headers[@]}"}"; do
		case "$h" in
		"Authorization: Bearer "*) return 0 ;;
		esac
	done
	return 1
}

# claim_in_request -> the company_id an Admin API write asks to store.
claim_in_request() {
	printf '%s' "$req" | grep -o '"company_id"[[:space:]]*:[[:space:]]*\[[^]]*\]' \
		| grep -o '"[^"]*"' | tail -n +2 | tr -d '"' || true
}

handle_admin() {
	local sub uid attr
	sub="${path#/admin/realms/}"
	sub="${sub#*/}"
	case "$sub" in
	clients\?*)
		code=200
		resp="[{\"id\":\"cid-app\",\"clientId\":\"life-control-client\"}]"
		;;
	users\?*)
		code=200
		if [ -s "$STATE/operator_id" ]; then
			resp="[{\"id\":\"$(cat "$STATE/operator_id")\",\"username\":\"w6b-provision-ops-offline\"}]"
		else
			resp='[]'
		fi
		;;
	users)
		# The disposable operator is created. It carries NO attribute: the
		# script writes one only once its company is decided.
		code=201
		resp=""
		printf 'op-1' >"$STATE/operator_id"
		;;
	users/*)
		uid="${sub#users/}"
		case "$uid" in
		*/reset-password | */role-mappings/clients/*)
			code=204
			resp=""
			;;
		*)
			case "$method" in
			PUT)
				attr="$(claim_in_request)"
				if [ -n "$attr" ]; then
					printf '%s' "$attr" >"$STATE/operator_attributes"
					note=" company_id=$attr"
					code=204
				else
					code=400
				fi
				resp=""
				;;
			GET)
				code=200
				if [ -s "$STATE/operator_attributes" ]; then
					resp="{\"id\":\"$uid\",\"username\":\"w6b-provision-ops-offline\",\"attributes\":{\"company_id\":[\"$(cat "$STATE/operator_attributes")\"]}}"
				else
					resp="{\"id\":\"$uid\",\"username\":\"w6b-provision-ops-offline\",\"attributes\":{}}"
				fi
				;;
			DELETE)
				code=204
				resp=""
				rm -f "$STATE/operator_id" "$STATE/operator_attributes"
				;;
			*)
				code=405
				resp='{}'
				;;
			esac
			;;
		esac
		;;
	clients/*/roles/*)
		code=200
		resp='{"id":"role-lc-admin","name":"lc-admin"}'
		;;
	*)
		code=404
		resp='{}'
		;;
	esac
}

handle_api() {
	local claim sub
	claim="$(bearer_claim)"
	case "$path" in
	/api/companies)
		# CompanyService.claimedCompaniesPage: a token with no company_id claim
		# gets an EMPTY page (200, totalElements 0). The role does not widen it.
		code=200
		if [ -n "$claim" ]; then
			resp="{\"content\":[{\"id\":\"$cid\"}],\"totalElements\":1}"
		else
			resp='{"content":[],"totalElements":0}'
		fi
		;;
	/api/companies/*)
		sub="${path#/api/companies/}"
		case "$sub" in
		*/positions)
			# No position on purpose: the case stops right after resolution,
			# which is the furthest point the company resolution can be proven.
			code=200
			resp='[]'
			;;
		*/*)
			code=404
			resp='{}'
			;;
		*)
			if [ -z "$claim" ]; then
				# A scoped company read without the claim: 403, the shape the
				# tenancy guard produces for a missing claim.
				code=403
				resp='{"error":"Forbidden"}'
			elif [ "$sub" = "$cid" ]; then
				code=200
				resp="{\"id\":\"$cid\",\"name\":\"Verify Flow Co\",\"emailDomain\":\"$domain\",\"enabled\":$enabled}"
			else
				code=404
				resp='{}'
			fi
			;;
		esac
		;;
	/api/seniority-levels)
		code=200
		resp='[]'
		;;
	*)
		code=404
		resp='{}'
		;;
	esac
}

code=200
resp=""
note=""

case "$path" in
*/realms/*/.well-known/openid-configuration)
	resp="{\"issuer\":\"http://localhost:8181/realms/life-control-realm\"}"
	;;
*/api/v1/messages)
	resp='{"messages":[]}'
	;;
*/protocol/openid-connect/token)
	case "$req" in
	*grant_type=password*)
		if [ -s "$STATE/operator_attributes" ]; then
			note=" operator-token=claimed"
			resp="$(mint_token "$(cat "$STATE/operator_attributes")")"
		else
			note=" operator-token=unclaimed"
			resp="$(mint_token "")"
		fi
		;;
	*)
		resp="$(mint_token "")"
		;;
	esac
	;;
/admin/realms/*)
	handle_admin
	;;
/api/*)
	handle_api
	;;
*)
	code=404
	resp='{}'
	;;
esac

# The unauthenticated preflight probe ("is the route alive?") asks the same URL
# as the authenticated listing read, so an API call without a bearer token is
# marked: an assertion on `GET /api/companies` then means the LISTING read.
case "$path" in
/api/*)
	if ! has_bearer; then note="$note (no-token)"; fi
	;;
esac

printf '%s %s%s\n' "$method" "$path" "$note" >>"$STATE/calls.log"

if [ -n "$out" ]; then
	# -o /dev/null: only the -w format is observable.
	if [ -n "$fmt" ]; then printf '%s' "$code"; fi
else
	if [ -n "$fmt" ]; then
		printf '%s\n%s' "$resp" "$code"
	else
		printf '%s' "$resp"
	fi
fi

if [ "$fail_on_error" = "1" ] && [ "$code" -ge 400 ]; then
	exit 22
fi
exit 0
SHIM
}

# build_layout <root>
# Builds the synthetic tree, asserts both copied files are byte-identical to the
# repository's, and writes the shim. Returns 1 (after reporting) when a copy does
# not match, so the case is skipped rather than run against foreign bytes.
#
# Per-case world state is written by the case before it runs: the shim reads
# company_id / company_domain / company_enabled from the state directory. The
# layout defaults are the usable company, so a case only narrows what it needs.
build_layout() {
	local root="$1"
	mkdir -p "$root/docker/scripts" "$root/docker/secrets" "$root/bin" "$root/state"

	{
		printf 'KEYCLOAK_REALM=%s\n' "$ENV_REALM"
		printf 'KEYCLOAK_CLIENT_ID=%s\n' "$ENV_APP_CLIENT"
		printf 'KEYCLOAK_ADMIN_CLIENT_ID=%s\n' "$ENV_ADMIN_CLIENT"
		printf 'KEYCLOAK_PORT=%s\n' "$ENV_KC_PORT"
		printf 'API_GATEWAY_PORT=%s\n' "$ENV_AG_PORT"
		printf 'MAILPIT_PORT=%s\n' "$ENV_MAILPIT_PORT"
		printf 'WORKER_INTERVAL_SECONDS=60\n'
	} >"$root/docker/.env.dev"

	# Non-empty and free of the CHANGEME placeholder, which is all the script
	# asks of it before it reads it.
	printf '%s\n' 'offline-test-admin-client-secret' >"$root/docker/secrets/keycloak_admin_client_secret"

	cp "$VERIFY_SH" "$root/docker/scripts/verify-provisioning-flow.sh"
	cp "$COMMON_SH" "$root/docker/scripts/_common.sh"
	chmod +x "$root/docker/scripts/verify-provisioning-flow.sh"
	if ! cmp -s "$VERIFY_SH" "$root/docker/scripts/verify-provisioning-flow.sh" ||
		! cmp -s "$COMMON_SH" "$root/docker/scripts/_common.sh"; then
		fail "layout: a copied file differs from the repository's"
		return 1
	fi

	printf '%s' "$COMPANY_ID" >"$root/state/company_id"
	printf '%s' "${LAYOUT_DOMAIN-$COMPANY_DOMAIN}" >"$root/state/company_domain"
	printf '%s' "${LAYOUT_ENABLED:-true}" >"$root/state/company_enabled"
	# The log exists from the start, so "never called" is asserted against an
	# empty file rather than against a file that never appeared.
	: >"$root/state/calls.log"

	write_shim "$root/bin/curl"
	chmod +x "$root/bin/curl"
	return 0
}

# run_verify <root> — runs the copied script with the shim first on PATH. Sets
# RUN_RC and RUN_OUT (stdout + stderr together, because the script reports its
# refusals through print_error on stderr).
run_verify() {
	local root="$1"
	RUN_OUT="$(PATH="$root/bin:$PATH" bash "$root/docker/scripts/verify-provisioning-flow.sh" dev 2>&1)"
	RUN_RC=$?
}

RUN_OUT=""
RUN_RC=0

# ------------------------------------------
# Case 1 (a) — PROVISIONING_VERIFY_COMPANY_ID is set. The operator's company_id
# attribute must be written (and read back, and the token re-obtained) BEFORE the
# first company read, because the API answers an empty page without that claim.
# The run must get PAST resolution — the shim serves no positions, so it stops at
# the position message. This is the defect the change removes: with the inverted
# order the scoped read answers 403 and resolution never happens.
# ------------------------------------------
LAYOUT_DOMAIN="$COMPANY_DOMAIN"
LAYOUT_ENABLED=true
root="$(new_root)"
if build_layout "$root"; then
	export VPF_SHIM_STATE_DIR="$root/state"
	export PROVISIONING_VERIFY_COMPANY_ID="$COMPANY_ID"
	unset PROVISIONING_VERIFY_TOKEN
	run_verify "$root"
	state="$root/state"
	attr_write="PUT $ADMIN_PATH/users/op-1 company_id=$COMPANY_ID"
	company_read="GET /api/companies/$COMPANY_ID"
	assert_rc "case 1 (a): stops at the missing position, past company resolution" 1 "$RUN_RC" "$RUN_OUT"
	assert_contains "case 1 (a): resolution succeeded before stopping" \
		"Company $COMPANY_ID (email domain $COMPANY_DOMAIN)" "$RUN_OUT"
	assert_contains "case 1 (a): the message is the position one, not a refusal" \
		"has no enabled position" "$RUN_OUT"
	assert_file_line "case 1 (a): the attribute write carrying company_id is logged" \
		"$attr_write" "$state/calls.log"
	assert_file_line "case 1 (a): the company is read by id" "$company_read" "$state/calls.log"
	assert_before "case 1 (a): the claim is written BEFORE the first company read" \
		"$attr_write" "$company_read" "$state/calls.log"
	assert_file_line "case 1 (a): the token is re-obtained after the write and carries the claim" \
		"POST /realms/$ENV_REALM/protocol/openid-connect/token operator-token=claimed" "$state/calls.log"
	assert_before "case 1 (a): the refreshed token comes after the write" \
		"$attr_write" \
		"POST /realms/$ENV_REALM/protocol/openid-connect/token operator-token=claimed" "$state/calls.log"
	assert_file_has_no_exact_line "case 1 (a): the company list is never read in this mode" \
		"GET /api/companies" "$state/calls.log"
	# Cleanup: nothing disposable is left behind.
	assert_file_line "case 1 (a): the disposable operator is deleted" \
		"DELETE $ADMIN_PATH/users/op-1" "$state/calls.log"
	assert_file_absent "case 1 (a): no disposable operator is left behind" "$state/operator_id"
	unset PROVISIONING_VERIFY_COMPANY_ID
fi

# ------------------------------------------
# Case 2 (b) — the named company declares no emailDomain. The employee write
# fails closed without one, so the run must refuse, naming the domain cause, and
# never read a position or write an employee.
# ------------------------------------------
LAYOUT_DOMAIN=""
LAYOUT_ENABLED=true
root="$(new_root)"
if build_layout "$root"; then
	export VPF_SHIM_STATE_DIR="$root/state"
	export PROVISIONING_VERIFY_COMPANY_ID="$COMPANY_ID"
	unset PROVISIONING_VERIFY_TOKEN
	run_verify "$root"
	state="$root/state"
	assert_rc "case 2 (b): a company without emailDomain aborts with 1" 1 "$RUN_RC" "$RUN_OUT"
	assert_contains "case 2 (b): names the company that cannot be used" \
		"Company $COMPANY_ID cannot be used" "$RUN_OUT"
	assert_contains "case 2 (b): names the domain cause" "emailDomain" "$RUN_OUT"
	assert_contains "case 2 (b): explains that the write fails closed" "fails closed" "$RUN_OUT"
	assert_file_has_no_exact_line "case 2 (b): never resolves a position" \
		"GET /api/companies/$COMPANY_ID/positions" "$state/calls.log"
	assert_file_has_no_match "case 2 (b): never writes an employee" \
		"/employees" "$state/calls.log"
	assert_file_absent "case 2 (b): no disposable operator is left behind" "$state/operator_id"
	unset PROVISIONING_VERIFY_COMPANY_ID
fi

# ------------------------------------------
# Case 3 (b-2) — the same refusal for a disabled company: the domain is declared
# but `enabled` is false. Without this, case 2 would pass on any refusal.
# ------------------------------------------
LAYOUT_DOMAIN="$COMPANY_DOMAIN"
LAYOUT_ENABLED=false
root="$(new_root)"
if build_layout "$root"; then
	export VPF_SHIM_STATE_DIR="$root/state"
	export PROVISIONING_VERIFY_COMPANY_ID="$COMPANY_ID"
	unset PROVISIONING_VERIFY_TOKEN
	run_verify "$root"
	state="$root/state"
	assert_rc "case 3 (b-2): a disabled company aborts with 1" 1 "$RUN_RC" "$RUN_OUT"
	assert_contains "case 3 (b-2): names the company and the email domain cause" \
		"Company $COMPANY_ID cannot be used" "$RUN_OUT"
	assert_contains "case 3 (b-2): reports enabled=false" "enabled is 'false'" "$RUN_OUT"
	assert_file_has_no_exact_line "case 3 (b-2): never resolves a position" \
		"GET /api/companies/$COMPANY_ID/positions" "$state/calls.log"
	assert_file_absent "case 3 (b-2): no disposable operator is left behind" "$state/operator_id"
	unset PROVISIONING_VERIFY_COMPANY_ID
fi
LAYOUT_DOMAIN="$COMPANY_DOMAIN"
LAYOUT_ENABLED=true

# ------------------------------------------
# Case 4 (c) — neither input. The listing path stays, and it can only answer for
# a token that already carries the claim. An operator created by this run does
# not, so the list comes back empty and the run must stop naming
# PROVISIONING_VERIFY_COMPANY_ID as the way forward — the empty page is the
# symptom, not the missing company.
# ------------------------------------------
root="$(new_root)"
if build_layout "$root"; then
	export VPF_SHIM_STATE_DIR="$root/state"
	unset PROVISIONING_VERIFY_COMPANY_ID PROVISIONING_VERIFY_TOKEN
	run_verify "$root"
	state="$root/state"
	assert_rc "case 4 (c): an empty company list aborts with 1" 1 "$RUN_RC" "$RUN_OUT"
	assert_contains "case 4 (c): names the input that moves the run forward" \
		"PROVISIONING_VERIFY_COMPANY_ID" "$RUN_OUT"
	assert_not_contains "case 4 (c): never resolves a company" \
		"Company co-1 (email domain" "$RUN_OUT"
	assert_file_has_no_exact_line "case 4 (c): never resolves a position" \
		"GET /api/companies/$COMPANY_ID/positions" "$state/calls.log"
	assert_file_line "case 4 (c): the listing path is the one exercised" \
		"GET /api/companies" "$state/calls.log"
	assert_file_absent "case 4 (c): no disposable operator is left behind" "$state/operator_id"
fi

# ------------------------------------------
# Case 5 (d) — positive control. PROVISIONING_VERIFY_TOKEN carries the company_id
# claim and the API returns a non-empty page, so the listing path resolves the
# company. This proves the harness does not pass vacuously, and that mode (b)
# writes no identity into the realm.
# ------------------------------------------
root="$(new_root)"
if build_layout "$root"; then
	export VPF_SHIM_STATE_DIR="$root/state"
	unset PROVISIONING_VERIFY_COMPANY_ID
	export PROVISIONING_VERIFY_TOKEN="$(make_jwt "$COMPANY_ID")"
	run_verify "$root"
	state="$root/state"
	assert_rc "case 5 (d): a claim-bearing supplied token resolves and stops at the position" 1 "$RUN_RC" "$RUN_OUT"
	assert_contains "case 5 (d): the listing path resolves the company" \
		"Company $COMPANY_ID (email domain $COMPANY_DOMAIN)" "$RUN_OUT"
	assert_contains "case 5 (d): the supplied token is used as it came" \
		"Using the operator token supplied in PROVISIONING_VERIFY_TOKEN" "$RUN_OUT"
	assert_file_line "case 5 (d): the company list is what resolved it" \
		"GET /api/companies" "$state/calls.log"
	assert_file_line "case 5 (d): the company is read by id to validate it" \
		"GET /api/companies/$COMPANY_ID" "$state/calls.log"
	assert_file_has_no_exact_line "case 5 (d): no identity is written to the realm" \
		"POST $ADMIN_PATH/users" "$state/calls.log"
	assert_file_has_no_match "case 5 (d): the disposable operator is never touched" \
		"users/op-1" "$state/calls.log"
	assert_file_absent "case 5 (d): no disposable operator exists" "$state/operator_id"
	unset PROVISIONING_VERIFY_TOKEN
fi

# ------------------------------------------
# Summary
# ------------------------------------------
echo "------------------------------------------"
printf 'verify-provisioning-flow: %s passed, %s failed\n' "$PASS_COUNT" "$FAIL_COUNT"
if [ "$FAIL_COUNT" -ne 0 ]; then
	exit 1
fi
exit 0
