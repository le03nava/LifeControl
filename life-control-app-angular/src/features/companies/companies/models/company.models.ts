import { FormControl } from '@angular/forms';
import type { Country, CompanyCountry, CompanyCountryRequest } from '../../countries/models/country.models';

export type { Country, CompanyCountry, CompanyCountryRequest };

export interface Company {
  id: string;
  companyKey: string;
  companyName: string;
  tipoPersonaId: number;
  razonSocial: string;
  rfc: string;
  email: string;
  phone: string;
  street?: string;
  streetNumber?: string;
  internalNumber?: string;
  neighborhood?: string;
  zipCode?: string;
  city?: string;
  state?: string;
  countryId?: string;
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
  street: FormControl<string | null>;
  streetNumber: FormControl<string | null>;
  internalNumber: FormControl<string | null>;
  neighborhood: FormControl<string | null>;
  zipCode: FormControl<string | null>;
  city: FormControl<string | null>;
  state: FormControl<string | null>;
  countryId: FormControl<string | null>;
  enabled: FormControl<boolean>;
}
