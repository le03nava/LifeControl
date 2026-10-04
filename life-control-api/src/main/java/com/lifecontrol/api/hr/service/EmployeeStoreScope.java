package com.lifecontrol.api.hr.service;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The five id sets the store-assignment derivation produces for one employee (decision T6).
 *
 * <p>Its shape is the shape the token's claim parser reads: {@code CurrentUserContext} holds one set
 * per {@code ScopeLevel}, extracted with {@code extractUuidSetFromClaim} (F3/F4), so this value is
 * what {@code employee-access-provisioning} will project into the {@code company_*}
 * claims — top-level and multivalued — for {@link StoreScopeDerivation}. It is the output of the
 * derivation and nothing else: it never writes the projection (T9), and it knows nothing about
 * Keycloak.</p>
 *
 * <p>{@code companyIds} always carries the employee's own {@code company_id} (D3), which is the
 * company-level fact; the four deeper levels are the assigned stores and their ancestors, and they
 * are empty for a company-wide person (G6). Because one employee may hold several stores at once
 * (D1), every level is a set and never a scalar.</p>
 *
 * <p>The compact constructor rejects a null set and a null element, and defensively copies every set
 * into its own <b>unmodifiable</b> {@link LinkedHashSet}: the derivation builds them in insertion
 * order, and a caller must not be able to mutate a value that already answered an authorization
 * question.</p>
 */
public record EmployeeStoreScope(
        Set<UUID> companyIds,
        Set<UUID> companyCountryIds,
        Set<UUID> companyRegionIds,
        Set<UUID> companyZoneIds,
        Set<UUID> companyStoreIds) {

    public EmployeeStoreScope {
        companyIds = immutableCopy(companyIds, "companyIds");
        companyCountryIds = immutableCopy(companyCountryIds, "companyCountryIds");
        companyRegionIds = immutableCopy(companyRegionIds, "companyRegionIds");
        companyZoneIds = immutableCopy(companyZoneIds, "companyZoneIds");
        companyStoreIds = immutableCopy(companyStoreIds, "companyStoreIds");
    }

    private static Set<UUID> immutableCopy(Set<UUID> ids, String name) {
        Objects.requireNonNull(ids, name + " is required");
        var copy = new LinkedHashSet<UUID>();
        for (var id : ids) {
            copy.add(Objects.requireNonNull(id, name + " cannot contain a null element"));
        }
        return Collections.unmodifiableSet(copy);
    }
}
