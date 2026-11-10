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
  /** Sum of the line taxes, also derived by the server. 0 in this slice: a purchase order states no tax yet. */
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
  /** Line total, derived by the database at four decimals (`subtotal + tax`, tax 0 in this slice). */
  total: number;
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
