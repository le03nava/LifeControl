#!/bin/bash
# ============================================
# LifeControl - CI trigger parity
# ============================================
# Answers one question about the four component workflows: do `push` and `pull_request`
# filter the same paths?
#
# Before this suite existed, angular-ci, api-ci, docker-build-integrity and gateway-ci each
# nested their `paths:` filter under `pull_request` and none under `push`, so every merge to
# `main` ran four component builds even when the merged paths matched no filter. AGENTS.md said
# the same thing, and went on saying it after the workflows changed: the parity below held while
# the prose asserted the opposite, because nothing read both. The section before the fixtures is
# that reader — the prose is asserted here, not trusted. A paths-filtered push job that no changed
# path matches cannot start, so the filter is a saving, never a way to skip a real build.
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

# concurrency_setting <file> <key>
# Prints the value declared for <key> inside the workflow's top-level `concurrency:` block, with
# a trailing comment and surrounding whitespace removed. Only that block is read, and comment
# lines inside it are skipped: the key is explained in a comment right above its own line, so a
# whole-file substring match would read the explanation as a declaration.
concurrency_setting() {
	awk -v key="$2" '
		BEGIN { in_concurrency = 0 }
		/^concurrency:[[:space:]]*$/ { in_concurrency = 1; next }
		in_concurrency && /^[^[:space:]]/ { in_concurrency = 0 }
		!in_concurrency { next }
		/^[[:space:]]*#/ { next }
		{
			line = $0
			if (line ~ "^[[:space:]]*" key ":") {
				sub("^[[:space:]]*" key ":[[:space:]]*", "", line)
				sub(/[[:space:]]+#.*$/, "", line)
				sub(/[[:space:]]+$/, "", line)
				print line
				exit
			}
		}
	' "$1"
}

# pull_request_types <file>
# Prints, one per line and in file order, the event types declared for the `pull_request`
# trigger. Both the inline `[a, b]` form and a block list are read; entries are stripped of
# whitespace so a reformat cannot change the answer. An empty result is never a pass: the
# assertion that has to keep passing requires `labeled` to be present, which is what proves this
# parser reads the list at all.
pull_request_types() {
	awk '
		BEGIN { in_on = 0; in_trigger = 0; in_types = 0 }
		/^on:[[:space:]]*$/ { in_on = 1; in_trigger = 0; in_types = 0; next }
		in_on && /^[^[:space:]]/ { in_on = 0; in_trigger = 0; in_types = 0 }
		in_on && /^  [A-Za-z_]+:/ {
			in_trigger = ($0 ~ "^  pull_request:")
			in_types = 0
			next
		}
		in_on && in_trigger && /^[[:space:]]*#/ { next }
		in_on && in_trigger && /^    types:[[:space:]]*\[/ {
			line = $0
			sub(/^    types:[[:space:]]*/, "", line)
			sub(/[[:space:]]*#.*$/, "", line)
			gsub(/[][[:space:]]/, "", line)
			count = split(line, entries, ",")
			for (i = 1; i <= count; i++) {
				if (entries[i] != "") print entries[i]
			}
			next
		}
		in_on && in_trigger && /^    types:[[:space:]]*$/ { in_types = 1; next }
		in_on && in_trigger && in_types && /^      -/ {
			line = $0
			sub(/^[[:space:]]*-[[:space:]]*/, "", line)
			sub(/[[:space:]]*#.*$/, "", line)
			sub(/[[:space:]]+$/, "", line)
			if (line != "") print line
			next
		}
	' "$1"
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

echo "== the label guard queues instead of cancelling =="
# A pull request created with `--label`, which is the procedure `references/labels.md` documents,
# fires `opened` and `labeled` in the same second. Measured on pull request #409: those two events
# started two runs of this workflow, `39089758364` cancelled and `39089758365` succeeded. With
# `cancel-in-progress: true` the newer run kills the running one, and a run killed that way leaves
# a `CANCELLED` check run on the head commit for both protected contexts this workflow carries
# (`Exactly one type:* label` and `A linked, approved issue`). The head commit then holds four
# check runs — two `cancelled` and two `success` — and its rollup reads `FAILURE` while
# `gh pr checks` reports both contexts `pass`: measured on #409, head `959df52`, where the pull
# request read `statusCheckRollup: FAILURE` / `mergeStateStatus: BLOCKED` and nothing visible
# named the cancelled run. Queued behind the running run instead, the newer run waits, no check
# run is ever cancelled, and the last run to complete reports the current label set because the
# verdict is read from the pull request itself through the API. The search is scoped to the
# `concurrency:` block and skips comment lines on purpose: the line is explained in a comment
# right above it, and a whole-file substring match would read that explanation as a declaration.
cancel_in_progress="$(concurrency_setting "$GUARD" cancel-in-progress)"
if [ "$cancel_in_progress" = "true" ]; then
	fail "pr-label-guard.yml does not cancel its own run (a cancelled running run leaves cancelled check runs for the two protected contexts, so the head commit's rollup reads FAILURE and the merge reads BLOCKED while both checks read pass)"
else
	pass "pr-label-guard.yml does not cancel its own run"
fi

# `labeled` has to stay in the trigger set: a pull request labelled after it was opened — every
# Dependabot bump, whose `.github/dependabot.yml` labels arrive a beat after `opened`, and any
# label added by `gh pr edit --add-label` — is re-evaluated only because that event starts a run.
# The queue is what makes that safe; a trigger list cannot lose the entry without the guard going
# back to a snapshot no later event can correct.
if pull_request_types "$GUARD" | grep -Fxq labeled; then
	pass "pr-label-guard.yml still triggers on labeled"
else
	fail "pr-label-guard.yml still triggers on labeled (a pull request labelled after it was opened is never re-evaluated)"
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

echo "== the documented claim matches the workflows =="
# AGENTS.md carries the human-readable version of this rule, and it kept the pre-repair claim
# ("el de `push` a `main` **no** filtra") after all four workflows had started filtering the
# same paths: the parity above was green, the prose was wrong, and no check read both. This is
# that check. Only the absence of the claim is asserted, because the workflows — never the prose
# — are the source: a rewrite that no longer claims the asymmetry passes without this suite
# pinning its new wording into place.
AGENTS_MD="$SCRIPT_DIR/../../../AGENTS.md"
if [ ! -r "$AGENTS_MD" ]; then
	fail "AGENTS.md exists ($AGENTS_MD)"
elif grep -Eq '\*\*no\*\* filtra' "$AGENTS_MD"; then
	fail "AGENTS.md still claims the push trigger is unfiltered"
else
	pass "AGENTS.md claims no push-trigger asymmetry"
fi

# CONTRIBUTING.md carried the other half of the same claim, and `L21`'s repair read only AGENTS.md:
# "Every workflow also runs on `push` to `main`, so a merge re-runs the gate even when the pull
# request ran none of it" stayed true-looking beside four filtered push triggers and a label guard
# with no push trigger. The sentence wraps, so the file is read as one line before matching.
CONTRIBUTING_MD="$SCRIPT_DIR/../../../CONTRIBUTING.md"
if [ ! -r "$CONTRIBUTING_MD" ]; then
	fail "CONTRIBUTING.md exists ($CONTRIBUTING_MD)"
elif tr '\n' ' ' <"$CONTRIBUTING_MD" | grep -Eqi 'every workflow +also runs on `push` to `main`'; then
	fail "CONTRIBUTING.md still claims every workflow runs on push to main"
else
	pass "CONTRIBUTING.md claims no universal push trigger"
fi

printf '\n%d passed, %d failed\n' "$PASS_COUNT" "$FAIL_COUNT"
[ "$FAIL_COUNT" -eq 0 ]
