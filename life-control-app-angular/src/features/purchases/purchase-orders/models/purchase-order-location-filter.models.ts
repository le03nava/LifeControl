/**
 * The five filter levels of the purchase order list, as ids — the shape the list sends to
 * `GET /api/purchase-orders` (`S4` of `odd/tasks/purchase-order-region-filter.md`).
 *
 * Every level is nullable on purpose: `Empresa`, `País` and `Región` are the required ones and the
 * page gates the read on them (`S2`), while `Zona` and `Tienda` are optional narrowing levels
 * (`S3`). A level that the cascade cleared is reported as `null`, never as a stale id or `''`.
 */
export interface PurchaseOrderLocationSelection {
  companyId: string | null;
  companyCountryId: string | null;
  regionId: string | null;
  zoneId: string | null;
  companyStoreId: string | null;
}
