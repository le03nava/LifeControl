import { FormControl } from '@angular/forms';

export interface Supplier {
  id: string;
  supplierName: string;
  razonSocial: string;
  rfc: string;
  email: string;
  phoneNumber: string;
  street: string;
  streetNumber: string;
  neighborhood: string;
  zipCode: string;
  city: string;
  state: string;
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
  street: string;
  streetNumber: string;
  neighborhood: string;
  zipCode: string;
  city: string;
  state: string;
  enabled: boolean;
}

export interface SupplierControl {
  id: FormControl<string>;
  supplierName: FormControl<string>;
  razonSocial: FormControl<string>;
  rfc: FormControl<string>;
  email: FormControl<string>;
  phoneNumber: FormControl<string>;
  street: FormControl<string>;
  streetNumber: FormControl<string>;
  neighborhood: FormControl<string>;
  zipCode: FormControl<string>;
  city: FormControl<string>;
  state: FormControl<string>;
  enabled: FormControl<boolean>;
}
