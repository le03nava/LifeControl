import { FormControl } from '@angular/forms';

/** FormGroup control types for the purchase order header form. */
export interface PurchaseOrderHeaderControl {
  supplierId: FormControl<string>;
  companyStoreId: FormControl<string>;
  paymentMethodId: FormControl<string>;
  comments: FormControl<string | null>;
}

/** FormGroup control types for a single line-item form row. */
export interface PurchaseOrderDetailControl {
  productId: FormControl<string>;
  quantity: FormControl<number>;
  unitPrice: FormControl<number>;
  comments: FormControl<string | null>;
}
