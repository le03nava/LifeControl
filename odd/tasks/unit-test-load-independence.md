# Unit-test gate: load-independent verdict

The unit-test gate in `life-control-app-angular/` fails on an unchanged green tree when the host is loaded,
with no assertion failure anywhere in the log. Issue #971 measured it on one commit: two red runs whose
failures were all `Test timed out in 20000ms` / `Hook timed out in 20000ms`, and a green run of the same tree
with the runner's worker count reduced to 2. The budget was not the defect — `src/test-timeouts.ts` already
buys 6.9x the worst observed test — so the verdict was a function of concurrency, not of the code.
`odd/tasks/test-timeout-headroom.md` raised those budgets and could not fix this, because contention had
already been ruled out of that unit's scope.

**Status**: implemented — the worker cap, its guard spec, the measured runs and this record are the branch's
content, and the `AGENTS.md` prose rule is replaced by the mechanism it used to ask for. Nothing is deferred.
**Created**: 2026-11-18 · **Risk**: item 5 — the change decides how much of the host the whole local suite and
both CI shards may occupy, and a cap that is too low is a wall-clock cost on every run that no test failure
signals.

## Specs

Provenance: the acceptance criteria of issue #971, which this unit implements, quoted verbatim.

**S1** — `npm run test:coverage:check` returns **exit 0** on an unchanged green tree at an idle load and at a
load average of at least 3× `nproc`, measured on the same commit, with both runs reported.

**S2** — A test that genuinely exceeds the budget still fails: raising the timeouts, if that is part of the
fix, must not be able to hide a hang. Prove it by mutating one test to sleep past the configured
`testTimeout` and observing a red run.

**S3** — No silent retry. A gate that re-runs a failed test hides exactly the signal it exists to report, so a
retry mechanism is not an acceptable fix even if it makes the numbers green.

**S4** — The wall-clock cost of the change is reported for the idle case. A fix that makes the local suite
measurably slower is a trade to state, not a side effect to discover later.

**S5** — The two CI shards keep their `VITEST_SHARD` behaviour and `test:coverage:merge:check` still produces
the merged verdict over both blob reports; `VITEST_SHARD=` unset still runs the whole suite.

**S6** — `npm run lint`, `npx tsc --noEmit` and every suite in `.github/scripts/tests/` are unaffected.

Non-goals, verbatim from the same issue: no test-runner migration and no change of runner; no change to the
coverage thresholds or to `scripts/check-coverage.mjs`; no per-test timeout tuning beyond what a measurement
justifies, and no touching `jsdom`; no change to how the shards split or how many there are.

## The change

One option: `maxWorkers: MAX_WORKERS` with `MAX_WORKERS = 2`, in the `test` object that `angular.json`'s
`runnerConfig` hands to Vitest — the same channel the budgets already travel, because the builder's schema
exposes neither `maxWorkers` nor `testTimeout`.

Vitest sizes the pool from the CPU count (`resolveMaxWorkers` in `vitest/dist/chunks/cli-api`:
`availableParallelism() - 1`, i.e. **7** on this 8-core host) and has no memory budget, so every additional
core adds a whole Node process — measured at ~280MB resident per fork here. The oversubscription surfaces as
swap, and a starved timer fires far past its 20s budget. The cap is **fixed**, not a fraction of the core
count: a fraction is the defect itself, because the resource that runs out first is memory, not cores.

## Tasks

| ID | Specs | Task | Evidence |
| --- | --- | --- | --- |
| T1 | S1, S4 | Bound runner concurrency in `src/test-timeouts.ts` (`MAX_WORKERS = 2` wired as `maxWorkers`) | `src/test-timeouts.ts:71,148` |
| T2 | S2, S3 | Guard the wiring, the measured ceiling and the absence of a `retry` in `src/test-timeouts.spec.ts` | RED `2 failed / 7 passed`, then GREEN `9 passed` (L5) |
| T3 | S2 | Prove a genuine hang still fails, with a probe spec that sleeps past `testTimeout` (not committed) | `Error: Test timed out in 20000ms` (L6) |
| T4 | S1, S4 | Measure both loads on the same commit and report the idle wall clock | four full-suite runs (L4) |
| T5 | S5 | Confirm both shards still split the suite and the merge still unions the two blob reports | 2+2 files, 6+9 tests, merged 4 files/15 tests (L7) |
| T6 | S6 | Gates: `prettier --check`, `npm run lint`, `npx tsc --noEmit`, every `.github/scripts/tests/*.test.sh` | L8 |
| T7 | all | Replace the `AGENTS.md` prose rule with the mechanism that now enforces its intent, and finish this record | `life-control-app-angular/AGENTS.md`, this record |

## Log

**L1** (user, verbatim): `puedes aprovar y hacer el issue 971`

**L2** — requirement source and approval. Issue #971 was read in full and approved with `status:approved` on
2026-11-18 (pre-read: no status label; post-read: `["status:approved"]`, state `OPEN`). The acceptance
criteria in `## Specs` are quoted from that body; the measured evidence in its `Problem` section (runs 1–3 on
one tree, `nproc` = 8, load 11.14 → 36.77) is the reproduction this unit starts from.

**L3** — why the cap is honoured and not merely written. `@angular/build:unit-test` merges the module's `test`
object into the Vitest project verbatim
(`node_modules/@angular/build/src/builders/unit-test/runners/vitest/plugins.js`:
`projectBase = mergeConfig(projectDefaults, { test: testConfig })`; its `projectOverrides` set `name`,
`include`, `fsModuleCache`, `browser`, `isolate` and `environment`, never the pool), and Vitest resolves the
pool size from `project.config.maxWorkers` (`resolveMaxWorkers`). Observed instead of argued, by counting live
runner processes during a run rather than reading a message in a log:

| Run | Max live `vitest/dist/workers/forks.js` | Wall clock |
| --- | --- | --- |
| 6-file subset, cap wired | **3** (2 workers + the transient start/stop fork) | 27.12s |
| 6-file subset, cap removed as a mutant | **6** (one per file) | 16.30s |
| full suite, cap wired, idle host | **2** | 246s |
| full suite, cap wired, 3x load | 4 (two workers plus replacements that overlap while a starved one exits) | 1070s |
| full suite, cap removed as a mutant | **7** | 152s |

**L4** — the measurement, all of it on this branch and the same tree, `nproc` = 8, 7.9GB RAM, 2GB swap.
"Load" is the 1-minute average read from `/proc/loadavg` immediately before the run and during it.

| # | Pool | Load average | Result | Wall | Notes |
| --- | --- | --- | --- | --- | --- |
| 1 | default (7) | 100 → 146, from a concurrent session's Gradle suite | **did not finish** | stopped at 1h47m | the reproduction: every failure was starvation (`× … 405375ms`, `× … 90004ms`, `× … 405202ms`, one file `522067ms`), zero assertion failures |
| 2 | default (7), mutant | 10.6 (1.3x) | exit 1 | 152s | the only failure is the guard asserting the wiring the mutant removed — i.e. the guard works in a full run, not only alone |
| 3 | cap 2 | 5.5 (0.7x) — the idle case | **exit 0** | **246s** | 215 files / 4412 tests, every coverage threshold OK |
| 4 | cap 2 | 24.10 → 27-29 (3.0x–3.6x) | **exit 0** | **1070s** | 215 files / 4412 tests, every coverage threshold OK |

S1 is runs 3 and 4: the same command, the same tree, exit 0 on an idle host and at 3.0x–3.6x `nproc`.
S4's cost is runs 2 vs 3: **152s → 246s, a 1.62x longer idle wall clock**, and the 6-file subset in L3 says
the same thing at 1.66x. That is the trade, paid on purpose: a gate that always tells the truth takes longer
than one that lies under load. Two honesty notes about the pair: run 2 is a mutant (the guard failure is the
only difference from a clean uncapped run), and run 2 faced *more* load than run 3, so the 1.62x is a
conservative reading of what the cap costs.

**L5** — the guard, RED before GREEN, with the discrimination made explicit. RED #1: the spec's new import
fails to resolve (`No matching export in "src/test-timeouts.ts" for import "MAX_WORKERS"`). RED #2, which is
the one that matters: with the constant exported at the runner's own default (`MAX_WORKERS = 7`) and nothing
wired, **2 failed / 7 passed** — the ceiling assertion and the wiring assertion, and only those. GREEN after
the change: **9 passed**. S3 is guarded, not asserted in prose: `retry` in the module fails a test, because a
retry would make a loaded host green by hiding the failure the gate exists to report.

**L6** — S2, that a real hang still fails. A probe spec (`src/tmp-hang-proof.spec.ts`, never committed) with
one test that sleeps 25 000ms and no per-test override: `Error: Test timed out in 20000ms`, `1 failed`. The
first attempt passed `25_000` as the per-test argument, which of course timed out at 25000ms — it proved the
wrong thing, and is recorded here because a probe that overrides the budget it is checking is the exact
mistake this criterion exists to catch.

**L7** — S5, shards and merge. With `VITEST_SHARD=1/2` over four small specs: 2 files / 6 tests; with `2/2`:
the other 2 files / 9 tests — disjoint and adding up. `npm run test:coverage:merge:check` then ran
`vitest --merge-reports` over **both** blobs and reported the union, **4 files / 15 tests**, and the threshold
gate fired on that subset as it must (29.24% statements against an 80% floor). Unset `VITEST_SHARD` ran the
whole suite in every full run above (215 files), which is the third leg of the criterion. The shard count and
the split are untouched: this unit changes how many runners run *within* a shard, never which files a shard
gets.

**L8** — S6 and the repository gates, run in the worktree after the change: `npx prettier --check
"src/**/*.{ts,html,scss}"` (the CI formatting step) → all files pass; `npm run lint` → all files pass;
`npx tsc --noEmit` → clean, no output; every suite in `.github/scripts/tests/` → **18 suites, 0 failed**.
`odd-status-headers.test.sh` and `odd-citations.test.sh` were also run on their own while this record was
being written, because this record is the first thing they judge.

**L9** — CI on the pull request that carries this unit, which is the one cost the local measurement cannot
show: **6 of 6 checks green** — `A linked, approved issue`, `Exactly one type:* label`, `Lint & Build` 57s,
`Unit tests (1)` **2m54s**, `Unit tests (2)` **2m28s**, `Coverage` 37s. The cap did not inflate the shards past
their historical range: a GitHub runner has 4 vCPUs, so its default pool was 3 and the cap removes one, against
the 7 removed locally.

