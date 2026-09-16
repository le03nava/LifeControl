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
