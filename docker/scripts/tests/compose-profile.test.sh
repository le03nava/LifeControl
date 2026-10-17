#!/bin/bash
# ============================================
# LifeControl - compose runtime profile tests
# ============================================
# The API emits HTTP request and response content only on the `dev` profile, and
# `LogbookProfileGuard` refuses to start when the effective profile would be `dev` without
# `SPRING_PROFILES_ACTIVE` being declared. A compose default of `dev` therefore defeats the
# guard: the variable arrives declared, the guard passes, and every body reaches the log store
# outside dev. That was the state before this test existed (`SPRING_PROFILES_ACTIVE=${...:-dev}`
# on both services).
#
# The test pins two facts, in both directions:
#
#   1. the runtime env of the API and gateway services requires the variable (`:?`), and no
#      runtime `:-dev` fallback survives in docker/;
#   2. when the compose CLI is available, an undeclared variable really fails the interpolation
#      (exit != 0 with the "required variable" diagnostic) while a declared one resolves.
#
# CI note: the compose plugin is not always present, so the interpolation probe is skipped
# loudly when it is missing. The static assertion above is the check that never skips.
#
# No Docker daemon, no network, no Gradle.
#
# Run: ./docker/scripts/tests/compose-profile.test.sh
# Exits 0 only when every check passes.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
COMPOSE_FILE="$REPO_ROOT/docker/docker-compose.yml"
REQUIRED_TOKEN='SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE:?'
FALLBACK_TOKEN='SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE:-'

PASS_COUNT=0
FAIL_COUNT=0
SKIP_COUNT=0

pass() {
	printf 'PASS: %s\n' "$1"
	PASS_COUNT=$((PASS_COUNT + 1))
}

fail() {
	printf 'FAIL: %s\n' "$1"
	FAIL_COUNT=$((FAIL_COUNT + 1))
}

skip() {
	printf 'SKIP: %s\n' "$1"
	SKIP_COUNT=$((SKIP_COUNT + 1))
}

# check_equal <description> <expected> <actual>
check_equal() {
	if [ "$2" = "$3" ]; then
		pass "$1 ($3)"
	else
		fail "$1 (expected $2, got $3)"
	fi
}

# ------------------------------------------
# 1. The runtime env requires the variable, for both services that run Spring Boot.
# ------------------------------------------
if [ ! -f "$COMPOSE_FILE" ]; then
	fail "missing docker/docker-compose.yml"
else
	required_count="$(grep -c -F "$REQUIRED_TOKEN" "$COMPOSE_FILE")"
	check_equal "API and gateway services require SPRING_PROFILES_ACTIVE" "2" "$required_count"

	# Only runtime env entries count: `BUILD_PROFILE: ${SPRING_PROFILES_ACTIVE:-dev}` is a build
	# argument (deploy.sh passes it to Gradle and to the Angular configuration), and no Dockerfile
	# declares `ARG BUILD_PROFILE`, so it cannot reach a running service's profile. Anchoring on the
	# `- SPRING_PROFILES_ACTIVE=` entry also keeps this script from matching its own literals.
	fallback_count="$(grep -r -E --include='*.yml' --include='*.yaml' \
		'^[[:space:]]*-[[:space:]]*SPRING_PROFILES_ACTIVE=\$\{SPRING_PROFILES_ACTIVE:-' \
		"$REPO_ROOT/docker" | wc -l | tr -d ' ')"
	check_equal "no runtime entry falls back to dev in docker/" "0" "$fallback_count"
fi

# ------------------------------------------
# 2. An image must not bake the profile either: an image default is a declaration too, so the
#    guard accepts it and a plain `docker run` outside compose would log every body.
# ------------------------------------------
for dockerfile in "$REPO_ROOT/life-control-api/Dockerfile" "$REPO_ROOT/api-gateway/Dockerfile"; do
	if [ ! -f "$dockerfile" ]; then
		fail "missing ${dockerfile#"$REPO_ROOT/"}"
		continue
	fi
	if grep -q -F "$FALLBACK_TOKEN" "$dockerfile"; then
		fail "${dockerfile#"$REPO_ROOT/"} bakes a dev profile default"
	else
		pass "${dockerfile#"$REPO_ROOT/"} bakes no profile default"
	fi
done

# ------------------------------------------
# 3. The interpolation really refuses an undeclared variable, and really resolves a declared one.
#    Run on a copy in a scratch directory so no `.env` in the repository can supply the value.
# ------------------------------------------
if ! docker compose version >/dev/null 2>&1; then
	skip "docker compose is unavailable, the interpolation probe did not run"
else
	probe_dir="$(mktemp -d)"
	trap 'rm -rf "$probe_dir"' EXIT
	cp "$COMPOSE_FILE" "$probe_dir/docker-compose.yml"

	undeclared_output="$probe_dir/undeclared.txt"
	if (cd "$probe_dir" && env -u SPRING_PROFILES_ACTIVE VOLUMES_ROOT="$probe_dir/volumes" \
		docker compose -f docker-compose.yml --env-file /dev/null config) >"$undeclared_output" 2>&1; then
		fail "compose without SPRING_PROFILES_ACTIVE must not resolve a configuration"
	else
		if grep -q -F "required variable SPRING_PROFILES_ACTIVE" "$undeclared_output"; then
			pass "compose without SPRING_PROFILES_ACTIVE refuses with the required-variable diagnostic"
		else
			fail "compose failed without naming the required variable (see $undeclared_output)"
		fi
	fi

	declared_output="$probe_dir/declared.txt"
	if (cd "$probe_dir" && SPRING_PROFILES_ACTIVE=dev VOLUMES_ROOT="$probe_dir/volumes" \
		docker compose -f docker-compose.yml --env-file /dev/null config) >"$declared_output" 2>&1; then
		pass "compose with SPRING_PROFILES_ACTIVE declared resolves the configuration"
	else
		fail "compose with SPRING_PROFILES_ACTIVE declared must resolve (see $declared_output)"
	fi
fi

# ------------------------------------------
# Summary
# ------------------------------------------
echo "------------------------------------------"
printf 'compose-profile: %s passed, %s failed, %s skipped\n' "$PASS_COUNT" "$FAIL_COUNT" "$SKIP_COUNT"
if [ "$FAIL_COUNT" -ne 0 ]; then
	exit 1
fi
exit 0
