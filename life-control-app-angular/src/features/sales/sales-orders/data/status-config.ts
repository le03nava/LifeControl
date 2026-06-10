/**
 * Sales Order status transitions map.
 *
 * Mirrors backend sales-order workflow exactly.
 * Each key is a status name; the value is the list of valid next statuses.
 * Terminal states (Completed, Cancelled) have no outgoing transitions.
 *
 * All status names are in English — keys match DB seed names directly.
 */
export const SO_STATUS_TRANSITIONS: Record<string, string[]> = {
  'Draft':      ['Pending', 'Cancelled'],
  'Pending':    ['Completed', 'Cancelled'],
  'Completed':  [],
  'Cancelled':  [],
};

/**
 * Sales Order Item status transitions map.
 *
 * Terminal states (Added, Cancelled) have no outgoing transitions.
 */
export const SO_ITEM_STATUS_TRANSITIONS: Record<string, string[]> = {
  'Pending':   ['Added', 'Cancelled'],
  'Added':     [],
  'Cancelled': [],
};

/** Status color mapping for Material chips (hex values). */
export const SO_STATUS_COLORS: Record<string, string> = {
  'Draft':      '#9e9e9e',
  'Pending':    '#ff9800',
  'Completed':  '#607d8b',
  'Cancelled':  '#f44336',
};

/** Human-readable labels for each status (English — same as keys). */
export const SO_STATUS_LABELS: Record<string, string> = {
  'Draft':      'Draft',
  'Pending':    'Pending',
  'Completed':  'Completed',
  'Cancelled':  'Cancelled',
};
