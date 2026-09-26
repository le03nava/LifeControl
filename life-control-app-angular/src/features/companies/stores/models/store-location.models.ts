import { FormControl } from '@angular/forms';

/**
 * Store location (level 4 of the store location tree, the leaf).
 *
 * Both the nested list endpoint and the flat `/api/store-locations/{storeLocationId}`
 * lookup return the full company → country → region → zone → store → area →
 * store zone chain, so a store location fetched by id alone can rebuild its
 * nested URLs.
 */
export interface StoreLocation {
  id: string;
  storeZoneId: string;
  storeAreaId: string;
  companyStoreId: string;
  companyId: string;
  companyCountryId: string;
  regionId: string;
  zoneId: string;
  locationCode: string;
  locationName: string;
  description: string | null;
  displayOrder: number | null;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
  /**
   * Optimistic-locking version, echoed back on update so a lost update is refused. Required on
   * purpose: if the API stops sending it, `tsc` fails instead of the screen quietly omitting the
   * precondition.
   */
  version: number;
}

export interface CreateStoreLocationRequest {
  locationCode: string;
  locationName: string;
  description?: string;
  displayOrder?: number;
}

export interface UpdateStoreLocationRequest {
  locationCode: string;
  locationName: string;
  description?: string;
  displayOrder?: number;
  /**
   * Optional version precondition. The page decides whether the key is present at all: the create
   * request type carries no such field, so a create body serializes no `version` key.
   */
  version?: number;
}

/**
 * Typed control map for StoreLocationForm's self-contained FormGroup.
 */
export interface StoreLocationControl {
  locationCode: FormControl<string>;
  locationName: FormControl<string>;
  description: FormControl<string | null>;
  displayOrder: FormControl<number | null>;
}
