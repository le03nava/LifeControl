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
}

export interface Company {
  id: string;
  companyKey: string;
  companyName: string;
  tipoPersonaId: number;
  razonSocial: string;
  rfc: string;
  email: string;
  phone: string;
  address?: string;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
  countries?: CompanyCountry[];
}

export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
  first: boolean;
  last: boolean;
  empty: boolean;
}

export interface CompanyControl {
  id: FormControl<string>;
  companyKey: FormControl<string>;
  companyName: FormControl<string>;
  tipoPersonaId: FormControl<number>;
  razonSocial: FormControl<string>;
  rfc: FormControl<string>;
  email: FormControl<string>;
  phone: FormControl<string>;
  address: FormControl<string>;
  enabled: FormControl<boolean>;
}
