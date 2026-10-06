#!/bin/bash
# ============================================
# LifeControl - keycloak-setup.sh offline behaviour tests
# ============================================
# Executes docker/scripts/keycloak-setup.sh offline: no Docker daemon, no
# Keycloak, no network. The script resolves every path from BASH_SOURCE and
# exposes no override (keycloak-setup.sh:22-27, :155-156), so the only way to
# give it a runnable environment without changing it is to COPY it byte-for-byte
# into a synthetic layout under mktemp -d and put a fake `docker` first on PATH.
# The copy is asserted identical with `cmp -s` before it runs, so the bytes under
# test are the repository's bytes.
#
# The seam is the script's only two external shell-outs:
#   `docker ps --format '{{.Names}}'`                 keycloak-setup.sh:148
#   `docker exec "$CONTAINER" .../kcadm.sh "$@"`      keycloak-setup.sh:55-57
# Everything the script does to Keycloak rides inside the `docker exec` argv,
# so a PATH shim named `docker` intercepts 100% of it.
#
# Nothing is written inside the repository tree, and no repository .env.<env> or
# materialised secret is read: this worktree has none and CI has none.
#
# Run: ./docker/scripts/tests/keycloak-setup.test.sh
# Exits 0 only when every case passes.

# No `set -e`: the cases assert non-zero exit codes, so every status is captured
# and compared explicitly (freshness-guard.test.sh does the same). `set -u`
# keeps the harness honest about typos.
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SETUP_SH="$SCRIPT_DIR/../keycloak-setup.sh"

PASS_COUNT=0
FAIL_COUNT=0

# Values the synthetic .env.dev hands the script. The container name is derived
# exactly as keycloak-setup.sh:42-43 derives it.
ENV_COMPOSE_NAME="lc-offline-test"
ENV_REALM="life-control-realm"
ENV_APP_CLIENT="life-control-client"
ENV_ADMIN_CLIENT="life-control-admin-client"
ENV_KC_HTTP_PORT="8181"
ENV_WEB_APP_URL="http://localhost:4200"
# frontendUrl is Keycloak's OWN public base (KEYCLOAK_URL), deliberately NOT the
# app origin. They are different values on purpose (the first fixes the realm
# issuer and the link host, the second is the client's redirect target), and the
# tests pin them to different values so a regression that swaps them cannot pass.
ENV_KEYCLOAK_URL="http://localhost:8181"
export KCS_SHIM_CONTAINER="$ENV_COMPOSE_NAME-keycloak"

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

# count_lines <file> -> 0 when the file is absent or empty, else its line count.
count_lines() {
	if [ -s "$1" ]; then
		wc -l <"$1" | tr -d ' '
	else
		printf '0\n'
	fi
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

# assert_creates <name> <expected> <creates-log>
# The create count is an OBSERVATION: every `create` the shim saw is one line.
assert_creates() {
	local name="$1" expected="$2" file="$3" actual
	actual="$(count_lines "$file")"
	if [ "$actual" = "$expected" ]; then
		pass "$name ($actual)"
	else
		fail "$name (expected $expected, got $actual)"
		if [ -s "$file" ]; then
			sed 's/^/        /' "$file"
		fi
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

# write_shim <path>
# One heredoc, so this stays a single new file: no fixture and no helper script
# is added to the repository. The shim never calls `docker` (not even
# indirectly) and never sets -e, so a deliberate exit 1 stays exit 1.
write_shim() {
	cat >"$1" <<'SHIM'
#!/bin/bash
# ============================================
# Offline `docker` shim for keycloak-setup.test.sh (written at run time)
# ============================================
# It stands in for the real `docker` CLI and answers exactly the two external
# shell-outs keycloak-setup.sh makes, and nothing else:
#
#   `docker ps --format '{{.Names}}'`                keycloak-setup.sh:148
#   `docker exec "$CONTAINER" .../kcadm.sh "$@"`     keycloak-setup.sh:55-57
#
# The argv after `exec` is parsed positionally: container, then everything up to
# and including the kcadm.sh argument, then the kcadm argv (keycloak-setup.sh:56).
#
# Per-case state lives in $KCS_SHIM_STATE_DIR:
#   mappers         claim names whose protocol mapper exists (one per line),
#                   seeded by the test.
#   created_mappers claim names this run created (appended here on `create`), so
#                   the mapper-list read at keycloak-setup.sh:344 sees them.
#   created_config  the DECLARATION each created mapper was asked for, one line
#                   per create: the claim name, then every `-s` argument the
#                   create passed, tab-separated. The single-mapper read-back is
#                   rebuilt from this line, so the JSON verification sees is
#                   exactly what the create asked for — no write happens, the
#                   round trip is argv -> read-back, and that is what makes a
#                   wrong create fail instead of passing: a create declaring
#                   multivalued=false reads back false and fails
#                   verify_tenancy_mapper instead of being papered over.
#   creates.log     every `create` argv, one line per call. THIS is the
#                   observation "how many creates were attempted".
#   updates.log     every `update` argv, one line per call, so the test can prove
#                   the script ATTEMPTED no repair of a drifted mapper (the
#                   fail-rather-than-repair property itself is keycloak-setup.sh:314-316).
#   calls.log       every kcadm argv, one line per call (evidence, not assertion).
#   drift           a claim whose single-mapper JSON is served with
#                   multivalued=false, modelling an existing but drifted mapper.
#   realm_frontend_url  the realm's attributes.frontendUrl (empty when unset),
#                   seeded by the test and rewritten by `update realms/*`.
#   realm_smtp      the realm's smtpServer map, one `field<TAB>value` per line,
#                   rewritten whole on `update realms/*` (the live Admin API
#                   replaces the map, measured 2026-10-06). A `password` entry is
#                   served back masked as `**********`, exactly as the live read
#                   does, so a read-back can assert presence but never the value.
#   client_redirect_uris / client_web_origins
#                   the public client's registered lists, one value per line,
#                   seeded by the test and rewritten on `update clients/*`.
#
# Injection knob (exported by the test):
#   KCS_SHIM_REALM_DROP_UPDATE=1  the realm update is logged but NOT applied, so
#                   the read-back models a write that did not stick.
#   KCS_SHIM_INJECT_MATCH / KCS_SHIM_INJECT_KIND as before.
#   KCS_SHIM_INJECT_MATCH  substring of the kcadm argv to disturb ("" = none)
#   KCS_SHIM_INJECT_KIND   "unanswered" = the Admin API never answered
#                          "absent"     = the genuine `Resource not found` text
# The two diagnostics are exactly what keycloak-setup.sh:60-65 says is the only
# discriminator between an absent resource and a dead Admin API:
# `absent` MUST contain "Resource not found" (keycloak-setup.sh:84), and
# `unanswered` MUST NOT, or the script would read a dead API as "absent".
set -u

STATE="${KCS_SHIM_STATE_DIR:?KCS_SHIM_STATE_DIR must be set}"
mkdir -p "$STATE"

# ---- docker ps (keycloak-setup.sh:148) ----
# Exactly one line so `grep -qx "$CONTAINER"` matches it.
if [ "${1:-}" = "ps" ]; then
	printf '%s\n' "${KCS_SHIM_CONTAINER:?KCS_SHIM_CONTAINER must be set}"
	exit 0
fi

# The script has no other docker call, so anything else is a harness bug.
if [ "${1:-}" != "exec" ]; then
	printf 'shim: refusing unsupported docker invocation: %s\n' "$*" >&2
	exit 64
fi

shift
# Drop the container name.
if [ "$#" -gt 0 ]; then
	shift
fi
# Drop everything up to and including the kcadm.sh argument.
while [ "$#" -gt 0 ]; do
	case "$1" in
		*/kcadm.sh | kcadm.sh)
			shift
			break
			;;
		*) shift ;;
	esac
done

SUB="${1:-}"
if [ "$#" -gt 0 ]; then
	shift
fi
argv="$SUB $*"
printf '%s\n' "$argv" >>"$STATE/calls.log"

absent() {
	printf 'Resource not found for url: http://localhost:8181/admin/realms/life-control-realm/%s\n' "$1" >&2
	exit 1
}

unanswered() {
	printf 'HTTP request error: Connect to localhost:8181 [localhost/127.0.0.1] failed: Connection refused (Connection refused)\n' >&2
	exit 1
}

# ---- Realm / client world state (W6b) ----
# realm_state_json prints the realm read-back the way the live Admin API answers:
# frontendUrl nested under "attributes" (a top-level frontendUrl is rejected by
# RealmRepresentation, measured 2026-10-06) and smtpServer as a flat string map
# with any password masked.
realm_state_json() {
	local realm="$1" fe="" entries="" k v
	if [ -s "$STATE/realm_frontend_url" ]; then
		fe="$(cat "$STATE/realm_frontend_url")"
	fi
	if [ -s "$STATE/realm_smtp" ]; then
		while IFS=$'\t' read -r k v; do
			[ -z "$k" ] && continue
			if [ "$k" = "password" ]; then
				v="**********"
			fi
			entries="${entries:+$entries, }\"$k\" : \"$v\""
		done <"$STATE/realm_smtp"
	fi
	printf '{ "realm" : "%s", "enabled" : true' "$realm"
	if [ -n "$fe" ]; then
		printf ', "attributes" : { "frontendUrl" : "%s" }' "$fe"
	fi
	printf ', "smtpServer" : { %s } }' "$entries"
}

# emit_array <state-file> <field> -> a pretty-printed JSON array member.
emit_array() {
	local file="$1" field="$2" out="" v
	if [ -s "$file" ]; then
		while IFS= read -r v; do
			[ -z "$v" ] && continue
			out="${out:+$out, }\"$v\""
		done <"$file"
	fi
	printf '  "%s" : [ %s ]' "$field" "$out"
}

# json_array_to_lines <json-array> -> one element per line, quotes stripped.
# The trailing newline matters: `read` drops a final line that has none, which
# would silently lose the last registered origin.
json_array_to_lines() {
	printf '%s\n' "$1" | sed 's/^\[//; s/\]$//' | tr ',' '\n' | tr -d '"'
}

case "$SUB" in
	config)
		# keycloak-setup.sh:173 — kcadm stores credentials inside the container.
		exit 0
		;;
	add-roles)
		# keycloak-setup.sh:372 — success so the script prints the assignment.
		exit 0
		;;
	create)
		# Every create is recorded and NEVER executed.
		printf '%s\n' "$argv" >>"$STATE/creates.log"
		# A created mapper becomes part of the world, so the mapper-list read at
		# keycloak-setup.sh:344 finds it, and its DECLARATION is recorded so the
		# single-mapper read below serves back what the create asked for.
		case "${1:-}" in
			clients/*/protocol-mappers/models)
				claim=""
				sargs=""
				expect=""
				for a in "$@"; do
					if [ "$expect" = "-s" ]; then
						sargs="${sargs:+$sargs	}$a"
						case "$a" in
							name=*) claim="${a#name=}" ;;
						esac
						expect=""
						continue
					fi
					case "$a" in
						-s) expect="-s" ;;
						name=*) claim="${a#name=}" ;;
					esac
				done
				if [ -n "$claim" ]; then
					printf '%s\n' "$claim" >>"$STATE/created_mappers"
					printf '%s\t%s\n' "$claim" "$sargs" >>"$STATE/created_config"
				fi
				;;
		esac
		exit 0
		;;
	update)
		# Recorded so the test can prove there is no repair of a drifted mapper,
		# and so a realm/client convergence can be observed by resource.
		printf '%s\n' "$argv" >>"$STATE/updates.log"
		res="${1:-}"
		case "$res" in
			realms/*)
				# KCS_SHIM_REALM_DROP_UPDATE models an update that returns 0 and
				# does not stick, which is the failure the read-back must catch.
				if [ "${KCS_SHIM_REALM_DROP_UPDATE:-0}" != "1" ]; then
					for a in "$@"; do
						case "$a" in
							attributes.frontendUrl=*)
								printf '%s' "${a#attributes.frontendUrl=}" >"$STATE/realm_frontend_url"
								;;
							smtpServer=*)
								inner="$(printf '%s' "${a#smtpServer=}" | sed 's/^[{]//; s/[}]$//')"
								: >"$STATE/realm_smtp.next"
								printf '%s\n' "$inner" | tr ',' '\n' >"$STATE/realm_smtp.pairs"
								while IFS= read -r pair; do
									[ -z "$pair" ] && continue
									pair="${pair//\"/}"
									k="${pair%%:*}"
									v="${pair#*:}"
									[ -n "$k" ] && printf '%s\t%s\n' "$k" "$v" >>"$STATE/realm_smtp.next"
								done <"$STATE/realm_smtp.pairs"
								mv "$STATE/realm_smtp.next" "$STATE/realm_smtp"
								;;
						esac
					done
				fi
				;;
			clients/*)
				for a in "$@"; do
					case "$a" in
						redirectUris=*) json_array_to_lines "${a#redirectUris=}" >"$STATE/client_redirect_uris" ;;
						webOrigins=*) json_array_to_lines "${a#webOrigins=}" >"$STATE/client_web_origins" ;;
					esac
				done
				;;
		esac
		exit 0
		;;
	get)
		res="${1:-}"
		if [ "$#" -gt 0 ]; then
			shift
		fi
		present=0
		output=""
		case "$res" in
			realms/*)
				# keycloak-setup.sh:176 — the full representation the realm
				# convergence reads back (frontendUrl + smtpServer).
				present=1
				output="$(realm_state_json "${res#realms/}")"
				;;
			clients)
				# keycloak-setup.sh:97 and :111 — client lookup by clientId, CSV id.
				cid=""
				for a in "$@"; do
					case "$a" in
						clientId=*) cid="${a#clientId=}" ;;
					esac
				done
				present=1
				output="cid-$cid"
				;;
			clients/*/protocol-mappers/models)
				# keycloak-setup.sh:138 — id,name CSV. The script selects the claim
				# client-side (the -q form does not filter) and takes field 1 as id.
				for f in "$STATE/mappers" "$STATE/created_mappers"; do
					if [ ! -f "$f" ]; then
						continue
					fi
					while IFS= read -r claim; do
						if [ -z "$claim" ]; then
							continue
						fi
						if [ -z "$output" ]; then
							output="mid-$claim,$claim"
						else
							output="$output
mid-$claim,$claim"
						fi
						present=1
					done <"$f"
				done
				;;
			clients/*/protocol-mappers/models/*)
				# keycloak-setup.sh:323 and :348 — the single-mapper JSON that
				# verify_tenancy_mapper (keycloak-setup.sh:297-318) checks. The id
				# is the read-back path's last segment; the claim is encoded in it.
				mid="${res##*/}"
				claim="${mid#mid-}"
				# Round trip, two worlds:
				#   created in THIS run -> rebuild the JSON from the declaration
				#     the create argv carried (created_config), so the read-back is a
				#     function of the write: a create asking for multivalued=false
				#     reads back false and verify_tenancy_mapper rejects it.
				#   seeded by the test -> serve the ideal contract JSON below, with
				#     the drift knob flipping multivalued to false. That models a
				#     mapper already in the realm and already correct (or drifted).
				declared=""
				if [ -f "$STATE/created_config" ]; then
					while IFS= read -r line; do
						if [ "${line%%	*}" = "$claim" ]; then
							declared="${line#*	}"
							break
						fi
					done <"$STATE/created_config"
				fi
				present=1
				if [ -n "$declared" ]; then
					IFS=$'\t' read -r -a declared_args <<<"$declared"
					top_members=("  \"id\" : \"$mid\"")
					config_members=()
					for sarg in "${declared_args[@]}"; do
						member_key="${sarg%%=*}"
						member_value="${sarg#*=}"
						case "$member_key" in
							config.*) config_members+=("    ${member_key#config.} : \"$member_value\"") ;;
							*) top_members+=("  \"$member_key\" : \"$member_value\"") ;;
						esac
					done
					top_json="$(IFS=,; printf '%s' "${top_members[*]}")"
					config_json="$(IFS=,; printf '%s' "${config_members[*]}")"
					output="{
$top_json,
  \"config\" : {
$config_json
  }
}"
				else
					multivalued="true"
					if [ -f "$STATE/drift" ] && [ "$(cat "$STATE/drift")" = "$claim" ]; then
						multivalued="false"
					fi
					output="$(
						printf '%s\n' \
							'{' \
							"  \"id\" : \"$mid\"," \
							"  \"name\" : \"$claim\"," \
							'  "protocol" : "openid-connect",' \
							'  "protocolMapper" : "oidc-usermodel-attribute-mapper",' \
							'  "config" : {' \
							"    \"claim.name\" : \"$claim\"," \
							"    \"user.attribute\" : \"$claim\"," \
							"    \"multivalued\" : \"$multivalued\"," \
							'    "access.token.claim" : "true",' \
							'    "id.token.claim" : "false",' \
							'    "userinfo.token.claim" : "false",' \
							'    "jsonType.label" : "String"' \
							'  }' \
							'}'
					)"
				fi
				;;
			clients/*/roles/*)
				# keycloak-setup.sh:126
				present=1
				output="{\"name\":\"${res##*/}\"}"
				;;
			clients/*)
				# The public client's own representation: redirectUris and
				# webOrigins, served from the world state so a convergence write is
				# observable on the read-back. Listed after the more specific
				# /protocol-mappers and /roles patterns so they keep winning.
				present=1
				output="{
$(emit_array "$STATE/client_redirect_uris" redirectUris),
$(emit_array "$STATE/client_web_origins" webOrigins)
}"
				;;
			users/profile)
				# keycloak-setup.sh:251 and :262, CSV. The four cases keep the
				# realm at ADMIN_EDIT, so the update and its read-back are not
				# exercised here.
				present=1
				output="ADMIN_EDIT"
				;;
			roles/*)
				# keycloak-setup.sh:145
				present=1
				output="{\"name\":\"${res#roles/}\"}"
				;;
			*)
				printf 'shim: unsupported kcadm read: %s\n' "$argv" >&2
				exit 1
				;;
		esac

		# Injection knob. "unanswered" makes the API fail to answer at all.
		# "absent" makes an empty read speak the 404 diagnostic instead of
		# succeeding empty; it stops firing once the resource exists, so the
		# create at keycloak-setup.sh:331 is what makes the read-back at :344
		# see it. This knob pins the DISCRIMINATOR the script keys on
		# (keycloak-setup.sh:84), not a live behaviour: which of the two an
		# existing-but-empty resource produces in a real Keycloak was never
		# measured, so nothing here claims it.
		if [ -n "${KCS_SHIM_INJECT_MATCH:-}" ] && [ -n "${KCS_SHIM_INJECT_KIND:-}" ]; then
			case "$argv" in
				*"$KCS_SHIM_INJECT_MATCH"*)
					if [ "$KCS_SHIM_INJECT_KIND" = "unanswered" ]; then
						unanswered
					fi
					if [ "$present" -eq 0 ]; then
						absent "$res"
					fi
					;;
			esac
		fi

		if [ "$present" -eq 0 ]; then
			# With no injection and nothing in the world this is an empty success,
			# which find_mapper_id (keycloak-setup.sh:139-140) turns into "absent".
			exit 0
		fi
		printf '%s\n' "$output"
		exit 0
		;;
	*)
		printf 'shim: unsupported kcadm subcommand: %s\n' "$SUB" >&2
		exit 1
		;;
esac
SHIM
}

# build_layout <root> <state-dir> <all|none> <drift-claim>
# Builds the synthetic tree D1 prescribes, asserts the copied script is
# byte-identical to the repository's, and writes the shim. Returns 1 (after
# reporting) when the copy does not match, so the case is skipped rather than
# run against foreign bytes.
build_layout() {
	local root="$1" state="$2" mappers="$3" drift="$4"
	mkdir -p "$root/docker/scripts" "$root/docker/secrets" "$root/bin" "$state"

	{
		printf 'COMPOSE_PROJECT_NAME=%s\n' "$ENV_COMPOSE_NAME"
		printf 'KEYCLOAK_REALM=%s\n' "$ENV_REALM"
		printf 'KEYCLOAK_CLIENT_ID=%s\n' "$ENV_APP_CLIENT"
		printf 'KEYCLOAK_ADMIN_CLIENT_ID=%s\n' "$ENV_ADMIN_CLIENT"
		printf 'KC_HTTP_PORT=%s\n' "$ENV_KC_HTTP_PORT"
		printf 'WEB_APP_URL=%s\n' "$ENV_WEB_APP_URL"
		# frontendUrl is Keycloak's own public base (KEYCLOAK_URL), NOT the app
		# origin; a non-dev case may point it at a localhost default to pin the
		# refusal. WEB_APP_URL stays the app origin for redirectUris/webOrigins.
		# LAYOUT_OMIT_KEYCLOAK_URL models an env file that declares neither
		# KEYCLOAK_URL nor KEYCLOAK_PORT, so the script must fall back.
		if [ "${LAYOUT_OMIT_KEYCLOAK_URL:-0}" != "1" ]; then
			printf 'KEYCLOAK_URL=%s\n' "${LAYOUT_KEYCLOAK_URL:-$ENV_KEYCLOAK_URL}"
		fi
		# SMTP_HOST is left absent in dev (get_env falls back to mailpit). A
		# non-dev case must declare a real relay, so the SMTP refusal cannot
		# pre-empt the frontendUrl refusal the case is actually testing.
		if [ -n "${LAYOUT_SMTP_HOST:-}" ]; then
			printf 'SMTP_HOST=%s\n' "$LAYOUT_SMTP_HOST"
		fi
		# SMTP_AUTH only matters to the W6b cases; the credential itself is
		# deliberately never materialized here (its absence is what case 8 pins).
		if [ "${LAYOUT_SMTP_AUTH:-false}" = "true" ]; then
			printf 'SMTP_AUTH=true\n'
			printf 'SMTP_USER=ops@example.com\n'
		fi
	} >"$root/docker/.env.${LAYOUT_ENV:-dev}"

	# Non-empty and free of the CHANGEME placeholder (keycloak-setup.sh:158-167).
	printf '%s\n' 'offline-test-admin-password' >"$root/docker/secrets/keycloak_admin_password"
	printf '%s\n' 'offline-test-admin-client-secret' >"$root/docker/secrets/keycloak_admin_client_secret"

	cp "$SETUP_SH" "$root/docker/scripts/keycloak-setup.sh"
	chmod +x "$root/docker/scripts/keycloak-setup.sh"
	if ! cmp -s "$SETUP_SH" "$root/docker/scripts/keycloak-setup.sh"; then
		fail "layout: the copied script differs from $SETUP_SH"
		return 1
	fi

	# Protocol-mapper world state for this case.
	: >"$state/mappers"
	if [ "$mappers" = "all" ]; then
		printf '%s\n' \
			company_id company_country_id company_region_id company_zone_id company_store_id \
			>"$state/mappers"
	fi
	: >"$state/created_mappers"
	: >"$state/created_config"
	# Both evidence logs exist from the start, so "no create" and "no update" are
	# asserted against an empty file rather than against a file that never
	# appeared — a missing log could otherwise satisfy a negative assertion.
	: >"$state/creates.log"
	: >"$state/updates.log"
	printf '%s' "$drift" >"$state/drift"

	# W6b world state: realm frontendUrl/smtpServer and the public client's
	# registered lists. Defaults are the already-converged values, so the plain
	# cases see an idempotent world; a case narrows them to model a first-run or
	# a partially registered client.
	: >"$state/realm_frontend_url"
	: >"$state/realm_smtp"
	printf '%s\n' "${LAYOUT_CLIENT_REDIRECTS:-${ENV_WEB_APP_URL}/*}" >"$state/client_redirect_uris"
	printf '%s\n' "${LAYOUT_CLIENT_WEB_ORIGINS:-$ENV_WEB_APP_URL}" >"$state/client_web_origins"

	write_shim "$root/bin/docker"
	chmod +x "$root/bin/docker"
	return 0
}

# run_setup_env <root> <env> — runs the copied script with the shim first on
# PATH. Sets SETUP_RC and SETUP_OUT (stdout + stderr together, because the script
# reports refusals through print_error on stderr).
run_setup_env() {
	local root="$1" env="${2:-dev}"
	SETUP_OUT="$(PATH="$root/bin:$PATH" bash "$root/docker/scripts/keycloak-setup.sh" "$env" 2>&1)"
	SETUP_RC=$?
}

run_setup() {
	run_setup_env "$1" dev
}

# assert_line_count <name> <expected> <file> — same observation as assert_creates
# but named for the update logs the W6b cases count.
assert_line_count() {
	local name="$1" expected="$2" file="$3" actual
	actual="$(count_lines "$file")"
	if [ "$actual" = "$expected" ]; then
		pass "$name ($actual)"
	else
		fail "$name (expected $expected, got $actual)"
		if [ -s "$file" ]; then
			sed 's/^/        /' "$file"
		fi
	fi
}

# assert_file_line <name> <line> <file> — passes only when the file holds <line>
# exactly, so a value that is merely a prefix of another fails.
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

# ------------------------------------------
# Case 1 — happy path, idempotent: realm, clients, roles, user-profile policy
# and all five tenancy mappers already exist and match the declared contract.
# Control: without it, "aborts" and "works" are indistinguishable.
# ------------------------------------------
root="$(new_root)"
state="$root/state"
if build_layout "$root" "$state" all ""; then
	export KCS_SHIM_STATE_DIR="$state"
	export KCS_SHIM_INJECT_MATCH=""
	export KCS_SHIM_INJECT_KIND=""
	run_setup "$root"
	assert_rc "case 1: a fully provisioned realm exits 0" 0 "$SETUP_RC" "$SETUP_OUT"
	assert_creates "case 1: no create is attempted" 0 "$state/creates.log"
	assert_contains "case 1: reports the realm present" \
		"Realm $ENV_REALM already exists" "$SETUP_OUT"
	assert_contains "case 1: verifies an existing mapper against the contract" \
		"Protocol mapper $ENV_APP_CLIENT/company_store_id exists and matches the declared contract" "$SETUP_OUT"
	assert_contains "case 1: reaches the end" "Keycloak setup complete for dev" "$SETUP_OUT"
fi

# ------------------------------------------
# Case 2 — R4-001: the mapper-list read answers as an Admin API that never
# answered (any diagnostic other than "Resource not found"). The script must
# hard-abort; reading it as "absent" is the defect this pins.
# ------------------------------------------
root="$(new_root)"
state="$root/state"
if build_layout "$root" "$state" all ""; then
	export KCS_SHIM_STATE_DIR="$state"
	export KCS_SHIM_INJECT_MATCH="protocol-mappers/models -r"
	export KCS_SHIM_INJECT_KIND="unanswered"
	run_setup "$root"
	assert_rc "case 2 (R4-001): an unanswered mapper-list read aborts with 1" 1 "$SETUP_RC" "$SETUP_OUT"
	assert_creates "case 2 (R4-001): no create is attempted" 0 "$state/creates.log"
	assert_contains "case 2 (R4-001): refuses to read the failure as \"not found\"" \
		'Refusing to read an unanswered Admin API call as "not found".' "$SETUP_OUT"
	assert_contains "case 2 (R4-001): names the offending read" \
		"$ENV_APP_CLIENT/protocol-mappers/models" "$SETUP_OUT"
	# Discriminating on its own: rc=1 alone is also what a refusal *after* a
	# create produces, so the case must also prove the create branch was never
	# entered. assert_creates above observes it; this observes the message.
	assert_not_contains "case 2 (R4-001): never enters the create branch" \
		"Creating protocol mapper" "$SETUP_OUT"
	assert_not_contains "case 2 (R4-001): never reports success" \
		"Keycloak setup complete" "$SETUP_OUT"
fi

# ------------------------------------------
# Case 3 — the ABSENCE branch of the same read: the SAME read answers with the
# "Resource not found" diagnostic the live probes observed for a missing resource
# (odd/tasks/keycloak-setup-hardening.md, measured 2026-10-02) while no mapper
# exists. The script must consume it as absence (kcadm_get, keycloak-setup.sh:84-86)
# and provision, recording exactly the five mapper creates.
# Inverse injection: without it, case 2 would be satisfied by aborting on
# everything, including a genuine absence.
# Limit, stated rather than implied: this exercises the DIAGNOSTIC branch only.
# find_mapper_id (keycloak-setup.sh:139-140) also reads an exit-0 empty list as
# "absent", and that branch is not exercised here because what a real Keycloak
# returns for an existing client with zero mappers was never measured live.
# ------------------------------------------
root="$(new_root)"
state="$root/state"
if build_layout "$root" "$state" none ""; then
	export KCS_SHIM_STATE_DIR="$state"
	export KCS_SHIM_INJECT_MATCH="protocol-mappers/models -r"
	export KCS_SHIM_INJECT_KIND="absent"
	run_setup "$root"
	assert_rc "case 3: a genuine absence is provisioned, not aborted" 0 "$SETUP_RC" "$SETUP_OUT"
	assert_creates "case 3: exactly the five tenancy mappers are created" 5 "$state/creates.log"
	assert_contains "case 3: creates the last tenancy mapper" \
		"Protocol mapper $ENV_APP_CLIENT/company_store_id created and verified" "$SETUP_OUT"
	assert_not_contains "case 3: does not refuse the absence diagnostic" \
		"Refusing to read an unanswered Admin API call" "$SETUP_OUT"
	assert_contains "case 3: reaches the end" "Keycloak setup complete for dev" "$SETUP_OUT"
fi

# ------------------------------------------
# Case 4 — R3-MAPPER-DRIFT: an existing mapper's JSON omits a required key
# (multivalued is false). The script must fail, name the claim and the missing
# requirement, and NOT repair it: no create and no mapper update.
# ------------------------------------------
root="$(new_root)"
state="$root/state"
if build_layout "$root" "$state" all "company_region_id"; then
	export KCS_SHIM_STATE_DIR="$state"
	export KCS_SHIM_INJECT_MATCH=""
	export KCS_SHIM_INJECT_KIND=""
	run_setup "$root"
	assert_rc "case 4 (R3-MAPPER-DRIFT): a drifted mapper aborts with 1" 1 "$SETUP_RC" "$SETUP_OUT"
	assert_creates "case 4 (R3-MAPPER-DRIFT): no create is attempted" 0 "$state/creates.log"
	assert_contains "case 4 (R3-MAPPER-DRIFT): names the claim and the missing requirement" \
		"$ENV_APP_CLIENT/company_region_id is missing \"multivalued\":\"true\"." "$SETUP_OUT"
	assert_contains "case 4 (R3-MAPPER-DRIFT): the drifted mapper was actually read" \
		"protocol-mappers/models/mid-company_region_id" "$(cat "$state/calls.log")"
	assert_file_has_no_match "case 4 (R3-MAPPER-DRIFT): no mapper update (no repair)" \
		"protocol-mappers/models" "$state/updates.log"
	assert_not_contains "case 4 (R3-MAPPER-DRIFT): never reports success" \
		"Keycloak setup complete" "$SETUP_OUT"
fi

# ------------------------------------------
# W6b — the invitation environment. Cases 5-10 pin the realm frontendUrl/SMTP
# convergence, the conditional SMTP credential, the non-dev refusal and the
# additive client redirect-URI convergence. Layout knobs are reset per case so
# state cannot leak between them.
# ------------------------------------------
LAYOUT_ENV=dev
LAYOUT_SMTP_AUTH=false
LAYOUT_SMTP_HOST=""
LAYOUT_KEYCLOAK_URL=""
LAYOUT_CLIENT_REDIRECTS=""
LAYOUT_CLIENT_WEB_ORIGINS=""

# ------------------------------------------
# Case 5 (a) — a realm with no frontendUrl and an empty smtpServer gets both
# written and read back verified (rc 0), and the world state proves it stuck.
# ------------------------------------------
root="$(new_root)"
state="$root/state"
if build_layout "$root" "$state" all ""; then
	export KCS_SHIM_STATE_DIR="$state"
	export KCS_SHIM_INJECT_MATCH=""
	export KCS_SHIM_INJECT_KIND=""
	unset KCS_SHIM_REALM_DROP_UPDATE
	run_setup "$root"
	assert_rc "case 5 (a): an unconverged realm converges and exits 0" 0 "$SETUP_RC" "$SETUP_OUT"
	assert_contains "case 5 (a): frontendUrl written and verified" "frontendUrl verified: $ENV_KEYCLOAK_URL" "$SETUP_OUT"
	assert_contains "case 5 (a): smtpServer written and verified" "smtpServer verified: mailpit:1025" "$SETUP_OUT"
	assert_file_line "case 5 (a): realm state holds Keycloak's public base, not the app origin" "$ENV_KEYCLOAK_URL" "$state/realm_frontend_url"
	assert_not_contains "case 5 (a): the app origin is NOT the frontendUrl" "frontendUrl verified: $ENV_WEB_APP_URL" "$SETUP_OUT"
	assert_file_line "case 5 (a): realm state holds the SMTP host" "$(printf 'host\tmailpit')" "$state/realm_smtp"
	assert_line_count "case 5 (a): exactly one realm update is attempted" 1 "$state/updates.log"
fi

# ------------------------------------------
# Case 6 (b) — the realm update returns success but does not stick. The
# read-back must abort, naming the failed convergence, and never print success.
# Inverse of case 5.
# ------------------------------------------
root="$(new_root)"
state="$root/state"
LAYOUT_ENV=dev
LAYOUT_SMTP_AUTH=false
LAYOUT_CLIENT_REDIRECTS=""
LAYOUT_CLIENT_WEB_ORIGINS=""
if build_layout "$root" "$state" all ""; then
	export KCS_SHIM_STATE_DIR="$state"
	export KCS_SHIM_INJECT_MATCH=""
	export KCS_SHIM_INJECT_KIND=""
	export KCS_SHIM_REALM_DROP_UPDATE=1
	run_setup "$root"
	assert_rc "case 6 (b): an update that does not stick aborts with 1" 1 "$SETUP_RC" "$SETUP_OUT"
	assert_contains "case 6 (b): names the failed convergence" "does not match the declared configuration" "$SETUP_OUT"
	assert_not_contains "case 6 (b): never reports success" "Keycloak setup complete" "$SETUP_OUT"
	unset KCS_SHIM_REALM_DROP_UPDATE
fi

# ------------------------------------------
# Case 7 (c) — an existing client whose redirectUris lack the app origin gains
# it, while an unrelated pre-existing origin (redirect URI AND web origin) is
# preserved. Additive convergence, not replace.
# ------------------------------------------
root="$(new_root)"
state="$root/state"
LAYOUT_ENV=dev
LAYOUT_SMTP_AUTH=false
LAYOUT_CLIENT_REDIRECTS="http://legacy.example/*"
LAYOUT_CLIENT_WEB_ORIGINS="http://legacy.example"
if build_layout "$root" "$state" all ""; then
	export KCS_SHIM_STATE_DIR="$state"
	export KCS_SHIM_INJECT_MATCH=""
	export KCS_SHIM_INJECT_KIND=""
	unset KCS_SHIM_REALM_DROP_UPDATE
	run_setup "$root"
	assert_rc "case 7 (c): a client missing the app origin converges and exits 0" 0 "$SETUP_RC" "$SETUP_OUT"
	assert_contains "case 7 (c): reports the app origin present" "include $ENV_WEB_APP_URL" "$SETUP_OUT"
	assert_contains "case 7 (c): a client update was attempted" "clients/" "$(cat "$state/updates.log")"
	assert_file_line "case 7 (c): the app redirect URI was added" "${ENV_WEB_APP_URL}/*" "$state/client_redirect_uris"
	assert_file_line "case 7 (c): an unrelated pre-existing redirect URI is preserved" "http://legacy.example/*" "$state/client_redirect_uris"
	assert_file_line "case 7 (c): the app web origin was added" "$ENV_WEB_APP_URL" "$state/client_web_origins"
	assert_file_line "case 7 (c): an unrelated pre-existing web origin is preserved" "http://legacy.example" "$state/client_web_origins"
fi

# ------------------------------------------
# Case 8 (d) — SMTP_AUTH=true with no materialized smtp_password secret. The
# script must abort before any Keycloak write (no realm update, no create).
# ------------------------------------------
root="$(new_root)"
state="$root/state"
LAYOUT_ENV=dev
LAYOUT_SMTP_AUTH=true
LAYOUT_CLIENT_REDIRECTS=""
LAYOUT_CLIENT_WEB_ORIGINS=""
if build_layout "$root" "$state" all ""; then
	export KCS_SHIM_STATE_DIR="$state"
	export KCS_SHIM_INJECT_MATCH=""
	export KCS_SHIM_INJECT_KIND=""
	unset KCS_SHIM_REALM_DROP_UPDATE
	run_setup "$root"
	assert_rc "case 8 (d): SMTP_AUTH=true without the secret aborts with 1" 1 "$SETUP_RC" "$SETUP_OUT"
	assert_contains "case 8 (d): names the missing credential" "smtp_password" "$SETUP_OUT"
	assert_contains "case 8 (d): names the switch that requires it" "SMTP_AUTH=true" "$SETUP_OUT"
	assert_file_has_no_match "case 8 (d): no realm write before the refusal" "realms/" "$state/updates.log"
	assert_creates "case 8 (d): no create before the refusal" 0 "$state/creates.log"
fi

# ------------------------------------------
# Case 9 (e) — a non-dev environment whose SMTP_HOST resolves to the dev mail
# container's service name (the resolved default). It must refuse, naming the
# variable and the reason, before any Keycloak write.
# ------------------------------------------
root="$(new_root)"
state="$root/state"
LAYOUT_ENV=staging
LAYOUT_SMTP_AUTH=false
LAYOUT_CLIENT_REDIRECTS=""
LAYOUT_CLIENT_WEB_ORIGINS=""
if build_layout "$root" "$state" all ""; then
	export KCS_SHIM_STATE_DIR="$state"
	export KCS_SHIM_INJECT_MATCH=""
	export KCS_SHIM_INJECT_KIND=""
	unset KCS_SHIM_REALM_DROP_UPDATE
	run_setup_env "$root" staging
	assert_rc "case 9 (e): a non-dev env resolving to the dev mail host aborts with 1" 1 "$SETUP_RC" "$SETUP_OUT"
	assert_contains "case 9 (e): names SMTP_HOST" "SMTP_HOST" "$SETUP_OUT"
	assert_contains "case 9 (e): names the dev mail container" "mailpit" "$SETUP_OUT"
	assert_file_has_no_match "case 9 (e): no realm write before the refusal" "realms/" "$state/updates.log"
	assert_creates "case 9 (e): no create before the refusal" 0 "$state/creates.log"
fi

# ------------------------------------------
# Case 10 (c-inverse) — an already-registered app origin is left alone: no
# client update is attempted. Without it, case 7 would be satisfied by rewriting
# the client unconditionally.
# ------------------------------------------
root="$(new_root)"
state="$root/state"
LAYOUT_ENV=dev
LAYOUT_SMTP_AUTH=false
LAYOUT_CLIENT_REDIRECTS=""
LAYOUT_CLIENT_WEB_ORIGINS=""
if build_layout "$root" "$state" all ""; then
	export KCS_SHIM_STATE_DIR="$state"
	export KCS_SHIM_INJECT_MATCH=""
	export KCS_SHIM_INJECT_KIND=""
	unset KCS_SHIM_REALM_DROP_UPDATE
	run_setup "$root"
	assert_rc "case 10 (c-inverse): an already-registered origin exits 0" 0 "$SETUP_RC" "$SETUP_OUT"
	assert_file_has_no_match "case 10 (c-inverse): no client update when already present" "clients/" "$state/updates.log"
	assert_contains "case 10 (c-inverse): reports the client already converged" "already include" "$SETUP_OUT"
fi

# ------------------------------------------
# Case 11 (f) — a non-dev environment whose KEYCLOAK_URL still resolves to a
# localhost/dev default. frontendUrl is Keycloak's public base and it silently
# sets the realm issuer, so a localhost value must never survive outside dev: it
# must refuse, naming the variable and the reason, before any Keycloak write.
# SMTP_HOST is a real relay here, so the SMTP refusal cannot be what stops the
# run — the frontendUrl refusal is the only one in play.
# ------------------------------------------
root="$(new_root)"
state="$root/state"
LAYOUT_ENV=staging
LAYOUT_SMTP_AUTH=false
LAYOUT_SMTP_HOST="smtp.example.com"
LAYOUT_KEYCLOAK_URL="http://localhost:8181"
LAYOUT_CLIENT_REDIRECTS=""
LAYOUT_CLIENT_WEB_ORIGINS=""
if build_layout "$root" "$state" all ""; then
	export KCS_SHIM_STATE_DIR="$state"
	export KCS_SHIM_INJECT_MATCH=""
	export KCS_SHIM_INJECT_KIND=""
	unset KCS_SHIM_REALM_DROP_UPDATE
	run_setup_env "$root" staging
	assert_rc "case 11 (f): a non-dev env resolving to a localhost frontendUrl aborts with 1" 1 "$SETUP_RC" "$SETUP_OUT"
	assert_contains "case 11 (f): names the variable" "KEYCLOAK_URL" "$SETUP_OUT"
	assert_contains "case 11 (f): names the frontendUrl reason" "localhost/dev default" "$SETUP_OUT"
	assert_not_contains "case 11 (f): does not refuse the SMTP relay" "SMTP_HOST resolves to" "$SETUP_OUT"
	assert_file_has_no_match "case 11 (f): no realm write before the refusal" "realms/" "$state/updates.log"
	assert_creates "case 11 (f): no create before the refusal" 0 "$state/creates.log"
fi

# ------------------------------------------
# Case 12 (f-inverse) — the positive control for case 11: a non-dev environment
# with a real relay AND a real Keycloak public URL runs to completion. Without
# it, case 11 would be satisfied by aborting on every non-dev run.
# ------------------------------------------
root="$(new_root)"
state="$root/state"
LAYOUT_ENV=staging
LAYOUT_SMTP_AUTH=false
LAYOUT_SMTP_HOST="smtp.example.com"
LAYOUT_KEYCLOAK_URL="https://auth.example.com"
LAYOUT_CLIENT_REDIRECTS=""
LAYOUT_CLIENT_WEB_ORIGINS=""
if build_layout "$root" "$state" all ""; then
	export KCS_SHIM_STATE_DIR="$state"
	export KCS_SHIM_INJECT_MATCH=""
	export KCS_SHIM_INJECT_KIND=""
	unset KCS_SHIM_REALM_DROP_UPDATE
	run_setup_env "$root" staging
	assert_rc "case 12 (f-inverse): a non-dev env with a real frontendUrl exits 0" 0 "$SETUP_RC" "$SETUP_OUT"
	assert_contains "case 12 (f-inverse): frontendUrl written and verified" "frontendUrl verified: https://auth.example.com" "$SETUP_OUT"
	assert_file_line "case 12 (f-inverse): realm state holds the public Keycloak URL" "https://auth.example.com" "$state/realm_frontend_url"
fi

# ------------------------------------------
# Case 13 (g) — KEYCLOAK_URL is absent from the environment file, so the script
# must fall back to the KEYCLOAK_PORT default measured against .env.template
# (KEYCLOAK_PORT=8181 and KEYCLOAK_URL=http://localhost:8181). Pins the fallback
# so it cannot silently drift away from the issuer the stack validates.
# ------------------------------------------
root="$(new_root)"
state="$root/state"
LAYOUT_ENV=dev
LAYOUT_SMTP_AUTH=false
LAYOUT_SMTP_HOST=""
LAYOUT_KEYCLOAK_URL=""
LAYOUT_OMIT_KEYCLOAK_URL=1
LAYOUT_CLIENT_REDIRECTS=""
LAYOUT_CLIENT_WEB_ORIGINS=""
if build_layout "$root" "$state" all ""; then
	export KCS_SHIM_STATE_DIR="$state"
	export KCS_SHIM_INJECT_MATCH=""
	export KCS_SHIM_INJECT_KIND=""
	unset KCS_SHIM_REALM_DROP_UPDATE
	run_setup "$root"
	assert_rc "case 13 (g): an absent KEYCLOAK_URL uses the measured fallback and exits 0" 0 "$SETUP_RC" "$SETUP_OUT"
	assert_contains "case 13 (g): falls back to http://localhost:8181" "frontendUrl verified: http://localhost:8181" "$SETUP_OUT"
fi
unset LAYOUT_OMIT_KEYCLOAK_URL

# ------------------------------------------
# Summary
# ------------------------------------------
echo "------------------------------------------"
printf 'keycloak-setup: %s passed, %s failed\n' "$PASS_COUNT" "$FAIL_COUNT"
if [ "$FAIL_COUNT" -ne 0 ]; then
	exit 1
fi
exit 0
