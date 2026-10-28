package com.lifecontrol.api.store.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.store.model.CompanyStore;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one derivation every store-scoped rule reads: a store belongs to a company through zone
 * &rarr; region &rarr; country, and {@code company_stores} carries no {@code company_id} to shortcut
 * it.
 *
 * <p>Short, and load-bearing. The four consumers (variant service, purchase order, sales order,
 * inventory engine) now ask this instead of walking the chain themselves, so the walk is pinned once
 * here: it reaches the <b>company</b> and not a level above it, which is the mistake a copy would
 * eventually make — a guard passed a zone id, or a predicate filtering by country, both of which
 * type-check as {@code UUID} and both of which would silently widen the tenant.</p>
 */
@DisplayName("CompanyStoreOwnerResolver Tests")
class CompanyStoreOwnerResolverTest {

    private final CompanyStoreOwnerResolver resolver = new CompanyStoreOwnerResolver();

    @Test
    @DisplayName("should answer the company of the store, not one of the levels above it")
    void answersTheCompanyOfTheStore() {
        var companyId = UUID.randomUUID();
        var countryId = UUID.randomUUID();
        var regionId = UUID.randomUUID();
        var zoneId = UUID.randomUUID();

        var store = storeUnder(companyId, countryId, regionId, zoneId);

        assertThat(resolver.companyIdOf(store)).isEqualTo(companyId);
        // Each level is a different id, so an off-by-one level in the walk cannot pass unnoticed.
        assertThat(resolver.companyIdOf(store))
                .isNotEqualTo(countryId)
                .isNotEqualTo(regionId)
                .isNotEqualTo(zoneId)
                .isNotEqualTo(store.getId());
    }

    @Test
    @DisplayName("should answer each store's own company when two tenants are in play")
    void answersPerStoreInAMultiTenantFixture() {
        var companyAId = UUID.randomUUID();
        var companyBId = UUID.randomUUID();

        var storeA = storeUnder(companyAId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var storeB = storeUnder(companyBId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        assertThat(resolver.companyIdOf(storeA)).isEqualTo(companyAId);
        assertThat(resolver.companyIdOf(storeB)).isEqualTo(companyBId);
        assertThat(resolver.companyIdOf(storeA)).isNotEqualTo(resolver.companyIdOf(storeB));
    }

    private static CompanyStore storeUnder(UUID companyId, UUID countryId, UUID regionId, UUID zoneId) {
        var company = Company.builder()
                .id(companyId)
                .companyKey("RESOLVER-KEY")
                .companyName("Resolver Test Company")
                .rfc("RSLV123456ABC")
                .enabled(true)
                .build();
        var country = CompanyCountry.builder().id(countryId).company(company).build();
        var region = CompanyRegion.builder()
                .id(regionId)
                .companyCountry(country)
                .regionCode("01")
                .regionName("Resolver Region")
                .enabled(true)
                .build();
        var zone = CompanyZone.builder()
                .id(zoneId)
                .companyRegion(region)
                .zoneCode("01")
                .zoneName("Resolver Zone")
                .enabled(true)
                .build();
        return CompanyStore.builder()
                .id(UUID.randomUUID())
                .companyZone(zone)
                .storeName("Resolver Store")
                .enabled(true)
                .build();
    }
}
