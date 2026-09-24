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

/**
 * `@media` text from the keyword up to the `{` that opens its block.
 *
 * The condition may itself contain an interpolation group (`#{…}`), which
 * carries a `{`. A plain `[^{]*` stops at that brace and hides the whole
 * condition — including its `(max-width: …)` clause — from the scan. The
 * alternation admits `#{…}` as one unit without mistaking its brace for the
 * block opener, so `@media (max-width: #{$bp-sm})` still reaches rule 1.
 *
 * The interpolation alternative must come FIRST: `[^{]` happily matches the
 * `#`, after which the leftover `{` is read as the block opener and the
 * condition is truncated again. (The shape `(?:[^{]|#\{[^}]*\})*` suggested
 * in review has exactly that bug; measured, it captures ` (max-width: #`.)
 */
const MEDIA_BLOCK = /@media((?:#\{[^}]*\}|[^{])*)\{/g;

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

/**
 * Blanks SCSS comments so commented-out queries are never scanned.
 *
 * Length and newline positions are preserved exactly: every comment character
 * except `\n` becomes a space, and a line comment contributes no newline. That
 * keeps every later character offset identical to the original file, so
 * `lineAt(content, …)` still reports the real file's line.
 *
 * A `//` immediately preceded by `:` is left intact so `url(http://…)` is not
 * mistaken for a comment. Residual hole: a protocol-relative `url(//…)` would
 * still be stripped; no such URL exists in this app's SCSS today.
 */
const BLOCK_COMMENT = /\/\*[\s\S]*?\*\//g;
const LINE_COMMENT = /(?<!:)\/\/[^\n]*/g;

function stripComments(content: string): string {
  return content
    .replace(BLOCK_COMMENT, (comment) => comment.replace(/[^\n]/g, ' '))
    .replace(LINE_COMMENT, (comment) => ' '.repeat(comment.length));
}

/** Applies the three rules to every media width clause in one SCSS file. */
function scanScss(content: string, file: string): MediaViolation[] {
  const source = stripComments(content);
  const violations: MediaViolation[] = [];
  for (const media of source.matchAll(MEDIA_BLOCK)) {
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
  // Comments are blanked for the same reason as in `scanScss`: documenting a
  // removed query in a comment is the natural thing to do after this change,
  // and it must not read as the defect itself. `stripComments` preserves both
  // length and newlines, so `lineAt(content, …)` still reports the real line.
  for (const match of stripComments(content).matchAll(TS_QUERY_LITERAL)) {
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

describe('breakpoint source guard scan', () => {
  it('catches a media width value written as interpolation', () => {
    const violations = scanScss(
      '@media (max-width: #{$bp-sm}) { .a { color: red; } }',
      'probe.scss',
    );
    expect(
      violations,
      `expected exactly one violation, got ${violations.length}: ${formatViolations(violations)}`,
    ).toHaveLength(1);
  });

  it('does not flag a width query that appears only inside a line comment', () => {
    const violations = scanScss(
      '// replaced @media (max-width: $bp-sm)\n.a { color: red; }',
      'probe.scss',
    );
    expect(violations).toEqual([]);
  });

  it('keeps original line numbers when a block comment spans several lines', () => {
    const content = [
      '/* line 1',
      '   @media (max-width: 576px) {',
      '   line 3 */',
      '.line-four { color: blue; }',
      '@media (max-width: 576px) { .a { color: red; } }',
    ].join('\n');
    const violations = scanScss(content, 'probe.scss');
    expect(violations).toHaveLength(1);
    expect(violations[0].line).toBe(5);
  });

  // The five falsification controls that were applied to the guard as external
  // scratch files, expressed directly against the pure scanner so they run with
  // no filesystem writes. Each must report exactly one violation.
  it.each([
    [
      'a bare literal re-declaring the mobile boundary',
      '@media (max-width: 576px) { .a { color: red; } }',
    ],
    ['max-width naming the small tier', '@media (max-width: $bp-sm) { .a { color: red; } }'],
    [
      'min-width naming the mobile maximum',
      '@media (min-width: $bp-mobile-max) { .a { color: red; } }',
    ],
    ['arithmetic in the width value', '@media (max-width: ($bp-sm - 1px)) { .a { color: red; } }'],
  ])('catches %s (external probe shape)', (_label, content) => {
    const violations = scanScss(content, 'probe.scss');
    expect(violations, formatViolations(violations)).toHaveLength(1);
  });

  it('catches a hardcoded width query in a TypeScript literal (external probe shape)', () => {
    const violations = scanTs('function f() { return "(min-width: 768px)"; }', 'probe.ts');
    expect(violations, formatViolations(violations)).toHaveLength(1);
  });

  it('does not flag a width query that appears only inside a TypeScript comment', () => {
    const violations = scanTs(
      "// matchMedia('(min-width: 768px)') was replaced by the shared constant\nconst x = 1;\n",
      'probe.ts',
    );
    expect(violations).toEqual([]);
  });
});
