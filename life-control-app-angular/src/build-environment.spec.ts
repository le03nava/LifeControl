import runnerConfig from './test-timeouts';

/**
 * Source guard for the build environment the gate depends on.
 *
 * Why this exists: `npm run test:coverage:check` is two commands — the builder writes a
 * coverage summary and `scripts/check-coverage.mjs` reads it — and nothing in TypeScript
 * connects the two. The @angular/build 21 upgrade moved the summary to
 * `coverage/<projectName>/` (the builder's default `reportsDirectory`) while the script
 * kept reading `coverage/coverage-summary.json`, and the mismatch is invisible on any
 * machine that still holds the previous run's file: the local gate read the stale file
 * and passed while a clean checkout fails. `odd/tasks/angular-22-upgrade.md` L11 has the
 * measurement. The runner config therefore pins `coverage.reportsDirectory`, and this
 * guard fails if that pin or the path the script reads ever moves apart again.
 *
 * The filesystem is reached through `process.getBuiltinModule` and paths are resolved
 * from `process.cwd()`, exactly like `src/test-timeouts.spec.ts` and
 * `src/shared/constants/breakpoints.spec.ts`: a static `node:*` import cannot be
 * resolved by the browser-targeted unit-test bundle.
 */

const COVERAGE_SCRIPT = 'scripts/check-coverage.mjs';

function readProjectFile(path: string): string {
  const fs = process.getBuiltinModule('fs');
  if (!fs.existsSync(path)) {
    throw new Error(
      `Cannot read ${path} from the working directory ${process.cwd()}. ` +
        'Run the tests from the Angular project root, for example with `npm test`.',
    );
  }
  return fs.readFileSync(path, 'utf8');
}

interface CoverageOption {
  reportsDirectory?: string;
}

describe('build environment wiring', () => {
  it('writes the coverage summary where check-coverage.mjs reads it', () => {
    const coverage = (runnerConfig as { test?: { coverage?: CoverageOption } }).test?.coverage;

    expect(coverage?.reportsDirectory).toBe('coverage');
    expect(readProjectFile(COVERAGE_SCRIPT)).toContain('coverage/coverage-summary.json');
  });
});
