#!/bin/bash
# ============================================
# LifeControl - the root TypeScript program is compiled by CI
# ============================================
# Answers one question about `angular-ci.yml`: does the workflow run `npx tsc --noEmit`, and does it
# run it in a job that installed dependencies first?
#
# Before this suite existed, `angular-ci.yml` ran formatting, lint and `ng build`, and nothing
# compiled the root `tsconfig.json` program. `tsconfig.app.json` includes `src/**/*.ts` and excludes
# `*.spec.ts`, and neither `e2e/**` nor `playwright.config.ts` is in the application program at all,
# so the program that holds the specs and the e2e mocks — 575 files — had no reader. Forty-six
# `TS2591` errors lived in it until #895 fixed them, and no job would have reported their return:
# issue #969 is the measured cost of a rule with no check.
#
# The second half is not decoration. `npx tsc` resolves the binary from `node_modules`, so a step
# that runs it in a job without an install step fails with `command not found` instead of a type
# error, and the log reads like an infrastructure hiccup rather than a missing check. Both halves are
# asserted, and the fixtures below are what keep the parser honest —
#   * a workflow with no such step must be reported;
#   * a workflow with the step but no install in the same job must be reported;
#   * a workflow with both must be accepted.
# Without those cases a parser that found nothing, or accepted anything, would look green.
#
# The suite parses the job blocks with awk rather than a YAML library: the shape is small and
# regular, every suite here reads its artifact with grep and awk, and the fixtures are what keep that
# exact. No network, no `gh`, no Docker, no YAML library.
#
# Run: ./.github/scripts/tests/workflow-typecheck.test.sh
# Exits 0 only when every case passes.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKFLOWS="$SCRIPT_DIR/../../workflows"
TMP="$(mktemp -d)" || {
	printf 'FAIL: cannot create a scratch directory (TMPDIR=%s)\n' "${TMPDIR:-unset}" >&2
	exit 1
}
trap 'rm -rf "$TMP"' EXIT

failures=0
pass() { printf 'PASS: %s\n' "$1"; }
fail() {
	printf 'FAIL: %s\n' "$1" >&2
	failures=$((failures + 1))
}

# Prints one line per job that runs `npx tsc --noEmit`: "<job> <installs|no-install>".
#
# A job header is a line indented exactly two spaces whose only content is `name:`; everything
# deeper belongs to the job until the next header. Tracking that is what makes the install question
# answerable per job instead of per file, which is the whole point of the second assertion.
typecheck_jobs() {
	awk '
		/^  [A-Za-z0-9_-]+:[[:space:]]*$/ { job = $1; sub(":$", "", job) }
		job == "" { next }
		/npm ci/ { install[job] = 1 }
		/npx tsc --noEmit/ { tsc[job] = 1 }
		END {
			for (name in tsc) {
				printf "%s %s\n", name, (install[name] ? "installs" : "no-install")
			}
		}
	' "$1"
}

# Prints the verdict for one workflow file: "ok", or the reason it is not.
verdict() {
	local file="$1" jobs job state found=0
	jobs="$(typecheck_jobs "$file")"

	while read -r job state; do
		[ -n "$job" ] || continue
		found=1
		if [ "$state" = "no-install" ]; then
			printf 'job %s runs npx tsc --noEmit but installs no dependencies\n' "$job"
			return 0
		fi
	done <<<"$jobs"

	[ "$found" -eq 1 ] || printf 'no job runs npx tsc --noEmit\n'
}

expect_ok() {
	local label="$1" file="$2" reason
	reason="$(verdict "$file")"
	if [ -z "$reason" ]; then
		pass "$label"
	else
		fail "$label — $reason"
	fi
}

expect_reported() {
	local label="$1" file="$2" expected="$3" reason
	reason="$(verdict "$file")"
	case "$reason" in
	*"$expected"*) pass "$label" ;;
	*) fail "$label — expected a report containing '$expected', got '${reason:-nothing}'" ;;
	esac
}

# --- The real workflow -------------------------------------------------------------------------
if [ ! -f "$WORKFLOWS/angular-ci.yml" ]; then
	fail 'angular-ci.yml exists'
else
	expect_ok 'angular-ci.yml runs npx tsc --noEmit in a job that installed dependencies' \
		"$WORKFLOWS/angular-ci.yml"
fi

# --- Fixtures ----------------------------------------------------------------------------------
cat >"$TMP/good.yml" <<'YAML'
jobs:
  lint-build:
    name: Lint & Build
    steps:
      - name: Install dependencies
        run: npm ci
      - name: Type check
        run: npx tsc --noEmit
YAML

cat >"$TMP/missing.yml" <<'YAML'
jobs:
  lint-build:
    name: Lint & Build
    steps:
      - name: Install dependencies
        run: npm ci
      - name: Build
        run: npm run build
YAML

cat >"$TMP/no-install.yml" <<'YAML'
jobs:
  lint-build:
    name: Lint & Build
    steps:
      - name: Type check
        run: npx tsc --noEmit
YAML

cat >"$TMP/install-elsewhere.yml" <<'YAML'
jobs:
  lint-build:
    name: Lint & Build
    steps:
      - name: Install dependencies
        run: npm ci
  typecheck:
    name: Type check
    steps:
      - name: Type check
        run: npx tsc --noEmit
YAML

expect_ok 'a job that installs and type-checks is accepted' "$TMP/good.yml"
expect_reported 'a workflow with no type-check step is reported' "$TMP/missing.yml" \
	'no job runs npx tsc --noEmit'
expect_reported 'a type-check step in a job that never installs is reported' "$TMP/no-install.yml" \
	'installs no dependencies'
expect_reported 'an install in a different job does not count' "$TMP/install-elsewhere.yml" \
	'installs no dependencies'

printf '\n'
if [ "$failures" -ne 0 ]; then
	printf 'FAILED: %s case(s)\n' "$failures" >&2
	exit 1
fi
printf 'All workflow-typecheck cases passed.\n'
