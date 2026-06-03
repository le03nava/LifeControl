/**
 * Purchase Order status transitions map.
 *
 * Mirrors backend `PurchaseOrderService.PO_TRANSITIONS` exactly.
 * Each key is a status name; the value is the list of valid next statuses.
 * Terminal states (Cerrada, Rechazada) have no outgoing transitions.
 *
 * Status names are stable and seeded by the backend initializer.
 * UUIDs are resolved at PATCH time from API response data.
 */
export const PO_STATUS_TRANSITIONS: Record<string, string[]> = {
  'Draft':       ['Sent', 'Rechazada'],
  'Sent':        ['Accepted', 'Rechazada'],
  'Accepted':    ['In Transit', 'Rechazada'],
  'In Transit':  ['Received', 'Rechazada'],
  'Received':    ['Facturada', 'Rechazada'],
  'Facturada':   ['Cerrada', 'Rechazada'],
  'Cerrada':     [],
  'Rechazada':   [],
};

/** Status color mapping for Material chips (used as `class="status-chip-<lowercase>"` or inline styles). */
export const PO_STATUS_COLORS: Record<string, string> = {
  'Draft':       '#9e9e9e',
  'Sent':        '#ff9800',
  'Accepted':    '#2196f3',
  'In Transit':  '#00bcd4',
  'Received':    '#4caf50',
  'Facturada':   '#009688',
  'Cerrada':     '#607d8b',
  'Rechazada':   '#f44336',
};

/** Human-readable labels for each status. */
export const PO_STATUS_LABELS: Record<string, string> = {
  'Draft':       'Borrador',
  'Sent':        'Enviada',
  'Accepted':    'Aceptada',
  'In Transit':  'En Tránsito',
  'Received':    'Recibida',
  'Facturada':   'Facturada',
  'Cerrada':     'Cerrada',
  'Rechazada':   'Rechazada',
};
