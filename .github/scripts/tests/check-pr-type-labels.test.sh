#!/bin/bash
# ============================================
# LifeControl - PR type label guard tests
# ============================================
# The guard answers one question about one pull request: does the label set carry
# exactly one `type:*` label, and is that label a member of the declared vocabulary?
#
# It is tested here instead of only inside the workflow because a gate that is wrong in
# the permissive direction accepts every PR silently, and a gate that is wrong in the
# strict direction blocks every PR at once. Neither failure is visible in a green run.
#
# The cases below are the rule's own cases, not invented ones: zero labels, two labels,
# one label that is not in the vocabulary, and a label set made only of orthogonal
# labels (area/triage) that must not be counted as an answer.
#
# No network, no `gh`, no Docker: the script is a pure function of its input.
#
# Run: ./.github/scripts/tests/check-pr-type-labels.test.sh
# Exits 0 only when every case passes.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GUARD="$SCRIPT_DIR/../check-pr-type-labels.sh"

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

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

# run_file <name> <label file> — feeds one label per line through --labels-file
run_file() {
	"$GUARD" --labels-file "$2" 2>&1
}

# expect_exit <description> <expected code> <actual code>
expect_exit() {
	if [ "$2" = "$3" ]; then
		pass "$1 (exit $3)"
	else
		fail "$1 (expected exit $2, got $3)"
	fi
}

# expect_contains <description> <needle> <haystack>
expect_contains() {
	case "$3" in
	*"$2"*) pass "$1 (mentions '$2')" ;;
	*) fail "$1 (expected '$2' in: $(printf '%s' "$3" | tr '\n' ' '))" ;;
	esac
}

if [ ! -x "$GUARD" ]; then
	fail "guard exists and is executable ($GUARD)"
	printf '\n%d passed, %d failed\n' "$PASS_COUNT" "$FAIL_COUNT"
	exit 1
fi

# ---------------------------------------------------------------- zero type:* labels
printf 'documentation\njava\n' >"$TMP_DIR/orthogonal.txt"
output="$(run_file "orthogonal labels only" "$TMP_DIR/orthogonal.txt")"
code=$?
expect_exit "a label set of orthogonal labels only is a failure" 1 "$code"
expect_contains "the failure names the missing namespace" "no type:* label" "$output"
expect_contains "the failure lists the vocabulary" "type:docs" "$output"

: >"$TMP_DIR/empty.txt"
output="$(run_file "empty label set" "$TMP_DIR/empty.txt")"
code=$?
expect_exit "an empty label set is a failure, never a pass" 1 "$code"
expect_contains "the empty set is reported as missing" "no type:* label" "$output"

# -------------------------------------------------------------- exactly one, in the set
printf 'type:docs\n' >"$TMP_DIR/one.txt"
output="$(run_file "one declared label" "$TMP_DIR/one.txt")"
code=$?
expect_exit "exactly one declared type:* label passes" 0 "$code"
expect_contains "the pass names the label" "type:docs" "$output"

printf 'java\ntype:breaking-change\ndependencies\n' >"$TMP_DIR/one-with-area.txt"
output="$(run_file "one declared label beside area labels" "$TMP_DIR/one-with-area.txt")"
code=$?
expect_exit "area and triage labels do not count as a second answer" 0 "$code"
expect_contains "the pass names the type label" "type:breaking-change" "$output"

# ------------------------------------------------------------------ two answers is zero
printf 'type:docs\ntype:chore\n' >"$TMP_DIR/two.txt"
output="$(run_file "two declared labels" "$TMP_DIR/two.txt")"
code=$?
expect_exit "two type:* labels fail" 1 "$code"
expect_contains "the failure counts them" "2 type:* labels" "$output"
expect_contains "the failure names the first" "type:docs" "$output"
expect_contains "the failure names the second" "type:chore" "$output"

# ------------------------------------------------------------------- vocabulary, not prefix
printf 'type:feat\n' >"$TMP_DIR/synonym.txt"
output="$(run_file "a synonym outside the vocabulary" "$TMP_DIR/synonym.txt")"
code=$?
expect_exit "a type:* label outside the vocabulary fails" 1 "$code"
expect_contains "the failure says it is unknown" "unknown type label 'type:feat'" "$output"
expect_contains "the failure shows the declared vocabulary" "type:feature" "$output"

# ------------------------------------------------------------- the two inputs the caller has
output="$("$GUARD" --labels-json '["java","type:bug"]' 2>&1)"
code=$?
expect_exit "a JSON array input is accepted" 0 "$code"
expect_contains "the JSON pass names the label" "type:bug" "$output"

output="$("$GUARD" --labels-json '[]' 2>&1)"
code=$?
expect_exit "an empty JSON array is a failure" 1 "$code"
expect_contains "the empty JSON array is reported as missing" "no type:* label" "$output"

output="$(printf 'type:refactor\n' | "$GUARD" 2>&1)"
code=$?
expect_exit "labels may arrive on stdin" 0 "$code"
expect_contains "the stdin pass names the label" "type:refactor" "$output"

output="$("$GUARD" --labels-file "$TMP_DIR/does-not-exist.txt" 2>&1)"
code=$?
expect_exit "an unreadable label file fails closed" 1 "$code"

output="$("$GUARD" --labels-json 'not json' 2>&1)"
code=$?
expect_exit "a malformed JSON payload fails closed" 1 "$code"

output="$("$GUARD" --labels-file "$TMP_DIR/one.txt" --labels-json '["type:docs"]' 2>&1)"
code=$?
expect_exit "two label sources at once fail closed" 1 "$code"

output="$("$GUARD" --no-such-flag 2>&1)"
code=$?
expect_exit "an unknown flag fails closed" 1 "$code"

printf '\n%d passed, %d failed\n' "$PASS_COUNT" "$FAIL_COUNT"
[ "$FAIL_COUNT" -eq 0 ]
