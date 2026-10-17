#!/bin/bash
# ============================================
# LifeControl - label enforcement artifacts, wired
# ============================================
# The rule lives in three versioned artifacts that must agree with each other:
#
#   .github/PULL_REQUEST_TEMPLATE.md      the six checkboxes a contributor ticks
#   .github/workflows/pr-label-guard.yml  the gate that reads the label, not the checkbox
#   .github/scripts/check-pr-type-labels.sh  the vocabulary the gate accepts
#
# Two failures are only visible here, because each artifact is valid on its own:
#
#   1. A seventh `type:*` label added to one side and not the other — the same drift shape
#      that docker/scripts/tests/image-pins.test.sh exists to catch for image pins.
#   2. A `paths:` filter added to the guard workflow. That is not a detail: this
#      repository's other four workflows all nest one under `pull_request`, which is why a
#      markdown-only PR runs zero jobs, and `docs` is the second largest prefix in the
#      history. A filtered guard would be blind exactly where the rule is forgotten.
#
# No network, no `gh`, no Docker.
#
# Run: ./.github/scripts/tests/label-artifacts.test.sh
# Exits 0 only when every case passes.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

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

# The template the label pairs with must offer exactly the vocabulary the guard accepts: a
# seventh `type:*` label added to one and not the other is the drift described above.
TEMPLATE="$SCRIPT_DIR/../../PULL_REQUEST_TEMPLATE.md"
WORKFLOW="$SCRIPT_DIR/../../workflows/pr-label-guard.yml"

if [ -r "$TEMPLATE" ]; then
	pass "the PR template exists"
	checkboxes="$(grep -c '^- \[ \] .*(`type:' "$TEMPLATE")"
	if [ "$checkboxes" -eq 6 ]; then
		pass "the template offers exactly 6 type:* checkboxes"
	else
		fail "the template offers exactly 6 type:* checkboxes (got $checkboxes)"
	fi
	for name in type:bug type:feature type:docs type:refactor type:chore type:breaking-change; do
		if grep -q "(\`$name\`)" "$TEMPLATE"; then
			pass "the template offers $name"
		else
			fail "the template offers $name"
		fi
	done
else
	fail "the PR template exists ($TEMPLATE)"
fi

if [ -r "$WORKFLOW" ]; then
	pass "the guard workflow exists"
	if grep -Eq '^[[:space:]]+paths:' "$WORKFLOW"; then
		fail "the guard workflow carries no paths filter, so it runs on markdown-only pull requests"
	else
		pass "the guard workflow carries no paths filter, so it runs on markdown-only pull requests"
	fi
	if grep -q 'check-pr-type-labels.sh' "$WORKFLOW"; then
		pass "the workflow runs the guard script"
	else
		fail "the workflow runs the guard script"
	fi
	if grep -q 'labeled' "$WORKFLOW"; then
		pass "adding the missing label clears the check, with no extra commit"
	else
		fail "adding the missing label clears the check, with no extra commit"
	fi
else
	fail "the guard workflow exists ($WORKFLOW)"
fi

printf '\n%d passed, %d failed\n' "$PASS_COUNT" "$FAIL_COUNT"
[ "$FAIL_COUNT" -eq 0 ]
