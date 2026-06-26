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
  details: PurchaseOrderDetail[];
}

export interface PurchaseOrderDetail {
  id: string;
  purchaseOrderId: string;
  productId: string;
  productName: string;
  quantity: number;
  unitPrice: number;
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
