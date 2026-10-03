/**
 * Client mirror of the server's employee status family and its Spanish labels.
 *
 * The four names are transcribed from `V20__employee_registry.sql`, which seeds
 * `Active`, `Inactive`, `OnLeave` and `Terminated` under the `EMPLOYEE_STATUS`
 * type. Keeping the server anchor in this comment is what makes a seed rename a
 * review-visible drift instead of a silent one.
 */

/** The status type name the employee statuses belong to. */
export const EMPLOYEE_STATUS_TYPE_NAME = 'EMPLOYEE_STATUS';

/**
 * Spanish labels for the four seeded status names — the roster's status copy.
 *
 * Keys are the server's names exactly, as `V20` seeds them. A name this map does
 * not know falls back to the raw name through {@link employeeStatusLabel}.
 */
export const EMPLOYEE_STATUS_LABELS: Readonly<Record<string, string>> = {
  Active: 'Activo',
  Inactive: 'Inactivo',
  OnLeave: 'Con licencia',
  Terminated: 'Dado de baja',
};

/**
 * The Spanish label for `statusName`, or the raw name when the map does not know
 * it — never `undefined` and never an empty string for a non-empty name.
 *
 * `Object.hasOwn` guards the prototype chain: a bare lookup would let a name like
 * `'constructor'` reach `Object.prototype` and return a function instead of the
 * fallback.
 */
export function employeeStatusLabel(statusName: string): string {
  return Object.hasOwn(EMPLOYEE_STATUS_LABELS, statusName)
    ? EMPLOYEE_STATUS_LABELS[statusName]
    : statusName;
}
