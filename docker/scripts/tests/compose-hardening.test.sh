#!/bin/bash
# ============================================
# LifeControl - container hardening tests (issue #897, S4/S5)
# ============================================
# S4 says "`docker compose config` reports `cap_drop: [ALL]`, `security_opt:
# [no-new-privileges:true]` and, for every image that tolerates it, `read_only: true` with a `tmpfs`
# for `/tmp`". The middle clause is not falsifiable: no command answers "tolerates it". What *is*
# falsifiable is the per-service verdict this stack arrived at by measuring each image, so that is
# what this suite freezes.
#
# The table below is the answer to four questions per service, read from `docker compose config`
# for the three file combinations this repository runs (base; base + the dev override; base + the
# prod override):
#
#   1. `cap_drop` -- `ALL` everywhere. The two `cap_add` sets (both Postgres, and Redis) are the
#      smallest measured sets that let those entrypoints chown their data dir and drop to a service
#      user; `cap_drop: ALL` with nothing added is a restart loop on them, measured:
#        `chmod: changing permissions of '/var/run/postgresql': Operation not permitted`
#        `chown: changing ownership of '/var/lib/postgresql/18/docker': Operation not permitted`
#      and, worse, on Redis it silently REVERSES the hardening: the image entrypoint guards its
#      privilege drop with `has_cap setuid && has_cap setgid`, so with an empty capability set
#      redis-server keeps running as root (measured with `docker top`).
#
#   2. `security_opt` -- `no-new-privileges:true` everywhere, no exception.
#
#   3. `read_only` -- true where the image was measured to start with a read-only root, and false
#      where it was measured NOT to, never a blanket. The two exceptions are the two services whose
#      images WRITE their own tree at start:
#        keycloak: `kc.sh start-dev` re-augments the server image and writes
#                  `/opt/keycloak/lib/quarkus/transformed-bytecode.jar`
#                  (`java.nio.file.FileSystemException: ... Read-only file system`).
#        web-app:  its entrypoint generates `/usr/share/nginx/html/assets/env.js` and `sed -i`s
#                  `index.html` inside the document root before nginx starts, and a tmpfs over that
#                  directory would hide the built SPA it has to patch.
#
#   4. `tmpfs` -- exactly the paths an image was measured to write outside its bind mounts, so a
#      path can neither appear nor disappear without this file changing.
#
# S5 is the second half: `prometheus` and `tempo` no longer declare `user: "0:0"` (the base file
# pinned root for both and the prod override pinned nothing, so the two environments disagreed), the
# numeric uid is the one the image itself declares, and `setup-env.sh` creates their data directories
# with it -- the same way it already did for Loki's 10001:10001. A `chown` that cannot be applied is
# now FATAL in that script instead of a warning it prints and walks past, because a directory left
# owned by someone else is a container in a restart loop and a health check that says nothing useful.
#
# The compose half needs the `docker compose` CLI but no daemon (config rendering only). The
# setup-env half needs no Docker at all: the ownership helper is extracted verbatim and driven
# against a throwaway tree under `mktemp -d`.
#
# Run: ./docker/scripts/tests/compose-hardening.test.sh
# Exits 0 only when every check passes.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
DOCKER_DIR="$REPO_ROOT/docker"
SETUP_ENV_SH="$DOCKER_DIR/scripts/setup-env.sh"

PASS_COUNT=0
FAIL_COUNT=0

pass() {
	printf 'PASS: %s\n' "$1"
	PASS_COUNT=$((PASS_COUNT + 1))
}

fail() {
	printf 'FAIL: %s\n' "$1"
	FAIL_COUNT=$((FAIL_COUNT + 1))
}

TMP_BASE="$(mktemp -d)"
# shellcheck disable=SC2329  # invoked by the EXIT trap below, which shellcheck cannot trace
cleanup() {
	rm -rf "$TMP_BASE"
}
trap cleanup EXIT

# ------------------------------------------
# 1. The freeze. One row per file combination and service:
#
#   combination | service | cap_drop | cap_add | security_opt | read_only | tmpfs | user
#
# `-` means "the key is absent", which for `read_only` is false and for `user` is "the image
# default". Lists are written sorted and comma-separated exactly as the normalizer below renders
# them. `dev` is the only combination that defines `mailpit` (it lives in the dev override, never in
# the base file or in prod), and everything else is identical across the three: prod redeclares the
# services for its own ports, limits and volumes, and inherits these keys from the base file.
# ------------------------------------------
EXPECTED_TABLE="base|api-gateway|ALL|-|no-new-privileges:true|true|/tmp|-
base|grafana|ALL|-|no-new-privileges:true|true|/tmp|1000:1000
base|keycloak|ALL|-|no-new-privileges:true|false|-|-
base|keycloak-postgres|ALL|CHOWN,DAC_OVERRIDE,SETGID,SETUID|no-new-privileges:true|true|/tmp,/var/run/postgresql|-
base|lifecontrol-api|ALL|-|no-new-privileges:true|true|/tmp|-
base|lifecontrol-postgres|ALL|CHOWN,DAC_OVERRIDE,SETGID,SETUID|no-new-privileges:true|true|/tmp,/var/run/postgresql|-
base|loki|ALL|-|no-new-privileges:true|true|/tmp|-
base|postgres-exporter-keycloak|ALL|-|no-new-privileges:true|true|-|-
base|postgres-exporter-lifecontrol|ALL|-|no-new-privileges:true|true|-|-
base|prometheus|ALL|-|no-new-privileges:true|true|-|65534:65534
base|redis|ALL|CHOWN,DAC_OVERRIDE,FOWNER,SETGID,SETUID|no-new-privileges:true|true|/tmp|-
base|redis-exporter|ALL|-|no-new-privileges:true|true|-|-
base|tempo|ALL|-|no-new-privileges:true|true|/tmp|10001:10001
base|web-app|ALL|-|no-new-privileges:true|false|-|-
dev|api-gateway|ALL|-|no-new-privileges:true|true|/tmp|-
dev|grafana|ALL|-|no-new-privileges:true|true|/tmp|1000:1000
dev|keycloak|ALL|-|no-new-privileges:true|false|-|-
dev|keycloak-postgres|ALL|CHOWN,DAC_OVERRIDE,SETGID,SETUID|no-new-privileges:true|true|/tmp,/var/run/postgresql|-
dev|lifecontrol-api|ALL|-|no-new-privileges:true|true|/tmp|-
dev|lifecontrol-postgres|ALL|CHOWN,DAC_OVERRIDE,SETGID,SETUID|no-new-privileges:true|true|/tmp,/var/run/postgresql|-
dev|loki|ALL|-|no-new-privileges:true|true|/tmp|-
dev|mailpit|ALL|-|no-new-privileges:true|true|/tmp|-
dev|postgres-exporter-keycloak|ALL|-|no-new-privileges:true|true|-|-
dev|postgres-exporter-lifecontrol|ALL|-|no-new-privileges:true|true|-|-
dev|prometheus|ALL|-|no-new-privileges:true|true|-|65534:65534
dev|redis|ALL|CHOWN,DAC_OVERRIDE,FOWNER,SETGID,SETUID|no-new-privileges:true|true|/tmp|-
dev|redis-exporter|ALL|-|no-new-privileges:true|true|-|-
dev|tempo|ALL|-|no-new-privileges:true|true|/tmp|10001:10001
dev|web-app|ALL|-|no-new-privileges:true|false|-|-
prod|api-gateway|ALL|-|no-new-privileges:true|true|/tmp|-
prod|grafana|ALL|-|no-new-privileges:true|true|/tmp|1000:1000
prod|keycloak|ALL|-|no-new-privileges:true|false|-|-
prod|keycloak-postgres|ALL|CHOWN,DAC_OVERRIDE,SETGID,SETUID|no-new-privileges:true|true|/tmp,/var/run/postgresql|-
prod|lifecontrol-api|ALL|-|no-new-privileges:true|true|/tmp|-
prod|lifecontrol-postgres|ALL|CHOWN,DAC_OVERRIDE,SETGID,SETUID|no-new-privileges:true|true|/tmp,/var/run/postgresql|-
prod|loki|ALL|-|no-new-privileges:true|true|/tmp|-
prod|postgres-exporter-keycloak|ALL|-|no-new-privileges:true|true|-|-
prod|postgres-exporter-lifecontrol|ALL|-|no-new-privileges:true|true|-|-
prod|prometheus|ALL|-|no-new-privileges:true|true|-|65534:65534
prod|redis|ALL|CHOWN,DAC_OVERRIDE,FOWNER,SETGID,SETUID|no-new-privileges:true|true|/tmp|-
prod|redis-exporter|ALL|-|no-new-privileges:true|true|-|-
prod|tempo|ALL|-|no-new-privileges:true|true|/tmp|10001:10001
prod|web-app|ALL|-|no-new-privileges:true|false|-|-"

# The env file a combination interpolates with: `base` interpolates with the dev file, exactly as the
# three verification commands for this unit do. `.env.dev` and `.env.prod` are materialized once per
# machine and gitignored, so a checkout (and CI) usually has neither; the tracked template carries a
# value for every key the compose files require, and none of the six keys this suite compares comes
# from it.
env_file_for() {
	local env_name="$1"
	local candidate
	[ "$env_name" = "base" ] && env_name="dev"
	for candidate in "$DOCKER_DIR/.env.$env_name" "$DOCKER_DIR/.env.template"; do
		if [ -f "$candidate" ]; then
			printf '%s\n' "$candidate"
			return 0
		fi
	done
	return 1
}

# combo_files <base|dev|prod>: the compose arguments of that combination, one per line.
combo_files() {
	case "$1" in
	base) printf '%s\n' docker-compose.yml ;;
	dev) printf '%s\n' docker-compose.yml docker-compose.override.yml ;;
	prod) printf '%s\n' docker-compose.yml docker-compose.prod.yml ;;
	*) return 1 ;;
	esac
}

# normalize_services: read `docker compose config --format json` on stdin and print one
# TAB-separated row per service in the shape of the table above (without the combination column).
normalize_services() {
	python3 -c '
import json
import sys

data = json.load(sys.stdin)


def render(value):
    if value is None:
        return "-"
    if isinstance(value, dict):
        items = [str(key) for key in value]
    elif isinstance(value, list):
        items = [str(item) for item in value]
    else:
        return str(value)
    items.sort()
    return ",".join(items) if items else "-"


for name in sorted(data.get("services", {})):
    service = data["services"][name]
    print("\t".join([
        name,
        render(service.get("cap_drop")),
        render(service.get("cap_add")),
        render(service.get("security_opt")),
        "true" if service.get("read_only") else "false",
        render(service.get("tmpfs")),
        render(service.get("user")),
    ]))
'
}

compose_available=0
if docker compose version >/dev/null 2>&1; then
	compose_available=1
	pass "the docker compose CLI is available, so the three file combinations can be rendered"
else
	fail "the docker compose CLI is missing: the per-service hardening table cannot be read for any combination"
fi

check_combination() {
	local combo="$1"
	local env_file files file rendered row service expected found=0 mismatches=0
	local -a args=()

	if [ "$compose_available" -eq 0 ]; then
		return 0
	fi

	env_file="$(env_file_for "$combo")" || {
		fail "$combo: no env file to interpolate with (docker/.env.$combo and docker/.env.template are both missing)"
		return 0
	}

	mapfile -t files < <(combo_files "$combo")
	for file in "${files[@]}"; do
		args+=(-f "$DOCKER_DIR/$file")
	done

	if ! rendered="$(cd "$DOCKER_DIR" && docker compose --env-file "$env_file" "${args[@]}" config --format json 2>&1 | normalize_services)"; then
		fail "$combo: \`docker compose config\` failed, so no service could be checked: $(printf '%s' "$rendered" | head -2)"
		return 0
	fi

	while IFS=$'\t' read -r service e1 e2 e3 e4 e5 e6; do
		[ -n "$service" ] || continue
		found=1
		expected="$e1"$'\t'"$e2"$'\t'"$e3"$'\t'"$e4"$'\t'"$e5"$'\t'"$e6"
		row="$(printf '%s\n' "$rendered" | awk -F'\t' -v svc="$service" '$1 == svc')"
		if [ -z "$row" ]; then
			fail "$combo: service '$service' is not in the rendered config; the table is out of date"
			mismatches=$((mismatches + 1))
			continue
		fi
		if [ "$(printf '%s' "$row" | cut -f2-)" != "$expected" ]; then
			fail "$combo: service '$service' does not match the frozen hardening table"
			printf '  expected: %s\n' "$expected"
			printf '  actual:   %s\n' "$(printf '%s' "$row" | cut -f2-)"
			mismatches=$((mismatches + 1))
		fi
	done < <(printf '%s\n' "$EXPECTED_TABLE" | awk -F'|' -v combo="$combo" \
		'$1 == combo { printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\n", $2, $3, $4, $5, $6, $7, $8 }')

	if [ "$found" -eq 0 ]; then
		fail "$combo: the frozen table has no row for this combination"
		return 0
	fi

	# A service that appears in the rendered config but not in the table is the silent drift this
	# suite exists to prevent: a new service (or a renamed one) would otherwise be checked by nobody.
	while IFS=$'\t' read -r service _rest; do
		[ -n "$service" ] || continue
		if ! printf '%s\n' "$EXPECTED_TABLE" | awk -F'|' -v combo="$combo" -v svc="$service" \
			'$1 == combo && $2 == svc { found = 1 } END { exit !found }'; then
			fail "$combo: service '$service' has no row in the frozen table; add its measured values to this file"
			mismatches=$((mismatches + 1))
		fi
	done <<<"$rendered"

	if [ "$mismatches" -eq 0 ]; then
		pass "$combo: every one of the $(printf '%s\n' "$rendered" | grep -c .) services matches the frozen capabilities, no-new-privileges, read-only and tmpfs table"
	fi
}

for combo in base dev prod; do
	check_combination "$combo"
done

# ------------------------------------------
# 2. S5, read straight out of the rendered config: the two services that used to run as root no
#    longer do, and the uid they run as is the image's own default (measured with `docker image
#    inspect -f '{{.Config.User}}'`: `nobody` for prometheus, which is 65534, and `10001:10001` for
#    tempo). Naming them here as their own assertions keeps the failure message pointing at S5 when
#    what broke is the uid rather than a capability.
# ------------------------------------------
if [ "$compose_available" -eq 1 ]; then
	base_env="$(env_file_for base)"
	base_json="$(cd "$DOCKER_DIR" && docker compose --env-file "$base_env" -f docker-compose.yml config --format json 2>/dev/null)"

	for pair in "prometheus:65534:65534" "tempo:10001:10001"; do
		svc="${pair%%:*}"
		want="${pair#*:}"
		got="$(printf '%s' "$base_json" | python3 -c "
import json
import sys
data = json.load(sys.stdin)
print((data.get('services', {}).get('$svc') or {}).get('user'))
")"
		if [ "$got" = "$want" ]; then
			pass "$svc runs as the image's own numeric uid ($want) instead of root"
		else
			fail "$svc resolves to user '$got'; S5 requires '$want', the uid the pinned image declares"
		fi
	done
fi

# ------------------------------------------
# 3. No compose file pins a service to root again. This is the grep that would have caught the
#    `user: "0:0"` this unit removed: it matches any spelling of the root uid in any of the three
#    files, including one reintroduced in a file the table does not render on its own.
# ------------------------------------------
root_pins=0
for compose in "$DOCKER_DIR"/docker-compose*.yml; do
	[ -f "$compose" ] || continue
	hits="$(grep -nE "^[[:space:]]+user:[[:space:]]*[\"']?(0:0|root|0)[\"']?[[:space:]]*$" "$compose" | grep -v -E '^[0-9]+:[[:space:]]*#' | wc -l)"
	if [ "$hits" -gt 0 ]; then
		fail "$(basename "$compose"): $hits service(s) still pinned to the root uid: $(grep -nE "^[[:space:]]+user:" "$compose" | head -3 | tr '\n' ' ')"
		root_pins=$((root_pins + hits))
	fi
done
if [ "$root_pins" -eq 0 ]; then
	pass "no compose file pins a service to the root uid"
fi

# ------------------------------------------
# 4. setup-env.sh owns the data directories as the uid each image runs as. The four calls are named
#    here beside the uids, because a chown to the wrong number and a `user:` that disagrees with it
#    are two ways of producing the same restart loop, and neither is visible from the compose file.
# ------------------------------------------
check_setup_env_owner() {
	local service="$1"
	local uid="$2"
	local pattern="require_dir_owner \"\$vol_path/$service/data\" $uid"

	if ! grep -qF -- "$pattern" "$SETUP_ENV_SH"; then
		fail "setup-env.sh does not own \$VOLUMES_ROOT/$service/data as $uid (expected the call: $pattern)"
		return 0
	fi
	pass "setup-env.sh owns \$VOLUMES_ROOT/$service/data as the uid that image runs as ($uid)"
}

if [ ! -f "$SETUP_ENV_SH" ]; then
	fail "missing docker/scripts/setup-env.sh"
else
	check_setup_env_owner loki 10001:10001
	check_setup_env_owner prometheus 65534:65534
	check_setup_env_owner tempo 10001:10001
	check_setup_env_owner grafana 1000:1000

	# The warning-and-continue shape is what made the Loki restart loop invisible (AGENTS.md: a
	# directory left owned by someone else was reported by nobody). A `chown` that cannot be applied
	# must stop the script, so no such fallback may remain.
	chown_fallbacks="$(grep -c 'Could not chown' "$SETUP_ENV_SH" || true)"
	if [ "$chown_fallbacks" -eq 0 ]; then
		pass "setup-env.sh has no warning-and-continue chown left ($chown_fallbacks 'Could not chown' fallbacks)"
	else
		fail "setup-env.sh still contains $chown_fallbacks 'Could not chown' fallback(s): a data directory left owned by another uid is a restart loop nobody reports"
	fi
fi

# ------------------------------------------
# 5. The ownership helper itself, extracted verbatim from setup-env.sh and driven against throwaway
#    directories. Asserting on the text would not prove the failure path exits; these three cases
#    do.
# ------------------------------------------
helper="$TMP_BASE/require_dir_owner.sh"
if ! awk '
	/^require_dir_owner\(\) \{/ { in_fn = 1 }
	in_fn { print }
	in_fn && /^\}/ { exit }
' "$SETUP_ENV_SH" >"$helper" || [ ! -s "$helper" ]; then
	fail "could not extract require_dir_owner from setup-env.sh"
else
	driver="$TMP_BASE/driver.sh"
	{
		printf '%s\n' '#!/bin/bash'
		printf '%s\n' 'set -u'
		printf '%s\n' 'print_success() { printf "OK: %s\n" "$1"; }'
		printf '%s\n' 'print_error() { printf "ERROR: %s\n" "$1" >&2; }'
		cat "$helper"
		printf '%s\n' 'require_dir_owner "$1" "$2"'
	} >"$driver"

	current_uid="$(id -u):$(id -g)"

	# (a) already correct: a success, so a non-root operator can re-run the script after following
	#     the hint the failing case prints.
	mkdir -p "$TMP_BASE/owned-ok"
	out="$(bash "$driver" "$TMP_BASE/owned-ok" "$current_uid" 2>&1)"
	rc=$?
	if [ "$rc" -eq 0 ] && printf '%s' "$out" | grep -q 'already owned'; then
		pass "the ownership helper succeeds without chown when the directory already has the target owner"
	else
		fail "the ownership helper did not treat an already-correct owner as success (rc=$rc, output: $(printf '%s' "$out" | head -2))"
	fi

	# (b) a missing directory is created, because the call must work on a first run.
	out="$(bash "$driver" "$TMP_BASE/created" "$current_uid" 2>&1)"
	rc=$?
	if [ "$rc" -eq 0 ] && [ -d "$TMP_BASE/created" ]; then
		pass "the ownership helper creates a missing data directory"
	else
		fail "the ownership helper did not create a missing data directory (rc=$rc, output: $(printf '%s' "$out" | head -2))"
	fi

	# (c) a chown it cannot apply must abort, naming the directory and the command to run. Uid 10001
	#     is unreachable for a non-root user, which is the situation the Loki incident was made of.
	if [ "$(id -u)" -eq 0 ]; then
		pass "the failing-chown case is not exercisable as root (chown would succeed)"
	else
		mkdir -p "$TMP_BASE/owned-bad"
		out="$(bash "$driver" "$TMP_BASE/owned-bad" 10001:10001 2>&1)"
		rc=$?
		if [ "$rc" -eq 0 ]; then
			fail "the ownership helper continued after a chown it could not apply (rc=0): the container restart-loops and nothing says why"
		elif printf '%s' "$out" | grep -qF "$TMP_BASE/owned-bad" && printf '%s' "$out" | grep -qF 'sudo chown -R 10001:10001'; then
			pass "the ownership helper aborts when it cannot chown, naming the directory and the command an operator must run"
		else
			fail "the ownership helper aborted without naming the directory and the command (output: $(printf '%s' "$out" | head -3))"
		fi

		# (d) The order matters as much as the command. The first version of this helper said "run
		#     this once, then re-run the script", and that is exactly how the stack entered the restart
		#     loop anyway: the container still running writes here with its old uid until it is
		#     replaced, so a chown against a live stack leaves fresh foreign-owned files behind. The
		#     abort has to name the stop, then the chown, then the start.
		if printf '%s' "$out" | grep -qF 'deploy.sh <env> stop' && printf '%s' "$out" | grep -qF 'deploy.sh <env> up'; then
			pass "the abort names the order that works: stop the stack, chown, then start it"
		else
			fail "the abort does not name the stop-then-start order, so an operator chowns a live stack and the restart loop survives (output: $(printf '%s' "$out" | head -4))"
		fi
	fi

	# (e) The check reads the directory's contents, not only its own owner. This one is static on
	#     purpose: fabricating an entry owned by a second uid needs root, so the failure cannot be
	#     driven as an ordinary user — and this is the shape that was measured to fail in production,
	#     a data directory already owned by the target uid with 57 root-owned blocks inside it.
	if grep -qF 'find "$dir" ! -user "$uid"' "$helper"; then
		pass "the helper inspects the contents of the directory, not only its own owner"
	else
		fail "the helper only compares the directory's own owner: files a previous uid wrote inside it go unseen, which is the restart loop behind the Tempo incident"
	fi
fi

printf '\n%d passed, %d failed\n' "$PASS_COUNT" "$FAIL_COUNT"
[ "$FAIL_COUNT" -eq 0 ]
