package com.lifecontrol.api.hr.service;

import com.lifecontrol.api.hr.model.EmployeeStoreAssignment;
import com.lifecontrol.api.store.model.CompanyStore;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.UUID;

/**
 * The derivation of an employee's store scope: the pure function that turns the assignments valid
 * today plus the store tree into the five id sets the token's claim parser reads (decision T6).
 *
 * <p>It produces one {@link EmployeeStoreScope}, whose sets are the shape
 * {@code CurrentUserContext.extractUuidSetFromClaim} reads (F3/F4) — top-level and multivalued — so
 * {@code employee-access-provisioning} can project them into the {@code company_*} claims. This class
 * <b>only</b> derives: it never writes the projection, never touches Keycloak, and does not know the
 * mapper exists (T9). Static methods only, no Spring annotation and no static state, like the
 * neighbouring pure {@code EmployeeEmailGenerator}: the rule is the risky piece of the feature, so it
 * is isolated from the service and pinned by its own spec.</p>
 *
 * <p>Four rules ride with it. <b>The company-level fact is the employee's own</b>:
 * {@code companyIds} always contains {@code employeeCompanyId} (D3), and for a company-wide person it
 * survives alone (G6), because {@code employees.company_id} is where "belongs to this company"
 * lives. <b>A row contributes only</b> while it is enabled, covers {@code today}
 * ({@code validFrom <= today AND (validTo IS NULL OR validTo > today)} — the strictly-exclusive
 * reading of the {@code '[)'} column, D5), and its <b>store</b> is enabled (T12): a disabled store
 * is the one input that must never reach an authorization decision. <b>Never a partial chain</b>: the
 * whole {@code store -> zone -> region -> country -> company} path must resolve, or the row is
 * skipped whole rather than emitting a store without its country, because a required claim left empty
 * denies the caller at a level the store should have granted (the invariant
 * {@code companyCountryIds} non-empty whenever any deeper set is non-empty).</p>
 *
 * <p>Deliberately <b>not</b> consulted (T12): the {@code enabled} flags of {@code CompanyZone},
 * {@code CompanyRegion} and {@code Company}. Nothing in the store tree reads them —
 * {@code StoreLocationService.resolveStore} ignores them and {@code company_countries} has no such
 * column at all — so reading them here would invent a rule the tree does not have. The output is
 * deterministic (insertion-ordered sets) and unmodifiable, so a value that already answered an
 * authorization question cannot be mutated afterwards.</p>
 */
public final class StoreScopeDerivation {

    private StoreScopeDerivation() {}

    /**
     * Derives the company, country, region, zone and store id sets for one employee.
     *
     * @param employeeCompanyId the employee's own {@code company_id} (D3), always present in the
     *     result
     * @param assignments the candidate rows; only the enabled, currently-covered ones inside an
     *     enabled store contribute
     * @param today the day the assignment must cover to contribute
     * @throws NullPointerException when any argument is null
     */
    public static EmployeeStoreScope derive(
            UUID employeeCompanyId, Collection<EmployeeStoreAssignment> assignments, LocalDate today) {
        Objects.requireNonNull(employeeCompanyId, "employeeCompanyId is required");
        Objects.requireNonNull(assignments, "assignments is required");
        Objects.requireNonNull(today, "today is required");

        var companyIds = new LinkedHashSet<UUID>();
        var companyCountryIds = new LinkedHashSet<UUID>();
        var companyRegionIds = new LinkedHashSet<UUID>();
        var companyZoneIds = new LinkedHashSet<UUID>();
        var companyStoreIds = new LinkedHashSet<UUID>();

        // D3: the company-level fact is employees.company_id, not the store tree, so a company-wide
        // person derives the company and nothing deeper (G6).
        companyIds.add(employeeCompanyId);

        for (var assignment : assignments) {
            if (assignment == null || !Boolean.TRUE.equals(assignment.getEnabled())) {
                continue;
            }
            if (!coversDate(assignment, today)) {
                continue;
            }
            var chain = resolveChain(assignment.getCompanyStore());
            if (chain == null) {
                // Never a partial chain: a row whose store tree cannot be resolved contributes
                // nothing rather than a store id without its ancestors.
                continue;
            }
            companyStoreIds.add(chain.storeId());
            companyZoneIds.add(chain.zoneId());
            companyRegionIds.add(chain.regionId());
            companyCountryIds.add(chain.countryId());
        }

        return new EmployeeStoreScope(companyIds, companyCountryIds, companyRegionIds, companyZoneIds, companyStoreIds);
    }

    /**
     * The strictly-exclusive coverage test of D5: the stored {@code validTo} is the first day
     * <b>not</b> covered, so it must be strictly after {@code today}; {@code null} means open-ended.
     */
    private static boolean coversDate(EmployeeStoreAssignment assignment, LocalDate today) {
        var validFrom = assignment.getValidFrom();
        if (validFrom == null || validFrom.isAfter(today)) {
            return false;
        }
        var validTo = assignment.getValidTo();
        return validTo == null || validTo.isAfter(today);
    }

    /**
     * Resolves the complete store chain of one row, or {@code null} when any hop is missing.
     *
     * <p>The store's own {@code enabled} flag is checked here (T12); the ancestor flags are not
     * (T12). The company hop must resolve because it closes the chain, even though the output's
     * {@code companyIds} comes from the employee and not from it.</p>
     */
    private static Chain resolveChain(CompanyStore store) {
        if (store == null || !Boolean.TRUE.equals(store.getEnabled())) {
            return null;
        }
        var zone = store.getCompanyZone();
        if (zone == null) {
            return null;
        }
        var region = zone.getCompanyRegion();
        if (region == null) {
            return null;
        }
        var companyCountry = region.getCompanyCountry();
        if (companyCountry == null || companyCountry.getCompany() == null) {
            return null;
        }
        return new Chain(store.getId(), zone.getId(), region.getId(), companyCountry.getId());
    }

    /** The four ids of a fully resolved ancestor chain. */
    private record Chain(UUID storeId, UUID zoneId, UUID regionId, UUID countryId) {}
}
