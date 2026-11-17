import { REGIMEN_FISCAL_CODES, REGIMEN_FISCAL_OPTIONS } from './regimen-fiscal';

/**
 * Path of the enum that owns the vocabulary, from the Angular project root.
 *
 * The catalog is the authority's, and the API is the only place this tree states it (`RegimenFiscal`,
 * mirrored by `ck_companies_regimen_fiscal` in `V102`). The app needs the same list to paint a select and
 * has no HTTP reader for it: adding an endpoint would be a new public contract, which issue #931 excludes
 * as a non-goal ("Changing the API's contracts: the fields already exist"). So the list is duplicated here
 * and this spec is the thing that keeps the two in step — the option #931's own criterion allows ("or the
 * duplication is stated with the test that keeps the two in step").
 */
const JAVA_SOURCE =
  '../life-control-api/src/main/java/com/lifecontrol/api/fiscal/model/RegimenFiscal.java';

/**
 * Reads the enum from disk, or throws.
 *
 * Two details of the mechanism are load-bearing, and both were measured in
 * `src/shared/constants/breakpoints.spec.ts`:
 *
 * - The file is read through the Node builtin registry instead of a static `node:fs` import, because
 *   `@angular/build:unit-test` bundles the specs for the browser platform, where a static `node:*` import
 *   fails to resolve at build time.
 * - The path resolves from `process.cwd()`, which is the Angular project root under `npm test`;
 *   `import.meta.url` would resolve inside `dist/test-out/<timestamp>-<uuid>/` instead of `src/`.
 */
function javaSource(): string {
  const fs = process.getBuiltinModule('fs');
  if (!fs.existsSync(JAVA_SOURCE)) {
    throw new Error(
      `Cannot read ${JAVA_SOURCE} from the working directory ${process.cwd()}. ` +
        'Run the tests from the Angular project root, for example with `npm test`.',
    );
  }
  return fs.readFileSync(JAVA_SOURCE, 'utf8');
}

/**
 * The codes the enum declares, in declaration order.
 *
 * The pattern is deliberately narrow: enum constants are the only lines that begin with exactly four
 * spaces, an upper-case identifier and a three-digit string in parentheses, so the constructor, the
 * javadoc examples and `code()` cannot be mistaken for one.
 */
function javaCodes(source: string): string[] {
  return Array.from(
    source.matchAll(/^ {4}[A-Z][A-Z0-9_]*\("(\d{3})"\)[,;]$/gm),
    (match) => match[1] as string,
  );
}

/**
 * The description the enum's javadoc states for each code — the authority's own wording, which is what
 * the select renders. `{@code 601} — General de Ley Personas Morales.` is the shape.
 */
function javaLabels(source: string): Record<string, string> {
  const labels: Record<string, string> = {};
  for (const match of source.matchAll(/\{@code (\d{3})\} — (.+?)\.\s*\*\//g)) {
    labels[match[1] as string] = match[2] as string;
  }
  return labels;
}

describe('regimen fiscal vocabulary', () => {
  const source = javaSource();

  it('declares exactly the codes the API enum declares, in its order', () => {
    const expected = javaCodes(source);

    // A guard on the reader itself: if the regex stopped matching, both sides would agree on an empty
    // list and the parity would pass for the wrong reason.
    expect(expected.length).toBeGreaterThan(0);
    expect(REGIMEN_FISCAL_CODES).toEqual(expected);
  });

  it('declares nineteen codes, each exactly once', () => {
    expect(REGIMEN_FISCAL_CODES).toHaveLength(19);
    expect(new Set(REGIMEN_FISCAL_CODES).size).toBe(19);
  });

  it('takes every label from the enum description instead of inventing one', () => {
    const expected = javaLabels(source);

    for (const option of REGIMEN_FISCAL_OPTIONS) {
      expect(expected[option.code]).toBeDefined();
      expect(option.label).toBe(expected[option.code]);
    }
  });
});
