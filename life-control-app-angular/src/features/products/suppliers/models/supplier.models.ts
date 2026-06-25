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
  createdAt: string;
  updatedAt: string;
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
