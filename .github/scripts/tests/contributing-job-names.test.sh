#!/bin/bash
# ============================================
# LifeControl - CONTRIBUTING §6 job names match the workflows
# ============================================
# Answers one question about the workflows in `.github/workflows/`: does each one declare exactly
# the job `name:` values that `CONTRIBUTING.md` §6 quotes for it?
#
# §6 is a contract, not prose: the branch-protection runbook in §7 names required checks by exactly
# those strings, and §6 says so ("quoted verbatim because the branch-protection runbook below names
# required checks by exactly those strings"). Nothing read it. Measured: the api-ci split (pull
# request #435) turned one job called `Check` into `Static analysis` and `Test`, and §7 kept
# asserting that `api-ci.yml` and `gateway-ci.yml` "share the job name `Check`" — a false statement
# no check could see, because every component workflow filters by `paths:` and therefore never runs
# on a markdown-only change. The `paths:` half of exactly this problem already has a reader
# (`workflow-trigger-parity.test.sh`); this is the documentation half. The comparison is symmetric
# on purpose, so a stale name in §6 fails exactly like a renamed job in a workflow.
#
# The parse is scoped to §6's table alone: §7 quotes the same strings again and an unrelated table
# elsewhere in the file must never be read as the contract. A cell may name one job or several, and
# the split is on backticked segments — never on commas, because `Lint, Build & Test` is ONE job
# whose name carries commas while `` `Static analysis`, `Test` `` is two, and splitting on commas
# would make the guard pass on the first while reading it as two names. The effective name of a job
# is its `name:` value, or the job id when the job declares none. The fixtures below are what keep
# that parser honest: a comma inside one backticked name, a two-name cell, an unnamed job whose id
# is the name, and the four failure modes each of which would otherwise be a green run.
#
# Comparison is set-based: §6 may name a workflow's jobs in any order, but it must name each of them
# exactly once. A repeated name is a count mismatch and fails; a name present on only one side fails
# in that direction (`workflow -> §6` and `§6 -> workflow` are both reported, so a rename produces
# both).
#
# No network, no `gh`, no token, no YAML library, no git: both artifacts are small and regular, and
# the parser reads only the working tree. Point it at another checkout or a scratch copy by passing
# a root directory as the first argument (or with REPO_ROOT); that is how the failure probe runs it
# against a copy without touching this tree.
#
# Run: ./.github/scripts/tests/contributing-job-names.test.sh
# Exits 0 only when every case passes.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# The tree to audit. The first argument (or REPO_ROOT) lets the failure probe point the same suite
# at a scratch copy; the default is this checkout, derived from the script's own location so the
# answer does not depend on the caller's working directory.
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

# declared_job_names <workflow file>
# Prints, one per line and in file order, the effective name of each job declared in the workflow's
# top-level `jobs:` block: the job's `name:` value when it declares one, else the job id. Quotes
# around a `name:` value and a trailing comment are stripped. Only the `jobs:` block is read, job
# ids sit at exactly two spaces and a job `name:` at exactly four, so a step's `name:` (which is a
# list item at six spaces or a `with:` key deeper still) is never read as a job name.
declared_job_names() {
	awk '
		BEGIN { in_jobs = 0; job = "" }
		/^jobs:[[:space:]]*$/ { in_jobs = 1; next }
		in_jobs && /^[^[:space:]]/ { in_jobs = 0 }
		!in_jobs { next }
		/^  [^[:space:]]/ {
			if (job != "") print job
			line = $0
			sub(/^  /, "", line)
			sub(/:.*$/, "", line)
			job = line
			next
		}
		/^    name:[[:space:]]/ {
			line = $0
			sub(/^    name:[[:space:]]*/, "", line)
			sub(/[[:space:]]+#.*$/, "", line)
			sub(/[[:space:]]+$/, "", line)
			sub(/^"/, "", line)
			sub(/"$/, "", line)
			sub(/^'\''/, "", line)
			sub(/'\''$/, "", line)
			job = line
		}
		END { if (job != "") print job }
	' "$1"
}

# section6_rows <doc>
# Prints the §6 table rows only (each starting with `|`), from the `## 6.` heading to the next
# Markdown heading. This is the scope trap: a table anywhere else in the file is not the contract.
section6_rows() {
	awk '
		BEGIN { in_section = 0 }
		/^## 6\./ { in_section = 1; next }
		in_section && /^## / { in_section = 0 }
		!in_section { next }
		/^\|/ { print }
	' "$1"
}

# documented_row_paths <doc>
# Prints, one per line and in file order, the workflow path named in the first column of each §6
# row. Reading the first column specifically (never the first backticked segment of the whole row)
# keeps the header row, whose third column is the literal `Job \`name:\``, out of the path list.
documented_row_paths() {
	section6_rows "$1" | awk '
		{
			n = split($0, cols, "|")
			if (n < 4) next
			if (match(cols[2], /`[^`]+`/))
				print substr(cols[2], RSTART + 1, RLENGTH - 2)
		}
	'
}

# documented_job_names <doc> <workflow path>
# Prints, one per line and in row order, the job names §6 gives for one workflow. The cell is split
# into backticked segments; commas between segments, and commas inside one segment, are never
# separators.
documented_job_names() {
	section6_rows "$1" | awk -v want="$2" '
		{
			n = split($0, cols, "|")
			if (n < 4) next
			if (!match(cols[2], /`[^`]+`/)) next
			path = substr(cols[2], RSTART + 1, RLENGTH - 2)
			if (path != want) next
			s = cols[4]
			while (match(s, /`[^`]+`/)) {
				print substr(s, RSTART + 1, RLENGTH - 2)
				s = substr(s, RSTART + RLENGTH)
			}
		}
	'
}

# audit <root>
# Compares §6 of <root>/CONTRIBUTING.md against every workflow in <root>/.github/workflows. Prints
# one `PROBLEM:` line per disagreement, then a final `CHECKED <workflows> <jobs>` line. Exits 0 only
# when both directions agree for every workflow and the row/file sets match too.
audit() {
	local root="$1"
	local doc="$root/CONTRIBUTING.md"
	local workflows_dir="$root/.github/workflows"
	local file rel declared_list documented_list declared_set documented_set name problems=0 workflows=0 jobs=0

	if [ ! -r "$doc" ]; then
		printf 'PROBLEM: no readable CONTRIBUTING.md at %s\n' "$doc"
		printf 'CHECKED 0 0\n'
		return 1
	fi
	if [ ! -d "$workflows_dir" ]; then
		printf 'PROBLEM: no workflow directory at %s\n' "$workflows_dir"
		printf 'CHECKED 0 0\n'
		return 1
	fi

	# §6 may not name a file that does not exist.
	while IFS= read -r rel; do
		[ -n "$rel" ] || continue
		if [ ! -r "$root/$rel" ]; then
			printf 'PROBLEM: §6 names %s, which is not a workflow file\n' "$rel"
			problems=$((problems + 1))
		fi
	done < <(documented_row_paths "$doc")

	# Every workflow file needs a §6 row, and that row's names must match the workflow's jobs.
	for file in "$workflows_dir"/*.yml "$workflows_dir"/*.yaml; do
		[ -f "$file" ] || continue
		rel=".github/workflows/$(basename "$file")"
		workflows=$((workflows + 1))
		if ! documented_row_paths "$doc" | grep -Fxq "$rel"; then
			printf 'PROBLEM: §6 has no row for %s\n' "$rel"
			problems=$((problems + 1))
			continue
		fi
		declared_list="$(declared_job_names "$file")"
		documented_list="$(documented_job_names "$doc" "$rel")"
		jobs=$((jobs + $(printf '%s\n' "$declared_list" | grep -c .)))
		declared_set="$(printf '%s\n' "$declared_list" | sort -u)"
		documented_set="$(printf '%s\n' "$documented_list" | sort -u)"

		# Direction workflow -> §6: a job the workflow declares and §6 never names.
		while IFS= read -r name; do
			[ -n "$name" ] || continue
			printf 'PROBLEM: %s declares job name %s that §6 does not name\n' "$rel" "'$name'"
			problems=$((problems + 1))
		done < <(comm -23 <(printf '%s\n' "$declared_set") <(printf '%s\n' "$documented_set"))

		# Direction §6 -> workflow: a name §6 keeps that no job declares (a stale row).
		while IFS= read -r name; do
			[ -n "$name" ] || continue
			printf 'PROBLEM: %s does not declare job name %s that §6 names\n' "$rel" "'$name'"
			problems=$((problems + 1))
		done < <(comm -13 <(printf '%s\n' "$declared_set") <(printf '%s\n' "$documented_set"))

		# Count, not just set: §6 naming one job twice is a mismatch the set comparison would hide.
		while IFS= read -r name; do
			[ -n "$name" ] || continue
			printf 'PROBLEM: %s §6 names %s more than once\n' "$rel" "'$name'"
			problems=$((problems + 1))
		done < <(printf '%s\n' "$documented_list" | sort | uniq -d)
	done

	printf 'CHECKED %d %d\n' "$workflows" "$jobs"
	[ "$problems" -eq 0 ]
}

# new_fixture <name> -> prints the root of an empty fixture tree (with a workflow directory)
new_fixture() {
	local dir="$TMP/fixtures/$1"
	mkdir -p "$dir/.github/workflows"
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
echo "== §6 names exactly the jobs the workflows declare =="
if real_out="$(audit "$ROOT")"; then
	real_code=0
else
	real_code=$?
fi
REAL_WORKFLOWS="$(printf '%s\n' "$real_out" | awk '/^CHECKED / { print $2 }')"
REAL_JOBS="$(printf '%s\n' "$real_out" | awk '/^CHECKED / { print $3 }')"
REAL_WORKFLOWS="${REAL_WORKFLOWS:-0}"
REAL_JOBS="${REAL_JOBS:-0}"

if [ "$real_code" -eq 0 ]; then
	pass "every workflow job name and §6 agree in both directions ($REAL_WORKFLOWS workflows, $REAL_JOBS jobs)"
else
	fail "a workflow job name and §6 disagree"
fi
printf '%s\n' "$real_out" | grep '^PROBLEM' | sed 's/^/  /'

# The audit must have read the whole tree: a parser that found no workflow, or no job, would agree
# with everything vacuously. These two assertions make that impossible to mistake for a pass.
EXPECTED_WORKFLOWS="$(find "$ROOT/.github/workflows" -maxdepth 1 -type f \( -name '*.yml' -o -name '*.yaml' \) 2>/dev/null | wc -l | tr -d ' ')"
if [ "$REAL_WORKFLOWS" -eq "$EXPECTED_WORKFLOWS" ] && [ "$REAL_WORKFLOWS" -gt 0 ]; then
	pass "the audit read every workflow file ($EXPECTED_WORKFLOWS)"
else
	fail "the audit read $REAL_WORKFLOWS workflow(s), the tree has $EXPECTED_WORKFLOWS"
fi
if [ "$REAL_JOBS" -gt 0 ]; then
	pass "the audit read at least one job name ($REAL_JOBS)"
else
	fail "the audit read no job name, so agreement would be vacuous"
fi

# ------------------------------------------------------------------------- the parser's fixtures
echo "== the parser is proven by fixtures =="

# The rule's own case: a cell naming one job whose `name:` value contains a comma. If the cell were
# split on commas this would read as two names and fail, so a pass here is the proof it is not.
FX="$(new_fixture comma-in-name)"
cat >"$FX/CONTRIBUTING.md" <<'MD'
## 6. Gates CI runs

| Workflow | Runs on | Job `name:` |
| --- | --- | --- |
| `.github/workflows/one.yml` | every change | `Lint, Build & Test` |
MD
cat >"$FX/.github/workflows/one.yml" <<'YAML'
name: One
on:
  pull_request:
jobs:
  ci:
    name: Lint, Build & Test
    runs-on: ubuntu-latest
YAML
expect_audit "a comma inside one backticked name is one job" "$FX" pass

# The same trap from the other side: a cell naming two jobs, each its own backticked segment.
FX="$(new_fixture two-names-one-cell)"
cat >"$FX/CONTRIBUTING.md" <<'MD'
## 6. Gates CI runs

| Workflow | Runs on | Job `name:` |
| --- | --- | --- |
| `.github/workflows/one.yml` | every change | `Static analysis`, `Test` |
MD
cat >"$FX/.github/workflows/one.yml" <<'YAML'
name: One
on:
  pull_request:
jobs:
  static-analysis:
    name: Static analysis
    runs-on: ubuntu-latest
  test:
    name: Test
    runs-on: ubuntu-latest
YAML
expect_audit "two backticked names in one cell are two jobs" "$FX" pass

# A job with no `name:` is known by its job id, which is what §6 must name.
FX="$(new_fixture unnamed-job)"
cat >"$FX/CONTRIBUTING.md" <<'MD'
## 6. Gates CI runs

| Workflow | Runs on | Job `name:` |
| --- | --- | --- |
| `.github/workflows/one.yml` | every change | `ci` |
MD
cat >"$FX/.github/workflows/one.yml" <<'YAML'
name: One
on:
  pull_request:
jobs:
  ci:
    runs-on: ubuntu-latest
YAML
expect_audit "a job with no name is read as its job id" "$FX" pass

# Order is not part of the contract: §6 may list the jobs in any order.
FX="$(new_fixture reordered)"
cat >"$FX/CONTRIBUTING.md" <<'MD'
## 6. Gates CI runs

| Workflow | Runs on | Job `name:` |
| --- | --- | --- |
| `.github/workflows/one.yml` | every change | `Beta`, `Alpha` |
MD
cat >"$FX/.github/workflows/one.yml" <<'YAML'
name: One
on:
  pull_request:
jobs:
  alpha:
    name: Alpha
    runs-on: ubuntu-latest
  beta:
    name: Beta
    runs-on: ubuntu-latest
YAML
expect_audit "§6 names the jobs in any order" "$FX" pass

# Other tables are not the contract: an unrelated table before and after §6 must be ignored.
FX="$(new_fixture out-of-scope-tables)"
cat >"$FX/CONTRIBUTING.md" <<'MD'
## 5. Leave an evidence record

| Step | Job `name:` |
| --- | --- |
| one | `Wrong from section five` |

## 6. Gates CI runs

| Workflow | Runs on | Job `name:` |
| --- | --- | --- |
| `.github/workflows/one.yml` | every change | `Alpha` |

## 7. Branch protection

| Setting | Value | Job `name:` |
| --- | --- | --- |
| required checks | on | `Wrong from section seven` |
MD
cat >"$FX/.github/workflows/one.yml" <<'YAML'
name: One
on:
  pull_request:
jobs:
  alpha:
    name: Alpha
    runs-on: ubuntu-latest
YAML
expect_audit "tables outside §6 are out of scope" "$FX" pass

echo "== each failure mode fails in the direction it names =="

# 1. A job renamed in the workflow while §6 is untouched: fails in BOTH directions, because the
#    workflow now declares a name §6 does not and §6 still names a job the workflow does not.
FX="$(new_fixture renamed-job)"
cat >"$FX/CONTRIBUTING.md" <<'MD'
## 6. Gates CI runs

| Workflow | Runs on | Job `name:` |
| --- | --- | --- |
| `.github/workflows/one.yml` | every change | `Alpha` |
MD
cat >"$FX/.github/workflows/one.yml" <<'YAML'
name: One
on:
  pull_request:
jobs:
  alpha:
    name: Alpha renamed
    runs-on: ubuntu-latest
YAML
expect_audit "a renamed job fails" "$FX" fail
expect_audit_mentions "the rename is reported workflow -> §6" "$FX" "declares job name 'Alpha renamed' that §6 does not name"
expect_audit_mentions "the rename is reported §6 -> workflow" "$FX" "does not declare job name 'Alpha' that §6 names"

# 2. A name present in §6 that no workflow declares.
FX="$(new_fixture stale-name)"
cat >"$FX/CONTRIBUTING.md" <<'MD'
## 6. Gates CI runs

| Workflow | Runs on | Job `name:` |
| --- | --- | --- |
| `.github/workflows/one.yml` | every change | `Alpha`, `Ghost` |
MD
cat >"$FX/.github/workflows/one.yml" <<'YAML'
name: One
on:
  pull_request:
jobs:
  alpha:
    name: Alpha
    runs-on: ubuntu-latest
YAML
expect_audit "a name only §6 carries fails" "$FX" fail
expect_audit_mentions "the stale name is reported" "$FX" "does not declare job name 'Ghost' that §6 names"

# 2b. The mirror image: a name the workflow declares that §6 never names.
FX="$(new_fixture undocumented-name)"
cat >"$FX/CONTRIBUTING.md" <<'MD'
## 6. Gates CI runs

| Workflow | Runs on | Job `name:` |
| --- | --- | --- |
| `.github/workflows/one.yml` | every change | `Alpha` |
MD
cat >"$FX/.github/workflows/one.yml" <<'YAML'
name: One
on:
  pull_request:
jobs:
  alpha:
    name: Alpha
    runs-on: ubuntu-latest
  surprise:
    name: Surprise
    runs-on: ubuntu-latest
YAML
expect_audit "an undocumented job fails" "$FX" fail
expect_audit_mentions "the undocumented name is reported" "$FX" "declares job name 'Surprise' that §6 does not name"

# 2c. A count mismatch: §6 names one job twice, so the set agrees while the count does not.
FX="$(new_fixture duplicated-name)"
cat >"$FX/CONTRIBUTING.md" <<'MD'
## 6. Gates CI runs

| Workflow | Runs on | Job `name:` |
| --- | --- | --- |
| `.github/workflows/one.yml` | every change | `Alpha`, `Alpha` |
MD
cat >"$FX/.github/workflows/one.yml" <<'YAML'
name: One
on:
  pull_request:
jobs:
  alpha:
    name: Alpha
    runs-on: ubuntu-latest
YAML
expect_audit "a name repeated in §6 fails" "$FX" fail
expect_audit_mentions "the duplicate is reported" "$FX" "§6 names 'Alpha' more than once"

# 3a. A workflow file with no §6 entry, the table left as it was.
FX="$(new_fixture added-workflow)"
cat >"$FX/CONTRIBUTING.md" <<'MD'
## 6. Gates CI runs

| Workflow | Runs on | Job `name:` |
| --- | --- | --- |
| `.github/workflows/one.yml` | every change | `Alpha` |
MD
cat >"$FX/.github/workflows/one.yml" <<'YAML'
name: One
on:
  pull_request:
jobs:
  alpha:
    name: Alpha
    runs-on: ubuntu-latest
YAML
cat >"$FX/.github/workflows/two.yml" <<'YAML'
name: Two
on:
  pull_request:
jobs:
  beta:
    name: Beta
    runs-on: ubuntu-latest
YAML
expect_audit "a workflow with no §6 row fails" "$FX" fail
expect_audit_mentions "the missing row is reported" "$FX" "§6 has no row for .github/workflows/two.yml"

# 3b. A §6 entry whose workflow file is gone, the table left as it was.
FX="$(new_fixture removed-workflow)"
cat >"$FX/CONTRIBUTING.md" <<'MD'
## 6. Gates CI runs

| Workflow | Runs on | Job `name:` |
| --- | --- | --- |
| `.github/workflows/one.yml` | every change | `Alpha` |
| `.github/workflows/ghost.yml` | every change | `Beta` |
MD
cat >"$FX/.github/workflows/one.yml" <<'YAML'
name: One
on:
  pull_request:
jobs:
  alpha:
    name: Alpha
    runs-on: ubuntu-latest
YAML
expect_audit "a §6 row with no workflow file fails" "$FX" fail
expect_audit_mentions "the dangling row is reported" "$FX" "§6 names .github/workflows/ghost.yml, which is not a workflow file"

printf '\nchecked %s workflow(s) and %s job(s); every pair compared in both directions (workflow -> §6, §6 -> workflow)\n' \
	"$REAL_WORKFLOWS" "$REAL_JOBS"
printf '%d fixture(s) asserted\n' "$(find "$TMP/fixtures" -mindepth 1 -maxdepth 1 -type d | wc -l | tr -d ' ')"
printf '%d passed, %d failed\n' "$PASS_COUNT" "$FAIL_COUNT"
[ "$FAIL_COUNT" -eq 0 ]
