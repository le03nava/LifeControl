import { FormControl } from '@angular/forms';

/**
 * Store zone (level 3 of the store location tree).
 *
 * Both the nested list endpoint and the flat `/api/store-zones/{storeZoneId}`
 * lookup return the full company → country → region → zone → store → area
 * chain, so a store zone fetched by id alone can rebuild its nested URLs.
 */
export interface StoreZone {
  id: string;
  storeAreaId: string;
  companyStoreId: string;
  companyId: string;
  companyCountryId: string;
  regionId: string;
  zoneId: string;
  zoneCode: string;
  zoneName: string;
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

export interface CreateStoreZoneRequest {
  zoneCode: string;
  zoneName: string;
  description?: string;
  displayOrder?: number;
}

export interface UpdateStoreZoneRequest {
  zoneCode: string;
  zoneName: string;
  description?: string;
  displayOrder?: number;
  /**
   * Optional version precondition. The page decides whether the key is present at all: the create
   * request type carries no such field, so a create body serializes no `version` key.
   */
  version?: number;
}

/**
 * Typed control map for StoreZoneForm's self-contained FormGroup.
 */
export interface StoreZoneControl {
  zoneCode: FormControl<string>;
  zoneName: FormControl<string>;
  description: FormControl<string | null>;
  displayOrder: FormControl<number | null>;
}
