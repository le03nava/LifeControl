import { FormControl } from '@angular/forms';

export interface ProductSupplier {
  id: string;
  productId: string;
  supplierId: string;
  supplierName: string;
  purchaseCost: number;
  main: boolean;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface ProductSupplierRequest {
  supplierId: string;
  purchaseCost: number;
  main: boolean;
  enabled: boolean;
}

export interface ProductSupplierControl {
  id: FormControl<string>;
  supplierId: FormControl<string>;
  purchaseCost: FormControl<number>;
  main: FormControl<boolean>;
  enabled: FormControl<boolean>;
}
