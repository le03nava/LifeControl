#!/bin/bash
# ============================================
# LifeControl - retro-label planner tests
# ============================================
# The planner decides, for every merged pull request, which single `type:*` label its own
# conventional-commit prefix maps to, and which label must therefore be added or removed.
# It touched 328 of 341 merged PRs when it first ran, so a wrong mapping is not a detail:
# it is 328 wrong labels that no later review reads again.
#
# It is tested offline, against a stubbed `gh`, for the same reason: the plan has to be
# verifiable before it is executed, and the execution's exact argv has to be asserted
# rather than assumed.
#
# The cases are the mapping's own cases (each fold in the commit-type table, the `!`
# marker, a second `type:*` label), the fail-closed case (a title outside conventional
# commits must stop the run, never be skipped silently) and the idempotent case (nothing
# to do means no `gh` call at all).
#
# Run: ./.github/scripts/tests/retro-label-prs.test.sh
# Exits 0 only when every case passes.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLANNER="$SCRIPT_DIR/../retro-label-prs.sh"

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

GH_LOG="$TMP_DIR/gh.log"
export GH_LOG
mkdir -p "$TMP_DIR/bin"
cat >"$TMP_DIR/bin/gh" <<'STUB'
#!/bin/bash
printf '%s\n' "$*" >>"$GH_LOG"
exit 0
STUB
chmod +x "$TMP_DIR/bin/gh"
GH="$TMP_DIR/bin/gh"

if [ ! -x "$PLANNER" ]; then
	fail "planner exists and is executable ($PLANNER)"
	printf '\n%d passed, %d failed\n' "$PASS_COUNT" "$FAIL_COUNT"
	exit 1
fi

# plan <fixture file> [apply] — prints the planner output, records gh argv in $GH_LOG
plan() {
	local args=(--prs-file "$1" --gh "$GH")
	[ "$#" -ge 2 ] && args+=(--apply)
	: >"$GH_LOG"
	"$PLANNER" "${args[@]}" 2>&1
}

expect_exit() {
	if [ "$2" = "$3" ]; then
		pass "$1 (exit $3)"
	else
		fail "$1 (expected exit $2, got $3)"
	fi
}

expect_line() {
	if printf '%s\n' "$3" | grep -qx -- "$2"; then
		pass "$1"
	else
		fail "$1 (expected exact line '$2' in: $(printf '%s' "$3" | tr '\n' '|'))"
	fi
}

expect_no_line_containing() {
	if printf '%s\n' "$3" | grep -q -- "$2"; then
		fail "$1 (did not expect '$2' in: $(printf '%s' "$3" | tr '\n' '|'))"
	else
		pass "$1"
	fi
}

# -------------------------------------------------------------------- mapping fixtures
cat >"$TMP_DIR/mapping.json" <<'JSON'
[
  {"number": 1,  "title": "docs(odd): record the merge",         "labels": []},
  {"number": 2,  "title": "chore(deps): bump the runner",        "labels": [{"name": "type:chore"}]},
  {"number": 3,  "title": "docs(odd): record the merge",         "labels": [{"name": "documentation"}, {"name": "type:chore"}]},
  {"number": 4,  "title": "refactor(product)!: split variants",  "labels": []},
  {"number": 5,  "title": "fix(docker): read the admin API",     "labels": [{"name": "bug"}]},
  {"number": 6,  "title": "feat(hr): add the menu entry",        "labels": []},
  {"number": 7,  "title": "perf(api): reduce the query count",   "labels": []},
  {"number": 8,  "title": "ci(labels): add the guard",           "labels": []},
  {"number": 9,  "title": "test(api): pin the timeout",          "labels": []},
  {"number": 10, "title": "build(docker): update the base",      "labels": []},
  {"number": 11, "title": "style(api): format the mapper",       "labels": []},
  {"number": 12, "title": "revert: undo the broken change",      "labels": []},
  {"number": 13, "title": "feat(hardening)!: drop the legacy adapter", "labels": []},
  {"number": 14, "title": "java\ttab-safe\ttitle",               "labels": []}
]
JSON

output="$(plan "$TMP_DIR/mapping.json")"
code=$?
expect_exit "a title outside conventional commits stops the run" 1 "$code"
expect_no_line_containing "the unmappable title is not silently skipped" "ADD 14" "$output"

# Drop the unmappable row and plan the rest.
cat >"$TMP_DIR/mapping.json" <<'JSON'
[
  {"number": 1,  "title": "docs(odd): record the merge",         "labels": []},
  {"number": 2,  "title": "chore(deps): bump the runner",        "labels": [{"name": "type:chore"}]},
  {"number": 3,  "title": "docs(odd): record the merge",         "labels": [{"name": "documentation"}, {"name": "type:chore"}]},
  {"number": 4,  "title": "refactor(product)!: split variants",  "labels": []},
  {"number": 5,  "title": "fix(docker): read the admin API",     "labels": [{"name": "bug"}]},
  {"number": 6,  "title": "feat(hr): add the menu entry",        "labels": []},
  {"number": 7,  "title": "perf(api): reduce the query count",   "labels": []},
  {"number": 8,  "title": "ci(labels): add the guard",           "labels": []},
  {"number": 9,  "title": "test(api): pin the timeout",          "labels": []},
  {"number": 10, "title": "build(docker): update the base",      "labels": []},
  {"number": 11, "title": "style(api): format the mapper",       "labels": []},
  {"number": 12, "title": "revert: undo the broken change",      "labels": []},
  {"number": 13, "title": "feat(hardening)!: drop the legacy adapter", "labels": []}
]
JSON

output="$(plan "$TMP_DIR/mapping.json")"
code=$?
expect_exit "the plan exits 0 when every title is mappable" 0 "$code"
expect_line "docs is added to an unlabelled docs PR" "ADD 1 type:docs" "$output"
expect_no_line_containing "an already correct PR is not planned at all" "^\(ADD\|REMOVE\) 2 " "$output"
expect_line "a mislabelled type:chore is removed" "REMOVE 3 type:chore" "$output"
expect_line "and the correct label is added" "ADD 3 type:docs" "$output"
expect_no_line_containing "the triage label documentation is untouched" "documentation" "$output"
expect_line "a breaking refactor is a breaking change, not a refactor" "ADD 4 type:breaking-change" "$output"
expect_line "fix maps to type:bug" "ADD 5 type:bug" "$output"
expect_no_line_containing "the triage label bug is untouched" "^REMOVE 5 bug" "$output"
expect_line "feat maps to type:feature" "ADD 6 type:feature" "$output"
expect_line "perf folds into type:feature" "ADD 7 type:feature" "$output"
expect_line "ci folds into type:chore" "ADD 8 type:chore" "$output"
expect_line "test folds into type:chore" "ADD 9 type:chore" "$output"
expect_line "build folds into type:chore" "ADD 10 type:chore" "$output"
expect_line "style folds into type:chore" "ADD 11 type:chore" "$output"
expect_line "revert maps to type:bug" "ADD 12 type:bug" "$output"
expect_line "a breaking feat is a breaking change" "ADD 13 type:breaking-change" "$output"
expect_line "the plan closes with its own count" "plan: 12 addition(s), 1 removal(s) over 13 pull request(s)" "$output"

# ------------------------------------------------------------------------ dry-run first
expect_no_line_containing "a dry run never calls gh" "pr edit" "$(cat "$GH_LOG")"

# ------------------------------------------------------------------------ apply mode
output="$(plan "$TMP_DIR/mapping.json" --apply)"
code=$?
expect_exit "apply mode exits 0 when every gh call succeeds" 0 "$code"
expect_line "apply mode reports what it did" "apply: 12 pull request(s) edited" "$output"
expect_line "the add-only edit passes exactly one flag" "pr edit 1 --add-label type:docs" "$(cat "$GH_LOG")"
expect_line "a correction removes and adds in one call" "pr edit 3 --remove-label type:chore --add-label type:docs" "$(cat "$GH_LOG")"
expect_no_line_containing "an already correct PR is never edited" "pr edit 2 " "$(cat "$GH_LOG")"

apply_edits="$(grep -c 'pr edit' "$GH_LOG")"
if [ "$apply_edits" -eq 12 ]; then
	pass "only the 12 PRs needing a change are edited"
else
	fail "only the 12 PRs needing a change are edited (got $apply_edits edits)"
fi

# ------------------------------------------------------------------------- idempotency
cat >"$TMP_DIR/converged.json" <<'JSON'
[
  {"number": 1, "title": "docs(odd): record the merge",  "labels": [{"name": "type:docs"}]},
  {"number": 2, "title": "fix(api): pin the timeout",    "labels": [{"name": "type:bug"}, {"name": "java"}]}
]
JSON

output="$(plan "$TMP_DIR/converged.json")"
code=$?
expect_exit "a converged history plans nothing" 0 "$code"
expect_no_line_containing "no addition is planned twice" "ADD " "$output"
expect_no_line_containing "no removal is planned twice" "REMOVE " "$output"

output="$(plan "$TMP_DIR/converged.json" --apply)"
expect_no_line_containing "a second apply never calls gh" "pr edit" "$(cat "$GH_LOG")"

# -------------------------------------------------------------------- fail-closed inputs
output="$("$PLANNER" --prs-file "$TMP_DIR/missing.json" --gh "$GH" 2>&1)"
code=$?
expect_exit "a missing input file fails closed" 1 "$code"

output="$("$PLANNER" --no-such-flag 2>&1)"
code=$?
expect_exit "an unknown flag fails closed" 1 "$code"

printf '\n%d passed, %d failed\n' "$PASS_COUNT" "$FAIL_COUNT"
[ "$FAIL_COUNT" -eq 0 ]
