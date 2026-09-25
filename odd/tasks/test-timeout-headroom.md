# ODD feature: test-timeout-headroom

**Repository**: LifeControl — Angular frontend test infrastructure (`life-control-app-angular/**`). Test
configuration only: the timeouts, the module that declares them, the guard that protects them, and a short
testing note in the component `AGENTS.md`. **No production code, no template, no route, no contract, no
build config, no CI change.**
**Status**: **implemented — 2 code/documentation commits plus this record on
`fix/test-timeout-headroom` off `main` @ `0725237`**, worktree
`~/workspace/LifeControl-worktrees/fix-test-timeout-headroom` (herdr workspace `w14`): `7fe3729` (the two
budgets, their shared module and the guard), `cdfc9d4` (the `AGENTS.md` note). **4 files, 202 insertions /
2 deletions** (the record is the fifth file and this commit). This header carries only dated local facts and
makes no claim about push or PR state — see `unify-mobile-viewport-consumers.md` F11.
**Created**: 2026-09-24
**Risk**: **low.** Production behaviour cannot change: the only runtime file touched is a test setup file
that no shipped bundle imports, verified by `npm run build` plus a grep of `dist/` for both constants. The
one real risk is raising a watchdog so far that a genuine hang stops being caught, addressed in D1 and
bounded in F7.

## Why this exists

This is the highest-value open item from `odd/tasks/unify-mobile-viewport-consumers.md`. That slice's
independent verification **proved** the suite's timeout flake pre-existing and load-dependent with an A/B
control, but deliberately left the fix undecided (`## Follow-ups`: "Options worth evaluating in their own
slice: raise the runner's test timeout, cap `maxWorkers`, or stop running suites concurrently in this repo's
multi-agent workflow. Each has a cost and none was decided here.").

The A/B control that established causality, for the record: the gate failed on that branch's tip (2 tests,
`setup 503s`, load 23.18) **and** on the anchor `0725237` with none of those changes present (1 test,
`setup 1063s`, load 22.49). Every failure was `Error: Test timed out in 5000ms`; not one was an assertion
failure. So the flake belongs to no particular slice, and it is not a product defect — it is a
**configuration defect**.

## What was measured before deciding anything

`npx ng test --no-watch --no-code-coverage --reporters=json`, parsed from `assertionResults[].duration`, on
an **idle** host (load average 0.69, 20 cores), 2529 tests, all passing:

| Metric | Value |
|---|---|
| p50 / p90 / p95 | 114 / 414 / 555 ms |
| p99 | **1030 ms** |
| p100 (worst) | **2909 ms** — `StoreLocationsPage initial state should create` |
| Tests over 1 s / 2 s / 5 s | **28** / **3** / **0** |

**The number that decides this slice**: the per-test budget is **5000 ms** and the worst observed test is
**2909 ms** — a margin of **1.72x**. A 72% slowdown of the slowest test breaches it. Note the direction of
that number: these durations were measured **without** coverage instrumentation while the gate runs
**with** it, so **1.72x is a ceiling on the margin, not a floor**. A second suite on the same host, a cold
module cache, or a 2-vCPU CI runner clears 1.72x comfortably. (F5 pushes this further: on a *busy* host the
same suite produced a 2954 ms worst case, i.e. 1.69x.)

The second measurement that decides it — **there is no other lever**:
`node_modules/@angular/build/src/builders/unit-test/schema.json` exposes `buildTarget`, `tsConfig`,
`runner`, `browsers`, `include`, `exclude`, `watch`, `debug`, `codeCoverage`, `codeCoverageExclude`,
`codeCoverageReporters`, `reporters`, `providersFile` and `setupFiles`, with `additionalProperties: false`
— **no `testTimeout`, no `hookTimeout`, no `pool`, no `maxWorkers`**. And `builder.js:186` starts Vitest with
`config: false`, comment `// Disable configuration file resolution/loading`. A `vitest.config.ts` is
therefore not merely unsupported but actively unread, which `life-control-app-angular/AGENTS.md`
already documented ("`@angular/build:unit-test` **ignora** `vitest.config.ts`. Usar `setupFiles` en
`angular.json` y `test-setup.ts`"). `setupFiles` is the single sanctioned injection point and
`vi.setConfig()` is the mechanism. Confirmed against the installed `vitest@3.2.4`:
`resolved.testTimeout ??= … 5e3` and `resolved.hookTimeout ??= … 1e4`, browser disabled (jsdom).

## Decisions

- **D1 — `testTimeout: 20000`. The timeout is a hang watchdog, not a performance budget.** 20000 ms is
  **6.9x** the worst test measured on an idle host and 2.9x the observed breach point. Rejected: `15000`
  (5.2x, and still only 2.9x over the breach point — it fixes today's flake with no room for the next
  legitimate slow test) and `30000` (10.3x, but a genuine hang stays silent for half a minute). The honest
  framing of 5000 ms is that it was Vitest's **default**, never a decided budget for this suite; the
  evidence is that it survives on an idle host and dies under contention, which is the definition of a
  flaky gate.
- **D2 — `hookTimeout: 20000`, raised alongside it.** Vitest's default is 10000 ms, and the same
  contention applies to the `beforeEach`/`afterEach` that build and tear down each `TestBed`
  (`teardown: { destroyAfterEach: true }`, `src/test-setup.ts`). Raising only `testTimeout` would redirect
  the identical flake into a hook timeout and the investigation would start over. Proven, not assumed: F3.
- **D3 — lowering parallelism was rejected on evidence, not taste.** The builder exposes no
  `maxWorkers`/`pool` option and reads no config file (see above), so there is no in-repo lever; and capping
  workers trades a load-sensitive gate for a slower one without removing the cause, since the slowest test
  is slow on an idle host too. Not available, and not a fix if it were.
- **D4 — a source guard, mirroring the existing idiom.** `src/shared/constants/breakpoints.spec.ts` already
  establishes the repo's guard pattern: read the source of truth as text through
  `process.getBuiltinModule('fs')`, resolved from `process.cwd()`, and fail if a declared boundary stops
  holding. The guard asserts that both budgets stay at or above the reviewed floor and that the setup module
  still wires them. Its honest limit is written into the file: **it proves the configuration is present and
  wired, not that Vitest honoured it** — that half is what the T1 probe is for (F5, and see F1 for how much
  work it took to make the text half mean anything).
- **D5 — the guard pins a floor, not the exact value.** It rejects a budget **below** 20000 ms rather than
  asserting equality, so a later slice may raise it without a guard fight, while lowering it requires
  deleting an assertion that names this record. That is the intended friction.
- **D6 — "do not run this suite in parallel with itself" becomes a written convention**, in
  `life-control-app-angular/AGENTS.md` next to the existing runner note, rather than in a record nobody
  re-reads mid-task. Two separate rounds had already rediscovered it.
- **D7 — the budgets live in their own side-effect-free module (`src/test-timeouts.ts`).** Forced by an
  obstacle found while building the guard: the first design had the guard import `src/test-setup.ts` to read
  the real constants, and that dies with `NG0400: A platform with a different configuration has been
  created`. The builder bundles `setupFiles` **separately** from the specs (the generated `init-testbed.js`
  entry), so the import evaluates a second copy of the module and runs
  `getTestBed().initTestEnvironment(...)` twice. Splitting the constants out removes the side effect, keeps
  the values importable as real values rather than parsed text, and is why the guard could then be hardened
  the way F1 describes.

## Scope

**In:** `src/test-timeouts.ts` (new), `src/test-setup.ts` (the wiring), `src/test-timeouts.spec.ts` (new, the
guard), `AGENTS.md` (the timeout note and the concurrency rule), this record.

**Out, deliberately:**

- **The stale coverage table in `AGENTS.md`.** It claims `92.53 / 72.56 / 86.13 / 92.53`; the real aggregate
  at this tip is **94.07 / 75.99 / 89.32 / 94.07**. Found while looking for the testing section, and the
  prose around it reasons about the old numbers ("~12 pts de margen sobre el baseline"), so correcting it
  needs its own decision rather than a silent patch here.
- **The 28 tests over 1 s, three of them over 2 s.** A 2.9 s `TestBed` bootstrap is a genuine performance
  signal, but optimising it is not what turns the gate green and would destroy the evidence this slice is
  built on.
- **Any per-spec timeout override.** `{ timeout: N }` on individual tests would fix the 28 slow ones and
  leave the other 2500 load-sensitive. The default belongs in one place.
- **`slowTestThreshold`.** Cosmetic here; nothing in this repo reads it.
- **CI runner sizing.** `ubuntu-latest` may be slower than the 20-core host these numbers come from, which
  argues *for* D1 and against tuning to this machine.

## Plan

| ID | Task | Surfaces | Risk | Status | Commit |
|---|---|---|---|---|---|
| T1 | Probe the mechanism: prove `vi.setConfig()` from a `setupFiles` entry changes the effective per-test timeout for a *different* spec file, with a 6 s body that must fail at the 5000 ms default and pass at 20000 ms. Throwaway, never committed. | — | **Medium — the slice rests on it** | **done** | evidence log |
| T2 | Declare both budgets in `src/test-timeouts.ts` and wire them in `src/test-setup.ts` (D7). | `src/test-timeouts.ts`, `src/test-setup.ts` | Low | **done** | `7fe3729` |
| T3 | Add the source guard `src/test-timeouts.spec.ts` per D4/D5, with its limits written down. | `src/test-timeouts.spec.ts` | Low | **done** | `7fe3729` |
| T4 | Prove the guard falsifiable by mutation, and keep hunting until no bypass is left. | — | **Medium — this is where the real defects were** | **done** | evidence log, F1 |
| T5 | Record the budgets and the concurrency rule in `AGENTS.md`; fix the two defects on the lines touched. | `AGENTS.md` | Low | **done** | `cdfc9d4` |
| T6 | Gates: lint, build, `test:coverage:check`, with count reconciliation against the `0725237` baseline. | — | Low | **done** | evidence log |
| T7 | Independent adversarial verification, then commit per work unit. | — | Low | **done** | this record |

## Gate

Run in `life-control-app-angular/` at the tip of `cdfc9d4`, host load 1.96 at the start:

1. `npm run lint` → exit 0, *All files pass linting*.
2. `npm run build` → exit 0, *Application bundle generation complete*.
3. `npm run test:coverage:check` → exit 0. **130 files / 2533 tests passed**, and **zero** occurrences of
   `Test timed out` in the whole log. Aggregate `statements 94.07% / branches 75.99% / functions 89.32% /
   lines 94.07%` against the enforced 80 / 60 / 75 / 80 (`scripts/check-coverage.mjs`).
4. **Count reconciliation.** Baseline at `0725237`: **129 files / 2529 tests** (measured twice, here and
   independently by the verification round in its own checkout). Tip: **130 / 2533**. Delta: **+1 file,
   +4 tests**, and the guard contributes exactly those — `grep -c 'it('` on `src/test-timeouts.spec.ts` is
   **4**, and no spec was added, removed or renamed otherwise (`git ls-tree main` has 129 spec files, the
   worktree has 129 tracked plus this one new untracked-then-added file). No
   `it.only`/`describe.only`/`fit(`/`fdescribe(`/`xit(`/`xdescribe(`/`.skip` was added.
5. **Production cannot be affected.** `grep -rn test-timeouts src/` returns only `src/test-setup.ts:11` and
   `src/test-timeouts.spec.ts:1`; a grep of `dist/life-control-app-angular/` for `TEST_TIMEOUT_MS`,
   `HOOK_TIMEOUT_MS` and `test-timeouts` returns **nothing**.
6. **The repo's own pre-commit hook is a no-op on this change, checked rather than assumed.** The hook is
   `npx lint-staged` (→ `eslint --fix`, `prettier --write` on `src/**/*.ts`); `npx prettier --check` on all
   three committed files reports *All matched files use Prettier code style!* and `npx eslint` on them is
   clean, so committing with `--no-verify` produced byte-identical files to what the hook would have left.

## Evidence log

| Date | Event | Evidence | Notes |
|---|---|---|---|
| 2026-09-24 | Slice opened | worktree `w14`, branch `fix/test-timeout-headroom` off `main` @ `0725237` | D1–D6 recorded before any file was written |
| 2026-09-24 | Per-test durations measured | idle host (load 0.69): p99 1030 ms, worst 2909 ms, 28 tests > 1 s, 3 > 2 s, **1.72x margin against the 5000 ms budget** | The measurement the decision rests on |
| 2026-09-24 | Available knobs surveyed | schema has no timeout/pool/maxWorkers option; `builder.js:186` passes `config: false`; `AGENTS.md` already named `setupFiles` as the route | D3, and the reason there is only one lever |
| 2026-09-24 | **T1 — mechanism probed with a control** | `src/__probe-timeout.spec.ts` (6000 ms body, throwaway, deleted): **before** → `1 failed`, `Test timed out in 5000ms` at 5013 ms; **after** → `1 passed`, body completed at 6006 ms. Same file, same command, only the setup module changed | Proves the setting reaches a *different* spec file, and reproduces the historical failure string on an idle host |
| 2026-09-24 | T1 re-confirmed after the D7 refactor | probe re-run at the final wiring: `1 passed`, body completed at 6007 ms, then the probe file deleted | Guards against the module split breaking the mechanism |
| 2026-09-24 | **T4 — first mutation matrix (5 mutants)** | base `3 passed`; lowering `TEST_TIMEOUT_MS` → red; literal `5_000` → red; delete the call → red. **Line-commenting the call stayed GREEN** | First real defect: a commented-out line still contains the call |
| 2026-09-24 | Guard hardened, matrix re-run | comment-stripping added; base `3 passed`; all five mutants red, including line comment, block comment and deletion | Closed by making the guard read executable text |
| 2026-09-24 | **T7 — independent adversarial verification** | delegated, read-only, in `/tmp` copies | 9 claims: **7 VERIFIED, 1 REFUTED in part, 1 NOT REPRODUCED on its stated terms**; findings below |
| 2026-09-24 | **F1 — two guard bypasses found by the verification round** | (f1) the call replaced by a *string literal* spelling the same text → guard `3 passed` while a 6 s probe failed at 5000 ms; (f2) a **second** `vi.setConfig({ testTimeout: 5_000 })` appended after the real call → guard `3 passed`, same probe failure | Neither was in my matrix; both are exactly the silent regression the guard exists to prevent |
| 2026-09-24 | Guard hardened again, matrix re-run with 7 mutants | base `4 passed`; string-literal decoy → red; second overriding call → red; the original five still red | Fixed by stripping string literals too, and by banning numeric timeout literals anywhere in the module |
| 2026-09-24 | Gates green | lint / build / `test:coverage:check` → 130 files, 2533 tests, coverage within thresholds, zero timeouts | Evidence in `## Gate` |
| 2026-09-24 | Commits | `7fe3729` (budgets, module, guard), `cdfc9d4` (AGENTS.md), this record | Four work-unit-scoped files plus the record |

## Findings

From the independent adversarial verification round (read-only, run in throwaway `/tmp` copies, never in the
worktree). All nine claims were attacked; these are the things it established that were not simply my claims
restated.

- **F1 — THE ONE THAT MATTERED: my guard shipped with two bypasses, and the verification round found both.**
  Neither was in my five-mutant matrix, and both leave the suite silently back at Vitest's 5000 ms with the
  guard reporting green:
  - **f1, string decoy**: replacing the call with
    `const _decoy = 'vi.setConfig({ testTimeout: TEST_TIMEOUT_MS, hookTimeout: HOOK_TIMEOUT_MS })';` satisfied
    all three regexes, because the stripper removed comments but not string literals.
  - **f2, later override**: *appending* a second `vi.setConfig({ testTimeout: 5_000, hookTimeout: 5_000 })`
    after the real call satisfied every positive assertion while Vitest's later call won.

  Both are now closed and re-proved: the stripper also removes single-quoted, double-quoted and template
  literals, and a new assertion rejects any numeric `testTimeout`/`hookTimeout` value anywhere in the
  module, which is what makes a second override impossible without counting calls and forbidding legitimate
  future ones. The post-fix matrix is **7 mutants, 7 red, base 4 passed**. The lesson generalizes past this
  slice: **a text guard is falsified by mutation testing or not at all**, and one round of mutations is not
  enough — the same guard passed five mutations and failed the sixth and seventh.
- **F2 — my own record was stale and self-contradictory, and the verification round said so.** It named the
  guard `src/test-setup.spec.ts` (the shipped file is `src/test-timeouts.spec.ts`), left all seven Plan rows
  at `pending`, left `## Gate` and `## Findings` as literally "Pending", and had no T1/T4 probe evidence —
  while the guard's own docstring pointed at "T1/T4 in the record". Symptom of writing a record up front and
  not closing the loop before handing it over. This revision fixes all of it; the pointer is now true.
- **F3 — `hookTimeout` is honoured, proved empirically rather than textually.** A probe with an 11.5 s
  `beforeEach` fails at base with `Hook timed out in 10000ms` and passes at the tip (11508 ms). This is what
  turns D2 from an inference into a measurement.
- **F4 — the branch-coverage movement is NOT attributable to this slice, and the old baseline did not
  reproduce.** My earlier note said branches moved 75.98 → 75.99. The verification round measured coverage on
  **both** base and tip in separate checkouts and got **75.99 on both**, and confirmed `src/test-timeouts.ts`
  contributes `branches: { total: 0 }` (100%, adds no branches at all), with spec files excluded from the
  report entirely. So the new file cannot move that number, and the base's own percentage is not
  bit-reproducible across runs. Recorded rather than smoothed over: it also means the enforced thresholds
  have ~14–16 points of margin against a metric that wobbles in the second decimal, which is fine, but nobody
  should cite a 0.01 movement as evidence of anything.
- **F5 — C1's "one additional file" limit is gone, and the mechanism is confirmed in Vitest's own source.**
  The round ran a **60-file** probe (all 6000 ms bodies) in a single run: `60 passed (60)`, ~360 s of test
  time in a 33 s wall clock, i.e. roughly 10–11 concurrent workers, and **every** file received 20000 ms;
  a 4-file probe including a different directory behaved the same; none missed it. Mechanically,
  `setConfig` does `Object.assign(workerState.config, …)`, the runner calls `vi.resetConfig()` **after each
  file** while setupFiles re-run per file, so each subject re-applies it, and the runner reads
  `runner.config.testTimeout` at execution time. The original claim was safe; it is now much better
  supported. On a busy host the same suite's worst test was **2953.8 ms** (1.69x margin) against the idle
  host's 2909 ms, which strengthens the case rather than weakening it.
- **F6 — a line-number drift in this record's own source citations.** `builder.js:189` was cited for
  `config: false`; the actual line is **186**. Corrected above. Cosmetic, but it is exactly the kind of
  citation that rots.
- **F7 — the cost side, stated honestly: a genuinely hung test now surfaces 4x later.** 20 s instead of 5 s,
  which is the accepted price of D1 and the reason the ceiling was not pushed to 30 s. It is bounded: with
  F1 closed, an accidental revert to a 5 s budget cannot pass silently.
- **F8 — two defects on lines this slice already touched, fixed here.** `AGENTS.md`'s directory tree carried
  two final-branch prefixes (`└──` on both `test-setup.ts` and `test-timeouts.ts`), which broke its
  rendering; and the `- **Setup**:` bullet said "zona.js" instead of "zone.js", a pre-existing typo on the
  line being edited. The typo was left untouched by the first edit and the verification round caught it.
- **F9 — no observed interaction with `zone.js` or the TestBed init.** `vi.setConfig` sits at
  `src/test-setup.ts:16`, after the `zone.js*` imports and before `initTestEnvironment`. The full 2533-test
  suite, the 60-probe run and the 11.5 s hook probe all pass. That is absence of evidence of harm, not a
  proof.
- **F10 — the verification round's own tooling wrote a harmless cache inside the worktree.** Running tests
  from a `/tmp` copy whose `node_modules` was a symlink landed `node_modules/.vite/vitest/<hash>/results.json`
  inside the worktree. It is gitignored build cache, not tracked state; the round switched to a real
  `node_modules` afterwards and reported it rather than hiding it. Worth knowing for future rounds: **copy
  `node_modules`, do not symlink it, if the copy must stay sealed.**
- **F11 — the breakpoint source guard is not tripped by the new file.** `scanSources` in
  `src/shared/constants/breakpoints.spec.ts` scans every `.ts` under `src/` except `*.spec.ts` and
  `breakpoints.ts` for quoted `(max|min)-width` literals; neither new file contains one, and
  `breakpoints.spec.ts` still reports its 12 tests passing.

## Follow-ups

- **The stale coverage table in `AGENTS.md`** (92.53 / 72.56 / 86.13 / 92.53 against a real
  94.07 / 75.99 / 89.32 / 94.07). Needs its own decision, because the surrounding prose is calibrated to the
  old numbers.
- **The 28 tests over 1 s, three over 2 s**: `TestBed` bootstrap cost, a genuine performance signal that this
  slice deliberately did not touch.
- **A structural alternative worth evaluating** if the guard keeps needing hardening: expose the effective
  timeout instead of matching text for it. Nothing public in Vitest 3.2.4 does that today, which is why D4
  went textual, but a future Vitest may, and that would make F1's whole class of bypasses disappear.
- Unchanged from the previous inventories: `header.ts:155–160` untested, the three off-scale breakpoint
  literals, the dead `LayoutModule`, six copies of `setupMatchMedia`, the silent-default hazard in
  `DetailTable (mobile)`, the unrefuted `NgZone` difference, the uncovered SSR branch, the ODD header drift
  in `mobile-breakpoint-guard.md` and `mobile-viewport-observer.md`, the dead `zones-list`/`stores-list`
  components, `var(--bp-*)` with no consumer, and
  `docker/scripts/_common.sh::verify_artifact_freshness` comparing mtimes.
