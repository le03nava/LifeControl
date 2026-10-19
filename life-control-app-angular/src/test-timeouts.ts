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
    isolate: true,
    coverage: {
      reportsDirectory: 'coverage',
    },
    ...(SHARD ? { shard: SHARD } : {}),
  },
};
