/** Core Sales Order entity returned by the API. */
export interface SalesOrder {
  id: string;
  orderNumber: string;
  customerId: string;
  customerName?: string;
  companyStoreId: string;
  companyStoreName?: string;
  shiftId?: string;
  shiftName?: string;
  userId?: string;
  orderDate: string;
  statusId: string;
  statusName: string;
  /**
   * The document's currency, `sales_orders.currency` since `V80`.
   *
   * The sale owns it — it is not a company default and not a UI preference — and every amount of the document is
   * expressed in it. `PaymentMethodOption` and the templates take it from here.
   */
  currency: string;
  /**
   * The document's breakdown, `sales_orders` since `V80`/`V84`.
   *
   * `totalAmount = subtotal - discountTotal + taxTotal + roundingAdjustment`, exact: the parts are the server's
   * statement and the client never re-derives them.
   */
  subtotal?: number;
  discountTotal?: number;
  taxTotal?: number;
  roundingAdjustment?: number;
  totalAmount: number;
  /**
   * The payment rows a charge appended, oldest first.
   *
   * The only statement of how the document was paid: ADR-0002 step 6 dropped `sales_orders.payment_method_id`
   * (`V87`), so the response no longer carries a single method. A tender is a row, and a sale can have several.
   */
  payments?: SalePayment[];
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
  items: SalesOrderItem[];
}

/** One payment row of a charged document (`sale_payment`). */
export interface SalePayment {
  id: string;
  methodId: string;
  /** `CHARGE` for every row this client can cause: a refund is not expressible (decision `D-g`). */
  direction: 'CHARGE' | 'REFUND';
  amount: number;
  tenderedAmount?: number | null;
  changeAmount?: number | null;
  reference?: string | null;
  paidAt: string;
  actor: string;
}

/** Line item within a Sales Order. */
export interface SalesOrderItem {
  id: string;
  salesOrderId: string;
  productVariantId: string;
  productVariantName?: string;
  quantity: number;
  listPrice: number;
  discountApplied: number;
  /** `quantity × listPrice`, derived by `V81` and held by `ck_item_subtotal`. */
  subtotal?: number;
  /** The platform tax code the line states, when it states one. */
  taxCode?: string | null;
  /** The rate `tax_rate` resolved for this line's document date. */
  taxRate?: number | null;
  /** The tax amount the server computed, held by `ck_item_tax`. */
  taxAmount?: number;
  /**
   * The line total, `subtotal - discountApplied + taxAmount` since `V85` (`T7c`), derived by the server and
   * read-only here.
   */
  finalPrice: number;
  promotionId?: string;
  statusId: string;
  statusName: string;
  createdAt: string;
  updatedAt: string;
}

/** Request body for creating or updating a Sales Order header. */
export interface SalesOrderRequest {
  customerId?: string;
  companyStoreId: string;
  shiftId?: string;
  userId?: string;
  items?: SalesOrderItemRequest[];
}

/** Request body for creating or updating a Sales Order line item. */
export interface SalesOrderItemRequest {
  id?: string;
  productVariantId: string;
  quantity: number;
  listPrice: number;
  discountApplied?: number;
  promotionId?: string;
}

/** Request body for updating the status of a Sales Order. */
export interface UpdateSalesOrderStatusRequest {
  statusId: string;
}

/** Generic paginated response from the backend. */
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

/** Subset of customer data used in autocomplete selectors. */
export interface CustomerOption {
  id: string;
  name: string;
  email?: string;
  rfc?: string;
}

/** Subset of product data used in the product→variant autocomplete flow. */
export interface ProductOption {
  id: string;
  name: string;
  sku?: string;
}

/** Subset of product variant data used in line-item selection. */
export interface ProductVariantOption {
  id: string;
  productId: string;
  variantName: string;
  barCode?: string;
  sku?: string;
  listPrice: number;
  stock: number;
  enabled: boolean;
  /** Product name from the joined Product table (returned by search endpoint). */
  productName: string;
}

/** Subset of shift data returned by /api/shifts/open. */
export interface OpenShiftOption {
  id: string;
  companyStoreId: string;
  companyStoreName?: string;
  userId?: string;
  openedAt: string;
  status: string;
}

/** Request body for charging a sales order. */
export interface ChargeSalesOrderRequest {
  /**
   * The payments this charge appends, one per tender (ADR-0002 decision 5).
   *
   * Between them the amounts add up to the document's total **exactly** (M3) — the server refuses a sum that is
   * short or over, and the panel that builds this list reports the difference before sending it.
   */
  payments: SalePaymentRequest[];
}

/** One payment a charge is asked to append. */
export interface SalePaymentRequest {
  paymentMethodId: string;
  /** What this payment applies to the sale, at most four decimals. */
  amount: number;
  /** What the customer handed over. Only for a cash method (M4); omitted rather than zero. */
  tenderedAmount?: number;
  /** What came back, held by `ck_payment_tender` to `tenderedAmount - amount`. */
  changeAmount?: number;
}

/** Payment method option for the charge selector. */
export interface PaymentMethodOption {
  id: string;
  paymentMethodName: string;
  /**
   * Whether the method is cash (`payment_methods.is_cash`, `V83`).
   *
   * Only a cash payment can state a tender and its change: M4 refuses change on any other method, because a card
   * hands back nothing. Absent means «not stated», which the panel reads as not cash rather than guessing.
   */
  isCash?: boolean;
}
