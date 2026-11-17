#!/bin/bash
# ============================================
# LifeControl - every commit an ODD record cites is in main, or is a frozen exception
# ============================================
# A record is the audit trail of a unit, and it proves provenance with commits. A citation that is not
# an ancestor of `origin/main` is worse than no citation, because it still resolves as an object and
# therefore reads as evidence. `odd-status-headers.test.sh` resolves ancestry only for the merge commit
# a Status paragraph chooses to name; the body of a `Log` entry and the `Tasks` table were unguarded,
# which is where every orphaned citation in this repository lives.
#
# Measured on 2026-11-17, the day this suite was written: of 1472 backticked commit citations across the
# record directory, 33 (record, commit) pairs are not ancestors of `origin/main`. They are frozen in the
# list below rather than repaired, because rule 5 of `references/feature-records.md` says a record's
# frozen narrative is evidence and is not rewritten. The count is read from the run, never from a number
# written in prose: it moves on its own as branches merge.
#
# WHAT COUNTS AS A CITATION
#
# Only a backticked hex token, 7 to 40 characters, that resolves as a commit. The scoping is deliberate
# and it is the one `odd-status-headers-guard.md` records as decision D3, measured there: the directory
# legitimately backticks patch-ids and tree objects that no repository can resolve as commits, and a
# bare hex string is prose. So a patch-id or a tree is not a citation, needs no entry, and produces no
# finding -- which the fixtures assert rather than assume.
#
# WHY AN ALLOWLIST, WHEN feature-records.md ARGUES AGAINST ONE
#
# That argument is about phrases: a denylist of forbidden wording only rejects what someone imagined, so
# the header rule was inverted into a closed set of verdict tokens. This list is the opposite shape. The
# default is fail, each exception is enumerated, each one is proven to still resolve as a commit and to
# still be cited by the record it names, and the list only shrinks. A closed guard with a frozen defect
# register is not a denylist; it is a bug list that cannot grow silently.
#
# ONE LIST, AND WHY THERE IS NO SECOND KIND
#
# An earlier revision carried two kinds, the second being `declared-non-arrival` -- an entry whose record
# states the commit never reached `main`. The guard cannot read that statement: any check of it is either
# a list of acceptable wordings, which is the denylist this repository rejects, or a mandatory marker
# token that would have to be written into frozen records. A guard that cannot verify a claim must not
# make it, so the distinction is gone and this list only says one thing: this citation is frozen, and the
# guard will stop the next one. That is a recorded deviation from S8 of `odd/tasks/odd-citation-provenance.md`.
#
# THE LIST ONLY SHRINKS, AND THAT IS THE POINT
#
# An entry whose commit reaches `main` is NOT a finding: it is the end state this rule exists to reach,
# and it is removed when someone notices. A rule that failed CI on `main` until an unrelated pull request
# edited this list would recreate the maintenance cost the unit exists to remove -- measured in this
# repository at 51 of the last 150 merged pull requests, which touched nothing but records. Every case
# below that needs an orphan builds one in a scratch repository, so no fixture depends on a commit of
# this repository staying out of `main`.
#
# No network, no `gh`, no YAML library: the records are markdown and git is the only dependency.
#
# Run: ./.github/scripts/tests/odd-citations.test.sh
# Exits 0 only when every case passes.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# The tree to audit. The first argument (or REPO_ROOT) lets a fixture point the same suite at a
# scratch tree; the default is this checkout, derived from the script's own location so the answer
# does not depend on the caller's working directory.
ROOT="${1:-${REPO_ROOT:-$SCRIPT_DIR/../../..}}"
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

# ---------------------------------------------------------------- the frozen list
#
# One entry per (record, commit) pair, `sha|repo-relative-path`. Nothing else: no kind, no reason, no
# date. A field the guard cannot check is a field that invites a false claim.
exceptions() {
	cat <<'EOF'
22b9531e|odd/tasks/tax-rate-write-honesty.md
22b9531e|odd/tasks/odd-citation-provenance.md
cec4578|odd/tasks/project-conventions-skill.md
cec4578|odd/tasks/odd-status-reconciliation.md
cec4578|odd/tasks/odd-status-headers-guard.md
cec4578|odd/tasks/odd-citation-provenance.md
cec4578|odd/tasks/purchase-order-goods-receipt.md
bc4dd00|odd/tasks/audit-trail-completion.md
b605d6f3|odd/tasks/configuration-tax-rates.md
8493fa1d|odd/tasks/derived-accounting.md
6a06a9b3|odd/tasks/inventory-ledger.md
4fb4c6c4|odd/tasks/ledger-mirror-retirement.md
5ad8fa7a|odd/tasks/ledger-mirror-retirement.md
6a06a9b3|odd/tasks/ledger-mirror-retirement.md
d6fbc815|odd/tasks/ledger-mirror-retirement.md
c698e4c9|odd/tasks/ledger-per-write-identity.md
143a5f35|odd/tasks/ledger-per-write-identity.md
a10d2cec|odd/tasks/money-first-class.md
f0978e01|odd/tasks/money-first-class.md
d1e6e8ef|odd/tasks/money-first-class.md
08ceb91e|odd/tasks/product-category-sat-code.md
cc67e78a|odd/tasks/product-category-sat-code.md
6f1831e3|odd/tasks/product-tax-code.md
da055432|odd/tasks/product-tax-code.md
aca0739b|odd/tasks/product-tax-code.md
e9fe0bd5|odd/tasks/product-tax-code.md
970adac|odd/tasks/purchase-order-fractional-quantity.md
a3fd81f|odd/tasks/purchase-order-goods-receipt.md
a5dcb72|odd/tasks/purchase-order-goods-receipt.md
b0f253f0|odd/tasks/starter-catalog-applier-tenant-scope.md
504b1209|odd/tasks/store-location-chain.md
d0079d2c|odd/tasks/supplier-catalog-tenancy.md
b5f4b9ca|odd/tasks/variant-listing-tax-rate.md
EOF
}

# origin_main <git-root>
# Prints the commit the citations have to be in, or nothing when it cannot be resolved.
#
# ONE rung, and it is `refs/remotes/origin/main` only. A local `refs/heads/main` was tried and removed: it
# let an unpushed local commit count as an ancestor, and the rule the suite enforces is ancestry of
# `origin/main`, not of whatever this clone happens to have checked out. When the ref does not resolve the
# audit says so out loud instead of reporting a green it did not earn, and the workflow is what guarantees
# it resolves in CI -- by fetching that exact ref before the suites run.
origin_main() {
	git -C "$1" rev-parse --verify --quiet refs/remotes/origin/main 2>/dev/null
}

# is_commit <git-root> <sha>
is_commit() {
	git -C "$1" cat-file -e "$2^{commit}" 2>/dev/null
}

# is_ancestor <git-root> <sha> <main-sha>
is_ancestor() {
	git -C "$1" merge-base --is-ancestor "$2" "$3" 2>/dev/null
}

# citations <record-root> <git-root>
# Prints `record<TAB>sha` for every backticked commit citation, deduplicated per record. A token that
# does not resolve as a commit is not a citation and is not printed -- see the header note.
citations() {
	local root="$1" git_root="$2" dir="$1/odd/tasks" file rel token

	[ -d "$dir" ] || return 0
	for file in "$dir"/*.md; do
		[ -f "$file" ] || continue
		rel="${file#"$root"/}"
		grep -oE '`[0-9a-f]{7,40}`' "$file" 2>/dev/null | tr -d '`' | sort -u |
			while IFS= read -r token; do
				[ -n "$token" ] || continue
				if is_commit "$git_root" "$token"; then
					printf '%s\t%s\n' "$rel" "$token"
				fi
			done
	done
}

# register_lists <register-file> <record> <sha>
register_lists() {
	[ -n "$1" ] && [ -f "$1" ] || return 1
	awk -F'|' -v f="$2" -v s="$3" '$1 == s && $2 == f { found = 1 } END { exit found ? 0 : 1 }' "$1"
}

# cited_by <record-root> <record> <sha>
cited_by() {
	grep -qF "\`$3\`" "$1/$2" 2>/dev/null
}

# audit <record-root> <register-file> <git-root>
# Reads every `odd/tasks/*.md` under <record-root>, prints one `PROBLEM:` line per finding, then the
# counts and the ancestry verdict. The three roots are separate on purpose: the fixtures audit a
# synthetic or copied record set against a scratch repository, which is what lets every fail-closed case
# run without touching a single byte of this tree.
audit() {
	local root="$1" register="$2" git_root="$3"
	local dir="$root/odd/tasks" file rel sha problems=0 records=0 cited=0 absorbed=0 ancestors=0
	local entries=0 unverifiable=0 main_sha ancestry="skipped"

	main_sha="$(origin_main "$git_root")"
	if [ -n "$main_sha" ]; then
		ancestry="ran"
	fi

	if [ ! -d "$dir" ]; then
		printf 'PROBLEM: no readable record directory at %s\n' "$dir"
		printf 'READ 0\n'
		printf 'ancestry: skipped (no record directory to audit)\n'
		return 1
	fi

	for file in "$dir"/*.md; do
		if [ -f "$file" ]; then
			records=$((records + 1))
		fi
	done

	while IFS='	' read -r rel sha; do
		[ -n "$sha" ] || continue
		cited=$((cited + 1))
		[ "$ancestry" = ran ] || continue
		if is_ancestor "$git_root" "$sha" "$main_sha"; then
			ancestors=$((ancestors + 1))
			continue
		fi
		if register_lists "$register" "$rel" "$sha"; then
			absorbed=$((absorbed + 1))
			continue
		fi
		printf 'PROBLEM: %s: cites %s, which is not an ancestor of origin/main and is not in the frozen list; cite content (a patch-id or a tree) or the merge commit, or bring it up to date with a merge before the evidence entry is written\n' \
			"$rel" "'$sha'"
		problems=$((problems + 1))
	done < <(citations "$root" "$git_root")

	# The list is checked in both directions. An entry that stopped resolving as a commit is a finding
	# even though no record complains about it; an entry whose record no longer cites it is rot that a
	# shrinking list would otherwise carry forever.
	if [ "$ancestry" = ran ] && [ -n "$register" ] && [ -f "$register" ]; then
		while IFS= read -r line; do
			[ -n "$line" ] || continue
			sha="${line%%|*}"
			rel="${line#*|}"
			entries=$((entries + 1))
			if [ "$rel" = "$line" ]; then
				printf 'PROBLEM: the frozen list has the line %s, which is not sha|path\n' "'$line'"
				problems=$((problems + 1))
				continue
			fi
			if ! git -C "$git_root" cat-file -e "$sha" 2>/dev/null; then
				# An orphaned commit hangs off no branch, so `git fetch` never brings it: a CI checkout has
				# main's history and the pull request's, and nothing else. Failing here would make the suite
				# red on every CI run for a fact about the clone rather than about the records, so the entry
				# is counted as unverifiable and the count is printed. A typo is still caught where the
				# objects exist, which is every clone that ever fetched the branch the commit came from.
				unverifiable=$((unverifiable + 1))
				continue
			fi
			if ! is_commit "$git_root" "$sha"; then
				printf 'PROBLEM: %s: the frozen list carries %s and it resolves as an object that is not a commit; an entry that stops resolving is a finding, not a pass\n' \
					"$rel" "'$sha'"
				problems=$((problems + 1))
				continue
			fi
			if ! cited_by "$root" "$rel" "$sha"; then
				printf 'PROBLEM: %s: the frozen list carries %s and the record no longer cites it; drop the entry together with the citation it froze\n' \
					"$rel" "'$sha'"
				problems=$((problems + 1))
			fi
		done <"$register"
	fi

	printf 'READ %d\n' "$records"
	printf 'CITED %d\n' "$cited"
	printf 'ABSORBED %d\n' "$absorbed"
	printf 'REGISTER %d\n' "$entries"
	printf 'UNVERIFIABLE %d\n' "$unverifiable"
	if [ "$unverifiable" -gt 0 ]; then
		printf 'NOTE: %d frozen entries could not be checked, because their commits hang off no branch and this clone never fetched them.\n' \
			"$unverifiable"
	fi
	if [ "$ancestry" = ran ]; then
		printf 'ANCESTORS %d\n' "$ancestors"
		printf 'ancestry: ran (origin/main @ %s)\n' "$(printf '%s' "$main_sha" | cut -c1-7)"
	else
		printf 'ancestry: skipped (origin/main does not resolve in %s)\n' "$git_root"
	fi

	[ "$problems" -eq 0 ] && [ "$records" -gt 0 ]
}

# ---------------------------------------------------------------- fixtures
#
# Every fail-closed case is built in a scratch repository of its own. None of them cites a commit of
# this repository: a fixture that borrowed one would rot the day that commit reached `main`, which is
# exactly the state the rule says must stay legal.

new_fixture() {
	local dir="$TMP/fixtures/$1"
	mkdir -p "$dir/odd/tasks"
	printf '%s\n' "$dir"
}

# new_git_fixture <name> -> prints the root of a scratch repository with two commits: `origin/main`
# points at the first, and the second lives on a `side` branch that was never merged, so its hash
# resolves as a commit and is provably not an ancestor of `origin/main`.
new_git_fixture() {
	local dir="$TMP/fixtures/$1"
	mkdir -p "$dir/odd/tasks"
	git -C "$dir" init -q -b main
	git -C "$dir" -c user.email=fixture@example.invalid -c user.name=fixture \
		commit -q --allow-empty -m 'the delivered base'
	git -C "$dir" update-ref refs/remotes/origin/main "$(git -C "$dir" rev-parse HEAD)"
	git -C "$dir" checkout -q -b side
	git -C "$dir" -c user.email=fixture@example.invalid -c user.name=fixture \
		commit -q --allow-empty -m 'a commit that never reached main'
	git -C "$dir" checkout -q main
	printf '%s\n' "$dir"
}

# expect_audit <label> <record-root> <register> <git-root> <pass|fail>
expect_audit() {
	local label="$1" want="$5" out code

	if out="$(audit "$2" "$3" "$4")"; then
		code=0
	else
		code=$?
	fi

	if { [ "$want" = pass ] && [ "$code" -eq 0 ]; } || { [ "$want" = fail ] && [ "$code" -ne 0 ]; }; then
		pass "$label"
	else
		fail "$label (expected a $want run, got exit $code)"
		printf '%s\n' "$out" | sed 's/^/    /'
	fi
}

# expect_mentions <label> <needle> <haystack-output>
expect_mentions() {
	local label="$1" needle="$2" out="$3"

	case "$out" in
	*"$needle"*) pass "$label" ;;
	*)
		fail "$label (output does not contain '$needle')"
		printf '%s\n' "$out" | sed 's/^/    /'
		;;
	esac
}

# run_audit_capture <record-root> <register> <git-root> -> sets OUT and CODE
run_audit_capture() {
	if OUT="$(audit "$1" "$2" "$3" 2>&1)"; then CODE=0; else CODE=$?; fi
}

REGISTER="$TMP/register"
exceptions >"$REGISTER"
: >"$TMP/register-empty"

echo "== the frozen list is well formed =="
MALFORMED="$(grep -c -v -E '^[0-9a-f]{7,40}\|[^|]+$' "$REGISTER")"
if [ "$MALFORMED" -eq 0 ]; then
	pass "every line is sha|path, with no kind, reason or stray field"
else
	fail "the frozen list has lines that are not sha|path"
	grep -v -E '^[0-9a-f]{7,40}\|[^|]+$' "$REGISTER" | sed 's/^/    /'
fi
DUPLICATES="$(cut -d'|' -f1,2 "$REGISTER" | sort | uniq -d | wc -l | tr -d ' ')"
if [ "$DUPLICATES" -eq 0 ]; then
	pass "no (record, commit) pair is listed twice"
else
	fail "the frozen list repeats a (record, commit) pair"
	cut -d'|' -f1,2 "$REGISTER" | sort | uniq -d | sed 's/^/    /'
fi

echo
echo "== the real tree is green before any fixture runs =="
# First, and before any failure case: a false positive on this tree must not be reportable as a caught
# drift, so the tree is audited as it stands.
run_audit_capture "$ROOT" "$REGISTER" "$ROOT"
REAL_OUT="$OUT"
REAL_CODE="$CODE"
REAL_RECORDS="$(printf '%s\n' "$REAL_OUT" | awk '$1 == "READ" { print $2 }')"
REAL_CITED="$(printf '%s\n' "$REAL_OUT" | awk '$1 == "CITED" { print $2 }')"
REAL_ABSORBED="$(printf '%s\n' "$REAL_OUT" | awk '$1 == "ABSORBED" { print $2 }')"
REAL_ANCESTORS="$(printf '%s\n' "$REAL_OUT" | awk '$1 == "ANCESTORS" { print $2 }')"
REAL_UNVERIFIABLE="$(printf '%s\n' "$REAL_OUT" | awk '$1 == "UNVERIFIABLE" { print $2 }')"
REAL_ANCESTRY="$(printf '%s\n' "$REAL_OUT" | awk '$1 == "ancestry:" { print $2 }')"

if [ "$REAL_CODE" -eq 0 ]; then
	pass "the tree on the branch passes"
else
	fail "the tree on the branch passes (expected a pass run, got exit $REAL_CODE)"
	printf '%s\n' "$REAL_OUT" | sed 's/^/    /'
fi

EXPECTED_RECORDS="$(find "$ROOT/odd/tasks" -maxdepth 1 -name '*.md' -type f | wc -l | tr -d ' ')"
if [ "$REAL_RECORDS" = "$EXPECTED_RECORDS" ] && [ "$REAL_RECORDS" -gt 0 ]; then
	pass "the audit read all $REAL_RECORDS record(s), so it cannot pass vacuously"
else
	fail "the audit read $REAL_RECORDS of $EXPECTED_RECORDS record(s)"
fi

# A skip is announced, never fatal. This is the sibling suite's rule and the reason the workflow now asks
# for full history: with a shallow checkout nothing resolves, and a suite that turns that into a red run
# gets switched off, while a suite that turns it into a silent pass verifies nothing.
if [ "$REAL_ANCESTRY" = ran ]; then
	pass "the ancestry half ran, so every citation was resolved against a base"
else
	pass "the ancestry half announced a skip instead of claiming a green it did not earn"
	printf 'NOTE: no base resolved, so this run verified no citation at all. A shallow checkout cannot verify anything.\n'
fi

# Derived from the run, never pinned: the three numbers partition the citations, whatever they are
# today, so this holds as the list shrinks and as `main` adopts commits that were once orphans.
# The partition only means something when a base resolved: with a skip there is no ancestor count to add,
# and asserting arithmetic over missing terms would fail on a state the suite just announced as legal.
if [ "$REAL_ANCESTRY" != ran ]; then
	pass "the partition is not asserted, because no base resolved to partition against"
elif [ $((REAL_ABSORBED + REAL_ANCESTORS)) -eq "$REAL_CITED" ]; then
	pass "every citation is either in main ($REAL_ANCESTORS) or absorbed by the list ($REAL_ABSORBED)"
else
	fail "the counts do not partition: $REAL_ABSORBED absorbed + $REAL_ANCESTORS ancestors != $REAL_CITED cited"
fi

echo
echo "== a citation of a commit that is not an ancestor of origin/main fails, naming both =="
S5="$(new_git_fixture s5)"
printf '**Status**: implemented — a fixture citing `%s`.\n' "$(git -C "$S5" rev-parse side)" >"$S5/odd/tasks/citing.md"
run_audit_capture "$S5" "$TMP/register-empty" "$S5"
expect_mentions "a non-ancestor citation fails and names the record" "citing.md" "$OUT"
expect_mentions "a non-ancestor citation fails and names the SHA" "$(git -C "$S5" rev-parse side)" "$OUT"
expect_audit "a non-ancestor citation is a failure" "$S5" "$TMP/register-empty" "$S5" fail

echo
echo "== a patch-id or a tree object is not a citation and needs no entry =="
S9="$(new_git_fixture s9)"
cat >"$S9/odd/tasks/content-proof.md" <<'EOF'
**Status**: implemented — a fixture.

Proven by content: the patch-id of what the merge added is `5ef49972f608ede1d41115f302e4c4eec3ce3a8b`,
and the tree is `54c5a75fc41`.
EOF
run_audit_capture "$S9" "$TMP/register-empty" "$S9"
expect_audit "a record whose only hex tokens are a patch-id and a tree passes" "$S9" "$TMP/register-empty" "$S9" pass
expect_mentions "and neither token is counted as a citation" "CITED 0" "$OUT"

echo
echo "== one listed (record, commit) pair absorbs it; the other record's does not =="
S6="$(new_git_fixture s6)"
SIDE="$(git -C "$S6" rev-parse side)"
printf '**Status**: implemented — a fixture citing `%s`.\n' "$SIDE" >"$S6/odd/tasks/one.md"
printf '**Status**: implemented — a fixture citing `%s`.\n' "$SIDE" >"$S6/odd/tasks/two.md"
printf '%s|odd/tasks/one.md\n%s|odd/tasks/two.md\n' "$SIDE" "$SIDE" >"$TMP/register-both"
printf '%s|odd/tasks/one.md\n' "$SIDE" >"$TMP/register-one"
expect_audit "a list that covers every citation passes" "$S6" "$TMP/register-both" "$S6" pass
run_audit_capture "$S6" "$TMP/register-one" "$S6"
expect_mentions "dropping an entry fails for the record that still cites it" "two.md" "$OUT"
expect_audit "dropping an entry fails on the same record set" "$S6" "$TMP/register-one" "$S6" fail

echo
echo "== an entry whose commit reaches main is stale, never red =="
# The case that keeps this list from becoming a maintenance burden, and the reason no fixture borrows a
# commit of this repository: a citation that was orphaned and is now in `main` is the state the rule
# wants, so an entry left behind for it must not fail.
S7="$(new_git_fixture s7)"
DELIVERED="$(git -C "$S7" rev-parse main)"
printf '**Status**: implemented — a fixture citing `%s`.\n' "$DELIVERED" >"$S7/odd/tasks/delivered.md"
printf '%s|odd/tasks/delivered.md\n' "$DELIVERED" >"$TMP/register-stale"
expect_audit "a listed entry whose commit is now an ancestor passes" "$S7" "$TMP/register-stale" "$S7" pass
run_audit_capture "$S7" "$TMP/register-empty" "$S7"
expect_audit "and the same record passes with no entry at all" "$S7" "$TMP/register-empty" "$S7" pass

echo
echo "== an entry that resolves as something other than a commit fails =="
# A real tree object of the fixture repository, not a made-up hex string: a fabricated sha is absent, and an
# absent object is the unverifiable case, so it would prove the wrong thing.
printf '%s|odd/tasks/delivered.md\n' "$(git -C "$S7" rev-parse 'HEAD^{tree}')" >"$TMP/register-tree"
run_audit_capture "$S7" "$TMP/register-tree" "$S7"
expect_mentions "a tree object registered as a commit is a finding" "resolves as an object that is not a commit" "$OUT"
expect_audit "an entry that is not a commit fails" "$S7" "$TMP/register-tree" "$S7" fail

echo
echo "== an entry whose object this clone never fetched is announced, not failed =="
# The CI shape: an orphaned commit hangs off no branch, so `git fetch` never brings it. The entry cannot be
# checked there, and saying so is the honest answer -- failing would be a finding about the clone.
printf '0123456789012345678901234567890123456789|odd/tasks/delivered.md\n' >"$TMP/register-absent"
run_audit_capture "$S7" "$TMP/register-absent" "$S7"
expect_mentions "an absent object is counted rather than reported as a defect" "UNVERIFIABLE 1" "$OUT"
expect_audit "and the run still passes" "$S7" "$TMP/register-absent" "$S7" pass

echo
echo "== an entry the named record no longer cites fails =="
printf '%s|odd/tasks/absent.md\n' "$DELIVERED" >"$TMP/register-rot"
run_audit_capture "$S7" "$TMP/register-rot" "$S7"
expect_mentions "an entry for a record that does not cite it is rot, and a finding" "no longer cites it" "$OUT"
expect_audit "a rotten entry fails" "$S7" "$TMP/register-rot" "$S7" fail

echo
echo "== a well formed list of one field is a finding, not a silent pass =="
printf '%s\n' "$DELIVERED" >"$TMP/register-malformed"
run_audit_capture "$S7" "$TMP/register-malformed" "$S7"
expect_mentions "a line with no path is rejected" "which is not sha|path" "$OUT"

echo
echo "== the guard cannot pass vacuously =="
EMPTY="$(new_git_fixture empty)"
expect_audit "a tree with no record at all fails" "$EMPTY" "$TMP/register-empty" "$EMPTY" fail
NODIR="$TMP/fixtures/no-dir"
mkdir -p "$NODIR"
expect_audit "a tree with no record directory fails" "$NODIR" "$TMP/register-empty" "$NODIR" fail

echo
 echo "== without origin/main the ancestry half announces the skip instead of passing silently =="
NOORIGIN="$TMP/fixtures/no-origin"
mkdir -p "$NOORIGIN/odd/tasks"
# Not `main`, and not a merge commit: this fixture has to defeat every rung of the resolution order,
# which is exactly the state a shallow or unusual checkout is in.
git -C "$NOORIGIN" init -q -b trunk
git -C "$NOORIGIN" -c user.email=fixture@example.invalid -c user.name=fixture commit -q --allow-empty -m 'no remote here'
printf '**Status**: implemented — a fixture citing `%s`.\n' "$(git -C "$NOORIGIN" rev-parse HEAD)" >"$NOORIGIN/odd/tasks/citing.md"
run_audit_capture "$NOORIGIN" "$TMP/register-empty" "$NOORIGIN"
expect_audit "a tree whose origin/main does not resolve still passes its own cases" "$NOORIGIN" "$TMP/register-empty" "$NOORIGIN" pass
expect_mentions "and says so on stdout rather than reporting a green ancestry check" "ancestry: skipped" "$OUT"

echo
echo "== a local branch that diverged from origin/main is not the authority =="
# The rung that was tried and removed. A commit on a local `main` and not on `origin/main` is exactly a
# citation the rule forbids, so an audit that accepted the local branch would be green on it.
DIV="$TMP/fixtures/diverged"
mkdir -p "$DIV/odd/tasks"
git -C "$DIV" init -q -b main
git -C "$DIV" -c user.email=fixture@example.invalid -c user.name=fixture \
	commit -q --allow-empty -m 'the delivered base'
git -C "$DIV" update-ref refs/remotes/origin/main "$(git -C "$DIV" rev-parse HEAD)"
git -C "$DIV" -c user.email=fixture@example.invalid -c user.name=fixture \
	commit -q --allow-empty -m 'a local commit that never reached origin/main'
printf '**Status**: implemented — a fixture citing `%s`.\n' "$(git -C "$DIV" rev-parse HEAD)" >"$DIV/odd/tasks/local.md"
run_audit_capture "$DIV" "$TMP/register-empty" "$DIV"
expect_mentions "a local commit outside origin/main is not treated as an ancestor" "local.md" "$OUT"
expect_audit "and citing it is a failure" "$DIV" "$TMP/register-empty" "$DIV" fail

printf '\nchecked %s record(s), %s citation(s): %s in main, %s absorbed by the frozen list; ancestry: %s\n' \
	"$REAL_RECORDS" "$REAL_CITED" "$REAL_ANCESTORS" "$REAL_ABSORBED" "$REAL_ANCESTRY"
# A green run has to say what it did not check. Thirty-three entries are unverifiable in a clone that never
# fetched the orphaned commits, and printing that only on failure would make the CI run look complete.
if [ "${REAL_UNVERIFIABLE:-0}" -gt 0 ]; then
	printf 'NOTE: %s frozen entries were NOT checked: their commits hang off no branch, so this clone never fetched them.\n' \
		"$REAL_UNVERIFIABLE"
fi
printf '%d fixture(s) asserted\n' "$(find "$TMP/fixtures" -mindepth 1 -maxdepth 1 -type d | wc -l | tr -d ' ')"
printf '%d passed, %d failed\n' "$PASS_COUNT" "$FAIL_COUNT"
[ "$FAIL_COUNT" -eq 0 ]
