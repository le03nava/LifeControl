/**
 * SAT's `c_RegimenFiscal` catalog — the closed vocabulary a fiscal profile declares.
 *
 * The API is the only place this tree states the catalog (`RegimenFiscal`, mirrored by the
 * `ck_companies_regimen_fiscal` check `V102` declares), and the app has no HTTP reader for it: adding an
 * endpoint would be a new public contract, which issue #931 excludes as a non-goal. So the list is
 * duplicated here and `regimen-fiscal.spec.ts` is what keeps the two in step — it reads the Java enum and
 * fails when a code, its order or its description disagrees.
 *
 * **Why this lives in `shared/` and not under `features/companies`.** The vocabulary is not the issuer's:
 * `customers.regimen_fiscal` (`V107`) reads the same catalog, which is also why `RegimenFiscal` itself
 * moved out of `company.model` on the API side. A copy under the company feature would have to be
 * duplicated again for the receiver, and two copies of one catalog are two copies free to drift.
 *
 * The `code` is what gets stored (`companies.regimen_fiscal` is a `VARCHAR(3)` holding `'601'`); the
 * `label` is presentation only and is copied verbatim from the enum's javadoc, which carries the
 * authority's own wording.
 */
export interface RegimenFiscalOption {
  readonly code: string;
  readonly label: string;
}

/**
 * The nineteen codes, in the enum's declaration order.
 *
 * Both the labels and the order are the enum's: `regimen-fiscal.spec.ts` pins every code, its position and
 * its description against `RegimenFiscal.java`, so adding a code there without adding it here is red.
 */
export const REGIMEN_FISCAL_OPTIONS: readonly RegimenFiscalOption[] = [
  { code: '601', label: 'General de Ley Personas Morales' },
  { code: '603', label: 'Personas Morales con Fines no Lucrativos' },
  { code: '605', label: 'Sueldos y Salarios e Ingresos Asimilados a Salarios' },
  { code: '606', label: 'Arrendamiento' },
  { code: '607', label: 'Régimen de Enajenación o Adquisición de Bienes' },
  { code: '608', label: 'Demás ingresos' },
  {
    code: '610',
    label: 'Residentes en el Extranjero sin Establecimiento Permanente en México',
  },
  { code: '611', label: 'Ingresos por Dividendos (socios y accionistas)' },
  { code: '612', label: 'Personas Físicas con Actividades Empresariales y Profesionales' },
  { code: '614', label: 'Ingresos por intereses' },
  { code: '615', label: 'Régimen de los ingresos por obtención de premios' },
  { code: '616', label: 'Sin obligaciones fiscales' },
  {
    code: '620',
    label: 'Sociedades Cooperativas de Producción que optan por diferir sus ingresos',
  },
  { code: '621', label: 'Incorporación Fiscal' },
  { code: '622', label: 'Actividades Agrícolas, Ganaderas, Silvícolas y Pesqueras' },
  { code: '623', label: 'Opcional para Grupos de Sociedades' },
  { code: '624', label: 'Coordinados' },
  {
    code: '625',
    label: 'Actividades Empresariales con ingresos a través de Plataformas Tecnológicas',
  },
  { code: '626', label: 'Régimen Simplificado de Confianza (RESICO)' },
];

/** The codes alone, in the same order — what a refusal or a comparison names. */
export const REGIMEN_FISCAL_CODES: readonly string[] = REGIMEN_FISCAL_OPTIONS.map(
  (option) => option.code,
);
