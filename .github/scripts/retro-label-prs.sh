#!/bin/bash
# ============================================
# LifeControl - retro-label merged pull requests
# ============================================
# Gives every merged pull request the single `type:*` label its own conventional-commit
# prefix maps to, so that the history is consistent with the rule the guard enforces from
# now on. On its first run it planned 328 mutations over 341 merged pull requests.
#
# The mapping is the one the machine-installed `branch-pr` skill declares and
# `.agents/skills/project-conventions/references/labels.md` mirrors:
#
#   feat, perf                      -> type:feature
#   fix, revert                     -> type:bug
#   docs                            -> type:docs
#   refactor                        -> type:refactor
#   chore, style, test, build, ci   -> type:chore
#   any type with the `!` marker    -> type:breaking-change
#
# Three properties are deliberate:
#
#   1. Dry run by default. It prints the plan and touches nothing; `--apply` is opt-in
#      and adds one flag to a command that is otherwise identical.
#   2. Fail closed, and before mutating. A title outside conventional commits stops the
#      whole run, because a silently skipped pull request is a label nobody reads again.
#      The plan is fully computed before the first `gh` call, so a mapping error can never
#      leave the history half-labelled.
#   3. Idempotent. When a pull request already carries the right single `type:*` label,
#      nothing is planned and no `gh` call happens.
#
# It removes a second `type:*` label when one is present: two answers to a single-valued
# question violate the same invariant as zero. Labels outside the namespace (the triage
# vocabulary: `documentation`, `bug`, `enhancement`, `java`, `dependencies`) are never
# touched — they answer a different question.
#
# Usage:
#   gh pr list --state merged --limit 1000 --json number,title,labels > /tmp/prs.json
#   retro-label-prs.sh --prs-file /tmp/prs.json               # plan only
#   retro-label-prs.sh --prs-file /tmp/prs.json --apply       # mutate
#
# The `!` case is stated rather than guessed: the skill's table enumerates only
# `feat!`/`fix!`, but `!` is the conventional-commits breaking marker whatever the type,
# so a breaking refactor is a breaking change and gets `type:breaking-change`.

set -u

usage() {
	cat <<'EOF'
Usage: retro-label-prs.sh [--prs-file PATH | --prs-json JSON] [--apply] [--gh PATH]

Reads the merged pull requests as the JSON array `gh pr list --state merged
--limit 1000 --json number,title,labels` produces, and plans one mutation per pull
request whose labels do not match its conventional-commit prefix.

Without --apply it only prints the plan.
EOF
}

prs_file=""
prs_json=""
apply=0
gh_path="gh"

while [ "$#" -gt 0 ]; do
	case "$1" in
	--prs-file)
		prs_file="${2-}"
		shift 2 || exit 1
		;;
	--prs-json)
		prs_json="${2-}"
		shift 2 || exit 1
		;;
	--apply)
		apply=1
		shift
		;;
	--gh)
		gh_path="${2-}"
		shift 2 || exit 1
		;;
	-h | --help)
		usage
		exit 0
		;;
	*)
		printf 'FAIL: unknown argument: %s\n' "$1" >&2
		usage >&2
		exit 1
		;;
	esac
done

if [ -n "$prs_file" ] && [ -n "$prs_json" ]; then
	printf 'FAIL: --prs-file and --prs-json are mutually exclusive\n' >&2
	exit 1
fi

if [ -z "$prs_file" ] && [ -z "$prs_json" ]; then
	printf 'FAIL: one of --prs-file or --prs-json is required\n' >&2
	usage >&2
	exit 1
fi

if [ -n "$prs_file" ] && [ ! -r "$prs_file" ]; then
	printf 'FAIL: cannot read: %s\n' "$prs_file" >&2
	exit 1
fi

command -v jq >/dev/null 2>&1 || {
	printf 'FAIL: jq is required\n' >&2
	exit 1
}

# The pattern lives in a variable on purpose: bash 5.2 rejects an escaped parenthesis in an
# inline `=~` pattern with a parse error, and the error surfaces only when the function is
# defined — an empty-variable form is both portable and the one shellcheck can read.
TITLE_RE='^([a-z]+)(\(([^)]*)\))?(!)?: .+'

# desired_label <title> — prints the single `type:*` label the title maps to, or nothing
# and exit 1 when the title is not a conventional commit.
desired_label() {
	local title="$1" type bang
	if [[ "$title" =~ $TITLE_RE ]]; then
		type="${BASH_REMATCH[1]}"
		bang="${BASH_REMATCH[4]}"
	else
		return 1
	fi
	if [ -n "$bang" ]; then
		printf 'type:breaking-change'
		return 0
	fi
	case "$type" in
	feat | perf) printf 'type:feature' ;;
	fix | revert) printf 'type:bug' ;;
	docs) printf 'type:docs' ;;
	refactor) printf 'type:refactor' ;;
	chore | style | test | build | ci) printf 'type:chore' ;;
	*) return 1 ;;
	esac
}

source_cmd=(cat "${prs_file:-/dev/stdin}")
if [ -n "$prs_json" ]; then
	source_cmd=(printf '%s' "$prs_json")
fi

# One row per pull request: number, title and the comma-joined label names, as TSV. A
# malformed payload must fail, not read as an empty inventory.
if ! rows="$("${source_cmd[@]}" | jq -er '.[] | [ (.number | tostring), .title, ([.labels[]?.name] | join(",")) ] | @tsv' 2>&1)"; then
	printf 'FAIL: input is not the JSON array `gh pr list --json number,title,labels` produces: %s\n' "$rows" >&2
	exit 1
fi

plan_lines=()
apply_numbers=()
apply_args=()
additions=0
removals=0
pr_count=0

while IFS=$'\t' read -r number title labels; do
	[ -n "$number" ] || continue
	pr_count=$((pr_count + 1))

	if ! desired="$(desired_label "$title")"; then
		printf 'FAIL: pull request #%s has no conventional-commit type to map: %s\n' "$number" "$title" >&2
		exit 1
	fi

	existing=()
	IFS=',' read -r -a existing <<<"$labels"
	present=""
	for label in ${existing[@]+"${existing[@]}"}; do
		[ "$label" = "$desired" ] && present="yes"
	done

	pr_removals=()
	for label in ${existing[@]+"${existing[@]}"}; do
		case "$label" in
		type:*) [ "$label" = "$desired" ] || pr_removals+=("$label") ;;
		esac
	done

	if [ -n "$present" ] && [ "${#pr_removals[@]}" -eq 0 ]; then
		continue
	fi

	if [ "${#pr_removals[@]}" -gt 0 ]; then
		for label in "${pr_removals[@]}"; do
			plan_lines+=("REMOVE $number $label")
			removals=$((removals + 1))
		done
	fi

	if [ -z "$present" ]; then
		plan_lines+=("ADD $number $desired")
		additions=$((additions + 1))
	fi

	args=(pr edit "$number")
	for label in ${pr_removals[@]+"${pr_removals[@]}"}; do
		args+=(--remove-label "$label")
	done
	if [ -z "$present" ]; then
		args+=(--add-label "$desired")
	fi
	apply_numbers+=("$number")
	apply_args+=("${args[*]}")
done <<<"$rows"

for line in ${plan_lines[@]+"${plan_lines[@]}"}; do
	printf '%s\n' "$line"
done
printf 'plan: %d addition(s), %d removal(s) over %d pull request(s)\n' "$additions" "$removals" "$pr_count"

if [ "$apply" -eq 0 ]; then
	exit 0
fi

if [ "${#apply_numbers[@]}" -eq 0 ]; then
	printf 'apply: 0 pull request(s) edited\n'
	exit 0
fi

edited=0
for i in "${!apply_numbers[@]}"; do
	read -r -a args <<<"${apply_args[$i]}"
	if ! "$gh_path" "${args[@]}"; then
		printf 'FAIL: gh failed for pull request #%s; the remaining edits were not attempted\n' "${apply_numbers[$i]}" >&2
		exit 1
	fi
	edited=$((edited + 1))
done
printf 'apply: %d pull request(s) edited\n' "$edited"
