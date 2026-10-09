package com.lifecontrol.api.provisioning.dto;

import java.util.List;

/**
 * The five tenancy claim values an employee's token <b>would</b> carry, as the membership projection
 * derives them (unit W3, records T1/T39).
 *
 * <p>The shape is the token's own shape rather than the projection's: every level is a
 * <b>list</b> of stringified ids, because the {@code company_*} claims are top-level and
 * multivalued (record T1). {@code companyId} always carries exactly one value — the derivation fails
 * closed otherwise — while the four deeper levels are genuine lists and may be empty. An empty list
 * means the level is not held and the projection deletes the key rather than writing an empty value
 * (record T39), so an empty list is the honest rendering of "no claim".</p>
 *
 * <p>A reader uses this to show what access the account would publish; it is deliberately not a
 * mirror of the account. The live account is the identity provider's to answer, and the derivation is
 * re-read here on every call, exactly as the write path re-reads it (record T7).</p>
 *
 * @param companyId the {@code company_id} claim values; exactly one for a converged account
 * @param companyCountryId the {@code company_country_id} claim values
 * @param companyRegionId the {@code company_region_id} claim values
 * @param companyZoneId the {@code company_zone_id} claim values
 * @param companyStoreId the {@code company_store_id} claim values
 */
public record AccessProvisioningClaims(
        List<String> companyId,
        List<String> companyCountryId,
        List<String> companyRegionId,
        List<String> companyZoneId,
        List<String> companyStoreId) {

    public AccessProvisioningClaims {
        companyId = immutableCopy(companyId);
        companyCountryId = immutableCopy(companyCountryId);
        companyRegionId = immutableCopy(companyRegionId);
        companyZoneId = immutableCopy(companyZoneId);
        companyStoreId = immutableCopy(companyStoreId);
    }

    /**
     * The five empty lists: what an account that is not linked — or whose membership was deleted when
     * employment ended — would carry.
     */
    public static AccessProvisioningClaims empty() {
        return new AccessProvisioningClaims(List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static List<String> immutableCopy(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
