#!/bin/bash
# ============================================
# LifeControl - CI trigger parity
# ============================================
# Answers one question about the four component workflows: do `push` and `pull_request`
# filter the same paths?
#
# Before this suite existed, angular-ci, api-ci, docker-build-integrity and gateway-ci each
# nested their `paths:` filter under `pull_request` and none under `push`, so every merge to
# `main` ran four component builds even when the merged paths matched no filter (AGENTS.md
# documents the asymmetry). A paths-filtered push job that no changed path matches cannot
# start, so the filter is a saving, never a way to skip a real build.
#
# The suite reads the trigger blocks with awk rather than a YAML library: the shape is small
# and regular, and the fixtures below are what keep the parser honest —
#   * a missing entry, an extra entry and an absent `push:` block must all be reported;
#   * a matching pair must be accepted.
# Without those cases a parser that returned nothing for both sides would look green.
#
# pr-label-guard.yml is asserted to stay unfiltered on purpose: it gates labels and issue
# linkage on every pull request, and a `paths:` filter there would blind it exactly where the
# rule is used most — 80 of the 341 merged PRs are `docs`-prefixed.
#
# No network, no `gh`, no Docker, no YAML library: every suite here parses its artifact with
# grep and awk, and a workflow trigger block is small and regular enough for that to stay
# exact.
#
# Run: ./.github/scripts/tests/workflow-trigger-parity.test.sh
# Exits 0 only when every case passes.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKFLOWS="$SCRIPT_DIR/../../workflows"
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

# extract_paths <file> <trigger>
# Prints, one per line and in file order, the `paths:` entries of one `on:` trigger.
# Comments and `branches:` are ignored; only the `- ...` entries of that trigger's `paths:`
# key are emitted.
extract_paths() {
	awk -v trigger="$2" '
		BEGIN { in_on = 0; in_trigger = 0; in_paths = 0 }
		/^on:[[:space:]]*$/ { in_on = 1; in_trigger = 0; in_paths = 0; next }
		in_on && /^[^[:space:]]/ { in_on = 0; in_trigger = 0; in_paths = 0 }
		in_on && /^  [A-Za-z_]+:/ {
			in_trigger = ($0 ~ "^  " trigger ":")
			in_paths = 0
			next
		}
		in_on && in_trigger && /^    [A-Za-z_-]+:/ {
			# The hyphen matters: `paths-ignore:` and `branches-ignore:` sit at this same
			# indentation and carry entries that look like `paths:` entries. Reading them as
			# paths reports parity between two lists that differ.
			in_paths = ($0 ~ "^    paths:")
			next
		}
		in_on && in_trigger && in_paths && /^[[:space:]]*-/ {
			line = $0
			sub(/^[[:space:]]*-[[:space:]]*/, "", line)
			sub(/[[:space:]]+$/, "", line)
			print line
		}
	' "$1"
}

# parity_verdict <file> -> match | differ | no-pull-request-paths
parity_verdict() {
	local push_paths pr_paths
	push_paths="$(extract_paths "$1" push)"
	pr_paths="$(extract_paths "$1" pull_request)"
	if [ -z "$pr_paths" ]; then
		printf 'no-pull-request-paths\n'
	elif [ "$push_paths" = "$pr_paths" ]; then
		printf 'match\n'
	else
		printf 'differ\n'
	fi
}

# expect_verdict <label> <file> <match|differ|no-pull-request-paths>
expect_verdict() {
	local got
	got="$(parity_verdict "$2")"
	if [ "$got" = "$3" ]; then
		pass "$1"
	else
		fail "$1 (got $got)"
	fi
}

echo "== the four component workflows filter the same paths on both triggers =="
for workflow in angular-ci.yml api-ci.yml docker-build-integrity.yml gateway-ci.yml; do
	file="$WORKFLOWS/$workflow"
	if [ ! -r "$file" ]; then
		fail "$workflow exists"
		continue
	fi
	pass "$workflow exists"
	if [ -z "$(extract_paths "$file" push)" ]; then
		fail "$workflow declares a paths list under push"
	else
		pass "$workflow declares a paths list under push"
	fi
	if [ -z "$(extract_paths "$file" pull_request)" ]; then
		fail "$workflow declares a paths list under pull_request"
	else
		pass "$workflow declares a paths list under pull_request"
	fi
	expect_verdict "$workflow push and pull_request list the same paths" "$file" match
	if [ "$(parity_verdict "$file")" = "differ" ]; then
		printf '  push list:\n'
		extract_paths "$file" push | sed 's/^/    /'
		printf '  pull_request list:\n'
		extract_paths "$file" pull_request | sed 's/^/    /'
	fi
done

echo "== the label guard stays unfiltered =="
GUARD="$WORKFLOWS/pr-label-guard.yml"
if [ ! -r "$GUARD" ]; then
	fail "pr-label-guard.yml exists ($GUARD)"
elif grep -Eq '^[[:space:]]*paths:[[:space:]]*$' "$GUARD"; then
	fail "pr-label-guard.yml carries no paths filter"
else
	pass "pr-label-guard.yml carries no paths filter"
fi

echo "== the parser is proven by fixtures =="
cat >"$TMP/match.yml" <<'YAML'
on:
  push:
    branches: [main]
    paths:
      - 'one/**'
      - 'two/**'
  pull_request:
    paths:
      - 'one/**'
      - 'two/**'
YAML
expect_verdict "a matching pair is accepted" "$TMP/match.yml" match

cat >"$TMP/missing.yml" <<'YAML'
on:
  push:
    branches: [main]
    paths:
      - 'one/**'
  pull_request:
    paths:
      - 'one/**'
      - 'two/**'
YAML
expect_verdict "a push missing an entry is reported" "$TMP/missing.yml" differ

cat >"$TMP/extra.yml" <<'YAML'
on:
  push:
    branches: [main]
    paths:
      - 'one/**'
      - 'two/**'
  pull_request:
    paths:
      - 'one/**'
YAML
expect_verdict "a push with an extra entry is reported" "$TMP/extra.yml" differ

cat >"$TMP/no-push.yml" <<'YAML'
on:
  pull_request:
    paths:
      - 'one/**'
YAML
expect_verdict "a workflow with no push block does not pass" "$TMP/no-push.yml" differ

# A `paths-ignore:` (or `branches-ignore:`) block sits at the same indentation as `paths:` and
# its entries look identical. Reading them as `paths` entries reports parity between two lists
# that differ, which is a false pass in the one suite that exists to catch drift.
cat >"$TMP/ignore.yml" <<'YAML'
on:
  push:
    paths:
      - 'one/**'
    paths-ignore:
      - 'two/**'
  pull_request:
    paths:
      - 'one/**'
      - 'two/**'
YAML
expect_verdict "paths-ignore entries are not read as paths entries" "$TMP/ignore.yml" differ

printf '\n%d passed, %d failed\n' "$PASS_COUNT" "$FAIL_COUNT"
[ "$FAIL_COUNT" -eq 0 ]
