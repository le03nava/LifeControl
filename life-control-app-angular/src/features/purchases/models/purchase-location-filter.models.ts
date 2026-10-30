/**
 * The five filter levels of the two purchase lists, as ids — the shape each list sends to its own
 * endpoint: `GET /api/purchase-orders` (`S4` of `odd/tasks/purchase-order-region-filter.md`) and
 * `GET /api/goods-receipts` (`S3` of `odd/tasks/receipt-location-filter.md`).
 *
 * Every level is nullable on purpose: `Empresa`, `País` and `Región` are the required ones and each
 * page gates the read on them (`S2`), while `Zona` and `Tienda` are optional narrowing levels
 * (`S3`). A level that the cascade cleared is reported as `null`, never as a stale id or `''`.
 */
export interface PurchaseLocationSelection {
  companyId: string | null;
  companyCountryId: string | null;
  regionId: string | null;
  zoneId: string | null;
  companyStoreId: string | null;
}
