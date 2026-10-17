#!/bin/bash
# ============================================
# LifeControl - issue reference gate
# ============================================
# Answers one question about one pull request: does its body reference an open issue that
# carries `status:approved`? The PR template declares the link as REQUIRED, and until this
# script existed the rule was a review-time convention: of the last 60 merged pull requests,
# 55 carried no reference at all.
#
#   exit 0 — the body references an open, approved issue
#   exit 1 — no reference, an unusable reference, or an unreadable input
#
# Why a script and not inline YAML: the rule has more than a dozen distinct failure modes
# (no reference, a keyword on the wrong base branch, a pull request number, a closed issue, a
# missing label, a cross-repository reference, malformed JSON, an unexpected number) and each
# one needs its own case in a test. Inline shell inside a workflow step can only be tested by
# pushing a branch, which is the wrong price for a gate that runs on every PR.
#
# The gate never calls the network: mode 1 (`--print-refs`) prints the numbers the workflow
# must fetch, mode 2 takes the fetched issues as JSON. That is what keeps every rule above
# testable offline in `.github/scripts/tests/check-issue-reference.test.sh`.
#
# `Refs #N` is accepted only off the default branch, because GitHub interprets closing
# keywords only when the pull request targets the default branch: a chained slice cannot close
# its issue from the child pull request, so requiring a closing keyword there would make the
# chain impossible instead of making it correct. The nine keywords GitHub honors are matched —
# `close`, `closes`, `closed`, `fix`, `fixes`, `fixed`, `resolve`, `resolves`, `resolved` —
# because a contributor who writes `Fix #12` means exactly what `Closes #12` means.
#
# `jq` is required for the JSON, as in check-pr-type-labels.sh.
#
# Usage:
#   check-issue-reference.sh --body-file body.md --base-branch br --default-branch main \
#     --issues-json '[{"number":12,"state":"OPEN","labels":[{"name":"status:approved"}]}]'
#   check-issue-reference.sh --body "$PR_BODY" --base-branch br --default-branch main --print-refs
#
# Run: ./.github/scripts/check-issue-reference.sh --help

set -u

usage() {
	cat <<'EOF'
Usage: check-issue-reference.sh --base-branch NAME --default-branch NAME
                               (--body TEXT | --body-file PATH)
                               [--issues-json JSON | --issues-json-file PATH]
                               [--print-refs]

Fails unless the pull request body references an open issue carrying `status:approved`.
--print-refs prints the referenced numbers, closing references first, for the caller to fetch.
EOF
}

body=""
body_file=""
base_branch=""
default_branch=""
issues_json=""
issues_json_file=""
print_refs=0

needs_value() {
	printf 'FAIL: %s needs a value\n' "$1" >&2
	exit 1
}

while [ "$#" -gt 0 ]; do
	case "$1" in
	--body)
		[ "$#" -ge 2 ] || needs_value "--body"
		body="$2"
		shift 2
		;;
	--body-file)
		[ "$#" -ge 2 ] || needs_value "--body-file"
		body_file="$2"
		shift 2
		;;
	--base-branch)
		[ "$#" -ge 2 ] || needs_value "--base-branch"
		base_branch="$2"
		shift 2
		;;
	--default-branch)
		[ "$#" -ge 2 ] || needs_value "--default-branch"
		default_branch="$2"
		shift 2
		;;
	--issues-json)
		[ "$#" -ge 2 ] || needs_value "--issues-json"
		issues_json="$2"
		shift 2
		;;
	--issues-json-file)
		[ "$#" -ge 2 ] || needs_value "--issues-json-file"
		issues_json_file="$2"
		shift 2
		;;
	--print-refs)
		print_refs=1
		shift
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

if [ -z "$base_branch" ]; then
	printf 'FAIL: --base-branch is required\n' >&2
	exit 1
fi

if [ -z "$default_branch" ]; then
	printf 'FAIL: --default-branch is required\n' >&2
	exit 1
fi

if [ -n "$body" ] && [ -n "$body_file" ]; then
	printf 'FAIL: --body and --body-file are mutually exclusive\n' >&2
	exit 1
fi

if [ -z "$body" ] && [ -z "$body_file" ]; then
	printf 'FAIL: provide --body or --body-file\n' >&2
	exit 1
fi

if [ -n "$body_file" ]; then
	if [ ! -r "$body_file" ]; then
		printf 'FAIL: cannot read --body-file: %s\n' "$body_file" >&2
		exit 1
	fi
	body="$(cat "$body_file")" || {
		printf 'FAIL: cannot read --body-file: %s\n' "$body_file" >&2
		exit 1
	}
fi

# A Windows checkout can hand the body over with CRLF endings; the keywords must still match.
body="${body//$'\r'/}"

if [ -z "${body//[[:space:]]/}" ]; then
	printf 'FAIL: the pull request body is empty, so it cannot reference an issue\n' >&2
	exit 1
fi

# A reference to another repository cannot be verified with this repository's token, and
# silently ignoring it would report a pass for a link nobody checked.
if printf '%s' "$body" | grep -qiE '[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+[[:space:]]*#[0-9]+'; then
	cross="$(printf '%s' "$body" | grep -oiE '[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+[[:space:]]*#[0-9]+' | head -1)"
	printf 'FAIL: cross-repository reference %s cannot be verified by this gate; reference an issue of this repository\n' \
		"'$cross'" >&2
	exit 1
fi

closing_numbers=""
ref_numbers=""

while IFS= read -r match; do
	[ -n "$match" ] || continue
	number="$(printf '%s' "$match" | tr -cd '0-9')"
	[ -n "$number" ] || continue
	case " $closing_numbers " in
	*" $number "*) continue ;;
	esac
	closing_numbers="$closing_numbers $number"
done < <(printf '%s' "$body" | grep -oiE '(close[sd]?|fix(es|ed)?|resolve[sd]?)[[:space:]]*:?[[:space:]]*#[0-9]+' || true)

while IFS= read -r match; do
	[ -n "$match" ] || continue
	number="$(printf '%s' "$match" | tr -cd '0-9')"
	[ -n "$number" ] || continue
	case " $closing_numbers " in
	*" $number "*) continue ;;
	esac
	case " $ref_numbers " in
	*" $number "*) continue ;;
	esac
	ref_numbers="$ref_numbers $number"
done < <(printf '%s' "$body" | grep -oiE 'refs[[:space:]]*:?[[:space:]]*#[0-9]+' || true)

if [ -z "$closing_numbers" ] && [ -z "$ref_numbers" ]; then
	printf 'FAIL: no issue reference in the pull request body; add a closing keyword (Closes #N, or the close/closed, fix/fixed, resolve/resolved forms) or Refs #N off the default branch\n' >&2
	exit 1
fi

if [ "$base_branch" = "$default_branch" ] && [ -z "$closing_numbers" ]; then
	printf 'FAIL: this pull request targets the default branch %s: a closing reference (Closes #N and the close/closed, fix/fixed, resolve/resolved forms) is required, because GitHub ignores closing keywords on any other base\n' \
		"'$base_branch'" >&2
	exit 1
fi

# Every number this script accepts is one the caller had to fetch; mode 1 is that list.
if [ "$print_refs" -eq 1 ]; then
	for number in $closing_numbers $ref_numbers; do
		printf '%s\n' "$number"
	done
	exit 0
fi

if [ -n "$issues_json" ] && [ -n "$issues_json_file" ]; then
	printf 'FAIL: --issues-json and --issues-json-file are mutually exclusive\n' >&2
	exit 1
fi

if [ -n "$issues_json_file" ]; then
	if [ ! -r "$issues_json_file" ]; then
		printf 'FAIL: cannot read --issues-json-file: %s\n' "$issues_json_file" >&2
		exit 1
	fi
	issues_json="$(cat "$issues_json_file")" || {
		printf 'FAIL: cannot read --issues-json-file: %s\n' "$issues_json_file" >&2
		exit 1
	}
fi

if [ -z "$issues_json" ]; then
	printf 'FAIL: provide --issues-json or --issues-json-file, or use --print-refs\n' >&2
	exit 1
fi

command -v jq >/dev/null 2>&1 || {
	printf 'FAIL: jq is required to read --issues-json\n' >&2
	exit 1
}

# The shape is checked before the content is read: a payload that is not a JSON array is an
# unreadable issue set, and must say so instead of reading as "no approved issue".
if ! printf '%s' "$issues_json" | jq -e 'type == "array"' >/dev/null 2>&1; then
	printf 'FAIL: --issues-json is not a JSON array: %s\n' "$issues_json" >&2
	exit 1
fi

if ! printf '%s' "$issues_json" | jq -e 'length > 0' >/dev/null 2>&1; then
	printf 'FAIL: --issues-json is empty, so no referenced number could be read\n' >&2
	exit 1
fi

issue_lines="$(printf '%s' "$issues_json" | jq -r '.[] | [((.number // "") | tostring), (if has("pull_request") then "pull_request" else "issue" end), (.state // ""), (((.labels // []) | map(if type == "string" then . else (.name // empty) end)) | join(","))] | @tsv')" || {
	printf 'FAIL: --issues-json could not be read as a list of issues\n' >&2
	exit 1
}

saw_issue=0
approved_number=""
rejected_label_number=""
rejected_state_number=""

while IFS=$'\t' read -r number kind state labels; do
	[ -n "$number" ] || continue
	# A number nobody asked to fetch is a caller defect, and an unchecked reference would
	# otherwise read as a pass.
	case " $closing_numbers $ref_numbers " in
	*" $number "*) ;;
	*)
		printf 'FAIL: unexpected referenced number #%s in --issues-json\n' "$number" >&2
		exit 1
		;;
	esac

	if [ "$kind" = "pull_request" ]; then
		# A closing keyword on a pull request number closes nothing this gate can verify.
		# `Refs #N` to another pull request is how a chained slice names its parent, so it is
		# ignored here and the gate still needs a real approved issue below.
		case " $closing_numbers " in
		*" $number "*)
			printf 'FAIL: #%s is a pull request, and a closing keyword on a pull request number cannot stand in for the approved issue\n' "$number" >&2
			exit 1
			;;
		esac
		continue
	fi

	saw_issue=1

	case ",$labels," in
	*,status:approved,*) approved_label=1 ;;
	*) approved_label=0 ;;
	esac

	# The REST API reports `open`/`closed` in lower case; comparing literally against `OPEN`
	# rejected every genuinely open issue, a defect the offline fixtures had agreed with until
	# a live payload was read through this same path. The state is echoed as the API reports it.
	state_upper="$(printf '%s' "$state" | tr '[:lower:]' '[:upper:]')"

	if [ "$state_upper" != "OPEN" ]; then
		rejected_state_number="$number:$state"
		continue
	fi

	if [ "$approved_label" -ne 1 ]; then
		rejected_label_number="$number:$labels"
		continue
	fi

	# On the default branch the approved issue must be the one the closing keyword names;
	# off it, a `Refs #N` to the approved issue is enough.
	if [ "$base_branch" = "$default_branch" ]; then
		case " $closing_numbers " in
		*" $number "*) approved_number="$number" ;;
		esac
	else
		approved_number="$number"
	fi
done <<<"$issue_lines"

if [ -n "$approved_number" ]; then
	printf 'PASS: #%s is open and carries status:approved\n' "$approved_number"
	exit 0
fi

if [ "$saw_issue" -eq 0 ]; then
	printf 'FAIL: no referenced number is an issue; a reference must name an approved issue of this repository\n' >&2
	exit 1
fi

if [ -n "$rejected_label_number" ]; then
	printf 'FAIL: issue #%s does not carry the status:approved label (labels: %s); approval is a maintainer decision applied to the issue before the pull request is opened\n' \
		"${rejected_label_number%%:*}" "${rejected_label_number#*:}" >&2
	exit 1
fi

if [ -n "$rejected_state_number" ]; then
	printf 'FAIL: issue #%s is not open (state=%s)\n' \
		"${rejected_state_number%%:*}" "${rejected_state_number#*:}" >&2
	exit 1
fi

printf 'FAIL: no referenced issue is open and carries status:approved\n' >&2
exit 1
