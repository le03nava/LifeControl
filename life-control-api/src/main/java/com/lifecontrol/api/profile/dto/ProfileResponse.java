package com.lifecontrol.api.profile.dto;

import java.util.List;
import java.util.UUID;

/**
 * The authenticated user's combined profile: basic info from the token and the location preferences
 * from {@code user_preferences}.
 *
 * <p>{@code companyStoreId} is a <b>resolved</b> value, not the stored column (decision D10): it is
 * returned only while a store assignment covering today still exists for the caller's employee row,
 * and {@code null} otherwise. The stored preference is therefore a pointer into the assignment set
 * instead of an assertion of its own, so the two readers of this field fail closed rather than
 * navigating the person into a store they can no longer work in.</p>
 *
 * <p>{@code assignedStores} is the discriminator (decision D11/T25): {@code null} when the caller has
 * no employee row — the constraint does not exist for them, and any store is accepted — and a list,
 * <b>possibly empty</b>, when they do, in which case the store preference is constrained to exactly
 * those. Each element carries the assigned store and the ancestor chain the token will derive from
 * it, so the client can label the option and post a coherent tuple without a second read.</p>
 *
 * <p>The four tenancy ancestors — {@code companyCountryId}, {@code companyId}, {@code companyRegionId}
 * and {@code companyZoneId} — are <b>deliberately absent</b> since 2026-10-14 (W3 of
 * {@code company-scope-local-fallback}, its T14): the profile screen was the only writer they ever
 * had and it no longer writes them, so echoing them back would return a value nothing asserts. They
 * never fed an authorization decision — {@code CurrentUserContext} reads the token's claims, which the
 * access projection materialises — which is why removing them is a <b>contract</b> change and not a
 * permission one. The chain a caller needs in order to label a store travels inside
 * {@link AssignedStore}, or is resolved from the store id by the caller.</p>
 */
public record ProfileResponse(
        String keycloakUserId,
        String username,
        String email,
        String firstName,
        String lastName,
        UUID companyStoreId,
        List<AssignedStore> assignedStores) {

    /** One store the caller is currently assigned to, with the chain derived from it. */
    public record AssignedStore(
            UUID companyStoreId,
            String companyStoreName,
            UUID companyId,
            String companyName,
            UUID companyCountryId,
            String companyCountryName,
            UUID companyRegionId,
            String companyRegionName,
            UUID companyZoneId,
            String companyZoneName) {}
}
