/**
 * Purchase Order status transitions map.
 *
 * Mirrors backend `PurchaseOrderService.PO_TRANSITIONS` exactly.
 * Each key is a status name; the value is the list of valid next statuses.
 * Terminal states (Closed, Rejected) have no outgoing transitions.
 *
 * Status names are the stable English values seeded by the backend (V3).
 * UUIDs are resolved at PATCH time from API response data.
 */
export const PO_STATUS_TRANSITIONS: Record<string, string[]> = {
  Draft: ['Sent', 'Rejected'],
  Sent: ['Accepted', 'Rejected'],
  Accepted: ['In Transit', 'Rejected'],
  'In Transit': ['Received', 'Rejected'],
  Received: ['Billed', 'Rejected'],
  Billed: ['Closed', 'Rejected'],
  Closed: [],
  Rejected: [],
};

/** Status color mapping for Material chips (used as `class="status-chip-<lowercase>"` or inline styles). */
export const PO_STATUS_COLORS: Record<string, string> = {
  Draft: '#9e9e9e',
  Sent: '#ff9800',
  Accepted: '#2196f3',
  'In Transit': '#00bcd4',
  Received: '#4caf50',
  Billed: '#009688',
  Closed: '#607d8b',
  Rejected: '#f44336',
};

/** Human-readable labels for each status. */
export const PO_STATUS_LABELS: Record<string, string> = {
  Draft: 'Borrador',
  Sent: 'Enviada',
  Accepted: 'Aceptada',
  'In Transit': 'En Tránsito',
  Received: 'Recibida',
  Billed: 'Facturada',
  Closed: 'Cerrada',
  Rejected: 'Rechazada',
};

/**
 * Linear happy-path order of the purchase-order lifecycle, used to render the
 * progress stepper. `Rejected` is an off-flow terminal state and is not listed.
 */
export const PO_STATUS_FLOW: string[] = [
  'Draft',
  'Sent',
  'Accepted',
  'In Transit',
  'Received',
  'Billed',
  'Closed',
];

// ─── Goods-receipt status family ────────────────────────────────────────
//
// The receipt statuses are seeded by the backend under the `GOODS_RECEIPT`
// reference-data type. Today the backend seeds only `Registered`; the maps
// keep the same shape as the order and detail families so an unknown or
// future status always falls back safely.

/** Receipt statuses (backend `GOODS_RECEIPT` family; the backend seeds only `Registered`). */
export const GOODS_RECEIPT_STATUS_LABELS: Record<string, string> = { Registered: 'Registrado' };

/** Status color mapping for Material chips, keyed by the stable English status name. */
export const GOODS_RECEIPT_STATUS_COLORS: Record<string, string> = { Registered: '#4caf50' };

// ─── Purchase-order DETAIL status family ────────────────────────────────
//
// The line (detail) statuses are seeded by the backend's V3 reference-data
// migration under the `PURCHASE_ORDER_DETAIL` type. They are a separate family
// from the order statuses above and are NOT reachable through `PO_STATUS_*`.

/**
 * Order statuses a goods receipt may be registered against.
 *
 * Mirrors `PurchaseOrderService.requireReceivable`, which admits only
 * `Accepted` and `In Transit` (compared case-insensitively on the backend).
 */
export const ORDER_RECEIVABLE_STATUSES = ['Accepted', 'In Transit'];

/** Line statuses that can still receive goods; the terminal Received/Rejected/Cancelled cannot. */
export const RECEIVABLE_DETAIL_STATUSES = [
  'Pending',
  'In Process',
  'In Transit',
  'Partial Received',
];

/** Human-readable Spanish labels for each purchase-order detail status. */
export const PO_DETAIL_STATUS_LABELS: Record<string, string> = {
  Pending: 'Pendiente',
  'In Process': 'En Proceso',
  'In Transit': 'En Tránsito',
  'Partial Received': 'Parcialmente Recibida',
  Received: 'Recibida',
  Rejected: 'Rechazada',
  Cancelled: 'Cancelada',
};

/** Status color mapping for Material chips, keyed by the stable English status name. */
export const PO_DETAIL_STATUS_COLORS: Record<string, string> = {
  Pending: '#9e9e9e',
  'In Process': '#2196f3',
  'In Transit': '#00bcd4',
  'Partial Received': '#ff9800',
  Received: '#4caf50',
  Rejected: '#f44336',
  Cancelled: '#607d8b',
};

/**
 * Whether a purchase-order header status may register a goods receipt.
 *
 * Fail-closed by design: a `null`, `undefined`, empty or unrecognised status
 * returns `false`, because an unknown status must never be offered as
 * receivable. The comparison is case-insensitive to match
 * `PurchaseOrderService.requireReceivable`.
 */
export function isOrderReceivable(statusName: string | null | undefined): boolean {
  if (!statusName) {
    return false;
  }
  const normalized = statusName.toLowerCase();
  return ORDER_RECEIVABLE_STATUSES.some((status) => status.toLowerCase() === normalized);
}

/**
 * Whether a purchase-order line status can still receive goods.
 *
 * Fail-closed by design: a `null`, `undefined`, empty or unrecognised status
 * returns `false`, so the terminal `Received`/`Rejected`/`Cancelled` lines are
 * never offered as receivable. The comparison is case-insensitive to match the
 * backend's reachability walk in `PurchaseOrderService.requireDetailReceivable`.
 */
export function isDetailStatusReceivable(statusName: string | null | undefined): boolean {
  if (!statusName) {
    return false;
  }
  const normalized = statusName.toLowerCase();
  return RECEIVABLE_DETAIL_STATUSES.some((status) => status.toLowerCase() === normalized);
}
