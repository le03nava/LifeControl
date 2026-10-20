package com.lifecontrol.api.store.service;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.common.security.ScopeLevel;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
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
     * The stores reachable from one level of the hierarchy.
     *
     * <p>Fail-closed on an empty claim: {@code IN ()} is not a query Hibernate can type, and the
     * alternative reading — "no claim means no restriction" — is the leak this class exists to
     * prevent. An empty claim yields an empty set, and the caller renders an empty page.</p>
     */
    private Set<UUID> storeIdsFor(ScopeLevel scope) {
        return switch (scope) {
            case COMPANY -> resolve(currentUserContext.getCompanyIds(), companyStoreRepository::findIdsByCompanyIdIn);
            case COUNTRY ->
                resolve(currentUserContext.getCompanyCountryIds(), companyStoreRepository::findIdsByCompanyCountryIdIn);
            case REGION ->
                resolve(currentUserContext.getCompanyRegionIds(), companyStoreRepository::findIdsByCompanyRegionIdIn);
            case ZONE ->
                resolve(currentUserContext.getCompanyZoneIds(), companyStoreRepository::findIdsByCompanyZoneIdIn);
            case STORE -> Set.copyOf(currentUserContext.getCompanyStoreIds());
        };
    }

    private static Set<UUID> resolve(Set<UUID> claimIds, Function<Collection<UUID>, List<UUID>> finder) {
        if (claimIds == null || claimIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(finder.apply(claimIds));
    }
}
