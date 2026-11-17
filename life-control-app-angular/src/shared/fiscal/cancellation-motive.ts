/**
 * SAT's `c_MotivoCancelacion` catalog — the four motives a cancellation may state.
 *
 * The API is the only place this tree states the vocabulary (`FiscalCancellationMotive`, mirrored by the
 * `ck_fiscal_document_cancellation` check `V108` declares), and no endpoint serves it: adding a reader
 * would be a new public contract, which issue #932 excludes as a non-goal. So the list is duplicated here
 * and `cancellation-motive.spec.ts` is what keeps the two in step — it reads the Java enum and fails when
 * a code, its order, its wording or its folio rule disagrees.
 *
 * **Why this lives in `shared/fiscal`.** It is the third sibling of `regimen-fiscal.ts` and `uso-cfdi.ts`:
 * the issuer states a régime, the receiver states a default use of the voucher, and a cancellation states a
 * motive. One copy per catalog, beside each other, is what keeps the three screens reading lists that come
 * from the same authority.
 *
 * The `code` is what gets stored (`fiscal_document.cancellation_motive` is a `VARCHAR(2)` holding `'01'`)
 * and what the request sends; the `label` is presentation only. `01` is the row that has a consequence: it
 * is the one motive that replaces the document, so `S4` asks for a substituting folio only under it.
 */
export interface CancellationMotiveOption {
  readonly code: string;
  readonly label: string;
}

/**
 * The four codes, in the enum's declaration order.
 *
 * Both the labels and the order are the enum's: the label is the authority's own Spanish wording, taken
 * from each constant's javadoc instead of retyped, and `cancellation-motive.spec.ts` pins every code, its
 * position and its wording against `FiscalCancellationMotive.java`.
 */
export const CANCELLATION_MOTIVE_OPTIONS: readonly CancellationMotiveOption[] = [
  { code: '01', label: 'Comprobante emitido con errores con relación' },
  { code: '02', label: 'Comprobante emitido con errores sin relación' },
  { code: '03', label: 'No se llevó a cabo la operación' },
  { code: '04', label: 'Operación nominativa relacionada en una factura global' },
];

/** The codes alone, in the same order — what a refusal or a comparison names. */
export const CANCELLATION_MOTIVE_CODES: readonly string[] = CANCELLATION_MOTIVE_OPTIONS.map(
  (option) => option.code,
);

/** Code to label, for a stored motive that has to be rendered without the select beside it. */
export const CANCELLATION_MOTIVE_LABELS: Readonly<Record<string, string>> = Object.fromEntries(
  CANCELLATION_MOTIVE_OPTIONS.map((option) => [option.code, option.label] as const),
);

/**
 * The motive that replaces the cancelled document, so a request under it must name the folio that does.
 *
 * It is one code and not a property of the option, because it is a rule of the vocabulary and not of an
 * option: `FiscalCancellationMotive.requiresSubstitutingFolio()` answers it for the API, and the parity spec
 * reads that flag per code rather than trusting this constant alone.
 */
const SUBSTITUTING_FOLIO_REQUIRED = '01';

/**
 * Whether a request under this motive must state the fiscal UUID of the document that substitutes the
 * cancelled one (`S4`).
 *
 * The screen asks for the folio only when this answers true, and the service sends it only then. A code
 * the vocabulary does not admit answers `false`: an unknown motive is the server's refusal to state, not a
 * form field to invent.
 */
export function requiresSubstitutingFolio(motive: string): boolean {
  return motive === SUBSTITUTING_FOLIO_REQUIRED;
}
