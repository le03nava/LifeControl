import type { StoreAssignmentDerivedScope } from '@features/hr/models/store-assignment.models';

/**
 * Response from GET /api/profile
 * Combines basic identity info (from Keycloak) with location preferences (from user_preferences).
 */
export interface ProfileResponse {
  keycloakUserId: string;
  username: string;
  email: string;
  firstName: string;
  lastName: string;
  /**
   * The resolved store preference (decision D10), or `null` when there is none.
   *
   * The four tenancy ancestors (`companyId`, `companyCountryId`, `companyRegionId`, `companyZoneId`)
   * are **deliberately absent** since 2026-10-14 (W3 of `company-scope-local-fallback`, its T14): the
   * profile screen was their only writer and it no longer writes them, so echoing them back would
   * return a value nothing asserts. The chain a caller needs in order to label the store travels
   * inside {@link AssignedStore} for the constrained mode; the free mode reports the preference's
   * presence instead of the hierarchy (T17/G12).
   */
  companyStoreId: string | null;
  /**
   * The caller's store assignments in force today, or `null` when the caller has **no employee
   * row** (`D11`/`T25`). The key is always present on the wire (`T25`), so `null` is the whole of
   * the *unconstrained* branch and nothing else: an optional field would make `undefined` a third
   * state that means "free" by luck and would hide a server that stopped sending the key.
   *
   * An array — **possibly empty** — means the store preference is constrained to exactly those
   * stores; an empty array is still constrained mode.
   */
  assignedStores: AssignedStore[] | null;
}

/**
 * One store the caller is currently assigned to, with the chain derived from it (`T28`).
 *
 * It extends {@link StoreAssignmentDerivedScope} so the option label and the tuple the screen
 * posts reuse the HR shape and its `derivedChainLabel()` helper instead of a second formatter —
 * the chain has one truth.
 */
export interface AssignedStore extends StoreAssignmentDerivedScope {
  companyStoreId: string;
  companyStoreName: string;
}

/**
 * Payload for PUT /api/profile
 * All fields are optional — only provided fields are updated.
 *
 * `companyStoreId` is the only location field: the four tenancy ancestors are **not writable** since
 * 2026-10-14 (W3 of `company-scope-local-fallback`, its T14). Omitting the key leaves the stored
 * preference alone; sending `null` clears it. That distinction is what makes the caller's own
 * untouched control harmless (T16) — a free-mode load can no longer reconstruct the cascade, so it
 * must not post `null` by accident.
 */
export interface ProfileUpdateRequest {
  firstName?: string;
  lastName?: string;
  email?: string;
  companyStoreId?: string | null;
}
