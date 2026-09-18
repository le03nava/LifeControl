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
import com.lifecontrol.api.store.dto.CreateStoreZoneRequest;
import com.lifecontrol.api.store.dto.UpdateStoreZoneRequest;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import com.lifecontrol.api.store.repository.StoreAreaRepository;
import com.lifecontrol.api.store.repository.StoreZoneRepository;
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
 * Persistence-level verification of {@code store_zones} against real PostgreSQL, with Flyway
 * enabled so {@code V6__store_zones.sql} builds the table exactly as in production
 * (including {@code UNIQUE(store_area_id, zone_code)} and the soft-delete semantics).
 *
 * <p>Also covers the cascade regression: disabling an area must disable its still-enabled zones.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Store Zone Integration Tests")
class StoreZoneIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StoreZoneRepository storeZoneRepository;

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

    private static final String AREA_BASE_URL =
            "/api/companies/{companyId}/countries/{companyCountryId}/regions/{regionId}/zones/{zoneId}/stores/{storeId}/areas";

    private static final String ZONE_BASE_URL = AREA_BASE_URL + "/{areaId}/store-zones";

    private static final String STORE_BASE_URL =
            "/api/companies/{companyId}/countries/{companyCountryId}/regions/{regionId}/zones/{zoneId}/stores";

    private UUID companyId;
    private UUID companyCountryId;
    private UUID regionId;
    private UUID zoneId;
    private UUID storeId;

    @BeforeEach
    void setUp() {
        storeZoneRepository.deleteAll();
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
                .findByCompanyKey("STORE-ZONE-KEY")
                .orElseGet(() -> companyRepository.save(Company.builder()
                        .companyKey("STORE-ZONE-KEY")
                        .companyName("Store Zone Test Company")
                        .rfc("SZRK010101ABC")
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
                        .regionCode("SZ")
                        .regionName("Store Zone Region")
                        .enabled(true)
                        .build()));

        var zone = companyZoneRepository.findByCompanyRegionIdOrderByZoneNameAsc(region.getId()).stream()
                .findFirst()
                .orElseGet(() -> companyZoneRepository.save(CompanyZone.builder()
                        .companyRegion(region)
                        .zoneCode("SZ")
                        .zoneName("Store Zone Zone")
                        .enabled(true)
                        .build()));

        var store = companyStoreRepository.findByCompanyZoneId(zone.getId()).stream()
                .findFirst()
                .orElseGet(() -> companyStoreRepository.save(CompanyStore.builder()
                        .companyZone(zone)
                        .storeName("Store Zone Test Store")
                        .enabled(true)
                        .build()));

        // The store-cascade test disables the shared store; re-enable it so the fixture stays
        // identical for every method (the container is shared per JVM and data survives).
        if (!Boolean.TRUE.equals(store.getEnabled())) {
            store.setEnabled(true);
            store = companyStoreRepository.save(store);
        }

        companyId = company.getId();
        companyCountryId = companyCountry.getId();
        regionId = region.getId();
        zoneId = zone.getId();
        storeId = store.getId();
    }

    private UUID createArea(String areaCode, String areaName) throws Exception {
        mockMvc.perform(post(AREA_BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId)
                        .with(jwt().authorities(ROLE_LC_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateStoreAreaRequest(areaCode, areaName, null, 1))))
                .andExpect(status().isCreated());

        return storeAreaRepository.findByCompanyStoreIdOrderByDisplayOrderAscAreaCodeAsc(storeId).stream()
                .filter(area -> areaCode.equals(area.getAreaCode()))
                .findFirst()
                .orElseThrow()
                .getId();
    }

    private void createZone(UUID areaId, String zoneCode, String zoneName, Integer displayOrder) throws Exception {
        mockMvc.perform(post(ZONE_BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId, areaId)
                        .with(jwt().authorities(ROLE_LC_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateStoreZoneRequest(zoneCode, zoneName, null, displayOrder))))
                .andExpect(status().isCreated());
    }

    private UUID zoneIdOf(UUID areaId, String zoneCode) {
        return storeZoneRepository.findByStoreAreaIdOrderByDisplayOrderAscZoneCodeAsc(areaId).stream()
                .filter(zone -> zoneCode.equals(zone.getZoneCode()))
                .findFirst()
                .orElseThrow()
                .getId();
    }

    @Nested
    @DisplayName("create + list")
    class CreateAndListTests {

        @Test
        @DisplayName("should persist a zone and return it in the active list")
        void createZone_ThenListActive() throws Exception {
            var areaId = createArea("A01", "Bodega");

            mockMvc.perform(post(ZONE_BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId, areaId)
                            .with(jwt().authorities(ROLE_LC_ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new CreateStoreZoneRequest("Z01", "Pasillo", null, 1))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.zoneCode").value("Z01"))
                    .andExpect(jsonPath("$.zoneName").value("Pasillo"))
                    .andExpect(jsonPath("$.storeAreaId").value(areaId.toString()))
                    .andExpect(jsonPath("$.companyStoreId").value(storeId.toString()))
                    .andExpect(jsonPath("$.enabled").value(true));

            var persisted = storeZoneRepository.findByIdAndStoreAreaId(zoneIdOf(areaId, "Z01"), areaId);
            assertThat(persisted).isPresent();
            assertThat(persisted.get().getEnabled()).isTrue();

            mockMvc.perform(get(ZONE_BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId, areaId)
                            .with(jwt().authorities(ROLE_LC_ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].zoneCode").value("Z01"));
        }

        @Test
        @DisplayName("should order the active list by displayOrder then zoneCode")
        void listActive_IsOrderedByDisplayOrderThenZoneCode() throws Exception {
            var areaId = createArea("A01", "Bodega");
            createZone(areaId, "Z02", "Segunda", 2);
            createZone(areaId, "Z01", "Primera", 1);

            mockMvc.perform(get(ZONE_BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId, areaId)
                            .with(jwt().authorities(ROLE_LC_ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].zoneCode").value("Z01"))
                    .andExpect(jsonPath("$[1].zoneCode").value("Z02"));
        }

        @Test
        @DisplayName("should reject a duplicate zone code inside the same area with 409")
        void createZone_DuplicateCodeInArea() throws Exception {
            var areaId = createArea("A01", "Bodega");
            createZone(areaId, "Z01", "Pasillo", 1);

            mockMvc.perform(post(ZONE_BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId, areaId)
                            .with(jwt().authorities(ROLE_LC_ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new CreateStoreZoneRequest("Z01", "Otro pasillo", null, 2))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.message").value("Store zone with code 'Z01' already exists in this area"));

            assertThat(storeZoneRepository.existsByStoreAreaIdAndZoneCode(areaId, "Z01"))
                    .isTrue();
            assertThat(storeZoneRepository.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("should allow the same zone code in a different area of the same store")
        void createZone_SameCodeInDifferentAreaIsAllowed() throws Exception {
            var firstAreaId = createArea("A01", "Bodega");
            var secondAreaId = createArea("A02", "Piso de venta");
            createZone(firstAreaId, "Z01", "Pasillo", 1);

            mockMvc.perform(post(ZONE_BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId, secondAreaId)
                            .with(jwt().authorities(ROLE_LC_ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new CreateStoreZoneRequest("Z01", "Pasillo", null, 1))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.storeAreaId").value(secondAreaId.toString()));

            assertThat(storeZoneRepository.count()).isEqualTo(2);
        }

        @Test
        @DisplayName("should return 400 when zoneCode is blank")
        void createZone_BlankCodeReturns400() throws Exception {
            var areaId = createArea("A01", "Bodega");

            mockMvc.perform(post(ZONE_BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId, areaId)
                            .with(jwt().authorities(ROLE_LC_ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new CreateStoreZoneRequest("", "Pasillo", null, 1))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.zoneCode").exists());
        }

        @Test
        @DisplayName("should return 404 when the area does not belong to the store")
        void createZone_UnknownAreaReturns404() throws Exception {
            mockMvc.perform(post(
                                    ZONE_BASE_URL,
                                    companyId,
                                    companyCountryId,
                                    regionId,
                                    zoneId,
                                    storeId,
                                    UUID.randomUUID())
                            .with(jwt().authorities(ROLE_LC_ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new CreateStoreZoneRequest("Z01", "Pasillo", null, 1))))
                    .andExpect(status().isNotFound());

            assertThat(storeZoneRepository.count()).isZero();
        }

        @Test
        @DisplayName("should return 409 when creating a zone under a disabled area")
        void createZone_DisabledAreaReturns409() throws Exception {
            var areaId = createArea("A01", "Bodega");

            mockMvc.perform(delete(
                                    AREA_BASE_URL + "/{areaId}",
                                    companyId,
                                    companyCountryId,
                                    regionId,
                                    zoneId,
                                    storeId,
                                    areaId)
                            .with(jwt().authorities(ROLE_LC_ADMIN)))
                    .andExpect(status().isNoContent());

            mockMvc.perform(post(ZONE_BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId, areaId)
                            .with(jwt().authorities(ROLE_LC_ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new CreateStoreZoneRequest("Z01", "Pasillo", null, 1))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.message")
                            .value("Cannot create a store zone: store area with id " + areaId + " is disabled"));

            assertThat(storeZoneRepository.findByStoreAreaIdOrderByDisplayOrderAscZoneCodeAsc(areaId))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("soft delete / enable")
    class SoftDeleteAndEnableTests {

        @Test
        @DisplayName("should exclude soft-deleted zones from the active list but keep the row")
        void softDelete_ExcludesFromActiveListKeepsRow() throws Exception {
            var areaId = createArea("A01", "Bodega");
            createZone(areaId, "Z01", "Pasillo", 1);
            createZone(areaId, "Z02", "Estante", 2);
            var disabledId = zoneIdOf(areaId, "Z02");

            mockMvc.perform(delete(
                                    ZONE_BASE_URL + "/{storeZoneId}",
                                    companyId,
                                    companyCountryId,
                                    regionId,
                                    zoneId,
                                    storeId,
                                    areaId,
                                    disabledId)
                            .with(jwt().authorities(ROLE_LC_ADMIN)))
                    .andExpect(status().isNoContent());

            var row = storeZoneRepository.findById(disabledId).orElseThrow();
            assertThat(row.getEnabled()).isFalse();

            mockMvc.perform(get(ZONE_BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId, areaId)
                            .with(jwt().authorities(ROLE_LC_ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].zoneCode").value("Z01"));

            mockMvc.perform(get(ZONE_BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId, areaId)
                            .param("includeDisabled", "true")
                            .with(jwt().authorities(ROLE_LC_ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2));
        }

        @Test
        @DisplayName("should re-enable a soft-deleted zone through PATCH /enable")
        void enable_ReactivatesZone() throws Exception {
            var areaId = createArea("A01", "Bodega");
            createZone(areaId, "Z01", "Pasillo", 1);
            var storeZoneId = zoneIdOf(areaId, "Z01");

            mockMvc.perform(delete(
                                    ZONE_BASE_URL + "/{storeZoneId}",
                                    companyId,
                                    companyCountryId,
                                    regionId,
                                    zoneId,
                                    storeId,
                                    areaId,
                                    storeZoneId)
                            .with(jwt().authorities(ROLE_LC_ADMIN)))
                    .andExpect(status().isNoContent());

            mockMvc.perform(patch(
                                    ZONE_BASE_URL + "/{storeZoneId}/enable",
                                    companyId,
                                    companyCountryId,
                                    regionId,
                                    zoneId,
                                    storeId,
                                    areaId,
                                    storeZoneId)
                            .with(jwt().authorities(ROLE_LC_ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.enabled").value(true));

            assertThat(storeZoneRepository.findById(storeZoneId).orElseThrow().getEnabled())
                    .isTrue();

            mockMvc.perform(get(ZONE_BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId, areaId)
                            .with(jwt().authorities(ROLE_LC_ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1));
        }

        @Test
        @DisplayName("should return 404 when enabling an unknown zone")
        void enable_UnknownZoneReturns404() throws Exception {
            var areaId = createArea("A01", "Bodega");
            var unknownId = UUID.randomUUID();

            mockMvc.perform(patch(
                                    ZONE_BASE_URL + "/{storeZoneId}/enable",
                                    companyId,
                                    companyCountryId,
                                    regionId,
                                    zoneId,
                                    storeId,
                                    areaId,
                                    unknownId)
                            .with(jwt().authorities(ROLE_LC_ADMIN)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Store zone not found with id: " + unknownId));
        }
    }

    @Nested
    @DisplayName("read single / update")
    class ReadAndUpdateTests {

        @Test
        @DisplayName("should return 200 with the persisted zone")
        void getById_ReturnsZone() throws Exception {
            var areaId = createArea("A01", "Bodega");
            createZone(areaId, "Z01", "Pasillo", 1);
            var storeZoneId = zoneIdOf(areaId, "Z01");

            mockMvc.perform(get(
                                    ZONE_BASE_URL + "/{storeZoneId}",
                                    companyId,
                                    companyCountryId,
                                    regionId,
                                    zoneId,
                                    storeId,
                                    areaId,
                                    storeZoneId)
                            .with(jwt().authorities(ROLE_LC_ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(storeZoneId.toString()))
                    .andExpect(jsonPath("$.zoneCode").value("Z01"));
        }

        @Test
        @DisplayName("should return 404 for an unknown zone id")
        void getById_UnknownZoneReturns404() throws Exception {
            var areaId = createArea("A01", "Bodega");
            var unknownId = UUID.randomUUID();

            mockMvc.perform(get(
                                    ZONE_BASE_URL + "/{storeZoneId}",
                                    companyId,
                                    companyCountryId,
                                    regionId,
                                    zoneId,
                                    storeId,
                                    areaId,
                                    unknownId)
                            .with(jwt().authorities(ROLE_LC_ADMIN)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Store zone not found with id: " + unknownId));
        }

        @Test
        @DisplayName("should persist an update and keep the unchanged fields")
        void updateZone_PersistsChanges() throws Exception {
            var areaId = createArea("A01", "Bodega");
            createZone(areaId, "Z01", "Pasillo", 1);
            var storeZoneId = zoneIdOf(areaId, "Z01");

            mockMvc.perform(put(
                                    ZONE_BASE_URL + "/{storeZoneId}",
                                    companyId,
                                    companyCountryId,
                                    regionId,
                                    zoneId,
                                    storeId,
                                    areaId,
                                    storeZoneId)
                            .with(jwt().authorities(ROLE_LC_ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new UpdateStoreZoneRequest("Z05", "Pasillo renovado", null, 5))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.zoneCode").value("Z05"))
                    .andExpect(jsonPath("$.zoneName").value("Pasillo renovado"))
                    .andExpect(jsonPath("$.displayOrder").value(5));

            var row = storeZoneRepository.findById(storeZoneId).orElseThrow();
            assertThat(row.getZoneCode()).isEqualTo("Z05");
            assertThat(row.getZoneName()).isEqualTo("Pasillo renovado");
            assertThat(row.getDisplayOrder()).isEqualTo(5);
        }

        @Test
        @DisplayName("should return 409 when updating into an existing zone code")
        void updateZone_DuplicateCodeReturns409() throws Exception {
            var areaId = createArea("A01", "Bodega");
            createZone(areaId, "Z01", "Pasillo", 1);
            createZone(areaId, "Z02", "Estante", 2);
            var secondId = zoneIdOf(areaId, "Z02");

            mockMvc.perform(put(
                                    ZONE_BASE_URL + "/{storeZoneId}",
                                    companyId,
                                    companyCountryId,
                                    regionId,
                                    zoneId,
                                    storeId,
                                    areaId,
                                    secondId)
                            .with(jwt().authorities(ROLE_LC_ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new UpdateStoreZoneRequest("Z01", null, null, null))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("Store zone with code 'Z01' already exists in this area"));
        }
    }

    @Nested
    @DisplayName("flat lookup")
    class FlatLookupTests {

        private static final String FLAT_URL = "/api/store-zones";

        @Test
        @DisplayName("should resolve the persisted chain from a flat zone lookup")
        void flatGet_ResolvesChainFromPersistedData() throws Exception {
            var areaId = createArea("A01", "Bodega");
            createZone(areaId, "Z01", "Pasillo", 1);
            var storeZoneId = zoneIdOf(areaId, "Z01");

            mockMvc.perform(get(FLAT_URL + "/{storeZoneId}", storeZoneId).with(jwt().authorities(ROLE_LC_ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(storeZoneId.toString()))
                    .andExpect(jsonPath("$.storeAreaId").value(areaId.toString()))
                    .andExpect(jsonPath("$.companyStoreId").value(storeId.toString()))
                    .andExpect(jsonPath("$.companyId").value(companyId.toString()))
                    .andExpect(jsonPath("$.companyCountryId").value(companyCountryId.toString()))
                    .andExpect(jsonPath("$.regionId").value(regionId.toString()))
                    .andExpect(jsonPath("$.zoneId").value(zoneId.toString()))
                    .andExpect(jsonPath("$.zoneCode").value("Z01"))
                    .andExpect(jsonPath("$.zoneName").value("Pasillo"));
        }

        @Test
        @DisplayName("should return 404 for an unknown zone id")
        void flatGet_UnknownZoneReturns404() throws Exception {
            var unknownId = UUID.randomUUID();

            mockMvc.perform(get(FLAT_URL + "/{storeZoneId}", unknownId).with(jwt().authorities(ROLE_LC_ADMIN)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Store zone not found with id: " + unknownId));
        }
    }

    @Nested
    @DisplayName("area soft-delete cascade")
    class AreaCascadeTests {

        @Test
        @DisplayName("should disable every enabled zone of an area when the area is soft-deleted")
        void deleteArea_DisablesItsZones() throws Exception {
            var areaId = createArea("A01", "Bodega");
            createZone(areaId, "Z01", "Pasillo", 1);
            createZone(areaId, "Z02", "Estante", 2);

            assertThat(storeZoneRepository.findByStoreAreaIdAndEnabledTrue(areaId))
                    .hasSize(2);

            mockMvc.perform(delete(
                                    AREA_BASE_URL + "/{areaId}",
                                    companyId,
                                    companyCountryId,
                                    regionId,
                                    zoneId,
                                    storeId,
                                    areaId)
                            .with(jwt().authorities(ROLE_LC_ADMIN)))
                    .andExpect(status().isNoContent());

            assertThat(storeAreaRepository.findById(areaId).orElseThrow().getEnabled())
                    .isFalse();
            assertThat(storeZoneRepository.findByStoreAreaIdAndEnabledTrue(areaId))
                    .isEmpty();
            assertThat(storeZoneRepository.findByStoreAreaIdOrderByDisplayOrderAscZoneCodeAsc(areaId))
                    .hasSize(2)
                    .allSatisfy(zone -> assertThat(zone.getEnabled()).isFalse());
        }
    }

    @Nested
    @DisplayName("store soft-delete cascade")
    class StoreCascadeTests {

        @Test
        @DisplayName("should disable the store's areas and zones when the store is soft-deleted")
        void deleteStore_DisablesAreasAndTheirZones() throws Exception {
            var areaId = createArea("A01", "Bodega");
            createZone(areaId, "Z01", "Pasillo", 1);
            createZone(areaId, "Z02", "Estante", 2);

            mockMvc.perform(delete(
                                    STORE_BASE_URL + "/{storeId}",
                                    companyId,
                                    companyCountryId,
                                    regionId,
                                    zoneId,
                                    storeId)
                            .with(jwt().authorities(ROLE_LC_ADMIN)))
                    .andExpect(status().isNoContent());

            assertThat(companyStoreRepository.findById(storeId).orElseThrow().getEnabled())
                    .isFalse();
            assertThat(storeAreaRepository.findByCompanyStoreIdOrderByDisplayOrderAscAreaCodeAsc(storeId))
                    .hasSize(1)
                    .allSatisfy(area -> assertThat(area.getEnabled()).isFalse());
            assertThat(storeAreaRepository.findByCompanyStoreIdAndEnabledTrueOrderByDisplayOrderAscAreaCodeAsc(storeId))
                    .isEmpty();
            assertThat(storeZoneRepository.findByStoreAreaIdOrderByDisplayOrderAscZoneCodeAsc(areaId))
                    .hasSize(2)
                    .allSatisfy(zone -> assertThat(zone.getEnabled()).isFalse());
            assertThat(storeZoneRepository.findByStoreAreaIdAndEnabledTrue(areaId))
                    .isEmpty();
        }

        @Test
        @DisplayName("should not re-enable the store's areas or zones when the store is re-enabled")
        void enableStore_DoesNotCascadeToAreasOrZones() throws Exception {
            var areaId = createArea("A01", "Bodega");
            createZone(areaId, "Z01", "Pasillo", 1);
            createZone(areaId, "Z02", "Estante", 2);

            mockMvc.perform(delete(
                                    STORE_BASE_URL + "/{storeId}",
                                    companyId,
                                    companyCountryId,
                                    regionId,
                                    zoneId,
                                    storeId)
                            .with(jwt().authorities(ROLE_LC_ADMIN)))
                    .andExpect(status().isNoContent());

            mockMvc.perform(patch(STORE_BASE_URL + "/{storeId}", companyId, companyCountryId, regionId, zoneId, storeId)
                            .with(jwt().authorities(ROLE_LC_ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.enabled").value(true));

            assertThat(companyStoreRepository.findById(storeId).orElseThrow().getEnabled())
                    .isTrue();

            // D8: reactivation is explicit and ordered (store -> area -> zone); nothing cascades down.
            mockMvc.perform(get(AREA_BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId)
                            .param("includeDisabled", "true")
                            .with(jwt().authorities(ROLE_LC_ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].areaCode").value("A01"))
                    .andExpect(jsonPath("$[0].enabled").value(false));

            mockMvc.perform(get(ZONE_BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId, areaId)
                            .param("includeDisabled", "true")
                            .with(jwt().authorities(ROLE_LC_ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].enabled").value(false))
                    .andExpect(jsonPath("$[1].enabled").value(false));

            assertThat(storeAreaRepository.findById(areaId).orElseThrow().getEnabled())
                    .isFalse();
            assertThat(storeZoneRepository.findByStoreAreaIdAndEnabledTrue(areaId))
                    .isEmpty();
        }
    }
}
