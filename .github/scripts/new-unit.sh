#!/bin/bash
# ============================================
# LifeControl - create the worktree a unit of work starts in
# ============================================
# The convention names the directory, the branch and the base; this script does the three of them so that
# obeying the rule costs one command. The rule is not about parallelism: EVERY unit starts in its own
# worktree, and the anchor checkout is never an edit target.
#
# It exists because the documented command used to be a `herdr worktree create` invocation, so a
# contributor without herdr had to improvise — and the easiest thing to improvise is staying on the
# default branch, which is the failure this whole unit is about. Plain `git worktree add` needs no tool
# beyond git, which is required anyway.
#
# The slug rule is derived, not typed: the directory is the branch name with `/` replaced by `-`, so the
# branch and the directory cannot disagree. `--base` defaults to `origin/main` when that ref resolves and
# to `main` otherwise; an existing branch is checked out instead of recreated.
#
# Usage:
#   ./.github/scripts/new-unit.sh feat/some-unit
#   ./.github/scripts/new-unit.sh fix/a-defect --base origin/main
#   ./.github/scripts/new-unit.sh chore/tooling --repo /path/to/checkout
#
# Run: ./.github/scripts/new-unit.sh --help

set -u

usage() {
	cat <<'EOF'
Usage: new-unit.sh <branch> [--base <ref>] [--repo <path>]

Creates <repo-parent>/<repo-name>-worktrees/<branch with / replaced by -> and checks <branch> out in it,
branching from --base (default: origin/main, else main) when the branch does not exist yet.

  <branch>          the branch the unit is delivered from, e.g. feat/some-unit
  --base <ref>      the commit the new branch starts at
  --repo <path>     the clone to add the worktree to (default: the current directory)
EOF
}

branch=""
base=""
repo="$PWD"

while [ "$#" -gt 0 ]; do
	case "$1" in
	--help | -h)
		usage
		exit 0
		;;
	--base)
		[ "$#" -ge 2 ] || {
			printf 'new-unit.sh: --base needs a ref\n' >&2
			exit 2
		}
		base="$2"
		shift 2
		;;
	--repo)
		[ "$#" -ge 2 ] || {
			printf 'new-unit.sh: --repo needs a path\n' >&2
			exit 2
		}
		repo="$2"
		shift 2
		;;
	-*)
		printf 'new-unit.sh: unknown option %s (see --help)\n' "$1" >&2
		exit 2
		;;
	*)
		[ -z "$branch" ] || {
			printf 'new-unit.sh: one branch per invocation, got %s and %s\n' "$branch" "$1" >&2
			exit 2
		}
		branch="$1"
		shift
		;;
	esac
done

[ -n "$branch" ] || {
	printf 'new-unit.sh: a branch is required (see --help)\n' >&2
	exit 2
}

repo_root="$(git -C "$repo" rev-parse --show-toplevel 2>/dev/null)" || {
	printf 'new-unit.sh: %s is not a git checkout\n' "$repo" >&2
	exit 1
}

# The slug is derived from the branch so the directory always names the branch it carries.
slug="${branch//\//-}"
parent="$(dirname "$repo_root")"
path="$parent/$(basename "$repo_root")-worktrees/$slug"

if [ -e "$path" ]; then
	printf 'new-unit.sh: %s already exists; one worktree, one unit of work -- pick another branch or clean it up first\n' "$path" >&2
	exit 1
fi

if git -C "$repo_root" rev-parse --verify --quiet "refs/heads/$branch" >/dev/null; then
	if ! git -C "$repo_root" worktree add "$path" "$branch" >&2; then
		printf 'new-unit.sh: git worktree add failed for the existing branch %s\n' "$branch" >&2
		exit 1
	fi
else
	if [ -z "$base" ]; then
		if git -C "$repo_root" rev-parse --verify --quiet refs/remotes/origin/main >/dev/null; then
			base="origin/main"
		else
			base="main"
		fi
	fi
	if ! git -C "$repo_root" worktree add -b "$branch" "$path" "$base" >&2; then
		printf 'new-unit.sh: git worktree add failed for %s at %s\n' "$branch" "$base" >&2
		exit 1
	fi
fi

cat <<MSG
worktree created
  branch:  $branch
  path:    $path

  cd $path

The pre-commit hook that refuses a commit on the default branch is installed once per clone and linked
worktrees share it:

  git config core.hooksPath .githooks
MSG
