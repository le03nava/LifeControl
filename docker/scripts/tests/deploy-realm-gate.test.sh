#!/bin/bash
# ============================================
# LifeControl - the realm step in the deploy path
# ============================================
# `deploy.sh <env> start` never ran `keycloak-setup.sh`, and since the audience validation landed the
# realm is what makes every token acceptable: without the `api-audience` mapper on the public client
# Keycloak mints `aud: ["account"]`, both JWT decoders answer `401 invalid_token` on everything, and
# `health` -- which reads containers, Prometheus and Tempo, never the realm -- reports green over it
# (issue #1029, odd/tasks/deploy-realm-gate.md S1-S5).
#
# This suite freezes both halves of the requirement offline:
#
#   * the deploy path reaches the realm step: `start` converges it with `--ensure-audience`, after
#     Keycloak has answered and before the health gate, and aborts the start when it fails;
#   * the health gate is red on an unconverged realm: `health` asks with the read-only
#     `--verify-audience` and returns non-zero when the realm does not mint what the decoders require,
#     while its existing verdicts still decide the rest.
#
# Removing either call from deploy.sh fails this suite: the assertions are about the invocation that
# happened, not about the text that describes it. The realm resynchronisation is observed through a
# stand-in `keycloak-setup.sh` (argv recorded, exit code from the case), and the container world through
# a `docker` shim, so no daemon, no Keycloak and no network are involved.
#
# What this suite deliberately does NOT assert: the behaviour of the two modes themselves. That is
# keycloak-setup.test.sh's job (its cases 19-21), which drives the real script against a kcadm shim;
# here the real script is only checked for accepting the exact flags the deploy path passes, because a
# seam where one side renames a flag and the other keeps the old name is invisible to both suites.
#
# Run: ./docker/scripts/tests/deploy-realm-gate.test.sh
# Exits 0 only when every case passes.

# No `set -e`: the cases assert non-zero exit codes, so every status is captured and compared
# explicitly (keycloak-setup.test.sh and freshness-guard.test.sh do the same). `set -u` keeps the
# harness honest about typos.
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DOCKER_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
DEPLOY_SH="$DOCKER_DIR/deploy.sh"
COMMON="$DOCKER_DIR/_common.sh"
SETUP_SH="$DOCKER_DIR/keycloak-setup.sh"

PASS_COUNT=0
FAIL_COUNT=0

# The compose project the synthetic env file declares. Only its shape matters: nothing resolves the
# container by name here, because the stand-in scripts never look at one.
TREE_COMPOSE_NAME="lc-realm-gate-test"

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

new_tree() {
	mktemp -d "$TMP_BASE/case.XXXXXX"
}

# count_matching <file> <needle> -> 0 when the file is absent or holds no match.
count_matching() {
	local file="$1" needle="$2" count
	count="$(grep -cF -- "$needle" "$file" 2>/dev/null || true)"
	printf '%s' "${count:-0}"
}

# first_line <file> <needle> -> the 1-based line of the first match, empty when absent.
first_line() {
	grep -nF -- "$2" "$1" 2>/dev/null | head -1 | cut -d: -f1
}

# last_line <file> <needle> -> the 1-based line of the last match, empty when absent.
last_line() {
	grep -nF -- "$2" "$1" 2>/dev/null | tail -1 | cut -d: -f1
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
# The observation: how many times a call was attempted. A missing file counts as zero, so
# "the call never happened" cannot be satisfied by the evidence file never appearing.
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

# assert_file_line <name> <line> <file> — passes only when the file holds <line> exactly, so
# `--verify-audience` cannot satisfy an assertion written for `--ensure-audience`.
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

# assert_before <name> <file> <earlier-needle> <later-needle>
# Ordering is a property of the recorded call log, which is why both stand-ins append to it: the
# order inside deploy.sh is the one thing a text search of the file cannot answer.
assert_before() {
	local name="$1" file="$2" earlier="$3" later="$4" a b
	a="$(first_line "$file" "$earlier")"
	b="$(first_line "$file" "$later")"
	if [ -n "$a" ] && [ -n "$b" ] && [ "$a" -lt "$b" ]; then
		pass "$name (line $a before line $b)"
	else
		fail "$name (expected '$earlier' before '$later', got lines '$a' and '$b')"
		if [ -f "$file" ]; then
			sed 's/^/        /' "$file"
		fi
	fi
}

# write_realm_stub <path>
# The stand-in for docker/scripts/keycloak-setup.sh. It answers the two narrow modes the deploy path
# asks for, records every invocation in the shared call log, and takes its exit code from the case --
# which is how "the realm did not converge" is modelled without a Keycloak.
write_realm_stub() {
	cat >"$1" <<'STUB'
#!/bin/bash
# ============================================
# Stand-in keycloak-setup.sh for deploy-realm-gate.test.sh (written at run time)
# ============================================
# There is exactly one reason this file exists: deploy.sh decides whether a deploy succeeds, and the
# realm is the state it has to answer for. So the stand-in keeps the ONE property the deploy path is
# asserted on -- which mode it was asked for -- and takes the verdict from the case:
#
#   DRG_ENSURE_RC  exit code for --ensure-audience (default 0: the realm converged)
#   DRG_VERIFY_RC  exit code for --verify-audience (default 0: the realm mints the audience)
#
# Both evidential logs are appended to, never truncated: `calls.log` is shared with the `docker` shim
# so ordering across the two is observable, and `keycloak-args` holds one argv per line so a mode can
# be matched exactly instead of by substring.
set -u

STATE="${DRG_STATE:?DRG_STATE must be set}"
printf 'keycloak-setup %s\n' "$*" >>"$STATE/calls.log"
printf '%s\n' "$*" >>"$STATE/keycloak-args"

mode=""
for arg in "$@"; do
	case "$arg" in
	--ensure-audience | --verify-audience) mode="$arg" ;;
	esac
done

rc=0
case "$mode" in
--ensure-audience) rc="${DRG_ENSURE_RC:-0}" ;;
--verify-audience) rc="${DRG_VERIFY_RC:-0}" ;;
esac

if [ "$rc" -ne 0 ]; then
	printf 'Protocol mapper life-control-client/api-audience does not meet the declared contract in realm life-control-realm (%s)\n' "${mode:-no-mode}" >&2
fi
exit "$rc"
STUB
}

# write_docker_stub <path>
# Answers only what deploy.sh's start/health paths ask of `docker`, and logs the two compose
# subcommands whose ORDER relative to the realm step is the requirement (D-c): `up` and `ps`.
write_docker_stub() {
	cat >"$1" <<'STUB'
#!/bin/bash
# ============================================
# Stand-in `docker` for deploy-realm-gate.test.sh (written at run time)
# ============================================
# `docker info` is the requirement check; everything else deploy.sh does to the stack goes through
# `docker compose`. The shim answers the handful of shapes those two commands read and logs the
# service-level facts the case asserts on:
#
#   compose up <target>        the start of one service (lifecontrol-postgres) or of the whole project
#                              (`all`, i.e. `up -d`), so "the realm step runs after the services" is a
#                              statement about the log
#   compose ps <args>          the health reads: `--services`, the `{{.Service}} {{.State}} {{.Health}}`
#                              table and the `{{.Health}}` wait that precedes the realm step
#
# DRG_KC_HEALTH is the health state the wait sees; every other service reports running/healthy, so a
# case that is about the realm is not also a case about a container verdict.
set -u

STATE="${DRG_STATE:?DRG_STATE must be set}"

if [ "${1:-}" = "info" ]; then
	exit 0
fi

if [ "${1:-}" != "compose" ]; then
	printf 'compose shim: unsupported docker invocation: %s\n' "$*" >&2
	exit 64
fi

shift
# Drop the compose files and the env file, in whatever order compose accepts them.
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
	# `up -d` starts the whole project; `up -d <service>` starts one service.
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
		# The expected container count of the health loop.
		printf '%s\n' keycloak lifecontrol-api
		;;
	*"{{.Service}} {{.State}} {{.Health}}"*)
		printf 'keycloak running %s\n' "${DRG_KC_HEALTH:-healthy}"
		printf 'lifecontrol-api running healthy\n'
		;;
	*"{{.Health}}"*)
		# The bounded wait before the realm step reads this shape.
		printf '%s\n' "${DRG_KC_HEALTH:-healthy}"
		;;
	*)
		printf '%s\n' "keycloak running ${DRG_KC_HEALTH:-healthy}" "lifecontrol-api running healthy"
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

# build_tree <root>
# The synthetic checkout deploy.sh runs in: the REAL deploy.sh and _common.sh (byte-identical, asserted
# with cmp so the bytes under test are the repository's), a materialised .env.dev, and stand-ins for the
# two sibling scripts start_services calls.
build_tree() {
	local root="$1"
	mkdir -p "$root/docker/scripts" "$root/bin" "$root/state"

	cp "$COMMON" "$root/docker/scripts/_common.sh"
	cp "$DEPLOY_SH" "$root/docker/scripts/deploy.sh"
	if ! cmp -s "$DEPLOY_SH" "$root/docker/scripts/deploy.sh"; then
		fail "layout: the copied deploy.sh differs from $DEPLOY_SH"
		return 1
	fi
	if ! cmp -s "$COMMON" "$root/docker/scripts/_common.sh"; then
		fail "layout: the copied _common.sh differs from $COMMON"
		return 1
	fi

	# No observability port is declared: the host probes then have nothing to ask, and a case about
	# the realm never depends on whether curl is installed.
	printf 'COMPOSE_PROJECT_NAME=%s\nKEYCLOAK_PORT=8181\n' "$TREE_COMPOSE_NAME" \
		>"$root/docker/.env.dev"

	cat >"$root/docker/scripts/db-roles.sh" <<'ROLES'
#!/bin/bash
# Stand-in db-roles.sh: the start path runs it before the services, and no case here is about SQL.
printf 'db-roles %s\n' "$*" >>"${DRG_STATE:?}/calls.log"
exit 0
ROLES

	write_realm_stub "$root/docker/scripts/keycloak-setup.sh"
	write_docker_stub "$root/bin/docker"
	chmod +x "$root/docker/scripts/db-roles.sh" "$root/docker/scripts/keycloak-setup.sh" \
		"$root/bin/docker"

	: >"$root/state/calls.log"
	: >"$root/state/keycloak-args"
	return 0
}

# run_deploy <root> <command> -> sets DEPLOY_RC and DEPLOY_OUT (stdout + stderr together, because the
# deploy reports failures through print_error).
run_deploy() {
	local root="$1" command="$2"
	DEPLOY_OUT="$(
		DRG_STATE="$root/state" SKIP_BUILD=true PATH="$root/bin:$PATH" \
			bash "$root/docker/scripts/deploy.sh" dev "$command" 2>&1
	)"
	DEPLOY_RC=$?
}

# ------------------------------------------
# Case 1 (S1, S4, S5) — the deploy path converges the realm.
# The positive half of S5: `start` on a realm that answers the converge call exits 0, asks for the
# converging mode exactly once, does it AFTER Keycloak has answered and BEFORE the health gate, and
# finishes its own work (show_status) afterwards. Deleting the call fails every assertion here.
# ------------------------------------------
root="$(new_tree)"
if build_tree "$root"; then
	export DRG_ENSURE_RC=0
	export DRG_VERIFY_RC=0
	run_deploy "$root" start
	assert_rc "case 1 (S1): start converges the realm and exits 0" 0 "$DEPLOY_RC" "$DEPLOY_OUT"
	assert_count "case 1 (S1): start converges the realm exactly once" 1 \
		"--ensure-audience" "$root/state/keycloak-args"
	assert_file_line "case 1 (S1): start asks for the converging mode on this environment" \
		"--ensure-audience dev" "$root/state/keycloak-args"
	assert_contains "case 1 (S1): start reports the realm converged" \
		"Realm audience" "$DEPLOY_OUT"
	# D-c: services up, then the bounded wait, then the converge.
	assert_before "case 1 (S4): the whole project is up before the realm step" \
		"$root/state/calls.log" "compose up all" "keycloak-setup --ensure-audience"
	assert_before "case 1 (S4): Keycloak answered before the realm step" \
		"$root/state/calls.log" "compose ps --format {{.Health}} keycloak" \
		"keycloak-setup --ensure-audience"
	# S4: the converge runs before the health gate that would otherwise pass over an unconverged realm.
	assert_before "case 1 (S4): the converge runs before the health gate" \
		"$root/state/calls.log" "keycloak-setup --ensure-audience" \
		"keycloak-setup --verify-audience"
	assert_count "case 1 (S4): the health gate reads the realm after start converges it" 1 \
		"keycloak-setup --verify-audience" "$root/state/calls.log"
	# start still ends with its own status report: the converge was inserted, not substituted.
	assert_contains "case 1 (S4): start still reports the service status" "Service Status:" "$DEPLOY_OUT"
	unset DRG_ENSURE_RC DRG_VERIFY_RC
fi

# ------------------------------------------
# Case 2 (S2) — a converge that fails aborts the start.
# The realm step refusing is the case the requirement is about: the deploy must NOT report success.
# The message has to name the script and the reason -- the shape db-roles.sh already has -- and the
# start must not reach show_status or the health gate after it failed.
# ------------------------------------------
root="$(new_tree)"
if build_tree "$root"; then
	export DRG_ENSURE_RC=1
	export DRG_VERIFY_RC=0
	run_deploy "$root" start
	assert_rc "case 2 (S2): a realm that does not converge fails the start" 1 "$DEPLOY_RC" "$DEPLOY_OUT"
	assert_contains "case 2 (S2): the failure names the script to run" "keycloak-setup.sh" "$DEPLOY_OUT"
	assert_contains "case 2 (S2): the failure names the reason" "Realm audience" "$DEPLOY_OUT"
	assert_contains "case 2 (S2): the failure says the start was aborted" "aborting start" "$DEPLOY_OUT"
	assert_not_contains "case 2 (S2): the failed start never reports the realm converged" \
		"Realm audience in place" "$DEPLOY_OUT"
	assert_not_contains "case 2 (S2): the failed start never reaches the status report" \
		"Service Status:" "$DEPLOY_OUT"
	assert_count "case 2 (S2): the health gate is never reached after the abort" 0 \
		"keycloak-setup --verify-audience" "$root/state/calls.log"
	unset DRG_ENSURE_RC DRG_VERIFY_RC
fi

# ------------------------------------------
# Case 3 (S3, S5) — health is red on an unconverged realm.
# The second half of S5. The stack itself is healthy -- every container reports running/healthy -- so
# a green verdict here is exactly the silent pass the unit exists to remove. health must ask with the
# READ-ONLY mode (D-d: a health check that writes can mask the state it is asked to report), return
# non-zero, and say which requirement failed.
# ------------------------------------------
root="$(new_tree)"
if build_tree "$root"; then
	export DRG_ENSURE_RC=0
	export DRG_VERIFY_RC=1
	run_deploy "$root" health
	assert_rc "case 3 (S3): health is red on an unconverged realm" 1 "$DEPLOY_RC" "$DEPLOY_OUT"
	assert_contains "case 3 (S3): health says the realm does not mint the audience" \
		"does not mint the audience the running decoders require" "$DEPLOY_OUT"
	assert_count "case 3 (S5): health reaches the realm step" 1 \
		"keycloak-setup" "$root/state/calls.log"
	assert_file_line "case 3 (S3): health asks for the read-only mode on this environment" \
		"--verify-audience dev" "$root/state/keycloak-args"
	assert_count "case 3 (D-d): health never converges the realm" 0 \
		"--ensure-audience" "$root/state/keycloak-args"
	# The containers were fine: the verdict is the realm's, not a container's.
	assert_contains "case 3 (S3): the container verdict still passed first" \
		"All services healthy" "$DEPLOY_OUT"
	# The reason has to be actionable, not a bare exit code: it names the command that converges it.
	assert_contains "case 3 (S3): the failure names the command that converges the realm" \
		"keycloak-setup.sh dev" "$DEPLOY_OUT"
	unset DRG_ENSURE_RC DRG_VERIFY_RC
fi

# ------------------------------------------
# Case 4 (S3, inverse) — health is green when the realm mints the audience.
# Without this control, case 3 would be satisfied by a health check that fails on every run.
# ------------------------------------------
root="$(new_tree)"
if build_tree "$root"; then
	export DRG_ENSURE_RC=0
	export DRG_VERIFY_RC=0
	run_deploy "$root" health
	assert_rc "case 4 (S3-inverse): health is green on a converged realm" 0 "$DEPLOY_RC" "$DEPLOY_OUT"
	assert_contains "case 4 (S3-inverse): health says the realm mints the audience" \
		"Realm mints the audience" "$DEPLOY_OUT"
	assert_contains "case 4 (S3-inverse): the container verdict is untouched" \
		"All services healthy" "$DEPLOY_OUT"
	assert_count "case 4 (S3-inverse): health invokes the realm step once" 1 \
		"keycloak-setup" "$root/state/calls.log"
	unset DRG_ENSURE_RC DRG_VERIFY_RC
fi

# ------------------------------------------
# Case 5 (D-a) — a command that is not start or health leaves the realm alone.
# `status` reports; it does not converge and does not gate. Without this case, "the realm step runs
# on every command" would pass case 1 and fail the owner of the stack, who runs `status` all day.
# ------------------------------------------
root="$(new_tree)"
if build_tree "$root"; then
	export DRG_ENSURE_RC=0
	export DRG_VERIFY_RC=0
	run_deploy "$root" status
	assert_rc "case 5 (D-a): status exits 0" 0 "$DEPLOY_RC" "$DEPLOY_OUT"
	assert_count "case 5 (D-a): status never invokes the realm step" 0 \
		"keycloak-setup" "$root/state/calls.log"
	unset DRG_ENSURE_RC DRG_VERIFY_RC
fi

# ------------------------------------------
# Case 6 (S3) — the gate is added after the existing verdicts, not in place of any of them.
# A text-level assertion, because the failure it prevents is structural: a gate moved to the TOP of
# health_check would answer about the realm before the containers are even read, and every behavioural
# case above would still pass. The three probes and the image-revision report have to stay inside
# health_check, and the realm gate has to come after them and before the success return.
# ------------------------------------------
health_body="$(
	awk '/^health_check\(\)/{f=1} f{print} f&&/^}$/{exit}' "$DEPLOY_SH" 2>/dev/null
)"
if [ -z "$health_body" ]; then
	fail "case 6 (S3): deploy.sh has no health_check to inspect"
else
	for probe in report_image_revisions host_readiness_probes host_observability_probes; do
		if printf '%s\n' "$health_body" | grep -q -- "$probe"; then
			pass "case 6 (S3): health_check still runs $probe"
		else
			fail "case 6 (S3): health_check no longer runs $probe"
		fi
	done
	gate_line="$(printf '%s\n' "$health_body" | grep -n -- '--verify-audience' | head -1 | cut -d: -f1)"
	probe_line="$(printf '%s\n' "$health_body" | grep -n -- 'host_observability_probes' | head -1 | cut -d: -f1)"
	return_line="$(printf '%s\n' "$health_body" | grep -n -- 'return 0' | head -1 | cut -d: -f1)"
	if [ -n "$gate_line" ] && [ -n "$probe_line" ] && [ -n "$return_line" ] &&
		[ "$probe_line" -lt "$gate_line" ] && [ "$gate_line" -lt "$return_line" ]; then
		pass "case 6 (S3): the realm gate runs after the probes and before the success return (lines $probe_line < $gate_line < $return_line)"
	else
		fail "case 6 (S3): the realm gate is not between the probes and the success return (probe=$probe_line gate=$gate_line return=$return_line)"
	fi
	if printf '%s\n' "$health_body" | grep -q -- '--ensure-audience'; then
		fail "case 6 (D-d): health_check converges the realm instead of reading it"
	else
		pass "case 6 (D-d): health_check never converges the realm"
	fi
fi

# ------------------------------------------
# Case 7 — the seam: the flags the deploy path passes are the flags the script parses.
# The stand-in above proves deploy.sh passes `--ensure-audience` / `--verify-audience`; nothing so far
# proves keycloak-setup.sh accepts them, and the two suites would both stay green if the script renamed
# its modes. The coupling is one line per flag.
# ------------------------------------------
if [ -f "$SETUP_SH" ]; then
	pass "case 7: docker/scripts/keycloak-setup.sh exists"
else
	fail "case 7: missing docker/scripts/keycloak-setup.sh"
fi
for flag in --ensure-audience --verify-audience; do
	if grep -q -- "$flag)" "$SETUP_SH" 2>/dev/null; then
		pass "case 7: keycloak-setup.sh parses $flag"
	else
		fail "case 7: keycloak-setup.sh does not parse $flag, which deploy.sh passes"
	fi
	if grep -q -- "$flag" "$SETUP_SH" 2>/dev/null &&
		printf '%s\n' "$(sed -n '/^usage()/,/^}/p' "$SETUP_SH" 2>/dev/null)" | grep -q -- "$flag"; then
		pass "case 7: the usage text documents $flag"
	else
		fail "case 7: the usage text does not document $flag"
	fi
done

# ------------------------------------------
# Summary
# ------------------------------------------
echo "------------------------------------------"
printf 'deploy-realm-gate: %s passed, %s failed\n' "$PASS_COUNT" "$FAIL_COUNT"
if [ "$FAIL_COUNT" -ne 0 ]; then
	exit 1
fi
exit 0
