import { FormControl, FormGroup } from '@angular/forms';
import type { Country, CompanyCountry, CompanyCountryRequest } from '../../countries/models/country.models';
import type { AddressValue, AddressControl } from '@shared/models/address.models';

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
  address?: AddressValue;
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
  address: FormGroup<AddressControl>;
  enabled: FormControl<boolean>;
}
