import { FormControl } from '@angular/forms';

export interface Product {
  id: string;
  sku: string;
  name: string;
  shortName?: string;
  satCode?: string;
  productType?: string;
  attributes?: Record<string, unknown>;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

/** The form value the edit page consumes: the domain model minus the two server-owned keys. */
export type ProductFormValue = Omit<Product, 'createdAt' | 'updatedAt'>;

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

export interface ProductRequest {
  sku: string;
  name: string;
  shortName?: string;
  satCode?: string;
  productType?: string;
  attributes?: Record<string, unknown>;
}

export interface ProductControl {
  id: FormControl<string>;
  sku: FormControl<string>;
  name: FormControl<string>;
  shortName: FormControl<string | null>;
  satCode: FormControl<string | null>;
  productType: FormControl<string | null>;
  attributes: FormControl<string | null>;
  enabled: FormControl<boolean>;
}
