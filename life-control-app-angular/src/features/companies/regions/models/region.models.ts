import { FormControl } from '@angular/forms';
import type { Country, CompanyCountry, CompanyCountryRequest } from '../../countries/models/country.models';

export type { Country, CompanyCountry, CompanyCountryRequest };

export interface CompanyRegion {
  id: string;
  companyCountryId: string;
  companyId: string;
  countryId: string;
  regionCode: string;
  regionName: string;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface CompanyRegionRequest {
  regionCode: string;
  regionName: string;
  enabled?: boolean;
}

/**
 * Composite save event emitted by RegionsFormComponent.
 * Bundles selector context (companyId, countryId) with the form payload.
 * countryId here refers to the CompanyCountry join record ID (companyCountryId).
 */
export interface RegionSaveEvent {
  companyId: string;
  countryId: string;
  request: CompanyRegionRequest;
  /** Populated for edit mode (updateRegion), omitted for create mode (addRegion). */
  regionId?: string;
}

/**
 * Typed control map for RegionsFormComponent's self-contained FormGroup.
 */
export interface RegionControl {
  regionCode: FormControl<string>;
  regionName: FormControl<string>;
  enabled: FormControl<boolean>;
}
