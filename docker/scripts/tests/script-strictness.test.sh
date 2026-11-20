#!/bin/bash
# ============================================
# LifeControl - script strictness tests
# ============================================
# Four of the environment scripts declare `set -e` only, while the newer ones (`db-roles.sh`, both
# preflight reports, the entrypoint wrappers) already declare `set -euo pipefail`. The difference is
# not cosmetic:
#
#   * without `-u` an unset variable expands to empty, and the destructive paths guard themselves
#     with helper checks — `volumes_root_is_deletable` builds the path it compares against from
#     `$ENV`, so an unset `ENV` produced `volumes-` and not a refusal;
#   * without `pipefail` a failing command inside a pipe is invisible, which is how a report prints
#     an empty list and exits 0.
#
# This suite asserts the **behaviour of each script's own option line**, not the presence of a
# literal: it extracts the line the script declares and runs probes under it, so the test cannot
# pass for a script whose flags were edited into something weaker. A flag list that only exists in
# the test would prove nothing.
#
# No Docker daemon, no network, no Gradle: five probes per script on `bash -c`.
#
# Run: ./docker/scripts/tests/script-strictness.test.sh
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

# assert_not_contains <name> <needle> <haystack>
assert_not_contains() {
	if printf '%s' "$3" | grep -qF -- "$2"; then
		fail "$1 (unexpected output: $2)"
		printf '%s\n' "$3" | sed 's/^/        /'
	else
		pass "$1"
	fi
}

STRICT_SCRIPTS=(
	setup-env.sh
	deploy.sh
	cleanup.sh
	validate-env.sh
)

# ------------------------------------------
# Each script declares the full option set
# ------------------------------------------
for script in "${STRICT_SCRIPTS[@]}"; do
	path="$DOCKER_DIR/scripts/$script"
	check_equal "$script exists" "yes" "$([ -f "$path" ] && echo yes || echo no)"

	option_line="$(grep -m1 '^set ' "$path" || true)"
	check_equal "$script declares the full strict option set" "set -euo pipefail" "$option_line"
	check_equal "$script still parses" "0" "$(bash -n "$path" 2>/dev/null; echo $?)"
done

# ------------------------------------------
# The behaviour that option line buys
# ------------------------------------------
# Every probe runs under the line the script itself declares, so a script whose flags regress makes
# its own probe fail.
for script in "${STRICT_SCRIPTS[@]}"; do
	option_line="$(grep -m1 '^set ' "$DOCKER_DIR/scripts/$script" || true)"

	# 1. An unset variable aborts instead of expanding to empty.
	unset_out="$(bash -c "$option_line"'
		echo "VALUE=[${SCRIPT_STRICTNESS_PROBE_UNSET}]"
	' 2>&1)"
	unset_rc=$?
	check_equal "$script: an unset variable exits non-zero" "nonzero" "$([ "$unset_rc" -ne 0 ] && echo nonzero || echo zero)"
	assert_not_contains "$script: an unset variable never reaches the line as empty" "VALUE=[]" "$unset_out"

	# 2. A failing command inside a pipe fails the pipeline.
	pipe_out="$(bash -c "$option_line"'
		false | true
		echo PIPE_SURVIVED
	' 2>&1)"
	pipe_rc=$?
	check_equal "$script: a failing command in a pipe exits non-zero" "nonzero" "$([ "$pipe_rc" -ne 0 ] && echo nonzero || echo zero)"
	assert_not_contains "$script: a failing pipeline does not continue" "PIPE_SURVIVED" "$pipe_out"

	# 3. A healthy pipeline is not turned into a failure: the probe guards against an option set so
	#    eager that it breaks the scripts it hardens.
	ok_out="$(bash -c "$option_line"'
		true | true
		echo PIPELINE_OK
	' 2>&1)"
	ok_rc=$?
	check_equal "$script: a healthy pipeline still exits zero" "0" "$ok_rc"
	assert_contains "$script: a healthy pipeline still runs" "PIPELINE_OK" "$ok_out"
done

# ------------------------------------------
# The deletion guard fails closed
# ------------------------------------------
# `cleanup.sh --data` resolves the directory it may delete through `volumes_root_env_dir_name`,
# which formats `$ENV`. With `-u` an unset `ENV` is a loud abort; without it, the name was
# `volumes-` and the comparison decided on a path nobody declared. Asserted through the shared
# helper, which is where the path is built.
guard_out="$(bash -c '
	set -euo pipefail
	source "$1"
	unset ENV
	volumes_root_env_dir_name
	echo "GUARD_RETURNED=[$(volumes_root_env_dir_name 2>/dev/null)]"
' _ "$DOCKER_DIR/scripts/_common.sh" 2>&1)"
guard_rc=$?
check_equal "the deletion path aborts when the environment is unset" "nonzero" \
	"$([ "$guard_rc" -ne 0 ] && echo nonzero || echo zero)"
assert_not_contains "the deletion path never builds a name from an unset environment" "GUARD_RETURNED=" "$guard_out"

# And with the environment declared, it still answers — the guard is not broken into always-refusing.
declared_out="$(bash -c '
	set -euo pipefail
	source "$1"
	ENV=dev
	volumes_root_env_dir_name
' _ "$DOCKER_DIR/scripts/_common.sh" 2>&1)"
check_equal "the deletion path still names the environment directory" "volumes-dev" "$declared_out"

# ------------------------------------------
# Summary
# ------------------------------------------
echo "------------------------------------------"
printf 'script-strictness: %s passed, %s failed\n' "$PASS_COUNT" "$FAIL_COUNT"
if [ "$FAIL_COUNT" -ne 0 ]; then
	exit 1
fi
exit 0
