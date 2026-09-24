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

/**
 * Source guard: every media width condition under `src/` must name the mobile
 * boundary through a sanctioned expression.
 *
 * Three rules, all positive — nothing is rejected by scanning for operator
 * characters, because `$bp-mobile-max` itself contains hyphens and an operator
 * blacklist would flag it as arithmetic:
 *
 * 1. A width value is either a single Sass variable token (`$[a-zA-Z0-9_-]+`)
 *    or a plain pixel literal (`\d+(\.\d+)?px`). Arithmetic, `calc()`,
 *    multi-token values and bare numbers are rejected by construction.
 * 2. A pixel literal in the `575`/`576` neighbourhood re-declares the mobile
 *    boundary and is rejected. The off-scale `480px`, `767px` and `768px`
 *    literals are deliberately out of scope.
 * 3. `max-width` pairs with `$bp-mobile-max` and `min-width` pairs with
 *    `$bp-sm`; both crossed combinations are the mistakes this guards.
 *
 * Load-bearing mechanism details match `scssMobileMax` above: the filesystem is
 * reached through `process.getBuiltinModule` because a static `node:*` import
 * cannot be resolved by the browser-targeted unit-test bundle, and paths are
 * resolved from `process.cwd()` because `import.meta.url` points into the
 * throwaway `dist/test-out/<timestamp>-<uuid>/` directory the runner emits.
 */

/** Root of the Angular sources, relative to the project root. */
const SOURCES_ROOT = 'src';

/** The one non-spec module allowed to hold the query as a string literal. */
const BREAKPOINTS_CONSTANT = 'src/shared/constants/breakpoints.ts';

/** `@media` text from the keyword up to the `{` that opens its block. */
const MEDIA_BLOCK = /@media([^{]*)\{/g;

/** One `(min|max)-width: <value>` clause inside a media condition. */
const WIDTH_CLAUSE = /\((min|max)-width\s*:\s*([^)]+)\)/g;

/** A single Sass variable token, for example `$bp-mobile-max`. */
const VARIABLE_TOKEN = /^\$[a-zA-Z0-9_-]+$/;

/** A plain pixel literal, for example `576px` or `575.98px`. */
const PX_LITERAL = /^\d+(\.\d+)?px$/;

/** A pixel literal in the guarded `575`/`576` neighbourhood. */
const GUARDED_LITERAL = /^57[56](\.[0-9]+)?px$/;

/** A quoted TypeScript string carrying a media width query. */
const TS_QUERY_LITERAL = /(['"`])[^'"`\n]*\((?:max|min)-width[^'"`\n]*\1/g;

interface MediaViolation {
  readonly file: string;
  readonly line: number;
  readonly text: string;
  readonly reason: string;
}

/** 1-based line number of a character offset. */
function lineAt(content: string, index: number): number {
  return content.slice(0, index).split('\n').length;
}

/** Recursively lists every file under `dir`, as `dir`-relative paths. */
function listFiles(dir: string): string[] {
  const fs = process.getBuiltinModule('fs');
  const files: string[] = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const path = `${dir}/${entry.name}`;
    if (entry.isDirectory()) files.push(...listFiles(path));
    else if (entry.isFile()) files.push(path);
  }
  return files;
}

/** Applies the three rules to every media width clause in one SCSS file. */
function scanScss(content: string, file: string): MediaViolation[] {
  const violations: MediaViolation[] = [];
  for (const media of content.matchAll(MEDIA_BLOCK)) {
    // `media[1]` is the condition; `media.index` points at `@media`.
    const conditionStart = media.index + '@media'.length;
    for (const clause of media[1].matchAll(WIDTH_CLAUSE)) {
      const side = clause[1];
      const value = clause[2].trim();
      const line = lineAt(content, conditionStart + clause.index);
      const violation = { file, line, text: clause[0] };

      if (!VARIABLE_TOKEN.test(value) && !PX_LITERAL.test(value)) {
        violations.push({
          ...violation,
          reason: `width value "${value}" is neither a single variable token nor a plain px literal; arithmetic, calc() and multi-token values are not allowed`,
        });
        continue;
      }

      if (GUARDED_LITERAL.test(value)) {
        violations.push({
          ...violation,
          reason: `literal "${value}" re-declares the mobile boundary; use $bp-mobile-max`,
        });
        continue;
      }

      if (side === 'max' && value === '$bp-sm') {
        violations.push({
          ...violation,
          reason:
            'max-width with $bp-sm claims a boundary that belongs to the mobile maximum; use $bp-mobile-max',
        });
      }

      if (side === 'min' && value === '$bp-mobile-max') {
        violations.push({
          ...violation,
          reason: 'min-width with $bp-mobile-max is the reverse mistake; use $bp-sm',
        });
      }
    }
  }
  return violations;
}

/** Rejects hardcoded media queries in TypeScript, outside the exemptions. */
function scanTs(content: string, file: string): MediaViolation[] {
  const violations: MediaViolation[] = [];
  for (const match of content.matchAll(TS_QUERY_LITERAL)) {
    violations.push({
      file,
      line: lineAt(content, match.index),
      text: match[0],
      reason:
        'media width query hardcoded as a string literal; use MOBILE_MAX_WIDTH_QUERY or the SCSS breakpoint variables',
    });
  }
  return violations;
}

/** Walks `src/` and collects every violation, deterministically ordered. */
function scanSources(): MediaViolation[] {
  const fs = process.getBuiltinModule('fs');
  if (!fs.existsSync(SOURCES_ROOT)) {
    throw new Error(
      `Cannot scan ${SOURCES_ROOT}/ from the working directory ${process.cwd()}. ` +
        'Run the tests from the Angular project root, for example with `npm test`.',
    );
  }

  const violations: MediaViolation[] = [];
  for (const file of listFiles(SOURCES_ROOT).sort()) {
    if (file.endsWith('.scss')) {
      violations.push(...scanScss(fs.readFileSync(file, 'utf8'), file));
    } else if (
      file.endsWith('.ts') &&
      !file.endsWith('.spec.ts') &&
      file !== BREAKPOINTS_CONSTANT
    ) {
      violations.push(...scanTs(fs.readFileSync(file, 'utf8'), file));
    }
  }
  return violations;
}

/** Renders every violation as `file:line  text — reason` for the failure message. */
function formatViolations(violations: MediaViolation[]): string {
  return [
    `Found ${violations.length} media breakpoint violation(s):`,
    ...violations.map(
      (violation) =>
        `  ${violation.file}:${violation.line}  ${violation.text} — ${violation.reason}`,
    ),
  ].join('\n');
}

describe('breakpoint source guard', () => {
  it('rejects media width expressions that contradict the mobile breakpoint contract', () => {
    const violations = scanSources();
    expect(violations, formatViolations(violations)).toEqual([]);
  });
});
