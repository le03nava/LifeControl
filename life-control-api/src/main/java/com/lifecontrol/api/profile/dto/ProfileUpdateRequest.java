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
 * <p>The four tenancy ancestors are <b>deliberately absent</b> since 2026-10-14 (W3 of
 * {@code company-scope-local-fallback}, its T14): a tenancy id is an authorization input, and the
 * subject of the decision is the last party who may assert one. A client that still sends them is
 * <b>ignored</b> — Jackson's default does not fail on unknown request properties — which is declared
 * as that record's <b>G13</b> rather than fixed here, because turning that flag on is a global
 * request-parsing change. Nothing is written either way: there is no longer a field to write.</p>
 */
public record ProfileUpdateRequest(
        @Size(max = 255) String firstName,
        @Size(max = 255) String lastName,
        @Email @Size(max = 255) String email,
        UUID companyStoreId) {}
