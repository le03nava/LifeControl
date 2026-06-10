import { FormControl } from '@angular/forms';

/** FormGroup control types for the sales order header form. */
export interface SalesOrderHeaderControl {
  customerId: FormControl<string>;
  companyStoreId: FormControl<string>;
  shiftId: FormControl<string>;
  comments: FormControl<string | null>;
}

/** FormGroup control types for a single sales-order line-item form row. */
export interface SalesOrderItemControl {
  productVariantId: FormControl<string>;
  quantity: FormControl<number>;
  listPrice: FormControl<number>;
  discountApplied: FormControl<number>;
}
