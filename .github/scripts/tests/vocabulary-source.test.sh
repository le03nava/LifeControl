#!/bin/bash
# ============================================
# LifeControl - one machine-readable type:* vocabulary
# ============================================
# The `type:*` namespace is answered in three artifacts that a contributor reads and one
# that CI executes:
#
#   .github/label-vocabulary.txt              the machine-readable source, one name per line
#   .github/PULL_REQUEST_TEMPLATE.md         the six checkboxes, each paired with its label
#   .agents/skills/project-conventions/references/labels.md   the vocabulary table
#   .github/scripts/check-pr-type-labels.sh  the guard, which reads the source file
#
# A seventh `type:*` name added to one artifact and not the others is the drift shape this
# suite exists to catch: the guard would accept a name the template never offers, or the
# documentation would prescribe a name the guard rejects. The three artifacts are compared
# as sets, so order and formatting do not matter, only the names.
#
# The fail-closed cases are proven on a copy of the guard placed outside the repository,
# beside a vocabulary file that this suite owns: a missing source and an empty source must
# both stop the gate, because an unreadable vocabulary that silently reads as "no names"
# would reject every label instead of failing loudly.
#
# No network, no `gh`, no Docker, no YAML library: the vocabulary table is regular enough
# to read with awk and grep.
#
# Run: ./.github/scripts/tests/vocabulary-source.test.sh
# Exits 0 only when every case passes.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

VOCAB_FILE="$REPO_ROOT/.github/label-vocabulary.txt"
TEMPLATE="$REPO_ROOT/.github/PULL_REQUEST_TEMPLATE.md"
LABELS_MD="$REPO_ROOT/.agents/skills/project-conventions/references/labels.md"
GUARD="$REPO_ROOT/.github/scripts/check-pr-type-labels.sh"

# The one vocabulary, stated here once so the three artifacts are compared against an
# independent expectation rather than against each other.
EXPECTED="type:bug
type:feature
type:docs
type:refactor
type:chore
type:breaking-change"

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

expect_exit() {
	if [ "$2" = "$3" ]; then
		pass "$1 (exit $3)"
	else
		fail "$1 (expected exit $2, got $3)"
	fi
}

expect_contains() {
	case "$3" in
	*"$2"*) pass "$1 (mentions '$2')" ;;
	*) fail "$1 (expected '$2' in: $(printf '%s' "$3" | tr '\n' ' '))" ;;
	esac
}

# show <list> — the list as one space-separated line, for a readable failure message
show() {
	printf '%s' "$1" | tr '\n' ' '
}

# vocabulary_table <labels.md> — prints only the rows of the "Checkbox in the PR body /
# Label" table, so a name that lives in another table of the same file cannot leak in.
vocabulary_table() {
	awk '
		/^\| Checkbox in the PR body \| Label \|$/ { inside = 1; next }
		inside && /^\| --- \| --- \|$/ { next }
		inside && /^$/ { exit }
		inside { print }
	' "$1"
}

expected_sorted="$(printf '%s\n' "$EXPECTED" | sort -u)"

echo "== three artifacts, one vocabulary =="

if [ -r "$VOCAB_FILE" ]; then
	pass "the machine-readable vocabulary source exists"
	file_list="$(grep -E '^type:[a-z-]+$' "$VOCAB_FILE" | sort -u)"
	# Every non-blank line must be a bare name: an accidental comment or prose line would
	# otherwise become a label the guard accepts.
	non_names="$(grep -vE '^type:[a-z-]+$' "$VOCAB_FILE" | sed '/^$/d')"
	if [ -z "$non_names" ]; then
		pass "every line of the source is a bare type:* name"
	else
		fail "every line of the source is a bare type:* name (found: $(show "$non_names"))"
	fi
else
	fail "the machine-readable vocabulary source exists ($VOCAB_FILE)"
	file_list=""
fi

if [ -r "$TEMPLATE" ]; then
	template_list="$(grep -oE '`type:[a-z-]+`' "$TEMPLATE" | tr -d '`' | sort -u)"
	checkboxes="$(grep -c '^- \[ \] .*(`type:' "$TEMPLATE")"
else
	template_list=""
	checkboxes=0
	fail "the PR template exists ($TEMPLATE)"
fi

if [ -r "$LABELS_MD" ]; then
	labels_list="$(vocabulary_table "$LABELS_MD" | grep -oE '`type:[a-z-]+`' | tr -d '`' | sort -u)"
else
	labels_list=""
	fail "the labels reference exists ($LABELS_MD)"
fi

compare_set() {
	if [ "$2" = "$3" ]; then
		pass "$1"
	else
		fail "$1 (expected: $(show "$2"); got: $(show "$3"))"
	fi
}

compare_set "the vocabulary source declares exactly the six type:* names" "$expected_sorted" "$file_list"
compare_set "the template offers exactly the six type:* names" "$expected_sorted" "$template_list"
compare_set "the labels.md vocabulary table declares exactly the six type:* names" "$expected_sorted" "$labels_list"
compare_set "the template's six names are the source's six names" "$file_list" "$template_list"
compare_set "the labels.md names are the source's six names" "$file_list" "$labels_list"

if [ "$checkboxes" -eq 6 ]; then
	pass "the template offers exactly 6 type:* checkboxes"
else
	fail "the template offers exactly 6 type:* checkboxes (got $checkboxes)"
fi

echo "== the guard reads the source =="

if [ -r "$GUARD" ]; then
	output="$(bash "$GUARD" --labels-json '["java","type:docs"]' 2>&1)"
	code=$?
	expect_exit "the guard accepts a name from the source" 0 "$code"
	expect_contains "the pass names the label" "type:docs" "$output"

	output="$(bash "$GUARD" --labels-json '["type:feat"]' 2>&1)"
	code=$?
	expect_exit "the guard rejects a synonym absent from the source" 1 "$code"
	expect_contains "the rejection says the name is unknown" "unknown type label" "$output"
else
	fail "the guard exists ($GUARD)"
fi

echo "== a broken source fails closed =="

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
mkdir -p "$TMP_DIR/scripts"
cp "$GUARD" "$TMP_DIR/scripts/check-pr-type-labels.sh"
TMP_GUARD="$TMP_DIR/scripts/check-pr-type-labels.sh"

# No $TMP_DIR/label-vocabulary.txt exists yet: the copied guard must say so.
output="$(bash "$TMP_GUARD" --labels-json '["type:docs"]' 2>&1)"
code=$?
expect_exit "a missing vocabulary source fails closed" 1 "$code"
expect_contains "the missing-source failure is a FAIL" "FAIL:" "$output"

# An empty file is not an empty vocabulary that rejects everything: it is a broken gate.
: >"$TMP_DIR/label-vocabulary.txt"
output="$(bash "$TMP_GUARD" --labels-json '["type:docs"]' 2>&1)"
code=$?
expect_exit "an empty vocabulary source fails closed" 1 "$code"
expect_contains "the empty-source failure is a FAIL" "FAIL:" "$output"

# And the copied guard passes once this suite supplies the vocabulary, so the two failures
# above prove the source is read rather than that the copy is broken.
printf '%s\n' "$EXPECTED" >"$TMP_DIR/label-vocabulary.txt"
output="$(bash "$TMP_GUARD" --labels-json '["type:docs"]' 2>&1)"
code=$?
expect_exit "a complete vocabulary source lets the guard pass" 0 "$code"

printf '\n%d passed, %d failed\n' "$PASS_COUNT" "$FAIL_COUNT"
[ "$FAIL_COUNT" -eq 0 ]
