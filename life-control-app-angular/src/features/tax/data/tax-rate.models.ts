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
