/**
 * One row of the platform tax-code catalog (`tax_code`, V82), as `GET /api/tax-codes` serves it.
 *
 * The shape is exactly the three columns of the catalog and nothing else: `code` is the identity —
 * `tax_rate.tax_code` references it and a sales line snapshots it at write time (ADR-0002 decision 6) —
 * so there is no generated id. `label` is presentation only: the string the form prints, never what a
 * caller filters by. `enabled` is served rather than filtered away silently, so a retired code stays
 * visible under `?includeDisabled=true` with its own flag instead of disappearing from a shape that could
 * not say why.
 */
export interface TaxCode {
  code: string;
  label: string;
  enabled: boolean;
}

/**
 * The fiscal code a product declares when the operator states none.
 *
 * It is the server's own default (`V82`: `tax_code VARCHAR(20) NOT NULL DEFAULT 'NA'`) and one of the
 * four codes the catalog seeds, which is what makes it an honest initial value for the form's select: an
 * untouched field states the same code the API would have written, instead of sending `undefined` and
 * leaving a decision the operator never delegated.
 */
export const DEFAULT_TAX_CODE = 'NA';
