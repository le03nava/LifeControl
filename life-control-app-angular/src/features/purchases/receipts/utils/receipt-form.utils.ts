/**
 * Pure helpers for the goods-receipt registration form.
 *
 * Deliberately free of Angular: `inject`, signals and HTTP all stay in the page
 * component, so these functions are plain data transformations that can be
 * unit-tested without a `TestBed`.
 */

import { isDetailStatusReceivable } from '../../purchase-orders/data/status-config';
import type { PurchaseOrderDetail } from '../../purchase-orders/models/purchase-order.models';
import type { GoodsReceiptLineRequest } from '../models/receipt.models';

/** One purchase-order line that can still receive goods, with its pending amount. */
export interface ReceivableLine {
  detailId: string;
  productName: string;
  variantName: string | null;
  ordered: number;
  alreadyReceived: number;
  pending: number;
}

/** A receivable line plus the quantity the operator typed for this reception. */
export interface ReceiptLineDraft extends ReceivableLine {
  quantity: number;
}

/**
 * Lines that can still receive goods AND have a positive pending amount, in the
 * order's own order.
 *
 * A line is excluded when its detail status is not receivable (terminal lines
 * such as `Received`, `Rejected` or `Cancelled`) or when `pending <= 0`. The
 * pending amount is `quantity - receivedQuantity` computed with the numbers as
 * they arrive: it is never rounded, so a fractional unit stays fractional.
 */
export function buildReceivableLines(details: readonly PurchaseOrderDetail[]): ReceivableLine[] {
  const lines: ReceivableLine[] = [];

  for (const detail of details) {
    if (!isDetailStatusReceivable(detail.statusName)) {
      continue;
    }

    // The type declares `receivedQuantity` as a number, but legacy rows can
    // carry `undefined`/`null` at runtime. Treat a missing value as "nothing
    // received yet" instead of leaking `NaN` into every later computation.
    const alreadyReceived = detail.receivedQuantity ?? 0;
    const pending = detail.quantity - alreadyReceived;

    if (pending <= 0) {
      continue;
    }

    lines.push({
      detailId: detail.id,
      productName: detail.productName,
      variantName: detail.productVariantName,
      ordered: detail.quantity,
      alreadyReceived,
      pending,
    });
  }

  return lines;
}

/** Draft rows from receivable lines; every quantity defaults to its pending amount. */
export function toDraftLines(lines: readonly ReceivableLine[]): ReceiptLineDraft[] {
  return lines.map((line) => ({ ...line, quantity: line.pending }));
}

/**
 * Validation of ONE draft row. Returns `null` when the row is acceptable.
 *
 * `quantity === 0` means "skip this line" and is always acceptable; a negative,
 * non-integer (this also catches `NaN` and `Infinity`), or pending-exceeding
 * quantity is not.
 */
export function validateQuantity(draft: ReceiptLineDraft): string | null {
  const { quantity, pending } = draft;

  if (quantity === 0) {
    return null;
  }
  if (quantity < 0) {
    return 'No puede ser negativo';
  }
  if (!Number.isInteger(quantity)) {
    return 'Debe ser un número entero';
  }
  if (quantity > pending) {
    return `No puede superar lo pendiente (${pending})`;
  }

  return null;
}

/** The drafts the operator actually wants to receive: quantity > 0. */
export function selectedDrafts(drafts: readonly ReceiptLineDraft[]): ReceiptLineDraft[] {
  return drafts.filter((draft) => draft.quantity > 0);
}

/** Request lines for the selected drafts, in the given order. */
export function toRequestLines(selected: readonly ReceiptLineDraft[]): GoodsReceiptLineRequest[] {
  return selected.map((draft) => ({
    purchaseOrderDetailId: draft.detailId,
    quantityReceived: draft.quantity,
  }));
}

/**
 * Whole-form check: every selected line valid AND at least one line selected.
 *
 * Per-row errors are keyed by `detailId`; the missing-selection error is keyed
 * by the reserved `lines` key.
 */
export function validateDrafts(drafts: readonly ReceiptLineDraft[]): {
  valid: boolean;
  errors: Record<string, string>;
} {
  const errors: Record<string, string> = {};

  for (const draft of drafts) {
    const error = validateQuantity(draft);
    if (error !== null) {
      errors[draft.detailId] = error;
    }
  }

  if (selectedDrafts(drafts).length === 0) {
    errors['lines'] = 'Elegí al menos una línea para recibir';
  }

  return { valid: Object.keys(errors).length === 0, errors };
}
