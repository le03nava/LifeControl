package com.lifecontrol.api.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.lifecontrol.api.inventory.dto.StoreInventorySettingsRequest;
import com.lifecontrol.api.inventory.model.StoreInventorySettings;
import com.lifecontrol.api.inventory.repository.StoreInventorySettingsRepository;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.model.StoreArea;
import com.lifecontrol.api.store.model.StoreLocation;
import com.lifecontrol.api.store.model.StoreZone;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import com.lifecontrol.api.store.repository.StoreAreaRepository;
import com.lifecontrol.api.store.repository.StoreLocationRepository;
import com.lifecontrol.api.store.repository.StoreZoneRepository;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Persistence-level verification of {@code store_inventory_settings} against real PostgreSQL with
 * Flyway enabled and {@code ddl-auto=validate}, so a green run proves
 * {@code V11__store_inventory_settings.sql} and the entity mapping agree.
 *
 * <p>Covers the not-configured 404, the create-then-update round trip on one row with an advancing
 * {@code @Version}, the "this location belongs to this store" rejection on both the create and the
 * update path (each leaving the stored row untouched), the two typed 404s, the same-location case,
 * the store-scoped enabled-location listing with its documented order, and the optimistic-lock
 * conflict.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Store inventory settings Integration Tests")
class StoreInventorySettingsIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final SimpleGrantedAuthority ROLE_LC_ADMIN = new SimpleGrantedAuthority("ROLE_lc-admin");

    private static final String STORE_BASE_URL =
            "/api/companies/{companyId}/countries/{companyCountryId}/regions/{regionId}/zones/{zoneId}/stores/{storeId}";
    private static final String SETTINGS_URL = STORE_BASE_URL + "/inventory-settings";
    private static final String LOCATIONS_URL = STORE_BASE_URL + "/store-locations";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StoreInventorySettingsRepository storeInventorySettingsRepository;

    @Autowired
    private StoreAreaRepository storeAreaRepository;

    @Autowired
    private StoreZoneRepository storeZoneRepository;

    @Autowired
    private StoreLocationRepository storeLocationRepository;

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
    private PlatformTransactionManager transactionManager;

    private UUID companyId;
    private UUID companyCountryId;
    private UUID regionId;
    private UUID zoneId;
    private CompanyZone companyZone;
    private CompanyStore storeA;
    private CompanyStore storeB;
    private UUID locationA1;
    private UUID locationA2;
    private UUID locationA3;
    private UUID disabledLocationA;
    private UUID locationB1;

    @BeforeEach
    void setUp() {
        // store_inventory_settings references company_stores and store_locations with no cascade, so
        // leaked rows would break the deleteAll() calls of later integration classes in this shared
        // JVM. Clean our own table before seeding and after every method.
        storeInventorySettingsRepository.deleteAll();

        seedCompanyHierarchy();
        seedStoreLocations();
    }

    @AfterEach
    void tearDown() {
        storeInventorySettingsRepository.deleteAll();
    }

    /** Find-or-create the company &rarr; country &rarr; region &rarr; zone chain. */
    private void seedCompanyHierarchy() {
        var country = countryRepository.findByCountryCode("MX").orElseThrow();

        var company = companyRepository
                .findByCompanyKey("STORE-INV-SETTINGS-KEY")
                .orElseGet(() -> companyRepository.save(Company.builder()
                        .companyKey("STORE-INV-SETTINGS-KEY")
                        .companyName("Store Inventory Settings Test Company")
                        .rfc("SISG010101ABC")
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
                        .regionCode("SIS")
                        .regionName("Store Inventory Settings Region")
                        .enabled(true)
                        .build()));

        companyZone = companyZoneRepository.findByCompanyRegionIdOrderByZoneNameAsc(region.getId()).stream()
                .findFirst()
                .orElseGet(() -> companyZoneRepository.save(CompanyZone.builder()
                        .companyRegion(region)
                        .zoneCode("SIS")
                        .zoneName("Store Inventory Settings Zone")
                        .enabled(true)
                        .build()));

        companyId = company.getId();
        companyCountryId = companyCountry.getId();
        regionId = region.getId();
        zoneId = companyZone.getId();
    }

    /** Two stores of the same company zone, each with its own area/zone/location tree. */
    private void seedStoreLocations() {
        storeA = findOrCreateStore("Inventory Settings Store A");
        storeB = findOrCreateStore("Inventory Settings Store B");

        var areaA1 = findOrCreateArea(storeA, "ISA1", "Warehouse", 1);
        var zoneA1 = findOrCreateZone(areaA1, "ISZ1", "Aisle", 1);
        locationA1 =
                findOrCreateLocation(zoneA1, "ISL1", "Receiving shelf", 1, true).getId();
        locationA2 =
                findOrCreateLocation(zoneA1, "ISL2", "Sales floor", 2, true).getId();
        disabledLocationA =
                findOrCreateLocation(zoneA1, "ISL3", "Decommissioned", 3, false).getId();

        var areaA2 = findOrCreateArea(storeA, "ISA2", "Backroom", 2);
        var zoneA2 = findOrCreateZone(areaA2, "ISZ2", "Overflow", 1);
        locationA3 =
                findOrCreateLocation(zoneA2, "ISL1", "Overflow shelf", 1, true).getId();

        var areaB1 = findOrCreateArea(storeB, "ISB1", "Warehouse", 1);
        var zoneB1 = findOrCreateZone(areaB1, "ISZB1", "Aisle", 1);
        locationB1 =
                findOrCreateLocation(zoneB1, "ISLB1", "B receiving", 1, true).getId();
    }

    private CompanyStore findOrCreateStore(String storeName) {
        return companyStoreRepository.findByCompanyZoneId(companyZone.getId()).stream()
                .filter(store -> storeName.equals(store.getStoreName()))
                .findFirst()
                .orElseGet(() -> companyStoreRepository.save(CompanyStore.builder()
                        .companyZone(companyZone)
                        .storeName(storeName)
                        .enabled(true)
                        .build()));
    }

    private StoreArea findOrCreateArea(CompanyStore store, String code, String name, int displayOrder) {
        return storeAreaRepository.findByCompanyStoreIdOrderByDisplayOrderAscAreaCodeAsc(store.getId()).stream()
                .filter(area -> code.equals(area.getAreaCode()))
                .findFirst()
                .orElseGet(() -> storeAreaRepository.save(StoreArea.builder()
                        .companyStore(store)
                        .areaCode(code)
                        .areaName(name)
                        .displayOrder(displayOrder)
                        .enabled(true)
                        .build()));
    }

    private StoreZone findOrCreateZone(StoreArea area, String code, String name, int displayOrder) {
        return storeZoneRepository.findByStoreAreaIdOrderByDisplayOrderAscZoneCodeAsc(area.getId()).stream()
                .filter(zone -> code.equals(zone.getZoneCode()))
                .findFirst()
                .orElseGet(() -> storeZoneRepository.save(StoreZone.builder()
                        .storeArea(area)
                        .zoneCode(code)
                        .zoneName(name)
                        .displayOrder(displayOrder)
                        .enabled(true)
                        .build()));
    }

    private StoreLocation findOrCreateLocation(
            StoreZone zone, String code, String name, int displayOrder, boolean enabled) {
        return storeLocationRepository.findByStoreZoneIdOrderByDisplayOrderAscLocationCodeAsc(zone.getId()).stream()
                .filter(location -> code.equals(location.getLocationCode()))
                .findFirst()
                .orElseGet(() -> storeLocationRepository.save(StoreLocation.builder()
                        .storeZone(zone)
                        .locationCode(code)
                        .locationName(name)
                        .displayOrder(displayOrder)
                        .enabled(enabled)
                        .build()));
    }

    private ResultActions putSettings(UUID storeId, StoreInventorySettingsRequest request) throws Exception {
        return mockMvc.perform(put(SETTINGS_URL, companyId, companyCountryId, regionId, zoneId, storeId)
                .with(jwt().authorities(ROLE_LC_ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
    }

    private ResultActions getSettings(UUID storeId) throws Exception {
        return mockMvc.perform(get(SETTINGS_URL, companyId, companyCountryId, regionId, zoneId, storeId)
                .with(jwt().authorities(ROLE_LC_ADMIN)));
    }

    private ResultActions getStoreLocations(UUID storeId) throws Exception {
        return mockMvc.perform(get(LOCATIONS_URL, companyId, companyCountryId, regionId, zoneId, storeId)
                .with(jwt().authorities(ROLE_LC_ADMIN)));
    }

    @Nested
    @DisplayName("round trip")
    class RoundTripTests {

        @Test
        @DisplayName("should create, read back and update the settings in place, advancing the version")
        void createReadAndUpdateOnOneRow() throws Exception {
            getSettings(storeA.getId()).andExpect(status().isNotFound());

            putSettings(storeA.getId(), new StoreInventorySettingsRequest(locationA1, locationA2))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.companyStoreId").value(storeA.getId().toString()))
                    .andExpect(jsonPath("$.receivingLocationId").value(locationA1.toString()))
                    .andExpect(jsonPath("$.salesLocationId").value(locationA2.toString()));

            getSettings(storeA.getId())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.receivingLocationId").value(locationA1.toString()))
                    .andExpect(jsonPath("$.salesLocationId").value(locationA2.toString()));

            var versionAfterCreate = storeInventorySettingsRepository
                    .findById(storeA.getId())
                    .orElseThrow()
                    .getVersion();

            putSettings(storeA.getId(), new StoreInventorySettingsRequest(locationA3, locationA1))
                    .andExpect(status().isOk());

            // One row, not two, and the optimistic-locking version advanced.
            assertThat(storeInventorySettingsRepository.count()).isEqualTo(1);
            var afterUpdate =
                    storeInventorySettingsRepository.findById(storeA.getId()).orElseThrow();
            assertThat(afterUpdate.getReceivingLocationId()).isEqualTo(locationA3);
            assertThat(afterUpdate.getSalesLocationId()).isEqualTo(locationA1);
            assertThat(afterUpdate.getVersion()).isGreaterThan(versionAfterCreate);
        }
    }

    @Nested
    @DisplayName("ownership validation")
    class OwnershipValidationTests {

        @Test
        @DisplayName("should reject a location of another store and write nothing")
        void rejectsForeignStoreLocation() throws Exception {
            putSettings(storeA.getId(), new StoreInventorySettingsRequest(locationB1, locationA1))
                    .andExpect(status().isNotFound());
            putSettings(storeA.getId(), new StoreInventorySettingsRequest(locationA1, locationB1))
                    .andExpect(status().isNotFound());

            assertThat(storeInventorySettingsRepository.count()).isZero();
        }

        @Test
        @DisplayName("should leave the stored pair and its version unchanged when an update is rejected")
        void rejectedUpdateChangesNothing() throws Exception {
            putSettings(storeA.getId(), new StoreInventorySettingsRequest(locationA1, locationA2))
                    .andExpect(status().isOk());
            var versionBefore = storeInventorySettingsRepository
                    .findById(storeA.getId())
                    .orElseThrow()
                    .getVersion();

            // Rejected on the receiving location, then on the sales location: both orders must 404.
            putSettings(storeA.getId(), new StoreInventorySettingsRequest(locationB1, locationA1))
                    .andExpect(status().isNotFound());
            putSettings(storeA.getId(), new StoreInventorySettingsRequest(locationA1, locationB1))
                    .andExpect(status().isNotFound());

            var after =
                    storeInventorySettingsRepository.findById(storeA.getId()).orElseThrow();
            assertThat(after.getReceivingLocationId()).isEqualTo(locationA1);
            assertThat(after.getSalesLocationId()).isEqualTo(locationA2);
            assertThat(after.getVersion()).isEqualTo(versionBefore);
            assertThat(storeInventorySettingsRepository.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("should report an unknown location and an unknown store as 404")
        void rejectsUnknownLocationAndStore() throws Exception {
            var unknown = UUID.randomUUID();

            putSettings(storeA.getId(), new StoreInventorySettingsRequest(unknown, locationA1))
                    .andExpect(status().isNotFound());
            putSettings(unknown, new StoreInventorySettingsRequest(locationA1, locationA2))
                    .andExpect(status().isNotFound());
            getSettings(unknown).andExpect(status().isNotFound());

            assertThat(storeInventorySettingsRepository.count()).isZero();
        }

        @Test
        @DisplayName("should accept the same location for receiving and sales")
        void acceptsSameLocationForBoth() throws Exception {
            putSettings(storeA.getId(), new StoreInventorySettingsRequest(locationA1, locationA1))
                    .andExpect(status().isOk());

            var settings =
                    storeInventorySettingsRepository.findById(storeA.getId()).orElseThrow();
            assertThat(settings.getReceivingLocationId()).isEqualTo(locationA1);
            assertThat(settings.getSalesLocationId()).isEqualTo(locationA1);
        }
    }

    @Nested
    @DisplayName("store-scoped location listing")
    class StoreLocationListingTests {

        @Test
        @DisplayName("should return exactly the store's enabled locations in area, zone, location order")
        void returnsOnlyThisStoresEnabledLocationsInOrder() throws Exception {
            getStoreLocations(storeA.getId())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(3))
                    .andExpect(jsonPath("$[0].id").value(locationA1.toString()))
                    .andExpect(jsonPath("$[0].locationCode").value("ISL1"))
                    .andExpect(jsonPath("$[0].zoneCode").value("ISZ1"))
                    .andExpect(jsonPath("$[0].areaCode").value("ISA1"))
                    .andExpect(jsonPath("$[1].id").value(locationA2.toString()))
                    .andExpect(jsonPath("$[1].areaCode").value("ISA1"))
                    .andExpect(jsonPath("$[2].id").value(locationA3.toString()))
                    .andExpect(jsonPath("$[2].zoneCode").value("ISZ2"))
                    .andExpect(jsonPath("$[2].areaCode").value("ISA2"));

            // Another store's locations and the disabled one are never offered.
            getStoreLocations(storeB.getId())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].id").value(locationB1.toString()));
            getStoreLocations(storeA.getId())
                    .andExpect(
                            jsonPath("$[?(@.id=='" + disabledLocationA + "')]").doesNotExist());
        }
    }

    @Nested
    @DisplayName("optimistic locking")
    class OptimisticLockingTests {

        @Test
        @DisplayName("should answer 409 when a PUT asserts a stale version")
        void staleVersionPutAnswers409() throws Exception {
            // Create the settings without a precondition: today's behaviour, no version asserted.
            var createBody = putSettings(storeA.getId(), new StoreInventorySettingsRequest(locationA1, locationA2))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            long staleVersion = storeInventorySettingsRepository
                    .findById(storeA.getId())
                    .orElseThrow()
                    .getVersion();
            // Both write paths must answer with the persisted version, never a pre-flush one.
            assertThat(objectMapper.readTree(createBody).get("version").asLong())
                    .isEqualTo(staleVersion);

            // Advance the row's version with another unconditional update, and pin that the response
            // carries the version the database actually assigned. Without this the mapper's
            // propagation would only ever be observed at the zero a freshly-built entity carries,
            // which a hard-coded 0L in the mapper would satisfy.
            var updateBody = putSettings(storeA.getId(), new StoreInventorySettingsRequest(locationA2, locationA1))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            long currentVersion = storeInventorySettingsRepository
                    .findById(storeA.getId())
                    .orElseThrow()
                    .getVersion();
            assertThat(currentVersion).isGreaterThan(staleVersion);
            assertThat(objectMapper.readTree(updateBody).get("version").asLong())
                    .isEqualTo(currentVersion);

            // Asserting the stale version must be rejected at the HTTP level: 409, not 200, not 500.
            putSettings(storeA.getId(), new StoreInventorySettingsRequest(locationA1, locationA2, staleVersion))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409));

            // The rejected request changed nothing.
            var stored =
                    storeInventorySettingsRepository.findById(storeA.getId()).orElseThrow();
            assertThat(stored.getReceivingLocationId()).isEqualTo(locationA2);
            assertThat(stored.getSalesLocationId()).isEqualTo(locationA1);
        }

        @Test
        @DisplayName("should fail a stale version commit instead of silently winning the write")
        void staleUpdateFailsInsteadOfSilentlyWinning() throws Exception {
            storeInventorySettingsRepository.saveAndFlush(StoreInventorySettings.builder()
                    .companyStoreId(storeA.getId())
                    .receivingLocationId(locationA1)
                    .salesLocationId(locationA1)
                    .build());

            var template = new TransactionTemplate(transactionManager);
            var threadAHasLoaded = new CountDownLatch(1);
            var threadBMayCommit = new CountDownLatch(1);
            var executor = Executors.newFixedThreadPool(2);

            try {
                // Thread A loads the row, mutates it, and holds its transaction open until B commits.
                var firstTransaction = executor.submit(() -> template.execute(status -> {
                    var loaded = storeInventorySettingsRepository
                            .findById(storeA.getId())
                            .orElseThrow();
                    loaded.setSalesLocationId(locationA2);
                    threadAHasLoaded.countDown();
                    awaitLatch(threadBMayCommit, "thread B to commit");
                    return null;
                }));
                assertThat(threadAHasLoaded.await(10, TimeUnit.SECONDS))
                        .as("thread A loads the settings within the timeout")
                        .isTrue();

                // Thread B loads the same row in its own persistence context, mutates and commits first.
                var secondTransaction = executor.submit(() -> template.execute(status -> {
                    var loaded = storeInventorySettingsRepository
                            .findById(storeA.getId())
                            .orElseThrow();
                    loaded.setSalesLocationId(locationA3);
                    storeInventorySettingsRepository.saveAndFlush(loaded);
                    return null;
                }));
                secondTransaction.get(15, TimeUnit.SECONDS);
                threadBMayCommit.countDown();

                // Thread A now commits with a stale version: it must fail rather than overwrite B.
                var failure = catchThrowable(() -> firstTransaction.get(15, TimeUnit.SECONDS));
                assertThat(failure).as("thread A's commit fails loudly").isInstanceOf(ExecutionException.class);
                assertThat(causeChainOf(failure))
                        .as("the stale commit surfaces as an optimistic locking failure")
                        .anySatisfy(throwable ->
                                assertThat(throwable).isInstanceOf(ObjectOptimisticLockingFailureException.class));
            } finally {
                threadBMayCommit.countDown();
                executor.shutdownNow();
            }

            // No lost update: the row keeps the value committed by the winning transaction.
            assertThat(storeInventorySettingsRepository
                            .findById(storeA.getId())
                            .orElseThrow()
                            .getSalesLocationId())
                    .isEqualTo(locationA3);
        }
    }

    private static void awaitLatch(CountDownLatch latch, String what) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for " + what);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for " + what, e);
        }
    }

    private static List<Throwable> causeChainOf(Throwable throwable) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            chain.add(current);
        }
        return chain;
    }
}
