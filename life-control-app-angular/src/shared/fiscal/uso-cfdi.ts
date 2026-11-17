/**
 * SAT's `c_UsoCFDI` catalog — the closed vocabulary of the receiver's default use of the voucher.
 *
 * The API is the only place this tree states the catalog (`UsoCfdi`, mirrored by the
 * `ck_customers_default_uso_cfdi` check `V107` declares), and the app has no HTTP reader for it: adding an
 * endpoint would be a new public contract, which #940 excludes as a non-goal. So the list is duplicated here
 * and `uso-cfdi.spec.ts` is what keeps the two in step — it reads the Java enum and fails when a code, its
 * order or its description disagrees.
 *
 * **Why this lives in `shared/fiscal`.** It is the sibling of `regimen-fiscal.ts`: the issuer states a régime,
 * the receiver states a default use, and both come from the authority. One copy per catalog, beside each
 * other, is what keeps the receiver's screen and the issuer's screen reading the same lists.
 *
 * The `code` is what gets stored (`customers.default_uso_cfdi` is a `VARCHAR(5)` holding `'G03'`); the `label`
 * is presentation only and is copied verbatim from the enum's javadoc, which carries the authority's wording.
 * Note that two codes are four characters wide — `CP01` (Pagos) and `CN01` (Nómina) — so any pattern written
 * to recognise a code has to admit one or two letters, not one.
 */
export interface UsoCfdiOption {
  readonly code: string;
  readonly label: string;
}

/**
 * The twenty-five codes, in the enum's declaration order.
 *
 * Both the labels and the order are the enum's: `uso-cfdi.spec.ts` pins every code, its position and its
 * description against `UsoCfdi.java`, so adding a code there without adding it here is red.
 */
export const USO_CFDI_OPTIONS: readonly UsoCfdiOption[] = [
  { code: 'G01', label: 'Adquisición de mercancías' },
  { code: 'G02', label: 'Devoluciones, descuentos o bonificaciones' },
  { code: 'G03', label: 'Gastos en general' },
  { code: 'I01', label: 'Construcciones' },
  { code: 'I02', label: 'Mobiliario y equipo de oficina por inversiones' },
  { code: 'I03', label: 'Equipo de transporte' },
  { code: 'I04', label: 'Equipo de cómputo y accesorios' },
  { code: 'I05', label: 'Dados, troqueles, moldes, matrices y herramental' },
  { code: 'I06', label: 'Comunicaciones telefónicas' },
  { code: 'I07', label: 'Comunicaciones satelitales' },
  { code: 'I08', label: 'Otra maquinaria y equipo' },
  { code: 'D01', label: 'Honorarios médicos, dentales y gastos hospitalarios' },
  { code: 'D02', label: 'Gastos médicos por incapacidad o discapacidad' },
  { code: 'D03', label: 'Gastos funerales' },
  { code: 'D04', label: 'Donativos' },
  {
    code: 'D05',
    label: 'Intereses reales efectivamente pagados por créditos hipotecarios (casa habitación)',
  },
  { code: 'D06', label: 'Aportaciones voluntarias al SAR' },
  { code: 'D07', label: 'Primas por seguros de gastos médicos' },
  { code: 'D08', label: 'Gastos de transportación escolar obligatoria' },
  {
    code: 'D09',
    label: 'Depósitos en cuentas para el ahorro, primas que tengan como base planes de pensiones',
  },
  { code: 'D10', label: 'Pagos por servicios educativos (colegiaturas)' },
  { code: 'P01', label: 'Por definir' },
  { code: 'S01', label: 'Sin efectos fiscales' },
  { code: 'CP01', label: 'Pagos' },
  { code: 'CN01', label: 'Nómina' },
];

/** The codes alone, in the same order — what a refusal or a comparison names. */
export const USO_CFDI_CODES: readonly string[] = USO_CFDI_OPTIONS.map((option) => option.code);
