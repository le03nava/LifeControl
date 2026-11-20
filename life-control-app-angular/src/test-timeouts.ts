/**
 * Per-test and per-hook budget for the unit-test runner, in milliseconds.
 *
 * Why these live in their own module instead of inside `src/test-setup.ts`:
 * `@angular/build:unit-test` bundles `setupFiles` separately from the specs (see
 * the generated `init-testbed.js` entry), so a spec that imports the setup module
 * gets a *second* evaluation of a module with real side effects and dies with
 * `NG0400: A platform with a different configuration has been created`. Keeping
 * the values in a side-effect-free module lets `src/test-timeouts.spec.ts` guard
 * the real constants while `src/test-setup.ts` does the wiring.
 *
 * The numbers, and why they are not Vitest's defaults: measured on an idle host
 * (load 0.69, 20 cores), the slowest of 2529 tests takes **2909ms** -- 58% of
 * Vitest's 5000ms default. A 1.72x slowdown is therefore enough to turn a merely
 * slow test into a red gate, and the measured durations are coverage-free while
 * the CI gate runs with coverage instrumentation, so 1.72x is an upper bound on
 * the margin rather than a lower bound. That is a watchdog firing on contention,
 * not a defect being caught, and it is how this suite earned its reputation for
 * flaky failures.
 *
 * 20000ms is 6.9x the worst observed test: room for a genuinely loaded machine,
 * while a real hang still surfaces inside 20s. `HOOK_TIMEOUT_MS` is raised with
 * it on purpose -- leaving it at Vitest's 10000ms default would redirect the same
 * contention into a `beforeEach`/`afterEach` failure, and the TestBed build and
 * teardown in this suite is where most of the per-test work actually happens.
 *
 * Measurements and reasoning: `odd/tasks/test-timeout-headroom.md`.
 * Guard: `src/test-timeouts.spec.ts`.
 */

/** Per-test budget. Vitest's default is 5000ms; see the module doc above. */
export const TEST_TIMEOUT_MS = 20_000;

/** Per-hook budget. Vitest's default is 10000ms; raised with the test budget. */
export const HOOK_TIMEOUT_MS = 20_000;

/**
 * How many runner processes the suite may use at once, at any core count.
 *
 * The budgets above are not what fails under load. Issue #971 measured that: the same
 * commit, the same host, 20 000ms budgets in every run, and the verdict flipped on load
 * alone -- `Test timed out in 20000ms`, `Hook timed out in 20000ms`, zero assertion
 * failures, and the same six files passing 448/448 when re-run alone. What changed was
 * how many runners were competing for the host, so that is what this constant bounds.
 *
 * Why the runner's own default is the defect: Vitest sizes the pool from the CPU count
 * (`resolveMaxWorkers` in `vitest/dist/chunks/cli-api`: `availableParallelism() - 1`), so
 * on this 8-core host it runs **7** forks. Each one is a whole Node process holding the
 * compiled Angular test bundle, a jsdom document and the coverage instrumentation --
 * measured at roughly 280MB resident per fork here. Seven of those do not fit beside the
 * rest of a development host, and the runner does not notice: it has no memory budget, so
 * the oversubscription shows up as swap, and a starved timer fires far past its 20s (the
 * reproduction suite logged single tests at 90s, 405s and one *file* at 522s). Raising the
 * budget cannot fix that -- a timer that cannot be scheduled does not honour a larger
 * number either -- which is why `odd/tasks/test-timeout-headroom.md` raised it and this
 * unit does not.
 *
 * 2 is the value a loaded host left green: measured in issue #971 with the full coverage
 * gate on a tree that was red at the default pool size, at a load average between 3.3x and
 * 4.6x `nproc`, 215 files and 4407 tests, every coverage threshold OK. It is a *fixed*
 * number rather than a fraction of the core count on purpose -- a fraction is the bug: more
 * cores would mean more forks, and the resource that runs out first is memory, not cores.
 *
 * The cost is stated rather than hidden: fewer workers means a longer wall clock on a host
 * that has the resources for more, and the measured idle pair is in the record
 * (`odd/tasks/unit-test-load-independence.md`). The guard is `src/test-timeouts.spec.ts`,
 * which fails if this number is raised past the measured ceiling, if the wiring disappears,
 * or if a `retry` ever appears here -- a retry would make a loaded host green by hiding the
 * failure it exists to report.
 */
export const MAX_WORKERS = 2;

/**
 * The same budgets, exposed as the Vitest configuration object that `angular.json`
 * hands to the runner through `runnerConfig`, plus the per-file isolation the suite
 * needs under Vitest 4.
 *
 * `name` is the Vitest project identifier, and it is here for one consumer: the
 * `vitest --merge-reports` run that recombines CI's shard blobs. That command loads this
 * module directly, without the builder, and with no `name` it exits 1 with `No test files
 * found` (measured) while, per the probe that shaped this change, still writing a
 * correct-looking union `coverage/coverage-summary.json` — the silent shape that would let the
 * coverage verdict pass on a report nobody produced. The builder overwrites the name with its
 * project name for the sharded run itself, so the only effect of the line is on the merge.
 *
 * The budgets belong in the base config, not only in `vi.setConfig`: the builder's
 * default `isolate: false` evaluates the setup module once per worker and Vitest 4
 * calls `vi.resetConfig()` after every file, so a `vi.setConfig` override only reaches
 * the first file a worker runs (measured: 5 of 24 identical 6s specs fell back to the
 * 5000ms default). The base config also reaches the builder's own cleanup hooks, which
 * are registered before `src/test-setup.ts` runs. Keeping `vi.setConfig` as well is
 * harmless; `src/test-timeouts.spec.ts` guards that wiring.
 *
 * `isolate: true` overrides the builder's `isolate: false`: sharing one jsdom
 * environment across the files a worker runs leaked `window.history` (the three
 * `*-edit` specs that call `window.history.replaceState` in `beforeEach` failed as a
 * whole file when it happened). A fresh environment per file removes the shared state.
 *
 * `coverage.reportsDirectory` is pinned to `coverage` because the builder's default is
 * `coverage/<projectName>` — under @angular/build 21 that moved the summary to
 * `coverage/life-control-app-angular/coverage-summary.json` while
 * `scripts/check-coverage.mjs` and the CI artifact path both read `coverage/`. The
 * failure is invisible on a machine that still holds the previous run's file there,
 * and fatal on a clean checkout. `src/build-environment.spec.ts` guards the hand-off.
 *
 * `maxWorkers` is the pool cap, and it belongs in this object for the same reason the
 * budgets do: the builder merges this `test` object into the Vitest project
 * (`@angular/build/src/builders/unit-test/runners/vitest/plugins.js`:
 * `mergeConfig(projectDefaults, { test: testConfig })`) and overrides nothing about the
 * pool, so a cap declared here is what the pool sizes itself from. Its value, and the
 * measurement that chose it, are documented on `MAX_WORKERS`.
 */

/**
 * The shard this run should execute, as Vitest's `"<index>/<count>"`, or `undefined` for the
 * whole suite.
 *
 * Why the shard is read from the environment instead of the command line: `ng test` rejects
 * `--shard` (`Error: Unknown argument: shard`) and `angular.json` declares no such option, so
 * `runnerConfig` is the only channel that reaches Vitest — the builder merges this `test`
 * object into its own project config, and a `test.shard` set here survives that merge
 * (measured on a fixed 4-file subset: `1/2` ran 26 tests over 2 files, `2/2` the other 15 over
 * the other 2, and 41 unsharded — disjoint sets that add up).
 *
 * Opt-in, and empty reads as absent: a plain local `ng test` sets nothing and still runs every
 * spec, while `VITEST_SHARD=` from a workflow with an unset matrix value falls back to the full
 * suite instead of handing Vitest a value it would have to reject.
 */
function readShard(): string | undefined {
  // Not `process.env` directly: `tsconfig.app.json` includes `src/**/*.ts` and excludes only
  // `*.spec.ts`, so the application build type-checks this module too — and that program has no
  // `@types/node`, where a bare `process` fails `ng build` with `TS2591: Cannot find name
  // 'process'` (measured on the first run of pull request #445). Reading it off `globalThis` keeps
  // one module compilable by both programs without pulling node types into application code.
  const env = (globalThis as { process?: { env?: Record<string, string | undefined> } }).process
    ?.env;

  return env?.['VITEST_SHARD']?.trim() || undefined;
}

const SHARD = readShard();

export default {
  test: {
    name: 'life-control-app-angular',
    testTimeout: TEST_TIMEOUT_MS,
    hookTimeout: HOOK_TIMEOUT_MS,
    maxWorkers: MAX_WORKERS,
    isolate: true,
    // Vitest 5 enables `clearMocks` by default, and the cost is not constant: `vi.clearAllMocks()`
    // runs before every test over the module's whole mock registry, and the two largest specs here
    // (`product-edit`, `purchase-order-edit`, 82 tests each) build their mocks per test. Measured on
    // v5.1.1: shard 2/2 took 276s against 91.56s on v4 in CI, and those two files alone accounted for
    // 290s, roughly 10x their v4 cost, which is what pushed one test per file past the 20s budget
    // (`TestTimeoutError` / `HookTimeoutError`, a different test each run).
    //
    // It buys nothing here: v5.1.1 with `clearMocks` on failed 22 tests, all of them from the
    // `globalThis.history` assignment change, and none from mock-history carry-over. Keeping the v4
    // default is the deliberate choice, not an oversight.
    clearMocks: false,
    coverage: {
      reportsDirectory: 'coverage',
    },
    ...(SHARD ? { shard: SHARD } : {}),
  },
};
