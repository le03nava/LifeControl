/**
 * One row of a company's `tax_rate` book, exactly as `GET /api/companies/{companyId}/tax-rates` serves it
 * (`TaxRateResponse`).
 *
 * `rate` is the **fraction** the column holds (`0.16`), never a percentage: the endpoint serves the value a
 * line snapshots and `ck_item_tax` compares against, and the screen is the only place that renders it as
 * `16 %`. Converting on the way in would put a second, disagreeing notion of the stored value in the tree.
 *
 * `regionId`/`regionName` are `null` together, and that is the company-wide rate. The name travels beside
 * the id because the scope is half of the key that decides precedence and an id alone makes two rates for
 * the same code and day indistinguishable on screen.
 */
export interface TaxRate {
  id: string;
  companyId: string;
  taxCode: string;
  rate: number;
  /** `YYYY-MM-DD`, the day the row applies from. */
  effectiveFrom: string;
  enabled: boolean;
  regionId: string | null;
  regionName: string | null;
}

/** The scope label of a company-wide rate. Written once so the list and the form cannot disagree. */
export const COMPANY_WIDE_SCOPE = 'Toda la compañía';

/**
 * Body of `POST /api/companies/{companyId}/tax-rates` (`TaxRateRequest`).
 *
 * `rate` is the **fraction** the endpoint and the column take (`0.16`), never the percentage the operator
 * types. The conversion lives in one exported function so the dialog cannot be the only place that knows it.
 * `regionId: null` is the company-wide scope — the half of the key the server resolves precedence from.
 */
export interface DeclareTaxRateRequest {
  taxCode: string;
  regionId: string | null;
  rate: number;
  /** `YYYY-MM-DD`, the day the rate applies from. */
  effectiveFrom: string;
  enabled: boolean;
}

/**
 * The percentage an operator types, as the fraction the contract takes (`16` → `0.16`).
 *
 * **The scale is six decimals on purpose.** `tax_rate.rate` is `NUMERIC(9,6)` and `TaxRateRequest` carries
 * `@Digits(integer = 1, fraction = 6)`, so a longer value is rejected by the server rather than rounded by
 * it. `toFixed(6)` also removes the binary-float noise a plain `/ 100` leaves (`12.3456 / 100` is
 * `0.12345600000000001`), which would otherwise travel as a number with more digits than the column holds.
 */
export function percentToFraction(percent: number): number {
  return Number((percent / 100).toFixed(6));
}
