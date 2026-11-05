import { FormControl, FormGroup } from '@angular/forms';
import type {
  Country,
  CompanyCountry,
  CompanyCountryRequest,
} from '../../countries/models/country.models';
import type { AddressValue, AddressRequest, AddressControl } from '@shared/models/address.models';

export type { Country, CompanyCountry, CompanyCountryRequest };

/**
 * The row shape of the company **selector** read (`GET /api/companies/selector`).
 *
 * Deliberately narrower than {@link Company}: that endpoint exists so a cascading dropdown can be
 * painted by any authenticated caller, authorized by the JWT's `company_id` claim instead of a
 * role, so it only projects the three fields a `mat-option` needs. A consumer that needs any other
 * member — `emailDomain`, `rfc`, `address` — must stay on the full {@link Company} read.
 */
export interface CompanySelectorOption {
  id: string;
  companyKey: string;
  companyName: string;
}

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
  /**
   * ISO 4217 code the company states as its operating currency.
   *
   * The API has returned it since `V57__company_currency.sql` and it is `NOT NULL` there, so the read
   * model declares it: an interface that omits a field the endpoint always sends is how the client kept
   * dropping a value it had already received.
   */
  currency: string;
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
  /** Required by `CompanyRequest` with no default: the operator states it, the API refuses a blank. */
  currency: string;
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
  currency: FormControl<string>;
  address: FormGroup<AddressControl>;
  enabled: FormControl<boolean>;
}
