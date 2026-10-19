import { FormControl, FormGroup } from '@angular/forms';
import type {
  Country,
  CompanyCountry,
  CompanyCountryRequest,
} from '../../countries/models/country.models';
import type { AddressValue, AddressRequest, AddressControl } from '@shared/models/address.models';

export type { Country, CompanyCountry, CompanyCountryRequest };

export interface Company {
  id: string;
  companyKey: string;
  companyName: string;
  tipoPersonaId: number;
  razonSocial: string;
  rfc: string;
  email: string;
  emailDomain?: string;
  phone: string;
  address?: AddressValue;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
  countries?: CompanyCountry[];
}

export interface CompanyRequest {
  companyKey: string;
  companyName: string;
  tipoPersonaId: number;
  razonSocial: string;
  rfc: string;
  phone: string;
  email: string;
  /** Optional like the API DTO, but the page always sends it (`''` when blank, which the API normalizes to null). */
  emailDomain?: string;
  enabled: boolean;
  address?: AddressRequest;
}

/** The form value the edit page consumes: the domain model minus the two server-owned keys. */
export type CompanyFormValue = Omit<Company, 'createdAt' | 'updatedAt'>;

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
  emailDomain: FormControl<string>;
  phone: FormControl<string>;
  address: FormGroup<AddressControl>;
  enabled: FormControl<boolean>;
}
