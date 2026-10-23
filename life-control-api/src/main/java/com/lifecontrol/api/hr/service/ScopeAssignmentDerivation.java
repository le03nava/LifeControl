package com.lifecontrol.api.hr.service;

import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.hr.model.EmployeeScopeAssignment;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The derivation of an employee's <b>per-level</b> scope: the pure function that turns the scope
 * assignments valid today plus the node tree into the same five id sets {@link EmployeeStoreScope}
 * carries, and the union that folds those into the store-derived ones.
 *
 * <p>It exists because the five {@code company_*} claims had exactly one source — the store
 * derivation — so a holder of {@code lc-company-country}, {@code lc-company-region} or
 * {@code lc-company-zone} had no claim to be scoped by and nowhere to be assigned. This class emits
 * the country, region and zone levels from an assignment row; the store level keeps coming from
 * {@link StoreScopeDerivation}, which is <b>not</b> modified (S5). {@link #union} is the one place the
 * two sources meet, so the projection and the token contract stay single-sourced.</p>
 *
 * <p>Five rules ride with it. <b>The company-level fact is the employee's own</b>:
 * {@code companyIds} always contains {@code employeeCompanyId} and never a row's chain, because one
 * person belongs to one company (S3). <b>A row contributes only</b> while it is enabled and covers
 * {@code today} ({@code validFrom <= today AND (validTo IS NULL OR validTo > today)} — the
 * strictly-exclusive reading of the {@code '[)'} column). <b>Each level contributes its own node and
 * its ancestors</b>: a {@code COUNTRY} row its country, a {@code REGION} row region <b>and</b> country,
 * a {@code ZONE} row zone, region and country — never a partial chain, because a required claim left
 * empty denies the caller at a level the assignment should have granted (the invariant
 * {@code companyCountryIds} non-empty whenever any deeper set is non-empty). <b>The assigned node's own
 * {@code enabled} flag decides, and no ancestor's flag is consulted</b>: a soft-deleted region or zone
 * must never reach an authorization decision, while a disabled <i>ancestor</i> must not silently drop a
 * grant the tree still resolves. That is exactly the split {@link StoreScopeDerivation} makes (its
 * decision T12): it checks the assigned store's own flag and deliberately ignores the flags of
 * {@code CompanyZone}, {@code CompanyRegion} and {@code Company}. The split is load-bearing in both
 * directions — {@code company_countries} has no {@code enabled} column at all, so a country row has no
 * node flag to consult and is decided by the row's own {@code enabled} alone. <b>The store level is
 * never produced here</b> and stays empty at this stage.</p>
 *
 * <p>Static methods only, no Spring annotation and no static state, like the neighbouring pure
 * {@link StoreScopeDerivation}: the rule is the risky piece of the feature, so it is isolated from the
 * service and pinned by its own spec. The output is deterministic (insertion-ordered sets) and
 * unmodifiable, so a value that already answered an authorization question cannot be mutated
 * afterwards.</p>
 */
public final class ScopeAssignmentDerivation {

    private ScopeAssignmentDerivation() {}

    /**
     * Derives the company, country, region and zone id sets of one employee's per-level assignments.
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
            if (chain.zoneId() != null) {
                companyZoneIds.add(chain.zoneId());
            }
            if (chain.regionId() != null) {
                companyRegionIds.add(chain.regionId());
            }
            companyCountryIds.add(chain.countryId());
        }

        return new EmployeeStoreScope(
                companyIds, companyCountryIds, companyRegionIds, companyZoneIds, new LinkedHashSet<>());
    }

    /**
     * Folds the store-derived scope and the per-level scope into one value: the ids of both sources,
     * deduplicated, with the store-derived ids first and the per-level ones appended.
     *
     * <p>The order is the store derivation's own order followed by the level derivation's, which is the
     * order the projection writes and the guard's claim parser reads; sorting or rebuilding would hide
     * it. Deduplication is by id, so a country reachable from both sources appears once. The company set
     * is the union of both — each side contributes exactly the employee's own company, so the union is
     * still exactly one and {@code CompanyScopeInvariantException} keeps its meaning at the call
     * site.</p>
     *
     * @throws NullPointerException when either side is null, so a missing source fails loudly instead of
     *     silently reading as "no scope"
     */
    public static EmployeeStoreScope union(EmployeeStoreScope storeDerived, EmployeeStoreScope levelDerived) {
        Objects.requireNonNull(storeDerived, "storeDerived is required");
        Objects.requireNonNull(levelDerived, "levelDerived is required");
        return new EmployeeStoreScope(
                merged(storeDerived.companyIds(), levelDerived.companyIds()),
                merged(storeDerived.companyCountryIds(), levelDerived.companyCountryIds()),
                merged(storeDerived.companyRegionIds(), levelDerived.companyRegionIds()),
                merged(storeDerived.companyZoneIds(), levelDerived.companyZoneIds()),
                merged(storeDerived.companyStoreIds(), levelDerived.companyStoreIds()));
    }

    private static Set<UUID> merged(Set<UUID> first, Set<UUID> second) {
        var merged = new LinkedHashSet<UUID>(first);
        merged.addAll(second);
        return merged;
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
     * unambiguous: exactly one of the three columns is set on a stored row, so a row that sets none
     * contributes nothing.</p>
     */
    private static Chain resolveChain(EmployeeScopeAssignment assignment) {
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
     * The chain of an assigned company-country. There is no node flag to consult: {@code company_countries}
     * carries no {@code enabled} column (V1 baseline) and {@code CompanyCountry} no field, so the row's
     * own {@code enabled} is the only enabled fact this level has.
     */
    private static Chain assignedCountry(CompanyCountry companyCountry) {
        if (companyCountry.getCompany() == null) {
            return null;
        }
        return new Chain(companyCountry.getId(), null, null);
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
        return chain == null ? null : new Chain(chain.countryId(), region.getId(), null);
    }

    /**
     * The chain of an assigned zone: the zone's own flag decides; the region's and the country's flags
     * are deliberately NOT consulted.
     *
     * <p>This is the symmetry with {@link StoreScopeDerivation} that must not be "fixed" into an
     * ancestor check: a disabled ancestor is context the tree still resolves, and dropping it would
     * silently revoke a grant nobody disabled — exactly as a disabled zone does not drop a store
     * assignment today.</p>
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
        return chain == null ? null : new Chain(chain.countryId(), region.getId(), zone.getId());
    }

    /** The resolved ids of one row's chain: the country is always set, the deeper ids per level. */
    private record Chain(UUID countryId, UUID regionId, UUID zoneId) {}
}
