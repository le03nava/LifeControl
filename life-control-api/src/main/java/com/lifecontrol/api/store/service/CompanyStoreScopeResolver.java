package com.lifecontrol.api.store.service;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.common.security.ScopeLevel;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The store ids a caller may read, derived from the broadest scope its roles grant.
 *
 * <p>A flat list — goods receipts, and anything else addressed without a store segment — cannot
 * authorize with {@code @PreAuthorize} alone: the annotation proves the caller holds <em>some</em>
 * role in the hierarchy, never which part of the hierarchy it may read. Scoping such a list by the
 * raw {@code company_store_ids} claim is wrong in both directions, because a caller whose position
 * sits at a broader level (company, country, region, zone) carries that level's claim and not a
 * store list: it would receive an empty page while the id-addressed read of the same rows admits
 * it.</p>
 *
 * <p>So the rule lives here, once, and it is the same rule {@code verifyCompanyStoreAccess} applies
 * to a single entity: the broadest granted scope decides how far the hierarchy is walked. List and
 * detail then agree by construction instead of by coincidence.</p>
 */
@Component
public class CompanyStoreScopeResolver {

    /**
     * The stores a caller may read.
     *
     * <p>{@code unscoped} is an administrator, which every list path serves from its unscoped query.
     * Otherwise the caller reads exactly {@code storeIds} — an empty set means "nothing", never
     * "everything".</p>
     */
    public record StoreScope(boolean unscoped, Set<UUID> storeIds) {}

    private final CurrentUserContext currentUserContext;
    private final CompanyStoreRepository companyStoreRepository;

    public CompanyStoreScopeResolver(
            CurrentUserContext currentUserContext, CompanyStoreRepository companyStoreRepository) {
        this.currentUserContext = currentUserContext;
        this.companyStoreRepository = companyStoreRepository;
    }

    /** Resolves the current caller's scope. */
    public StoreScope scopeForCurrentUser() {
        if (currentUserContext.isAdmin()) {
            return new StoreScope(true, Set.of());
        }
        return currentUserContext
                .broadestGrantedScope(ScopeLevel.STORE)
                .map(scope -> new StoreScope(false, storeIdsFor(scope)))
                .orElseGet(() -> new StoreScope(false, Set.of()));
    }

    /**
     * The stores reachable from one level of the hierarchy, constrained by every ancestor claim.
     *
     * <p>The deep claim alone is not enough. The id-addressed read verifies the <em>whole</em> path
     * from {@code company} down to the caller's broadest granted scope
     * ({@code CurrentUserContext#verifyLevelsUpTo}), so a list that filtered by its own level's claim
     * only would return rows the detail denies whenever an ancestor claim disagrees — a token minted
     * out of band by the Admin REST attribute write, a Keycloak group attribute, or migration
     * residue. Each level therefore passes its claim <strong>and</strong> every ancestor's to the
     * finder, which is the same constraint the detail applies.</p>
     *
     * <p>Fail-closed on a missing claim: the four-argument {@code IN ()} is not typeable, and the
     * alternative reading — "no claim means no restriction" — is the leak this class exists to
     * prevent. A missing ancestor claim means the detail would deny the row too, so the list renders
     * an empty page.</p>
     */
    private Set<UUID> storeIdsFor(ScopeLevel scope) {
        return switch (scope) {
            case COMPANY -> {
                var companyIds = currentUserContext.getCompanyIds();
                yield allows(companyIds)
                        ? Set.copyOf(companyStoreRepository.findIdsByCompanyIdIn(companyIds))
                        : Set.of();
            }
            case COUNTRY -> {
                var countryIds = currentUserContext.getCompanyCountryIds();
                var companyIds = currentUserContext.getCompanyIds();
                yield allows(countryIds, companyIds)
                        ? Set.copyOf(companyStoreRepository.findIdsByCompanyCountryIdIn(countryIds, companyIds))
                        : Set.of();
            }
            case REGION -> {
                var regionIds = currentUserContext.getCompanyRegionIds();
                var countryIds = currentUserContext.getCompanyCountryIds();
                var companyIds = currentUserContext.getCompanyIds();
                yield allows(regionIds, countryIds, companyIds)
                        ? Set.copyOf(
                                companyStoreRepository.findIdsByCompanyRegionIdIn(regionIds, countryIds, companyIds))
                        : Set.of();
            }
            case ZONE -> {
                var zoneIds = currentUserContext.getCompanyZoneIds();
                var regionIds = currentUserContext.getCompanyRegionIds();
                var countryIds = currentUserContext.getCompanyCountryIds();
                var companyIds = currentUserContext.getCompanyIds();
                yield allows(zoneIds, regionIds, countryIds, companyIds)
                        ? Set.copyOf(companyStoreRepository.findIdsByCompanyZoneIdIn(
                                zoneIds, regionIds, countryIds, companyIds))
                        : Set.of();
            }
            case STORE -> {
                var storeIds = currentUserContext.getCompanyStoreIds();
                var zoneIds = currentUserContext.getCompanyZoneIds();
                var regionIds = currentUserContext.getCompanyRegionIds();
                var countryIds = currentUserContext.getCompanyCountryIds();
                var companyIds = currentUserContext.getCompanyIds();
                yield allows(storeIds, zoneIds, regionIds, countryIds, companyIds)
                        ? Set.copyOf(companyStoreRepository.findIdsByStoreIdIn(
                                storeIds, zoneIds, regionIds, countryIds, companyIds))
                        : Set.of();
            }
        };
    }

    /**
     * Whether every claim the scope needs is present: an absent one cannot authorize anything.
     *
     * <p>{@code CurrentUserContext}'s claim getters always return an immutable set — empty when the
     * token carries nothing — so "absent" is an empty set and there is no null to guard.</p>
     */
    private static boolean allows(Set<UUID>... claims) {
        for (var claim : claims) {
            if (claim.isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
