import { FormControl } from '@angular/forms';

/**
 * Store area (level 2 of the store location tree).
 *
 * Both the nested list endpoint and the flat `/api/store-areas/{areaId}` lookup
 * return the full company → country → region → zone → store chain, so an area
 * fetched by id alone can rebuild its nested URLs.
 */
export interface StoreArea {
  id: string;
  companyStoreId: string;
  companyId: string;
  companyCountryId: string;
  regionId: string;
  zoneId: string;
  areaCode: string;
  areaName: string;
  description: string | null;
  displayOrder: number | null;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface CreateStoreAreaRequest {
  areaCode: string;
  areaName: string;
  description?: string;
  displayOrder?: number;
}

export interface UpdateStoreAreaRequest {
  areaCode: string;
  areaName: string;
  description?: string;
  displayOrder?: number;
}

/**
 * Typed control map for StoreAreaForm's self-contained FormGroup.
 */
export interface StoreAreaControl {
  areaCode: FormControl<string>;
  areaName: FormControl<string>;
  description: FormControl<string | null>;
  displayOrder: FormControl<number | null>;
}
