/**
 * Wire models of an employee's scope-assignment history.
 *
 * They mirror the three backend DTOs exactly: `ScopeAssignmentRequest` is the create payload (the
 * one node the person is assigned and the first day covered, and **no** end date), the optional body
 * of the close route, and `ScopeAssignment` the read model. There is deliberately no update payload:
 * the record defines no `PUT` and no `DELETE`, so a move is a new assignment and a mistake is
 * closed.
 *
 * The **level** is the shape of the payload rather than a field of it: exactly one of the four node
 * ids is present, which the service validates and the database enforces. On the read side the level
 * comes back as the backend's own `ScopeLevel` name — the same level the claim is declared under —
 * so the four values here are `'COUNTRY' | 'REGION' | 'ZONE' | 'STORE'` and one row shape carries
 * them all. The company is **not** one of them: it has no node column and no assignable row, because
 * `company_id` is required, exactly one, and comes from `employees.company_id`. The screen renders
 * it as the derived root of the tree instead (see `ScopeAssignmentsSection`).
 *
 * Dates are the wire's ISO strings, like `employees.termination_date`. `validTo` is the **last day
 * covered**, inclusive — an open-ended assignment is `null`, and `null` is **not** the same fact as
 * "current": a row whose `validTo` has already passed is still `validTo != null` and covers nothing.
 * The column stores the exclusive bound and the backend converts at the DTO boundary, so the value
 * sent is the value read back, and an omitted `endDate` on the close route means "close today".
 *
 * `derived` is the ancestor chain of the assigned node, which is what the token will carry and what
 * the operator should see before trusting it: ids **and** their names, because "the operator should
 * see what they are granting" is useless as bare UUIDs. Every field is nullable, and a level the
 * chain cannot resolve is `null` — which must be rendered as an em dash **in place**, never dropped:
 * the chain's meaning is positional, so collapsing a missing country would relabel a zone as a
 * country.
 */
export interface ScopeAssignment {
  id: string;
  /** Which level this row assigns, derived by the backend from which node column is set. */
  level: ScopeAssignmentLevel;
  companyCountryId: string | null;
  companyRegionId: string | null;
  companyZoneId: string | null;
  /** The store's id for a `STORE` row, `null` at every level above it. */
  companyStoreId: string | null;
  /** The assigned node's own name; the chain below carries its ancestors' names. */
  nodeName: string;
  /** The first day covered. May be in the future: a future row derives nothing until it covers today. */
  validFrom: string;
  /** The **last day covered**, inclusive; `null` means open-ended. */
  validTo: string | null;
  /** The soft-delete flag. It is **not** the same fact as "closed": a closed row keeps `enabled`. */
  enabled: boolean;
  derived: ScopeAssignmentDerivedScope;
}

/**
 * The four levels a scope assignment can name, in the order the tree descends.
 *
 * The store is the fourth level of this table rather than a table of its own: one row per assigned
 * store, closed and derived exactly like the three levels above it. Company is not a level — one
 * employee, one company, and a company row would be a second copy of a fact the employee carries.
 */
export type ScopeAssignmentLevel = 'COUNTRY' | 'REGION' | 'ZONE' | 'STORE';

/** The four assignable levels, in the order the tree descends. */
export const SCOPE_ASSIGNMENT_LEVELS: readonly ScopeAssignmentLevel[] = [
  'COUNTRY',
  'REGION',
  'ZONE',
  'STORE',
];

/**
 * The ancestor chain of the assigned node, as the token will carry it.
 *
 * A country row resolves company and country and leaves region and zone `null`; a region row adds
 * its region; a zone row adds all three; a store row adds the store's own zone on top of that chain.
 */
export interface ScopeAssignmentDerivedScope {
  companyId: string | null;
  companyName: string | null;
  companyCountryId: string | null;
  companyCountryName: string | null;
  companyRegionId: string | null;
  companyRegionName: string | null;
  companyZoneId: string | null;
  companyZoneName: string | null;
}

/**
 * Write payload for a new scope assignment.
 *
 * Exactly one node id is present — the service validates it and the database enforces it — and there
 * is no `level` field, because a level field would be a second copy of what the chosen id already
 * says. There is no `enabled` field either (a new assignment is always enabled) and **no** end date:
 * an assignment ends only through the close route.
 */
export interface ScopeAssignmentRequest {
  companyCountryId?: string;
  companyRegionId?: string;
  companyZoneId?: string;
  companyStoreId?: string;
  validFrom: string;
}

/**
 * Optional body of `PATCH …/scope-assignments/{id}/close`.
 *
 * An omitted body and a `null` `endDate` both mean "close today"; when present, `endDate` is the
 * last day covered, inclusive.
 */
export interface CloseScopeAssignmentRequest {
  endDate?: string | null;
}

/** The three states a row's own two facts can produce. */
export type ScopeAssignmentStatus = 'open' | 'closed' | 'disabled';

/**
 * Whether the row is open, closed or soft-deleted.
 *
 * The soft-delete flag wins over the dates: a disabled row is neither open nor closed, because
 * nothing but `enabled` says whether it counts. Exported so the section's chip and its "can this be
 * closed?" decision read the very same predicate instead of a second truth.
 */
export function scopeAssignmentStatus(assignment: ScopeAssignment): ScopeAssignmentStatus {
  if (!assignment.enabled) {
    return 'disabled';
  }
  return assignment.validTo === null ? 'open' : 'closed';
}

/** The Spanish label of {@link scopeAssignmentStatus}. */
export function scopeAssignmentStatusLabel(assignment: ScopeAssignment): string {
  switch (scopeAssignmentStatus(assignment)) {
    case 'disabled':
      return 'Deshabilitada';
    case 'closed':
      return 'Cerrada';
    default:
      return 'Abierta';
  }
}

/**
 * The covered range of a row, in words.
 *
 * An open-ended assignment names itself as such instead of rendering an invented end date, so the
 * distinction the API makes (`null` versus a date) survives to the screen.
 */
export function scopeAssignmentRangeLabel(assignment: ScopeAssignment): string {
  return assignment.validTo === null
    ? `${assignment.validFrom} – Sin fecha de fin`
    : `${assignment.validFrom} – ${assignment.validTo}`;
}

/** The Spanish label of one assignable level. */
export function scopeAssignmentLevelLabel(level: ScopeAssignmentLevel): string {
  switch (level) {
    case 'REGION':
      return 'Región';
    case 'ZONE':
      return 'Zona';
    case 'STORE':
      return 'Tienda';
    default:
      return 'País';
  }
}

/** The em dash used for a level the chain does not resolve. */
const UNRESOLVED_LEVEL = '—';

/**
 * The four levels of the derived chain, in the order the token carries them.
 *
 * A level that is missing — or blank — is rendered as an em dash **in place**, never dropped: the
 * chain's meaning is positional, so collapsing a missing country would relabel a zone as a country.
 */
export function scopeDerivedChainLabel(scope: ScopeAssignmentDerivedScope): string {
  return [
    scope.companyName,
    scope.companyCountryName,
    scope.companyRegionName,
    scope.companyZoneName,
  ]
    .map((name) => (name && name.trim() !== '' ? name : UNRESOLVED_LEVEL))
    .join(' / ');
}
