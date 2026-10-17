#!/bin/bash
# ============================================
# LifeControl - issue reference gate
# ============================================
# Answers one question about one pull request: does it reference an open issue that carries
# `status:approved`? The gate is the issue-side half of the flow the PR template declares,
# and until this script existed the link was a review-time convention: of the last 60 merged
# pull requests, 55 carried no reference at all.
#
# The failure modes are the point, and each one has its own case below:
#
#   1. no reference at all;
#   2. `Refs #N` on the default branch, where GitHub would ignore the keyword anyway;
#   3. a closing keyword naming a number that is a pull request, not an issue — a keyword
#      that closes nothing this gate can verify;
#   4. an issue that is not open, or does not carry `status:approved`;
#   5. a cross-repository reference, which this repository's token cannot verify;
#   6. an unreadable body, malformed JSON, an unexpected number or a missing argument.
#
# `Refs #N` is accepted off the default branch on purpose: GitHub interprets closing keywords
# only when the pull request targets the default branch, so a chained slice cannot close its
# issue from the child pull request.
#
# No network, no `gh`: the workflow fetches the referenced numbers and hands them in as JSON,
# so every rule here is decided offline. `jq` is required for the JSON, as in
# check-pr-type-labels.sh.
#
# The fixtures below use the payload shape the REST API actually returns, read back from
# `gh api repos/le03nava/LifeControl/issues/366` on 2026-10-17: `"state":"closed"` in lower
# case, with `labels` as objects carrying `name`. The functional check that read that payload
# is what caught the first version of the gate comparing against `OPEN`: the offline fixtures
# had agreed with the bug, and a real open issue would have been rejected on every pull
# request. One case below keeps an upper-case state so the normalisation stays pinned.
#
# Run: ./.github/scripts/tests/check-issue-reference.test.sh
# Exits 0 only when every case passes.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GUARD="$SCRIPT_DIR/../check-issue-reference.sh"
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

# check <label> <exit> <exact stdout or -> <stderr fragment or -> <command...>
check() {
	local label="$1" want_exit="$2" want_stdout="$3" want_stderr="$4"
	shift 4
	local out rc err
	out="$("$@" 2>"$TMP/stderr")"
	rc=$?
	err="$(cat "$TMP/stderr")"
	if [ "$rc" -ne "$want_exit" ]; then
		fail "$label (exit $rc, wanted $want_exit; stderr: $err)"
		return
	fi
	if [ "$want_stdout" != "-" ] && [ "$out" != "$want_stdout" ]; then
		fail "$label (stdout: $out)"
		return
	fi
	if [ "$want_stderr" != "-" ] && ! printf '%s' "$err" | grep -qF -- "$want_stderr"; then
		fail "$label (stderr: $err)"
		return
	fi
	pass "$label"
}

DEFAULT=main
TRACKER=chore/pr-issue-governance

APPROVED='[{"number":12,"state":"open","labels":[{"name":"status:approved"}]}]'
APPROVED_UPPER='[{"number":12,"state":"OPEN","labels":[{"name":"status:approved"}]}]'
UNAPPROVED='[{"number":12,"state":"open","labels":[{"name":"bug"}]}]'
CLOSED='[{"number":12,"state":"closed","labels":[{"name":"status:approved"}]}]'
A_PULL_REQUEST='[{"number":12,"state":"open","labels":[{"name":"status:approved"}],"pull_request":{"url":"https://api.github.com/repos/le03nava/LifeControl/pulls/12"}}]'
PULL_REQUEST_AND_APPROVED_ISSUE='[{"number":4,"state":"open","pull_request":{"url":"https://api.github.com/repos/le03nava/LifeControl/pulls/4"}},{"number":12,"state":"open","labels":[{"name":"status:approved"}]}]'
UNEXPECTED_NUMBER='[{"number":99,"state":"open","labels":[{"name":"status:approved"}]}]'

echo "== argument handling =="
check "a missing --base-branch fails closed" 1 - "FAIL: --base-branch is required" \
	"$GUARD" --body "Closes #12" --default-branch "$DEFAULT" --issues-json "$APPROVED"
check "a missing --default-branch fails closed" 1 - "FAIL: --default-branch is required" \
	"$GUARD" --body "Closes #12" --base-branch "$DEFAULT" --issues-json "$APPROVED"
check "missing both body sources fails closed" 1 - "provide --body or --body-file" \
	"$GUARD" --base-branch "$DEFAULT" --default-branch "$DEFAULT" --issues-json "$APPROVED"
check "an unknown argument fails closed" 1 - "unknown argument" \
	"$GUARD" --body "Closes #12" --base-branch "$DEFAULT" --default-branch "$DEFAULT" \
	--issues-json "$APPROVED" --labels-json "[]"
check "an unreadable body file fails closed" 1 - "FAIL: cannot read --body-file" \
	"$GUARD" --body-file "$TMP/absent.md" --base-branch "$DEFAULT" --default-branch "$DEFAULT" \
	--issues-json "$APPROVED"
check "an empty body fails closed" 1 - "FAIL: the pull request body is empty" \
	"$GUARD" --body "   " --base-branch "$DEFAULT" --default-branch "$DEFAULT" --issues-json "$APPROVED"
check "a missing issue payload fails closed" 1 - "provide --issues-json or --issues-json-file" \
	"$GUARD" --body "Closes #12" --base-branch "$DEFAULT" --default-branch "$DEFAULT"

echo "== the reference itself =="
check "a body with no reference fails" 1 - "FAIL: no issue reference in the pull request body" \
	"$GUARD" --body "This changes the menu." --base-branch "$DEFAULT" --default-branch "$DEFAULT" --issues-json "$APPROVED"
check "Refs alone fails on the default branch" 1 - "a closing reference" \
	"$GUARD" --body "Refs #12" --base-branch "$DEFAULT" --default-branch "$DEFAULT" --issues-json "$APPROVED"
check "Refs alone passes off the default branch" 0 "PASS: #12 is open and carries status:approved" - \
	"$GUARD" --body "Refs #12" --base-branch "$TRACKER" --default-branch "$DEFAULT" --issues-json "$APPROVED"
check "a cross-repository reference fails" 1 - "cross-repository reference" \
	"$GUARD" --body "Closes le03nava/other#3" --base-branch "$DEFAULT" --default-branch "$DEFAULT" --issues-json "$APPROVED"
check "an uppercase keyword with a colon passes" 0 "PASS: #12 is open and carries status:approved" - \
	"$GUARD" --body "CLOSES: #12" --base-branch "$DEFAULT" --default-branch "$DEFAULT" --issues-json "$APPROVED"
# GitHub honors nine closing keywords, not three: a contributor who writes `Fix #12` or
# `resolve #12` means the same thing as `Closes #12`, and rejecting it would block a valid
# pull request over a conjugation.
check "a single-word closing keyword passes" 0 "PASS: #12 is open and carries status:approved" - \
	"$GUARD" --body "Fix #12" --base-branch "$DEFAULT" --default-branch "$DEFAULT" --issues-json "$APPROVED"
check "a past-tense closing keyword passes" 0 "PASS: #12 is open and carries status:approved" - \
	"$GUARD" --body "FIXED: #12" --base-branch "$DEFAULT" --default-branch "$DEFAULT" --issues-json "$APPROVED"
check "the resolve family closes too" 0 "PASS: #12 is open and carries status:approved" - \
	"$GUARD" --body "resolve #12" --base-branch "$DEFAULT" --default-branch "$DEFAULT" --issues-json "$APPROVED"
check "a keyword GitHub does not honor is not a reference" 1 - "FAIL: no issue reference" \
	"$GUARD" --body "Linked #12" --base-branch "$DEFAULT" --default-branch "$DEFAULT" --issues-json "$APPROVED"
# A pull request that documents this gate quotes its own keywords. Pull request #390 did exactly
# that: its body explained the corrected `Fix #12` case, the raw scan read the quotation as a
# closing reference, #12 happens to be a pull request in this repository, and the gate failed the
# pull request that fixes the gate. Only prose references count.
check "a quoted keyword is documentation, not a reference" 1 - "FAIL: no issue reference" \
	"$GUARD" --body 'The gate now accepts `Fix #12` as a closing keyword.' --base-branch "$DEFAULT" --default-branch "$DEFAULT" --issues-json "$APPROVED"
check "a fenced keyword is documentation, not a reference" 1 - "FAIL: no issue reference" \
	"$GUARD" --body '```
Closes #12
```' --base-branch "$DEFAULT" --default-branch "$DEFAULT" --issues-json "$APPROVED"
check "a quoted example beside a real reference passes" 0 "PASS: #12 is open and carries status:approved" - \
	"$GUARD" --body 'The old gate rejected `Fix #12`, which is a pull request here.

Closes #12' --base-branch "$DEFAULT" --default-branch "$DEFAULT" --issues-json "$APPROVED"

echo "== the referenced issue =="
check "an approved open issue passes" 0 "PASS: #12 is open and carries status:approved" - \
	"$GUARD" --body "Closes #12" --base-branch "$DEFAULT" --default-branch "$DEFAULT" --issues-json "$APPROVED"
check "an issue without status:approved fails" 1 - "does not carry the status:approved label" \
	"$GUARD" --body "Closes #12" --base-branch "$DEFAULT" --default-branch "$DEFAULT" --issues-json "$UNAPPROVED"
check "a closed issue fails" 1 - "FAIL: issue #12 is not open (state=closed)" \
	"$GUARD" --body "Closes #12" --base-branch "$DEFAULT" --default-branch "$DEFAULT" --issues-json "$CLOSED"
check "an upper-case state is still read as open" 0 "PASS: #12 is open and carries status:approved" - \
	"$GUARD" --body "Closes #12" --base-branch "$DEFAULT" --default-branch "$DEFAULT" --issues-json "$APPROVED_UPPER"
check "a closing keyword naming a pull request fails" 1 - "is a pull request" \
	"$GUARD" --body "Closes #12" --base-branch "$DEFAULT" --default-branch "$DEFAULT" --issues-json "$A_PULL_REQUEST"
check "a closing pull request reference fails even beside a valid issue" 1 - "is a pull request" \
	"$GUARD" --body "Closes #4
Closes #12" --base-branch "$DEFAULT" --default-branch "$DEFAULT" --issues-json "$PULL_REQUEST_AND_APPROVED_ISSUE"
check "Refs to a pull request alone does not satisfy the gate" 1 - "FAIL: no referenced number is an issue" \
	"$GUARD" --body "Refs #12" --base-branch "$TRACKER" --default-branch "$DEFAULT" --issues-json "$A_PULL_REQUEST"
check "an unexpected referenced number fails closed" 1 - "unexpected referenced number" \
	"$GUARD" --body "Closes #12" --base-branch "$DEFAULT" --default-branch "$DEFAULT" --issues-json "$UNEXPECTED_NUMBER"
check "malformed JSON fails closed" 1 - "not a JSON array" \
	"$GUARD" --body "Closes #12" --base-branch "$DEFAULT" --default-branch "$DEFAULT" --issues-json '{'
check "a JSON object instead of an array fails closed" 1 - "not a JSON array" \
	"$GUARD" --body "Closes #12" --base-branch "$DEFAULT" --default-branch "$DEFAULT" --issues-json '{"number":12}'
check "an unreadable --issues-json-file fails closed" 1 - "FAIL: cannot read --issues-json-file" \
	"$GUARD" --body "Closes #12" --base-branch "$DEFAULT" --default-branch "$DEFAULT" --issues-json-file "$TMP/absent.json"

echo "== extraction for the workflow =="
check "--print-refs emits the closing reference" 0 "12" - \
	"$GUARD" --body "Closes #12" --base-branch "$DEFAULT" --default-branch "$DEFAULT" --print-refs
check "--print-refs ignores a quoted example" 0 "12" - \
	"$GUARD" --body 'The old gate rejected `Fix #4` as a reference.

Closes #12' --base-branch "$DEFAULT" --default-branch "$DEFAULT" --print-refs
check "--print-refs emits every reference once, closing first" 0 "12
4" - \
	"$GUARD" --body "Closes #12
Refs #4
Refs #12" --base-branch "$TRACKER" --default-branch "$DEFAULT" --print-refs
check "--print-refs with no reference fails" 1 - "FAIL: no issue reference in the pull request body" \
	"$GUARD" --body "no reference here" --base-branch "$DEFAULT" --default-branch "$DEFAULT" --print-refs
check "--print-refs reads a body file" 0 "12" - \
	"$GUARD" --body-file <(printf 'Closes #12\n') --base-branch "$DEFAULT" --default-branch "$DEFAULT" --print-refs

printf '\n%d passed, %d failed\n' "$PASS_COUNT" "$FAIL_COUNT"
[ "$FAIL_COUNT" -eq 0 ]
