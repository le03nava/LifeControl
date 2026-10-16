/**
 * Sales Order status transitions map.
 *
 * Mirrors backend sales-order workflow exactly: `Draft → Active → Pending → Completed`, with
 * `Cancelled` reachable from any open status. Terminal states (Completed, Cancelled) have no
 * outgoing transitions. The `Active` key matters: the first scanned line activates the order, so an
 * order sits in `Active` for the whole rest of the sale — it is not a status the API skips.
 *
 * All status names are in English — keys match DB seed names directly.
 */
export const SO_STATUS_TRANSITIONS: Record<string, string[]> = {
  Draft: ['Active', 'Cancelled'],
  Active: ['Pending', 'Cancelled'],
  Pending: ['Completed', 'Cancelled'],
  Completed: [],
  Cancelled: [],
};

/**
 * A status with no outgoing transition is terminal, and so is a status this map does not know.
 *
 * The same rule the backend applies in `SalesOrderService.isTerminalStatus`: it is derived from the
 * transition table rather than hardcoded, so it stays true if the status machine changes.
 */
export function isTerminalSalesOrderStatus(statusName: string): boolean {
  return (SO_STATUS_TRANSITIONS[statusName] ?? []).length === 0;
}

/**
 * Sales Order Item status transitions map.
 *
 * Terminal states (Added, Cancelled) have no outgoing transitions.
 */
export const SO_ITEM_STATUS_TRANSITIONS: Record<string, string[]> = {
  Pending: ['Added', 'Cancelled'],
  Added: [],
  Cancelled: [],
};

/** Status color mapping for Material chips (hex values). */
export const SO_STATUS_COLORS: Record<string, string> = {
  Draft: '#9e9e9e',
  Active: '#2196f3',
  Pending: '#ff9800',
  Completed: '#607d8b',
  Cancelled: '#f44336',
};

/** Human-readable labels for each status (English — same as keys). */
export const SO_STATUS_LABELS: Record<string, string> = {
  Draft: 'Draft',
  Active: 'Active',
  Pending: 'Pending',
  Completed: 'Completed',
  Cancelled: 'Cancelled',
};
