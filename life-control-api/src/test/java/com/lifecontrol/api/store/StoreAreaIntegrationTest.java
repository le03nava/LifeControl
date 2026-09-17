package com.lifecontrol.api.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
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
import com.lifecontrol.api.store.dto.CreateStoreAreaRequest;
import com.lifecontrol.api.store.dto.UpdateStoreAreaRequest;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import com.lifecontrol.api.store.repository.StoreAreaRepository;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Persistence-level verification of {@code store_areas} against real PostgreSQL, with Flyway
 * enabled so {@code V5__store_areas.sql} builds the table exactly as in production
 * (including {@code UNIQUE(company_store_id, area_code)} and the soft-delete semantics).
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Store Area Integration Tests")
class StoreAreaIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StoreAreaRepository storeAreaRepository;

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

    private static final SimpleGrantedAuthority ROLE_LC_ADMIN = new SimpleGrantedAuthority("ROLE_lc-admin");

    private static final String BASE_URL =
            "/api/companies/{companyId}/countries/{companyCountryId}/regions/{regionId}/zones/{zoneId}/stores/{storeId}/areas";

    private UUID companyId;
    private UUID companyCountryId;
    private UUID regionId;
    private UUID zoneId;
    private UUID storeId;

    @BeforeEach
    void setUp() {
        storeAreaRepository.deleteAll();
        seedCompanyHierarchy();
    }

    /**
     * Find-or-create the company &rarr; country &rarr; region &rarr; zone &rarr; store chain.
     * Idempotent because the container is shared per JVM and the data survives across methods.
     */
    private void seedCompanyHierarchy() {
        var country = countryRepository.findByCountryCode("MX").orElseThrow();

        var company = companyRepository
                .findByCompanyKey("STORE-AREA-KEY")
                .orElseGet(() -> companyRepository.save(Company.builder()
                        .companyKey("STORE-AREA-KEY")
                        .companyName("Store Area Test Company")
                        .rfc("SARK010101ABC")
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
                        .regionCode("SA")
                        .regionName("Store Area Region")
                        .enabled(true)
                        .build()));

        var zone = companyZoneRepository.findByCompanyRegionIdOrderByZoneNameAsc(region.getId()).stream()
                .findFirst()
                .orElseGet(() -> companyZoneRepository.save(CompanyZone.builder()
                        .companyRegion(region)
                        .zoneCode("SA")
                        .zoneName("Store Area Zone")
                        .enabled(true)
                        .build()));

        var store = companyStoreRepository.findByCompanyZoneId(zone.getId()).stream()
                .findFirst()
                .orElseGet(() -> companyStoreRepository.save(CompanyStore.builder()
                        .companyZone(zone)
                        .storeName("Store Area Test Store")
                        .enabled(true)
                        .build()));

        companyId = company.getId();
        companyCountryId = companyCountry.getId();
        regionId = region.getId();
        zoneId = zone.getId();
        storeId = store.getId();
    }

    private String createAreaJson(String areaCode, String areaName, Integer displayOrder) throws Exception {
        return objectMapper.writeValueAsString(new CreateStoreAreaRequest(areaCode, areaName, null, displayOrder));
    }

    private void createArea(String areaCode, String areaName, Integer displayOrder) throws Exception {
        mockMvc.perform(post(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId)
                        .with(jwt().authorities(ROLE_LC_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createAreaJson(areaCode, areaName, displayOrder)))
                .andExpect(status().isCreated());
    }

    @Nested
    @DisplayName("create + list")
    class CreateAndListTests {

        @Test
        @DisplayName("should persist an area and return it in the active list")
        void createArea_ThenListActive() throws Exception {
            mockMvc.perform(post(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId)
                            .with(jwt().authorities(ROLE_LC_ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createAreaJson("A01", "Bodega", 1)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.areaCode").value("A01"))
                    .andExpect(jsonPath("$.areaName").value("Bodega"))
                    .andExpect(jsonPath("$.companyStoreId").value(storeId.toString()))
                    .andExpect(jsonPath("$.enabled").value(true));

            var persisted = storeAreaRepository.findByIdAndCompanyStoreId(
                    storeAreaRepository.findAll().get(0).getId(), storeId);
            assertThat(persisted).isPresent();
            assertThat(persisted.get().getEnabled()).isTrue();

            mockMvc.perform(get(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId)
                            .with(jwt().authorities(ROLE_LC_ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].areaCode").value("A01"));
        }

        @Test
        @DisplayName("should order the active list by displayOrder then areaCode")
        void listActive_IsOrderedByDisplayOrderThenAreaCode() throws Exception {
            createArea("A02", "Segunda", 2);
            createArea("A01", "Primera", 1);

            mockMvc.perform(get(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId)
                            .with(jwt().authorities(ROLE_LC_ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].areaCode").value("A01"))
                    .andExpect(jsonPath("$[1].areaCode").value("A02"));
        }

        @Test
        @DisplayName("should reject a duplicate area code inside the same store with 409")
        void createArea_DuplicateCodeInStore() throws Exception {
            createArea("A01", "Bodega", 1);

            mockMvc.perform(post(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId)
                            .with(jwt().authorities(ROLE_LC_ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createAreaJson("A01", "Otra bodega", 2)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.message").value("Store area with code 'A01' already exists in this store"));

            assertThat(storeAreaRepository.existsByCompanyStoreIdAndAreaCode(storeId, "A01"))
                    .isTrue();
            assertThat(storeAreaRepository.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("should allow the same area code in a different store of the same zone")
        void createArea_SameCodeInDifferentStoreIsAllowed() throws Exception {
            createArea("A01", "Bodega", 1);

            var zone = companyZoneRepository.findById(zoneId).orElseThrow();
            var secondStore = companyStoreRepository.save(CompanyStore.builder()
                    .companyZone(zone)
                    .storeName("Store Area Test Store 2")
                    .enabled(true)
                    .build());

            mockMvc.perform(post(BASE_URL, companyId, companyCountryId, regionId, zoneId, secondStore.getId())
                            .with(jwt().authorities(ROLE_LC_ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createAreaJson("A01", "Bodega", 1)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.companyStoreId")
                            .value(secondStore.getId().toString()));

            assertThat(storeAreaRepository.count()).isEqualTo(2);
        }

        @Test
        @DisplayName("should return 400 when areaCode is blank")
        void createArea_BlankCodeReturns400() throws Exception {
            mockMvc.perform(post(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId)
                            .with(jwt().authorities(ROLE_LC_ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createAreaJson("", "Bodega", 1)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.areaCode").exists());
        }

        @Test
        @DisplayName("should return 404 when the store does not belong to the zone")
        void createArea_UnknownStoreReturns404() throws Exception {
            mockMvc.perform(post(BASE_URL, companyId, companyCountryId, regionId, zoneId, UUID.randomUUID())
                            .with(jwt().authorities(ROLE_LC_ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createAreaJson("A01", "Bodega", 1)))
                    .andExpect(status().isNotFound());

            assertThat(storeAreaRepository.count()).isZero();
        }
    }

    @Nested
    @DisplayName("soft delete / enable")
    class SoftDeleteAndEnableTests {

        @Test
        @DisplayName("should exclude soft-deleted areas from the active list but keep the row")
        void softDelete_ExcludesFromActiveListKeepsRow() throws Exception {
            createArea("A01", "Bodega", 1);
            createArea("A02", "Piso de venta", 2);
            var disabledId = storeAreaRepository.findByCompanyStoreIdOrderByDisplayOrderAscAreaCodeAsc(storeId).stream()
                    .filter(area -> "A02".equals(area.getAreaCode()))
                    .findFirst()
                    .orElseThrow()
                    .getId();

            mockMvc.perform(delete(
                                    BASE_URL + "/{areaId}",
                                    companyId,
                                    companyCountryId,
                                    regionId,
                                    zoneId,
                                    storeId,
                                    disabledId)
                            .with(jwt().authorities(ROLE_LC_ADMIN)))
                    .andExpect(status().isNoContent());

            var row = storeAreaRepository.findById(disabledId).orElseThrow();
            assertThat(row.getEnabled()).isFalse();

            mockMvc.perform(get(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId)
                            .with(jwt().authorities(ROLE_LC_ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].areaCode").value("A01"));

            mockMvc.perform(get(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId)
                            .param("includeDisabled", "true")
                            .with(jwt().authorities(ROLE_LC_ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2));
        }

        @Test
        @DisplayName("should re-enable a soft-deleted area through PATCH /enable")
        void enable_ReactivatesArea() throws Exception {
            createArea("A01", "Bodega", 1);
            var areaId = storeAreaRepository.findByCompanyStoreIdOrderByDisplayOrderAscAreaCodeAsc(storeId).stream()
                    .findFirst()
                    .orElseThrow()
                    .getId();

            mockMvc.perform(delete(
                                    BASE_URL + "/{areaId}",
                                    companyId,
                                    companyCountryId,
                                    regionId,
                                    zoneId,
                                    storeId,
                                    areaId)
                            .with(jwt().authorities(ROLE_LC_ADMIN)))
                    .andExpect(status().isNoContent());

            mockMvc.perform(patch(
                                    BASE_URL + "/{areaId}/enable",
                                    companyId,
                                    companyCountryId,
                                    regionId,
                                    zoneId,
                                    storeId,
                                    areaId)
                            .with(jwt().authorities(ROLE_LC_ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.enabled").value(true));

            assertThat(storeAreaRepository.findById(areaId).orElseThrow().getEnabled())
                    .isTrue();

            mockMvc.perform(get(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId)
                            .with(jwt().authorities(ROLE_LC_ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1));
        }

        @Test
        @DisplayName("should return 404 when enabling an unknown area")
        void enable_UnknownAreaReturns404() throws Exception {
            var unknownId = UUID.randomUUID();

            mockMvc.perform(patch(
                                    BASE_URL + "/{areaId}/enable",
                                    companyId,
                                    companyCountryId,
                                    regionId,
                                    zoneId,
                                    storeId,
                                    unknownId)
                            .with(jwt().authorities(ROLE_LC_ADMIN)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Store area not found with id: " + unknownId));
        }
    }

    @Nested
    @DisplayName("read single / update")
    class ReadAndUpdateTests {

        @Test
        @DisplayName("should return 200 with the persisted area")
        void getById_ReturnsArea() throws Exception {
            createArea("A01", "Bodega", 1);
            var areaId = storeAreaRepository.findByCompanyStoreIdOrderByDisplayOrderAscAreaCodeAsc(storeId).stream()
                    .findFirst()
                    .orElseThrow()
                    .getId();

            mockMvc.perform(get(BASE_URL + "/{areaId}", companyId, companyCountryId, regionId, zoneId, storeId, areaId)
                            .with(jwt().authorities(ROLE_LC_ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(areaId.toString()))
                    .andExpect(jsonPath("$.areaCode").value("A01"));
        }

        @Test
        @DisplayName("should return 404 for an unknown area id")
        void getById_UnknownAreaReturns404() throws Exception {
            var unknownId = UUID.randomUUID();

            mockMvc.perform(get(
                                    BASE_URL + "/{areaId}",
                                    companyId,
                                    companyCountryId,
                                    regionId,
                                    zoneId,
                                    storeId,
                                    unknownId)
                            .with(jwt().authorities(ROLE_LC_ADMIN)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Store area not found with id: " + unknownId));
        }

        @Test
        @DisplayName("should persist an update and keep the unchanged fields")
        void updateArea_PersistsChanges() throws Exception {
            createArea("A01", "Bodega", 1);
            var areaId = storeAreaRepository.findByCompanyStoreIdOrderByDisplayOrderAscAreaCodeAsc(storeId).stream()
                    .findFirst()
                    .orElseThrow()
                    .getId();

            mockMvc.perform(put(BASE_URL + "/{areaId}", companyId, companyCountryId, regionId, zoneId, storeId, areaId)
                            .with(jwt().authorities(ROLE_LC_ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new UpdateStoreAreaRequest("A05", "Bodega renovada", null, 5))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.areaCode").value("A05"))
                    .andExpect(jsonPath("$.areaName").value("Bodega renovada"))
                    .andExpect(jsonPath("$.displayOrder").value(5));

            var row = storeAreaRepository.findById(areaId).orElseThrow();
            assertThat(row.getAreaCode()).isEqualTo("A05");
            assertThat(row.getAreaName()).isEqualTo("Bodega renovada");
            assertThat(row.getDisplayOrder()).isEqualTo(5);
        }

        @Test
        @DisplayName("should return 409 when updating into an existing area code")
        void updateArea_DuplicateCodeReturns409() throws Exception {
            createArea("A01", "Bodega", 1);
            createArea("A02", "Piso de venta", 2);
            var secondId = storeAreaRepository.findByCompanyStoreIdOrderByDisplayOrderAscAreaCodeAsc(storeId).stream()
                    .filter(area -> "A02".equals(area.getAreaCode()))
                    .findFirst()
                    .orElseThrow()
                    .getId();

            mockMvc.perform(put(
                                    BASE_URL + "/{areaId}",
                                    companyId,
                                    companyCountryId,
                                    regionId,
                                    zoneId,
                                    storeId,
                                    secondId)
                            .with(jwt().authorities(ROLE_LC_ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new UpdateStoreAreaRequest("A01", null, null, null))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("Store area with code 'A01' already exists in this store"));
        }
    }
}
