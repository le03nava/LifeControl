#!/bin/bash
# ============================================
# LifeControl - `deploy.sh clean` against bind-mounted data
# ============================================
# Every directory this stack keeps state in is a **bind mount** from ${VOLUMES_ROOT} (dev and staging
# mount all of them; prod mounts the application database, the keycloak realm copies, redis and the
# prometheus/tempo/grafana config trees the same way). `docker compose down -v` removes **named
# volumes** and containers and the network -- it never touches a bind mount.
#
# Before this suite, `clean` printed `This will remove all volumes and data!` and then
# `Services and volumes cleaned!`, and the database directory was still there, dated from the previous
# month. That message is not cosmetic: it was trusted, nothing else was deleted, and the next `start`
# came up against the old cluster, which read exactly like "the change under test does not work"
# (issue #723).
#
# What this suite pins, one case per rule:
#
#   1. `clean` names the bind-mounted directories it left in place -- it never claims to have removed
#      data it cannot remove.
#   2. `clean` resolves the compose project from the env file in use, the way `start` does, so `down`
#      cannot act on a different project's network.
#   3. `--data` is the only way the host directories are deleted, and it asks a second, explicit
#      confirmation naming the exact path before deleting anything.
#   4. Declining that second confirmation keeps the data and says so.
#   5. An absolute VOLUMES_ROOT (production, outside the checkout) is refused by `--data`, the policy
#      `cleanup.sh local` already takes, and the paths are printed so they can be deleted by hand.
#   6. Cancelling the first prompt does nothing at all, and an unknown option is an error, not a
#      silently different cleanup.
#   7. `cleanup.sh local` still removes a relative root and still refuses an absolute one.
#
# No Docker daemon, no network: the real scripts run inside a throwaway tree with a stubbed `docker`
# that records the argv it was given, and every data directory is asserted on disk.
#
# Run: ./docker/scripts/tests/clean-bind-mounts.test.sh
# Exits 0 only when every case passes.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
DOCKER_DIR="$REPO_ROOT/docker"
COMMON="$DOCKER_DIR/scripts/_common.sh"
DEPLOY_SH="$DOCKER_DIR/scripts/deploy.sh"
CLEANUP_SH="$DOCKER_DIR/scripts/cleanup.sh"

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

assert_contains() { # <description> <needle> <haystack>
	if printf '%s' "$3" | grep -qF -- "$2"; then
		pass "$1"
	else
		fail "$1 (missing '$2')"
		printf '%s\n' "$3" | sed 's/^/        /'
	fi
}

assert_not_contains() { # <description> <needle> <haystack>
	if printf '%s' "$3" | grep -qF -- "$2"; then
		fail "$1 (unexpected '$2')"
		printf '%s\n' "$3" | sed 's/^/        /'
	else
		pass "$1"
	fi
}

assert_equal() { # <description> <expected> <actual>
	if [ "$2" = "$3" ]; then
		pass "$1 ($3)"
	else
		fail "$1 (expected '$2', got '$3')"
	fi
}

assert_dir_present() { # <description> <path>
	if [ -d "$2" ]; then
		pass "$1"
	else
		fail "$1 ($2 is gone)"
	fi
}

assert_dir_absent() { # <description> <path>
	if [ -d "$2" ]; then
		fail "$1 ($2 still exists)"
	else
		pass "$1"
	fi
}

TMP_BASE="$(mktemp -d)"
# shellcheck disable=SC2329  # invoked by the EXIT trap below, which shellcheck cannot trace
cleanup() { rm -rf "$TMP_BASE"; }
trap cleanup EXIT

# new_tree -> echoes a throwaway tree holding the real scripts and a stubbed `docker`.
#
# The env file is materialized, never copied: docker/.env.<env> is gitignored, so CI has no such file
# and a suite that read the checkout's own env file would pass on one machine and fail in CI.
new_tree() {
	local tree
	tree="$(mktemp -d "$TMP_BASE/tree.XXXXXX")"
	mkdir -p "$tree/docker/scripts" "$tree/bin"
	cp "$COMMON" "$tree/docker/scripts/_common.sh"
	cp "$DEPLOY_SH" "$tree/docker/scripts/deploy.sh"
	cp "$CLEANUP_SH" "$tree/docker/scripts/cleanup.sh"
	cat >"$tree/bin/docker" <<'STUB'
#!/bin/bash
# Records what compose was asked to do. No daemon is involved, so nothing is really removed.
printf '%s\n' "$*" >>"${STUB_LOG:?}"
exit 0
STUB
	chmod +x "$tree/bin/docker" "$tree/docker/scripts/deploy.sh" "$tree/docker/scripts/cleanup.sh"
	printf 'COMPOSE_PROJECT_NAME=lifecontrol-test\nVOLUMES_ROOT=./volumes-dev\n' >"$tree/docker/.env.dev"
	printf '%s' "$tree"
}

# run_sh <tree> <stdin> <script> [args...] -> sets RUN_OUT, RUN_LOG (the stub's argv) and RUN_RC.
# The stdin string answers the confirmations: one character per prompt, 'y'/'n'.
run_sh() {
	local tree="$1" input="$2" script="$3"
	shift 3
	: >"$tree/stub.log"
	RUN_OUT="$(printf '%s' "$input" | STUB_LOG="$tree/stub.log" PATH="$tree/bin:$PATH" \
		"$tree/docker/scripts/$script" "$@" 2>&1)"
	RUN_RC=$?
	RUN_LOG="$(cat "$tree/stub.log")"
}

# seed_data <tree> -> the bind-mounted trees a running dev stack leaves behind.
seed_data() {
	mkdir -p "$1/docker/volumes-dev/postgres/lifecontrol/data/18" \
		"$1/docker/volumes-dev/keycloak-postgres/data" \
		"$1/docker/volumes-dev/redis/data"
}

# ------------------------------------------
# 1. `clean` without --data: honest, and the data survives
# ------------------------------------------
tree="$(new_tree)"
seed_data "$tree"
vol_root="$tree/docker/volumes-dev"
postgres_dir="$vol_root/postgres/lifecontrol/data"

run_sh "$tree" "y" deploy.sh dev clean
assert_equal "clean exits 0 after removing the Docker objects" "0" "$RUN_RC"
assert_contains "clean removes the containers and the network through compose down -v" "down -v" "$RUN_LOG"
assert_contains "clean resolves the project from the env file in use, like start does" \
	"--env-file $tree/docker/.env.dev" "$RUN_LOG"
assert_dir_present "the bind-mounted database directory survives clean" "$postgres_dir"
assert_not_contains "clean no longer claims it removed the volumes it cannot remove" \
	"Services and volumes cleaned" "$RUN_OUT"
assert_contains "clean names the directory that is still there" "$vol_root" "$RUN_OUT"
assert_contains "clean names the bind mounts inside it, postgres first" "$vol_root/postgres" "$RUN_OUT"
assert_contains "clean names the keycloak database among the survivors" \
	"$vol_root/keycloak-postgres" "$RUN_OUT"
assert_contains "clean says the next start reuses what is there" "The next start reuses" "$RUN_OUT"
assert_contains "clean points at the flag that would delete it" "--data" "$RUN_OUT"

# ------------------------------------------
# 2. `clean --data`: a second, explicit confirmation names the path
# ------------------------------------------
tree="$(new_tree)"
seed_data "$tree"
vol_root="$tree/docker/volumes-dev"

run_sh "$tree" "yy" deploy.sh dev clean --data
assert_equal "clean --data exits 0 when both confirmations are given" "0" "$RUN_RC"
assert_contains "the first prompt states the deletion that --data adds" \
	"ALSO delete the bind-mounted data directory $vol_root" "$RUN_OUT"
assert_contains "the second prompt names the exact path it is about to delete" \
	"Delete $vol_root and everything under it" "$RUN_OUT"
assert_dir_absent "the bind-mounted data is gone after --data" "$vol_root"
assert_contains "clean --data reports the path it removed" "Removed $vol_root" "$RUN_OUT"
assert_contains "clean --data says how to recreate the directories the images need" "setup-env.sh" "$RUN_OUT"

# ------------------------------------------
# 3. `clean --data` with the second confirmation declined
# ------------------------------------------
tree="$(new_tree)"
seed_data "$tree"
vol_root="$tree/docker/volumes-dev"

run_sh "$tree" "yn" deploy.sh dev clean --data
assert_equal "declining the second confirmation is not an error" "0" "$RUN_RC"
assert_dir_present "declining the second confirmation keeps the data" "$vol_root"
assert_contains "the decline says the directory was kept" "Kept $vol_root" "$RUN_OUT"
assert_not_contains "a declined deletion is never reported as a removal" "Removed $vol_root" "$RUN_OUT"

# ------------------------------------------
# 4. An absolute VOLUMES_ROOT (production) is refused by --data
# ------------------------------------------
tree="$(new_tree)"
seed_data "$tree"
prod_root="$TMP_BASE/prod-volumes"
mkdir -p "$prod_root/postgres/lifecontrol/data/18"
printf 'COMPOSE_PROJECT_NAME=lifecontrol-test-prod\nVOLUMES_ROOT=%s\n' "$prod_root" \
	>"$tree/docker/.env.prod"

run_sh "$tree" "y" deploy.sh prod clean --data
assert_equal "clean --data on an absolute root is not an error" "0" "$RUN_RC"
assert_dir_present "an absolute VOLUMES_ROOT is never deleted by clean --data" "$prod_root"
assert_contains "the refusal names the variable and its value" "VOLUMES_ROOT=$prod_root" "$RUN_OUT"
assert_contains "the refusal says why it is refused" "outside the checkout" "$RUN_OUT"
assert_contains "the refusal says the data must be deleted by hand" "by hand" "$RUN_OUT"
assert_not_contains "a refused --data never promises the deletion" \
	"ALSO delete the bind-mounted data directory" "$RUN_OUT"
assert_contains "the surviving data is still named, with its absolute path" \
	"$prod_root/postgres" "$RUN_OUT"

# ------------------------------------------
# 4b. Only this environment's own directory is ever deleted
# ------------------------------------------
# VOLUMES_ROOT is interpolated into a mount source AND into the path an irreversible `rm -rf` uses, so
# it is validated as a path instead of trusted as one. Every value below is one typo away from an env
# file and resolves to something that must survive: docker/ itself, the checkout, the credential
# files, or another environment's live database.
refusal_matrix=". ./ .. ./../.. ./volumes-dev/.. ./secrets ./volumes-staging"
for value in $refusal_matrix; do
	tree="$(new_tree)"
	seed_data "$tree"
	mkdir -p "$tree/docker/secrets" "$tree/docker/volumes-staging/postgres/data"
	printf 'marker\n' >"$tree/docker/secrets/MARKER"
	printf 'COMPOSE_PROJECT_NAME=lifecontrol-test\nVOLUMES_ROOT=%s\n' "$value" >"$tree/docker/.env.dev"

	run_sh "$tree" "yy" deploy.sh dev clean --data
	assert_equal "clean --data on VOLUMES_ROOT=$value is not an error" "0" "$RUN_RC"
	for kept in docker/scripts/_common.sh docker/secrets/MARKER docker/volumes-dev/postgres \
		docker/volumes-staging/postgres; do
		if [ -e "$tree/$kept" ]; then
			pass "VOLUMES_ROOT=$value left $kept alone"
		else
			fail "VOLUMES_ROOT=$value deleted $kept"
		fi
	done
	assert_not_contains "VOLUMES_ROOT=$value is never reported as removed" "Removed " "$RUN_OUT"
	assert_contains "VOLUMES_ROOT=$value is refused by name" "is not this environment's data directory" "$RUN_OUT"
	assert_contains "the refusal names the directory this environment owns" "$tree/docker/volumes-dev" "$RUN_OUT"

	run_sh "$tree" "" cleanup.sh local dev
	assert_dir_present "cleanup.sh local refuses VOLUMES_ROOT=$value too" "$tree/docker/scripts"
	assert_contains "cleanup.sh local says why it refused" "Refusing to remove" "$RUN_OUT"
done

# A symlink named like the environment's directory is not the directory: `rm -rf` would delete the
# link and report a removal while the data survives at the target.
tree="$(new_tree)"
seed_data "$tree"
mkdir -p "$tree/docker/volumes-real/postgres"
ln -s "$tree/docker/volumes-real" "$tree/docker/volumes-dev-link"
printf 'COMPOSE_PROJECT_NAME=lifecontrol-test\nVOLUMES_ROOT=./volumes-dev-link\n' >"$tree/docker/.env.dev"
run_sh "$tree" "yy" deploy.sh dev clean --data
assert_dir_present "a symlinked VOLUMES_ROOT is never deleted through" "$tree/docker/volumes-real/postgres"
assert_dir_present "the symlink itself is left for its owner to remove" "$tree/docker/volumes-dev-link"
assert_not_contains "a symlinked root is never reported as removed" "Removed " "$RUN_OUT"

# A non-existent root says so instead of pretending the path is wrong.
tree="$(new_tree)"
printf 'COMPOSE_PROJECT_NAME=lifecontrol-test\nVOLUMES_ROOT=./volumes-nowhere\n' >"$tree/docker/.env.dev"
run_sh "$tree" "yy" deploy.sh dev clean --data
assert_contains "a root that does not exist is reported as missing" "does not exist" "$RUN_OUT"

# ------------------------------------------
# 5. Cancelling, a bad option, and a missing VOLUMES_ROOT
# ------------------------------------------
tree="$(new_tree)"
seed_data "$tree"

run_sh "$tree" "n" deploy.sh dev clean
assert_equal "cancelling the first prompt is not an error" "0" "$RUN_RC"
assert_equal "cancelling the first prompt leaves the Docker objects alone" "" "$RUN_LOG"
assert_contains "cancelling says so" "Cancelled" "$RUN_OUT"
assert_dir_present "cancelling keeps the data" "$tree/docker/volumes-dev"

run_sh "$tree" "y" deploy.sh dev clean --bogus
assert_equal "an unknown clean option is an error" "1" "$RUN_RC"
assert_contains "an unknown clean option names itself" "Unknown clean option: --bogus" "$RUN_OUT"
assert_equal "an unknown clean option removes nothing" "" "$RUN_LOG"

printf 'COMPOSE_PROJECT_NAME=lifecontrol-test\n' >"$tree/docker/.env.dev"
run_sh "$tree" "y" deploy.sh dev clean --data
assert_equal "clean --data without VOLUMES_ROOT is not an error" "0" "$RUN_RC"
assert_contains "clean --data says there is no directory to delete" \
	"VOLUMES_ROOT is not set" "$RUN_OUT"
assert_contains "the Docker half still ran" "down -v" "$RUN_LOG"

# EOF at the second prompt (Ctrl-D, a closed stdin) cancels; it must not abort silently under set -e.
tree="$(new_tree)"
seed_data "$tree"
run_sh "$tree" "y" deploy.sh dev clean --data
assert_equal "EOF at the confirmation is not an abort" "0" "$RUN_RC"
assert_contains "EOF at the confirmation is reported as a cancel" "Cancelled" "$RUN_OUT"
assert_dir_present "EOF at the confirmation keeps the data" "$tree/docker/volumes-dev"
assert_not_contains "one confirmed prompt never deletes the data" "Removed " "$RUN_OUT"

# The project name is named, and when it is missing the message says that instead of printing ''.
tree="$(new_tree)"
printf 'VOLUMES_ROOT=./volumes-dev\n' >"$tree/docker/.env.dev"
run_sh "$tree" "n" deploy.sh dev clean
assert_contains "a missing COMPOSE_PROJECT_NAME is named as missing" \
	"COMPOSE_PROJECT_NAME is not set in" "$RUN_OUT"
assert_not_contains "the project is never printed as an empty name" "project ''" "$RUN_OUT"

# ------------------------------------------
# 6. `cleanup.sh local` keeps its two verdicts through the shared helpers
# ------------------------------------------
tree="$(new_tree)"
seed_data "$tree"
vol_root="$tree/docker/volumes-dev"

run_sh "$tree" "" cleanup.sh local dev
assert_equal "cleanup.sh local exits 0 on a relative root" "0" "$RUN_RC"
assert_dir_absent "cleanup.sh local still removes the relative volume directory" "$vol_root"
assert_contains "cleanup.sh local names the directory it removed" "$vol_root" "$RUN_OUT"

tree="$(new_tree)"
prod_root="$TMP_BASE/prod-volumes-cleanup"
mkdir -p "$prod_root/postgres/lifecontrol/data"
printf 'COMPOSE_PROJECT_NAME=lifecontrol-test-prod\nVOLUMES_ROOT=%s\n' "$prod_root" \
	>"$tree/docker/.env.prod"

run_sh "$tree" "" cleanup.sh local prod
assert_equal "cleanup.sh local exits 0 on an absolute root" "0" "$RUN_RC"
assert_dir_present "cleanup.sh local still refuses to delete an absolute root" "$prod_root"
assert_contains "cleanup.sh local names the path it will not touch" "$prod_root" "$RUN_OUT"

# ------------------------------------------
# 7. The wiring, asserted statically
# ------------------------------------------
if grep -qF 'clean [--data]' "$DEPLOY_SH"; then
	pass "the help text documents the --data option of clean"
else
	fail "the help text does not document clean [--data]"
fi

if grep -qF 'clean_services "${3:-}"' "$DEPLOY_SH"; then
	pass "the clean dispatch passes the option through"
else
	fail "the clean dispatch drops the option"
fi

if grep -qF 'volumes_root_host_path' "$CLEANUP_SH"; then
	pass "cleanup.sh resolves the volume root through the shared helper"
else
	fail "cleanup.sh keeps its own copy of the volume-root resolution"
fi

# The premise the honest message rests on, asserted against the real compose file: the base stack
# declares no named volume, so `down -v` removes no data at all and every stateful directory a
# developer loses is a bind mount under VOLUMES_ROOT.
if grep -qE '^volumes:' "$DOCKER_DIR/docker-compose.yml"; then
	fail "docker-compose.yml declares a top-level volumes section: the bind-mount premise changed"
else
	pass "docker-compose.yml declares no named volume, so down -v removes no dev data"
fi

# ------------------------------------------
# Summary
# ------------------------------------------
echo ""
echo "=========================================="
echo "Results: $PASS_COUNT passed, $FAIL_COUNT failed"
echo "=========================================="

if [ "$FAIL_COUNT" -gt 0 ]; then
	exit 1
fi
exit 0
