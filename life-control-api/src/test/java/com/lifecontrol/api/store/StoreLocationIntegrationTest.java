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
import com.lifecontrol.api.store.dto.CreateStoreLocationRequest;
import com.lifecontrol.api.store.dto.CreateStoreZoneRequest;
import com.lifecontrol.api.store.dto.UpdateStoreLocationRequest;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import com.lifecontrol.api.store.repository.StoreAreaRepository;
import com.lifecontrol.api.store.repository.StoreLocationRepository;
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
 * Persistence-level verification of {@code store_locations} against real PostgreSQL, with Flyway
 * enabled so {@code V7__store_locations.sql} builds the table exactly as in production
 * (including {@code UNIQUE(store_zone_id, location_code)} and the soft-delete semantics).
 *
 * <p>Also covers the D2 cascade regression down to the new leaf: disabling a zone, an area or a
 * store must disable the still-enabled locations below it, while re-enabling never cascades.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Store Location Integration Tests")
class StoreLocationIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StoreLocationRepository storeLocationRepository;

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

    private static final String LOCATION_BASE_URL = ZONE_BASE_URL + "/{storeZoneId}/store-locations";

    private static final String STORE_BASE_URL =
            "/api/companies/{companyId}/countries/{companyCountryId}/regions/{regionId}/zones/{zoneId}/stores";

    private static final String FLAT_URL = "/api/store-locations";

    private UUID companyId;
    private UUID companyCountryId;
    private UUID regionId;
    private UUID zoneId;
    private UUID storeId;

    @BeforeEach
    void setUp() {
        // store_locations references store_zones and store_zones references store_areas, none with
        // ON DELETE CASCADE: deleteAll() deletes row by row, so cleanup must go leaf-first or the
        // foreign keys break when another integration class ran earlier in the shared JVM.
        storeLocationRepository.deleteAll();
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
                .findByCompanyKey("STORE-LOCATION-KEY")
                .orElseGet(() -> companyRepository.save(Company.builder()
                        .companyKey("STORE-LOCATION-KEY")
                        .companyName("Store Location Test Company")
                        .rfc("SLOC010101ABC")
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
                        .regionCode("SL")
                        .regionName("Store Location Region")
                        .enabled(true)
                        .build()));

        var zone = companyZoneRepository.findByCompanyRegionIdOrderByZoneNameAsc(region.getId()).stream()
                .findFirst()
                .orElseGet(() -> companyZoneRepository.save(CompanyZone.builder()
                        .companyRegion(region)
                        .zoneCode("SL")
                        .zoneName("Store Location Zone")
                        .enabled(true)
                        .build()));

        var store = companyStoreRepository.findByCompanyZoneId(zone.getId()).stream()
                .findFirst()
                .orElseGet(() -> companyStoreRepository.save(CompanyStore.builder()
                        .companyZone(zone)
                        .storeName("Store Location Test Store")
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

    private UUID createZone(UUID areaId, String zoneCode, String zoneName, Integer displayOrder) throws Exception {
        mockMvc.perform(post(ZONE_BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId, areaId)
                        .with(jwt().authorities(ROLE_LC_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateStoreZoneRequest(zoneCode, zoneName, null, displayOrder))))
                .andExpect(status().isCreated());

        return zoneIdOf(areaId, zoneCode);
    }

    private UUID createLocation(
            UUID areaId, UUID storeZoneId, String locationCode, String locationName, Integer displayOrder)
            throws Exception {
        mockMvc.perform(post(
                                LOCATION_BASE_URL,
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
                                new CreateStoreLocationRequest(locationCode, locationName, null, displayOrder))))
                .andExpect(status().isCreated());

        return locationIdOf(storeZoneId, locationCode);
    }

    private UUID zoneIdOf(UUID areaId, String zoneCode) {
        return storeZoneRepository.findByStoreAreaIdOrderByDisplayOrderAscZoneCodeAsc(areaId).stream()
                .filter(zone -> zoneCode.equals(zone.getZoneCode()))
                .findFirst()
                .orElseThrow()
                .getId();
    }

    private UUID locationIdOf(UUID storeZoneId, String locationCode) {
        return storeLocationRepository.findByStoreZoneIdOrderByDisplayOrderAscLocationCodeAsc(storeZoneId).stream()
                .filter(location -> locationCode.equals(location.getLocationCode()))
                .findFirst()
                .orElseThrow()
                .getId();
    }

    @Nested
    @DisplayName("create + list")
    class CreateAndListTests {

        @Test
        @DisplayName("should persist a location and return it in the active list")
        void createLocation_ThenListActive() throws Exception {
            var areaId = createArea("A01", "Bodega");
            var storeZoneId = createZone(areaId, "Z01", "Pasillo", 1);

            mockMvc.perform(post(
                                    LOCATION_BASE_URL,
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
                                    new CreateStoreLocationRequest("L01", "Estante", null, 1))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.locationCode").value("L01"))
                    .andExpect(jsonPath("$.locationName").value("Estante"))
                    .andExpect(jsonPath("$.storeZoneId").value(storeZoneId.toString()))
                    .andExpect(jsonPath("$.storeAreaId").value(areaId.toString()))
                    .andExpect(jsonPath("$.companyStoreId").value(storeId.toString()))
                    .andExpect(jsonPath("$.enabled").value(true));

            var persisted =
                    storeLocationRepository.findByIdAndStoreZoneId(locationIdOf(storeZoneId, "L01"), storeZoneId);
            assertThat(persisted).isPresent();
            assertThat(persisted.get().getEnabled()).isTrue();

            mockMvc.perform(get(
                                    LOCATION_BASE_URL,
                                    companyId,
                                    companyCountryId,
                                    regionId,
                                    zoneId,
                                    storeId,
                                    areaId,
                                    storeZoneId)
                            .with(jwt().authorities(ROLE_LC_ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].locationCode").value("L01"));
        }

        @Test
        @DisplayName("should order the active list by displayOrder then locationCode")
        void listActive_IsOrderedByDisplayOrderThenLocationCode() throws Exception {
            var areaId = createArea("A01", "Bodega");
            var storeZoneId = createZone(areaId, "Z01", "Pasillo", 1);
            createLocation(areaId, storeZoneId, "L02", "Segunda", 2);
            createLocation(areaId, storeZoneId, "L01", "Primera", 1);

            mockMvc.perform(get(
                                    LOCATION_BASE_URL,
                                    companyId,
                                    companyCountryId,
                                    regionId,
                                    zoneId,
                                    storeId,
                                    areaId,
                                    storeZoneId)
                            .with(jwt().authorities(ROLE_LC_ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].locationCode").value("L01"))
                    .andExpect(jsonPath("$[1].locationCode").value("L02"));
        }

        @Test
        @DisplayName("should reject a duplicate location code inside the same zone with 409")
        void createLocation_DuplicateCodeInZone() throws Exception {
            var areaId = createArea("A01", "Bodega");
            var storeZoneId = createZone(areaId, "Z01", "Pasillo", 1);
            createLocation(areaId, storeZoneId, "L01", "Estante", 1);

            mockMvc.perform(post(
                                    LOCATION_BASE_URL,
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
                                    new CreateStoreLocationRequest("L01", "Otro estante", null, 2))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(
                            jsonPath("$.message").value("Store location with code 'L01' already exists in this zone"));

            assertThat(storeLocationRepository.existsByStoreZoneIdAndLocationCode(storeZoneId, "L01"))
                    .isTrue();
            assertThat(storeLocationRepository.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("should allow the same location code in a different zone of the same area")
        void createLocation_SameCodeInDifferentZoneIsAllowed() throws Exception {
            var areaId = createArea("A01", "Bodega");
            var firstZoneId = createZone(areaId, "Z01", "Pasillo", 1);
            var secondZoneId = createZone(areaId, "Z02", "Estante", 2);
            createLocation(areaId, firstZoneId, "L01", "Estante", 1);

            mockMvc.perform(post(
                                    LOCATION_BASE_URL,
                                    companyId,
                                    companyCountryId,
                                    regionId,
                                    zoneId,
                                    storeId,
                                    areaId,
                                    secondZoneId)
                            .with(jwt().authorities(ROLE_LC_ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new CreateStoreLocationRequest("L01", "Estante", null, 1))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.storeZoneId").value(secondZoneId.toString()));

            assertThat(storeLocationRepository.count()).isEqualTo(2);
        }

        @Test
        @DisplayName("should return 400 when locationCode is blank")
        void createLocation_BlankCodeReturns400() throws Exception {
            var areaId = createArea("A01", "Bodega");
            var storeZoneId = createZone(areaId, "Z01", "Pasillo", 1);

            mockMvc.perform(post(
                                    LOCATION_BASE_URL,
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
                                    new CreateStoreLocationRequest("", "Estante", null, 1))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.locationCode").exists());
        }

        @Test
        @DisplayName("should return 404 when the zone does not belong to the area")
        void createLocation_UnknownZoneReturns404() throws Exception {
            var areaId = createArea("A01", "Bodega");

            mockMvc.perform(post(
                                    LOCATION_BASE_URL,
                                    companyId,
                                    companyCountryId,
                                    regionId,
                                    zoneId,
                                    storeId,
                                    areaId,
                                    UUID.randomUUID())
                            .with(jwt().authorities(ROLE_LC_ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new CreateStoreLocationRequest("L01", "Estante", null, 1))))
                    .andExpect(status().isNotFound());

            assertThat(storeLocationRepository.count()).isZero();
        }

        @Test
        @DisplayName("should return 404 when the area does not belong to the store")
        void createLocation_UnknownAreaReturns404() throws Exception {
            var unknownAreaId = UUID.randomUUID();

            mockMvc.perform(post(
                                    LOCATION_BASE_URL,
                                    companyId,
                                    companyCountryId,
                                    regionId,
                                    zoneId,
                                    storeId,
                                    unknownAreaId,
                                    UUID.randomUUID())
                            .with(jwt().authorities(ROLE_LC_ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new CreateStoreLocationRequest("L01", "Estante", null, 1))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Store area not found with id: " + unknownAreaId));

            assertThat(storeLocationRepository.count()).isZero();
        }

        @Test
        @DisplayName("should return 409 when creating a location under a disabled zone")
        void createLocation_DisabledZoneReturns409() throws Exception {
            var areaId = createArea("A01", "Bodega");
            var storeZoneId = createZone(areaId, "Z01", "Pasillo", 1);

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

            mockMvc.perform(post(
                                    LOCATION_BASE_URL,
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
                                    new CreateStoreLocationRequest("L01", "Estante", null, 1))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.message")
                            .value("Cannot create a store location: store zone with id " + storeZoneId
                                    + " is disabled"));

            assertThat(storeLocationRepository.findByStoreZoneIdOrderByDisplayOrderAscLocationCodeAsc(storeZoneId))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("soft delete / enable")
    class SoftDeleteAndEnableTests {

        @Test
        @DisplayName("should exclude soft-deleted locations from the active list but keep the row")
        void softDelete_ExcludesFromActiveListKeepsRow() throws Exception {
            var areaId = createArea("A01", "Bodega");
            var storeZoneId = createZone(areaId, "Z01", "Pasillo", 1);
            createLocation(areaId, storeZoneId, "L01", "Estante", 1);
            var disabledId = createLocation(areaId, storeZoneId, "L02", "Estante 2", 2);

            mockMvc.perform(delete(
                                    LOCATION_BASE_URL + "/{storeLocationId}",
                                    companyId,
                                    companyCountryId,
                                    regionId,
                                    zoneId,
                                    storeId,
                                    areaId,
                                    storeZoneId,
                                    disabledId)
                            .with(jwt().authorities(ROLE_LC_ADMIN)))
                    .andExpect(status().isNoContent());

            var row = storeLocationRepository.findById(disabledId).orElseThrow();
            assertThat(row.getEnabled()).isFalse();

            mockMvc.perform(get(
                                    LOCATION_BASE_URL,
                                    companyId,
                                    companyCountryId,
                                    regionId,
                                    zoneId,
                                    storeId,
                                    areaId,
                                    storeZoneId)
                            .with(jwt().authorities(ROLE_LC_ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].locationCode").value("L01"));

            mockMvc.perform(get(
                                    LOCATION_BASE_URL,
                                    companyId,
                                    companyCountryId,
                                    regionId,
                                    zoneId,
                                    storeId,
                                    areaId,
                                    storeZoneId)
                            .param("includeDisabled", "true")
                            .with(jwt().authorities(ROLE_LC_ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2));
        }

        @Test
        @DisplayName("should re-enable a soft-deleted location through PATCH /enable")
        void enable_ReactivatesLocation() throws Exception {
            var areaId = createArea("A01", "Bodega");
            var storeZoneId = createZone(areaId, "Z01", "Pasillo", 1);
            var storeLocationId = createLocation(areaId, storeZoneId, "L01", "Estante", 1);

            mockMvc.perform(delete(
                                    LOCATION_BASE_URL + "/{storeLocationId}",
                                    companyId,
                                    companyCountryId,
                                    regionId,
                                    zoneId,
                                    storeId,
                                    areaId,
                                    storeZoneId,
                                    storeLocationId)
                            .with(jwt().authorities(ROLE_LC_ADMIN)))
                    .andExpect(status().isNoContent());

            mockMvc.perform(patch(
                                    LOCATION_BASE_URL + "/{storeLocationId}/enable",
                                    companyId,
                                    companyCountryId,
                                    regionId,
                                    zoneId,
                                    storeId,
                                    areaId,
                                    storeZoneId,
                                    storeLocationId)
                            .with(jwt().authorities(ROLE_LC_ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.enabled").value(true));

            assertThat(storeLocationRepository
                            .findById(storeLocationId)
                            .orElseThrow()
                            .getEnabled())
                    .isTrue();

            mockMvc.perform(get(
                                    LOCATION_BASE_URL,
                                    companyId,
                                    companyCountryId,
                                    regionId,
                                    zoneId,
                                    storeId,
                                    areaId,
                                    storeZoneId)
                            .with(jwt().authorities(ROLE_LC_ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1));
        }

        @Test
        @DisplayName("should return 404 when enabling an unknown location")
        void enable_UnknownLocationReturns404() throws Exception {
            var areaId = createArea("A01", "Bodega");
            var storeZoneId = createZone(areaId, "Z01", "Pasillo", 1);
            var unknownId = UUID.randomUUID();

            mockMvc.perform(patch(
                                    LOCATION_BASE_URL + "/{storeLocationId}/enable",
                                    companyId,
                                    companyCountryId,
                                    regionId,
                                    zoneId,
                                    storeId,
                                    areaId,
                                    storeZoneId,
                                    unknownId)
                            .with(jwt().authorities(ROLE_LC_ADMIN)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Store location not found with id: " + unknownId));
        }
    }

    @Nested
    @DisplayName("read single / update")
    class ReadAndUpdateTests {

        @Test
        @DisplayName("should return 200 with the persisted location and its resolved chain")
        void getById_ReturnsLocationWithChain() throws Exception {
            var areaId = createArea("A01", "Bodega");
            var storeZoneId = createZone(areaId, "Z01", "Pasillo", 1);
            var storeLocationId = createLocation(areaId, storeZoneId, "L01", "Estante", 1);

            mockMvc.perform(get(
                                    LOCATION_BASE_URL + "/{storeLocationId}",
                                    companyId,
                                    companyCountryId,
                                    regionId,
                                    zoneId,
                                    storeId,
                                    areaId,
                                    storeZoneId,
                                    storeLocationId)
                            .with(jwt().authorities(ROLE_LC_ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(storeLocationId.toString()))
                    .andExpect(jsonPath("$.storeZoneId").value(storeZoneId.toString()))
                    .andExpect(jsonPath("$.storeAreaId").value(areaId.toString()))
                    .andExpect(jsonPath("$.companyStoreId").value(storeId.toString()))
                    .andExpect(jsonPath("$.companyId").value(companyId.toString()))
                    .andExpect(jsonPath("$.companyCountryId").value(companyCountryId.toString()))
                    .andExpect(jsonPath("$.regionId").value(regionId.toString()))
                    .andExpect(jsonPath("$.zoneId").value(zoneId.toString()))
                    .andExpect(jsonPath("$.locationCode").value("L01"));
        }

        @Test
        @DisplayName("should return 404 for an unknown location id")
        void getById_UnknownLocationReturns404() throws Exception {
            var areaId = createArea("A01", "Bodega");
            var storeZoneId = createZone(areaId, "Z01", "Pasillo", 1);
            var unknownId = UUID.randomUUID();

            mockMvc.perform(get(
                                    LOCATION_BASE_URL + "/{storeLocationId}",
                                    companyId,
                                    companyCountryId,
                                    regionId,
                                    zoneId,
                                    storeId,
                                    areaId,
                                    storeZoneId,
                                    unknownId)
                            .with(jwt().authorities(ROLE_LC_ADMIN)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Store location not found with id: " + unknownId));
        }

        @Test
        @DisplayName("should persist an update and keep the unchanged fields")
        void updateLocation_PersistsChanges() throws Exception {
            var areaId = createArea("A01", "Bodega");
            var storeZoneId = createZone(areaId, "Z01", "Pasillo", 1);
            var storeLocationId = createLocation(areaId, storeZoneId, "L01", "Estante", 1);

            mockMvc.perform(put(
                                    LOCATION_BASE_URL + "/{storeLocationId}",
                                    companyId,
                                    companyCountryId,
                                    regionId,
                                    zoneId,
                                    storeId,
                                    areaId,
                                    storeZoneId,
                                    storeLocationId)
                            .with(jwt().authorities(ROLE_LC_ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new UpdateStoreLocationRequest("L05", "Estante renovado", null, 5))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.locationCode").value("L05"))
                    .andExpect(jsonPath("$.locationName").value("Estante renovado"))
                    .andExpect(jsonPath("$.displayOrder").value(5));

            var row = storeLocationRepository.findById(storeLocationId).orElseThrow();
            assertThat(row.getLocationCode()).isEqualTo("L05");
            assertThat(row.getLocationName()).isEqualTo("Estante renovado");
            assertThat(row.getDisplayOrder()).isEqualTo(5);
        }

        @Test
        @DisplayName("should return 409 when updating into an existing location code")
        void updateLocation_DuplicateCodeReturns409() throws Exception {
            var areaId = createArea("A01", "Bodega");
            var storeZoneId = createZone(areaId, "Z01", "Pasillo", 1);
            createLocation(areaId, storeZoneId, "L01", "Estante", 1);
            var secondId = createLocation(areaId, storeZoneId, "L02", "Estante 2", 2);

            mockMvc.perform(put(
                                    LOCATION_BASE_URL + "/{storeLocationId}",
                                    companyId,
                                    companyCountryId,
                                    regionId,
                                    zoneId,
                                    storeId,
                                    areaId,
                                    storeZoneId,
                                    secondId)
                            .with(jwt().authorities(ROLE_LC_ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new UpdateStoreLocationRequest("L01", null, null, null))))
                    .andExpect(status().isConflict())
                    .andExpect(
                            jsonPath("$.message").value("Store location with code 'L01' already exists in this zone"));
        }
    }

    @Nested
    @DisplayName("flat lookup")
    class FlatLookupTests {

        @Test
        @DisplayName("should resolve the persisted chain from a flat location lookup")
        void flatGet_ResolvesChainFromPersistedData() throws Exception {
            var areaId = createArea("A01", "Bodega");
            var storeZoneId = createZone(areaId, "Z01", "Pasillo", 1);
            var storeLocationId = createLocation(areaId, storeZoneId, "L01", "Estante", 1);

            mockMvc.perform(get(FLAT_URL + "/{storeLocationId}", storeLocationId)
                            .with(jwt().authorities(ROLE_LC_ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(storeLocationId.toString()))
                    .andExpect(jsonPath("$.storeZoneId").value(storeZoneId.toString()))
                    .andExpect(jsonPath("$.storeAreaId").value(areaId.toString()))
                    .andExpect(jsonPath("$.companyStoreId").value(storeId.toString()))
                    .andExpect(jsonPath("$.companyId").value(companyId.toString()))
                    .andExpect(jsonPath("$.companyCountryId").value(companyCountryId.toString()))
                    .andExpect(jsonPath("$.regionId").value(regionId.toString()))
                    .andExpect(jsonPath("$.zoneId").value(zoneId.toString()))
                    .andExpect(jsonPath("$.locationCode").value("L01"))
                    .andExpect(jsonPath("$.locationName").value("Estante"));
        }

        @Test
        @DisplayName("should return 404 for an unknown location id")
        void flatGet_UnknownLocationReturns404() throws Exception {
            var unknownId = UUID.randomUUID();

            mockMvc.perform(get(FLAT_URL + "/{storeLocationId}", unknownId).with(jwt().authorities(ROLE_LC_ADMIN)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Store location not found with id: " + unknownId));
        }
    }

    @Nested
    @DisplayName("cascade regression (D2)")
    class CascadeTests {

        @Test
        @DisplayName("should disable a zone's locations when the zone is soft-deleted")
        void deleteZone_DisablesItsLocations() throws Exception {
            var areaId = createArea("A01", "Bodega");
            var storeZoneId = createZone(areaId, "Z01", "Pasillo", 1);
            createLocation(areaId, storeZoneId, "L01", "Estante", 1);
            createLocation(areaId, storeZoneId, "L02", "Estante 2", 2);

            assertThat(storeLocationRepository.findByStoreZoneIdAndEnabledTrue(storeZoneId))
                    .hasSize(2);

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

            assertThat(storeZoneRepository.findById(storeZoneId).orElseThrow().getEnabled())
                    .isFalse();
            assertThat(storeLocationRepository.findByStoreZoneIdAndEnabledTrue(storeZoneId))
                    .isEmpty();
            assertThat(storeLocationRepository.findByStoreZoneIdOrderByDisplayOrderAscLocationCodeAsc(storeZoneId))
                    .hasSize(2)
                    .allSatisfy(location -> assertThat(location.getEnabled()).isFalse());
        }

        @Test
        @DisplayName("should disable the area's zones and their locations when the area is soft-deleted")
        void deleteArea_DisablesItsZonesAndTheirLocations() throws Exception {
            var areaId = createArea("A01", "Bodega");
            var firstZoneId = createZone(areaId, "Z01", "Pasillo", 1);
            var secondZoneId = createZone(areaId, "Z02", "Estante", 2);
            createLocation(areaId, firstZoneId, "L01", "Estante", 1);
            createLocation(areaId, secondZoneId, "L01", "Estante", 1);

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
            assertThat(storeLocationRepository.findByStoreZoneIdAndEnabledTrue(firstZoneId))
                    .isEmpty();
            assertThat(storeLocationRepository.findByStoreZoneIdAndEnabledTrue(secondZoneId))
                    .isEmpty();
            assertThat(storeLocationRepository.findAll())
                    .hasSize(2)
                    .allSatisfy(location -> assertThat(location.getEnabled()).isFalse());
        }

        @Test
        @DisplayName("should disable areas, zones and locations when the store is soft-deleted")
        void deleteStore_DisablesAreasZonesAndLocations() throws Exception {
            var areaId = createArea("A01", "Bodega");
            var storeZoneId = createZone(areaId, "Z01", "Pasillo", 1);
            createLocation(areaId, storeZoneId, "L01", "Estante", 1);
            createLocation(areaId, storeZoneId, "L02", "Estante 2", 2);

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
            assertThat(storeAreaRepository.findById(areaId).orElseThrow().getEnabled())
                    .isFalse();
            assertThat(storeZoneRepository.findById(storeZoneId).orElseThrow().getEnabled())
                    .isFalse();
            assertThat(storeLocationRepository.findByStoreZoneIdAndEnabledTrue(storeZoneId))
                    .isEmpty();
            assertThat(storeLocationRepository.findByStoreZoneIdOrderByDisplayOrderAscLocationCodeAsc(storeZoneId))
                    .hasSize(2)
                    .allSatisfy(location -> assertThat(location.getEnabled()).isFalse());
        }

        @Test
        @DisplayName("should not re-enable areas, zones or locations when the store is re-enabled")
        void enableStore_DoesNotCascadeToAreasZonesOrLocations() throws Exception {
            var areaId = createArea("A01", "Bodega");
            var storeZoneId = createZone(areaId, "Z01", "Pasillo", 1);
            var storeLocationId = createLocation(areaId, storeZoneId, "L01", "Estante", 1);

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

            // D8: reactivation is explicit and ordered (store -> area -> zone -> location); nothing
            // cascades down.
            assertThat(storeAreaRepository.findById(areaId).orElseThrow().getEnabled())
                    .isFalse();
            assertThat(storeZoneRepository.findById(storeZoneId).orElseThrow().getEnabled())
                    .isFalse();
            assertThat(storeLocationRepository
                            .findById(storeLocationId)
                            .orElseThrow()
                            .getEnabled())
                    .isFalse();

            mockMvc.perform(get(
                                    LOCATION_BASE_URL,
                                    companyId,
                                    companyCountryId,
                                    regionId,
                                    zoneId,
                                    storeId,
                                    areaId,
                                    storeZoneId)
                            .param("includeDisabled", "true")
                            .with(jwt().authorities(ROLE_LC_ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].locationCode").value("L01"))
                    .andExpect(jsonPath("$[0].enabled").value(false));
        }
    }
}
