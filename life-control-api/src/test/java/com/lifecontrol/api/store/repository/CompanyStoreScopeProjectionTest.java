package com.lifecontrol.api.store.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.country.model.Country;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.support.TenantFixtures;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * PROBE: the four new JPQL projections must walk companyStore -> zone -> region -> country -> company
 * and return exactly the stores under the requested ancestor, on a real JPA provider.
 */
@SpringBootTest
@Transactional
@DisplayName("CompanyStore hierarchy projections")
class CompanyStoreScopeProjectionTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private EntityManager em;

    @Autowired
    private CompanyStoreRepository companyStoreRepository;

    private UUID companyA;
    private UUID companyB;
    private UUID countryA;
    private UUID countryB;
    private UUID regionA;
    private UUID regionB;
    private UUID zoneA1;
    private UUID zoneA2;
    private UUID zoneB;
    private UUID storeA1;
    private UUID storeA2;
    private UUID storeB;

    @BeforeEach
    void seed() {
        var country = Country.builder().countryCode("ZZ").countryName("Probe").build();
        em.persist(country);

        var ca = TenantFixtures.persist(
                em,
                Company.builder()
                        .companyKey("PROBE-A")
                        .companyName("A")
                        .rfc("XAXX010101001")
                        .build());
        var cb = TenantFixtures.persist(
                em,
                Company.builder()
                        .companyKey("PROBE-B")
                        .companyName("B")
                        .rfc("XAXX010101002")
                        .build());
        companyA = ca.getId();
        companyB = cb.getId();

        var cca = CompanyCountry.builder().company(ca).country(country).build();
        var ccb = CompanyCountry.builder().company(cb).country(country).build();
        em.persist(cca);
        em.persist(ccb);
        countryA = cca.getId();
        countryB = ccb.getId();

        var ra = CompanyRegion.builder()
                .companyCountry(cca)
                .regionCode("RA")
                .regionName("Region A")
                .build();
        var rb = CompanyRegion.builder()
                .companyCountry(ccb)
                .regionCode("RB")
                .regionName("Region B")
                .build();
        em.persist(ra);
        em.persist(rb);
        regionA = ra.getId();
        regionB = rb.getId();

        var za1 = CompanyZone.builder()
                .companyRegion(ra)
                .zoneCode("ZA1")
                .zoneName("Zone A1")
                .build();
        var za2 = CompanyZone.builder()
                .companyRegion(ra)
                .zoneCode("ZA2")
                .zoneName("Zone A2")
                .build();
        var zb = CompanyZone.builder()
                .companyRegion(rb)
                .zoneCode("ZB")
                .zoneName("Zone B")
                .build();
        em.persist(za1);
        em.persist(za2);
        em.persist(zb);
        zoneA1 = za1.getId();
        zoneA2 = za2.getId();
        zoneB = zb.getId();

        var sa1 = CompanyStore.builder().companyZone(za1).storeName("Store A1").build();
        var sa2 = CompanyStore.builder()
                .companyZone(za2)
                .storeName("Store A2")
                .enabled(false)
                .build();
        var sb = CompanyStore.builder().companyZone(zb).storeName("Store B").build();
        em.persist(sa1);
        em.persist(sa2);
        em.persist(sb);
        storeA1 = sa1.getId();
        storeA2 = sa2.getId();
        storeB = sb.getId();
        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("each level returns exactly the stores underneath it, disabled ones included")
    void projectionsWalkTheRightAssociations() {
        assertThat(companyStoreRepository.findIdsByCompanyIdIn(List.of(companyA)))
                .containsExactlyInAnyOrder(storeA1, storeA2);
        assertThat(companyStoreRepository.findIdsByCompanyIdIn(List.of(companyB)))
                .containsExactly(storeB);

        assertThat(companyStoreRepository.findIdsByCompanyCountryIdIn(List.of(countryA), List.of(companyA)))
                .containsExactlyInAnyOrder(storeA1, storeA2);
        assertThat(companyStoreRepository.findIdsByCompanyCountryIdIn(List.of(countryB), List.of(companyB)))
                .containsExactly(storeB);

        assertThat(companyStoreRepository.findIdsByCompanyRegionIdIn(
                        List.of(regionA), List.of(countryA), List.of(companyA)))
                .containsExactlyInAnyOrder(storeA1, storeA2);
        assertThat(companyStoreRepository.findIdsByCompanyRegionIdIn(
                        List.of(regionB), List.of(countryB), List.of(companyB)))
                .containsExactly(storeB);

        assertThat(companyStoreRepository.findIdsByCompanyZoneIdIn(
                        List.of(zoneA1), List.of(regionA), List.of(countryA), List.of(companyA)))
                .containsExactly(storeA1);
        assertThat(companyStoreRepository.findIdsByCompanyZoneIdIn(
                        List.of(zoneA1, zoneA2), List.of(regionA), List.of(countryA), List.of(companyA)))
                .containsExactlyInAnyOrder(storeA1, storeA2);
        assertThat(companyStoreRepository.findIdsByStoreIdIn(
                        List.of(storeA2), List.of(zoneA2), List.of(regionA), List.of(countryA), List.of(companyA)))
                .containsExactly(storeA2);
    }

    @Test
    @DisplayName("a row whose ancestor claim disagrees is excluded, exactly as the detail denies it")
    void ancestorClaimsConstrainEveryLevel() {
        // The deep claim matches but the company does not: the detail denies this row, so the list
        // must not return it. This is the defect the independent verification found.
        assertThat(companyStoreRepository.findIdsByCompanyCountryIdIn(List.of(countryA), List.of(companyB)))
                .isEmpty();
        assertThat(companyStoreRepository.findIdsByCompanyRegionIdIn(
                        List.of(regionA), List.of(countryA), List.of(companyB)))
                .isEmpty();
        assertThat(companyStoreRepository.findIdsByCompanyZoneIdIn(
                        List.of(zoneA1), List.of(regionA), List.of(countryB), List.of(companyB)))
                .isEmpty();
        // A region claim that does not own the zone excludes it even inside the right company.
        assertThat(companyStoreRepository.findIdsByCompanyZoneIdIn(
                        List.of(zoneA1), List.of(regionB), List.of(countryA), List.of(companyA)))
                .isEmpty();
        // The store level carries the same constraint: the store is right and an ancestor is not.
        assertThat(companyStoreRepository.findIdsByStoreIdIn(
                        List.of(storeA1), List.of(zoneB), List.of(regionA), List.of(countryA), List.of(companyA)))
                .isEmpty();
        assertThat(companyStoreRepository.findIdsByStoreIdIn(
                        List.of(storeA1), List.of(zoneA1), List.of(regionA), List.of(countryA), List.of(companyB)))
                .isEmpty();
    }

    @Test
    @DisplayName("an id that belongs to no chain resolves to nothing, and nothing bleeds across companies")
    void unknownIdsAndCrossCompanyBleed() {
        assertThat(companyStoreRepository.findIdsByCompanyZoneIdIn(
                        List.of(UUID.randomUUID()), List.of(regionA), List.of(countryA), List.of(companyA)))
                .isEmpty();
        assertThat(companyStoreRepository.findIdsByCompanyRegionIdIn(
                        List.of(regionB), List.of(countryB), List.of(companyB)))
                .doesNotContain(storeA1, storeA2);
    }
}
