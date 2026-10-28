package com.lifecontrol.api.store.service;

import com.lifecontrol.api.store.model.CompanyStore;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The company that owns a store.
 *
 * <p>{@code company_stores} carries no {@code company_id} (V1): a store hangs off a zone, the zone
 * off a region, the region off a country, and the country off the company. That walk is the only way
 * from a store to its tenant, and it is the answer to two different questions that must never
 * disagree — <em>who may act on this store</em> (the access guard) and <em>which tenant's rows a
 * store-scoped lookup may read</em> (the company predicate of every variant lookup).</p>
 *
 * <p>It lives here, once, because four consumers now ask it: the variant service (the store-scoped
 * reads and the store-stock write), the purchase order (the line's variant), the sales order (the
 * line's variant) and the inventory engine (the receipt and the sale deduction). Four copies of an
 * entity walk are four chances to walk it differently, and a predicate that derives a different
 * tenant than the guard verified is exactly the hole this answers.</p>
 *
 * <p>Deliberately dependency-free: it answers a question about an entity it is handed. Loading the
 * store is the caller's business, because each caller already holds the store (or has a repository
 * for it) and each reports a different failure for a missing one.</p>
 */
@Component
public class CompanyStoreOwnerResolver {

    /**
     * The company id reached by walking store &rarr; zone &rarr; region &rarr; country &rarr;
     * company.
     *
     * @param store the store whose owner is asked for; the whole chain must be loaded
     * @return the id of the company that owns the store
     */
    public UUID companyIdOf(CompanyStore store) {
        return store.getCompanyZone()
                .getCompanyRegion()
                .getCompanyCountry()
                .getCompany()
                .getId();
    }
}
