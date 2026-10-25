# ODD feature: env-image-pin-drift

**Repository**: LifeControl — `docker/` only: the shared report helper, `validate-env.sh`, `deploy.sh`, one
new offline suite under `docker/scripts/tests/`, and the `AGENTS.md` section that documents them. No
compose file, no image version, no application code.
**Status**: implemented — `validate-env.sh` reports every `*_IMAGE` whose value diverges between
`docker/.env.<env>` and `docker/.env.template` (naming the key and both values), `deploy.sh <env> start`
prints the same report before building, a new suite pins both behaviours, and the two stale local copies
on the machine that runs this stack are refreshed to the template's pins. Nothing is deferred.
**Created**: 2026-10-25 · **Base**: `main` @ `43c370c3` · **Branch**: `fix/env-image-pin-drift` ·
**Worktree**: `~/workspace/LifeControl-worktrees/fix-env-image-pin-drift`
**Risk**: **items 5 and 3**. Item 5 is the honest one: `validate-env.sh` is the **production start gate**
(`deploy.sh` refuses to start prod when it exits non-zero) and `deploy.sh start` is the deploy path itself,
so a mistake here can stop a production deploy or hide a stale image behind a green start. Item 3 is the
script contract — the argument shape and exit codes `deploy.sh` and the CI workflows consume. Both are held
by tests that run in CI (item 6 never fires), and the new failure path is reachable only when a caller
passes the flag this unit introduces, so no existing invocation changes behaviour.

## Review size

The unit is **630 authored changed lines in one commit**, past the advisory ~400-line budget, and it ships
as **one slice with the exception declared** rather than a chain. The reason is that every candidate split
cuts through the cohesive unit: the report without a caller is dead code (the `_common.sh` commit alone calls
nothing), and `deploy.sh`'s four lines are the behaviour the issue is *about*. The two suites are what makes
this reviewable at all — 38 and 20 assertions, each one a line — so the code half is roughly 170 lines across
three scripts. Splitting would have produced a first pull request that changes nothing observable, which is
the shape the chain rule exists to prevent, not to cause.

## Specs

**S1 — the request, verbatim**: `si dale a ese` — the issue left open in the previous message to the user:
*"Queda pendiente en el repo únicamente #597 (avisar cuando un `.env.<env>` materializado pinnea imágenes
más viejas que el template). ¿Querés que lo tome?"*.

**S2 — the problem, verbatim from issue #597**:

> `docker/.env.<env>` files are gitignored, materialised once by `setup-env.sh`, and never rewritten
> afterwards ("`setup-env.sh` sólo lo crea si falta"). The pinned image versions live in
> `docker/.env.template` and in the compose defaults, so an env file that was materialised months ago keeps
> pinning whatever the template said then — silently, because every key it does not declare falls back to
> the compose default with no signal.

Measured in the issue on 2026-10-25, and re-measured on this worktree before any edit — identical:

| Key | `.env.template` | `.env.staging` / `.env.prod` |
| --- | --- | --- |
| `KEYCLOAK_IMAGE` | `quay.io/keycloak/keycloak:26.8.1` | `quay.io/keycloak/keycloak:26.2.1` |
| `LOKI_IMAGE` | `grafana/loki:3.7.8` | `grafana/loki:2.9.0` |
| `PROMETHEUS_IMAGE` | `prom/prometheus:v3.15.0` | `prom/prometheus:v2.48.0` |
| `TEMPO_IMAGE` | `grafana/tempo:3.1.0` | `grafana/tempo:2.3.0` |
| `POSTGRES_IMAGE` | `postgres:18.6` | `postgres:18.1` |

**S3 — the acceptance criteria, verbatim from issue #597**:

> - `./docker/scripts/validate-env.sh <env>` (which already reports template keys the env file omits) also
>   reports every `*_IMAGE` whose value differs between `docker/.env.<env>` and `docker/.env.template`,
>   naming both values, and exits non-zero only when the caller asks it to be strict (a warning by default:
>   the script is the gate of the prod start, so it must not start failing on drift alone).
> - `./docker/scripts/deploy.sh <env> start` prints that same warning before building, so a stale pin cannot
>   survive a deploy silently.
> - A suite under `docker/scripts/tests/` pins both behaviours: the divergence is reported, the message names
>   the key and both values, and an aligned env file produces no warning.
> - The two local files on the machine that runs this stack are refreshed to the template's pins, and the
>   refresh is recorded here with the resulting versions.

**S4 — the non-goals, verbatim from issue #597**:

> - Changing `setup-env.sh` to rewrite existing env files: that would delete operator values nobody can
>   recover, which is why it only creates them.
> - Making drift a hard failure of `validate-env.sh`: the script gates the production start, and a stale pin
>   is a warning an operator decides on, not a reason to block a running system.
> - Touching the images themselves. This issue is about visibility and about the two local copies.

**S5 — the report lives in `_common.sh`, beside the missing-key report it mirrors, and is read-only.**
`report_stale_image_pins <env_file> <template>` returns **0 in every case** and sets `STALE_IMAGE_PIN_COUNT`
to the number of divergent pins, so a caller that wants to be strict decides for itself; that is the only
shape that cannot stop a production start, which is the same contract `report_missing_env_keys` carries one
function above it.

**S6 — only `*_IMAGE` keys are compared, and only the ones the env file declares.** Ports and memory limits
legitimately differ per environment — the same exception `image-pins.test.sh` §7 records — and a key the env
file does not declare at all is not drift: it runs the compose default, which `image-pins.test.sh` §2
already pins against the template. Reporting it here would duplicate the missing-key report and would call
a legal fallback a stale pin.

**S7 — strict is an explicit request, never the default.** `validate-env.sh [env] [--strict-pins]`: the
divergence is a warning in every run, and the strict run is the only one that exits non-zero — with its own
error line, so the summary never calls a warnable drift a validation *error*.

**S8 — `deploy.sh <env> start` prints the report before building.** In the `start` dispatch, before
`build_services`. In `prod` the report therefore appears twice — once before the build and once more inside
the validation gate `start_services` runs — which is accepted rather than deduplicated: suppressing it in
the environment where it matters most would be the wrong trade, and each line names the file it read.

## Tasks

| ID | Spec | Task | Commit |
| --- | --- | --- | --- |
| T1 | S1 | Approve #597 (`status:approved`), worktree off `origin/main` @ `43c370c3`, this record | — |
| T2 | S2 | Re-measure the five divergent pins on this worktree, read-only | — (read-only) |
| T3 | S3, S5, S6 | RED: `docker/scripts/tests/env-image-pin-drift.test.sh`, and `validate-env-strict-pins.test.sh` for the flag's behaviour | `fix(docker)` |
| T4 | S5, S6 | `_common.sh`: `env_file_value`, `template_image_keys`, `report_stale_image_pins` | `fix(docker)` |
| T5 | S7 | `validate-env.sh`: `check_image_pins`, `--strict-pins`, usage | `fix(docker)` |
| T6 | S8 | `deploy.sh`: the report in the `start` dispatch | `fix(docker)` |
| T7 | S3 | GREEN: the new suite, every suite in `docker/scripts/tests/`, shellcheck | — (checks) |
| T8 | S3 | Real run: `validate-env.sh dev`, `validate-env.sh dev --strict-pins`, then the refresh of both local files and the same run again | — (measurement, recorded below) |
| T9 | S3 | `AGENTS.md`: the section that documents both behaviours | `docs(docker)` |
| T10 | — | Nothing: `S4` rules out rewriting `setup-env.sh`, a hard failure, and any image bump | — |

## Log

**L1** *(the request, verbatim — `S1`)*: `si dale a ese`.

**L2** *(where the issue came from, so this record is not a root)*: #597 was opened on 2026-10-25 out of the
observability unit, and its own body names the stake — *"Tempo 2.3.0 is what wrote the `vParquet2` blocks
that 3.1.0 cannot read, and Loki 2.9.0 is the generation whose packaging differs from 3.7.8's. A
contributor who deploys staging from this machine today would recreate both problems, and nothing warns
them: `deploy.sh staging start` is happy."*

**L3** *(what the existing suites already own, so this unit does not duplicate them)*: `image-pins.test.sh`
§7 already compares every materialised env file against the template and **fails** — the right verdict for a
suite, the wrong one for a production gate, and silent in CI because the gitignored files do not exist
there. The new report is the same comparison with the opposite default, reachable where the files do exist.

**L4** *(the independent verification found a defect in the tests, and one real gap in the report — the two
corrections this record keeps)*. Three assertions of the new suite read `STALE_IMAGE_PIN_COUNT` after
`out="$(report_stale_image_pins …)"`; a command substitution runs the function in a **subshell**, so the
counter never reached the parent and those assertions could not observe what they claimed — the suite was
RED for the right reason and would have been GREEN for the wrong one. The fix is to call the report directly
and capture its text through a file; the trap is now also stated next to the counter itself. The same
verification measured that a **legal** env-file spelling — `KEY=value # comment`, `KEY="value"`, a trailing
space — was reported as drift, because the comparison was raw text; compose trims and unwraps, so a correct
file would have failed `--strict-pins`. The comparison now normalises the way compose reads the line, and the
suite pins the three spellings plus the one case where `#` really is part of the value.

## Evidence log

**2026-10-25 — the unit, and the re-measurement.** `./.github/scripts/new-unit.sh fix/env-image-pin-drift` created
the worktree off `origin/main` @ `43c370c3` (anchor clean). Reading `.env.template` against `.env.staging` and
`.env.prod` on that tree reproduced the issue's table exactly — the same five keys, the same two values — so
`S2` is a measurement, not a citation.

**2026-10-25 — RED, observed before any implementation.** `./docker/scripts/tests/env-image-pin-drift.test.sh`
→ `EXIT=1`, five failures on the first case and then
`line 94: STALE_IMAGE_PIN_COUNT: unbound variable`: the report did not exist, and the counter the strict caller
needs did not exist either. Nothing was implemented when this ran.

**2026-10-25 — the real differential, against the two files that actually diverge.** The worktree has no
`docker/.env.*` (gitignored) and no materialised secrets, so the local materialisations of the anchor were
copied into it (0444, one per secret: a **symlink** was tried first and rejected — `stat -c '%a'` does not
follow the link, so every secret read as mode `777` and `validate-env.sh` failed with 5 errors that had
nothing to do with this unit). With the real files in place, before the refresh:

| Command | Exit | Verdict printed |
| --- | --- | --- |
| `validate-env.sh dev` | 0 | `pins the same image versions as .env.template (9 compared)` |
| `validate-env.sh dev --strict-pins` | 0 | same — strict does not fail on a zero count |
| `validate-env.sh staging` | 0 | `pins 5 image(s) that .env.template names differently`, each key with both values, then `Validation passed!` |
| `validate-env.sh staging --strict-pins` | 1 | `Strict pin check failed: 5 image pin(s) in .env.staging diverge from .env.template` |
| `validate-env.sh prod --strict-pins` | 1 | the same verdict for `.env.prod` |

The third row is the property `S3` and `S4` demand and the one a mistake here would break: **drift alone does
not fail the production gate**. The anchor's unmodified script on the same machine answers `Validation
passed!`, so the baseline is the same and the new failure path is reachable only through the flag.

**2026-10-25 — the two local files, refreshed and recorded.** Backed up first to
`/tmp/lc-env-backup-20261025-135516/` (`staging.bak`, `prod.bak`, byte-identical by `cksum`). Only lines the
file already declared and that diverged were rewritten; `diff` against the backup shows **five changed lines
per file and nothing else** — no port, no limit, no key added. Resulting pins in both files:
`POSTGRES_IMAGE=postgres:18.6`, `KEYCLOAK_IMAGE=quay.io/keycloak/keycloak:26.8.1`,
`LOKI_IMAGE=grafana/loki:3.7.8`, `PROMETHEUS_IMAGE=prom/prometheus:v3.15.0`,
`TEMPO_IMAGE=grafana/tempo:3.1.0`. After the refresh, `validate-env.sh staging` and `validate-env.sh prod`
(each with and without `--strict-pins`) answer `pins the same image versions` and exit **0** — staging
compares 7 keys, prod 6, dev 9, which is each file's own declared subset, not a fixed number.

**2026-10-25 — what was deliberately not exercised.** `deploy.sh <env> start` was **not** run: it rebuilds and
restarts the stack that is up on this machine, which is not a check proportional to a three-line call site.
Its wiring is pinned by the suite instead — the report appears in the `start)` dispatch, before
`build_services`, and the case is otherwise unchanged with `SKIP_BUILD=true` (the report runs before that
branch, and skipping the build skips only the build).

**2026-10-25 — the final gates, after the third round of corrections.** **19 suites, 0 failing** —
`env-image-pin-drift` **44/0** and `validate-env-strict-pins` **25/0** — ShellCheck over **27** files under
`docker/scripts` → exit 0, no output, and the live runs unchanged: `dev` 9 compared, `staging` 7, `prod` 6,
every one aligned and exit **0** with and without `--strict-pins`.

**2026-10-25 — the offline gates.** The new suite, every suite in `docker/scripts/tests/` and the ShellCheck
gate exactly as `docker-build-integrity.yml` runs it, all from this worktree: **19 suites, 0 failing** —
`env-image-pin-drift` 38/0 and `validate-env-strict-pins` 20/0 among them, and `image-pins` 82/0, which is
17 more passing comparisons than before the refresh because the two materialised files now agree with the
template. ShellCheck over **27** files under `docker/scripts` (the workflow's own `find` glob) → exit 0, no
output. The same live runs again after the normalisation: `dev` 9 compared, `staging` 7, `prod` 6, all
aligned, `--strict-pins` included, every one exit **0**.

**2026-10-25 — independent verification, and what it changed.** `gentle-ai-verify` ran the new suite, the whole
suite directory and the ShellCheck gate on a scratch copy and returned one blocker and two advisories; all
three were acted on, none dismissed:

- **Blocker (`L4`)**: `env-image-pin-drift: 28 passed, 3 failed` — the three count assertions ran inside a
  command substitution. Fixed by calling the report directly and capturing the text through a file; the
  counter's own comment now states the trap. The suite is **38 passed, 0 failed** after the fix.
- **Advisory 1 (`L4`)**: legal spellings read as drift. Fixed by normalising both sides the way compose reads
  the line (`normalize_env_value`), with the three spellings pinned by the suite.
- **Advisory 2**: the flag's behaviour was pinned only by a grep. It now has its own suite,
  `validate-env-strict-pins.test.sh`, which runs the real script inside a scratch tree with a stub `docker`
  and asserts **exit codes**: drift without the flag `0`, drift with it `1`, flag before the environment `1`,
  aligned with it `0`, a broken secret without it `1` and never blamed on the pins, a missing env file `1`,
  an unknown environment `1`. **20 passed, 0 failed.**

The verifier's non-blocking findings reproduced the free probes behind every claim above, including the
differential against the baseline `deploy.sh` @ `43c370c3` with `build`/`start`/`health` stubbed: the report
prints before `MARK build_services`, `SKIP_BUILD=true` reaches `start_services` exactly as before plus the
three report lines, and `build`/`up`/`status` print no report at all.

**2026-10-25 — a second verification round, on the fixes themselves, and three more corrections.** Asking a
fresh verifier to attack the *fixes* rather than the unit paid twice over: it measured two defects of the
normalisation and one unrequested contract change, and all three were fixed before delivery.

- **B1, a false positive with real consequences**: `LOKI_IMAGE="grafana/loki:3.7.8" # keep pinned` read as
drift, because the value stopped being quoted once the comment was attached — `--strict-pins` would have
failed a correct file. The value is now read up to its closing quote, so anything after it is not part of it.
- **B2, a false negative with worse consequences**: the *declaration* guard was still the old `^KEY=` grep, so
`export KEY=v`, `KEY = v` and an indented `  KEY=v` were read correctly by the value reader and then skipped as
undeclared — the report printed `same image versions (1 compared)` while the stack would have run the old
image, with `--strict-pins` exiting **0**. One pattern now drives both readers, so they cannot disagree again.
It also settles the empty value: `${KEY:-default}` substitutes on empty, so a declared-but-empty pin runs the
pinned compose default and is not drift.
- **A1, an unrequested contract change**: parsing the arguments had made `-h`/`--help` exit 0 (baseline: 1,
as an invalid environment) and made `validate-env.sh dev staging` validate staging instead of the first
positional. Both are back to baseline; `--strict-pins` is the only token this unit adds. The native review's
two CRITICAL findings sat on exactly those lines and were refuted by its refuter, which is the same signal
from a different direction.

Each correction ships with its own assertion, and the suite is 44/0 (was 38/0) and 25/0 (was 20/0). The RED
for the three key spellings was observed on the pre-fix tree — `expected 1, got 0` three times — before the
shared pattern replaced the grep.

**2026-10-25 — the native review.** Lineage `review-467c9f6596dfc33f`, tier **high** (executable-mode change,
shell process, shell source), four lenses — `risk`, `resilience`, `readability`, `reliability` — 7 paths and
855 changed lines frozen. The reviewer group ran (4 model runs, ~63 KB of prompt each) and asked for a
refuter (1 run); the verdict was **approved**, with six findings all `informational`, two of them CRITICAL and
`refuted` by that refuter. Authority **burned** by the exact acknowledgement. The two refuted findings pointed
at the argument-parsing lines, which is where advisory A1 above found a real, smaller problem — the review is
an outside view, not a substitute for the verification that found it.
