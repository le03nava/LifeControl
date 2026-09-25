/**
 * Client models for the store inventory endpoints.
 *
 * Field-for-field mirror of the backend DTOs (`StoreLocationSummaryResponse`,
 * `StoreInventorySettingsResponse`, `StoreInventorySettingsRequest`), including the `version`
 * optimistic-lock token those settings DTOs carry in each direction.
 */

/**
 * Lightweight read model of one store location, used by the receiving/sales
 * location picker. Carries the zone and area code/name so two locations that
 * share a name across different zones or areas can be disambiguated.
 */
export interface StoreLocationSummary {
  id: string;
  locationCode: string;
  locationName: string;
  storeZoneId: string;
  zoneCode: string;
  zoneName: string;
  storeAreaId: string;
  areaCode: string;
  areaName: string;
}

/** The 5-level store chain the inventory endpoints are nested under. */
export interface StoreChain {
  companyId: string;
  companyCountryId: string;
  regionId: string;
  zoneId: string;
  storeId: string;
}

/** The receiving and sales locations configured for one store. */
export interface StoreInventorySettings {
  companyStoreId: string;
  receivingLocationId: string;
  salesLocationId: string;
  /** Optimistic-lock token that must be echoed back on update or the write is rejected as a lost update. */
  version: number;
}

/** Request body for the store's inventory settings. */
export interface StoreInventorySettingsRequest {
  receivingLocationId: string;
  salesLocationId: string;
  /**
   * Optional optimistic-lock precondition. Omit the key entirely to send no precondition, which is
   * what a create must send; when present it must equal the version read from the server.
   */
  version?: number;
}
