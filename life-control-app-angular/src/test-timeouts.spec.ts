import { HOOK_TIMEOUT_MS, MAX_WORKERS, TEST_TIMEOUT_MS } from './test-timeouts';

/**
 * Source guard for the per-test and per-hook budgets that `src/test-setup.ts`
 * installs into Vitest.
 *
 * Why this exists: those numbers are the only thing standing between this suite
 * and a flaky gate, because Vitest's 5000ms default is just 1.72x the slowest test
 * measured on an *idle* host (2909ms out of 2529 tests, coverage-free). Nothing
 * else in the repository breaks if the configuration is quietly removed or
 * lowered, so the regression would be silent: the gate starts failing under
 * contention again and the next person re-investigates from scratch. The
 * measurements and the full reasoning are in `odd/tasks/test-timeout-headroom.md`.
 *
 * Three groups of assertions for the budgets, which fail for different reasons:
 *
 * - The **values** are imported from `./test-timeouts` (side-effect free, which is
 *   why they are not read from the setup module) and compared against the reviewed
 *   floor, so lowering either one trips the guard. This is a floor rather than an
 *   equality on purpose (decision D5): raising the budget in a later slice must not
 *   require a guard fight, while lowering it must require deleting an assertion
 *   that names this record.
 * - The **wiring** is read as text and matched against source with comments AND
 *   string literals stripped, to confirm the setup module still passes those
 *   constants to `vi.setConfig`.
 *
 *   Stripping is load-bearing, not tidiness, because a first round of mutation
 *   testing defeated the raw-text version twice over: a mutant that merely
 *   *commented out* the call stayed green (a comment still contains the call), and
 *   a mutant that replaced the call with a *string literal* spelling the same text
 *   stayed green too. A guard whose job is to notice that a line stopped being
 *   executed has to look at executable text.
 *
 * - The **numeric ban** closes a third hole found by the same round: appending a
 *   second `vi.setConfig({ testTimeout: 5_000 })` after the real call left every
 *   positive assertion satisfied while Vitest's later call won, so a hardcoded
 *   number anywhere in this module is now rejected outright, not just inside the
 *   first call. This is what lets the guard reject "someone added a second
 *   override" without counting calls and forbidding legitimate future ones.
 *
 *   The stripper is still an approximation, not a parser (`//` or a quote inside a
 *   string literal can fool it, and an escaped-quote corner case exists). It
 *   defeats removal, commenting-out, string decoration and numeric hardcoding --
 *   the realistic regressions -- not a determined adversary hand-crafting source to
 *   satisfy a regex, against which no regex guard can win.
 *
 * A fourth group covers the **runner concurrency cap** (`MAX_WORKERS`), added by
 * `odd/tasks/unit-test-load-independence.md`: a ceiling on the value, the same
 * wiring assertion against `src/test-timeouts.ts` instead of the setup module, the
 * same numeric ban, and a refusal of any `retry` option -- a retry would turn a
 * loaded host green by hiding the failure the gate exists to report.
 *
 * **What this guard cannot do, stated so it is not mistaken for proof**: it shows
 * the configuration is present and wired, not that Vitest honours it. That second
 * claim is falsifiable only by a test body that outlives the default, which was
 * done by hand when this slice was built (T1/T4 in the record): a 6000ms body fails
 * with `Test timed out in 5000ms` before the change and passes after it, in a
 * separate spec file -- which is also the evidence that the setting reaches beyond
 * the setup module. Repeating that permanently would cost 6s on every run to
 * re-prove a property that only changes when these two files change. The worker cap
 * has the same limit and was proved the same way, by counting live runner processes
 * during a run instead of reading a log (record, L3).
 *
 * Deliberately NOT guarded: the `setupFiles` wiring in `angular.json`. Dropping
 * `src/test-setup.ts` from it removes the `matchMedia` mock and the `vi.setConfig`
 * wiring at once, so it fails thousands of tests loudly and cannot regress silently.
 *
 * Load-bearing mechanism details match `src/shared/constants/breakpoints.spec.ts`:
 * the filesystem is reached through `process.getBuiltinModule` because a static
 * `node:*` import cannot be resolved by the browser-targeted unit-test bundle, and
 * the path is resolved from `process.cwd()` because `import.meta.url` points into
 * the throwaway `dist/test-out/<timestamp>-<uuid>/` directory the runner emits.
 */

/** The reviewed floor for both budgets, in milliseconds. See D1/D2/D5. */
const REVIEWED_FLOOR_MS = 20_000;

/**
 * The largest number of concurrent runners the reviewed measurement left green, on
 * the 8-core host of `odd/tasks/unit-test-load-independence.md`. A ceiling rather
 * than an equality for the same reason as the millisecond floor: lowering the cap
 * is a cost, not a risk, and must not require a guard fight -- while raising it past
 * the measured value must require deleting an assertion that names the record.
 */
const REVIEWED_WORKER_CEILING = 2;

/** The setup module whose wiring this guard protects. */
const SETUP_SOURCE = 'src/test-setup.ts';

/** The module that declares the runner configuration the builder merges. */
const CONFIG_SOURCE = 'src/test-timeouts.ts';

/** Reads one of this suite's own source modules as text, or throws with the reason. */
function readSource(path: string): string {
  const fs = process.getBuiltinModule('fs');
  if (!fs.existsSync(path)) {
    throw new Error(
      `Cannot read ${path} from the working directory ${process.cwd()}. ` +
        'Run the tests from the Angular project root, for example with `npm test`.',
    );
  }
  return fs.readFileSync(path, 'utf8');
}

/**
 * Removes block comments, line comments and string literals so that commented-out
 * or merely quoted text cannot satisfy the wiring assertions, and so the numeric
 * assertions below cannot be fooled by a number inside a comment.
 *
 * Order matters: comments are stripped first, because the doc comments in this
 * module contain apostrophes (`Vitest's`) that would otherwise open a bogus string
 * literal and swallow the following code. See the "stripping is load-bearing" note
 * in the file docstring for what this does and does not defeat.
 */
function stripNonCode(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:])\/\/.*$/gm, '$1')
    .replace(/'(?:[^'\\]|\\.)*'/g, "''")
    .replace(/"(?:[^"\\]|\\.)*"/g, '""')
    .replace(/`(?:[^`\\]|\\.)*`/g, '``');
}

describe('test timeouts', () => {
  it('keeps the per-test budget at or above the reviewed floor', () => {
    expect(TEST_TIMEOUT_MS).toBeGreaterThanOrEqual(REVIEWED_FLOOR_MS);
  });

  it('keeps the per-hook budget at or above the reviewed floor', () => {
    expect(HOOK_TIMEOUT_MS).toBeGreaterThanOrEqual(REVIEWED_FLOOR_MS);
  });

  it('wires both budgets into vi.setConfig from the shared constants', () => {
    const source = stripNonCode(readSource(SETUP_SOURCE));
    expect(source).toMatch(/vi\.setConfig\(/);
    expect(source).toMatch(/testTimeout:\s*TEST_TIMEOUT_MS/);
    expect(source).toMatch(/hookTimeout:\s*HOOK_TIMEOUT_MS/);
  });

  it('never hardcodes a numeric timeout that could override the constants', () => {
    // A later call wins over an earlier one, so a second override with a literal
    // would silently drop the suite back to a 5s budget. Forbidding numeric
    // timeout values anywhere in the module closes that hole without needing to
    // count calls and reject legitimate future ones.
    const source = stripNonCode(readSource(SETUP_SOURCE));
    expect(source).not.toMatch(/testTimeout:\s*[\d_]+/);
    expect(source).not.toMatch(/hookTimeout:\s*[\d_]+/);
  });
});

describe('runner concurrency', () => {
  it('leaves at least one worker so the suite can run at all', () => {
    expect(MAX_WORKERS).toBeGreaterThanOrEqual(1);
  });

  it('never exceeds the worker count the loaded-host measurement left green', () => {
    expect(MAX_WORKERS).toBeLessThanOrEqual(REVIEWED_WORKER_CEILING);
  });

  it('hands the cap to the runner configuration from the shared constant', () => {
    // `@angular/build:unit-test` merges this module's `test` object into the Vitest
    // project as-is (`plugins.js`: `mergeConfig(projectDefaults, { test: testConfig })`),
    // and Vitest resolves the pool size from `project.config.maxWorkers`
    // (`cli-api` `resolveMaxWorkers`), so a cap declared here reaches the pool. This
    // guard only proves the wiring is present; the record proves it is honoured.
    const source = stripNonCode(readSource(CONFIG_SOURCE));
    expect(source).toMatch(/maxWorkers:\s*MAX_WORKERS/);
  });

  it('never hardcodes a numeric worker count', () => {
    const source = stripNonCode(readSource(CONFIG_SOURCE));
    expect(source).not.toMatch(/maxWorkers:\s*[\d_]+/);
    expect(source).not.toMatch(/minWorkers:\s*[\d_]+/);
  });

  it('never asks the runner to retry a failed test', () => {
    // A retry would make a loaded host green by hiding the failure it is supposed to
    // report, which is the one fix issue #971 rules out by name.
    const source = stripNonCode(readSource(CONFIG_SOURCE));
    expect(source).not.toMatch(/\bretry:/);
  });
});
