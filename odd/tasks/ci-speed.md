# ODD feature: ci-speed

**Repository**: LifeControl — the shape of the CI workflows that gate a pull request (`.github/workflows/api-ci.yml`, `.github/workflows/angular-ci.yml`) and the two documents that name those gates verbatim (`CONTRIBUTING.md` §6 and §7, `.agents/skills/project-conventions/references/index.md` → `## Dispatch`). **No production code, no test semantics, no timeouts, no isolation settings, no coverage threshold, and — after D1 — no Gradle build change either.**
**Status**: merged — U1 in pull request **#435** (`chore/ci-speed` @ `f16d7ae`), 2026-10-19, with its `CONTRIBUTING.md` §6 contract guarded by pull request **#443** (`chore/job-name-guard` @ `e461af7`), and the Angular shards in pull request **#445** (`chore/angular-shard` @ `d396d3a`), 2026-10-19. Measured on the runner: API CI **193s** against a 247–336s baseline, Angular CI **158s** against a 228–401s baseline. **U2 closed with its measurement, and the setting does not land**: on the runner, four parallel forks are **+21% on the median** (247s serial against 299.5s) and three times as unstable (100s spread against 33s), so `life-control-api/build.gradle` is unchanged. **No work left in this record.**
**Created**: 2026-10-18
**Risk**: **medium.** Every file touched is CI-only, which is Task Size item 5 (delivery/environment). Two named risks, both argued rather than assumed: (1) dropping a gate, a `paths:` filter or a step would silently narrow what the four workflows cover, mitigated by `workflow-trigger-parity.test.sh` and by S2; (2) the Gradle configuration cache can fail inside spotless/spotbugs/asciidoctor in ways whose output does not name the cause, so it is validated locally first (F3) and removed from the slice if it does not hold. Everything here is revertible with one commit. The one change deliberately **not** taken — `org.gradle.caching` — is excluded in S7 because it would change what a green check proves, not merely how long it takes.

## Why this exists

A pull request into this repository waits on two component workflows, and nobody had measured
where their time goes. Measured on 2026-10-18 from the last ~10 runs of each workflow
(`gh run view <id> --json jobs`, per-step durations):

| Workflow | Wall clock | Step breakdown |
| --- | --- | --- |
| **API CI** | 247–336s | `Static analysis` 43–98s, then `Run tests` 186–221s — two steps of **one** job, so they cannot overlap |
| **Angular CI** | 228–401s | `Install dependencies` 12–89s · `Lint` 11–15s · `Build` 12–19s · **`Test` 135–282s** |
| Gateway CI | 45s | `Run tests` 37s |
| Docker Build Integrity | 17s | five offline suites |
| PR Label Guard | 17–21s | shellcheck 9s; required check |

The Angular test step swinging 135s → 282s **on unchanged code** is the other finding: the floor is
not merely slow, it is unstable.

Two of the most inviting fixes were killed with data before this record was written, which is why
they are non-goals and not tasks:

| Hypothesis | Measurement | Verdict |
| --- | --- | --- |
| `spring.jpa.show-sql=true` in `application-test.properties` costs the API suite | `forks=1`: 168s summed class time; `forks=1` + `SPRING_JPA_SHOW_SQL=false`: **169s** | **No effect.** Left alone |
| `maxParallelForks=4` cuts the 186–221s test step by ~4x | local: 184s → **145s** (1.27x) with per-class time inflating 169s → 464s, effective parallelism 3.65 | **Real but not free**, and the dev host (11 GB with the stack up) is not the 4 vCPU / 16 GB runner. U2 measures it there; nothing lands on this number |

## Specs

S1. The `API CI` run reports static analysis and tests as **two jobs that run concurrently**, and the
workflow's total wall clock is lower than the `247–336s` baseline above, measured on the same commit
content.

S2. Every `paths:` filter that ran before the change still runs after it, and
`bash .github/scripts/tests/workflow-trigger-parity.test.sh` exits 0.

S3. `CONTRIBUTING.md` §6 and `.agents/skills/project-conventions/references/index.md` (`## Dispatch`)
name the same job `name:` strings and gate commands the workflows actually declare. *"Each job's
`name:` is quoted verbatim because the branch-protection runbook below names required checks by
exactly those strings."*

S4. `cd life-control-api && ./gradlew test --no-daemon` still executes the whole suite — **3527**
tests, as the runner's own report counts them. A run that reports success with the `test` task
`UP-TO-DATE` or `FROM-CACHE` while executing nothing fails this spec.

S5. With sharding enabled, a run in which one shard's own summary is below a threshold **cannot**
produce a green `npm run test:coverage:check`, because the gate reads the merged report and never a
single shard's file.

S6. Each unit's dated log records the `Run tests` / `Test` step seconds before and after, taken from
`gh run view --json jobs`.

S7. Non-goals, quoted from issue #433: *"Paying for larger runners, self-hosting runners, or buying
more vCPU"*; *"Removing or weakening any existing gate, `paths:` filter, or coverage threshold"*;
Gradle's **build cache** — *"it would make a re-run of the same commit report green without executing
the suite, and this repository treats a green check as evidence that the suite ran"*;
`maxParallelForks` — *"it lands only if a run on the runner shows a win; otherwise it is closed with
its measurement recorded"*; *"Running the Playwright e2e suite in CI"*; *"Changing the test suite's
semantics, timeouts or isolation settings."*

## Tasks

| ID | Spec | Route | Unit |
| --- | --- | --- | --- |
| T1 | S1, S2, S3 | `gentle-ai-worker` (delegated under the context backstop) | **U1** — split `api-ci.yml` into `Static analysis` and `Test` jobs, and move `CONTRIBUTING.md` §6 plus the §7 drift the split created in the same commit. Landed as `4cbe362` |
| T2 | S4, S6 | inline | **U1** verification — `workflow-trigger-parity.test.sh` (25 passed, 0 failed) and an independent YAML parse prove the job graph, the steps of each job, both `paths:` lists and `concurrency:` on this tree; the runner proved the rest on pull request #435, run `39195746631` (see the evidence log). Closed |
| T3 | S7, S6 | inline | **U2** — measure `maxParallelForks` on the runner. **Closed without landing the setting**: 8 runs on the runner gave `serial` median 247s against `forks=4` median 299.5s, with a 100s spread against 33s. The probe pull request is closed unmerged by design and issue #439 closes with the number |
| T4 | S5 | inline | **U3** — merge the coverage report across shards, prerequisite of T5. Landed in pull request #445 (@ `d396d3a`) |
| T5 | S1, S3 | inline | **U3** — shard the Angular `Test` step. Landed in the same pull request, measured in its run: 158s against 228–401s |

## Decisions

D1. **Neither Gradle cache is in this slice — measured, not assumed** (S7). The configuration cache
was the plan until it was measured: `./gradlew spotlessCheck spotbugsMain test --dry-run --no-daemon`
takes 8–11s warm, 10s on the first `--configuration-cache` run and 5–6s on the following ones
(`Configuration cache entry reused`), i.e. **3–4s per invocation**, not the 10–20s estimated. And
after T1 each job runs `./gradlew` once on a fresh runner, and `setup-java` persists only
`~/.gradle/caches` and `~/.gradle/wrapper` — never `~/.gradle/configuration-cache` — so there is no
entry to reuse in CI at all. The two halves of U1 cancelled each other: the split removed the second
invocation that gave the cache its value. The **build cache** stays out on principle (S7): its only
win is a re-run of an identical commit, which is exactly the case where the gate must prove the suite
ran. `life-control-api/gradle.properties` was therefore not created.

D2. **The split's win is bounded by the test job, not by the sum.** In one job `compileJava` is
executed once and reused by the test step (`> Task :compileJava UP-TO-DATE` in the measured log).
Split, the test job compiles `main` itself before `compileTestJava` — and the runner confirms it
did: the `Test` job's log carries `> Task :compileJava` with no `UP-TO-DATE`. Measured cost of the
duplicate: `compileJava` + `processResources` + `classes` began at 03:40:15 and `compileTestJava` at
03:40:22, so ~7s, not the ~18s first estimated. Measured outcome on pull request #435: workflow wall
clock **193s** against the 247–336s baseline, `Run tests` 182s (baseline 186–221s), `Static analysis`
35s (baseline 43–98s).

D3. **`maxParallelForks` is a measurement, not a change** (S7). Local evidence is 1.27x with 2.7x
per-class inflation; the runner is the only valid instrument, and 4–5 runs are needed to tell a win
from noise (local run-to-run noise measured at ~9%).

D4. **The coverage merge precedes the shards** (S5). `scripts/check-coverage.mjs` reads
`coverage/coverage-summary.json` and compares `summary.total` against global thresholds; per shard
that file describes only that shard's subset, so the gate would pass or fail on half the suite
without saying so.

D5. **Job `name:` strings are a public contract.** `CONTRIBUTING.md` §6 quotes them verbatim because
the branch-protection runbook names required checks by exactly those strings, so the rename and the
documentation move in one commit (S3). The same rule caught §7, which the delegation was not told to
touch and which asserted that `api-ci.yml` and `gateway-ci.yml` *"share the job name `Check`"* — true
before T1, false after it. It was repaired in the same commit: a document that names the gate wrongly
is the defect S3 exists to catch, not a follow-up.

## U3 design — the coverage merge, verified against the published builder

Verified read-only on 2026-10-18 against the published `@angular/build@21.2.27` sources. The
checkout's `node_modules` is 20.3.36 and predates `runnerConfig`, so it cannot answer any of this.

| Fact | Evidence |
| --- | --- |
| `runnerConfig` is handed to Vitest as its own `config:` | `runners/vitest/executor.js`: `const vitestConfig = { config: externalConfigPath, root: workspaceRoot, … }` → `startVitest('test', undefined, vitestConfig, …)` |
| `shard` is a **string** (`"1/2"`) and a **non-project** option, read from the **root** config | `resolveConfig.ts`: `options.shard.split('/')`; `reporters.d.*.d.ts`: `shard?: string` and `NonProjectOptions = "shard" \| …`; `chunks/coverage.*.js`: `const { index, count } = config.shard` |
| The builder folds the `runnerConfig` `test` object into `test.projects[0]` — **project** level | `runners/vitest/plugins.js`: `mergeConfig(projectDefaults, { test: testConfig })` → `projects: [projectConfig]` |
| `coverageReporters` in `angular.json` **wins** over `coverage.reporter` in `runnerConfig` | `plugins.js` deletes `testConfig.coverage.reporter` and sets `reporter: optionsCoverage.reporters` |
| `reportsDirectory` can only come from `runnerConfig` | the builder has no option for it: `reportsDirectory: configCoverage?.reportsDirectory ?? join('coverage', projectName)` |
| `blob` is a **test** reporter, never a coverage reporter | the `coverageReporters` enum in `schema.json` is html/lcov/lcovonly/text/text-summary/cobertura/json/json-summary |
| Blobs carry coverage | blob tuple `[version, files, errors, coverage, …]` |
| Native thresholds are enforced on the map **that run measured** — per shard when they sit in `angular.json`, on the merged map when they sit on the merge command | `@vitest/coverage-v8/dist/provider.js` `generateReports(coverageMap, allTestsRun)` → `reportThresholds`; `chunks/coverage.*.js` `mergeReports(coverageMaps)` → `coverageMap.merge(…)` → `generateReports(coverageMap, true)` |
| v8 coverage `clean()` **rm -rf** the reports directory at the start of a run | `chunks/coverage.*.js` |

**The chain T5 would land**

1. Each shard job: `ng test --no-watch --coverage --configuration shard`, where that `angular.json`
   configuration sets a blob test reporter (`reporters: [["blob", {…}]]`) and uploads
   `.vitest-reports/*` as an artifact. The blob directory defaults to `.vitest-reports`.
2. One merge job, from `life-control-app-angular`, downloads every shard's blobs and merges **as a
   direct Vitest invocation** — the builder never runs `merge-reports` (`executor.js` always calls
   `startVitest('test', …)`):
   `npx vitest --config src/test-timeouts.ts --merge-reports=.vitest-reports --coverage --coverage.reporter=json-summary`
3. `node scripts/check-coverage.mjs` reads the **merged** `coverage/coverage-summary.json`, which is
   S5. The shard jobs must not run it: with `--coverage` each shard would write a partial summary,
   and a shared filesystem would also lose it to `clean()`'s `rm -rf`.

**Both hinges were resolved on 2026-10-19, locally, with real runs** — a fresh worktree, `npm ci`, and a
4-spec subset selected with the builder's own `include` option; the whole suite was never run:

- **`shard` IS honoured through the builder.** With `shard: '1/2'` in `src/test-timeouts.ts`, shard 1
  ran 2 files / 34 tests and shard 2 the other 2 / 35 — disjoint sets, 34 + 35 = 69 unsharded. The
  blob filename `blob-1-2.json` proves the value reaches the root config even though the builder folds
  the `test` object into `test.projects[0]`.
- **`ng test` rejects `--shard`**: `Error: Unknown argument: shard`, exit 1, and `schema.json` declares
  `additionalProperties: false` with no such option. The per-shard value can therefore only travel in
  the `runnerConfig`, read from an environment variable there — which is what the shipped slice does.
- **The merge needs `name`, and this is the correction that matters.** The command as first written in
  this section exits 1 with `No test files found` and reports 0 tests **while still writing a correct
  union `coverage/coverage-summary.json`** — the blobs record `projectName:
  'life-control-app-angular'` while a direct Vitest run's project is unnamed, so no spec resolves.
  Adding `name: 'life-control-app-angular'` to the `runnerConfig` makes the same command exit 0 and
  report every file and test. That the flag engages at all was proven separately: a missing directory
  gives `ENOENT scandir`.
- **`NG_TEST_LOG=2` does NOT print Vitest's resolved config** — it prints the builder's options and
  the `startVitest` argument object. The earlier claim in this section was wrong; the hinge is settled
  by the run counts and by the blob filenames, not by that log.
- **The blob reporter must not live in `src/test-timeouts.ts`**: the merge loads that same file and
  throws `Cannot merge reports when '--reporter=blob' is used`. It belongs to the shard invocation.
- **The D4 trap is real and was observed**: the second shard's coverage `clean()` wiped the first
  shard's `coverage/coverage-summary.json`.

**Fallback, not needed**: disjoint `include` sets per job — a real builder option, coarser than Vitest
sharding, would have been the answer had `shard` not survived the project wrapper.

**Deliberately not taken here**: moving the thresholds to `coverageThresholds` in `angular.json` would
enforce them **per shard** on partial maps, the exact defect S5 forbids. `scripts/check-coverage.mjs`
stays.

## Evidence log

| Date | Evidence |
| --- | --- |
| 2026-10-18 | Baseline step timings for all five workflows read from `gh run view <id> --json jobs` over the last ~10 runs each; recorded in the table under *Why this exists*. |
| 2026-10-18 | `show-sql` A/B on the dev host: `forks=1` 168s summed class time vs `forks=1` + `SPRING_JPA_SHOW_SQL=false` 169s (suite 922 suites / 3524 tests, read from `build/test-results/test/*.xml`). Hypothesis killed. |
| 2026-10-18 | `maxParallelForks` A/B on the dev host: 184s → 145s (`forks=4`, `maxHeapSize=1g`), summed class time 169s → 464s, effective parallelism 3.65. Local noise between identical serial runs: 169s then 184s (~9%). |
| 2026-10-18 | `runnerConfig` confirmed as a real `@angular/build` 21.2.27 option (published `schema.json`) and passed to Vitest as `config:` (`runners/vitest/executor.js:253`), so `test.shard` is reachable for U3. The local `node_modules` is 20.3.36 and predates it, which is why the local source could not confirm this. |
| 2026-10-18 | Issue #433 opened with the three required sections and the non-goals above. |
| 2026-10-18 | Configuration phase isolated with `--dry-run`: 8–11s warm without the cache, 10s storing the entry, 5–6s reusing it (`Configuration cache entry reused`) → **3–4s per invocation**. D1 was rewritten from this number and the configuration cache left U1. |
| 2026-10-18 | Pre-existing and untouched: `./gradlew` reports `Deprecated Gradle features were used in this build, making it incompatible with Gradle 10` **with and without** the configuration cache, so it belongs to the build, not to this slice. Recorded so nobody attributes it here. |
| 2026-10-18 | U1 committed as `4cbe362` (`.github/workflows/api-ci.yml`, `CONTRIBUTING.md`; 2 files, 32 insertions / 9 deletions, 41 authored changed lines). |
| 2026-10-18 | U1 verification on this tree: `bash .github/scripts/tests/workflow-trigger-parity.test.sh` exit 0, `25 passed, 0 failed`; independent parse with `yaml@2.9.0` reports jobs `static-analysis`, `test`, names `Static analysis`, `Test`, steps `Checkout>Setup Java>Static analysis` and `Checkout>Setup Java>Run tests>Upload test report`, with both `paths:` lists and `concurrency:` byte-identical to the base commit. |
| 2026-10-18 | U3 design verified read-only against the published `@angular/build@21.2.27` sources; the table under *U3 design* carries the `path` evidence. It corrects an earlier assumption of the parent's: `test.shard` as `{index, count}` in `runnerConfig` is **not** the mechanism — `shard` is a root-level string and a non-project option. |
| 2026-10-18 | Two U3 hinges remain unverified and need a runner: shard selection through the builder's project wrapper, and whether `ng test` forwards `--shard`. Recorded rather than inferred. |
| 2026-10-19 | **U1 measured on the runner** — pull request #435, run `39195746631`. `Test` and `Static analysis` started 1s apart (03:39:47 and 03:39:48), so they are genuinely concurrent; `Test` ran to 03:43:00 while `Static analysis` ended at 03:40:33. Workflow wall clock **193s** against the 247–336s baseline of six runs. `Test` job 193s (`Run tests` 182s), `Static analysis` job 45s (its step 35s). Both workflows' labels passed: `Exactly one type:* label` and `A linked, approved issue`. |
| 2026-10-19 | **All three post-split API CI measurements**: `564380c` 193s, `dbebc5e` 159s, `f16d7ae` on `main` **~215s** — every one of them below the *best* baseline run (247s), against a 247–336s baseline whose median is 266s. The `Test` job went 193 / 159 / 212s (baseline 186–221s) and `Static analysis` **45 / 57 / 61s** (baseline 43–98s), so **the static-analysis gate did not get faster**: the win is that the two steps no longer sum, which is the prediction D2 made before the first run. |
| 2026-10-19 | S4 verified from the same run: `Run tests` logs `> Task :test`, `BUILD SUCCESSFUL in 3m` and `5 actionable tasks: 5 executed` — nothing `UP-TO-DATE`, nothing `FROM-CACHE`. The `api-test-results` artifact's own report counts **3527 tests, 0 failures, 0 ignored**. |
| 2026-10-19 | **S4 corrected**: it read 3524, counted from this host's `build/test-results` on a tree older than the one the runner ran. The spec now carries the runner's number, and the 3524 was corrected openly rather than overwritten. |
| 2026-10-19 | **Delivery**: U1 landed in pull request **#435** (merge `f16d7ae`); the `CONTRIBUTING.md` §6 contract got its guard in pull request **#443** (`e461af7`); the Angular shards landed in pull request **#445** (`d396d3a`). |
| 2026-10-19 | **U3 measured on the runner** — pull request #445, run `39204601681`, sha `07f5dbb`: workflow wall clock **158s** against the 228–401s baseline (median 312s). `Lint & Build` 33s, `Unit tests (1)` 134s and `Unit tests (2)` 135s starting together, `Coverage` 22s. Counts: shard 1 **1885 tests / 91 files**, shard 2 **1756 / 91**, and the merge reports **3641 tests / 182 files** — the halves add up exactly. The verdict read the merged report only: `statements 86.33%`, `branches 84.70%`, `functions 80.18%`, `lines 88.96%`, all OK, and no shard runs `check-coverage.mjs`. |
| 2026-10-19 | **Two defects the first U3 run caught**, kept because the second is a shape worth recognising: (1) `ng build` failed with `TS2591: Cannot find name 'process'` — `tsconfig.app.json` includes `src/**/*.ts` and excludes only `*.spec.ts`, so the application program type-checks that module and has no `@types/node`; the shard is now read off `globalThis`. The real defect was the delegation brief, which omitted `npm run build` from its verification list. (2) `Upload shard report` reported `No files were found` while the shard log said the blob had been written there: `actions/upload-artifact` excludes hidden files by default (`include-hidden-files: false`, read from its own `action.yml` at v7.0.0) and `.vitest-reports` is hidden. The `if-no-files-found: error` is what turned a silently absent shard into a red job instead of a green verdict over half the suite. |
| 2026-10-19 | **GitHub evaluates a workflow's `paths:` against the whole pull-request diff, not the last push.** A commit touching only `odd/**` re-ran `api-ci` inside pull request #435. The documented gap — *"a records-only change runs no workflow"* — applies to a records-only **pull request**, never to a record commit inside a component pull request. |
| 2026-10-19 | **Open, not acted on**: `life-control-app-angular/AGENTS.md` states the current coverage line as `94.1/76.0/89.3/94.1` (measured 2026-09-24 @ `c49d296`), while CI now measures `86.33/84.70/80.18/88.96`. Not caused by this feature — on a 4-spec subset the merged totals were identical to the unsharded ones, and `branches` moved **up** 8.7 points while `lines` fell 5.1, the signature of code evolution rather than of a measurement change. The real margin on `lines` is 8.96 points, not the ~14–16 that file claims. Restating a target line is the owner's call. |
| 2026-10-19 | **U2 measured on the runner — the setting does not land.** The probe pull request (#448, draft, closed unmerged by design), run `39206276365`: the `test` job fanned into 8 cells on the same commit content — 4 `serial` and 4 with `maxParallelForks=4` and `maxHeapSize=1g` injected by an init script, so the production build stays clean. Gradle-only seconds around the `test` task: `serial` 270 / 250 / 237 / 244 (**median 247s**, spread 33s); `forks` 300 / 314 / **214** / 299 (**median 299.5s**, spread 100s). Job durations agree: median 261.5s against 312s. Equivalence held in every cell — **3527 tests, 0 failures, 0 ignored**, read from each cell's own report artifact, and no cell was `UP-TO-DATE` or `FROM-CACHE`. The dev-host measurement that motivated the issue said the opposite (1.27x faster), which is precisely why the number had to come from the runner: four forks on a 4 vCPU runner means four Spring contexts, four Testcontainers Postgres and four JVMs competing for four cores. **The methodological point worth keeping**: the fastest of the eight cells was a `forks` one (214s), so a single run — or a single configuration — would have "proven" the opposite conclusion |

## Log

L1. *"hay alguna forma de mejorar los tiempos de la ejecucion de los CI?"*

L2. *"solo estoy explorando, si ves algun beneficio de hacerlo y cual es el riesgo?"* — answered with
a per-change benefit/risk table; the two traps named there are now D4 (the coverage gate under
sharding) and D1 (the build cache changing what green proves).

L3. *"si quisiera hacer esos cambios, como empezamos?"*

L4. Scope decision: **"Las tres unidades"** (U1 API split + caches, U2 forks measurement, U3 Angular
shards) in one ODD record.

L5. Decision: **"Lo abro yo y lo aprobás vos"** — the agent opens the issue; `status:approved` stays a
maintainer action, and no pull request is opened before it carries the label.

L6. Decision: **"que recomiendas?"** on the Gradle cache → D1.

L7. Measurement correction, before U1 was committed: `--dry-run` put the configuration phase at
3–4s per invocation, and the split T1 introduces removes the second invocation that made the cache
worth storing. U1 became the split alone, with no `gradle.properties` and no Gradle build change.

L8. *"listo ya esta aprobado"* — `status:approved` applied on #433, so pull request #435 was opened
with `Closes #433` and the single `type:chore` label.

L9. Measurement return on the runner: the workflow wall clock is **193s** against the 247–336s
baseline of six runs. S4's test count was corrected from the runner's own report — it is 3527, not
the 3524 this record first copied from a local results directory on an older tree.

L10. Decision: **"Mergeá #445 y los que queden verdes"** — merge authority for this feature's remaining
pull requests, under the green-checks gate and the repository's only allowed merge type, and
explicitly **not** for direct pushes to `main`, force-push, or branch deletion. Under it, #445 merged
as `d396d3a`; this record's closure (#440) and U2 (#439) follow.

L11. Decision: **U2 does not land.** The runner contradicts the dev host — median 247s serial against
299.5s with forks, and a spread three times as wide — so `maxParallelForks` stays out of
`build.gradle`. The probe pull request #448 was closed unmerged, which was its stated intent, and
issue #439 closes with this measurement: exactly what that issue's own non-goals asked for.

L12. **Header repair, 2026-10-20.** The `**Status**:` line above ended with *"This header makes no
claim about live push or PR state; see the evidence log."* — a sentence that is false on its own
terms, because the same paragraph names three pull requests and three merge commits. `#436` measured
this exact shape and repaired it once already; `odd/tasks/odd-header-without-transport.md` turns that
finding into a rule, and `odd-status-headers.test.sh` now refuses a transport disclaimer beside
delivery evidence. Only the closing sentence was removed: the header still states its three
deliveries, the measurements, and U2's remaining work as issue #439. Nothing else in this record
changed.
