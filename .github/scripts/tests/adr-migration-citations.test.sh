#!/bin/bash
# ============================================
# LifeControl - a citation from docs/ into a migration resolves, fits, and names something the migration declares
# ============================================
# Answers one question about this tree: does every `path:line` citation written under `docs/` that points
# into `life-control-api/src/main/resources/db/migration/` resolve to a file that exists, to a range that
# fits, and to a migration that declares at least one identifier the citing line names?
#
# WHY THIS EXISTS. `adr-line-citations.test.sh` forbids addressing an ADR by line range, because an ADR
# grows and the range moves. It says nothing about the other direction: a line range written FROM a
# document INTO a migration. Measured on `main` @ `71ca18fe`, `docs/` carries **101** of them across 7
# files, in two shapes -- the full filename (`V1__baseline_schema.sql:468-482`) and the version alone
# (`V1:477`) -- plus a comma continuation that keeps one prefix for several lines (`V12:19,39`).
#
# THE HAZARD IS THE WINDOW BEFORE THE MERGE, NOT THE DRIFT AFTER IT. A merged migration is frozen by its
# Flyway checksum: it cannot move, so a citation into `V1` cannot drift. What moves is a migration still
# open on a branch -- `V85` was rewritten during its own slice -- and every citation into it shifts
# silently. The other half is a citation written wrong from the start: ADR-0002 cited the
# `sales_order_items` column list as `V1:496-509` when the columns are `V1:499-509`, and no gate failed.
#
# THE IDENTIFIER RULE IS A FILE-LEVEL TEST, AND THAT IS DELIBERATE. It asks whether the citing line
# names, in backticks, something the migration it points at declares -- anywhere in that file, not
# necessarily inside the cited range. The first version of this rule required the name inside the range
# and failed **17** legitimate citations on this tree; every one of them was the rule's fault, not the
# document's. `docs/adr/0005-tenant-isolation-rls.md:369` cites `V37:140` for
# `product_attribute_profile_roots`, and `V37:140` is `company_id UUID REFERENCES companies(id)` -- a
# column OF that table, whose name is seventy lines up. `docs/adr/0006-catalog-identity.md:66` cites
# `V13:32` for `cost_price` the same way, and so does `docs/adr/0003-document-identity-per-tenant.md:20`.
# A range is an address into a file; "does this file hold what the sentence is about" is the only
# question the text can answer without understanding the sentence.
#
# THREE NARROWINGS make that file-level test true on this tree, and each one is a measured case:
#   - A PARAGRAPH IS THE UNIT, NOT A PHYSICAL LINE. Markdown wraps a sentence across lines and puts a
#     sibling citation in the same sentence: `docs/adr/0004-typed-state-machine.md:37` cites `V20:39`
#     while naming `goods_receipts`, which belongs to the `V12:25` two lines above. So a name is compared
#     against every migration the citing PARAGRAPH points at.
#   - A MIGRATION VERSION IS AN ADDRESS, NOT A NAME. `docs/adr/README.md:67` names `V88` while citing
#     `V58:26-32`; `V88` is a file, and the resolution rule above already validates it, so `V88` is not
#     part of the identifier set.
#   - A NAME NO MIGRATION DECLARES IS NOT JUDGED. `docs/adr/0003-document-identity-per-tenant.md:120`
#     names `fk_sales_orders_store_same_company`, a constraint ADR-0002 plans to add, and cites
#     `V51:86-90` as the pattern it will follow. The tree cannot place that name, so this rule says
#     nothing about it -- the same asymmetry as a line that names nothing in backticks.
#
# WHAT IT CANNOT SEE, stated so nobody trusts it for more than it does:
#   - a range that is merely too wide. `V1:496-509` CONTAINS `V1:499-509`, so it holds every identifier
#     the line named and passes here. Catching "starts one line too early" needs the meaning of the
#     sentence, and that is not a check -- it is a rewrite of every citation into a section address.
#   - a citation that points at the wrong lines of the right file: the name is in the file, so this
#     passes. It catches a wrong file (the name lives only in migrations the citing paragraph does not
#     point at), a wrong version, an out-of-bounds range, and a name the file never declares while
#     another migration the paragraph cites does.
#   - a name the paragraph also points at elsewhere, but not in the file this citation addresses. The
#     paragraph is the unit because a wrapped sentence cannot be split mechanically, and that is the
#     price of it.
#   - a name no migration declares at all -- a typo and a planned name look identical here, and both are
#     skipped rather than guessed at.
#   - a line with no backticked identifier names nothing to compare against, so the identifier rule is
#     skipped there; only resolution and bounds apply.
#   - the comma continuation is expanded for bounds, but every element is compared against the whole
#     citing line's identifiers, not against the clause it belongs to.
#
# No network, no `gh`, no YAML: it reads local files and writes nothing inside the repository. Point it at
# another checkout or a scratch fixture by passing a root as the first argument (or with REPO_ROOT), which
# is how the fixtures below run it without touching this tree.
#
# Run: ./.github/scripts/tests/adr-migration-citations.test.sh
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

MIG_REL='life-control-api/src/main/resources/db/migration'

# `V1__baseline_schema.sql:468-482` or `V1:477`, each optionally continuing with `,39` or `,18-20`.
# Bracket expressions and not `\.`: awk reads a `-v` value as an escape-bearing string and warns that
# `\.` was treated as a plain `.`, and a warning suppressed is a warning nobody reads.
CITATION='(V[0-9]+__[A-Za-z0-9_]+[.]sql|V[0-9]+):[0-9]+(-[0-9]+)?(,[0-9]+(-[0-9]+)?)*'

# citations <root> -- every citation under docs/, one per line, tab separated:
# <relative path> <line number> <paragraph number> <citation> <the whole citing line>
# The paragraph number restarts at 1 in each file and advances on a blank line, because a markdown
# paragraph -- not a physical line -- is the unit the identifier rule compares against.
citations() {
	local root="$1"
	[ -d "$root/docs" ] || return 0
	find "$root/docs" -type f -name '*.md' | sort | while IFS= read -r f; do
		awk -v rel="${f#"$root"/}" -v re="$CITATION" '
			/^[[:space:]]*$/ { para++; next }
			{
				s = $0
				while (match(s, re)) {
					printf "%s\t%s\t%s\t%s\t%s\n", rel, NR, para, substr(s, RSTART, RLENGTH), $0
					s = substr(s, RSTART + RLENGTH)
				}
			}' "$f"
	done
}

# candidates <root> <Vn> -- every migration whose version is Vn, one path per line.
candidates() {
	local root="$1" ver="$2" f
	for f in "$root/$MIG_REL/$ver"__*.sql; do
		[ -f "$f" ] || continue
		printf '%s\n' "$f"
	done
}

# paragraph_files <rel> <paragraph> -- every migration the citing paragraph points at, one path per line.
paragraph_files() {
	awk -F'\t' -v rel="$1" -v para="$2" '$1 == rel && $3 == para { print $5 }' "$TMP/resolved" | sort -u
}

# identifiers <text> -- the backticked single-word names a citing line gives, one per line. A migration
# version is an address rather than a name, so it is not one of them.
identifiers() {
	printf '%s\n' "$1" | grep -oE '`[A-Za-z_][A-Za-z0-9_]*`' | tr -d '`' | grep -vE '^V[0-9]+$' | sort -u
}

# violations <root> -- one line per finding, and nothing at all when the tree is clean.
violations() {
	local root="$1" rel line para cite text ver spec part start end target count found judged ident file

	if [ ! -d "$root/docs" ]; then
		printf 'no docs/ directory exists under %s, so this check would pass vacuously\n' "$root"
		return 0
	fi

	citations "$root" >"$TMP/citations" || true
	: >"$TMP/resolved"

	# Resolution first, for the whole tree: the identifier rule needs to know what else the citing
	# paragraph points at, so every citation is resolved before any one of them is judged.
	while IFS=$'\t' read -r rel line para cite text; do
		[ -n "${rel:-}" ] || continue
		ver="$(printf '%s' "$cite" | sed -E 's/^(V[0-9]+).*/\1/')"

		# Resolution: the filename form names the file, the short form must be unambiguous.
		if [ -f "$root/$MIG_REL/${cite%%:*}" ]; then
			target="$root/$MIG_REL/${cite%%:*}"
		else
			count="$(candidates "$root" "$ver" | grep -c . || true)"
			if [ "$count" -eq 0 ]; then
				printf '%s:%s cites %s, and no migration with version %s exists\n' \
					"$rel" "$line" "$cite" "$ver"
				continue
			fi
			if [ "$count" -gt 1 ]; then
				printf '%s:%s cites %s, which names %s migrations by version alone (%s); name the file\n' \
					"$rel" "$line" "$cite" "$count" \
					"$(candidates "$root" "$ver" | sed "s|^$root/$MIG_REL/||" | tr '\n' ' ')"
				continue
			fi
			target="$(candidates "$root" "$ver")"
		fi
		printf '%s\t%s\t%s\t%s\t%s\t%s\n' \
			"$rel" "$line" "$para" "$cite" "$target" "$text" >>"$TMP/resolved"
	done <"$TMP/citations"

	while IFS=$'\t' read -r rel line para cite target text; do
		[ -n "${rel:-}" ] || continue

		# Bounds, and the comma continuation expanded one element at a time.
		spec="${cite#*:}"
		printf '%s\n' "$(printf '%s' "$spec" | tr ',' '\n')" >"$TMP/parts"
		found=0
		while IFS= read -r part; do
			[ -n "$part" ] || continue
			case "$part" in
			*-*) start="${part%%-*}" end="${part#*-}" ;;
			*) start="$part" end="$part" ;;
			esac
			if [ "$start" -lt 1 ] || [ "$end" -lt "$start" ]; then
				printf '%s:%s cites %s, and %s-%s is not a range\n' \
					"$rel" "$line" "$cite" "$start" "$end"
				found=1
				continue
			fi
			if [ "$end" -gt "$(wc -l <"$target")" ]; then
				printf '%s:%s cites %s line %s, and %s has %s line(s)\n' \
					"$rel" "$line" "$cite" "$end" "$(basename "$target")" "$(wc -l <"$target")"
				found=1
			fi
		done <"$TMP/parts"
		[ "$found" -eq 0 ] || continue

		# The identifier rule: the citing line has to name, in backticks, something the migrations its
		# paragraph points at declare -- anywhere in them, not necessarily inside the cited range. A line
		# that names nothing is not judged by it, and neither is a name no migration declares; both are
		# stated above.
		paragraph_files "$rel" "$para" >"$TMP/scope"
		identifiers "$text" >"$TMP/idents"
		[ -s "$TMP/idents" ] || continue
		found=0
		judged=0
		while IFS= read -r ident; do
			[ -n "$ident" ] || continue
			grep -rqF -- "$ident" "$root/$MIG_REL" || continue
			judged=$((judged + 1))
			while IFS= read -r file; do
				[ -n "$file" ] || continue
				if grep -qF -- "$ident" "$file"; then
					found=1
					break
				fi
			done <"$TMP/scope"
			if [ "$found" -eq 1 ]; then
				break
			fi
		done <"$TMP/idents"
		if [ "$judged" -gt 0 ] && [ "$found" -eq 0 ]; then
			printf '%s:%s cites %s for %s, and no migration this paragraph cites declares any of those names\n' \
				"$rel" "$line" "$cite" "$(tr '\n' ' ' <"$TMP/idents")"
		fi
	done <"$TMP/resolved"

	return 0
}

# write_migration <root> <relpath> [<line>:<text> ...] -- a migration of 20 numbered filler lines, with
# the given line numbers replaced, so a fixture can say exactly what line 7 holds.
write_migration() {
	local root="$1" rel="$2" i
	shift 2
	mkdir -p "$(dirname "$root/$rel")"
	: >"$root/$rel"
	for i in $(seq 1 20); do
		printf -- '-- filler %s\n' "$i" >>"$root/$rel"
	done
	for i in "$@"; do
		case "$i" in
		*:*)
			sed -i "$(printf '%s' "$i" | cut -d: -f1)s|.*|$(printf '%s' "$i" | cut -d: -f2)|" "$root/$rel"
			;;
		esac
	done
}

new_fixture() {
	mkdir -p "$TMP/fixtures/$1/docs/adr" "$TMP/fixtures/$1/$MIG_REL"
	printf '%s' "$TMP/fixtures/$1"
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

# 1. The clean shape, in both address forms: the version alone and the whole filename.
FX="$(new_fixture clean)"
write_migration "$FX" "$MIG_REL/V1__baseline_schema.sql" \
	'7:    total_amount DECIMAL(12,2),' '12:CREATE TABLE sales_orders ('
printf '# ADR\n\n`total_amount` is at `V1:7` and `sales_orders` opens at `V1__baseline_schema.sql:12`.\n' \
	>"$FX/docs/adr/0002-money.md"
expect_pass "a citation whose range holds the identifier the line names" "$FX"

# 2. The rule is file-level, and this is the case that made it so: the name is in the cited file but
#    NOT in the cited lines. Measured on this tree at `docs/adr/0005-tenant-isolation-rls.md:369`, which
#    cites `V37:140` for `product_attribute_profile_roots` while `V37:140` holds `company_id`. Requiring
#    the name inside the range failed that citation and sixteen others.
FX="$(new_fixture name-elsewhere-in-the-file)"
write_migration "$FX" "$MIG_REL/V37__product_attribute_catalog.sql" \
	'3:CREATE TABLE product_attribute_profile_roots (' \
	'17:    company_id UUID REFERENCES companies(id),'
printf '# ADR\n\n`product_attribute_profile_roots` is keyed at `V37:17`.\n' \
	>"$FX/docs/adr/0005-tenant-isolation-rls.md"
expect_pass "a name declared elsewhere in the cited file is enough, even outside the cited range" "$FX"

# 3. The class the rule does catch: the name is declared, and not by any migration this paragraph
#    points at -- so the address does not reach the thing the sentence names.
FX="$(new_fixture wrong-file)"
write_migration "$FX" "$MIG_REL/V1__baseline_schema.sql" '7:    total_amount DECIMAL(12,2),'
write_migration "$FX" "$MIG_REL/V2__variant_stock.sql" '7:    product_variant_id UUID NOT NULL,'
printf '# ADR\n\n`product_variant_id` is at `V1:7`.\n' >"$FX/docs/adr/0002-money.md"
expect_finding "a citation whose name lives only in a migration the paragraph never cites fails" "$FX" \
	"docs/adr/0002-money.md:3 cites V1:7 for product_variant_id"

# 4. A name no migration declares is not judged, and this is the measured case that forced it:
#    `docs/adr/0003-document-identity-per-tenant.md:120` names `fk_sales_orders_store_same_company`, a
#    constraint ADR-0002 plans to add, and cites `V51:86-90` as the pattern it will follow. The tree
#    cannot place that name, so a finding there would be about the ADR's plan and not about a citation.
FX="$(new_fixture planned-name)"
write_migration "$FX" "$MIG_REL/V51__purchase_order_store_company_contract.sql" \
	'9:    ADD CONSTRAINT fk_purchase_orders_store_same_company'
printf '# ADR\n\nADR-0002 adds `fk_sales_orders_store_same_company` following `V51:9`.\n' \
	>"$FX/docs/adr/0003-document-identity-per-tenant.md"
expect_pass "a name no migration declares is not judged, because the tree cannot place it" "$FX"

# 5. A paragraph is one statement, not one physical line: markdown wraps a sentence, and a citation at
#    the start of a line can name a table the citation two lines above points at. Measured at
#    `docs/adr/0004-typed-state-machine.md:37`, which cites `V20:39` and names `goods_receipts`.
FX="$(new_fixture wrapped-paragraph)"
write_migration "$FX" "$MIG_REL/V12__goods_receipts.sql" \
	'5:CREATE TABLE goods_receipts (' '12:    status_id UUID NOT NULL,'
write_migration "$FX" "$MIG_REL/V20__employee_registry.sql" '9:    employment_status_id UUID NOT NULL,'
printf '# ADR\n\n`goods_receipts` is cited at `V12:12`, and its status column is\n(`V20:9`). `goods_receipts` is a numbered commerce document.\n' \
	>"$FX/docs/adr/0004-typed-state-machine.md"
expect_pass "the paragraph's other citations count, because markdown wraps one sentence across lines" "$FX"

# 6. A migration version is an address, not a name. Measured at `docs/adr/README.md:67`, which names
#    `V88` while citing `V58:26-32`: `V88` is a file, and the resolution rule is what validates it.
FX="$(new_fixture version-is-an-address)"
write_migration "$FX" "$MIG_REL/V58__variant_listing.sql" '10:CREATE TABLE variant_listing ('
write_migration "$FX" "$MIG_REL/V88__tax_rate.sql" '3:-- V88 adds the pointer V58 promised'
printf '# ADR\n\n`V88` added the pointer `V58:10` had promised.\n' >"$FX/docs/adr/README.md"
expect_pass "a migration version on the citing line is an address, not a name to look up" "$FX"

# 7. A range past the end of the file.
FX="$(new_fixture out-of-bounds)"
write_migration "$FX" "$MIG_REL/V1__baseline_schema.sql"
printf '# ADR\n\n`total_amount` is at `V1:477`.\n' >"$FX/docs/adr/0002-money.md"
expect_finding "a citation past the last line of the migration fails" "$FX" \
	"docs/adr/0002-money.md:3 cites V1:477 line 477"

# 8. A version that names more than one file: `V80` and `V80b` would both answer, so the address is not
#    an address. The fixture needs two files of one version, which Flyway itself would refuse.
FX="$(new_fixture ambiguous-version)"
write_migration "$FX" "$MIG_REL/V1__baseline_schema.sql" '7:    total_amount DECIMAL(12,2),'
write_migration "$FX" "$MIG_REL/V1__second.sql" '7:    total_amount DECIMAL(12,2),'
printf '# ADR\n\n`total_amount` is at `V1:7`.\n' >"$FX/docs/adr/0002-money.md"
expect_finding "a version that matches more than one migration fails instead of picking one" "$FX" \
	"which names 2 migrations by version alone"

# 9. A version no migration carries.
FX="$(new_fixture missing-version)"
write_migration "$FX" "$MIG_REL/V1__baseline_schema.sql" '7:    total_amount DECIMAL(12,2),'
printf '# ADR\n\n`total_amount` is at `V99:7`.\n' >"$FX/docs/adr/0002-money.md"
expect_finding "a version no migration carries fails" "$FX" \
	"cites V99:7, and no migration with version V99 exists"

# 10. The comma continuation: one prefix, several lines, each of which has to be inside the file.
FX="$(new_fixture comma-continuation)"
write_migration "$FX" "$MIG_REL/V12__goods_receipts.sql" \
	'12:CREATE TABLE goods_receipts (' '39:-- the table closes at line 39'
printf '# ADR\n\n`goods_receipts` (`V12:12,39`) and its items (`V12:19,40`) carry lines.\n' \
	>"$FX/docs/adr/0004-states.md"
expect_finding "every element of a comma continuation is bounds-checked" "$FX" \
	"cites V12:19,40 line 40"

# 11. A tree the check cannot read is a finding, not a pass.
FX="$TMP/fixtures/empty-tree"
mkdir -p "$FX"
expect_finding "a tree with no docs/ fails instead of passing vacuously" "$FX" \
	"no docs/ directory exists"

# 12. Out of scope on purpose: a record quoting a citation is evidence, not a citation to check.
FX="$(new_fixture record-is-out-of-scope)"
write_migration "$FX" "$MIG_REL/V1__baseline_schema.sql" '7:    total_amount DECIMAL(12,2),'
mkdir -p "$FX/odd/tasks"
printf '# record\n\nquoted `V1:900` as the defect.\n' >"$FX/odd/tasks/example.md"
printf '# ADR\n\n`total_amount` is at `V1:7`.\n' >"$FX/docs/adr/0002-money.md"
expect_pass "an ODD record under odd/ is outside the scanned scope" "$FX"

echo "== this tree =="

REAL_FINDINGS="$(violations "$ROOT")"
REAL_CITATIONS="$(citations "$ROOT" | grep -c . || true)"

if [ -z "$REAL_FINDINGS" ]; then
	pass "every citation from docs/ into a migration resolves and names something the migration declares"
else
	fail "this tree carries citations that do not hold what they claim: $REAL_FINDINGS"
fi

if [ "$REAL_CITATIONS" -gt 0 ]; then
	pass "the check found $REAL_CITATIONS citation(s) to judge, so it is not passing on an empty set"
else
	fail "no citation was found under docs/, so this suite proved nothing about this tree"
fi

printf '\nchecked %s citation(s) across docs/; %s of 7 ADRs carry at least one\n' \
	"$REAL_CITATIONS" "$(citations "$ROOT" | cut -f1 | sort -u | grep -c . || true)"
printf '%d passed, %d failed\n' "$PASS_COUNT" "$FAIL_COUNT"
[ "$FAIL_COUNT" -eq 0 ]
