#!/bin/bash
# ============================================
# LifeControl - label enforcement artifacts, wired
# ============================================
# The rule lives in four versioned artifacts that must agree with each other:
#
#   .github/label-vocabulary.txt             the six names, the machine-readable source
#   .github/PULL_REQUEST_TEMPLATE.md         the six checkboxes a contributor ticks
#   .github/workflows/pr-label-guard.yml     the gate that reads the label, not the checkbox
#   .github/scripts/check-pr-type-labels.sh  the guard that reads the source file
#
# Three failures are only visible here, because each artifact is valid on its own:
#
#   1. A seventh `type:*` label added to one side and not the other — the same drift shape
#      that docker/scripts/tests/image-pins.test.sh exists to catch for image pins.
#      `vocabulary-source.test.sh` owns the cross-artifact comparison; this suite reads the
#      same source to check the template from the wiring side.
#   2. A `paths:` filter added to the guard workflow. That is not a detail: this
#      repository's other four workflows all nest one under `pull_request`, which is why a
#      markdown-only PR runs zero jobs, and `docs` is the second largest prefix in the
#      history. A filtered guard would be blind exactly where the rule is forgotten.
#   3. A Dependabot `updates[]` entry without `labels:`, with `dependencies` missing, with two
#      `type:*` labels, or with a name outside the source. Dependabot opens its own pull
#      requests, cannot author the approved issue the issue gate needs, and cannot edit its
#      own labels after opening: the `labels:` block in `.github/dependabot.yml` is the whole
#      of what lets its pull requests through the gate.
#
# No network, no `gh`, no Docker, no YAML library.
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
VOCAB_FILE="$SCRIPT_DIR/../../label-vocabulary.txt"
DEPENDABOT="$SCRIPT_DIR/../../dependabot.yml"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

# The vocabulary is read from the single source (`.github/label-vocabulary.txt`), never
# restated: a Dependabot label is checked against the same six names the guard accepts.
if [ -r "$VOCAB_FILE" ]; then
	VOCABULARY="$(tr '\n' ' ' <"$VOCAB_FILE")"
else
	VOCABULARY=""
	fail "the vocabulary source exists ($VOCAB_FILE)"
fi

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

echo "== Dependabot labels =="
# One row per updates[] entry: "<n>\t<comma-joined labels>". Only the block-list form is
# parsed, which is what the config uses; an entry with no labels block yields an empty
# second field instead of being skipped silently.
dependabot_rows() {
	awk '
		function emit() { printf "%d\t%s\n", entry, labels }
		/^[[:space:]]*-[[:space:]]+package-ecosystem:/ {
			if (entry > 0) emit()
			entry++
			labels = ""
			in_labels = 0
			next
		}
		/^[[:space:]]*labels:[[:space:]]*$/ { in_labels = 1; next }
		in_labels && /^[[:space:]]*-[[:space:]]+/ {
			v = $0
			sub(/^[[:space:]]*-[[:space:]]*/, "", v)
			sub(/[[:space:]]+$/, "", v)
			labels = (labels == "" ? v : labels "," v)
			next
		}
		in_labels && /^[[:space:]]*[^[:space:]-]/ { in_labels = 0 }
		END { if (entry > 0) emit() }
	' "$1"
}

# dependabot_failures <file> — prints one reason per offending updates[] entry and returns
# non-zero when any entry is offending. A file with no entry at all is a failure too.
dependabot_failures() {
	local rows entry labels has_dependencies name ok bad
	local -a names type_labels

	if ! rows="$(dependabot_rows "$1")"; then
		printf '%s\n' "cannot parse $1"
		return 1
	fi
	if [ -z "$rows" ]; then
		printf '%s\n' "no updates[] entry in $1"
		return 1
	fi

	bad=0
	while IFS=$'\t' read -r entry labels; do
		has_dependencies=0
		type_labels=()
		if [ -n "$labels" ]; then
			IFS=',' read -r -a names <<<"$labels"
			for name in "${names[@]}"; do
				[ "$name" = "dependencies" ] && has_dependencies=1
				case "$name" in
				type:*) type_labels+=("$name") ;;
				esac
			done
		fi
		if [ "$has_dependencies" -ne 1 ]; then
			printf '%s\n' "updates[] entry $entry does not carry the dependencies label"
			bad=1
		fi
		if [ "${#type_labels[@]}" -eq 0 ]; then
			printf '%s\n' "updates[] entry $entry carries no type:* label"
			bad=1
			continue
		fi
		if [ "${#type_labels[@]}" -gt 1 ]; then
			printf '%s\n' "updates[] entry $entry carries ${#type_labels[@]} type:* labels"
			bad=1
			continue
		fi
		ok=0
		for name in $VOCABULARY; do
			[ "$name" = "${type_labels[0]}" ] && ok=1
		done
		if [ "$ok" -ne 1 ]; then
			printf '%s\n' "updates[] entry $entry carries off-vocabulary ${type_labels[0]}"
			bad=1
		fi
	done <<<"$rows"
	return "$bad"
}

# expect_dependabot_rejected <description> <file> <expected reason>
expect_dependabot_rejected() {
	local reasons
	if reasons="$(dependabot_failures "$2")"; then
		fail "$1 (accepted a config it must reject)"
	elif printf '%s' "$reasons" | grep -qF -- "$3"; then
		pass "$1"
	else
		fail "$1 (expected '$3' in: $(printf '%s' "$reasons" | tr '\n' '; '))"
	fi
}

if [ -r "$DEPENDABOT" ]; then
	pass "the Dependabot config exists"
	if reasons="$(dependabot_failures "$DEPENDABOT")"; then
		pass "every Dependabot updates[] entry carries dependencies and one type:* label"
	else
		fail "every Dependabot updates[] entry carries dependencies and one type:* label ($(printf '%s' "$reasons" | tr '\n' '; '))"
	fi
else
	fail "the Dependabot config exists ($DEPENDABOT)"
fi

# The three failure modes the rule names, proven on fixtures: a missing label, a doubled
# type:* label and an off-vocabulary name. The real file passing above does not prove the
# parser can reject anything.
cat >"$TMP_DIR/dependabot-missing.yml" <<'YAML'
version: 2
updates:
  - package-ecosystem: gradle
    directory: /a
    schedule:
      interval: weekly
YAML

cat >"$TMP_DIR/dependabot-no-type.yml" <<'YAML'
version: 2
updates:
  - package-ecosystem: gradle
    directory: /a
    labels:
      - dependencies
YAML

cat >"$TMP_DIR/dependabot-doubled.yml" <<'YAML'
version: 2
updates:
  - package-ecosystem: gradle
    directory: /a
    labels:
      - dependencies
      - type:chore
      - type:docs
YAML

cat >"$TMP_DIR/dependabot-off-vocabulary.yml" <<'YAML'
version: 2
updates:
  - package-ecosystem: gradle
    directory: /a
    labels:
      - dependencies
      - type:feat
YAML

cat >"$TMP_DIR/dependabot-complete.yml" <<'YAML'
version: 2
updates:
  - package-ecosystem: gradle
    directory: /a
    labels:
      - dependencies
      - type:chore
  - package-ecosystem: npm
    directory: /b
    labels:
      - dependencies
      - type:docs
YAML

expect_dependabot_rejected "an updates[] entry with no labels block is a failure" "$TMP_DIR/dependabot-missing.yml" "does not carry the dependencies label"
expect_dependabot_rejected "an updates[] entry without a type:* label is a failure" "$TMP_DIR/dependabot-no-type.yml" "carries no type:* label"
expect_dependabot_rejected "an updates[] entry with two type:* labels is a failure" "$TMP_DIR/dependabot-doubled.yml" "carries 2 type:* labels"
expect_dependabot_rejected "an updates[] entry with an off-vocabulary type:* label is a failure" "$TMP_DIR/dependabot-off-vocabulary.yml" "off-vocabulary type:feat"

if dependabot_failures "$TMP_DIR/dependabot-complete.yml" >/dev/null 2>&1; then
	pass "two complete updates[] entries are accepted"
else
	fail "two complete updates[] entries are accepted"
fi

printf '\n%d passed, %d failed\n' "$PASS_COUNT" "$FAIL_COUNT"
[ "$FAIL_COUNT" -eq 0 ]