import { describe, expect, it } from 'vitest';
import {
  buildReceivableLines,
  selectedDrafts,
  toDraftLines,
  toRequestLines,
  validateDrafts,
  validateQuantity,
  type ReceiptLineDraft,
} from './receipt-form.utils';
import { RECEIVABLE_DETAIL_STATUSES } from '../../purchase-orders/data/status-config';
import type { PurchaseOrderDetail } from '../../purchase-orders/models/purchase-order.models';

function detail(overrides: Partial<PurchaseOrderDetail> = {}): PurchaseOrderDetail {
  return {
    id: 'det-1',
    purchaseOrderId: 'po-1',
    productId: 'prod-1',
    productName: 'Widget A',
    productVariantId: 'var-1',
    productVariantName: 'Presentación 1L',
    quantity: 10,
    unitPrice: 100,
    total: 1000,
    receivedQuantity: 0,
    comments: null,
    statusId: 'st-pending',
    statusName: 'Pending',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  };
}

function draft(overrides: Partial<ReceiptLineDraft> = {}): ReceiptLineDraft {
  return {
    detailId: 'det-1',
    productName: 'Widget A',
    variantName: 'Presentación 1L',
    ordered: 10,
    alreadyReceived: 4,
    pending: 6,
    quantity: 6,
    ...overrides,
  };
}

describe('buildReceivableLines', () => {
  it('keeps every receivable detail status', () => {
    const details = RECEIVABLE_DETAIL_STATUSES.map((statusName, index) =>
      detail({ id: `det-${index}`, statusName }),
    );

    const lines = buildReceivableLines(details);

    expect(lines.length).toBe(RECEIVABLE_DETAIL_STATUSES.length);
    expect(lines.map((line) => line.detailId)).toEqual(details.map((d) => d.id));
  });

  it('excludes the terminal Received, Rejected and Cancelled statuses', () => {
    const details = ['Received', 'Rejected', 'Cancelled'].map((statusName, index) =>
      detail({ id: `det-${index}`, statusName }),
    );

    expect(buildReceivableLines(details)).toEqual([]);
  });

  it('excludes unknown, empty and null detail statuses', () => {
    const details = [
      detail({ id: 'det-unknown', statusName: 'Something Else' }),
      detail({ id: 'det-empty', statusName: '' }),
      detail({ id: 'det-null', statusName: null as unknown as string }),
    ];

    expect(buildReceivableLines(details)).toEqual([]);
  });

  it('excludes a line whose pending amount is exactly zero', () => {
    const lines = buildReceivableLines([detail({ quantity: 5, receivedQuantity: 5 })]);

    expect(lines).toEqual([]);
  });

  it('excludes an over-received line whose pending amount is negative', () => {
    const lines = buildReceivableLines([detail({ quantity: 5, receivedQuantity: 8 })]);

    expect(lines).toEqual([]);
  });

  it('computes pending as quantity minus receivedQuantity', () => {
    const lines = buildReceivableLines([detail({ quantity: 10, receivedQuantity: 4 })]);

    expect(lines).toHaveLength(1);
    expect(lines[0].ordered).toBe(10);
    expect(lines[0].alreadyReceived).toBe(4);
    expect(lines[0].pending).toBe(6);
  });

  it('never rounds a fractional pending amount', () => {
    const lines = buildReceivableLines([detail({ quantity: 2.5, receivedQuantity: 1 })]);

    expect(lines[0].pending).toBe(1.5);
  });

  it('carries the product and variant names', () => {
    const lines = buildReceivableLines([
      detail({ productName: 'Widget B', productVariantName: null }),
    ]);

    expect(lines[0].productName).toBe('Widget B');
    expect(lines[0].variantName).toBeNull();
  });

  it('treats an undefined receivedQuantity as zero', () => {
    const lines = buildReceivableLines([
      detail({ quantity: 7, receivedQuantity: undefined as unknown as number }),
    ]);

    expect(lines).toHaveLength(1);
    expect(lines[0].alreadyReceived).toBe(0);
    expect(lines[0].pending).toBe(7);
  });

  it('treats a null receivedQuantity as zero', () => {
    const lines = buildReceivableLines([
      detail({ quantity: 7, receivedQuantity: null as unknown as number }),
    ]);

    expect(lines).toHaveLength(1);
    expect(lines[0].alreadyReceived).toBe(0);
    expect(lines[0].pending).toBe(7);
  });

  it('preserves the order of the eligible lines', () => {
    const lines = buildReceivableLines([
      detail({ id: 'det-1' }),
      detail({ id: 'det-2', statusName: 'Received' }),
      detail({ id: 'det-3' }),
      detail({ id: 'det-4', quantity: 1, receivedQuantity: 1 }),
      detail({ id: 'det-5' }),
    ]);

    expect(lines.map((line) => line.detailId)).toEqual(['det-1', 'det-3', 'det-5']);
  });

  it('returns an empty array for an empty detail list', () => {
    expect(buildReceivableLines([])).toEqual([]);
  });
});

describe('toDraftLines', () => {
  it('returns no drafts for no lines', () => {
    expect(toDraftLines([])).toEqual([]);
  });

  it('defaults every quantity to its pending amount', () => {
    const lines = buildReceivableLines([
      detail({ id: 'det-1', quantity: 10, receivedQuantity: 4 }),
      detail({ id: 'det-2', quantity: 3, receivedQuantity: 1 }),
    ]);

    const drafts = toDraftLines(lines);

    expect(drafts.map((d) => d.quantity)).toEqual([6, 2]);
    expect(drafts[0].pending).toBe(6);
  });
});

describe('validateQuantity', () => {
  it('accepts zero as "skip this line"', () => {
    expect(validateQuantity(draft({ quantity: 0 }))).toBeNull();
  });

  it('rejects a negative quantity', () => {
    expect(validateQuantity(draft({ quantity: -1 }))).toBe('No puede ser negativo');
  });

  it('rejects a fractional quantity', () => {
    expect(validateQuantity(draft({ quantity: 1.5 }))).toBe('Debe ser un número entero');
  });

  it('rejects a NaN quantity as a non-integer', () => {
    expect(validateQuantity(draft({ quantity: Number.NaN }))).toBe('Debe ser un número entero');
  });

  it('accepts exactly the pending amount', () => {
    expect(validateQuantity(draft({ pending: 6, quantity: 6 }))).toBeNull();
  });

  it('accepts less than the pending amount', () => {
    expect(validateQuantity(draft({ pending: 6, quantity: 1 }))).toBeNull();
  });

  it('rejects more than the pending amount and names the pending value', () => {
    expect(validateQuantity(draft({ pending: 6, quantity: 7 }))).toBe(
      'No puede superar lo pendiente (6)',
    );
  });
});

describe('selectedDrafts', () => {
  it('keeps only the drafts with a positive quantity, in order', () => {
    const drafts = [
      draft({ detailId: 'det-1', quantity: 2 }),
      draft({ detailId: 'det-2', quantity: 0 }),
      draft({ detailId: 'det-3', quantity: 6 }),
    ];

    expect(selectedDrafts(drafts).map((d) => d.detailId)).toEqual(['det-1', 'det-3']);
  });

  it('returns an empty array when nothing is selected', () => {
    expect(selectedDrafts([draft({ quantity: 0 })])).toEqual([]);
  });
});

describe('toRequestLines', () => {
  it('maps each selected draft to its request line, preserving order', () => {
    const selected = [
      draft({ detailId: 'det-3', quantity: 2 }),
      draft({ detailId: 'det-1', quantity: 5 }),
    ];

    expect(toRequestLines(selected)).toEqual([
      { purchaseOrderDetailId: 'det-3', quantityReceived: 2 },
      { purchaseOrderDetailId: 'det-1', quantityReceived: 5 },
    ]);
  });

  it('maps an empty selection to an empty request list', () => {
    expect(toRequestLines([])).toEqual([]);
  });
});

describe('validateDrafts', () => {
  it('flags an empty selection with the reserved lines key', () => {
    const result = validateDrafts([]);

    expect(result.valid).toBe(false);
    expect(result.errors).toEqual({ lines: 'Elegí al menos una línea para recibir' });
  });

  it('flags a selection where every row is skipped', () => {
    const result = validateDrafts([draft({ detailId: 'det-1', quantity: 0 })]);

    expect(result.valid).toBe(false);
    expect(result.errors['lines']).toBe('Elegí al menos una línea para recibir');
  });

  it('flags a single invalid row by its detail id while another row is valid', () => {
    const result = validateDrafts([
      draft({ detailId: 'det-1', pending: 6, quantity: 6 }),
      draft({ detailId: 'det-2', pending: 6, quantity: 9 }),
    ]);

    expect(result.valid).toBe(false);
    expect(result.errors).toEqual({ 'det-2': 'No puede superar lo pendiente (6)' });
  });

  it('accepts a selection whose rows are all valid', () => {
    const result = validateDrafts([
      draft({ detailId: 'det-1', pending: 6, quantity: 6 }),
      draft({ detailId: 'det-2', pending: 3, quantity: 0 }),
    ]);

    expect(result.valid).toBe(true);
    expect(result.errors).toEqual({});
  });
});
