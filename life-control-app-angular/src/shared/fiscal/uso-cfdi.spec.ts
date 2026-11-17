import { USO_CFDI_CODES, USO_CFDI_OPTIONS } from './uso-cfdi';

/**
 * Path of the enum that owns the vocabulary, from the Angular project root.
 *
 * The catalog is the authority's, and the API is the only place this tree states it (`UsoCfdi`, mirrored by
 * `ck_customers_default_uso_cfdi` in `V107`). The app needs the same list to paint the receiver's default
 * use of the voucher and has no HTTP reader for it: adding an endpoint would be a new public contract,
 * which #940 excludes as a non-goal. So the list is duplicated here and this spec is the thing that keeps
 * the two in step — the same mechanism, for the same reason, as `regimen-fiscal.spec.ts`.
 */
const JAVA_SOURCE =
  '../life-control-api/src/main/java/com/lifecontrol/api/fiscal/model/UsoCfdi.java';

/**
 * One `c_UsoCFDI` code: **one or two** upper-case letters followed by two digits.
 *
 * The width is the whole point (`L3(c)`). `UsoCfdi` holds `G01…G03`, `I01…I08`, `D01…D10`, `P01`, `S01`,
 * and two codes of four characters, `CP01` and `CN01`. A pattern written as `[A-Z]\d{2}` matches 23 of the
 * 25, and the parity below would then compare 23 against 23 and pass — silently dropping two codes from the
 * product. The count assertion and this pattern are therefore asserted together.
 */
const CODE_PATTERN = '[A-Z]{1,2}\\d{2}';

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
 * spaces, an upper-case identifier and a quoted code in parentheses, so the constructor, the javadoc
 * examples and `codes()` cannot be mistaken for one.
 */
function javaCodes(source: string): string[] {
  const constant = new RegExp(`^ {4}[A-Z][A-Z0-9_]*\\("(${CODE_PATTERN})"\\)[,;]$`, 'gm');
  return Array.from(source.matchAll(constant), (match) => match[1] as string);
}

/**
 * The description the enum's javadoc states for each code — the authority's own wording, which is what the
 * select renders. `{@code G01} — Adquisición de mercancías.` is the shape.
 */
function javaLabels(source: string): Record<string, string> {
  const javadoc = new RegExp(`\\{@code (${CODE_PATTERN})\\} — (.+?)\\.\\s*\\*\\/`, 'g');
  const labels: Record<string, string> = {};
  for (const match of source.matchAll(javadoc)) {
    labels[match[1] as string] = match[2] as string;
  }
  return labels;
}

describe('uso cfdi vocabulary', () => {
  const source = javaSource();

  it('declares exactly the codes the API enum declares, in its order', () => {
    const expected = javaCodes(source);

    // A guard on the reader itself: if the regex stopped matching, both sides would agree on an empty
    // list and the parity would pass for the wrong reason.
    expect(expected.length).toBeGreaterThan(0);
    expect(USO_CFDI_CODES).toEqual(expected);
  });

  it('reads the four-character codes, so the pattern is not silently narrow', () => {
    // The reader's own proof: a `[A-Z]\d{2}` pattern returns 23 codes here and this assertion fails.
    const fourCharacters = javaCodes(source).filter((code) => code.length === 4);
    expect(fourCharacters).toEqual(['CP01', 'CN01']);
  });

  it('declares twenty-five codes, each exactly once, CP01 and CN01 included', () => {
    expect(USO_CFDI_CODES).toHaveLength(25);
    expect(new Set(USO_CFDI_CODES).size).toBe(25);
    expect(USO_CFDI_CODES).toContain('CP01');
    expect(USO_CFDI_CODES).toContain('CN01');
  });

  it('states each code in the one-or-two-letters shape the catalog uses', () => {
    for (const code of USO_CFDI_CODES) {
      expect(code).toMatch(new RegExp(`^${CODE_PATTERN}$`));
    }
  });

  it('takes every label from the enum description instead of inventing one', () => {
    const expected = javaLabels(source);

    for (const option of USO_CFDI_OPTIONS) {
      expect(expected[option.code]).toBeDefined();
      expect(option.label).toBe(expected[option.code]);
    }
  });
});
