#!/bin/bash
# ============================================
# LifeControl - an ADR-0006 citation names a section, never a line range
# ============================================
# Answers one question about this tree: does any comment under `life-control-api/src` or `docs` cite
# `docs/adr/0006-catalog-identity.md` by line range? None may, because that address cannot be kept.
#
# Measured on `main` @ `ec19ea72`: nine citations, and every one of them pointed at text that was not
# what it claimed. Two facts make that permanent rather than repairable:
#
#   - THE ADR GROWS. #784 added five lines to decision 1 and moved everything after line 47 by five.
#     Several of the nine were already wrong before that, so "re-measure after each ADR edit" is not a
#     rule that can hold.
#   - THREE OF THE NINE LIVE IN A SHIPPED MIGRATION. `V58__variant_listing.sql` already ran on every
#     database this repository has: editing a comment there changes the Flyway checksum and fails
#     validation. Those three line ranges are frozen, so they can never be repaired -- which is the
#     strongest argument that a line range was the wrong address to begin with.
#
# So the address is the thing that does not move: a decision number (`decision 5`), an invariant id
# (`C7`), a migration step, or a `## Heading`. The three frozen citations are the only legal line
# ranges left, and `FROZEN_ENTRIES` below names what each one intended to say, so the exemption is
# readable instead of implied.
#
# An exemption that no longer cites is a finding, not a silent pass: the list is re-read against the
# file on every run, so a rewritten comment cannot leave a dead entry behind pretending to cover
# something. That is the same asymmetry `odd-status-headers.test.sh` refuses -- a silent skip is
# indistinguishable from a pass.
#
# Scope is the two directories the acceptance criteria measure. `odd/tasks/*.md` is deliberately
# outside: a record that quotes this defect is evidence of it, not a citation to fix.
#
# No network, no `gh`, no YAML: it greps local files only and writes nothing inside the repository.
# Point it at another checkout or a scratch copy by passing a root directory as the first argument (or
# with REPO_ROOT), which is how the fixtures below run it without touching this tree.
#
# Run: ./.github/scripts/tests/adr-line-citations.test.sh
# Exits 0 only when every case passes.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="${1:-${REPO_ROOT:-$(cd "$SCRIPT_DIR/../../.." && pwd)}}"

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

# The address this unit removes: the ADR's path, a colon, and a line number.
CITATION='0006-catalog-identity\.md:[0-9]'

ADR='docs/adr/0006-catalog-identity.md'

# The only legal line ranges in the tree, and what each one intended to say. All three live in one
# shipped migration, whose comments cannot be edited without changing the Flyway checksum.
FROZEN_FILE='life-control-api/src/main/resources/db/migration/V58__variant_listing.sql'
FROZEN_ENTRIES='11|decision 5, the price half of the per-store row it replaces
23|this ADR'"'"'s `## Schema` block, the two deliberate departures from it
90|this ADR'"'"'s `## Schema` block, the column order, types and constraint names it mirrors'
FROZEN_LINES="$(printf '%s\n' "$FROZEN_ENTRIES" | cut -d'|' -f1 | tr '\n' ' ')"

# citations <root> -- every line-range citation in the two directories, as <relative path>:<line>:<text>
citations() {
	local root="$1"
	grep -rnEI -- "$CITATION" "$root/life-control-api/src" "$root/docs" 2>/dev/null \
		| sed "s|^$root/||" || true
}

in_frozen() {
	case " $FROZEN_LINES " in
	*" $1 "*) return 0 ;;
	esac
	return 1
}

# violations <root> -- one line per finding, and nothing at all when the tree is clean.
violations() {
	local root="$1" hit file line text frozen_line frozen_intent

	citations "$root" >"$TMP/citations" || true
	if [ ! -s "$TMP/citations" ] && [ ! -d "$root/life-control-api/src" ] && [ ! -d "$root/docs" ]; then
		printf 'neither life-control-api/src nor docs exists under %s, so this check would pass vacuously\n' "$root"
		return 0
	fi

	while IFS= read -r hit; do
		[ -n "$hit" ] || continue
		file="${hit%%:*}"
		line="${hit#*:}"
		line="${line%%:*}"
		if [ "$file" = "$FROZEN_FILE" ] && in_frozen "$line"; then
			continue
		fi
		printf '%s:%s cites the ADR by line range; name the decision, the invariant, the migration step or the heading instead\n' \
			"$file" "$line"
	done <"$TMP/citations"

	while IFS='|' read -r frozen_line frozen_intent; do
		[ -n "${frozen_line:-}" ] || continue
		text="$(sed -n "${frozen_line}p" "$root/$FROZEN_FILE" 2>/dev/null || true)"
		if printf '%s' "$text" | grep -qE -- "$CITATION"; then
			continue
		fi
		printf '%s:%s is a frozen exemption that no longer cites the ADR (it intended to say: %s); restore the citation or drop the entry deliberately\n' \
			"$FROZEN_FILE" "$frozen_line" "$frozen_intent"
	done <<<"$FROZEN_ENTRIES"
	return 0
}

# A fixture is a directory holding only the two directories the check reads, so no case can borrow a
# file from this tree and pass for the wrong reason.
new_fixture() {
	mkdir -p "$TMP/fixtures/$1/life-control-api/src/main/resources/db/migration" \
		"$TMP/fixtures/$1/life-control-api/src/main/java/com/lifecontrol/api/product/model" \
		"$TMP/fixtures/$1/docs/adr"
	printf '%s' "$TMP/fixtures/$1"
}

# write_frozen <root> [extra citation line] -- the shipped migration, citing at exactly 11, 23 and 90.
write_frozen() {
	local root="$1" extra="${2:-}" file i
	file="$root/$FROZEN_FILE"
	mkdir -p "$(dirname "$file")"
	: >"$file"
	for i in $(seq 1 95); do
		case "$i" in
		11 | 23 | 90)
			printf -- '-- cites docs/adr/0006-catalog-identity.md:%s\n' "$i" >>"$file"
			;;
		*)
			if [ -n "$extra" ] && [ "$i" -eq "$extra" ]; then
				printf '%s\n' '-- a fourth citation docs/adr/0006-catalog-identity.md:109-123' >>"$file"
			else
				printf -- '-- filler %s\n' "$i" >>"$file"
			fi
			;;
		esac
	done
}

expect_pass() {
	local label="$1" root="$2" out
	out="$(violations "$root")"
	if [ -z "$out" ]; then
		pass "$label"
	else
		fail "$label (expected a pass, got: $out)"
	fi
}

expect_finding() {
	local label="$1" root="$2" fragment="$3" out
	out="$(violations "$root")"
	if [ -z "$out" ]; then
		fail "$label (expected a finding, got a pass)"
		return
	fi
	if printf '%s' "$out" | grep -qF -- "$fragment"; then
		pass "$label"
	else
		fail "$label (the finding did not name '$fragment'; it said: $out)"
	fi
}

echo "== fixtures =="

# 1. The clean shape: three frozen citations, and everything else addressed by name.
FX="$(new_fixture clean)"
write_frozen "$FX"
cat >"$FX/life-control-api/src/main/java/com/lifecontrol/api/product/model/VariantListing.java" <<'EOF'
/**
 * ADR-0006 decision 5 splits two facts that shared one lifecycle, and ADR-0006 C7 keeps the cost out.
 */
public class VariantListing {}
EOF
printf '# ADR 0006\n\n## Referring to this ADR\n\nCite `ADR-0006 decision 5`.\n' >"$FX/$ADR"
expect_pass "only the three frozen citations present" "$FX"

# 2. The defect itself, in editable code: a Java comment naming the ADR by line range.
FX="$(new_fixture java-line-range)"
write_frozen "$FX"
cat >"$FX/life-control-api/src/main/java/com/lifecontrol/api/product/model/VariantListing.java" <<'EOF'
/**
 * Decision 5 (<code>docs/adr/0006-catalog-identity.md:57-59</code>) splits two facts.
 */
public class VariantListing {}
EOF
expect_finding "a Java comment that cites the ADR by line range fails" "$FX" \
	"life-control-api/src/main/java/com/lifecontrol/api/product/model/VariantListing.java:2 cites the ADR by line range"

# 3. A fourth citation inside the frozen migration: the file is exempt, the line is not.
FX="$(new_fixture fourth-in-frozen-file)"
write_frozen "$FX" 40
expect_finding "a citation the frozen list does not name fails even inside the exempt file" "$FX" \
	"$FROZEN_FILE:40 cites the ADR by line range"

# 4. A document address that drifts the same way the code ones did.
FX="$(new_fixture doc-line-range)"
write_frozen "$FX"
printf '# ADR 0006\n\nDecision 5 (`docs/adr/0006-catalog-identity.md:57-59`) splits two facts.\n' >"$FX/$ADR"
expect_finding "a document that cites the ADR by line range fails" "$FX" \
	"$ADR:3 cites the ADR by line range"

# 5. An exemption that stopped citing: the list has rotted and has to be re-read, not trusted.
FX="$(new_fixture rotted-exemption)"
write_frozen "$FX"
sed -i '23s|.*|-- the Schema block, addressed by name|' "$FX/$FROZEN_FILE"
expect_finding "a frozen entry whose line no longer cites is a finding, never a silent pass" "$FX" \
	"$FROZEN_FILE:23 is a frozen exemption that no longer cites the ADR"

# 6. Out of scope on purpose: a record quoting the defect is evidence, not a citation to fix.
FX="$(new_fixture record-quoting-the-defect)"
write_frozen "$FX"
mkdir -p "$FX/odd/tasks"
printf '**Status**: implemented — quoted `docs/adr/0006-catalog-identity.md:57-59` as the defect.\n' \
	>"$FX/odd/tasks/example.md"
expect_pass "an ODD record quoting the defect is outside the scope" "$FX"

# 7. A tree the check cannot read is a finding, not a pass.
FX="$TMP/fixtures/empty-tree"
mkdir -p "$FX"
expect_finding "a tree with neither scanned directory fails instead of passing vacuously" "$FX" \
	"neither life-control-api/src nor docs exists"

echo "== this tree =="

# The exemption is legal only where it is frozen: inside a migration that already shipped. An entry
# outside `db/migration/` would be an editable comment excused by a list, which is the defect again.
case "$FROZEN_FILE" in
life-control-api/src/main/resources/db/migration/*) pass "every frozen exemption lives in a shipped migration" ;;
*) fail "the frozen list names $FROZEN_FILE, which is not a migration and could be edited" ;;
esac

# The ADR states the addressing rule this suite enforces, and its headings are now the addresses
# citations use -- so a renamed section is a citation defect and this case says so.
if grep -qx -- '#.*Referring to this ADR' "$ROOT/$ADR" 2>/dev/null; then
	pass "the ADR carries the '## Referring to this ADR' section citations point at"
else
	fail "the ADR carries no '## Referring to this ADR' heading, so the addresses this suite requires are undocumented"
fi

REAL_FINDINGS="$(violations "$ROOT")"
REAL_CITATIONS="$(citations "$ROOT" | grep -c . || true)"
FROZEN_COUNT="$(printf '%s\n' "$FROZEN_ENTRIES" | grep -c . || true)"

if [ -z "$REAL_FINDINGS" ]; then
	pass "no citation on this tree is outside the frozen list"
else
	fail "this tree carries line-range citations the frozen list does not excuse: $REAL_FINDINGS"
fi

if [ "$REAL_CITATIONS" -eq "$FROZEN_COUNT" ]; then
	pass "the tree carries exactly $FROZEN_COUNT line-range citation(s), all of them frozen"
else
	fail "the tree carries $REAL_CITATIONS line-range citation(s) and the frozen list has $FROZEN_COUNT; every one named in the list has to exist and no other may"
fi

printf '\nchecked %s citation(s) across life-control-api/src and docs; %s frozen\n' \
	"$REAL_CITATIONS" "$FROZEN_COUNT"
printf '%d passed, %d failed\n' "$PASS_COUNT" "$FAIL_COUNT"
[ "$FAIL_COUNT" -eq 0 ]
