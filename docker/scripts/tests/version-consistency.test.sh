#!/bin/bash
# ============================================
# LifeControl - JAR version consistency tests
# ============================================
# The JAR version is declared in several places that all have to agree:
#
#   - build.gradle in each Java module (what Gradle actually names the JAR)
#   - the APP_VERSION build arg default in the compose files
#   - the ARG APP_VERSION default in each Java Dockerfile
#   - the fallback inside resolve_jar_version() in docker/scripts/deploy.sh
#
# When they drift, the Dockerfile COPYs a file that does not exist and the image
# cannot be built at all. That is exactly what happened in prod: the prod compose
# override defaulted APP_VERSION to 1.0.0 while Gradle only ever produces
# 0.0.1-SNAPSHOT, so prod looked for api-gateway-1.0.0.jar.
#
# No Docker, no Gradle, no network: this is a static consistency check.
#
# Run: ./docker/scripts/tests/version-consistency.test.sh
# Exits 0 only when every check passes.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

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

# check_equal <description> <expected> <actual>
check_equal() {
	if [ "$2" = "$3" ]; then
		pass "$1 ($3)"
	else
		fail "$1 (expected $2, got $3)"
	fi
}

JAVA_MODULES=(api-gateway life-control-api)

# ------------------------------------------
# 1. The modules must agree with each other, so one canonical version exists.
# ------------------------------------------
declare -A MODULE_VERSION
for module in "${JAVA_MODULES[@]}"; do
	gradle_file="$REPO_ROOT/$module/build.gradle"
	if [ ! -f "$gradle_file" ]; then
		fail "missing $module/build.gradle"
		continue
	fi
	# version = '0.0.1-SNAPSHOT'
	MODULE_VERSION[$module]="$(sed -n "s/^version[[:space:]]*=[[:space:]]*['\"]\(.*\)['\"].*/\1/p" "$gradle_file" | head -1)"
	if [ -z "${MODULE_VERSION[$module]}" ]; then
		fail "could not read version from $module/build.gradle"
	fi
done

CANONICAL="${MODULE_VERSION[${JAVA_MODULES[0]}]:-}"
for module in "${JAVA_MODULES[@]:1}"; do
	check_equal "module versions agree ($module vs ${JAVA_MODULES[0]})" "$CANONICAL" "${MODULE_VERSION[$module]:-}"
done

if [ -z "$CANONICAL" ]; then
	printf '%s\n' "------------------------------------------"
	printf 'version-consistency: cannot continue without a canonical version\n'
	exit 1
fi

# ------------------------------------------
# 2. Every compose APP_VERSION default must match the canonical version.
#    Each compose file that declares one is checked, so an environment-specific
#    override cannot silently disagree with the others.
# ------------------------------------------
compose_defaults=0
while IFS= read -r line; do
	file="${line%%:*}"
	# APP_VERSION: ${APP_VERSION:-1.0.0}
	# shellcheck disable=SC2016  # the compose default is matched literally, so it must not expand
	value="$(printf '%s' "$line" | sed -n 's/.*APP_VERSION:[[:space:]]*\${APP_VERSION:-\([^}]*\)}.*/\1/p')"
	if [ -z "$value" ]; then
		# A literal (non-interpolated) value is just as wrong when it drifts.
		value="$(printf '%s' "$line" | sed -n 's/.*APP_VERSION:[[:space:]]*\([^$#][^ ]*\).*/\1/p')"
	fi
	compose_defaults=$((compose_defaults + 1))
	check_equal "compose APP_VERSION default in $(basename "$file")" "$CANONICAL" "$value"
done < <(grep -rn "APP_VERSION:" "$REPO_ROOT"/docker/docker-compose*.yml 2>/dev/null)

if [ "$compose_defaults" -eq 0 ]; then
	fail "no compose APP_VERSION default found (expected at least the base file)"
fi

# ------------------------------------------
# 3. Each Java Dockerfile ARG default must match, and its JAR_FILE must name the
#    file Gradle produces for that module.
# ------------------------------------------
for module in "${JAVA_MODULES[@]}"; do
	dockerfile="$REPO_ROOT/$module/Dockerfile"
	if [ ! -f "$dockerfile" ]; then
		fail "missing $module/Dockerfile"
		continue
	fi
	arg_default="$(sed -n 's/^ARG APP_VERSION=\(.*\)$/\1/p' "$dockerfile" | head -1)"
	check_equal "Dockerfile ARG APP_VERSION default in $module/Dockerfile" "$CANONICAL" "$arg_default"

	# ARG JAR_FILE=build/libs/api-gateway-${APP_VERSION}.jar
	jar_pattern="$(sed -n 's/^ARG JAR_FILE=\(.*\)$/\1/p' "$dockerfile" | head -1)"
	expected_pattern="build/libs/$module-\${APP_VERSION}.jar"
	check_equal "Dockerfile JAR_FILE path in $module/Dockerfile" "$expected_pattern" "$jar_pattern"
done

# ------------------------------------------
# 4. The deploy guard must resolve the same version, or it would verify a
#    different file from the one the Dockerfile COPYs.
# ------------------------------------------
DEPLOY_SH="$REPO_ROOT/docker/scripts/deploy.sh"
if [ ! -f "$DEPLOY_SH" ]; then
	fail "missing docker/scripts/deploy.sh"
else
	# jar_version="0.0.1-SNAPSHOT"
	fallback="$(sed -n 's/^[[:space:]]*jar_version="\([^$]*\)"$/\1/p' "$DEPLOY_SH" | head -1)"
	check_equal "deploy.sh resolve_jar_version fallback" "$CANONICAL" "$fallback"
fi

# ------------------------------------------
# Summary
# ------------------------------------------
echo "------------------------------------------"
printf 'version-consistency: %s passed, %s failed\n' "$PASS_COUNT" "$FAIL_COUNT"
if [ "$FAIL_COUNT" -ne 0 ]; then
	exit 1
fi
exit 0
