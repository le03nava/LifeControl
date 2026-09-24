import { MOBILE_MAX_WIDTH_QUERY } from './breakpoints';

/** Path of the SCSS file that owns the mobile maximum, from the project root. */
const SCSS_SOURCE = 'src/shared/styles/_variables.scss';

/**
 * Reads `$bp-mobile-max` from the SCSS source of truth, or throws.
 *
 * Two details of the mechanism are load-bearing:
 *
 * - The file is read through the Node builtin registry instead of a static
 *   `node:fs` import, because this project's `@angular/build:unit-test` runner
 *   bundles the specs for the browser platform, where a static `node:*` import
 *   fails to resolve at build time (`Could not resolve "node:fs"`).
 * - The path is resolved from `process.cwd()`, which is the Angular project root
 *   under `npm test`. `import.meta.url` is not usable here: the runner emits the
 *   spec bundles under `dist/test-out/<timestamp>-<uuid>/`, so a URL-relative
 *   path would resolve inside that throwaway directory instead of `src/`.
 */
function scssMobileMax(): string {
  const fs = process.getBuiltinModule('fs');
  if (!fs.existsSync(SCSS_SOURCE)) {
    throw new Error(
      `Cannot read ${SCSS_SOURCE} from the working directory ${process.cwd()}. ` +
        'Run the tests from the Angular project root, for example with `npm test`.',
    );
  }
  const match = fs.readFileSync(SCSS_SOURCE, 'utf8').match(/\$bp-mobile-max:\s*([\d.]+px)\s*;/);
  if (!match) throw new Error(`$bp-mobile-max not found in ${SCSS_SOURCE}`);
  return match[1];
}

describe('MOBILE_MAX_WIDTH_QUERY', () => {
  it('matches the mobile max width declared in the SCSS source of truth', () => {
    expect(MOBILE_MAX_WIDTH_QUERY).toBe(`(max-width: ${scssMobileMax()})`);
  });

  it('encodes the mobile band maximum as 575.98px', () => {
    expect(scssMobileMax()).toBe('575.98px');
  });
});
