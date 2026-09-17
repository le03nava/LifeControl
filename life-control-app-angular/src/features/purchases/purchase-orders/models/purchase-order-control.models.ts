import { FormControl, FormGroup } from '@angular/forms';

/**
 * Cascade context controls (Company → Country → Region → Zone).
 *
 * These IDs are UI-only state used to resolve the `companyStoreId` picked in
 * the parent header form; they are never sent to the backend.
 */
export interface PurchaseOrderCompanyControl {
  companyId: FormControl<string | null>;
  companyCountryId: FormControl<string | null>;
  regionId: FormControl<string | null>;
  zoneId: FormControl<string | null>;
}

/** FormGroup control types for the purchase order header form. */
export interface PurchaseOrderHeaderControl {
  supplierId: FormControl<string>;
  companyStoreId: FormControl<string>;
  paymentMethodId: FormControl<string>;
  comments: FormControl<string | null>;
  /** Nested cascade group feeding the location store selector. */
  company: FormGroup<PurchaseOrderCompanyControl>;
}

/** FormGroup control types for a single line-item form row. */
export interface PurchaseOrderDetailControl {
  productId: FormControl<string>;
  quantity: FormControl<number>;
  unitPrice: FormControl<number>;
  comments: FormControl<string | null>;
}
