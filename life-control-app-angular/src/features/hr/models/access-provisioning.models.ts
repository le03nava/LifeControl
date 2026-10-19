/**
 * Wire models of one employee's access-provisioning state, the company's decision queue and the
 * decision payload.
 *
 * They mirror the backend DTOs of `com.lifecontrol.api.provisioning.dto` exactly, field names and
 * casing included: `AccessProvisioningOverview` is the read model of `GET …/access`,
 * `AccessProvisioningTaskView` one task as that read composes it, `AccessProvisioningClaims` the
 * five tenancy claim values the token would carry, `AccessProvisioningRoleDiff` the two role sets a
 * convergence would touch, `AccessProvisioningQueueEntry` one row of
 * `GET /api/companies/{companyId}/access/requests`, and `AccessProvisioningRejectionRequest` the
 * body of the reject route.
 *
 * Dates are the wire's ISO strings (`LocalDateTime` serialized by the API), like
 * `employees.termination_date`. Every nullable field is typed `| null` and never `| undefined`: the
 * API answers JSON, where an absent value is `null`.
 *
 * The role sets arrive as JSON arrays even though the backend normalizes them to `TreeSet`, so they
 * are ordered ascending and the UI renders that order rather than re-sorting.
 */

/** The task's immutable reference: what work the task represents. Mirrors `AccessProvisioningTaskKind`. */
export type AccessProvisioningTaskKind = 'ACTIVATE' | 'DEACTIVATE' | 'RECONCILE';

/**
 * The task's life-cycle status. Mirrors `AccessProvisioningTaskStatus`.
 *
 * `PENDING`, `APPROVAL_PENDING`, `RUNNING` and `FAILED` are the open states — at most one per
 * employee — while `APPLIED`, `REJECTED` and `SUPERSEDED` are terminal.
 */
export type AccessProvisioningTaskStatus =
  | 'PENDING'
  | 'APPROVAL_PENDING'
  | 'RUNNING'
  | 'APPLIED'
  | 'FAILED'
  | 'REJECTED'
  | 'SUPERSEDED';

/** Spanish labels for the seven task states. Keys are the server's enum names exactly. */
export const ACCESS_TASK_STATUS_LABELS: Readonly<Record<AccessProvisioningTaskStatus, string>> = {
  PENDING: 'Pendiente',
  APPROVAL_PENDING: 'Esperando aprobación',
  RUNNING: 'En ejecución',
  APPLIED: 'Aplicada',
  FAILED: 'Fallida',
  REJECTED: 'Rechazada',
  SUPERSEDED: 'Reemplazada',
};

/** Spanish labels for the three task kinds. Keys are the server's enum names exactly. */
export const ACCESS_TASK_KIND_LABELS: Readonly<Record<AccessProvisioningTaskKind, string>> = {
  ACTIVATE: 'Activación',
  DEACTIVATE: 'Desactivación',
  RECONCILE: 'Reconciliación',
};

/**
 * The Spanish label for `status`, or the raw value when the map does not know it — never
 * `undefined` and never an empty string.
 *
 * `Object.hasOwn` guards the prototype chain: a bare lookup would let a value like `'constructor'`
 * reach `Object.prototype` and return a function instead of the fallback.
 */
export function accessTaskStatusLabel(status: AccessProvisioningTaskStatus): string {
  return Object.hasOwn(ACCESS_TASK_STATUS_LABELS, status)
    ? ACCESS_TASK_STATUS_LABELS[status]
    : status;
}

/** The Spanish label for `kind`, with the same fallback and prototype guard as {@link accessTaskStatusLabel}. */
export function accessTaskKindLabel(kind: AccessProvisioningTaskKind): string {
  return Object.hasOwn(ACCESS_TASK_KIND_LABELS, kind) ? ACCESS_TASK_KIND_LABELS[kind] : kind;
}

/** The roles a convergence would touch: what is missing and what is no longer required. */
export interface AccessProvisioningRoleDiff {
  /** The required roles the account does not hold. */
  added: string[];
  /** The account's grantable roles that are no longer required. */
  removed: string[];
}

/** The five tenancy claim values the account would publish, each as the token's own list of ids. */
export interface AccessProvisioningClaims {
  /** The `company_id` claim values; exactly one for a converged account. */
  companyId: string[];
  companyCountryId: string[];
  companyRegionId: string[];
  companyZoneId: string[];
  companyStoreId: string[];
}

/**
 * The five claim fields in the order the token carries them, with the claim name the token uses.
 *
 * The UI renders from this list so the five levels cannot drift from the DTO, and a level that is
 * not held (an empty list) is rendered as "not held" instead of being dropped.
 */
export const ACCESS_CLAIM_FIELDS: readonly {
  field: keyof AccessProvisioningClaims;
  claim: string;
}[] = [
  { field: 'companyId', claim: 'company_id' },
  { field: 'companyCountryId', claim: 'company_country_id' },
  { field: 'companyRegionId', claim: 'company_region_id' },
  { field: 'companyZoneId', claim: 'company_zone_id' },
  { field: 'companyStoreId', claim: 'company_store_id' },
];

/** One durable access-provisioning task as the Access section reads it. */
export interface AccessProvisioningTaskView {
  id: string;
  kind: AccessProvisioningTaskKind;
  status: AccessProvisioningTaskStatus;
  /** How many times the task has been claimed. Render against `maxAttempts`, never alone. */
  attempts: number;
  /** The configured ceiling `attempts` is rendered against. */
  maxAttempts: number;
  /** The visible reason the task is not applied: a failure or a rejection. */
  lastError: string | null;
  /** The backoff deadline while the task waits; `null` means due immediately. */
  nextAttemptAt: string | null;
  /** Who asked for the task: the requester's JWT `sub`. */
  requestedBy: string | null;
  /** The requester's display name, or `null` when it cannot be resolved (gap G42). */
  requestedByName: string | null;
  requestedAt: string | null;
  /** Who approved or refused it; `null` until a gate decides. */
  decidedBy: string | null;
  decidedAt: string | null;
  /** The row's declared-but-unconsumed flag. */
  enabled: boolean;
}

/**
 * The whole read model of one employee's access.
 *
 * `roleDiff` is the **live** diff, recomputed on every read (record T7). `frozenRoleDiff` is the
 * **different fact** the open task froze for review (record T92), and it is `null` when no task is
 * open and also when the open task froze nothing — the UI has to tell the two apart and say which
 * one it is showing, never render an empty set in its place.
 */
export interface AccessProvisioningOverview {
  /** `employees.keycloak_user_id`, or `null` when the account is not linked. */
  keycloakUserId: string | null;
  accountLinked: boolean;
  requiredRoles: string[];
  currentRoles: string[];
  roleDiff: AccessProvisioningRoleDiff;
  /** The set the open task froze for review, or `null` when there is none (record T92). */
  frozenRoleDiff: AccessProvisioningRoleDiff | null;
  /** The newest task in an open status, or `null` when none is open. */
  openTask: AccessProvisioningTaskView | null;
  /** Every task of the employee, newest first. */
  history: AccessProvisioningTaskView[];
  claims: AccessProvisioningClaims;
  /**
   * Whether the access-provisioning worker runs in this environment (record S3-D).
   *
   * Optional on purpose, and the one place this file's "absent is `null`" rule does not apply: the
   * field is additive, so a backend deployed before it answers a payload with no `workerEnabled` at
   * all. `undefined` therefore means **unknown** and must never be read as disabled — a screen that
   * asserted "the worker is off" from a field it never received would be inventing the same silent
   * default this notice exists to surface.
   */
  workerEnabled?: boolean;
}

/**
 * One row of a company's decision queue: a task waiting for a human, with the employee it belongs
 * to.
 *
 * Narrower than {@link AccessProvisioningTaskView} on purpose, and it deliberately carries no role
 * set: the dialog that decides reads the frozen set from the employee's own overview (record T95).
 */
export interface AccessProvisioningQueueEntry {
  taskId: string;
  kind: AccessProvisioningTaskKind;
  status: AccessProvisioningTaskStatus;
  /** Who asked, as the row records them — the JWT `sub` the self-approval rule compares. */
  requestedBy: string | null;
  /** The requester's display name, or `null` when it cannot be resolved (gap G42). */
  requestedByName: string | null;
  requestedAt: string | null;
  employeeId: string;
  employeeNumber: string;
  employeeFirstName: string;
  employeePaternalLastName: string;
  employeeMaternalLastName: string | null;
}

/**
 * The body of `POST …/requests/{taskId}/reject`.
 *
 * There is no bean validation on the server, so the required non-blank reason is the dialog's
 * obligation; a blank one is refused with 409 (record T95).
 */
export interface AccessProvisioningRejectionRequest {
  reason: string;
}
