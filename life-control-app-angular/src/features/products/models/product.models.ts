import { FormControl } from '@angular/forms';

export interface Product {
  id: string;
  name: string;
  description: string;
  price: number;
}

export interface ProductControl {
  id: FormControl<string>;
  name: FormControl<string>;
  description: FormControl<string>;
  price: FormControl<number>;
}

export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
  first: boolean;
  last: boolean;
  empty: boolean;
}
