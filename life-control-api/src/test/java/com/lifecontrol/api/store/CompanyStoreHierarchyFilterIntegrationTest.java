package com.lifecontrol.api.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.company.repository.CompanyCountryRepository;
import com.lifecontrol.api.company.repository.CompanyRegionRepository;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.company.repository.CompanyZoneRepository;
import com.lifecontrol.api.country.repository.CountryRepository;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * {@code CompanyStoreRepository#findIdsByHierarchyFilter} verified against real PostgreSQL with
 * Flyway enabled, so the JPQL predicates are checked by the database that runs them in production.
 *
 * <p>The finder is the only part of the receipt location filter a unit test cannot cover: the same
 * "keep every occurrence typed" hazard that made the purchase-order list answer {@code HTTP 500} on an
 * absent {@code search} (<code>odd/tasks/purchase-order-region-filter.md</code>, L8) applies to a null
 * UUID parameter, and no mock renders SQL. The first assertion below is deliberately the all-null call:
 * it is the one whose null bindings have to be typed by the path comparison, and running it first in its
 * own Spring context keeps a previous non-null binding from typing the parameter for it.</p>
 */
@SpringBootTest
@DisplayName("Company store hierarchy filter integration tests")
class CompanyStoreHierarchyFilterIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private CompanyStoreRepository companyStoreRepository;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private CompanyCountryRepository companyCountryRepository;

    @Autowired
    private CompanyRegionRepository companyRegionRepository;

    @Autowired
    private CompanyZoneRepository companyZoneRepository;

    @Autowired
    private CountryRepository countryRepository;

    private UUID companyAId;
    private UUID regionAlphaId;
    private UUID regionBetaId;
    private UUID zoneBetaOneId;
    private UUID storeAlphaId;
    private UUID storeBetaOneId;

    private UUID companyBId;
    private UUID storeGammaId;

    @BeforeEach
    void setUp() {
        var companyA = findOrCreateCompany("GR-FILTER-A", "GR Filter Company A", "GRFA010101ABC");
        var countryA = findOrCreateCompanyCountry(companyA);
        var regionAlpha = findOrCreateRegion(countryA, "GRFA", "GR Filter Region Alpha");
        var regionBeta = findOrCreateRegion(countryA, "GRFB", "GR Filter Region Beta");
        var zoneAlpha = findOrCreateZone(regionAlpha, "GRFA1", "GR Filter Zone Alpha");
        var zoneBetaOne = findOrCreateZone(regionBeta, "GRFB1", "GR Filter Zone Beta One");

        companyAId = companyA.getId();
        regionAlphaId = regionAlpha.getId();
        regionBetaId = regionBeta.getId();
        zoneBetaOneId = zoneBetaOne.getId();
        storeAlphaId = findOrCreateStore(zoneAlpha, "GR Filter Store Alpha").getId();
        storeBetaOneId =
                findOrCreateStore(zoneBetaOne, "GR Filter Store Beta One").getId();

        var companyB = findOrCreateCompany("GR-FILTER-B", "GR Filter Company B", "GRFB010101ABC");
        var countryB = findOrCreateCompanyCountry(companyB);
        var regionGamma = findOrCreateRegion(countryB, "GRFG", "GR Filter Region Gamma");
        var zoneGamma = findOrCreateZone(regionGamma, "GRFG1", "GR Filter Zone Gamma");

        companyBId = companyB.getId();
        storeGammaId = findOrCreateStore(zoneGamma, "GR Filter Store Gamma").getId();
    }

    @Test
    @DisplayName("should constrain nothing when every level is null")
    void noLevelConstrainsNothing() {
        var storeIds = companyStoreRepository.findIdsByHierarchyFilter(null, null, null, null, null);

        // Every seeded store, and nothing else: the null bindings are typed by the path comparisons.
        assertThat(storeIds).contains(storeAlphaId, storeBetaOneId, storeGammaId);
    }

    @Test
    @DisplayName("should return the stores of one company")
    void companyLevelRestrictsToItsStores() {
        var storeIds = companyStoreRepository.findIdsByHierarchyFilter(companyAId, null, null, null, null);

        assertThat(storeIds).containsExactlyInAnyOrder(storeAlphaId, storeBetaOneId);
    }

    @Test
    @DisplayName("should narrow a company to a region named on its own")
    void regionLevelAloneNarrows() {
        var storeIds = companyStoreRepository.findIdsByHierarchyFilter(null, null, regionBetaId, null, null);

        assertThat(storeIds).containsExactly(storeBetaOneId);
    }

    @Test
    @DisplayName("should combine levels with AND, so a zone of another region matches nothing")
    void levelsCombineWithAnd() {
        var storeIds = companyStoreRepository.findIdsByHierarchyFilter(null, null, regionAlphaId, zoneBetaOneId, null);

        assertThat(storeIds).isEmpty();
    }

    @Test
    @DisplayName("should resolve the whole chain down to a single store")
    void storeLevelRestrictsToOne() {
        var storeIds = companyStoreRepository.findIdsByHierarchyFilter(
                companyAId, null, regionBetaId, zoneBetaOneId, storeBetaOneId);

        assertThat(storeIds).containsExactly(storeBetaOneId);
    }

    @Test
    @DisplayName("should exclude another company's store when the filter names it together with this company")
    void foreignStoreDoesNotLeakThroughTheFilter() {
        var storeIds = companyStoreRepository.findIdsByHierarchyFilter(companyAId, null, null, null, storeGammaId);

        assertThat(storeIds).isEmpty();
        assertThat(companyStoreRepository.findIdsByHierarchyFilter(null, null, null, null, storeGammaId))
                .containsExactly(storeGammaId);
    }

    @Test
    @DisplayName("should answer an empty list for an unknown id")
    void unknownIdMatchesNothing() {
        var storeIds = companyStoreRepository.findIdsByHierarchyFilter(null, null, null, null, UUID.randomUUID());

        assertThat(storeIds).isEmpty();
    }

    // ─── fixture ────────────────────────────────────────────────────────

    private Company findOrCreateCompany(String key, String name, String rfc) {
        return companyRepository
                .findByCompanyKey(key)
                .orElseGet(() -> companyRepository.save(Company.builder()
                        .companyKey(key)
                        .companyName(name)
                        .rfc(rfc)
                        .enabled(true)
                        .build()));
    }

    private CompanyCountry findOrCreateCompanyCountry(Company company) {
        var country = countryRepository.findByCountryCode("MX").orElseThrow();
        return companyCountryRepository
                .findByCompanyIdAndCountryId(company.getId(), country.getId())
                .orElseGet(() -> companyCountryRepository.save(CompanyCountry.builder()
                        .company(company)
                        .country(country)
                        .build()));
    }

    private CompanyRegion findOrCreateRegion(CompanyCountry companyCountry, String code, String name) {
        return companyRegionRepository.findByCompanyCountryIdOrderByRegionNameAsc(companyCountry.getId()).stream()
                .filter(region -> name.equals(region.getRegionName()))
                .findFirst()
                .orElseGet(() -> companyRegionRepository.save(CompanyRegion.builder()
                        .companyCountry(companyCountry)
                        .regionCode(code)
                        .regionName(name)
                        .enabled(true)
                        .build()));
    }

    private CompanyZone findOrCreateZone(CompanyRegion region, String code, String name) {
        return companyZoneRepository.findByCompanyRegionIdOrderByZoneNameAsc(region.getId()).stream()
                .filter(zone -> name.equals(zone.getZoneName()))
                .findFirst()
                .orElseGet(() -> companyZoneRepository.save(CompanyZone.builder()
                        .companyRegion(region)
                        .zoneCode(code)
                        .zoneName(name)
                        .enabled(true)
                        .build()));
    }

    private CompanyStore findOrCreateStore(CompanyZone zone, String name) {
        return companyStoreRepository.findByCompanyZoneId(zone.getId()).stream()
                .filter(store -> name.equals(store.getStoreName()))
                .findFirst()
                .orElseGet(() -> companyStoreRepository.save(CompanyStore.builder()
                        .companyZone(zone)
                        .storeName(name)
                        .enabled(true)
                        .build()));
    }
}
