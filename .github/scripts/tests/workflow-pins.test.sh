#!/bin/bash
# ============================================
# LifeControl - CI workflow pin tests
# ============================================
# Answers the three questions issue #896 asked about `.github/workflows`, none of which any suite
# asked before, and all of which were measured wrong on the tree:
#
#   1. **The token scope.** Four of the five workflows declared no `permissions:` block, so every
#      job inherited the repository default `GITHUB_TOKEN`, which is write-capable, and handed it to
#      every third-party action it ran. `pr-label-guard.yml` was already the model, not the rule.
#
#   2. **The action pins.** Every `uses:` named a major tag — `actions/checkout@v7`,
#      `actions/setup-java@v6`, `actions/setup-node@v7`, `actions/{upload,download}-artifact@v7`. A
#      major tag is a pointer its publisher can move, and these actions run with this repository's
#      token, so "the version that ran last week" was never a fact this tree could state.
#
#   3. **The container image.** `koalaman/shellcheck:stable` ran under a comment claiming the image
#      was pinned "so the verdict does not drift" — the linter that gates this repository could
#      change, and refuse different code, without a commit here.
#
# The three checkers below run against the tree **and** against fixtures that must be rejected:
# `uses: actions/checkout@v7`, a SHA with no version comment, a workflow with no `permissions:`, a
# `contents: write` scope, and a `:latest` container. Without those cases a reader that matched
# nothing would print the same green line as a reader that matched everything.
#
# Comments are stripped before the floating-tag and image checks: a `#` line does not run, so
# explaining a former value in a comment must not read as still using it. The `uses:` check reads
# the raw line, because there the tag comment is the evidence it looks for.
#
# A write scope is rejected outright rather than accepted with a justification comment: a comment
# cannot be verified, and the failure this suite exists for is a widened scope nobody noticed. A job
# that genuinely needs one edits the vocabulary below in the same commit, which is the reviewed
# decision this repository asks for everywhere else.
#
# No network, no `gh`, no Docker, no YAML library: this is a static consistency check.
#
# Run: ./.github/scripts/tests/workflow-pins.test.sh
# Exits 0 only when every case passes.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKFLOWS="${WORKFLOWS_DIR:-$SCRIPT_DIR/../../workflows}"
TMP="$(mktemp -d)" || {
	printf 'FAIL: cannot create a scratch directory (TMPDIR=%s)\n' "${TMPDIR:-unset}" >&2
	exit 1
}
[ -d "$TMP" ] || {
	printf 'FAIL: the scratch directory is not usable: %s\n' "$TMP" >&2
	exit 1
}
trap 'rm -rf "$TMP"' EXIT

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

# The frozen shellcheck pin. Bumping it is editing this line and the two workflows together, in the
# same commit, so the version the linter gate runs is always a reviewed change.
EXPECTED_SHELLCHECK_TAG="v0.11.0"

# ------------------------------------------
# Checkers. Each prints one line per finding and nothing when the file is clean, so the same code
# serves the tree (empty output is the pass) and the fixtures (empty output is the failure).
# ------------------------------------------

# permissions_scopes <file>: the scopes of the top-level `permissions:` block, one per line.
permissions_scopes() {
	awk '
		BEGIN { in_perms = 0 }
		/^permissions:/ { in_perms = 1; next }
		in_perms && /^[^[:space:]#]/ { in_perms = 0 }
		in_perms && /^[[:space:]]+[A-Za-z_-]+:/ {
			line = $0
			sub(/^[[:space:]]+/, "", line)
			sub(/[[:space:]]*#.*$/, "", line)
			sub(/[[:space:]]+$/, "", line)
			print line
		}
	' "$1"
}

# permissions_verdict <file>
permissions_verdict() {
	local file="$1" inline scopes writes

	if ! grep -qE '^permissions:' "$file"; then
		printf 'declares no top-level permissions: block, so every job inherits the repository default token\n'
		return
	fi

	inline="$(sed -n 's/^permissions:[[:space:]]*\(.*\)$/\1/p' "$file" | sed 's/[[:space:]]*#.*$//' | head -1)"
	if [ -n "$inline" ]; then
		printf 'declares permissions inline (%s) instead of a scope block, so no scope can be checked\n' "$inline"
		return
	fi

	scopes="$(permissions_scopes "$file")"
	if [ -z "$scopes" ]; then
		printf 'declares a permissions: block with no scope in it\n'
		return
	fi

	if ! printf '%s\n' "$scopes" | grep -qx 'contents: read'; then
		printf 'declares permissions without contents: read\n'
	fi

	writes="$(printf '%s\n' "$scopes" | grep -E ': write$' | tr '\n' ' ' | sed 's/[[:space:]]*$//')"
	if [ -n "$writes" ]; then
		printf 'grants a write scope (%s): add it to this suite deliberately if a job really needs it\n' "$writes"
	fi
}

# uses_entries <file>: every `uses:` line with its line number, in file order.
uses_entries() {
	grep -nE '^[[:space:]]*(-[[:space:]]+)?uses:' "$1" 2>/dev/null
}

# uses_verdict <file>
uses_verdict() {
	local file="$1" entry lineno rest ref comment

	while IFS= read -r entry; do
		[ -n "$entry" ] || continue
		lineno="${entry%%:*}"
		rest="${entry#*:}"
		ref="$(printf '%s' "$rest" |
			sed -E 's/^[[:space:]]*(-[[:space:]]+)?uses:[[:space:]]+//; s/[[:space:]]+#.*$//; s/[[:space:]]+$//')"
		comment="$(printf '%s' "$rest" | sed -nE 's/.*#[[:space:]]*(.*)$/\1/p')"

		# A local action is this repository's own file, not a publisher that can retag it.
		case "$ref" in
		./*) continue ;;
		esac

		if ! printf '%s' "$ref" | grep -qE '@[0-9a-f]{40}$'; then
			printf 'line %s pins %s to a mutable ref: a major tag is a pointer its publisher can retag\n' "$lineno" "$ref"
			continue
		fi

		if ! printf '%s' "$comment" | grep -qE '^v[0-9]+(\.[0-9]+)*'; then
			printf 'line %s pins a commit but carries no released tag comment (expected a trailing `# vN`)\n' "$lineno"
		fi
	done <<< "$(uses_entries "$file")"
}

# floating_verdict <file>: container images running from a floating tag or an unpinned image.
floating_verdict() {
	local file="$1" entry lineno text code ref

	while IFS= read -r entry; do
		[ -n "$entry" ] || continue
		lineno="${entry%%:*}"
		text="${entry#*:}"
		code="$(printf '%s' "$text" | sed 's/#.*//')"
		# A line that is only a comment keeps nothing of itself once the comment is gone.
		[ -n "$(printf '%s' "$code" | tr -d '[:space:]')" ] || continue
		for ref in $(printf '%s' "$code" | grep -oE '[A-Za-z0-9._/-]+:[A-Za-z0-9._-]+'); do
			case "$ref" in
			*:latest | *:stable | *:main)
				printf 'line %s runs %s, a floating tag the publisher can move\n' "$lineno" "$ref"
				;;
			esac
		done
	done <<< "$(grep -nE 'image:|docker[[:space:]]+run' "$file" 2>/dev/null)"
}

# linter_pins <file>: the shellcheck image references that actually run, one per line.
# Comments are stripped first, because a line that explains a former tag does not run with it.
linter_pins() {
	sed 's/#.*//' "$1" 2>/dev/null | grep -oE 'koalaman/shellcheck:[A-Za-z0-9._-]+' | sort -u
}

# findings <file>: the three checks against one workflow file, findings only.
findings() {
	permissions_verdict "$1"
	uses_verdict "$1"
	floating_verdict "$1"
}

# ------------------------------------------
# 1. The checkers reject what they exist to reject.
# ------------------------------------------
mkdir -p "$TMP/fixtures"

cat > "$TMP/fixtures/clean.yml" <<'YAML'
name: Clean
on:
  pull_request:
permissions:
  # A comment inside the block is not a scope.
  contents: read
jobs:
  ci:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7
      - run: docker run --rm koalaman/shellcheck:v0.11.0 --version
YAML

cat > "$TMP/fixtures/mutable-ref.yml" <<'YAML'
name: Mutable
on:
  pull_request:
permissions:
  contents: read
jobs:
  ci:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
YAML

cat > "$TMP/fixtures/no-version-comment.yml" <<'YAML'
name: No comment
on:
  pull_request:
permissions:
  contents: read
jobs:
  ci:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1
YAML

cat > "$TMP/fixtures/no-permissions.yml" <<'YAML'
name: No permissions
on:
  pull_request
jobs:
  ci:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7
YAML

cat > "$TMP/fixtures/write-scope.yml" <<'YAML'
name: Write scope
on:
  pull_request:
permissions:
  contents: write
jobs:
  ci:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7
YAML

cat > "$TMP/fixtures/floating-image.yml" <<'YAML'
name: Floating image
on:
  pull_request:
permissions:
  contents: read
jobs:
  ci:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7
      # A comment that merely names the former tag must not read as still using it.
      - run: docker run --rm koalaman/shellcheck:stable --version
YAML

# A clean fixture proves the checkers can pass, and each broken one proves they are not passing by
# returning nothing. Quoted, so a fixture that fails to write is a case failure and not an error.
check_fixture_clean() {
	local fixture="$1" result
	result="$(findings "$fixture")"
	if [ -z "$result" ]; then
		pass "fixture $(basename "$fixture") is accepted"
	else
		fail "fixture $(basename "$fixture") should be clean but was rejected: $result"
	fi
}

check_fixture_rejected() {
	local fixture="$1" result
	result="$(findings "$fixture")"
	if [ -n "$result" ]; then
		pass "fixture $(basename "$fixture") is rejected ($(printf '%s' "$result" | head -1))"
	else
		fail "fixture $(basename "$fixture") was accepted, so the checker that should reject it is not reading the file"
	fi
}

check_fixture_clean "$TMP/fixtures/clean.yml"
check_fixture_rejected "$TMP/fixtures/mutable-ref.yml"
check_fixture_rejected "$TMP/fixtures/no-version-comment.yml"
check_fixture_rejected "$TMP/fixtures/no-permissions.yml"
check_fixture_rejected "$TMP/fixtures/write-scope.yml"
check_fixture_rejected "$TMP/fixtures/floating-image.yml"

# ------------------------------------------
# 2. Every workflow on the tree declares a read-only scope, pins every third-party action to a
#    commit with its tag in a comment, and runs no floating container image.
# ------------------------------------------
WORKFLOW_COUNT=0
USES_COUNT=0
SHELLCHECK_PINS=""

for workflow in "$WORKFLOWS"/*.yml; do
	[ -f "$workflow" ] || continue
	WORKFLOW_COUNT=$((WORKFLOW_COUNT + 1))
	name="$(basename "$workflow")"

	result="$(findings "$workflow")"
	if [ -z "$result" ]; then
		pass "$name declares contents: read, pins every action to a commit and runs no floating image"
	else
		while IFS= read -r line; do
			[ -n "$line" ] || continue
			fail "$name $line"
		done <<< "$result"
	fi

	uses="$(uses_entries "$workflow" | sed '/^$/d' | wc -l | tr -d ' ')"
	USES_COUNT=$((USES_COUNT + uses))

	for pin in $(linter_pins "$workflow"); do
		SHELLCHECK_PINS="$SHELLCHECK_PINS$name:$pin
"
		if [ "$pin" = "koalaman/shellcheck:$EXPECTED_SHELLCHECK_TAG" ]; then
			pass "$name runs shellcheck at the frozen pin ($pin)"
		else
			fail "$name runs $pin, but the frozen shellcheck pin is koalaman/shellcheck:$EXPECTED_SHELLCHECK_TAG"
		fi
	done
done

# A glob that matches nothing, or a reader that matches no `uses:` line, would report green while
# checking nothing at all: the same false pass the path filters and the distroless probes were about.
if [ "$WORKFLOW_COUNT" -eq 0 ]; then
	fail "no *.yml workflow matched $WORKFLOWS, so nothing was checked"
fi
# The five known files are the floor, not the ceiling: a sixth workflow is welcome and gets the same
# three checks, but fewer than five means the glob or the directory moved and the run would report
# green over a smaller tree.
if [ "$WORKFLOW_COUNT" -lt 5 ]; then
	fail "only $WORKFLOW_COUNT workflow(s) were checked, fewer than the five this repository ships: the glob or the directory moved"
fi
if [ "$USES_COUNT" -eq 0 ]; then
	fail "no uses: line matched in $WORKFLOW_COUNT workflow(s), so the pin reader is not reading them"
fi
printf 'NOTE: %s workflow(s), %s uses: line(s), %s shellcheck reference(s) checked\n' \
	"$WORKFLOW_COUNT" "$USES_COUNT" "$(printf '%s' "$SHELLCHECK_PINS" | sed '/^$/d' | wc -l | tr -d ' ')"

# ------------------------------------------
# Summary
# ------------------------------------------
echo "------------------------------------------"
printf 'workflow-pins: %s passed, %s failed\n' "$PASS_COUNT" "$FAIL_COUNT"
if [ "$FAIL_COUNT" -ne 0 ]; then
	exit 1
fi
exit 0
