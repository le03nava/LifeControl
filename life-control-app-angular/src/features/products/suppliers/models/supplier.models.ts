import { FormControl, FormGroup } from '@angular/forms';
import type { AddressValue, AddressRequest, AddressControl } from '@shared/models/address.models';

export interface Supplier {
  id: string;
  supplierName: string;
  razonSocial: string;
  rfc: string;
  email: string;
  phoneNumber: string;
  internalNumber?: string;
  address?: AddressValue;
  enabled: boolean;
  /**
   * The company that owns the supplier. It is the scope the row is addressed through
   * (`/api/companies/{companyId}/suppliers`), and the response reports it so a caller that is picking a
   * supplier can prove the row belongs to the company it is attaching it to.
   *
   * Optional because the request body does not carry it (the path does): a `SupplierRequest` is not a
   * `Supplier`, and a form built from a form value has no opinion about the owner.
   */
  companyId?: string;
  createdAt: string;
  updatedAt: string;
}

/** The form value the edit page consumes: the domain model minus the two server-owned keys. */
export type SupplierFormValue = Omit<Supplier, 'createdAt' | 'updatedAt'>;

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

export interface SupplierRequest {
  supplierName: string;
  razonSocial: string;
  rfc: string;
  email: string;
  phoneNumber: string;
  internalNumber?: string;
  address?: AddressRequest;
  enabled: boolean;
}

export interface SupplierControl {
  id: FormControl<string>;
  supplierName: FormControl<string>;
  razonSocial: FormControl<string>;
  rfc: FormControl<string>;
  email: FormControl<string>;
  phoneNumber: FormControl<string>;
  internalNumber: FormControl<string | null>;
  address: FormGroup<AddressControl>;
  enabled: FormControl<boolean>;
}
