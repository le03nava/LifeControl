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
  companyCountryId: string | null;
  companyId: string | null;
  companyRegionId: string | null;
  companyZoneId: string | null;
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
 * Location fields can be set to null to clear the preference.
 */
export interface ProfileUpdateRequest {
  firstName?: string;
  lastName?: string;
  email?: string;
  companyCountryId?: string | null;
  companyId?: string | null;
  companyRegionId?: string | null;
  companyZoneId?: string | null;
  companyStoreId?: string | null;
}
