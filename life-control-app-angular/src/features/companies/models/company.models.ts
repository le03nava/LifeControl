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
  companyId: number;
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
  companyId: FormControl<number | null>;
  companyName: FormControl<string>;
  tipoPersonaId: FormControl<number>;
  razonSocial: FormControl<string>;
  rfc: FormControl<string>;
  email: FormControl<string>;
  phone: FormControl<string>;
  address: FormControl<string>;
  enabled: FormControl<boolean>;
}
