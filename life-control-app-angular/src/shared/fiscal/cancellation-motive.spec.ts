import {
  CANCELLATION_MOTIVE_CODES,
  CANCELLATION_MOTIVE_LABELS,
  CANCELLATION_MOTIVE_OPTIONS,
  requiresSubstitutingFolio,
} from './cancellation-motive';

/**
 * Path of the enum that owns the vocabulary, from the Angular project root.
 *
 * The authority's `c_MotivoCancelacion` is stated once in this tree — the API's
 * `FiscalCancellationMotive`, mirrored by the `ck_fiscal_document_cancellation` check `V108` declares — and
 * no endpoint serves it. The app needs the four codes to paint `S4`'s select and adding a reader would be a
 * new public contract, which #932 excludes as a non-goal (`L4`). So the list is copied here and this spec
 * is what keeps the two in step, on the precedent of `regimen-fiscal.spec.ts` and `uso-cfdi.spec.ts`.
 */
const JAVA_SOURCE =
  '../life-control-api/src/main/java/com/lifecontrol/api/fiscal/model/FiscalCancellationMotive.java';

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

/** One constant of the enum, as its own javadoc and its declaration state it. */
interface JavaMotive {
  readonly name: string;
  readonly code: string;
  readonly requiresSubstitutingFolio: boolean;
  /** The javadoc above the constant, with the comment markers and the HTML stripped. */
  readonly description: string;
}

/**
 * The constants the enum declares, in declaration order.
 *
 * The pattern is deliberately narrow: it pins the javadoc **immediately** above a constant-shaped
 * declaration whose two arguments are the code and the folio flag, so the class javadoc, the constructor,
 * `code()` and `requiresSubstitutingFolio()` cannot be mistaken for one. The javadoc is captured with the
 * constant because this enum's labels are wrapped over two lines and its prose explains the code, which a
 * single-line reader cannot state.
 */
function javaMotives(source: string): JavaMotive[] {
  const constant = /\/\*\*([\s\S]*?)\*\/\s+([A-Z][A-Z0-9_]*)\("(\d{2})",\s*(true|false)\)[,;]/g;

  return Array.from(source.matchAll(constant), (match) => ({
    name: match[2] as string,
    code: match[3] as string,
    requiresSubstitutingFolio: match[4] === 'true',
    description: javadocText(match[1] as string),
  }));
}

/** The javadoc of one constant as one line of text: markers stripped, `<b>`/`{@link}` gone. */
function javadocText(comment: string): string {
  return comment
    .split('\n')
    .map((line) => line.replace(/^\s*\*?\s?/, ''))
    .join(' ')
    .replace(/<[^>]+>/g, '')
    .replace(/\s+/g, ' ')
    .trim();
}

/**
 * The authority's Spanish name for each code, as the enum states it.
 *
 * Each constant's javadoc opens with the code, an em dash and the SAT's own wording, and then explains it
 * after a colon — except `04`, which is only named. The label is that opening phrase: the part before the
 * explanation, with the trailing full stop of the one constant that has no explanation removed.
 */
function javaLabels(motives: JavaMotive[]): Record<string, string> {
  const labels: Record<string, string> = {};

  for (const motive of motives) {
    const stated = new RegExp(`\\{@code ${motive.code}\\} — (.+)$`).exec(motive.description);
    if (!stated) {
      throw new Error(
        `The javadoc of ${motive.name} does not state its code, so no label can be read.`,
      );
    }
    const wording = stated[1] as string;
    const colon = wording.indexOf(':');

    labels[motive.code] = (
      colon === -1 ? wording.replace(/\.$/, '') : wording.slice(0, colon)
    ).trim();
  }

  return labels;
}

describe('cancellation motive vocabulary', () => {
  const motives = javaMotives(javaSource());

  it('declares exactly the codes the API enum declares, in its order', () => {
    // A guard on the reader itself: if the regex stopped matching, both sides would agree on an empty
    // list and the parity would pass for the wrong reason.
    expect(motives.length).toBeGreaterThan(0);
    expect(CANCELLATION_MOTIVE_CODES).toEqual(motives.map((motive) => motive.code));
  });

  it('declares four codes, each exactly once', () => {
    expect(CANCELLATION_MOTIVE_CODES).toHaveLength(4);
    expect(new Set(CANCELLATION_MOTIVE_CODES).size).toBe(4);
  });

  it('takes every label from the enum description instead of inventing one', () => {
    const expected = javaLabels(motives);

    expect(Object.keys(expected)).toHaveLength(CANCELLATION_MOTIVE_OPTIONS.length);
    for (const option of CANCELLATION_MOTIVE_OPTIONS) {
      expect(expected[option.code]).toBeDefined();
      expect(option.label).toBe(expected[option.code]);
    }
  });

  it('names the label of every code the map is asked for, and no other', () => {
    expect(Object.keys(CANCELLATION_MOTIVE_LABELS)).toEqual([...CANCELLATION_MOTIVE_CODES]);
  });

  it('requires the substituting folio for the one motive the enum requires it for', () => {
    for (const motive of motives) {
      expect(requiresSubstitutingFolio(motive.code)).toBe(motive.requiresSubstitutingFolio);
    }
  });

  it('requires it for 01 alone, and never for a code the vocabulary does not admit', () => {
    expect(requiresSubstitutingFolio('01')).toBe(true);
    for (const code of ['02', '03', '04', '05', '']) {
      expect(requiresSubstitutingFolio(code)).toBe(false);
    }
  });
});
