/**
 * One row of the platform currency catalog, as `GET /api/currencies` serves it.
 *
 * `code` is the identity — an ISO 4217 code, not a generated id — so it is what a company states and what a
 * caller filters by. `symbol` is presentation only: the label a select prints, never a key.
 */
export interface Currency {
  code: string;
  minorUnits: number;
  symbol: string;
  enabled: boolean;
}
