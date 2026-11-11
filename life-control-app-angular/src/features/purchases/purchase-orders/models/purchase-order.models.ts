export interface PurchaseOrder {
  id: string;
  orderNumber: string;
  supplierId: string;
  supplierName: string;
  companyStoreId: string;
  companyStoreName: string;
  /** UUID of the parent company for cascade reconstruction (edit mode). */
  companyId: string | null;
  /** UUID of the company-country join record for cascade reconstruction. */
  companyCountryId: string | null;
  /** UUID of the region for cascade reconstruction. */
  regionId: string | null;
  /** UUID of the zone for cascade reconstruction. */
  zoneId: string | null;
  paymentMethodId: string;
  paymentMethodName: string;
  statusId: string;
  statusName: string;
  comments: string | null;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
  /**
   * The currency the order was written with, `purchase_orders.currency` since `V93`.
   *
   * Derived from the company's currency when the row is created and frozen on it: the order owns its currency
   * (`V80` decided the same for a sale), so the screen renders every amount of the document in this code and never
   * in one of its own.
   */
  currency: string;
  /** Sum of the line bases, derived by the server from the order's own lines. The client never re-derives it. */
  subtotal: number;
  /**
   * Sum of the lines' `taxAmount`, derived by the server from the same rows its `subtotal` is summed from.
   *
   * `0` on an order whose lines state no tax — and `0` is a value the screen reads, not one it infers.
   */
  taxTotal: number;
  /**
   * `subtotal + taxTotal` as the server derived it — the document's only total.
   *
   * The screen reads this number instead of summing its rows: a total the browser computes is a number no other
   * client can reproduce, and it is the defect this unit removes.
   */
  totalAmount: number;
  details: PurchaseOrderDetail[];
}

export interface PurchaseOrderDetail {
  id: string;
  purchaseOrderId: string;
  productId: string;
  productName: string;
  /** Variant the line is for. `null` only on legacy rows saved before the variant contract. */
  productVariantId: string | null;
  /** Display label for the variant. `null` on the same legacy rows. */
  productVariantName: string | null;
  quantity: number;
  unitPrice: number;
  /**
   * Line total as the database derived it: `subtotal + taxAmount`, at four decimals.
   *
   * The wire states this total and the tax, never the line's base: the screen renders the two numbers the response
   * carries and never subtracts or multiplies to produce a third one.
   */
  total: number;
  /**
   * The fiscal code the line was written with, copied from the product when the line was created.
   *
   * `NA`, `TASA_0` and `EXENTO` all mean «no tax is owed», for three different reasons: a code names **what** the tax
   * is and never how much, so the amount is read from {@link taxAmount} instead of being inferred from this one.
   *
   * Optional exactly like the three tax fields of `SalesOrderItem`, which is the surface this one mirrors: not because
   * the API may omit them — the columns are `NOT NULL` — but because a fixture written before the tax columns existed
   * carries no tax parts, and a screen reading them must render nothing rather than a `0` it invented.
   */
  taxCode?: string;
  /**
   * The rate as it stood on the document's date when the line was created, as a fraction: `0.16` is 16%.
   *
   * A snapshot, not a lookup: the screen prints it and never resolves a rate of its own, and a later rate change does
   * not restate this line.
   */
  taxRate?: number;
  /** `round(subtotal * taxRate, 4)`, derived by the schema from the stored rate and base and read back here. */
  taxAmount?: number;
  receivedQuantity: number;
  comments: string | null;
  statusId: string;
  statusName: string;
  createdAt: string;
  updatedAt: string;
}

export interface PurchaseOrderRequest {
  supplierId: string;
  companyStoreId: string;
  paymentMethodId: string;
  statusId?: string;
  comments?: string;
  details?: PurchaseOrderDetailRequest[];
}

export interface PurchaseOrderDetailRequest {
  productId: string;
  /** Mandatory since the variant contract flip: the API rejects a payload without it. */
  productVariantId: string;
  quantity: number;
  unitPrice: number;
  comments?: string;
  statusId?: string;
}

export interface UpdatePurchaseOrderStatusRequest {
  statusId: string;
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
