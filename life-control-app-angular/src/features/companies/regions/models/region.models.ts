import { FormControl } from '@angular/forms';
import type {
  Country,
  CompanyCountry,
  CompanyCountryRequest,
} from '../../countries/models/country.models';

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

/**
 * Body of POST/PUT .../regions.
 *
 * Mirrors CreateCompanyRegionRequest and UpdateCompanyRegionRequest on the API side, which
 * declare exactly these two keys. `enabled` is deliberately absent: the region's state is owned
 * by the server and changes only through DELETE (soft-disable) and PATCH (re-enable) on the
 * resource. An extra key here is rejected with 400 before field validation runs.
 */
export interface CompanyRegionRequest {
  regionCode: string;
  regionName: string;
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
}
