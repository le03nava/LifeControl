#!/bin/bash
# ============================================
# LifeControl - Dockerfile hardening tests
# ============================================
# Two properties of the two Java images, both of them invisible to any other suite:
#
#   1. Java is PID 1. `api-gateway/Dockerfile` shipped `ENTRYPOINT ["sh","-c","java $JAVA_OPTS …"]`
#      with no `exec`, so the shell stayed PID 1, did not forward SIGTERM, and every deploy waited
#      out the stop timeout before SIGKILL. The API module already did it with `exec java …`.
#   2. The build context. Neither module had a `.dockerignore`, so the whole `build/` tree was
#      uploaded on every build.
#
# The second property has a trap this suite exists to keep: the Dockerfile gets its JAR from
# `${JAR_FILE}`, which lives *inside* `build/` (`build/libs/<module>-<version>.jar`). A
# `.dockerignore` that excludes `build/` without re-including that one directory makes `COPY` fail
# and the image unbuildable, so the test asserts the exception pattern instead of the directory
# name. "The build context no longer contains `build/`" is not satisfiable while the JAR lives
# there; shrinking the context to the JAR is.
#
# No Docker daemon, no network, no Gradle: static checks over the four files.
#
# Run: ./docker/scripts/tests/dockerfile-hardening.test.sh
# Exits 0 only when every check passes.

# No `set -e`: the cases assert return codes, so each is captured explicitly.
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../_common.sh
source "$SCRIPT_DIR/../_common.sh"

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

# assert_contains <name> <needle> <haystack>
assert_contains() {
	if printf '%s' "$3" | grep -qF -- "$2"; then
		pass "$1"
	else
		fail "$1 (missing output: $2)"
		printf '%s\n' "$3" | sed 's/^/        /'
	fi
}

# The two modules that build a Java image from a pre-built JAR.
JAVA_MODULES=(
	api-gateway
	life-control-api
)

# ------------------------------------------
# Java is PID 1: the entrypoint execs it
# ------------------------------------------
for module in "${JAVA_MODULES[@]}"; do
	dockerfile="$REPO_ROOT/$module/Dockerfile"
	entrypoint_line="$(grep -m1 '^ENTRYPOINT' "$dockerfile" || true)"

	assert_contains "$module: the entrypoint execs Java so it is PID 1" "exec java" "$entrypoint_line"
	check_equal "$module: the entrypoint leaves a shell in front of Java" "0" \
		"$(printf '%s' "$entrypoint_line" | grep -c '\["sh", "-c", "java' || true)"
done

# ------------------------------------------
# No Dockerfile hides a failed package install
# ------------------------------------------
# `RUN apk add --no-cache curl || true` is how the gateway shipped a dependency nobody used: the
# same file's HEALTHCHECK probes with `wget`. A failed install is a build that should fail.
for module in "${JAVA_MODULES[@]}"; do
	dockerfile="$REPO_ROOT/$module/Dockerfile"
	hidden="$(grep -cE '^[[:space:]]*RUN[[:space:]].*(apk|apt-get|yum|dnf)[[:space:]].*\|\|[[:space:]]*true' "$dockerfile" || true)"
	check_equal "$module: no package install is hidden behind || true" "0" "$hidden"
done

# ------------------------------------------
# The build context is shrunk, not broken
# ------------------------------------------
for module in "${JAVA_MODULES[@]}"; do
	ignore_file="$REPO_ROOT/$module/.dockerignore"

	if [ ! -f "$ignore_file" ]; then
		fail "$module: ships a .dockerignore"
		continue
	fi
	pass "$module: ships a .dockerignore"

	ignore_body="$(cat "$ignore_file")"

	# The local Gradle cache and the intermediate output are the bulk of the context.
	assert_contains "$module: the ignore file excludes the Gradle cache" "/.gradle/" "$ignore_body"
	assert_contains "$module: the ignore file excludes the build tree" "/build/*" "$ignore_body"

	# The trap: the Dockerfile gets its JAR from `build/libs/`. Excluding the tree without
	# re-including that JAR makes `COPY ${JAR_FILE}` fail at build time.
	assert_contains "$module: the ignore file re-includes the JAR directory" "!/build/libs/" "$ignore_body"
	assert_contains "$module: the ignore file re-includes the JAR" "!/build/libs/*.jar" "$ignore_body"

	# A bare `build/` line excludes the JAR along with the tree.
	check_equal "$module: the ignore file never excludes the JAR's own tree outright" "0" \
		"$(printf '%s\n' "$ignore_body" | grep -cE '^/?build/?$' || true)"

	# And the JAR the Dockerfile copies has to be the one the ignore file keeps.
	jar_file="$(grep -m1 '^ARG JAR_FILE=' "$dockerfile" | cut -d= -f2- || true)"
	assert_contains "$module: the copied JAR lives under the re-included directory" "build/libs/" "$jar_file"
done

# ------------------------------------------
# Summary
# ------------------------------------------
echo "------------------------------------------"
printf 'dockerfile-hardening: %s passed, %s failed\n' "$PASS_COUNT" "$FAIL_COUNT"
if [ "$FAIL_COUNT" -ne 0 ]; then
	exit 1
fi
exit 0
