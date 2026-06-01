import { FormControl } from '@angular/forms';

export interface Product {
  id: string;
  sku: string;
  name: string;
  shortName?: string;
  satCode?: string;
  productType?: string;
  attributes?: Record<string, any>;
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

export interface ProductRequest {
  sku: string;
  name: string;
  shortName?: string;
  satCode?: string;
  productType?: string;
  attributes?: Record<string, any>;
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
