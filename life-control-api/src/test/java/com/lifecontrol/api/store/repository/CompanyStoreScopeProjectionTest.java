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
import org.springframework.jdbc.core.JdbcTemplate;
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

    @Autowired
    private JdbcTemplate jdbc;

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

        // Each company is stated as its own tenant while its chain is written: a company's tenant is its own
        // id, so no single stated value admits two `companies` rows, and every child row inherits its parent's
        // tenant (records L24/L26). The transaction is this suite's own — Spring's test transaction began
        // before @BeforeEach ran — so the tenant is stated inside it.
        var ca = persistCompany("PROBE-A", "A", "XAXX010101001");
        var cb = persistCompany("PROBE-B", "B", "XAXX010101002");
        companyA = ca.getId();
        companyB = cb.getId();

        useTenant(companyA);
        var cca = CompanyCountry.builder().company(ca).country(country).build();
        em.persist(cca);
        countryA = cca.getId();

        var ra = CompanyRegion.builder()
                .companyCountry(cca)
                .regionCode("RA")
                .regionName("Region A")
                .build();
        em.persist(ra);
        regionA = ra.getId();

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
        em.persist(za1);
        em.persist(za2);
        zoneA1 = za1.getId();
        zoneA2 = za2.getId();

        var sa1 = CompanyStore.builder().companyZone(za1).storeName("Store A1").build();
        var sa2 = CompanyStore.builder()
                .companyZone(za2)
                .storeName("Store A2")
                .enabled(false)
                .build();
        em.persist(sa1);
        em.persist(sa2);
        storeA1 = sa1.getId();
        storeA2 = sa2.getId();

        // The switch flushes A's chain under A's own tenant before B's is stated.
        useTenant(companyB);
        var ccb = CompanyCountry.builder().company(cb).country(country).build();
        em.persist(ccb);
        countryB = ccb.getId();

        var rb = CompanyRegion.builder()
                .companyCountry(ccb)
                .regionCode("RB")
                .regionName("Region B")
                .build();
        em.persist(rb);
        regionB = rb.getId();

        var zb = CompanyZone.builder()
                .companyRegion(rb)
                .zoneCode("ZB")
                .zoneName("Zone B")
                .build();
        em.persist(zb);
        zoneB = zb.getId();

        var sb = CompanyStore.builder().companyZone(zb).storeName("Store B").build();
        em.persist(sb);
        storeB = sb.getId();

        // Back to the tenant the first case works on; the switch flushes B's chain under B's tenant first.
        useTenant(companyA);
        em.clear();
    }

    /**
     * Persists one {@code companies} row at an id chosen in memory, stating it as the tenant before the insert.
     */
    private Company persistCompany(String companyKey, String companyName, String rfc) {
        var company = Company.builder()
                .id(UUID.randomUUID())
                .companyKey(companyKey)
                .companyName(companyName)
                .rfc(rfc)
                // Required since V57; this suite is about the scope projection, so it states the seeded code.
                .currency(TenantFixtures.SEED_CURRENCY)
                .build();
        useTenant(company.getId());
        em.persist(company);
        return company;
    }

    /**
     * Switches this suite's transaction to {@code companyId}: the statements the previous tenant left pending
     * run under that tenant first, and the new value is stated after them.
     *
     * <p>Every row here is on one of the sixteen armed tables, so the sequence matters twice: the state has to
     * be stated before the insert that carries it (a company's tenant is its own id, its children's is their
     * parent's), and a stated value only covers the statements that execute while it is stated — Hibernate
     * defers an insert to the next flush, so switching without flushing would run the previous tenant's row
     * under the new tenant's scope and meet that table's {@code WITH CHECK}. The whole suite is
     * {@code @Transactional}, so the value lives inside Spring's own test transaction, which began before
     * {@code @BeforeEach} and cannot be retro-fitted (record L24, cause B).</p>
     */
    private void useTenant(UUID companyId) {
        em.flush();
        TenantFixtures.stateTenantWithinTheOpenTransaction(companyId, jdbc);
    }

    @Test
    @DisplayName("each level returns exactly the stores underneath it, disabled ones included")
    void projectionsWalkTheRightAssociations() {
        // One SET LOCAL per transaction and the assertions grouped by tenant: A's rows are the ones stated,
        // so A's five levels are checked first and B's three after the switch. Each expectation is the one it
        // always was; only the order within the method changed, because the two tenants cannot be visible at
        // the same time inside one transaction.
        useTenant(companyA);
        assertThat(companyStoreRepository.findIdsByCompanyIdIn(List.of(companyA)))
                .containsExactlyInAnyOrder(storeA1, storeA2);
        assertThat(companyStoreRepository.findIdsByCompanyCountryIdIn(List.of(countryA), List.of(companyA)))
                .containsExactlyInAnyOrder(storeA1, storeA2);
        assertThat(companyStoreRepository.findIdsByCompanyRegionIdIn(
                        List.of(regionA), List.of(countryA), List.of(companyA)))
                .containsExactlyInAnyOrder(storeA1, storeA2);
        assertThat(companyStoreRepository.findIdsByCompanyZoneIdIn(
                        List.of(zoneA1), List.of(regionA), List.of(countryA), List.of(companyA)))
                .containsExactly(storeA1);
        assertThat(companyStoreRepository.findIdsByCompanyZoneIdIn(
                        List.of(zoneA1, zoneA2), List.of(regionA), List.of(countryA), List.of(companyA)))
                .containsExactlyInAnyOrder(storeA1, storeA2);
        assertThat(companyStoreRepository.findIdsByStoreIdIn(
                        List.of(storeA2), List.of(zoneA2), List.of(regionA), List.of(countryA), List.of(companyA)))
                .containsExactly(storeA2);

        useTenant(companyB);
        assertThat(companyStoreRepository.findIdsByCompanyIdIn(List.of(companyB)))
                .containsExactly(storeB);
        assertThat(companyStoreRepository.findIdsByCompanyCountryIdIn(List.of(countryB), List.of(companyB)))
                .containsExactly(storeB);
        assertThat(companyStoreRepository.findIdsByCompanyRegionIdIn(
                        List.of(regionB), List.of(countryB), List.of(companyB)))
                .containsExactly(storeB);
    }

    @Test
    @DisplayName("a row whose ancestor claim disagrees is excluded, exactly as the detail denies it")
    void ancestorClaimsConstrainEveryLevel() {
        // Company A is the stated tenant, so A's rows are visible and every exclusion below is the query's
        // ancestor predicate doing its job rather than the policy hiding the row: that is the verdict this
        // case is about.
        useTenant(companyA);
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
        useTenant(companyA);
        assertThat(companyStoreRepository.findIdsByCompanyZoneIdIn(
                        List.of(UUID.randomUUID()), List.of(regionA), List.of(countryA), List.of(companyA)))
                .isEmpty();
        assertThat(companyStoreRepository.findIdsByCompanyRegionIdIn(
                        List.of(regionB), List.of(countryB), List.of(companyB)))
                .doesNotContain(storeA1, storeA2);
    }
}
