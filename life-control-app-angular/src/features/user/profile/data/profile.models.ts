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
  /**
   * The location chain of the **resolved** store preference (`S1`/`S2` of `location-preference`), or
   * `null` when there is nothing usable to preselect: no preference, a stored store the caller's
   * assignments no longer cover, or one the caller can no longer read.
   *
   * Non-optional on purpose, for the same reason as {@link assignedStores}: an optional field would
   * make `undefined` a third state meaning "no location" by luck, and would hide a server that
   * stopped sending the key. `companyStoreId` above and this chain can disagree — the id keeps the
   * resolution it always had, so a `null` here with a non-null id there is a real, meaningful pair and
   * the screens must fail closed on it (`S8`).
   *
   * Read-only, and refused rather than ignored: no field of {@link ProfileUpdateRequest} feeds it, and
   * the server runs with `spring.jackson.deserialization.fail-on-unknown-properties=true`, so a body
   * carrying a `location` object is rejected with `400` instead of accepted and dropped (`S3`).
   */
  location: LocationChain | null;
}

/**
 * The five-level location chain of the resolved store preference, exactly as `GET /api/profile`
 * derives it from the store itself (`S1`).
 *
 * Every field is non-null: the backend only returns a chain when the whole ancestry resolved, and
 * reports `null` instead of a partial one. The ancestors carry **ids only** — no names (`S1`): a
 * preselect is worthless without the option, so every screen already loads the catalogue it needs.
 */
export interface LocationChain {
  companyId: string;
  companyCountryId: string;
  companyRegionId: string;
  companyZoneId: string;
  companyStoreId: string;
  companyStoreName: string;
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
 * 2026-10-14 (W3 of `company-scope-local-fallback`, its T14), and sending them is not tolerated —
 * `spring.jackson.deserialization.fail-on-unknown-properties=true` on the server rejects any
 * undeclared key with `400`, so the ancestors and a `location` object cannot be sent at all. Omitting
 * both location keys leaves the stored preference alone, which is what makes the caller's own
 * untouched control harmless (T16) — a free-mode load can no longer reconstruct the cascade, so it
 * must not clear by accident.
 *
 * **Clearing needs a second field, and that is measured rather than stylistic** (`S4`/`L6`):
 * `companyStoreId` is a `UUID`, so an omitted key and an explicit `null` are the same thing on the
 * wire, and a `null` there is exactly what an untouched control would send. `clearStorePreference`
 * is the only unambiguous way to say *clear it*, and it must never travel together with a non-null
 * `companyStoreId` — the server answers that pair with `400` (`S5`). Send exactly one of the two.
 */
export interface ProfileUpdateRequest {
  firstName?: string;
  lastName?: string;
  email?: string;
  /** Sets the stored store preference. Never sent together with {@link clearStorePreference}. */
  companyStoreId?: string | null;
  /**
   * Clears the stored store preference.
   *
   * Boxed on the server for the same reason it is optional here: an absent key arrives as `null` and
   * means *leave it alone*, which a primitive boolean could not express (`L9`).
   */
  clearStorePreference?: boolean;
}
