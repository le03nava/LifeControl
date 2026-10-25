package com.lifecontrol.api.hr.service;

import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.hr.model.EmployeeScopeAssignment;
import com.lifecontrol.api.store.model.CompanyStore;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.UUID;

/**
 * The derivation of an employee's <b>per-level</b> scope: the pure function that turns the scope
 * assignments valid today plus the node tree into the five id sets {@link EmployeeStoreScope}
 * carries.
 *
 * <p>It exists because the five {@code company_*} claims had exactly one source — the store table —
 * so a holder of {@code lc-company-country}, {@code lc-company-region} or {@code lc-company-zone} had
 * no claim to be scoped by and nowhere to be assigned. This class emits <b>all four deeper levels</b>
 * from one table: a country, region or zone row, and a store row, whose chain is the store's own zone,
 * region and country. The store level used to come from its own table; since
 * {@code V32__employee_scope_assignments_store_level.sql} it is the fourth node column of this table,
 * and since {@code V33__drop_employee_store_assignments.sql} that table is gone, so this is one
 * source, one rule, and there is no second derivation and no union to fold.</p>
 *
 * <p>Five rules ride with it. <b>The company-level fact is the employee's own</b>:
 * {@code companyIds} always contains {@code employeeCompanyId} and never a row's chain, because one
 * person belongs to one company (S3). <b>A row contributes only</b> while it is enabled and covers
 * {@code today} ({@code validFrom <= today AND (validTo IS NULL OR validTo > today)} — the
 * strictly-exclusive reading of the {@code '[)'} column). <b>Each level contributes its own node and
 * its ancestors</b>: a {@code COUNTRY} row its country, a {@code REGION} row region <b>and</b> country,
 * a {@code ZONE} row zone, region and country, a {@code STORE} row store, zone, region and country —
 * never a partial chain, because a required claim left empty denies the caller at a level the
 * assignment should have granted (the invariant {@code companyCountryIds} non-empty whenever any
 * deeper set is non-empty). <b>The assigned node's own {@code enabled} flag decides, and no ancestor's
 * flag is consulted</b>: a soft-deleted region, zone or store must never reach an authorization
 * decision, while a disabled <i>ancestor</i> must not silently drop a grant the tree still resolves.
 * That is the split the store level inherited (decision T12): it checks the assigned store's own flag
 * and deliberately ignores the flags of {@code CompanyZone}, {@code CompanyRegion} and
 * {@code Company}. The split is load-bearing in both
 * directions — {@code company_countries} has no {@code enabled} column at all, so a country row has no
 * node flag to consult and is decided by the row's own {@code enabled} alone. <b>The company level is
 * never produced by a row</b>: it comes from the employee, and a company node does not exist.</p>
 *
 * <p>Static methods only, no Spring annotation and no static state, like the neighbouring pure
 * {@code EmployeeEmailGenerator}: the rule is the risky piece of the feature, so it is isolated from
 * the service and pinned by its own spec. The output is deterministic (insertion-ordered sets) and
 * unmodifiable, so a value that already answered an authorization question cannot be mutated
 * afterwards.</p>
 */
public final class ScopeAssignmentDerivation {

    private ScopeAssignmentDerivation() {}

    /**
     * Derives the company, country, region, zone and store id sets of one employee's per-level
     * assignments.
     *
     * @param employeeCompanyId the employee's own {@code company_id} (S3), always present in the
     *     result
     * @param assignments the candidate rows; only the enabled, currently-covered rows whose assigned
     *     node is itself enabled contribute
     * @param today the day the assignment must cover to contribute
     * @throws NullPointerException when any argument is null
     */
    public static EmployeeStoreScope derive(
            UUID employeeCompanyId, Collection<EmployeeScopeAssignment> assignments, LocalDate today) {
        Objects.requireNonNull(employeeCompanyId, "employeeCompanyId is required");
        Objects.requireNonNull(assignments, "assignments is required");
        Objects.requireNonNull(today, "today is required");

        var companyIds = new LinkedHashSet<UUID>();
        var companyCountryIds = new LinkedHashSet<UUID>();
        var companyRegionIds = new LinkedHashSet<UUID>();
        var companyZoneIds = new LinkedHashSet<UUID>();
        var companyStoreIds = new LinkedHashSet<UUID>();

        // S3: the company-level fact is employees.company_id, not the assigned node's tree, so the
        // company is present even when nothing is assigned.
        companyIds.add(employeeCompanyId);

        for (var assignment : assignments) {
            if (assignment == null || !Boolean.TRUE.equals(assignment.getEnabled())) {
                continue;
            }
            if (!coversDate(assignment, today)) {
                continue;
            }
            var chain = resolveChain(assignment);
            if (chain == null) {
                // Never a partial chain: a row whose node tree cannot be resolved contributes nothing
                // rather than a zone id without its region and country.
                continue;
            }
            if (chain.storeId() != null) {
                companyStoreIds.add(chain.storeId());
            }
            if (chain.zoneId() != null) {
                companyZoneIds.add(chain.zoneId());
            }
            if (chain.regionId() != null) {
                companyRegionIds.add(chain.regionId());
            }
            companyCountryIds.add(chain.countryId());
        }

        return new EmployeeStoreScope(companyIds, companyCountryIds, companyRegionIds, companyZoneIds, companyStoreIds);
    }

    /**
     * The strictly-exclusive coverage test: the stored {@code validTo} is the first day <b>not</b>
     * covered, so it must be strictly after {@code today}; {@code null} means open-ended.
     */
    private static boolean coversDate(EmployeeScopeAssignment assignment, LocalDate today) {
        var validFrom = assignment.getValidFrom();
        if (validFrom == null || validFrom.isAfter(today)) {
            return false;
        }
        var validTo = assignment.getValidTo();
        return validTo == null || validTo.isAfter(today);
    }

    /**
     * Resolves the node the row assigns and its ancestors, or {@code null} when the row does not
     * contribute at all.
     *
     * <p>The level is read from the row's shape, in the order the CHECK constraint guarantees is
     * unambiguous: exactly one of the four columns is set on a stored row, so a row that sets none
     * contributes nothing. The store is checked first because its chain walks a hop further than a
     * zone's: a store row reaches the zone, region and country <b>through its store</b>.</p>
     */
    private static Chain resolveChain(EmployeeScopeAssignment assignment) {
        var store = assignment.getCompanyStore();
        if (store != null) {
            return assignedStore(store);
        }
        var zone = assignment.getCompanyZone();
        if (zone != null) {
            return assignedZone(zone);
        }
        var region = assignment.getCompanyRegion();
        if (region != null) {
            return assignedRegion(region);
        }
        var companyCountry = assignment.getCompanyCountry();
        return companyCountry == null ? null : assignedCountry(companyCountry);
    }

    /**
     * The chain of an assigned company-store: the store's own flag decides, its zone, region and country
     * are context reached through the store.
     *
     * <p>The store's flag is the one node flag a store level has, which is what makes the settled split
     * reachable at this level: a disabled store never contributes, while a disabled zone above it does
     * not drop the grant the tree still resolves.</p>
     */
    private static Chain assignedStore(CompanyStore store) {
        if (!Boolean.TRUE.equals(store.getEnabled())) {
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
        if (companyCountry == null) {
            return null;
        }
        var chain = assignedCountry(companyCountry);
        return chain == null ? null : new Chain(chain.countryId(), region.getId(), zone.getId(), store.getId());
    }

    /**
     * The chain of an assigned company-country. There is no node flag to consult: {@code company_countries}
     * carries no {@code enabled} column (V1 baseline) and {@code CompanyCountry} no field, so the row's
     * own {@code enabled} is the only enabled fact this level has.
     */
    private static Chain assignedCountry(CompanyCountry companyCountry) {
        if (companyCountry.getCompany() == null) {
            return null;
        }
        return new Chain(companyCountry.getId(), null, null, null);
    }

    /** The chain of an assigned region: its own flag decides, and its country is context. */
    private static Chain assignedRegion(CompanyRegion region) {
        // T12's split, per level: the assigned node is the deciding input.
        if (!Boolean.TRUE.equals(region.getEnabled())) {
            return null;
        }
        var companyCountry = region.getCompanyCountry();
        if (companyCountry == null) {
            return null;
        }
        var chain = assignedCountry(companyCountry);
        return chain == null ? null : new Chain(chain.countryId(), region.getId(), null, null);
    }

    /**
     * The chain of an assigned zone: the zone's own flag decides; the region's and the country's flags
     * are deliberately NOT consulted.
     *
     * <p>This is the store level's own split (T12) that must not be "fixed" into an ancestor check: a
     * disabled ancestor is context the tree still resolves, and dropping it would silently revoke a
     * grant nobody disabled — exactly as a disabled zone does not drop a store assignment today.</p>
     */
    private static Chain assignedZone(CompanyZone zone) {
        if (!Boolean.TRUE.equals(zone.getEnabled())) {
            return null;
        }
        var region = zone.getCompanyRegion();
        if (region == null) {
            return null;
        }
        var companyCountry = region.getCompanyCountry();
        if (companyCountry == null) {
            return null;
        }
        var chain = assignedCountry(companyCountry);
        return chain == null ? null : new Chain(chain.countryId(), region.getId(), zone.getId(), null);
    }

    /** The resolved ids of one row's chain: the country is always set, the deeper ids per level. */
    private record Chain(UUID countryId, UUID regionId, UUID zoneId, UUID storeId) {}
}
