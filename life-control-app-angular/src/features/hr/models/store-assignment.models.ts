/**
 * Wire models of an employee's store-assignment history.
 *
 * They mirror the three backend DTOs exactly: `StoreAssignmentRequest` is the create payload
 * (`T13`: the store and the first day covered, and **no** end date), `CloseStoreAssignmentRequest`
 * the optional body of the close route, and `StoreAssignment` the `StoreAssignmentResponse` read
 * model. There is deliberately no update payload: the record defines no `PUT` and no `DELETE`
 * (`T5`), so a transfer is a new assignment and a mistake is closed.
 *
 * Dates are the wire's ISO strings, like `employees.termination_date`. `validTo` is the **last day
 * covered**, inclusive (`D5`) — an open-ended assignment is `null`, and `null` is **not** the same
 * fact as "current". The column stores the exclusive bound and the backend converts at the DTO
 * boundary, so the value sent is the value read back.
 *
 * `derived` is the ancestor chain of the assigned store (`T14`): the four ancestor ids **and** their
 * names, which is what the token will carry and what the operator should see before trusting it.
 * Every name is nullable because the backend reports the chain only as far as the entity graph
 * resolves it in one hop — a level it cannot reach is omitted **together with everything below it**,
 * and the display keeps each level in its own position rather than shifting the survivors.
 */
export interface StoreAssignment {
  id: string;
  companyStoreId: string;
  companyStoreName: string;
  /** The first day covered. May be in the future: a future row derives nothing until it covers today. */
  validFrom: string;
  /** The **last day covered**, inclusive; `null` means open-ended. */
  validTo: string | null;
  /** The soft-delete flag. It is **not** the same fact as "closed": a closed row keeps `enabled`. */
  enabled: boolean;
  derived: StoreAssignmentDerivedScope;
}

/** The ancestor chain of the assigned store, as the token will carry it. */
export interface StoreAssignmentDerivedScope {
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
 * Write payload for a new assignment.
 *
 * Shared by nothing: create is the only assignment write. There is deliberately no `enabled` field —
 * a new assignment is always enabled — and no end date (`T13`): an assignment ends only through the
 * close route.
 */
export interface StoreAssignmentRequest {
  companyStoreId: string;
  validFrom: string;
}

/**
 * Optional body of `PATCH …/store-assignments/{id}/close`.
 *
 * An omitted body and a `null` `endDate` both mean "close today"; when present, `endDate` is the
 * last day covered, inclusive (`D5`).
 */
export interface CloseStoreAssignmentRequest {
  endDate?: string | null;
}

/** The three states a row's own two facts can produce. */
export type StoreAssignmentStatus = 'open' | 'closed' | 'disabled';

/**
 * Whether the row is open, closed or soft-deleted.
 *
 * The soft-delete flag wins over the dates: a disabled row is neither open nor closed, because
 * nothing but `enabled` says whether it counts. Exported so the section's chip and its "can this be
 * closed?" decision read the very same predicate instead of a second truth.
 */
export function assignmentStatus(assignment: StoreAssignment): StoreAssignmentStatus {
  if (!assignment.enabled) {
    return 'disabled';
  }
  return assignment.validTo === null ? 'open' : 'closed';
}

/** The Spanish label of {@link assignmentStatus}. */
export function assignmentStatusLabel(assignment: StoreAssignment): string {
  switch (assignmentStatus(assignment)) {
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
export function assignmentRangeLabel(assignment: StoreAssignment): string {
  return assignment.validTo === null
    ? `${assignment.validFrom} – Sin fecha de fin`
    : `${assignment.validFrom} – ${assignment.validTo}`;
}

/** The em dash used for a level the chain does not resolve. */
const UNRESOLVED_LEVEL = '—';

/**
 * The four levels of the derived chain, in the order the token carries them.
 *
 * A level that is missing — or blank — is rendered as an em dash **in place**, never dropped: the
 * chain's meaning is positional, so collapsing a missing country would relabel a zone as a country.
 */
export function derivedChainLabel(scope: StoreAssignmentDerivedScope): string {
  return [
    scope.companyName,
    scope.companyCountryName,
    scope.companyRegionName,
    scope.companyZoneName,
  ]
    .map((name) => (name && name.trim() !== '' ? name : UNRESOLVED_LEVEL))
    .join(' / ');
}
