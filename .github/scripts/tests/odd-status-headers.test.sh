#!/bin/bash
# ============================================
# LifeControl - ODD record headers never assert the transport
# ============================================
# Answers one question about `odd/tasks/*.md`: does any record's `**Status**:` paragraph assert a
# delivery state -- pushed, opened, reviewed, merged -- that a later merge can falsify?
#
# That line is the only artifact every clone, every future session and every reviewer read, and
# nothing read it back — no workflow filtered `odd/tasks/**` or any markdown — so a header that
# asserted a *live* delivery state was falsified by the next merge, silently, with no commit touching
# the sentence that lied. Measured six times, each repaired by hand: pull requests #162 (14 headers),
# #170, #364 (8), #436 (8), #455 (4, issue #454) and #471 (2). This suite is the reader that was
# missing, and `PR Label Guard` is what runs it: that workflow deliberately keeps no `paths:` filter,
# so a records-only pull request is audited like any other.
#
# The rule is FALSIFIABILITY, not topic. Two header shapes stay legal because no future merge can
# falsify either:
#
#   - `implemented — <what remains>`. The work exists. No pull-request number and no merge commit is
#     required, because neither exists when the record is written -- the record ships INSIDE the pull
#     request that carries the work, which is why the header has to be final there. Demanding a merge
#     commit is what forced a second `docs(odd)` pull request per unit: 51 of the last 150 merged pull
#     requests were that follow-up, 40 of them rewriting one record.
#   - `merged — PR #<n> (<branch> @ <merge-commit>), <date>`. The delivery happened, and cannot
#     unhappen. This stays legal, so the 75 landed headers on the tree are never migrated; the hash it
#     names is checked against `origin/main` below.
#
# What is illegal is the class of sentence that can still change: `awaiting review` is false fifteen
# minutes later. Before this rule the guard ALLOWED that class whenever the same paragraph named a
# merge commit, so `not merged` beside the hash of the merge that landed it passed. Nothing rescues it
# now: the escape hatch and the evidence requirement left together, on purpose, which is why losing one
# did not weaken the other.
#
# Two smaller rules carry the same failure mode:
#
#   - A DISCLAIMER beside delivery evidence is a contradiction. `This header makes no claim about push
#     or PR state` next to a named merge commit is the sentence #426 wrote and #436 had to repair one
#     day later. The disclaimer alone is harmless and stays legal -- 22 records on the tree carry it --
#     so the rule rejects the combination, not the phrase.
#   - The vocabulary is BILINGUAL. The two most recent records were written in Spanish, and #492
#     replaced a header reading `implementado y entregado para revisión ... el merge es una decisión del
#     mantenedor`, a live claim an English-only denylist cannot see. The suite also asserts that every
#     phrase `AGENTS.md`'s forbidden row names is actually caught, so documentation and code cannot
#     drift into a promise nothing keeps.
#
# The parse is scoped to the FIRST `**Status**:` paragraph, ending at a blank line, a Markdown heading
# or the next `**Field**:` line, with its whitespace collapsed. The collapse is what makes a claim
# split across a line wrap decidable: the sweep that fed #436 was line-oriented, so
# `odd/tasks/mobile-header-repair.md`'s claim was split by a wrap, the filter saw neither half, and it
# hid a stale record for a day. The scoping is what removes the allowlist
# `references/feature-records.md` used to say this guard would need: the records that legitimately
# quote the forbidden phrases (the template tables in `odd-record-convention.md`, the BEFORE blocks of
# `odd-header-hygiene.md`, `odd-status-reconciliation.md` and `odd-merged-header-reconciliation.md`,
# and the appended repair blocks of every repaired record) keep those quotations OUTSIDE the first
# paragraph.
#
# The second half verifies the evidence a header CHOOSES to name. A merge commit it introduces behind a
# delivery marker must resolve and be an ancestor of `origin/main`; when `origin/main` does not resolve
# -- a shallow CI checkout has no such ref -- the half says `ancestry: skipped` out loud, because a
# silent skip is indistinguishable from a pass.
#
# What the ancestry half deliberately does NOT read: every backticked hex token. Measured on `main` @
# `ab649a5`, the directory backticks patch-ids (`5ef49972f608...`) and tree objects (`54c5a75fc41...`,
# `8896dcb02c9...`, `ca4ceb38`) that no repository can resolve as commits, and it documents one commit
# that legitimately is NOT an ancestor -- `project-conventions-skill.md` names `cec4578` as "PR #129 was
# closed without merging and its commit `cec4578` never reached `main`". Failing on those would fire on
# an honest sentence and on four identities that are not commits at all. So only hashes a DELIVERY
# MARKER introduces are read: `merge commit`, `merged as`, `merged by`, `@` and `->`.
#
# No network, no `gh`, no token, no YAML library: it reads `odd/tasks/*.md`, `AGENTS.md` and local
# `git` only, and writes nothing inside the repository. Point it at another checkout or a scratch copy
# by passing a root directory as the first argument (or with REPO_ROOT); that is how the fixtures run
# it without touching this tree.
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

# live_claim_phrases <paragraph>
# Prints, one per line and deduplicated, every phrase that asserts this unit's own DELIVERY state --
# pushed, opened, reviewed, merged. This is the whole vocabulary the rule rejects, and it is rejected
# unconditionally: no merge commit, no pull-request number and no terminal word rescues it, because
# the sentence is false as soon as the state changes and the file is never touched again.
#
# `implemented` is deliberately NOT here. It claims the work exists, which no merge can falsify, and
# requiring evidence for it is what forced a follow-up `docs(odd)` pull request per unit.
#
# The Spanish half is not decoration: the two most recent records were written in Spanish, and the
# header #492 replaced (`implementado y entregado para revisión ... el merge es una decisión del
# mantenedor`) is a live claim in the same class that an English-only denylist cannot see. Both
# halves have to stay in step with `AGENTS.md`'s forbidden row, which the parity case below asserts.
live_claim_phrases() {
	printf '%s' "$1" | grep -oiE \
		'not pushed|no PR is open|not merged|yet to land|awaiting review|pending review|in progress|unstarted|what remains is the delivery|sin pushear|no hay PR abierto|en progreso|esperando review|sin mergear' \
		2>/dev/null | sort -u || true
}

# disclaims_transport <paragraph>
# Prints the phrase with which a header disclaims a claim it does not need to disclaim. Alone it is
# harmless and stays legal; beside delivery evidence it is a contradiction, because the same
# paragraph then names the merge the disclaimer says nothing is being claimed about. That exact
# sentence is #436's incident: #426 wrote it, and it was false one day later.
disclaims_transport() {
	printf '%s' "$1" | grep -oiE 'makes no claim about [^.]{0,60}push' 2>/dev/null || true
}

# names_delivery_evidence <paragraph>
# Answers whether the paragraph names any delivery identity at all: a `#<number>`, or a commit hash
# behind a delivery marker. Only `disclaims_transport` reads it -- nothing REQUIRES evidence any more.
names_delivery_evidence() {
	local p="$1"
	printf '%s' "$p" | grep -qE '#[0-9]+' && return 0
	[ -n "$(delivery_hashes "$p")" ] && return 0
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
	local main_sha file rel para claims disclaimers hash problems=0 records=0 ancestry=skipped deliveries=0

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

		# The rule. A LIVE CLAIM fails unconditionally: the paragraph asserts a delivery state that a
		# later merge falsifies, and no evidence rescues it -- citing the very merge that falsified it is
		# the worse shape, not the better one. A DISCLAIMER fails only beside delivery evidence, because
		# on its own it states nothing and 22 records on the tree carry it. The emptiness tests are
		# load-bearing: `grep` in a pipeline reports the last command's status, so testing an
		# assignment's status would enter the branch on every record.
		claims="$(live_claim_phrases "$para")"
		if [ -n "$claims" ]; then
			printf 'PROBLEM: %s: asserts a live delivery state in its Status paragraph (phrases: %s); a later merge falsifies it, so state the work and what remains instead\n' \
				"$rel" "$(printf '%s' "$claims" | tr '\n' ',' | sed 's/,$//; s/,/, /g')"
			problems=$((problems + 1))
		fi

		disclaimers="$(disclaims_transport "$para")"
		if [ -n "$disclaimers" ] && names_delivery_evidence "$para"; then
			printf 'PROBLEM: %s: disclaims the transport state while naming delivery evidence in the same Status paragraph (phrases: %s)\n' \
				"$rel" "$(printf '%s' "$disclaimers" | tr '\n' ',' | sed 's/,$//; s/,/, /g')"
			problems=$((problems + 1))
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
	pass "no record on the tree asserts a live delivery state, names a disclaimer beside its own delivery evidence, or claims a merge commit that is not in origin/main ($REAL_RECORDS records, ancestry: $REAL_ANCESTRY)"
else
	fail "a record on the tree asserts a live delivery state, contradicts itself with a transport disclaimer, or claims a merge commit that is not in origin/main"
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
echo "== the falsifiability rule's fixtures =="

# (a) The canonical landed shape, and the reason this change exists: the record ships in the pull
# request that carries the work, so its header can name neither a merge commit nor a pull-request
# number that does not exist yet. It passed before only when a `#N` was bolted on; the requirement
# is what forced a second `docs(odd)` pull request per unit, 51 of the last 150 merges.
FX="$(new_fixture implemented-no-evidence)"
cat >"$FX/odd/tasks/implemented.md" <<'MD'
# ODD feature: implemented

**Status**: implemented — the guard is written and its fixtures pass; nothing is deferred.
**Created**: 2026-10-19
MD
expect_audit "implemented with no delivery evidence passes: the header ships final" "$FX" pass

# The mirror, and the shape four records on the tree carry: a soft claim with a pull request named.
FX="$(new_fixture soft-phrase-with-number)"
cat >"$FX/odd/tasks/soft.md" <<'MD'
# ODD feature: soft claim

**Status**: implemented -- pull request #467 carries the guard and its fixtures, 2026-10-19.
**Created**: 2026-10-19
MD
expect_audit "implemented with only a pull request number passes" "$FX" pass

# (b) A live claim with no evidence behind it. The shape of the four headers #455 repaired; it fails
# before and after this change, and the message no longer offers a merge commit as a way out.
FX="$(new_fixture live-claim-no-evidence)"
cat >"$FX/odd/tasks/declining.md" <<'MD'
# ODD feature: declining

**Status**: implemented — the guard and its fixtures are written; awaiting review.
**Created**: 2026-10-19
MD
expect_audit "a live delivery claim fails" "$FX" fail
expect_audit_mentions "the live-state class is named" "$FX" "asserts a live delivery state"
expect_audit_mentions "the offending phrase is quoted" "$FX" "awaiting review"

# (c) The escape hatch is gone: a live claim with a merge commit in the SAME paragraph used to pass,
# because the delivery-state class only asked for a hash. `not merged` beside the hash of the merge
# that landed it is the exact sentence this closes.
FX="$(new_git_fixture live-claim-with-merge-commit)"
BASE_SHA="$(git -C "$FX" rev-parse refs/remotes/origin/main)"
cat >"$FX/odd/tasks/merge-commit.md" <<MD
# ODD feature: live claim with the merge commit

**Status**: merged as pull request #9004 is not merged for U2; merge commit \`$BASE_SHA\`, 2026-10-19.
**Created**: 2026-10-19
MD
expect_audit "a live claim with a merge commit in origin/main still fails" "$FX" fail
expect_audit_mentions "the claim is named, not the ancestry" "$FX" "asserts a live delivery state"

# (d) A live claim split across a line wrap. The #436 blind spot: a line-oriented filter saw neither
# half, and the paragraph scope sees both. Without the whitespace collapse this passes, which is
# exactly the defect it reproduces.
FX="$(new_fixture claim-across-line-wrap)"
cat >"$FX/odd/tasks/wrapped.md" <<'MD'
# ODD feature: wrapped

**Status**: implemented — the change is written and the delivery is not
merged.
**Created**: 2026-10-19
MD
expect_audit "a live claim split across a line wrap fails" "$FX" fail

# (e) The disclaimer beside delivery evidence. `#426` wrote it, `#436` repaired it one day later: the
# sentence says the header makes no claim about the transport while the same paragraph names the merge
# that closed it. Held by two records on the tree, which this change repairs.
FX="$(new_fixture disclaimer-with-evidence)"
cat >"$FX/odd/tasks/hedged.md" <<'MD'
# ODD feature: hedged

**Status**: merged — U1 in pull request #435, 2026-10-19. This header makes no claim about live
push or PR state; see the evidence log.
**Created**: 2026-10-19
MD
expect_audit "a transport disclaimer beside delivery evidence fails" "$FX" fail
expect_audit_mentions "the contradiction is named" "$FX" "disclaims the transport state"

# The mirror: the disclaimer on its own is harmless, and 22 records on the tree carry it. A rule that
# rejected the phrase outright would fail all of them.
FX="$(new_fixture disclaimer-alone)"
cat >"$FX/odd/tasks/disclaimer.md" <<'MD'
# ODD feature: disclaimer alone

**Status**: implemented — the guard is written. This header makes no claim about push or PR state;
see the evidence log.
**Created**: 2026-10-19
MD
expect_audit "a transport disclaimer with no delivery evidence passes" "$FX" pass

# (f) The incident that stopped a bare number counting for one class of phrase, and that this change
# makes unconditional: a header that claims its own delivery is pending and cites a number belonging
# to another unit. Offline nothing decides who owns `#N` -- no `gh`, no token, no network.
FX="$(new_fixture claim-with-foreign-number)"
cat >"$FX/odd/tasks/foreign-number.md" <<'MD'
# ODD feature: foreign number

**Status**: the guard and its fixtures are written, and what remains is the delivery. The vocabulary
this header borrows is the one pull request #460 settled.
**Created**: 2026-10-19
MD
expect_audit "a live claim backed only by another unit's number fails" "$FX" fail
expect_audit_mentions "the claim is named" "$FX" "asserts a live delivery state"

# (g) A bare `not merged` opened with a pending delivery and a pull-request number, and the guard
# never looked at it because the vocabulary listed `not merged yet`.
FX="$(new_fixture claim-bare-not-merged)"
cat >"$FX/odd/tasks/bare-not-merged.md" <<'MD'
# ODD feature: not merged

**Status**: delivered on the branch, not merged, and opened as pull request #466 on 2026-10-19.
**Created**: 2026-10-19
MD
expect_audit "a bare not merged with only a pull request number fails" "$FX" fail

# (h) A delivery that resolves but is not an ancestor fails: the header claims a merge that never
# reached `origin/main`. The fixture's `side` commit exists and is a real commit, so a rule that only
# checked existence would pass it.
FX="$(new_git_fixture merge-commit-not-an-ancestor)"
SIDE_SHA="$(git -C "$FX" rev-parse side)"
cat >"$FX/odd/tasks/not-ancestor.md" <<MD
# ODD feature: not an ancestor

**Status**: merged — PR #9001 (merge commit \`$SIDE_SHA\`), 2026-10-19. No work left.
**Created**: 2026-10-19
MD
expect_audit "a merge commit outside origin/main fails" "$FX" fail
expect_audit_mentions "the non-ancestor commit is named" "$FX" "is not an ancestor of origin/main"

# (i) A delivery identity that does not resolve as a commit fails too: a marker pointing at something
# that is not a commit is a false claim about the same sentence. Measured on the real tree, patch-ids
# and tree objects are backticked exactly like commits, which is why only the hash behind a delivery
# marker is ever read.
FX="$(new_git_fixture delivery-hash-unresolved)"
cat >"$FX/odd/tasks/unresolved.md" <<'MD'
# ODD feature: unresolved delivery

**Status**: merged — PR #9002 (merge commit `deadbeef1234`), 2026-10-19. No work left.
**Created**: 2026-10-19
MD
expect_audit "a delivery hash that is not a commit fails" "$FX" fail
expect_audit_mentions "the unresolvable hash is named" "$FX" "does not resolve as a commit"

# (j) `origin/main` does not resolve: the ancestry half says so out loud and does not fail, because a
# shallow CI checkout legitimately has no such ref. The assertion is on the REPORTED line, not just the
# exit code -- a silent skip would be indistinguishable from a pass.
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

# (k) A record with no readable `**Status**:` paragraph cannot state a durable state at all.
FX="$(new_fixture no-status-paragraph)"
cat >"$FX/odd/tasks/headerless.md" <<'MD'
# ODD feature: headerless

**Repository**: no status line at all.

## Specs
MD
expect_audit "a record with no Status paragraph fails" "$FX" fail
expect_audit_mentions "the missing paragraph is named" "$FX" "has no **Status**: paragraph"

# (l) A tree that reads zero records fails instead of agreeing vacuously.
FX="$TMP/fixtures/zero-records"
mkdir -p "$FX/odd/tasks"
expect_audit "a tree with no records fails rather than passing vacuously" "$FX" fail

# ------------------------------------------------------------------------- AGENTS.md parity
# Every phrase the documentation's forbidden row names has to be caught by the code, or the two
# sources drift and the row becomes a promise nothing keeps. The two most recent records were written
# in Spanish, and `#492` replaced a header reading "implementado y entregado para revisión ... el merge
# es una decisión del mantenedor" -- a live claim an English-only denylist cannot see.
echo "== every phrase AGENTS.md forbids is caught =="
FORBIDDEN_ROW="$(grep -E '^\| *Qué no lleva nunca *\|' "$ROOT/AGENTS.md" 2>/dev/null || true)"
FORBIDDEN_PHRASES="$(printf '%s' "$FORBIDDEN_ROW" | grep -oE '"[^"]+"' | tr -d '"' | sort -u)"
FORBIDDEN_COUNT="$(printf '%s' "$FORBIDDEN_PHRASES" | grep -c . || true)"
if [ -z "$FORBIDDEN_ROW" ] || [ "$FORBIDDEN_COUNT" -lt 5 ]; then
	fail "the AGENTS.md forbidden row is unreadable or names fewer than five phrases ($FORBIDDEN_COUNT), so this parity check would pass vacuously"
else
	pass "the forbidden row names $FORBIDDEN_COUNT phrase(s) to check"
	PARITY_INDEX=0
	while IFS= read -r phrase; do
		[ -n "$phrase" ] || continue
		PARITY_INDEX=$((PARITY_INDEX + 1))
		FX="$(new_fixture "forbidden-phrase-$PARITY_INDEX")"
		printf '# ODD feature: forbidden phrase\n\n**Status**: implemented — %s.\n**Created**: 2026-10-19\n' "$phrase" \
			>"$FX/odd/tasks/phrase.md"
		expect_audit "the phrase '$phrase' is caught" "$FX" fail
		expect_audit_mentions "the phrase '$phrase' is named in the finding" "$FX" "asserts a live delivery state"
	done <<<"$FORBIDDEN_PHRASES"
fi

printf '\nchecked %s record(s); %s claimed merge commit(s); ancestry: %s\n' "$REAL_RECORDS" "$REAL_DELIVERY" "$REAL_ANCESTRY"
printf '%d fixture(s) asserted\n' "$(find "$TMP/fixtures" -mindepth 1 -maxdepth 1 -type d | wc -l | tr -d ' ')"
printf '%d passed, %d failed\n' "$PASS_COUNT" "$FAIL_COUNT"
[ "$FAIL_COUNT" -eq 0 ]
