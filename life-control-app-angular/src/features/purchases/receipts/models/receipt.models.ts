/**
 * Client models for the goods receipts REST contract.
 *
 * Field-for-field mirror of the backend DTOs (`GoodsReceiptResponse`,
 * `GoodsReceiptLineResponse`, `GoodsReceiptRequest`, `GoodsReceiptLineRequest`).
 * No renames and no extra fields: the backend is the single source of truth.
 */

/** One line of a persisted goods receipt. */
export interface GoodsReceiptLine {
  id: string;
  purchaseOrderDetailId: string;
  productVariantId: string;
  /** JSON number (backend `BigDecimal`, column `DECIMAL(12,2)`); never a string. */
  quantityReceived: number;
  comments: string | null;
}

/** A persisted goods receipt, with order number and status name denormalized. */
export interface GoodsReceipt {
  id: string;
  receiptNumber: string;
  purchaseOrderId: string;
  orderNumber: string;
  companyStoreId: string;
  receivingLocationId: string;
  statusId: string;
  statusName: string;
  receivedBy: string | null;
  receivedAt: string;
  comments: string | null;
  enabled: boolean;
  lines: GoodsReceiptLine[];
}

/** One reception line of a goods-receipt request. */
export interface GoodsReceiptLineRequest {
  purchaseOrderDetailId: string;
  /** JSON number (backend `BigDecimal`); the backend rejects values below 0.01. */
  quantityReceived: number;
  comments?: string | null;
}

/** Request body to register a reception against a purchase order. */
export interface GoodsReceiptRequest {
  purchaseOrderId: string;
  /** `null`/omitted means "use the store's configured receiving location". */
  receivingLocationId?: string | null;
  comments?: string | null;
  lines: GoodsReceiptLineRequest[];
}
