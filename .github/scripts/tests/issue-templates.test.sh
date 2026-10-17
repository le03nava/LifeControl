#!/bin/bash
# ============================================
# LifeControl - issue forms, wired
# ============================================
# Answers one question about the issue side of this repository's flow: does every issue it
# accepts carry the fields a reviewer needs to hold the work to something checkable?
#
# A form that exists but leaves its acceptance criteria optional is worse than no form at
# all: it looks like a gate and admits an issue nobody can verify. So this suite reads the
# declared structure, never the file name:
#
#   1. config.yml exists and disables blank issues, so the chooser cannot bypass the forms.
#   2. Every form declares name, description and body.
#   3. Every form declares the three canonical fields: problem, outcome, acceptance.
#   4. Every one of those three is marked required, and the marker belongs to its own field
#      rather than to the next one.
#
# No network, no `gh`, no Docker, no YAML library: every suite here parses its artifact with
# grep and awk, and a form is small and regular enough for that to stay exact. The window
# rule is the part worth keeping: a required marker read from the wrong block passes a check
# that should have failed, which is the defect this file exists to catch.
#
# Run: ./.github/scripts/tests/issue-templates.test.sh
# Exits 0 only when every case passes.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEMPLATES="$SCRIPT_DIR/../../ISSUE_TEMPLATE"

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

FORMS="bug feature chore"
REQUIRED_IDS="problem outcome acceptance"

# required_field_belongs_to <file> <id>
# True only when a `required: true` marker appears after this field's `id:` line and before
# the next `- type:` entry. A marker belonging to the following field does not count.
required_field_belongs_to() {
	awk -v id="$2" '
		/^[[:space:]]*-[[:space:]]*type:/ { if (inside && !ok) exit 1 }
		inside && /required:[[:space:]]*true/ { ok = 1 }
		$0 ~ "^[[:space:]]*id:[[:space:]]*" id "[[:space:]]*$" { inside = 1 }
		END { exit !(inside && ok) }
	' "$1"
}

echo "== the template chooser =="
CONFIG="$TEMPLATES/config.yml"
if [ -r "$CONFIG" ]; then
	pass "config.yml exists"
	if grep -Eq '^[[:space:]]*blank_issues_enabled:[[:space:]]*false[[:space:]]*$' "$CONFIG"; then
		pass "blank_issues_enabled is false, so the forms cannot be skipped"
	else
		fail "blank_issues_enabled is false, so the forms cannot be skipped"
	fi
else
	fail "config.yml exists ($CONFIG)"
fi

for form in $FORMS; do
	file="$TEMPLATES/$form.yml"
	echo "== $form =="
	if [ ! -r "$file" ]; then
		fail "$form.yml exists"
		continue
	fi
	pass "$form.yml exists"

	if grep -Eq '^name:[[:space:]]*[^[:space:]]' "$file"; then
		pass "$form.yml declares name"
	else
		fail "$form.yml declares name"
	fi

	if grep -Eq '^description:[[:space:]]*[^[:space:]]' "$file"; then
		pass "$form.yml declares description"
	else
		fail "$form.yml declares description"
	fi

	if grep -Eq '^body:[[:space:]]*$' "$file" && grep -Eq '^[[:space:]]*-[[:space:]]*type:' "$file"; then
		pass "$form.yml declares a body made of form elements"
	else
		fail "$form.yml declares a body made of form elements"
	fi

	for id in $REQUIRED_IDS; do
		if ! grep -Eq "^[[:space:]]*id:[[:space:]]*${id}[[:space:]]*$" "$file"; then
			fail "$form.yml declares the $id field"
			continue
		fi
		pass "$form.yml declares the $id field"
		if required_field_belongs_to "$file" "$id"; then
			pass "$form.yml marks $id required"
		else
			fail "$form.yml marks $id required"
		fi
	done
done

echo "== the marker belongs to its own field =="
# A fixture proves the awk rule itself: a marker under the *second* field must not satisfy
# the first one. Without this case the suite would pass on a file whose first field is
# optional and whose second one is required.
FIXTURE="$(mktemp)"
trap 'rm -f "$FIXTURE"' EXIT
cat >"$FIXTURE" <<'YAML'
name: fixture
description: fixture
body:
  - type: textarea
    id: problem
    attributes:
      label: Problem
  - type: textarea
    id: outcome
    attributes:
      label: Outcome
    validations:
      required: true
YAML
if required_field_belongs_to "$FIXTURE" problem; then
	fail "an optional field is not credited with the next field's required marker"
else
	pass "an optional field is not credited with the next field's required marker"
fi
if required_field_belongs_to "$FIXTURE" outcome; then
	pass "a required field is credited with its own marker"
else
	fail "a required field is credited with its own marker"
fi

printf '\n%d passed, %d failed\n' "$PASS_COUNT" "$FAIL_COUNT"
[ "$FAIL_COUNT" -eq 0 ]
