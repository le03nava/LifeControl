#!/bin/bash
# ============================================
# LifeControl - Build artifact freshness guard tests
# ============================================
# Self-contained tests for verify_artifact_freshness (docker/scripts/_common.sh).
# No Docker, no Gradle, no network: every case builds a throwaway tree under
# mktemp -d and removes it on exit.
#
# Run: ./docker/scripts/tests/freshness-guard.test.sh
# Exits 0 only when every case passes.

# No `set -e`: the cases assert non-zero returns, so each return code is
# captured explicitly instead. `set -u` keeps the test honest about typos.
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../_common.sh
source "$SCRIPT_DIR/../_common.sh"

PASS_COUNT=0
FAIL_COUNT=0

# Every case's throwaway tree lives under this single mktemp -d base. The EXIT
# trap can then remove all of them even though new_root runs in a command
# substitution: that is a subshell, so registering paths in an array there
# would be lost before cleanup runs.
TMP_BASE="$(mktemp -d)"

cleanup() {
	if [ -n "${TMP_BASE:-}" ] && [ -d "$TMP_BASE" ]; then
		rm -rf "$TMP_BASE"
	fi
}
trap cleanup EXIT

# new_root -> echoes a fresh temporary directory inside TMP_BASE.
new_root() {
	mktemp -d "$TMP_BASE/case.XXXXXX"
}

# Initialize a repository whose commits do not depend on the host git config.
git_init() {
	git -C "$1" init -q
	git -C "$1" config user.email test@example.com
	git -C "$1" config user.name "Freshness Guard Test"
}

# Run the guard and capture both its status and its combined output.
# Sets GUARD_RC and GUARD_OUT.
run_guard() {
	GUARD_OUT="$(verify_artifact_freshness "$@" 2>&1)"
	GUARD_RC=$?
}

# record <name> <expected_rc> <actual_rc> <output>
record() {
	local name="$1" expected="$2" actual="$3" output="$4"
	if [ "$actual" -eq "$expected" ]; then
		printf 'PASS: %s (rc=%s)\n' "$name" "$actual"
		PASS_COUNT=$((PASS_COUNT + 1))
	else
		printf 'FAIL: %s (expected rc=%s, got rc=%s)\n' "$name" "$expected" "$actual"
		printf '%s\n' "$output" | sed 's/^/        /'
		FAIL_COUNT=$((FAIL_COUNT + 1))
	fi
}

# assert_contains <name> <needle> <haystack>
assert_contains() {
	local name="$1" needle="$2" haystack="$3"
	if printf '%s' "$haystack" | grep -qF -- "$needle"; then
		printf 'PASS: %s\n' "$name"
		PASS_COUNT=$((PASS_COUNT + 1))
	else
		printf 'FAIL: %s (missing output: %s)\n' "$name" "$needle"
		printf '%s\n' "$haystack" | sed 's/^/        /'
		FAIL_COUNT=$((FAIL_COUNT + 1))
	fi
}

JAR_REL="svc/build/libs/svc-0.0.1-SNAPSHOT.jar"

# ------------------------------------------
# Case 1: artifact missing entirely -> 1
# ------------------------------------------
root="$(new_root)"
run_guard "svc" "$JAR_REL" "$root"
record "case 1: missing artifact returns 1" 1 "$GUARD_RC" "$GUARD_OUT"

# ------------------------------------------
# Case 2: artifact exists but is zero bytes -> 1
# ------------------------------------------
root="$(new_root)"
mkdir -p "$root/svc/build/libs"
: >"$root/$JAR_REL"
run_guard "svc" "$JAR_REL" "$root"
record "case 2: empty artifact returns 1" 1 "$GUARD_RC" "$GUARD_OUT"

# ------------------------------------------
# Case 3: a scanned source is newer than the artifact -> 1
# (git repo without commits, so only the mtime check can fire)
# ------------------------------------------
root="$(new_root)"
git_init "$root"
mkdir -p "$root/svc/build/libs"
printf 'fake jar' >"$root/$JAR_REL"
touch -d '1 day ago' "$root/$JAR_REL"
mkdir -p "$root/svc/src/main/java"
printf 'class A {}\n' >"$root/svc/src/main/java/A.java"
run_guard "svc" "$JAR_REL" "$root"
record "case 3: source newer than artifact returns 1" 1 "$GUARD_RC" "$GUARD_OUT"

# ------------------------------------------
# Case 4: artifact newer than every source, but the last commit touching the
# module is newer than the artifact -> 1 (only the commit check can fire)
# ------------------------------------------
root="$(new_root)"
git_init "$root"
mkdir -p "$root/svc/src/main/java" "$root/svc/build/libs"
printf 'class A {}\n' >"$root/svc/src/main/java/A.java"
touch -d '3 days ago' "$root/svc/src/main/java/A.java"
printf 'fake jar' >"$root/$JAR_REL"
touch -d '2 days ago' "$root/$JAR_REL"
git -C "$root" add -f svc
git -C "$root" commit -qm "add svc module"
run_guard "svc" "$JAR_REL" "$root"
record "case 4: commit newer than artifact returns 1" 1 "$GUARD_RC" "$GUARD_OUT"

# ------------------------------------------
# Case 5: sources backdated, commit made, artifact created last -> 0
# ------------------------------------------
root="$(new_root)"
git_init "$root"
mkdir -p "$root/svc/src/main/java" "$root/svc/build/libs"
printf 'class A {}\n' >"$root/svc/src/main/java/A.java"
touch -d '3 days ago' "$root/svc/src/main/java/A.java"
git -C "$root" add -f svc
git -C "$root" commit -qm "add svc module"
printf 'fake jar' >"$root/$JAR_REL"
run_guard "svc" "$JAR_REL" "$root"
record "case 5: consistent tree returns 0" 0 "$GUARD_RC" "$GUARD_OUT"

# ------------------------------------------
# Case 6: non-git directory with a fresh artifact -> 0 (plus a warning)
# ------------------------------------------
root="$(new_root)"
mkdir -p "$root/svc/src/main/java" "$root/svc/build/libs"
printf 'class A {}\n' >"$root/svc/src/main/java/A.java"
touch -d '3 days ago' "$root/svc/src/main/java/A.java"
printf 'fake jar' >"$root/$JAR_REL"
run_guard "svc" "$JAR_REL" "$root"
record "case 6: non-git fresh artifact returns 0" 0 "$GUARD_RC" "$GUARD_OUT"
assert_contains "case 6: warns that the commit check is unavailable" \
	"Sin repositorio git" "$GUARD_OUT"

# ------------------------------------------
# Case 7: non-git directory with a stale artifact -> 1
# (the mtime check must still apply with no git)
# ------------------------------------------
root="$(new_root)"
mkdir -p "$root/svc/build/libs"
printf 'fake jar' >"$root/$JAR_REL"
touch -d '3 days ago' "$root/$JAR_REL"
mkdir -p "$root/svc/src/main/java"
printf 'class A {}\n' >"$root/svc/src/main/java/A.java"
run_guard "svc" "$JAR_REL" "$root"
record "case 7: non-git stale artifact returns 1" 1 "$GUARD_RC" "$GUARD_OUT"

# ------------------------------------------
# Summary
# ------------------------------------------
echo "------------------------------------------"
printf 'freshness-guard: %s passed, %s failed\n' "$PASS_COUNT" "$FAIL_COUNT"
if [ "$FAIL_COUNT" -ne 0 ]; then
	exit 1
fi
exit 0
