package com.lifecontrol.api.store.service;

import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The store ids an explicit hierarchy filter names, resolved so a list can constrain itself by them.
 *
 * <p>It answers the other half of the question {@link CompanyStoreScopeResolver} answers. That one
 * resolves the <em>caller's claims</em> to stores, and it is authorization. This one resolves the
 * <em>caller's query parameters</em> to stores, and it is not: a filter is input, so the consumer has
 * to intersect the two sets rather than choose one. A list that used this answer on its own would let
 * a query parameter name another tenant's region.</p>
 *
 * <p>The five levels are optional and combine with {@code AND} on the single path
 * {@code store -> zone -> region -> country -> company}: {@code CompanyStore} has no company,
 * country or region column, so every level of the filter is a step up that path. An absent level
 * constrains nothing. The lookup does not filter by {@code enabled}: a disabled store's receipts stay
 * readable to whoever can reach them, which is the same rule the scope projections apply.</p>
 */
@Component
public class CompanyStoreFilterResolver {

    private final CompanyStoreRepository companyStoreRepository;

    public CompanyStoreFilterResolver(CompanyStoreRepository companyStoreRepository) {
        this.companyStoreRepository = companyStoreRepository;
    }

    /**
     * The stores the filter names — every store when no level is bound.
     *
     * <p>The answer is never {@code null}: an empty set means "no store matches this filter", which
     * the caller must render as an empty page and never as "no constraint".</p>
     */
    public Set<UUID> storeIdsFor(
            UUID companyId, UUID companyCountryId, UUID regionId, UUID zoneId, UUID companyStoreId) {
        return Set.copyOf(companyStoreRepository.findIdsByHierarchyFilter(
                companyId, companyCountryId, regionId, zoneId, companyStoreId));
    }
}
