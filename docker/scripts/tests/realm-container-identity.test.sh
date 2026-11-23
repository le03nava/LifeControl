#!/bin/bash
# ============================================
# LifeControl - the keycloak container a project actually runs
# ============================================
# Both realm scripts used to address the keycloak container by a NAME THEY BUILT:
# `CONTAINER="${COMPOSE_PROJECT_NAME}-keycloak"` in keycloak-setup.sh, and a `compose ps --format` health
# column in deploy.sh. That name only exists in dev. Measured in the tree (and pinned by case 1 below):
#
#   docker/docker-compose.yml           keycloak: no `container_name` (docker-compose.yml:161-232)
#   docker/docker-compose.override.yml  container_name: ${COMPOSE_PROJECT_NAME:-lifecontrol}-keycloak  (dev only)
#   docker/docker-compose.prod.yml      keycloak: deploy.replicas: 2, still no `container_name`
#
# so in staging and prod compose names the container `<project>-keycloak-1` (and `-2` in prod), and every
# `docker exec` targeted something that does not exist: `deploy.sh <env> start` would abort at the realm
# step and `deploy.sh <env> health` would be permanently red there. The health read had the same shape of
# bug: with two replicas the `{{.Health}}` column prints TWO lines, and a shell that strips newlines turns
# them into `healthyhealthy`, which matches no state and waits out the whole bound on a healthy stack.
#
# compose is the only thing that knows how a project names what it runs, so both scripts ask it for the id
# (`ps -q keycloak`) and read the health per id (`docker inspect -f '{{.State.Health.Status}}' <id>`). This
# suite freezes that, offline: a `docker` shim answers the compose question and records every exec target,
# so both halves are observable --
#
#   * the resolution does not depend on `container_name`: each of the three environment shapes a project can
#     take (base, base+override, base+prod) resolves through the compose file stack that shape loads, and
#     the exec target is always the id compose answered -- never a name built from COMPOSE_PROJECT_NAME;
#   * the wait counts containers, not lines: two healthy replicas pass, a project with none is diagnosed
#     with a message that names the cause instead of timing out, and "created but not running" is told
#     apart from "never created" because the operator fixes those two differently.
#
# No Docker daemon, no Keycloak, no network: every answer comes from the shim.
#
# Run: ./docker/scripts/tests/realm-container-identity.test.sh
# Exits 0 only when every case passes.

# No `set -e`: the cases assert non-zero exit codes, so every status is captured explicitly. `set -u`
# keeps the harness honest about typos.
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DOCKER_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
DEPLOY_SH="$DOCKER_DIR/deploy.sh"
COMMON="$DOCKER_DIR/_common.sh"
SETUP_SH="$DOCKER_DIR/keycloak-setup.sh"
# deploy.sh and keycloak-setup.sh live in docker/scripts; the compose files live one level up, in docker/.
COMPOSE_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
COMPOSE_BASE="$COMPOSE_DIR/docker-compose.yml"
COMPOSE_OVERRIDE="$COMPOSE_DIR/docker-compose.override.yml"
COMPOSE_PROD="$COMPOSE_DIR/docker-compose.prod.yml"

PASS_COUNT=0
FAIL_COUNT=0

# The compose project the synthetic env file declares. Only its shape matters: nothing resolves the
# container by name any more, and the ids the shim answers are deliberately NOT derived from it -- a name
# built from the project and an id compose answered must never be confusable.
TREE_PROJECT="rci-offline-test"
# The id compose answers for the keycloak service.
KC_ID="c1d2e3f4a5b6"
# A second replica id, so "two healthy replicas" is two distinct containers rather than one id twice.
KC_ID_2="f6a5b4c3d2e1"
# A container that exists but is not running (compose names it `<project>-keycloak-1` outside dev).
KC_ID_STOPPED="9a8b7c6d5e4f"

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

new_root() {
	mktemp -d "$TMP_BASE/case.XXXXXX"
}

count_matching() {
	local file="$1" needle="$2" count
	count="$(grep -cF -- "$needle" "$file" 2>/dev/null || true)"
	printf '%s' "${count:-0}"
}

assert_rc() {
	local name="$1" expected="$2" actual="$3" output="$4"
	if [ "$actual" -eq "$expected" ]; then
		pass "$name (rc=$actual)"
	else
		fail "$name (expected rc=$expected, got rc=$actual)"
		printf '%s\n' "$output" | sed 's/^/        /'
	fi
}

assert_contains() {
	local name="$1" needle="$2" haystack="$3"
	if printf '%s' "$haystack" | grep -qF -- "$needle"; then
		pass "$name"
	else
		fail "$name (missing output: $needle)"
		printf '%s\n' "$haystack" | sed 's/^/        /'
	fi
}

assert_not_contains() {
	local name="$1" needle="$2" haystack="$3"
	if printf '%s' "$haystack" | grep -qF -- "$needle"; then
		fail "$name (unexpected output: $needle)"
		printf '%s\n' "$haystack" | sed 's/^/        /'
	else
		pass "$name"
	fi
}

# assert_count <name> <expected> <needle> <file>
# A missing file counts as zero, so "the call never happened" cannot be satisfied by evidence that never
# appeared.
assert_count() {
	local name="$1" expected="$2" needle="$3" file="$4" actual
	actual="$(count_matching "$file" "$needle")"
	if [ "$actual" = "$expected" ]; then
		pass "$name ($actual)"
	else
		fail "$name (expected $expected, got $actual)"
		if [ -f "$file" ]; then
			sed 's/^/        /' "$file"
		fi
	fi
}

# assert_file_line <name> <line> <file> — passes only when the file holds <line> exactly.
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

# assert_exec_targets_are <name> <expected id> <calls.log>
# The load-bearing assertion of the resolution: WHAT the script exec'd into. Every exec target must be the
# id compose answered -- a name built from COMPOSE_PROJECT_NAME is the value compose does not run outside
# dev, and keeping it out is what this suite exists for.
assert_exec_targets_are() {
	local name="$1" expected="$2" file="$3" targets target bad=""
	targets="$(sed -n 's/^exec //p' "$file" 2>/dev/null)"
	if [ -z "$targets" ]; then
		fail "$name (no exec was recorded at all)"
		return 0
	fi
	while IFS= read -r target; do
		[ -n "$target" ] || continue
		if [ "$target" != "$expected" ]; then
			bad="${bad:+$bad, }$target"
		fi
	done <<<"$targets"
	if [ -z "$bad" ]; then
		pass "$name (every exec target is $expected)"
	else
		fail "$name (exec targets other than $expected: $bad)"
		sed 's/^/        /' "$file"
	fi
}

# write_deploy_shim <path>
# Stands in for `docker` when the subject is deploy.sh. It models what compose knows about the project
# rather than the one shape a caller happens to read: the same fixture answers `ps -q` (ids), `ps -aq`
# (stopped containers included), the `{{.Health}}` column (one line per container, which is the shape the
# old wait collapsed) and `docker inspect` (the health of ONE id, read one container at a time).
write_deploy_shim() {
	cat >"$1" <<'STUB'
#!/bin/bash
# ============================================
# Stand-in `docker` for realm-container-identity.test.sh (deploy.sh side)
# ============================================
# Fixtures, from the case environment:
#   RCI_KC_REPLICAS     0 = compose runs no keycloak container, 1 (default) = one, 2 = two
#   RCI_KC_IDS_1/2      the ids of those replicas, in the order compose prints them
#   RCI_KC_STOPPED      non-empty = a keycloak container exists but is not running
#   RCI_KC_HEALTH       the health a running container reports (default healthy)
#   RCI_KC_UNHEALTHY_IDS  ids that report that health instead of healthy, space-separated
set -u

STATE="${RCI_STATE:?RCI_STATE must be set}"
mkdir -p "$STATE"

if [ "${1:-}" = "info" ]; then
	exit 0
fi

if [ "${1:-}" = "inspect" ]; then
	target="${!#}"
	printf 'inspect %s\n' "$target" >>"$STATE/calls.log"
	state="${RCI_KC_HEALTH:-healthy}"
	case " ${RCI_KC_UNHEALTHY_IDS:-} " in
	*" $target "*) state="${RCI_KC_HEALTH:-unhealthy}" ;;
	esac
	printf '%s\n' "$state"
	exit 0
fi

if [ "${1:-}" != "compose" ]; then
	printf 'deploy shim: unsupported docker invocation: %s\n' "$*" >&2
	exit 64
fi

shift
while [ "$#" -gt 0 ]; do
	case "$1" in
	-f | --env-file) shift 2 ;;
	*) break ;;
	esac
done

sub="${1:-}"
if [ "$#" -gt 0 ]; then
	shift
fi

case "$sub" in
up)
	target="all"
	for arg in "$@"; do
		case "$arg" in
		-d) continue ;;
		*) target="$arg" ;;
		esac
	done
	printf 'compose up %s\n' "$target" >>"$STATE/calls.log"
	exit 0
	;;
ps)
	printf 'compose ps %s\n' "$*" >>"$STATE/calls.log"
	case " $* " in
	*" --services "*)
		printf '%s\n' keycloak lifecontrol-api
		;;
	*"{{.Service}} {{.State}} {{.Health}}"*)
		printf 'keycloak running %s\n' "${RCI_KC_HEALTH:-healthy}"
		printf 'lifecontrol-api running healthy\n'
		;;
	*" -aq "* | *" -a -q "*)
		# Stopped containers included: the question that separates "never created" from "created but
		# not running".
		if [ -n "${RCI_KC_STOPPED:-}" ]; then
			printf '%s\n' "$RCI_KC_STOPPED"
		fi
		;;
	*" -q "*)
		# The ids of the running containers, one per line. Zero is a real answer, not an error.
		case "${RCI_KC_REPLICAS:-1}" in
		0) : ;;
		2) printf '%s\n' "$RCI_KC_IDS_1" "$RCI_KC_IDS_2" ;;
		*) printf '%s\n' "$RCI_KC_IDS_1" ;;
		esac
		;;
	*"{{.Health}}"*)
		# The column the old wait read: one line per container. Two replicas are two lines, which is the
		# shape a `tr -d '\n'` collapsed into `healthyhealthy`.
		case "${RCI_KC_REPLICAS:-1}" in
		0) : ;;
		2)
			printf '%s\n' "${RCI_KC_HEALTH:-healthy}"
			printf '%s\n' "${RCI_KC_HEALTH:-healthy}"
			;;
		*) printf '%s\n' "${RCI_KC_HEALTH:-healthy}" ;;
		esac
		;;
	*)
		printf '%s\n' "keycloak running ${RCI_KC_HEALTH:-healthy}" "lifecontrol-api running healthy"
		;;
	esac
	exit 0
	;;
exec | logs | down | build | *)
	exit 0
	;;
esac
STUB
}

# write_setup_shim <path>
# Stands in for `docker` when the subject is keycloak-setup.sh, which resolves through compose AND execs.
# It answers the resolution, the in-container login and the three reads the narrow mode makes, records every
# exec TARGET (the assertion this suite is about), and refuses anything it was not asked to model so the
# shim cannot silently absorb a call it does not answer.
write_setup_shim() {
	cat >"$1" <<'STUB'
#!/bin/bash
# ============================================
# Stand-in `docker` for realm-container-identity.test.sh (keycloak-setup.sh side)
# ============================================
# Fixtures, from the case environment:
#   RCI_KC_REPLICAS  0 = compose runs no keycloak container, 1 (default) = one
#   RCI_KC_ID        the id compose answers
#   RCI_KC_STOPPED   non-empty = a keycloak container exists but is not running
#
# The realm world is deliberately minimal -- the public client exists and has NO protocol mapper -- so the
# narrow mode reaches its verdict quickly and the run stays about the container identity.
set -u

STATE="${RCI_STATE:?RCI_STATE must be set}"
mkdir -p "$STATE"

refuse() {
	printf 'setup shim: unsupported invocation: %s\n' "$*" >&2
	exit 64
}

if [ "${1:-}" = "compose" ]; then
	printf 'compose %s\n' "${*:2}" >>"$STATE/calls.log"
	case " ${*:2} " in
	*" ps "*)
		case " ${*:2} " in
		*" -aq "* | *" -a -q "*)
			# Stopped containers included.
			if [ -n "${RCI_KC_STOPPED:-}" ]; then
				printf '%s\n' "$RCI_KC_STOPPED"
			fi
			;;
		*" -q "*)
			case "${RCI_KC_REPLICAS:-1}" in
			0) : ;;
			*) printf '%s\n' "${RCI_KC_ID:?RCI_KC_ID must be set}" ;;
			esac
			;;
		*)
			refuse "compose ps ${*:2}"
			;;
		esac
		exit 0
		;;
	esac
	refuse "$@"
fi

if [ "${1:-}" != "exec" ]; then
	refuse "$@"
fi

shift
if [ "$#" -eq 0 ]; then
	refuse "exec without a target"
fi
target="$1"
shift
printf 'exec %s\n' "$target" >>"$STATE/calls.log"

# The in-container login the narrow modes use: the password is expanded inside the container, so the host
# argv carries no secret. There is nothing to answer beyond success.
if [ "${1:-}" = "/bin/sh" ] && [ "${2:-}" = "-c" ]; then
	exit 0
fi

# Drop everything up to and including the kcadm.sh argument, then dispatch on the subcommand.
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

case "$SUB" in
config)
	exit 0
	;;
get)
	case "${1:-}" in
	realms/*)
		printf '{ "realm" : "%s", "enabled" : true }\n' "${1#realms/}"
		;;
	clients)
		# The client lookup by clientId: id-only CSV with no quotes, one row.
		printf 'cid-life-control-client\n'
		;;
	clients/*/protocol-mappers/models)
		# An empty mapper list: find_mapper_id reads it as "absent", which is the shortest path from the
		# resolution to a verdict, so the run stays about the container id.
		:
		;;
	*)
		refuse "get ${1:-}"
		;;
	esac
	exit 0
	;;
*)
	refuse "${SUB:-nothing}"
	;;
esac
STUB
}

# build_deploy_tree <root>
# The synthetic checkout deploy.sh runs in: the REAL deploy.sh and _common.sh (byte-identical, asserted with
# cmp), a materialised .env.dev, and stand-ins for the two sibling scripts start_services calls.
build_deploy_tree() {
	local root="$1"
	mkdir -p "$root/docker/scripts" "$root/bin" "$root/state"

	cp "$COMMON" "$root/docker/scripts/_common.sh"
	cp "$DEPLOY_SH" "$root/docker/scripts/deploy.sh"
	if ! cmp -s "$DEPLOY_SH" "$root/docker/scripts/deploy.sh"; then
		fail "layout: the copied deploy.sh differs from $DEPLOY_SH"
		return 1
	fi

	printf 'COMPOSE_PROJECT_NAME=%s\nKEYCLOAK_PORT=8181\n' "$TREE_PROJECT" >"$root/docker/.env.dev"

	cat >"$root/docker/scripts/db-roles.sh" <<'ROLES'
#!/bin/bash
printf 'db-roles %s\n' "$*" >>"${RCI_STATE:?RCI_STATE must be set}/calls.log"
exit 0
ROLES

	cat >"$root/docker/scripts/keycloak-setup.sh" <<'REALM'
#!/bin/bash
# Stand-in realm step: WHICH mode the deploy asks for is deploy-realm-gate.test.sh's subject, so here it
# only records that the deploy got that far.
printf 'keycloak-setup %s\n' "$*" >>"${RCI_STATE:?RCI_STATE must be set}/calls.log"
exit 0
REALM

	write_deploy_shim "$root/bin/docker"
	chmod +x "$root/docker/scripts/db-roles.sh" "$root/docker/scripts/keycloak-setup.sh" \
		"$root/bin/docker"
	: >"$root/state/calls.log"
	return 0
}

# build_setup_tree <root> <env>
# The synthetic checkout keycloak-setup.sh runs in: the REAL script (byte-identical, asserted with cmp), an
# .env.<env> for the case, and the shim. No host secret is materialised on purpose -- the narrow modes must
# not read one, so a run that needed it would fail here instead of passing for the wrong reason.
build_setup_tree() {
	local root="$1" env="$2"
	mkdir -p "$root/docker/scripts" "$root/docker/secrets" "$root/bin" "$root/state"

	cp "$SETUP_SH" "$root/docker/scripts/keycloak-setup.sh"
	if ! cmp -s "$SETUP_SH" "$root/docker/scripts/keycloak-setup.sh"; then
		fail "layout: the copied keycloak-setup.sh differs from $SETUP_SH"
		return 1
	fi

	{
		printf 'COMPOSE_PROJECT_NAME=%s\n' "$TREE_PROJECT"
		printf 'KEYCLOAK_REALM=life-control-realm\n'
		printf 'KEYCLOAK_CLIENT_ID=life-control-client\n'
		printf 'KC_HTTP_PORT=8181\n'
	} >"$root/docker/.env.$env"

	write_setup_shim "$root/bin/docker"
	chmod +x "$root/docker/scripts/keycloak-setup.sh" "$root/bin/docker"
	: >"$root/state/calls.log"
	return 0
}

# run_setup_mode <root> <env> -> sets RCI_RC and RCI_OUT (stdout + stderr together).
run_setup_mode() {
	local root="$1" env="$2"
	RCI_OUT="$(
		RCI_STATE="$root/state" PATH="$root/bin:$PATH" \
			bash "$root/docker/scripts/keycloak-setup.sh" --verify-audience "$env" 2>&1
	)"
	RCI_RC=$?
}

# run_deploy <root> <command> -> sets RCI_RC and RCI_OUT.
# The wait is bounded to two attempts and zero seconds: the property under test is the ANSWER, not the
# patience, and a regression must redden the case instead of stalling the suite for the default 7.5 minutes.
run_deploy() {
	local root="$1" command="$2"
	RCI_OUT="$(
		RCI_STATE="$root/state" SKIP_BUILD=true PATH="$root/bin:$PATH" \
			REALM_WAIT_ATTEMPTS="${RCI_WAIT_ATTEMPTS:-2}" REALM_WAIT_SECONDS=0 \
			bash "$root/docker/scripts/deploy.sh" dev "$command" 2>&1
	)"
	RCI_RC=$?
}

# The keycloak service block of a compose file, without its neighbours.
keycloak_block() {
	# The service block only: from `  <service>:` to the next top-level service (service keys are two-space
	# indented, every key inside a service is deeper than that).
	awk -v svc="^  $2:$" '
		$0 ~ svc { inside = 1; print; next }
		inside && /^  [a-z0-9_-]+:$/ { exit }
		inside { print }
	' "$1"
}

# ------------------------------------------
# Case 1 — the compose shapes this suite's premise rests on, measured in the tree.
# Fixture truths, not behaviours: they are what makes base, base+override and base+prod the three shapes a
# project can take, and what makes a name built from COMPOSE_PROJECT_NAME wrong in two of them. Asserted so
# this suite cannot keep passing over a compose file that changed underneath it (the compose files are
# deliberately NOT this unit's to edit).
# ------------------------------------------
if block="$(keycloak_block "$COMPOSE_BASE" keycloak)" && [ -n "$block" ]; then
	if printf '%s\n' "$block" | grep -q 'container_name'; then
		fail "case 1: the base compose declares container_name for keycloak, so the name is not generated in staging"
	else
		pass "case 1: the base compose declares no container_name for keycloak (compose generates the name)"
	fi
else
	fail "case 1: could not read the keycloak service block of $COMPOSE_BASE"
fi

if grep -q 'container_name: ${COMPOSE_PROJECT_NAME:-lifecontrol}-keycloak' "$COMPOSE_OVERRIDE" 2>/dev/null; then
	pass "case 1: the dev override is the only place that fixes the container_name"
else
	fail "case 1: the dev override no longer declares the keycloak container_name this suite measures"
fi

if prod_block="$(keycloak_block "$COMPOSE_PROD" keycloak)" && [ -n "$prod_block" ]; then
	if printf '%s\n' "$prod_block" | grep -q 'replicas: 2'; then
		pass "case 1: the prod compose runs two keycloak replicas (so the health read must be per container)"
	else
		fail "case 1: the prod compose no longer sets replicas: 2 on keycloak, so the replica case is untested"
	fi
	if printf '%s\n' "$prod_block" | grep -q 'container_name'; then
		fail "case 1: the prod compose now fixes a container_name, so the generated name is not what prod runs"
	else
		pass "case 1: the prod compose still declares no container_name for keycloak"
	fi
else
	fail "case 1: could not read the keycloak service block of $COMPOSE_PROD"
fi

# The static half of "never guess a name again": neither script may derive the container from the project,
# and the health may not come from a column that prints one line per container.
if grep -Eq 'CONTAINER="\$[{]?COMPOSE_PROJECT_NAME' "$SETUP_SH" 2>/dev/null; then
	fail "case 1: keycloak-setup.sh derives CONTAINER from COMPOSE_PROJECT_NAME again"
else
	pass "case 1: keycloak-setup.sh no longer derives the container from COMPOSE_PROJECT_NAME"
fi

if grep -q "docker ps --format '{{.Names}}'" "$SETUP_SH" 2>/dev/null; then
	fail "case 1: keycloak-setup.sh greps a container NAME out of \`docker ps\` again"
else
	pass "case 1: keycloak-setup.sh asks compose for the id instead of grepping a name"
fi

if grep -q "ps --format '{{.Health}}' \"\$KEYCLOAK_SERVICE\"" "$DEPLOY_SH" 2>/dev/null; then
	fail "case 1: deploy.sh reads the keycloak health from a ps column again (one line per container)"
else
	pass "case 1: deploy.sh reads the keycloak health per container id"
fi

# ------------------------------------------
# Case 2 — dev: base + override, where the override is what fixes the container_name.
# The resolution must go through that file stack, and the exec target must be the id compose answered.
# ------------------------------------------
root="$(new_root)"
if build_setup_tree "$root" dev; then
	export RCI_STATE="$root/state"
	export RCI_KC_ID="$KC_ID"
	export RCI_KC_REPLICAS="1"
	run_setup_mode "$root" dev
	assert_count "case 2 (dev): compose is asked once, for the keycloak id" 1 "compose -f" "$root/state/calls.log"
	assert_contains "case 2 (dev): the resolution loads the base compose file" \
		"docker-compose.yml" "$(cat "$root/state/calls.log")"
	assert_contains "case 2 (dev): the resolution loads the dev override" \
		"docker-compose.override.yml" "$(cat "$root/state/calls.log")"
	assert_contains "case 2 (dev): the resolution asks for the keycloak service" \
		"ps -q keycloak" "$(cat "$root/state/calls.log")"
	assert_exec_targets_are "case 2 (dev): every exec target is the id compose answered" "$KC_ID" "$root/state/calls.log"
	# The narrow mode's verdict is reached THROUGH the resolved id, which is what makes the identity real
	# rather than a prefix of the run that was never used.
	assert_rc "case 2 (dev): the run reaches its verdict through the resolved container" 1 "$RCI_RC" "$RCI_OUT"
	assert_contains "case 2 (dev): the verdict is the mapper's, read from the realm" \
		"life-control-client/api-audience is absent" "$RCI_OUT"
fi

# ------------------------------------------
# Case 3 — staging: base only. No override, so compose generates `<project>-keycloak-1`; a name built from
# COMPOSE_PROJECT_NAME would be `<project>-keycloak`, which is not what runs.
# ------------------------------------------
root="$(new_root)"
if build_setup_tree "$root" staging; then
	export RCI_STATE="$root/state"
	export RCI_KC_ID="$KC_ID"
	export RCI_KC_REPLICAS="1"
	run_setup_mode "$root" staging
	assert_contains "case 3 (staging): the resolution loads the base compose file" \
		"docker-compose.yml" "$(cat "$root/state/calls.log")"
	assert_not_contains "case 3 (staging): the dev override is not loaded in staging" \
		"docker-compose.override.yml" "$(cat "$root/state/calls.log")"
	assert_exec_targets_are "case 3 (staging): every exec target is the id compose answered" "$KC_ID" "$root/state/calls.log"
	assert_rc "case 3 (staging): the run reaches its verdict through the resolved container" 1 "$RCI_RC" "$RCI_OUT"
fi

# ------------------------------------------
# Case 4 — prod: base + prod, where the service runs two replicas and still has no container_name.
# One replica is enough to provision -- the replicas share one realm database -- but the resolution has to
# name the file stack prod actually loads.
# ------------------------------------------
root="$(new_root)"
if build_setup_tree "$root" prod; then
	export RCI_STATE="$root/state"
	export RCI_KC_ID="$KC_ID"
	export RCI_KC_REPLICAS="1"
	run_setup_mode "$root" prod
	assert_contains "case 4 (prod): the resolution loads the base compose file" \
		"docker-compose.yml" "$(cat "$root/state/calls.log")"
	assert_contains "case 4 (prod): the resolution loads the prod override" \
		"docker-compose.prod.yml" "$(cat "$root/state/calls.log")"
	assert_exec_targets_are "case 4 (prod): every exec target is the id compose answered" "$KC_ID" "$root/state/calls.log"
	assert_rc "case 4 (prod): the run reaches its verdict through the resolved container" 1 "$RCI_RC" "$RCI_OUT"
fi

# ------------------------------------------
# Case 5 — no container at all: the script says so and exits, naming the cause and the command that starts
# the stack. A message about a container it invented is exactly what this replaces.
# ------------------------------------------
root="$(new_root)"
if build_setup_tree "$root" dev; then
	export RCI_STATE="$root/state"
	export RCI_KC_ID="$KC_ID"
	export RCI_KC_REPLICAS="0"
	unset RCI_KC_STOPPED
	run_setup_mode "$root" dev
	assert_rc "case 5: no keycloak container aborts with 1" 1 "$RCI_RC" "$RCI_OUT"
	assert_contains "case 5: the message names the cause" "no keycloak container" "$RCI_OUT"
	assert_contains "case 5: the message names the command that starts the stack" \
		"deploy.sh dev up" "$RCI_OUT"
	assert_count "case 5: nothing is exec'd into a container that does not exist" 0 "exec " "$root/state/calls.log"
fi

# ------------------------------------------
# Case 6 — two healthy replicas: the wait succeeds.
# The health of a two-replica project printed as a column is two lines; stripping the newlines made
# `healthyhealthy`, which matched no state and consumed the whole bound on a perfectly healthy stack.
# ------------------------------------------
root="$(new_root)"
if build_deploy_tree "$root"; then
	export RCI_STATE="$root/state"
	export RCI_KC_REPLICAS="2"
	export RCI_KC_IDS_1="$KC_ID"
	export RCI_KC_IDS_2="$KC_ID_2"
	unset RCI_KC_STOPPED RCI_KC_UNHEALTHY_IDS
	RCI_WAIT_ATTEMPTS=2
	run_deploy "$root" start
	assert_rc "case 6: two healthy replicas pass the wait" 0 "$RCI_RC" "$RCI_OUT"
	assert_contains "case 6: the deploy reached the realm step" \
		"keycloak-setup --ensure-audience dev" "$(cat "$root/state/calls.log")"
	assert_not_contains "case 6: the wait never reports the replicas as unhealthy" \
		"did not report healthy" "$RCI_OUT"
	assert_file_line "case 6: the health was read for a replica id" "inspect $KC_ID" "$root/state/calls.log"
fi

# ------------------------------------------
# Case 7 — the second replica is the healthy one.
# One health value read for the whole project would either fail this case or succeed it by luck; reading
# per id is what makes "any replica healthy" true. Inverse of case 8's neighbour, so case 6 cannot pass by
# looking at exactly one id and being right.
# ------------------------------------------
root="$(new_root)"
if build_deploy_tree "$root"; then
	export RCI_STATE="$root/state"
	export RCI_KC_REPLICAS="2"
	export RCI_KC_IDS_1="$KC_ID"
	export RCI_KC_IDS_2="$KC_ID_2"
	export RCI_KC_UNHEALTHY_IDS="$KC_ID"
	unset RCI_KC_STOPPED
	RCI_WAIT_ATTEMPTS=2
	run_deploy "$root" start
	assert_rc "case 7: the wait passes when the second replica is the healthy one" 0 "$RCI_RC" "$RCI_OUT"
	assert_file_line "case 7: the unhealthy replica was read by its id" "inspect $KC_ID" "$root/state/calls.log"
	assert_file_line "case 7: the healthy replica was read by its id" "inspect $KC_ID_2" "$root/state/calls.log"
	unset RCI_KC_UNHEALTHY_IDS
fi

# ------------------------------------------
# Case 8 — no container in the project: a DIFFERENT failure from "it never became healthy", and the
# operator fixes it differently. It must be diagnosed before any waiting, and the realm step must not run.
# ------------------------------------------
root="$(new_root)"
if build_deploy_tree "$root"; then
	export RCI_STATE="$root/state"
	export RCI_KC_REPLICAS="0"
	export RCI_KC_IDS_1="$KC_ID"
	export RCI_KC_IDS_2="$KC_ID_2"
	unset RCI_KC_STOPPED RCI_KC_UNHEALTHY_IDS
	RCI_WAIT_ATTEMPTS=2
	run_deploy "$root" start
	assert_rc "case 8: a project with no keycloak container fails the start" 1 "$RCI_RC" "$RCI_OUT"
	assert_contains "case 8: the message names the cause, not a timeout" \
		"no keycloak container" "$RCI_OUT"
	assert_contains "case 8: the message names the command that starts the stack" \
		"deploy.sh dev up" "$RCI_OUT"
	assert_not_contains "case 8: it is not reported as a container that never became healthy" \
		"did not report healthy" "$RCI_OUT"
	assert_count "case 8: the realm step is never reached" 0 "keycloak-setup " "$root/state/calls.log"
fi

# ------------------------------------------
# Case 9 — a container exists but is not running. Without the `-a` probe this is indistinguishable from a
# project that never ran keycloak, and the two have different fixes.
# ------------------------------------------
root="$(new_root)"
if build_deploy_tree "$root"; then
	export RCI_STATE="$root/state"
	export RCI_KC_REPLICAS="0"
	export RCI_KC_STOPPED="$KC_ID_STOPPED"
	export RCI_KC_IDS_1="$KC_ID"
	export RCI_KC_IDS_2="$KC_ID_2"
	unset RCI_KC_UNHEALTHY_IDS
	RCI_WAIT_ATTEMPTS=2
	run_deploy "$root" start
	assert_rc "case 9: a stopped keycloak container fails the start" 1 "$RCI_RC" "$RCI_OUT"
	assert_contains "case 9: the message says the container exists and is not running" \
		"is not running" "$RCI_OUT"
	assert_not_contains "case 9: it is not reported as a missing container" \
		"no keycloak container" "$RCI_OUT"
	assert_not_contains "case 9: it is not reported as a container that never became healthy" \
		"did not report healthy" "$RCI_OUT"
	unset RCI_KC_STOPPED
fi

# ------------------------------------------
# Summary
# ------------------------------------------
echo "------------------------------------------"
printf 'realm-container-identity: %s passed, %s failed\n' "$PASS_COUNT" "$FAIL_COUNT"
if [ "$FAIL_COUNT" -ne 0 ]; then
	exit 1
fi
exit 0
