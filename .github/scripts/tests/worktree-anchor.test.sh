#!/bin/bash
# ============================================
# LifeControl - the anchor is never an edit target
# ============================================
# Answers one question about this repository's worktree convention: does anything hold it?
#
# The rule is stated four times -- `AGENTS.md`, `CONTRIBUTING.md`,
# `.agents/skills/project-conventions/SKILL.md` and `references/worktrees.md` -- and until this suite it
# was stated as a benefit of PARALLELISM in two of them («Parallel work needs isolation», «When work is
# parallelized»), so a single sequential writer read each of them as not applying to it. Nothing verified
# the rule either way: no suite mentioned the hook, and `core.hooksPath` was unset in the clone where the
# question came up. The failure mode is not hypothetical -- the anchor of this repository was found
# carrying five uncommitted Angular files that belonged to no pull request.
#
# The server half is already closed and this suite does not touch it: `main` requires a pull request with
# `enforce_admins: true`, so a unit done in the anchor can never be published from there. What is not
# closed is the LOCAL half -- work left uncommitted on top of the reference, which then either cannot be
# committed or is committed on the default branch and can never be pushed. The server cannot see a dirty
# checkout, so the reader for that half is a hook, and this suite drives the hook as a program.
#
# Two halves, one file:
#
#   1. Behaviour. `.githooks/pre-commit` is copied into scratch repositories and RUN, never merely
#      asserted to exist: it must refuse a commit on the default branch, allow one on any other branch,
#      allow one on a detached HEAD, allow one when `ALLOW_MAIN_COMMIT=1` says the commit is deliberate,
#      and resolve the default branch from `refs/remotes/origin/HEAD` -- a fixture whose default branch
#      is `trunk` proves the last one, which a hardcoded `main` would silently fail.
#   2. Parity. The four artifacts must still carry the rule and the one command that makes it easy to
#      obey, and the two conditional phrasings must not come back. A rule nobody reads drifts; a rule
#      four documents state and no check reads drifts silently.
#
# `.github/scripts/new-unit.sh` is driven the same way: it creates a real worktree in a scratch clone,
# derives the directory from the branch, and refuses a second creation on the same branch.
#
# No network, no `gh`, no token: local `git` and `mktemp` only, and nothing is written inside the
# repository. Point it at another checkout by passing a root directory (or REPO_ROOT).
#
# Run: ./.github/scripts/tests/worktree-anchor.test.sh
# Exits 0 only when every case passes.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="${1:-${REPO_ROOT:-$SCRIPT_DIR/../../..}}"
TMP="$(mktemp -d)" || {
	printf 'FAIL: cannot create a scratch directory (TMPDIR=%s)\n' "${TMPDIR:-unset}" >&2
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

HOOK="$ROOT/.githooks/pre-commit"
NEW_UNIT="$ROOT/.github/scripts/new-unit.sh"

# new_repo <name> [default branch] -> prints the root of a scratch repository whose default branch is
# `main` unless another name is given, with `origin/HEAD` pointing at it and the hook installed through
# `core.hooksPath`, which is the one command the documentation asks of a clone.
new_repo() {
	local name="$1" branch="${2:-main}" dir
	dir="$TMP/$name"
	mkdir -p "$dir/.githooks"
	git -C "$dir" init -q -b "$branch"
	git -C "$dir" -c user.email=fixture@example.invalid -c user.name=fixture \
		commit -q --allow-empty -m 'the base commit'
	git -C "$dir" update-ref "refs/remotes/origin/$branch" "$(git -C "$dir" rev-parse HEAD)"
	git -C "$dir" symbolic-ref refs/remotes/origin/HEAD "refs/remotes/origin/$branch"
	if [ -r "$HOOK" ]; then
		cp "$HOOK" "$dir/.githooks/pre-commit"
		chmod +x "$dir/.githooks/pre-commit"
	fi
	git -C "$dir" config core.hooksPath .githooks
	printf '%s\n' "$dir"
}

# attempt_commit <repo> [env assignments...] -> prints the commit's stderr, returns the commit's status.
# The hook is executed by git itself, so this is the only honest way to answer whether it bites.
attempt_commit() {
	local dir="$1"
	shift
	env "$@" git -C "$dir" -c user.email=fixture@example.invalid -c user.name=fixture \
		commit --allow-empty -m 'a unit of work' 2>&1
}

echo "== the hook is a program this suite can run =="
if [ -x "$HOOK" ]; then
	pass "the pre-commit hook exists and is executable (.githooks/pre-commit)"
else
	fail "the pre-commit hook is missing or not executable at .githooks/pre-commit"
fi

echo "== the hook refuses the default branch and only the default branch =="
REPO="$(new_repo refuses-main)"
if out="$(attempt_commit "$REPO")"; then
	fail "a commit on the default branch is refused (it was accepted)"
else
	pass "a commit on the default branch is refused"
	case "$out" in
	*new-unit.sh*)
		pass "the refusal names the command that creates the worktree" ;;
	*)
		fail "the refusal does not name .github/scripts/new-unit.sh (got: $(printf '%s' "$out" | tr '\n' ' '))" ;;
	esac
	case "$out" in
	*ALLOW_MAIN_COMMIT*)
		pass "the refusal names the escape for a deliberate commit" ;;
	*)
		fail "the refusal does not name ALLOW_MAIN_COMMIT" ;;
	esac
fi

REPO="$(new_repo allows-feature-branch)"
git -C "$REPO" checkout -q -b feat/some-unit
if attempt_commit "$REPO" >/dev/null; then
	pass "a commit on a feature branch is accepted"
else
	fail "a commit on a feature branch is refused, so the hook breaks ordinary work"
fi

REPO="$(new_repo allows-deliberate-commit)"
if attempt_commit "$REPO" ALLOW_MAIN_COMMIT=1 >/dev/null; then
	pass "ALLOW_MAIN_COMMIT=1 accepts a deliberate commit on the default branch"
else
	fail "ALLOW_MAIN_COMMIT=1 does not accept a deliberate commit, so the escape does not exist"
fi

REPO="$(new_repo allows-detached-head)"
git -C "$REPO" checkout -q --detach
if attempt_commit "$REPO" >/dev/null; then
	pass "a commit on a detached HEAD is accepted"
else
	fail "a commit on a detached HEAD is refused, which is not the situation the rule is about"
fi

# The default branch is resolved, never assumed. A fixture whose default branch is `trunk` is refused on
# `trunk`; a hook that matched the literal string `main` would accept that commit and pass every case
# above, which is why this case exists.
REPO="$(new_repo resolves-a-non-main-default trunk)"
if attempt_commit "$REPO" >/dev/null; then
	fail "a commit on a non-main default branch (trunk) is refused (it was accepted: the hook hardcodes main)"
else
	pass "the default branch is resolved from origin/HEAD, not hardcoded (trunk refused)"
fi
git -C "$REPO" checkout -q -b feat/other
if attempt_commit "$REPO" >/dev/null; then
	pass "a feature branch in that same repository is still accepted"
else
	fail "a feature branch in a non-main-default repository is refused"
fi

echo "== new-unit.sh creates the worktree the convention names =="
if [ -x "$NEW_UNIT" ]; then
	pass "new-unit.sh exists and is executable"
else
	fail "new-unit.sh is missing or not executable at .github/scripts/new-unit.sh"
fi
REPO="$(new_repo creates-worktree)"
CREATED=0
if out="$("$NEW_UNIT" --repo "$REPO" feat/a-portable-unit 2>&1)"; then
	CREATED=1
	pass "new-unit.sh exits 0 for a fresh branch"
else
	fail "new-unit.sh failed for a fresh branch (got: $(printf '%s' "$out" | tr '\n' ' '))"
fi
EXPECTED_PATH="$(cd "$REPO" && cd .. && pwd)/$(basename "$REPO")-worktrees/feat-a-portable-unit"
if [ -d "$EXPECTED_PATH" ]; then
	pass "the worktree lands in the sibling directory the convention names ($(basename "$EXPECTED_PATH"))"
else
	fail "no worktree at the conventional path $EXPECTED_PATH"
fi
if [ "$(git -C "$EXPECTED_PATH" rev-parse --abbrev-ref HEAD 2>/dev/null)" = "feat/a-portable-unit" ]; then
	pass "the worktree carries the branch it was created for"
else
	fail "the worktree does not carry feat/a-portable-unit"
fi
# Only meaningful when the first creation worked: a missing script exits non-zero for every input, and a
# refusal asserted against that would be a pass nobody earned.
if [ "$CREATED" -eq 1 ]; then
	if "$NEW_UNIT" --repo "$REPO" feat/a-portable-unit >/dev/null 2>&1; then
		fail "a second creation on the same branch is refused (it succeeded)"
	else
		pass "a second creation on the same branch is refused"
	fi
fi

echo "== the four artifacts still state the rule, and the conditional stays gone =="
for artifact in "AGENTS.md" "CONTRIBUTING.md" \
	".agents/skills/project-conventions/SKILL.md" \
	".agents/skills/project-conventions/references/worktrees.md"; do
	if grep -q 'new-unit\.sh' "$ROOT/$artifact" 2>/dev/null; then
		pass "$artifact names the command that makes the rule easy to obey"
	else
		fail "$artifact no longer names .github/scripts/new-unit.sh, so the rule has no recipe"
	fi
done

for phrase in "Parallel work needs isolation" "When work is parallelized"; do
	if grep -rqF "$phrase" "$ROOT/AGENTS.md" "$ROOT/CONTRIBUTING.md" \
		"$ROOT/.agents/skills/project-conventions" 2>/dev/null; then
		fail "the conditional phrasing '$phrase' is back: it is what made a sequential writer read the rule as inapplicable"
	else
		pass "the conditional phrasing '$phrase' is gone"
	fi
done

# The rule itself, in the one artifact that owns the invariants, in the words the suite can check
# without pinning prose nobody rereads.
if grep -qF "The anchor is never an edit target" \
	"$ROOT/.agents/skills/project-conventions/references/worktrees.md" 2>/dev/null; then
	pass "worktrees.md states the invariant in the words this suite checks"
else
	fail "worktrees.md no longer states 'The anchor is never an edit target'"
fi

# The install is one command, and every clone that skips it is unguarded; the doc that a contributor
# reads first has to carry it.
if grep -qF "core.hooksPath .githooks" "$ROOT/CONTRIBUTING.md" 2>/dev/null; then
	pass "CONTRIBUTING.md tells a clone how to install the hook"
else
	fail "CONTRIBUTING.md does not carry the hook-install command"
fi

# A hook is shell that runs on every commit, and it carries no `.sh` extension because git requires the
# bare name -- which is exactly how it would escape a `-name '*.sh'` lint and stay unlinted forever.
HOOKED='find .githooks'
if grep -qF "$HOOKED" "$ROOT/.github/workflows/pr-label-guard.yml" 2>/dev/null; then
	pass "the guard workflow's ShellCheck step still covers .githooks"
else
	fail "the ShellCheck step no longer reaches .githooks, so the hook is never linted"
fi

echo "== the session-start procedure and the operations the anchor may run =="

# Git has no `pre-reset` hook -- only commit, checkout, merge and push -- so the rule about what the anchor
# may run is prose, not a program. That is exactly why it needs a reader: a rule stated in four artifacts
# and verified by none is the state this whole unit exists to leave behind.
WORKTREES_MD="$ROOT/.agents/skills/project-conventions/references/worktrees.md"
SESSION_SECTION="$(awk '/^## Starting a session/{inside=1; next} /^## /{inside=0} inside' "$WORKTREES_MD" 2>/dev/null)"
if [ -n "$SESSION_SECTION" ]; then
	pass "worktrees.md still has a 'Starting a session' section"
else
	fail "worktrees.md has no 'Starting a session' section, so the session-start procedure has no owner"
fi
for step in "git fetch origin" "git status --short" "git log --oneline origin/main"; do
	case "$SESSION_SECTION" in
	*"$step"*)
		pass "the start-of-session procedure names '$step'" ;;
	*)
		fail "the start-of-session procedure no longer names '$step'" ;;
	esac
done

# The forbidden list, checked inside its own section: a word that stops being named there is a word that
# goes back to being allowed by silence.
ANCHOR_SECTION="$(awk '/^## What the anchor may run/{inside=1; next} /^## /{inside=0} inside' "$WORKTREES_MD" 2>/dev/null)"
if [ -n "$ANCHOR_SECTION" ]; then
	pass "worktrees.md still has a 'What the anchor may run' section"
else
	fail "worktrees.md has no 'What the anchor may run' section"
fi
# Scoped to the TABLE, not the section: the section's prose names `git reset` while telling the incident
# that motivated the rule, and a whole-section grep was measured to keep passing after the table row that
# forbids it was deleted -- a pass nobody earned. Only the list is the rule.
ANCHOR_TABLE="$(printf '%s\n' "$ANCHOR_SECTION" | grep '^| ')"
ANCHOR_ROWS="$(printf '%s\n' "$ANCHOR_TABLE" | grep -c '^|' || true)"
if [ "${ANCHOR_ROWS:-0}" -ge 5 ]; then
	pass "the anchor's allowed/forbidden table still has $ANCHOR_ROWS rows"
else
	fail "the anchor's allowed/forbidden table has only ${ANCHOR_ROWS:-0} rows, so part of the rule is gone"
fi
for operation in "git reset" "git stash" "git clean" "git checkout -b" "git rebase" "git commit"; do
	case "$ANCHOR_TABLE" in
	*"$operation"*)
		pass "the anchor's forbidden list still names '$operation'" ;;
	*)
		fail "the anchor's forbidden list no longer names '$operation', so it is forbidden by silence" ;;
	esac
done

# And the one command that decides whether the anchor is safe to touch at all is restated where each
# audience reads: the agent rules, the skill and the contributor guide.
for artifact in "AGENTS.md" "CONTRIBUTING.md" ".agents/skills/project-conventions/SKILL.md"; do
	if grep -qF 'git status --short' "$ROOT/$artifact" 2>/dev/null; then
		pass "$artifact restates the read that decides whether the anchor is safe to touch"
	else
		fail "$artifact no longer tells a session to read the anchor's working-tree state first"
	fi
done

printf '\n%d passed, %d failed\n' "$PASS_COUNT" "$FAIL_COUNT"
[ "$FAIL_COUNT" -eq 0 ]
