package com.lifecontrol.api.profile.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * The profile an authenticated caller may write about itself.
 *
 * <p>The basic information belongs to Keycloak; {@code companyStoreId} is the location preference,
 * constrained to the caller's current store assignments (decisions D2/T11/T24 of
 * {@code employee-store-assignments}) and resolved on read (D10). The preference is a pointer into
 * the assignment set, never an assertion of its own.</p>
 *
 * <p>{@code clearStorePreference} is what makes the column <b>clearable</b> (S4): with a {@link UUID}
 * field alone, an omitted key and an explicit {@code null} are indistinguishable on the wire, so the
 * screen's «— None —» restored the stored value instead of clearing it — there was no request shape
 * that could express "no store". Its absence must keep meaning "leave the stored value untouched",
 * never "clear it", and an absent value reaches this record as {@code null}: it is boxed for the same
 * reason every other optional boolean of this module's request bodies is (see
 * {@code CompanyRequest.enabled}) — Jackson 3 ships with
 * {@code DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES} <b>on</b>, so a primitive component fails
 * an omitted key with a 400 instead of defaulting it to {@code false}. {@code true} together with a
 * non-null {@code companyStoreId} is a contradiction and is refused with 400 before Keycloak is
 * touched (S5).</p>
 *
 * <p>The four tenancy ancestors are <b>deliberately absent</b> since 2026-10-14 (W3 of
 * {@code company-scope-local-fallback}, its T14): a tenancy id is an authorization input, and the
 * subject of the decision is the last party who may assert one. A client that still sends them is not
 * quietly tolerated: {@code spring.jackson.deserialization.fail-on-unknown-properties=true}
 * ({@code application.properties}) turns an undeclared key into a <b>400</b>, so the four ancestors —
 * and a {@code location}-shaped object with them — cannot be sent at all. That is a stronger guarantee
 * than the "ignored" one that record's <b>G13</b> described, and it is also why nothing is written:
 * such a request never reaches the service, instead of reaching it with no field to write.</p>
 */
public record ProfileUpdateRequest(
        @Size(max = 255) String firstName,
        @Size(max = 255) String lastName,
        @Email @Size(max = 255) String email,
        UUID companyStoreId,
        Boolean clearStorePreference) {}
