#!/bin/bash
# ============================================
# LifeControl - the formatting gate lives in the job branch protection names
# ============================================
# Answers one question about `.github/workflows/angular-ci.yml`: does the `Lint & Build` job run a
# prettier check over the Angular app's source glob?
#
# Before this suite existed, formatting was enforced by exactly one thing: `life-control-app-angular/
# .husky/pre-commit`, which runs `npx lint-staged` (`eslint --fix` + `prettier --write` on staged
# files). That hook is reachable only while `core.hooksPath` points at `life-control-app-angular/
# .husky/_` — and the documented setup (`git config core.hooksPath .githooks`, in `AGENTS.md`,
# `CONTRIBUTING.md` and the `project-conventions` skill) points it at the anchor guard instead, while
# `life-control-app-angular/package.json`'s `prepare` sets it back on the next `npm install`. Which of
# the two won depended on the order two commands were run in, so the same tree was formatted or not
# depending on a local setting CI can never read. Nothing in `.github/` mentioned prettier at all
# (`grep -rn 'prettier' .github/workflows/ .github/scripts/*.sh` returned nothing), and
# `eslint.config.js` carries no prettier plugin, so `ng lint` could not catch a formatting difference
# either. The measured result was a tree reported unformatted in 20 files while every gate was green.
#
# The gate is therefore a step of `lint-build`, and this suite is what keeps it there. It asserts four
# things, each of which can be lost on its own:
#   * the step exists inside that job — not elsewhere in the file, because a step in `test` would not
#     gate the pull request the way the job name says it does;
#   * the step is a `--check`, never a `--write`, because a gate that rewrites the tree passes on the
#     drift it was supposed to report;
#   * the glob is the app's source glob (`src/**/*.{ts,html,scss}`), the same one `npx prettier
#     --write` rewrites, so a locally formatted tree is exactly what the gate accepts;
#   * the job keeps `name: Lint & Build` and its `working-directory: life-control-app-angular`, which
#     `CONTRIBUTING.md` §7 quotes as the branch-protection context and without which the relative glob
#     reads the repository root.
#
# The parse is scoped to the job block, skips comment lines and joins backslash continuations, and the
# fixtures below are what keep it honest: a missing step, a step in the wrong job, a `--write`, a
# narrower glob, a commented-out step and a renamed job must all be reported; a correct step — including
# one split across two lines with a trailing backslash — must be accepted. Without the accepting cases a
# parser that returned nothing for every input would look green.
#
# No network, no Node, no `gh`, no YAML library: both artifacts are small and regular, and the parser
# reads only the working tree. Point it at another checkout or a scratch copy by passing a root
# directory as the first argument (or with REPO_ROOT); that is how the failure probe runs it against a
# copy without touching this tree.
#
# Run: ./.github/scripts/tests/angular-formatting-gate.test.sh
# Exits 0 only when every case passes.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# The tree to audit. The first argument (or REPO_ROOT) lets the failure probe point the same suite at a
# scratch copy; the default is this checkout, derived from the script's own location so the answer does
# not depend on the caller's working directory.
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

WORKFLOW="$ROOT/.github/workflows/angular-ci.yml"
PACKAGE_JSON="$ROOT/life-control-app-angular/package.json"
# The job branch protection names. `CONTRIBUTING.md` §7 quotes this string as the check context, so a
# rename is a delivery blocker no workflow-level test would otherwise see.
JOB_ID="lint-build"
JOB_NAME="Lint & Build"
# The app's source glob: the exact string `package.json`'s lint-staged and the local
# `npx prettier --write "src/**/*.{ts,html,scss}"` use.
APP_GLOB='src/**/*.{ts,html,scss}'

# job_block <workflow file> <job id>
# Prints the lines of one job — from its `id:` line to the line before the next job id — with comment
# lines removed and backslash continuations joined, so a command split across two lines is read as the
# one logical line it is. Job ids sit at exactly two spaces and everything belonging to a job is indented
# deeper, which is what makes the block a block.
job_block() {
	awk -v job="$2" '
		BEGIN { in_jobs = 0; in_job = 0; buf = "" }
		/^jobs:[[:space:]]*$/ { in_jobs = 1; next }
		in_jobs && /^[^[:space:]]/ { in_jobs = 0; in_job = 0 }
		!in_jobs { next }
		/^  [^[:space:]]/ {
			if (buf != "") { print buf; buf = "" }
			line = $0
			sub(/^  /, "", line)
			sub(/:.*$/, "", line)
			in_job = (line == job)
			next
		}
		!in_job { next }
		/^[[:space:]]*#/ { next }
		{
			line = $0
			if (buf != "") { buf = buf " " line } else { buf = line }
			if (line ~ /\\[[:space:]]*$/) { sub(/\\[[:space:]]*$/, "", buf); next }
			print buf
			buf = ""
		}
		END { if (buf != "") print buf }
	' "$1"
}

# job_name <workflow file> <job id>
# Prints the job's `name:` value, or nothing when the job declares none (its id is then the effective
# name). Surrounding quotes are stripped, so `name: "Lint & Build"` and `name: Lint & Build` agree.
job_name() {
	job_block "$1" "$2" | awk '
		/^    name:[[:space:]]/ {
			line = $0
			sub(/^    name:[[:space:]]*/, "", line)
			sub(/[[:space:]]+#.*$/, "", line)
			sub(/[[:space:]]+$/, "", line)
			print line
			exit
		}
	' | sed -e "s/^['\"]//" -e "s/['\"]\$//"
}

# formatting_command <workflow file> <job id>
# Prints the job's logical command lines that invoke prettier with `--check` over the app's glob. A
# `--write` line, a narrower glob, a comment and a step in another job all print nothing, which is the
# point: only the gate's exact shape answers.
formatting_command() {
	job_block "$1" "$2" | grep -F 'prettier' | grep -F -- '--check' | grep -F "$APP_GLOB"
}

# has_gate <workflow file> -> yes | no
has_gate() {
	if formatting_command "$1" "$JOB_ID" | grep -q .; then
		printf 'yes\n'
	else
		printf 'no\n'
	fi
}

# gates_working_directory <workflow file> -> yes | no
# The `run:` glob is relative, so the job has to keep running in the app's directory. Without it the
# same command reads the repository root, where its glob matches nothing.
gates_working_directory() {
	if job_block "$1" "$JOB_ID" | grep -Eq '^[[:space:]]*working-directory:[[:space:]]*life-control-app-angular[[:space:]]*$'; then
		printf 'yes\n'
	else
		printf 'no\n'
	fi
}

# prettier_is_a_dev_dependency <package.json> -> yes | no
# The step runs `npx prettier` after `npm ci`, which only installs what `package.json` declares. A gate
# whose binary is not a declared devDependency is a gate that fails for the wrong reason, or resolves a
# different prettier than the developer's.
prettier_is_a_dev_dependency() {
	awk '
		/"devDependencies":[[:space:]]*\{/ { in_dev = 1; next }
		in_dev && /^[[:space:]]*\},?[[:space:]]*$/ { in_dev = 0 }
		in_dev && /"prettier"[[:space:]]*:/ { found = 1 }
		END { print (found ? "yes" : "no") }
	' "$1"
}

expect_gate() {
	local got
	got="$(has_gate "$2")"
	if [ "$got" = "$3" ]; then
		pass "$1"
	else
		fail "$1 (got gate: $got)"
	fi
}

expect_job_name() {
	local got
	got="$(job_name "$2" "$JOB_ID")"
	if [ "$got" = "$3" ]; then
		pass "$1"
	else
		fail "$1 (got name: '$got')"
	fi
}

# expect_job_name_differs <label> <workflow file>
# The mirror of expect_job_name for the fixtures: it passes when the read name is NOT the
# branch-protection context, which is what proves the assertion above can fail at all. A fixture that
# merely repeated the accepted case would prove nothing.
expect_job_name_differs() {
	local got
	got="$(job_name "$2" "$JOB_ID")"
	if [ "$got" != "$JOB_NAME" ]; then
		pass "$1"
	else
		fail "$1 (the read name was still '$JOB_NAME', so a rename would not be reported)"
	fi
}

echo "== the gate is declared in the job branch protection names =="
if [ -r "$WORKFLOW" ]; then
	pass ".github/workflows/angular-ci.yml exists"
else
	fail ".github/workflows/angular-ci.yml exists ($WORKFLOW)"
fi
expect_job_name "the job keeps the name 'Lint & Build'" "$WORKFLOW" "$JOB_NAME"
expect_gate "the job runs a prettier --check over the app's source glob" "$WORKFLOW" yes
if [ "$(gates_working_directory "$WORKFLOW")" = "yes" ]; then
	pass "the job still runs in life-control-app-angular, so its relative glob reads the app"
else
	fail "the job no longer declares 'working-directory: life-control-app-angular', so 'src/**' reads the repository root"
fi

echo "== the gate cannot fail for a missing binary =="
if [ ! -r "$PACKAGE_JSON" ]; then
	fail "life-control-app-angular/package.json exists ($PACKAGE_JSON)"
elif [ "$(prettier_is_a_dev_dependency "$PACKAGE_JSON")" = "yes" ]; then
	pass "prettier is a declared devDependency, so 'npm ci' installs the binary the gate runs"
else
	fail "prettier is not in devDependencies, so 'npx prettier' in CI resolves nothing (or another version)"
fi

echo "== the parser is proven by fixtures =="
# A correct workflow. If this one were reported missing, every failure case below would be a pass
# nobody earned.
cat >"$TMP/gate.yml" <<YAML
jobs:
  lint-build:
    name: Lint & Build
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: life-control-app-angular
    steps:
      - name: Install dependencies
        run: npm ci

      - name: Check formatting
        run: npx prettier --check "$APP_GLOB"

      - name: Lint
        run: npm run lint
YAML
expect_gate "a correct prettier step is accepted" "$TMP/gate.yml" yes
expect_job_name "a correct job name is accepted" "$TMP/gate.yml" "$JOB_NAME"

# The same command split with a trailing backslash. A rewrite that wraps the line must stay accepted;
# a parser that only matched single-line commands would refuse it and send the author hunting a step
# that is present.
cat >"$TMP/continuation.yml" <<YAML
jobs:
  lint-build:
    name: Lint & Build
    steps:
      - name: Check formatting
        run: npx prettier --check \\
          "$APP_GLOB"
YAML
expect_gate "a command split with a trailing backslash is still read" "$TMP/continuation.yml" yes

# The step removed entirely: the exact deletion this suite exists to catch.
cat >"$TMP/no-step.yml" <<YAML
jobs:
  lint-build:
    name: Lint & Build
    steps:
      - name: Lint
        run: npm run lint

      - name: Build
        run: npm run build
YAML
expect_gate "a job with no prettier step is reported" "$TMP/no-step.yml" no

# The step moved to another job. A step in `test` reports a separate check context and never fails
# `Lint & Build`, so it must not answer for it.
cat >"$TMP/other-job.yml" <<YAML
jobs:
  lint-build:
    name: Lint & Build
    steps:
      - name: Lint
        run: npm run lint
  test:
    name: Unit tests
    steps:
      - name: Check formatting
        run: npx prettier --check "$APP_GLOB"
YAML
expect_gate "a prettier step in another job does not answer for lint-build" "$TMP/other-job.yml" no

# `--write` instead of `--check`: a gate that rewrites the tree passes on the drift it exists to report.
cat >"$TMP/write.yml" <<YAML
jobs:
  lint-build:
    name: Lint & Build
    steps:
      - name: Check formatting
        run: npx prettier --write "$APP_GLOB"
YAML
expect_gate "a prettier --write step is reported" "$TMP/write.yml" no

# A narrower glob: `*.{ts,html,scss}` is the sweep's set, and a step over `.ts` alone leaves the
# templates and stylesheets unguarded.
cat >"$TMP/narrow-glob.yml" <<YAML
jobs:
  lint-build:
    name: Lint & Build
    steps:
      - name: Check formatting
        run: npx prettier --check "src/**/*.ts"
YAML
expect_gate "a narrower glob is reported" "$TMP/narrow-glob.yml" no

# A commented-out step. The declaration now carries its own explanatory comment that names the command,
# so a search that reads comment lines reports a gate that does not exist — measured here rather than
# asserted in prose.
cat >"$TMP/comment-only.yml" <<YAML
jobs:
  lint-build:
    name: Lint & Build
    steps:
      # - name: Check formatting
      #   run: npx prettier --check "$APP_GLOB"
      - name: Lint
        run: npm run lint
YAML
expect_gate "a commented-out prettier step is reported" "$TMP/comment-only.yml" no

# The job renamed. The branch-protection runbook in CONTRIBUTING.md §7 names `Lint & Build`, so the
# gate could be present and the pull request still never gate.
cat >"$TMP/renamed.yml" <<YAML
jobs:
  lint-build:
    name: Lint and Build
    steps:
      - name: Check formatting
        run: npx prettier --check "$APP_GLOB"
YAML
expect_job_name_differs "a renamed job is reported" "$TMP/renamed.yml"

printf '\n%d passed, %d failed\n' "$PASS_COUNT" "$FAIL_COUNT"
[ "$FAIL_COUNT" -eq 0 ]
