import { FormControl } from '@angular/forms';

export interface Country {
  id: string;
  countryCode: string;
  countryName: string;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface CompanyCountry {
  id: string;
  companyId: string;
  countryId: string;
  countryCode: string;
  countryName: string;
  localAlias: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CompanyCountryRequest {
  countryCode: string;
  localAlias?: string;
}

/**
 * Composite save event emitted by CountriesFormComponent.
 * Bundles the company context with the form payload.
 * countryId here refers to the CompanyCountry join record ID.
 */
export interface CountrySaveEvent {
  companyId: string;
  request: CompanyCountryRequest;
  /** Populated for edit mode (updateCountry), omitted for create mode (addCountry). */
  countryId?: string;
}

/**
 * Typed control map for CountriesFormComponent's self-contained FormGroup.
 */
export interface CountryControl {
  localAlias: FormControl<string | null>;
}
