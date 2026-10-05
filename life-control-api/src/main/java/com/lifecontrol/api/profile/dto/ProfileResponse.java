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
 */
public record ProfileResponse(
        String keycloakUserId,
        String username,
        String email,
        String firstName,
        String lastName,
        UUID companyCountryId,
        UUID companyId,
        UUID companyRegionId,
        UUID companyZoneId,
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
