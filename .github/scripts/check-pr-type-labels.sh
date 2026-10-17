#!/bin/bash
# ============================================
# LifeControl - PR type label guard
# ============================================
# Answers one question about one pull request: does its label set carry exactly one
# `type:*` label, and is that label a member of the declared vocabulary?
#
#   exit 0 — exactly one declared `type:*` label
#   exit 1 — zero labels, more than one, an unknown name, or an unreadable input
#
# Why a script and not inline YAML: the rule has four distinct failure modes (zero, two,
# a synonym, an unreadable set) and each one needs its own case in a test. Inline shell
# inside a workflow step can only be tested by pushing a branch, which is the wrong price
# for a gate that runs on every PR of the repository.
#
# The vocabulary is not written here: it is read from `.github/label-vocabulary.txt`, the one
# machine-readable source. `.github/PULL_REQUEST_TEMPLATE.md` and
# `.agents/skills/project-conventions/references/labels.md` carry the same six names, and
# `.github/scripts/tests/vocabulary-source.test.sh` freezes the three artifacts together —
# the way `docker/scripts/tests/image-pins.test.sh` freezes the image set on purpose: a name
# that exists in one artifact and not the others is the defect those files exist to catch. A
# seventh `type:*` label must be added to the source file and both documentation artifacts at
# once, in the same commit.
#
# Fails closed: a missing, unreadable or empty vocabulary source, an empty label set, an
# unreadable file, malformed JSON and an unknown argument are all failures, never a silent
# pass.
#
# Usage:
#   check-pr-type-labels.sh --labels-json '["java","type:docs"]'
#   check-pr-type-labels.sh --labels-file pr-labels.txt     # one name per line
#   printf 'type:docs\n' | check-pr-type-labels.sh           # stdin
#
# Run: ./.github/scripts/check-pr-type-labels.sh --help

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VOCABULARY_FILE="$SCRIPT_DIR/../label-vocabulary.txt"

usage() {
	cat <<'EOF'
Usage: check-pr-type-labels.sh [--labels-json JSON | --labels-file PATH]

Fails unless the given label set contains exactly one declared `type:*` label.
With no option the label names are read from stdin, one per line.

The vocabulary is read from `.github/label-vocabulary.txt`, beside this script's parent
directory: a missing, unreadable or empty source is itself a failure.
EOF
}

labels_json=""
labels_file=""

while [ "$#" -gt 0 ]; do
	case "$1" in
	--labels-json)
		labels_json="${2-}"
		shift 2 || {
			printf 'FAIL: --labels-json needs a value\n' >&2
			exit 1
		}
		;;
	--labels-file)
		labels_file="${2-}"
		shift 2 || {
			printf 'FAIL: --labels-file needs a value\n' >&2
			exit 1
		}
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

if [ -n "$labels_json" ] && [ -n "$labels_file" ]; then
	printf 'FAIL: --labels-json and --labels-file are mutually exclusive\n' >&2
	exit 1
fi

# The vocabulary is read before any label is validated: a broken source is a broken gate,
# not a gate that silently rejects every name. An empty read is empty vocabulary, and empty
# vocabulary would make every label unknown, so it fails here instead.
vocabulary=()
if [ ! -f "$VOCABULARY_FILE" ] || [ ! -r "$VOCABULARY_FILE" ]; then
	printf 'FAIL: cannot read the label vocabulary source: %s\n' "$VOCABULARY_FILE" >&2
	exit 1
fi
while IFS= read -r line; do
	line="${line%$'\r'}"
	[ -n "$line" ] && vocabulary+=("$line")
done <"$VOCABULARY_FILE"

if [ "${#vocabulary[@]}" -eq 0 ]; then
	printf 'FAIL: the label vocabulary source is empty: %s\n' "$VOCABULARY_FILE" >&2
	exit 1
fi
VOCABULARY="${vocabulary[*]}"

labels=()

if [ -n "$labels_json" ]; then
	command -v jq >/dev/null 2>&1 || {
		printf 'FAIL: jq is required to read --labels-json\n' >&2
		exit 1
	}
	# The shape is checked before the content is read: a payload that is not a JSON array
	# is an unreadable label set, and must say so instead of reading as "no labels".
	if ! printf '%s' "$labels_json" | jq -e 'type == "array"' >/dev/null 2>&1; then
		printf 'FAIL: --labels-json is not a JSON array: %s\n' "$labels_json" >&2
		exit 1
	fi
	while IFS= read -r line; do
		[ -n "$line" ] && labels+=("$line")
	done < <(printf '%s' "$labels_json" | jq -r '.[] | select(type == "string")')
elif [ -n "$labels_file" ] || [ "$#" -eq 0 ]; then
	while IFS= read -r line; do
		line="${line%$'\r'}"
		[ -n "$line" ] && labels+=("$line")
	done <"${labels_file:-/dev/stdin}" || {
		printf 'FAIL: cannot read label source: %s\n' "${labels_file:-stdin}" >&2
		exit 1
	}
fi

type_labels=()
for label in ${labels[@]+"${labels[@]}"}; do
	case "$label" in
	type:*) type_labels+=("$label") ;;
	esac
done

if [ "${#type_labels[@]}" -eq 0 ]; then
	printf 'FAIL: no type:* label on this pull request; add exactly one of: %s\n' "$VOCABULARY" >&2
	exit 1
fi

if [ "${#type_labels[@]}" -gt 1 ]; then
	printf 'FAIL: %d type:* labels on this pull request (%s); exactly one is required, and a change that is genuinely two types is two pull requests\n' \
		"${#type_labels[@]}" "$(printf '%s, ' "${type_labels[@]}" | sed 's/, $//')" >&2
	exit 1
fi

only="${type_labels[0]}"
declared=0
for label in $VOCABULARY; do
	[ "$label" = "$only" ] && declared=1
done

if [ "$declared" -ne 1 ]; then
	printf 'FAIL: unknown type label %s; the declared vocabulary is: %s\n' "'$only'" "$VOCABULARY" >&2
	exit 1
fi

printf 'PASS: exactly one type:* label: %s\n' "$only"
