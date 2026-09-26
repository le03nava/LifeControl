package com.lifecontrol.api.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.company.repository.CompanyCountryRepository;
import com.lifecontrol.api.company.repository.CompanyRegionRepository;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.company.repository.CompanyZoneRepository;
import com.lifecontrol.api.country.repository.CountryRepository;
import com.lifecontrol.api.store.dto.CreateCompanyStoreRequest;
import com.lifecontrol.api.store.dto.UpdateCompanyStoreRequest;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * HTTP-level verification of the {@code company_stores} version precondition and of the post-flush
 * response mapping, against real PostgreSQL with Flyway enabled.
 *
 * <p>Four behaviours are pinned here: a {@code PUT} asserting a stale version answers 412 and
 * writes nothing; a {@code PUT} asserting the current version answers 200 with a version advanced
 * by one and a fresh {@code updatedAt}; the enable {@code PATCH} answers with the post-flush
 * version and a fresh {@code updatedAt}; and the create path answers non-null timestamps. The
 * version increment and the fresh timestamp are exactly what the {@code saveAndFlush} change buys:
 * Hibernate advances {@code @Version} and runs the {@code Auditable} callbacks at flush time, so a
 * response mapped from a non-flushed entity would be stale on both.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Company store version precondition Integration Tests")
class CompanyStoreVersionPreconditionIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final SimpleGrantedAuthority ROLE_LC_ADMIN = new SimpleGrantedAuthority("ROLE_lc-admin");

    private static final String BASE_URL =
            "/api/companies/{companyId}/countries/{companyCountryId}/regions/{regionId}/zones/{zoneId}/stores";
    private static final String STORE_URL = BASE_URL + "/{storeId}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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

    private UUID companyId;
    private UUID companyCountryId;
    private UUID regionId;
    private UUID zoneId;
    private UUID storeId;

    @BeforeEach
    void setUp() {
        seedCompanyHierarchy();
    }

    /** Find-or-create the company &rarr; country &rarr; region &rarr; zone &rarr; store chain. */
    private void seedCompanyHierarchy() {
        var country = countryRepository.findByCountryCode("MX").orElseThrow();

        var company = companyRepository
                .findByCompanyKey("STORE-VERSION-KEY")
                .orElseGet(() -> companyRepository.save(Company.builder()
                        .companyKey("STORE-VERSION-KEY")
                        .companyName("Store Version Test Company")
                        .rfc("SVPR010101ABC")
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
                        .regionCode("SVP")
                        .regionName("Store Version Region")
                        .enabled(true)
                        .build()));

        var zone = companyZoneRepository.findByCompanyRegionIdOrderByZoneNameAsc(region.getId()).stream()
                .findFirst()
                .orElseGet(() -> companyZoneRepository.save(CompanyZone.builder()
                        .companyRegion(region)
                        .zoneCode("SVP")
                        .zoneName("Store Version Zone")
                        .enabled(true)
                        .build()));

        var store = companyStoreRepository.findByCompanyZoneId(zone.getId()).stream()
                .findFirst()
                .orElseGet(() -> companyStoreRepository.save(CompanyStore.builder()
                        .companyZone(zone)
                        .storeName("Version Precondition Store")
                        .enabled(true)
                        .build()));

        companyId = company.getId();
        companyCountryId = companyCountry.getId();
        regionId = region.getId();
        zoneId = zone.getId();
        storeId = store.getId();
    }

    private ResultActions putStore(UpdateCompanyStoreRequest request) throws Exception {
        return mockMvc.perform(put(STORE_URL, companyId, companyCountryId, regionId, zoneId, storeId)
                .with(jwt().authorities(ROLE_LC_ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
    }

    private ResultActions getStore() throws Exception {
        return mockMvc.perform(get(STORE_URL, companyId, companyCountryId, regionId, zoneId, storeId)
                .with(jwt().authorities(ROLE_LC_ADMIN)));
    }

    @Test
    @DisplayName("should answer 412 and leave the row unchanged when a PUT asserts a stale version")
    void staleVersionPutAnswers412AndWritesNothing() throws Exception {
        // The row outlives this method (singleton container, find-or-create seed), so read the
        // version it actually holds instead of assuming a fresh zero.
        long versionBefore =
                companyStoreRepository.findById(storeId).orElseThrow().getVersion();

        // First accepted update, no precondition: it advances the stored version by exactly one.
        putStore(new UpdateCompanyStoreRequest("Primer cambio", null, null, null))
                .andExpect(status().isOk());

        var accepted = companyStoreRepository.findById(storeId).orElseThrow();
        long acceptedVersion = accepted.getVersion();
        LocalDateTime acceptedUpdatedAt = accepted.getUpdatedAt();
        assertThat(acceptedVersion).isEqualTo(versionBefore + 1);

        // The same store named again with an outdated version must be rejected.
        putStore(new UpdateCompanyStoreRequest("Cambio perdido", null, null, null, acceptedVersion - 1))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.status").value(412));

        var after = companyStoreRepository.findById(storeId).orElseThrow();
        assertThat(after.getStoreName()).isEqualTo("Primer cambio");
        assertThat(after.getVersion()).isEqualTo(acceptedVersion);
        assertThat(after.getUpdatedAt()).isEqualTo(acceptedUpdatedAt);
    }

    @Test
    @DisplayName("should answer 200 with the version advanced by one and a fresh updatedAt")
    void currentVersionPutAnswers200WithIncrementedVersionAndFreshUpdatedAt() throws Exception {
        var beforeBody = getStore()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var before = objectMapper.readTree(beforeBody);
        long versionBefore = before.get("version").asLong();
        LocalDateTime updatedAtBefore =
                LocalDateTime.parse(before.get("updatedAt").asText());

        var responseBody = putStore(new UpdateCompanyStoreRequest("Segundo cambio", null, null, null, versionBefore))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var response = objectMapper.readTree(responseBody);

        // The increment proves the flush happened before the response was mapped.
        assertThat(response.get("version").asLong()).isEqualTo(versionBefore + 1);
        // D4: the flushed update also carries a genuinely newer timestamp.
        assertThat(LocalDateTime.parse(response.get("updatedAt").asText())).isAfter(updatedAtBefore);

        var stored = companyStoreRepository.findById(storeId).orElseThrow();
        assertThat(stored.getVersion()).isEqualTo(versionBefore + 1);
        assertThat(stored.getStoreName()).isEqualTo("Segundo cambio");
    }

    @Test
    @DisplayName("should answer 200 when the PUT carries no version at all")
    void putWithoutVersionAnswers200() throws Exception {
        // The legacy four-argument construction serializes no `version` key: today's contract.
        putStore(new UpdateCompanyStoreRequest("Sin versión", null, null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeName").value("Sin versión"));

        assertThat(companyStoreRepository.findById(storeId).orElseThrow().getStoreName())
                .isEqualTo("Sin versión");
    }

    @Test
    @DisplayName("should answer an enable PATCH with the post-flush version and a fresh updatedAt")
    void enablePatchAnswersPostFlushVersionAndFreshUpdatedAt() throws Exception {
        // The enable endpoint takes no body, so the store is soft-deleted through the real DELETE
        // endpoint first.
        mockMvc.perform(delete(STORE_URL, companyId, companyCountryId, regionId, zoneId, storeId)
                        .with(jwt().authorities(ROLE_LC_ADMIN)))
                .andExpect(status().isNoContent());

        var disabled = companyStoreRepository.findById(storeId).orElseThrow();
        long versionBefore = disabled.getVersion();
        LocalDateTime updatedAtBefore = disabled.getUpdatedAt();

        var body = mockMvc.perform(patch(STORE_URL, companyId, companyCountryId, regionId, zoneId, storeId)
                        .with(jwt().authorities(ROLE_LC_ADMIN)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var reenabled = objectMapper.readTree(body);

        // Without the enable path's flush the response would repeat the pre-increment version.
        assertThat(reenabled.get("version").asLong()).isEqualTo(versionBefore + 1);
        assertThat(LocalDateTime.parse(reenabled.get("updatedAt").asText())).isAfter(updatedAtBefore);

        var stored = companyStoreRepository.findById(storeId).orElseThrow();
        assertThat(stored.getEnabled()).isTrue();
        assertThat(stored.getVersion()).isEqualTo(versionBefore + 1);
    }

    @Test
    @DisplayName("should answer a POST with non-null timestamps and a present version")
    void postAnswersTimestampsAndVersion() throws Exception {
        var body = mockMvc.perform(post(BASE_URL, companyId, companyCountryId, regionId, zoneId)
                        .with(jwt().authorities(ROLE_LC_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateCompanyStoreRequest("Tienda Nueva Con Versión", null, null, null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var created = objectMapper.readTree(body);

        // A non-flushed create maps a null createdAt: the Auditable callback has not run yet.
        assertThat(created.get("createdAt").isNull()).isFalse();
        assertThat(created.get("updatedAt").isNull()).isFalse();
        assertThat(created.get("version").asLong()).isZero();
    }
}
