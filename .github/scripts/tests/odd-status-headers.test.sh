#!/bin/bash
# ============================================
# LifeControl - ODD record headers do not decline a delivery that landed
# ============================================
# Answers one question about `odd/tasks/*.md`: does any record's `**Status**:` line assert a
# *declining* delivery state over a delivery that already happened?
#
# That line is the only artifact every clone, every future session and every reviewer read, and
# nothing read it back: no workflow filters `odd/tasks/**` or any markdown, so a header that
# declined a delivery that had landed was falsified by the next merge, silently, with no commit
# touching the sentence that lied. Measured five times, each repaired by hand: pull requests #162
# (14 headers), #170, #364 (8), #436 (8) and #455 (4, issue #454).
#
# The fifth episode is the reason this guard reads a PARAGRAPH and not a line. The sweep that fed
# #436 was line-oriented, and `odd/tasks/mobile-header-repair.md`'s decline was split by a line
# wrap -- `This header makes no` ended one line and `claim about push or PR state` started the
# next -- so the filter could not see it, #436 declared that blind spot in its own verification
# table, and it still hid a fourth record for a day. Fixture `decline-across-line-wrap` below is
# that exact case.
#
# The vocabulary alone is NOT the rule. `odd/tasks/ci-speed.md` states its delivery (`#435`,
# `#443`, `#445`, `merged`) and still carries a legitimate hedge about the live push state. A
# naive "no declining vocabulary" rule would fail it, so the rule here is declining vocabulary
# WITH NO delivery evidence in the same paragraph -- the four stale headers had none, `ci-speed`
# has three. Fixture `ci-speed-shape` is that pass case, and it is the fixture the issue names by
# name.
#
# The vocabulary is split in two classes, because one kind of evidence is enough for one and not
# for the other:
#
#   - The DELIVERY-STATE class asserts that this unit's own delivery is still pending: `not pushed`,
#     `no PR is open`, `not merged`, `yet to land`, `awaiting review`, `pending review`,
#     `in progress`, `unstarted`, `what remains is the delivery`. For these, `#<number>` proves
#     nothing. Who owns a number is not decidable offline -- no `gh`, no token, no network, by
#     design -- and a header that declined its own landed delivery passed by citing ANOTHER unit's
#     pull request in an unrelated clause while this suite reported
#     `PASS: no record on the tree declines a delivery that landed`. So this class requires a MERGE
#     COMMIT: a hash a delivery marker introduces, which the ancestry half can check against
#     `origin/main`. Fixture `decline-with-foreign-number` is that incident. The hash is not proof of
#     ownership either -- nothing offline is -- but citing another unit's merge commit by accident is
#     a far narrower error than citing a number, and the ancestry half at least proves the commit
#     exists and landed.
#   - The SOFT class makes no claim about this unit's delivery: `implemented`, and the hedge
#     `makes no claim about ... push`. For these the rule is unchanged -- `has_delivery_evidence`,
#     which a `#<number>` satisfies -- because `implemented` says the work exists, not that the unit
#     has landed. Four records on the tree have that shape and stay green (fixture
#     `soft-phrase-with-number`).
#
# The split also closed a blind spot: the vocabulary listed `not merged yet`, so a bare `not merged`
# matched NOTHING and a header opening with "delivered on the branch, not merged" was invisible
# rather than wrongly satisfied (fixture `decline-bare-not-merged`). The phrase is now `not merged`,
# a superset.
#
# The cost, accepted deliberately: a genuinely mid-flight unit cannot say `awaiting review` and
# back it with only a pull request number -- it must name the merge commit, i.e. wait for the merge,
# or state what is not delivered without a delivery-state phrase. That is not a new obligation in
# practice: `.agents/skills/project-conventions/references/feature-records.md` already forbids every
# phrase of the delivery-state class in a record header, because the header has to state a verdict
# rather than a live state. Fixture `delivery-state-with-merge-commit` pins that the stricter class
# is satisfiable and not the impossible "never say awaiting review".
#
# The second half verifies the evidence a header claims. A merge commit it names must be an
# ancestor of `origin/main`; when `origin/main` does not resolve -- a shallow CI checkout has no
# such ref -- the half says `ancestry: skipped` out loud, because a silent skip is
# indistinguishable from a pass.
#
# What the ancestry half deliberately does NOT read: every backticked hex token. Measured on
# `main` @ `ab649a5`, the directory backticks patch-ids (`5ef49972f608...`) and tree objects
# (`54c5a75fc41...`, `8896dcb02c9...`, `ca4ceb38`) that no repository can resolve as commits, and
# it documents one commit that legitimately is NOT an ancestor -- `project-conventions-skill.md`
# names `cec4578` as "PR #129 was closed without merging and its commit `cec4578` never reached
# `main`". Failing on those would fire on an honest sentence and on four identities that are not
# commits at all. So only hashes a DELIVERY MARKER introduces are read: `merge commit`,
# `merged as`, `merged by`, `@` and `->`.
#
# The parse is scoped to the FIRST `**Status**:` paragraph, ending at a blank line, a Markdown
# heading or the next `**Field**:` line, with its whitespace collapsed. That scoping is what
# removes the allowlist `references/feature-records.md` used to say this guard would need: the
# records that legitimately quote the forbidden phrases (the template tables in
# `odd-record-convention.md`, the BEFORE blocks of `odd-header-hygiene.md`,
# `odd-status-reconciliation.md` and `odd-merged-header-reconciliation.md`, and the appended
# repair blocks of every repaired record) all keep those quotations OUTSIDE the first paragraph.
# `S6` asserts those four files stay green, which is the evidence.
#
# No network, no `gh`, no token, no YAML library: it reads `odd/tasks/*.md` and local `git` only,
# and writes nothing inside the repository. Point it at another checkout or a scratch copy by
# passing a root directory as the first argument (or with REPO_ROOT); that is how the fixtures
# run it without touching this tree.
#
# Run: ./.github/scripts/tests/odd-status-headers.test.sh
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

# status_paragraph <record file>
# Prints the first `**Status**:` paragraph with its whitespace collapsed to single spaces and its
# leading/trailing spaces trimmed. The paragraph starts on the first line that carries the
# `**Status**:` marker anywhere (a record may put `**Repository**:` above it) and ends at:
#   - a blank line,
#   - a Markdown heading (`#`),
#   - the next `**Field**:` line -- asterisks, a field name, asterisks, a colon. That last rule is
#     what keeps `**Repair, 2026-10-17 -- ...**:` inside the paragraph (its name carries a comma,
#     so it is not a field line) while stopping at `**Created**:` and `**Risk**:`.
# Collapsing is what makes a decline split across a line wrap decidable: without it, the fixture
# that reproduces the #436 blind spot would pass.
status_paragraph() {
	awk '
		BEGIN { started = 0; done = 0; para = "" }
		done { next }
		!started {
			if ((at = index($0, "**Status**:")) > 0) {
				started = 1
				para = substr($0, at + 11)
			}
			next
		}
		/^[[:space:]]*$/ { done = 1; next }
		/^#/ { done = 1; next }
		/^\*\*[A-Za-z][A-Za-z ]*\*\*:/ { done = 1; next }
		{ para = para " " $0 }
		END {
			gsub(/[[:space:]]+/, " ", para)
			sub(/^ /, "", para)
			sub(/ $/, "", para)
			print para
		}
	' "$1"
}

# declining_phrases <paragraph>
# Prints, one per line and deduplicated, every declining phrase the paragraph carries -- from BOTH
# classes the header comment describes. All of them, not the first by position: a header that is
# merely `implemented` and also hedges about the live push state carries two, and the hedge is the
# one a reader needs to see. `implemented` is in the list because a record that is merely
# implemented has no delivery to claim yet, so its header must either say what is missing or carry
# the delivery evidence that closes it. That choice has one consequence, recorded as D1 in the
# record: a header written before its pull request exists cannot pass this guard, and must name the
# pull request before the merge. This function feeds the failure messages; the RULE is
# `declines_its_delivery` plus `has_delivery_evidence` below.
declining_phrases() {
	printf '%s' "$1" | grep -oiE \
		'not pushed|no PR is open|not merged|yet to land|awaiting review|pending review|in progress|unstarted|what remains is the delivery|makes no claim about [^.]{0,60}push|implemented' \
		2>/dev/null | sort -u || true
}

# declines_its_delivery <paragraph>
# Answers whether the paragraph carries a phrase of the DELIVERY-STATE class: a claim that this
# unit's own delivery is still pending. Those phrases are the ones a bare `#<number>` cannot close,
# because nothing offline can say whose pull request a number is -- see the header comment.
declines_its_delivery() {
	printf '%s' "$1" | grep -qiE \
		'not pushed|no PR is open|not merged|yet to land|awaiting review|pending review|in progress|unstarted|what remains is the delivery' \
		2>/dev/null
}

# has_delivery_evidence <paragraph>
# Delivery evidence is `#<number>`, or a terminal token (merged / delivered / closed / done /
# superseded) together with a commit or PR identity. `done` alone is not evidence: a claim that
# cannot be checked against an identity is what the rule exists to reject.
has_delivery_evidence() {
	local p="$1"
	printf '%s' "$p" | grep -qE '#[0-9]+' && return 0
	if printf '%s' "$p" | grep -qiE '\b(merged|delivered|closed|done|superseded)\b' &&
		printf '%s' "$p" | grep -qE '\b[0-9a-f]{7,40}\b'; then
		return 0
	fi
	return 1
}

# delivery_hashes <paragraph>
# Prints, one per line and deduplicated, the commit hashes the paragraph introduces as evidence of
# delivery: `merge commit <hash>`, `merged as <hash>`, `merged by <hash>`, `<branch> @ <hash>` and
# `PR #<n> -> <hash>`. The gap between marker and hash is bounded to what those phrasings actually
# put there (`**`, a space), never a free-for-all scan of the paragraph -- see the header note.
delivery_hashes() {
	printf '%s' "$1" |
		grep -oE '(merge commit|merged as|merged by|→|@)[^`]{0,24}`[0-9a-f]{7,40}`' |
		grep -oE '`[0-9a-f]{7,40}`' |
		tr -d '`' |
		sort -u
}

# origin_main <root>
# Prints the resolved `origin/main` commit, or nothing when the ref does not exist (shallow
# checkout, a bare fixture, a tree that is not a repository at all).
origin_main() {
	git -C "$1" rev-parse --verify --quiet refs/remotes/origin/main 2>/dev/null
}

# audit <root>
# Reads every `odd/tasks/*.md` under <root>, prints one `PROBLEM:` line per finding, then
# `READ <n>` and the ancestry verdict. Exits 0 only when there is no finding and at least one
# record was read -- a parser that found nothing would agree with everything vacuously.
audit() {
	local root="$1"
	local dir="$root/odd/tasks"
	local main_sha file rel para declining hash problems=0 records=0 ancestry=skipped deliveries=0

	main_sha="$(origin_main "$root")"
	if [ -n "$main_sha" ]; then
		ancestry=ran
	fi

	if [ ! -d "$dir" ]; then
		printf 'PROBLEM: no readable record directory at %s\n' "$dir"
		printf 'READ 0\n'
		printf 'ancestry: skipped (no record directory to audit)\n'
		return 1
	fi

	for file in "$dir"/*.md; do
		[ -f "$file" ] || continue
		records=$((records + 1))
		rel="${file#"$root"/}"
		para="$(status_paragraph "$file")"

		if [ -z "$para" ]; then
			printf 'PROBLEM: %s: has no **Status**: paragraph, so its durable state is unreadable\n' "$rel"
			problems=$((problems + 1))
			continue
		fi

		# The rule, in two classes. A DELIVERY-STATE phrase needs a merge commit -- a bare number is not
		# ownership, and the guard is offline -- while a SOFT phrase keeps the older rule, a number or a
		# terminal word with an identity, because it never claimed this unit's delivery. The emptiness
		# tests are load-bearing: `grep` in a pipeline reports the last command's status, so testing an
		# assignment's status would enter the branch on every record.
		declining="$(declining_phrases "$para")"
		if [ -n "$declining" ]; then
			if declines_its_delivery "$para"; then
				if [ -z "$(delivery_hashes "$para")" ]; then
					printf 'PROBLEM: %s: declines its own delivery with no merge commit behind a delivery marker in its Status paragraph (phrases: %s)\n' \
						"$rel" "$(printf '%s' "$declining" | tr '\n' ',' | sed 's/,$//; s/,/, /g')"
					problems=$((problems + 1))
				fi
			elif ! has_delivery_evidence "$para"; then
				printf 'PROBLEM: %s: declines the delivery with no delivery evidence in its Status paragraph (phrases: %s)\n' \
					"$rel" "$(printf '%s' "$declining" | tr '\n' ',' | sed 's/,$//; s/,/, /g')"
				problems=$((problems + 1))
			fi
		fi

		# The evidence the paragraph claims has to exist, and to be in the delivered history.
		[ "$ancestry" = ran ] || continue
		while IFS= read -r hash; do
			[ -n "$hash" ] || continue
			deliveries=$((deliveries + 1))
			if ! git -C "$root" cat-file -e "${hash}^{commit}" 2>/dev/null; then
				printf 'PROBLEM: %s: names %s as delivery evidence, and it does not resolve as a commit\n' \
					"$rel" "'$hash'"
				problems=$((problems + 1))
			elif ! git -C "$root" merge-base --is-ancestor "$hash" "$main_sha" 2>/dev/null; then
				printf 'PROBLEM: %s: names %s as delivery evidence, and it is not an ancestor of origin/main\n' \
					"$rel" "'$hash'"
				problems=$((problems + 1))
			fi
		done < <(delivery_hashes "$para")
	done

	printf 'READ %d\n' "$records"
	if [ "$ancestry" = ran ]; then
		printf 'DELIVERY %d\n' "$deliveries"
		printf 'ancestry: ran (origin/main @ %s)\n' "$(printf '%s' "$main_sha" | cut -c1-7)"
	else
		printf 'ancestry: skipped (origin/main does not resolve in %s)\n' "$root"
	fi

	[ "$problems" -eq 0 ] && [ "$records" -gt 0 ]
}

# new_fixture <name> -> prints the root of an empty fixture tree, with a record directory
new_fixture() {
	local dir="$TMP/fixtures/$1"
	mkdir -p "$dir/odd/tasks"
	printf '%s\n' "$dir"
}

# new_git_fixture <name> -> prints the root of a fixture tree that is a real repository with two
# commits: `origin/main` points at the first, and the second lives on a `side` branch that was
# never merged, so its hash resolves as a commit and is provably not an ancestor of `origin/main`.
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

# expect_audit <label> <root> <pass|fail>
expect_audit() {
	local label="$1" root="$2" want="$3" out code
	if out="$(audit "$root")"; then
		code=0
	else
		code=$?
	fi
	if [ "$want" = pass ] && [ "$code" -eq 0 ]; then
		pass "$label"
	elif [ "$want" = fail ] && [ "$code" -ne 0 ]; then
		pass "$label"
	else
		fail "$label (expected $want, got exit $code)"
		printf '%s\n' "$out" | sed 's/^/    /'
	fi
}

# expect_audit_mentions <label> <root> <needle>
expect_audit_mentions() {
	local label="$1" root="$2" needle="$3" out
	out="$(audit "$root" 2>&1)" || true
	case "$out" in
	*"$needle"*) pass "$label (reports '$needle')" ;;
	*) fail "$label (expected '$needle' in: $(printf '%s' "$out" | tr '\n' ' '))" ;;
	esac
}

# ------------------------------------------------------------------------- the real tree
# First, and before any fixture: a false positive on `main` must not be reportable as a caught
# drift. This is the invariant `contributing-job-names.test.sh` states as Si.
echo "== the real tree is green before any fixture runs =="
if real_out="$(audit "$ROOT")"; then
	real_code=0
else
	real_code=$?
fi
REAL_RECORDS="$(printf '%s\n' "$real_out" | awk '/^READ / { print $2 }')"
REAL_RECORDS="${REAL_RECORDS:-0}"
REAL_ANCESTRY="$(printf '%s\n' "$real_out" | awk '/^ancestry: / { print $2 }')"
REAL_ANCESTRY="${REAL_ANCESTRY:-unknown}"
REAL_DELIVERY="$(printf '%s\n' "$real_out" | awk '/^DELIVERY / { print $2 }')"
REAL_DELIVERY="${REAL_DELIVERY:-0}"

if [ "$real_code" -eq 0 ]; then
	pass "no record on the tree declines a delivery that landed, and every claimed merge commit is in origin/main ($REAL_RECORDS records, ancestry: $REAL_ANCESTRY)"
else
	fail "a record on the tree declines a delivery that landed, or claims a merge commit that is not in origin/main"
fi
printf '%s\n' "$real_out" | grep '^PROBLEM' | sed 's/^/  /'

# A parser that read no record would agree with everything vacuously, so the count is asserted.
EXPECTED_RECORDS="$(find "$ROOT/odd/tasks" -maxdepth 1 -type f -name '*.md' 2>/dev/null | wc -l | tr -d ' ')"
if [ "$REAL_RECORDS" -eq "$EXPECTED_RECORDS" ] && [ "$REAL_RECORDS" -gt 0 ]; then
	pass "the audit read every record file ($EXPECTED_RECORDS)"
else
	fail "the audit read $REAL_RECORDS record(s), the tree has $EXPECTED_RECORDS"
fi

# A tree where the marker regex matched nothing would report a green ancestry half without having
# read a single delivery claim, so the count of evaluated hashes is asserted too -- but only when
# the half actually ran: a shallow checkout skips it by design.
if [ "$REAL_ANCESTRY" != ran ] || [ "$REAL_DELIVERY" -gt 0 ]; then
	pass "the ancestry half evaluated a nonempty set of claimed merge commits ($REAL_DELIVERY)"
else
	fail "the ancestry half ran but matched no delivery hash, so its verdict would be vacuous"
fi

# S6: the four records that legitimately quote the forbidden phrases keep them outside their
# first paragraph, which is what makes the paragraph scoping -- not an allowlist -- the mechanism.
QUOTATION_FILES="odd-record-convention odd-header-hygiene odd-status-reconciliation odd-merged-header-reconciliation"
QUOTATION_PROBLEMS=0
for name in $QUOTATION_FILES; do
	if [ ! -r "$ROOT/odd/tasks/$name.md" ]; then
		fail "the record that carries quotations, odd/tasks/$name.md, is missing"
		QUOTATION_PROBLEMS=$((QUOTATION_PROBLEMS + 1))
		continue
	fi
	if printf '%s\n' "$real_out" | grep -q "^PROBLEM: odd/tasks/$name\.md:"; then
		QUOTATION_PROBLEMS=$((QUOTATION_PROBLEMS + 1))
	fi
done
if [ "$QUOTATION_PROBLEMS" -eq 0 ]; then
	pass "the four records that quote the forbidden phrases stay green, without an allowlist"
else
	fail "$QUOTATION_PROBLEMS of the four quotation-carrying records were reported"
fi

# ------------------------------------------------------------------------- the rule's fixtures
echo "== the vocabulary rule's fixtures =="

# (a) A decline with no delivery evidence. The shape of the four headers #455 repaired.
FX="$(new_fixture declines-no-evidence)"
cat >"$FX/odd/tasks/declining.md" <<'MD'
# ODD feature: declining

**Status**: implemented — the guard and its fixtures are written. This header makes no claim about
push or PR state; see the evidence log.
**Created**: 2026-10-19
MD
expect_audit "a decline with no delivery evidence fails" "$FX" fail
expect_audit_mentions "the declining phrase is named" "$FX" "declines the delivery with no delivery evidence"

# (b) The `ci-speed.md` shape: declining vocabulary PLUS a PR number in the same paragraph. The
# hedge is legitimate because the paragraph states its delivery, so this must pass -- it is the
# fixture the issue names, and the reason the rule is a conjunction rather than a vocabulary scan.
FX="$(new_fixture ci-speed-shape)"
cat >"$FX/odd/tasks/hedged.md" <<'MD'
# ODD feature: hedged

**Status**: merged — U1 in pull request #435, 2026-10-19. This header makes no claim about live
push or PR state; see the evidence log.
**Created**: 2026-10-19
MD
expect_audit "declining vocabulary plus a delivery number passes" "$FX" pass

# (c) A decline split across a line wrap. This is the #436 blind spot: a line-oriented filter saw
# neither half, and the paragraph scope sees both. Without the whitespace collapse this passes,
# which is exactly the defect it reproduces.
FX="$(new_fixture decline-across-line-wrap)"
cat >"$FX/odd/tasks/wrapped.md" <<'MD'
# ODD feature: wrapped

**Status**: implemented — the change is written and nothing is deferred. This header makes no
claim about push or PR state.
**Created**: 2026-10-19
MD
expect_audit "a decline split across a line wrap fails" "$FX" fail

# (d) `implemented — ...` with no terminal evidence.
FX="$(new_fixture implemented-no-evidence)"
cat >"$FX/odd/tasks/implemented.md" <<'MD'
# ODD feature: implemented

**Status**: implemented — the guard is written and its fixtures pass.
**Created**: 2026-10-19
MD
expect_audit "implemented with no delivery evidence fails" "$FX" fail
expect_audit_mentions "the implemented state is named" "$FX" "phrases: implemented"

# The mirror of (d) in the direction that must pass: the same word with the delivery that closes
# it. Without this fixture the rule could be satisfied by rejecting the word outright.
FX="$(new_fixture implemented-with-evidence)"
cat >"$FX/odd/tasks/implemented-landed.md" <<'MD'
# ODD feature: implemented and landed

**Status**: implemented, and delivered as PR #443 (merge commit `4f3c2a1`), 2026-10-19.
**Created**: 2026-10-19
MD
expect_audit "implemented with a PR and a merge commit passes" "$FX" pass

# (e) A delivery that resolves but is not an ancestor fails: the header claims a merge that never
# reached `origin/main`. The fixture's `side` commit exists and is a real commit, so a rule that
# only checked existence would pass it.
FX="$(new_git_fixture merge-commit-not-an-ancestor)"
SIDE_SHA="$(git -C "$FX" rev-parse side)"
cat >"$FX/odd/tasks/not-ancestor.md" <<MD
# ODD feature: not an ancestor

**Status**: merged — PR #9001 (merge commit \`$SIDE_SHA\`), 2026-10-19. No work left.
**Created**: 2026-10-19
MD
expect_audit "a merge commit outside origin/main fails" "$FX" fail
expect_audit_mentions "the non-ancestor commit is named" "$FX" "is not an ancestor of origin/main"

# (f) A delivery identity that does not resolve as a commit fails too: a marker pointing at
# something that is not a commit is a false claim about the same sentence. Measured on the real
# tree, patch-ids and tree objects are backticked exactly like commits, which is why only the
# hash behind a delivery marker is ever read.
FX="$(new_git_fixture delivery-hash-unresolved)"
cat >"$FX/odd/tasks/unresolved.md" <<'MD'
# ODD feature: unresolved delivery

**Status**: merged — PR #9002 (merge commit `deadbeef1234`), 2026-10-19. No work left.
**Created**: 2026-10-19
MD
expect_audit "a delivery hash that is not a commit fails" "$FX" fail
expect_audit_mentions "the unresolvable hash is named" "$FX" "does not resolve as a commit"

# (g) `origin/main` does not resolve: the ancestry half says so out loud and does not fail, because
# a shallow CI checkout legitimately has no such ref. The assertion is on the REPORTED line, not
# just the exit code -- a silent skip would be indistinguishable from a pass.
FX="$(new_git_fixture ancestry-skipped)"
BASE_SHA="$(git -C "$FX" rev-parse refs/remotes/origin/main)"
git -C "$FX" update-ref -d refs/remotes/origin/main
cat >"$FX/odd/tasks/skipped.md" <<MD
# ODD feature: ancestry skipped

**Status**: merged — PR #9003 (merge commit \`$BASE_SHA\`), 2026-10-19. No work left.
**Created**: 2026-10-19
MD
expect_audit "a tree without origin/main does not fail on ancestry" "$FX" pass
expect_audit_mentions "the skip is reported, not silently green" "$FX" "ancestry: skipped"

# (h) A record with no readable `**Status**:` paragraph cannot state a durable state at all.
FX="$(new_fixture no-status-paragraph)"
cat >"$FX/odd/tasks/headerless.md" <<'MD'
# ODD feature: headerless

**Repository**: no status line at all.

## Specs
MD
expect_audit "a record with no Status paragraph fails" "$FX" fail
expect_audit_mentions "the missing paragraph is named" "$FX" "has no **Status**: paragraph"

# (i) A tree that reads zero records fails instead of agreeing vacuously.
FX="$TMP/fixtures/zero-records"
mkdir -p "$FX/odd/tasks"
expect_audit "a tree with no records fails rather than passing vacuously" "$FX" fail

# (j) The incident, and the reason a bare number stopped counting for one class of phrase: a header
# that declines its OWN delivery and cites a number that belongs to another unit. Offline nothing
# can decide who owns `#N` -- there is no `gh`, no token and no network -- so `#460` here says only
# that some pull request exists somewhere. This fixture passed before the split, which is the whole
# defect: it reported `PASS: no record on the tree declines a delivery that landed`.
FX="$(new_fixture decline-with-foreign-number)"
cat >"$FX/odd/tasks/foreign-number.md" <<'MD'
# ODD feature: foreign number

**Status**: the guard and its fixtures are written, and what remains is the delivery. The vocabulary
this header borrows is the one pull request #460 settled.
**Created**: 2026-10-19
MD
expect_audit "a declined delivery backed only by another unit's number fails" "$FX" fail
expect_audit_mentions "the missing merge commit is named" "$FX" "declines its own delivery with no merge commit"

# (k) `not merged yet` was in the vocabulary and a bare `not merged` matched nothing, so the second
# hole was invisible rather than wrongly satisfied: this header opened with a pending delivery and a
# pull request number and the guard never looked at it. It also passed before the split.
FX="$(new_fixture decline-bare-not-merged)"
cat >"$FX/odd/tasks/bare-not-merged.md" <<'MD'
# ODD feature: not merged

**Status**: delivered on the branch, not merged, and opened as pull request #466 on 2026-10-19.
**Created**: 2026-10-19
MD
expect_audit "a bare not merged with only a pull request number fails" "$FX" fail

# (l) The mirror of (j) in the direction the split must not break: `implemented` makes no claim about
# this unit's delivery, so a pull request number is still enough for it. The tree carries four
# records of exactly this shape, and a split that demanded a hash here would fail them all.
FX="$(new_fixture soft-phrase-with-number)"
cat >"$FX/odd/tasks/soft.md" <<'MD'
# ODD feature: soft claim

**Status**: implemented -- pull request #467 carries the guard and its fixtures, 2026-10-19.
**Created**: 2026-10-19
MD
expect_audit "implemented with only a pull request number passes" "$FX" pass

# (m) And the stricter class has to be satisfiable, or the rule would just be "never say awaiting
# review": the same delivery-state phrase with the merge commit the ancestry half can check against
# `origin/main` passes. The fixture is a real repository so the hash resolves AND is an ancestor.
FX="$(new_git_fixture delivery-state-with-merge-commit)"
BASE_SHA="$(git -C "$FX" rev-parse refs/remotes/origin/main)"
cat >"$FX/odd/tasks/merge-commit.md" <<MD
# ODD feature: delivery state with the merge commit

**Status**: merged as pull request #9004 (merge commit \`$BASE_SHA\`), 2026-10-19; the follow-up guard is
awaiting review.
**Created**: 2026-10-19
MD
expect_audit "a delivery-state phrase with a merge commit in origin/main passes" "$FX" pass

printf '\nchecked %s record(s); %s claimed merge commit(s); ancestry: %s\n' "$REAL_RECORDS" "$REAL_DELIVERY" "$REAL_ANCESTRY"
printf '%d fixture(s) asserted\n' "$(find "$TMP/fixtures" -mindepth 1 -maxdepth 1 -type d | wc -l | tr -d ' ')"
printf '%d passed, %d failed\n' "$PASS_COUNT" "$FAIL_COUNT"
[ "$FAIL_COUNT" -eq 0 ]
