import { HOOK_TIMEOUT_MS, TEST_TIMEOUT_MS } from './test-timeouts';

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
 * Three groups of assertions, which fail for different reasons:
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
 * **What this guard cannot do, stated so it is not mistaken for proof**: it shows
 * the configuration is present and wired, not that Vitest honours it. That second
 * claim is falsifiable only by a test body that outlives the default, which was
 * done by hand when this slice was built (T1/T4 in the record): a 6000ms body fails
 * with `Test timed out in 5000ms` before the change and passes after it, in a
 * separate spec file -- which is also the evidence that the setting reaches beyond
 * the setup module. Repeating that permanently would cost 6s on every run to
 * re-prove a property that only changes when these two files change.
 *
 * Deliberately NOT guarded: the `setupFiles` wiring in `angular.json`. Dropping
 * `src/test-setup.ts` from it removes `zone.js`, the `TestBed` environment and the
 * `matchMedia` mock at once, so it fails thousands of tests loudly and cannot
 * regress silently.
 *
 * Load-bearing mechanism details match `src/shared/constants/breakpoints.spec.ts`:
 * the filesystem is reached through `process.getBuiltinModule` because a static
 * `node:*` import cannot be resolved by the browser-targeted unit-test bundle, and
 * the path is resolved from `process.cwd()` because `import.meta.url` points into
 * the throwaway `dist/test-out/<timestamp>-<uuid>/` directory the runner emits.
 */

/** The reviewed floor for both budgets, in milliseconds. See D1/D2/D5. */
const REVIEWED_FLOOR_MS = 20_000;

/** The setup module whose wiring this guard protects. */
const SETUP_SOURCE = 'src/test-setup.ts';

/** Reads the setup module as text, or throws with the reason it could not. */
function readSetupSource(): string {
  const fs = process.getBuiltinModule('fs');
  if (!fs.existsSync(SETUP_SOURCE)) {
    throw new Error(
      `Cannot read ${SETUP_SOURCE} from the working directory ${process.cwd()}. ` +
        'Run the tests from the Angular project root, for example with `npm test`.',
    );
  }
  return fs.readFileSync(SETUP_SOURCE, 'utf8');
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
    const source = stripNonCode(readSetupSource());
    expect(source).toMatch(/vi\.setConfig\(/);
    expect(source).toMatch(/testTimeout:\s*TEST_TIMEOUT_MS/);
    expect(source).toMatch(/hookTimeout:\s*HOOK_TIMEOUT_MS/);
  });

  it('never hardcodes a numeric timeout that could override the constants', () => {
    // A later call wins over an earlier one, so a second override with a literal
    // would silently drop the suite back to a 5s budget. Forbidding numeric
    // timeout values anywhere in the module closes that hole without needing to
    // count calls and reject legitimate future ones.
    const source = stripNonCode(readSetupSource());
    expect(source).not.toMatch(/testTimeout:\s*[\d_]+/);
    expect(source).not.toMatch(/hookTimeout:\s*[\d_]+/);
  });
});
