package com.lifecontrol.api.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifecontrol.api.common.address.model.Address;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.company.repository.CompanyCountryRepository;
import com.lifecontrol.api.company.repository.CompanyRegionRepository;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.company.repository.CompanyZoneRepository;
import com.lifecontrol.api.country.model.Country;
import com.lifecontrol.api.country.repository.CountryRepository;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Query-count guard for the store list path against real PostgreSQL.
 *
 * <p>{@code spring.jpa.open-in-view=false} makes every un-fetched association in the response
 * mapper a separate select per element, so the list path is measured instead of assumed: the
 * {@code @EntityGraph} on the {@code CompanyStoreRepository} list finders must keep the statement
 * count constant as the number of stores in the zone grows. Hibernate statistics are enabled for
 * this class only.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@DisplayName("Store list query count Integration Tests")
class CompanyStoreListQueryCountIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final SimpleGrantedAuthority ROLE_LC_ADMIN = new SimpleGrantedAuthority("ROLE_lc-admin");

    private static final Logger LOGGER = LoggerFactory.getLogger(CompanyStoreListQueryCountIntegrationTest.class);

    private static final String BASE_URL =
            "/api/companies/{companyId}/countries/{companyCountryId}/regions/{regionId}/zones/{zoneId}/stores";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CompanyStoreRepository companyStoreRepository;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private CompanyCountryRepository companyCountryRepository;

    @Autowired
    private CompanyRegionRepository companyRegionRepository;

    @Autowired
    private CompanyZoneRepository companyZoneRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Statistics statistics;
    private UUID companyId;
    private UUID companyCountryId;
    private UUID regionId;
    private Country country;

    @BeforeEach
    void setUp() {
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        country = countryRepository.findByCountryCode("MX").orElseThrow();

        var company = companyRepository
                .findByCompanyKey("STORE-QUERY-COUNT-KEY")
                .orElseGet(() -> companyRepository.save(Company.builder()
                        .companyKey("STORE-QUERY-COUNT-KEY")
                        .companyName("Store Query Count Test Company")
                        .rfc("SQCT010101ABC")
                        .enabled(true)
                        .build()));

        var companyCountry = companyCountryRepository
                .findByCompanyIdAndCountryId(company.getId(), country.getId())
                .orElseGet(() -> companyCountryRepository.save(CompanyCountry.builder()
                        .company(company)
                        .country(country)
                        .build()));

        var region = companyRegionRepository.findByCompanyCountryIdOrderByRegionNameAsc(companyCountry.getId()).stream()
                .findFirst()
                .orElseGet(() -> companyRegionRepository.save(CompanyRegion.builder()
                        .companyCountry(companyCountry)
                        .regionCode("QC")
                        .regionName("Store Query Count Region")
                        .enabled(true)
                        .build()));

        companyId = company.getId();
        companyCountryId = companyCountry.getId();
        regionId = region.getId();
    }

    @Test
    @DisplayName("should issue the same number of SQL statements for 1 store and for 4 stores")
    void listStatements_AreConstantRegardlessOfStoreCount() throws Exception {
        // A dedicated zone keeps the measurement isolated from any other class's data.
        var region = companyRegionRepository.findById(regionId).orElseThrow();
        var zone = companyZoneRepository.save(CompanyZone.builder()
                .companyRegion(region)
                .zoneCode("QC" + UUID.randomUUID().toString().substring(0, 6))
                .zoneName("Query Count Zone")
                .enabled(true)
                .build());

        createStoreWithAddress(zone, "Single Store");
        var oneStoreStatements = countListStatements(zone.getId(), 1);

        for (var index = 2; index <= 4; index++) {
            createStoreWithAddress(zone, "Store " + index);
        }
        var fourStoreStatements = countListStatements(zone.getId(), 4);

        LOGGER.info(
                "store list query count: 1 store={} statements, 4 stores={} statements",
                oneStoreStatements,
                fourStoreStatements);

        assertThat(fourStoreStatements)
                .as(
                        "N+1 guard: 1 store issued %d statements, 4 stores issued %d statements",
                        oneStoreStatements, fourStoreStatements)
                .isEqualTo(oneStoreStatements);
    }

    private long countListStatements(UUID zoneId, int expectedStores) throws Exception {
        statistics.clear();

        mockMvc.perform(get(BASE_URL, companyId, companyCountryId, regionId, zoneId)
                        .with(jwt().authorities(ROLE_LC_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(expectedStores));

        return statistics.getPrepareStatementCount();
    }

    private void createStoreWithAddress(CompanyZone zone, String storeName) {
        var address = Address.builder()
                .street("Calle " + storeName)
                .city("Ciudad")
                .country(country)
                .enabled(true)
                .build();

        companyStoreRepository.save(CompanyStore.builder()
                .companyZone(zone)
                .storeName(storeName)
                .address(address)
                .enabled(true)
                .build());
    }
}
