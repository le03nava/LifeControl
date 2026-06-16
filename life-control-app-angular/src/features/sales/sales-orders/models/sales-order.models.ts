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
  totalAmount: number;
  paymentMethodId?: string;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
  items: SalesOrderItem[];
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
  paymentMethodId: string;
}

/** Payment method option for the charge selector. */
export interface PaymentMethodOption {
  id: string;
  paymentMethodName: string;
}
