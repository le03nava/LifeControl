package com.lifecontrol.api.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.company.repository.CompanyCountryRepository;
import com.lifecontrol.api.company.repository.CompanyRegionRepository;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.company.repository.CompanyZoneRepository;
import com.lifecontrol.api.inventory.dto.StoreInventorySettingsRequest;
import com.lifecontrol.api.inventory.exception.StoreInventorySettingsNotFoundException;
import com.lifecontrol.api.inventory.exception.StoreLocationNotInStoreException;
import com.lifecontrol.api.inventory.model.StoreInventorySettings;
import com.lifecontrol.api.inventory.repository.StoreInventorySettingsRepository;
import com.lifecontrol.api.inventory.service.StoreInventorySettingsService;
import com.lifecontrol.api.store.exception.CompanyStoreNotFoundException;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.model.StoreArea;
import com.lifecontrol.api.store.model.StoreLocation;
import com.lifecontrol.api.store.model.StoreZone;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import com.lifecontrol.api.store.repository.StoreAreaRepository;
import com.lifecontrol.api.store.repository.StoreLocationRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Unit coverage of {@link StoreInventorySettingsService}: the not-configured 404, the create/update
 * choice, the ownership rejection and the store-scoped location listing. Real
 * persistence, the one-row property and the {@code @Version} conflict are covered by
 * {@code StoreInventorySettingsIntegrationTest} on PostgreSQL.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("StoreInventorySettingsService Tests")
class StoreInventorySettingsServiceTest {

    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID COMPANY_COUNTRY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
    private static final UUID REGION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
    private static final UUID ZONE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c4");
    private static final UUID STORE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c5");
    private static final UUID AREA_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c6");
    private static final UUID STORE_ZONE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c7");
    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c8");
    private static final UUID OTHER_LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c9");
    private static final UUID UNKNOWN_STORE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000ca");

    @Mock
    private StoreInventorySettingsRepository storeInventorySettingsRepository;

    @Mock
    private StoreLocationRepository storeLocationRepository;

    @Mock
    private StoreAreaRepository storeAreaRepository;

    @Mock
    private CompanyStoreRepository companyStoreRepository;

    @Mock
    private CompanyZoneRepository companyZoneRepository;

    @Mock
    private CompanyRegionRepository companyRegionRepository;

    @Mock
    private CompanyCountryRepository companyCountryRepository;

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private CurrentUserContext currentUserContext;

    @Captor
    private ArgumentCaptor<StoreInventorySettings> settingsCaptor;

    private StoreInventorySettingsService service;
    private CompanyStore store;

    @BeforeEach
    void setUp() {
        service = new StoreInventorySettingsService(
                storeInventorySettingsRepository,
                storeLocationRepository,
                storeAreaRepository,
                companyStoreRepository,
                companyZoneRepository,
                companyRegionRepository,
                companyCountryRepository,
                companyRepository,
                currentUserContext);

        store = CompanyStore.builder()
                .id(STORE_ID)
                .storeName("Store")
                .enabled(true)
                .build();
        stubStoreResolution();
    }

    /** Stubs the resolved company -> country -> region -> zone -> store path. */
    private void stubStoreResolution() {
        var company = Company.builder()
                .id(COMPANY_ID)
                .companyKey("KEY")
                .companyName("Company")
                .rfc("KEY010101ABC")
                .enabled(true)
                .build();
        var companyCountry =
                CompanyCountry.builder().id(COMPANY_COUNTRY_ID).company(company).build();
        var region = CompanyRegion.builder()
                .id(REGION_ID)
                .companyCountry(companyCountry)
                .regionCode("R")
                .regionName("Region")
                .enabled(true)
                .build();
        var zone = CompanyZone.builder()
                .id(ZONE_ID)
                .companyRegion(region)
                .zoneCode("Z")
                .zoneName("Zone")
                .enabled(true)
                .build();

        lenient().when(companyRepository.findById(COMPANY_ID)).thenReturn(Optional.of(company));
        lenient()
                .when(companyCountryRepository.findByCompanyIdAndId(COMPANY_ID, COMPANY_COUNTRY_ID))
                .thenReturn(Optional.of(companyCountry));
        lenient()
                .when(companyRegionRepository.findByIdAndCompanyCountryId(REGION_ID, COMPANY_COUNTRY_ID))
                .thenReturn(Optional.of(region));
        lenient()
                .when(companyZoneRepository.findByIdAndCompanyRegionId(ZONE_ID, REGION_ID))
                .thenReturn(Optional.of(zone));
        lenient()
                .when(companyStoreRepository.findByIdAndCompanyZoneId(STORE_ID, ZONE_ID))
                .thenReturn(Optional.of(store));
    }

    /** A location of {@link #store}, with its area and zone, and the matching ownership stub. */
    private StoreLocation stubOwnedLocation(UUID locationId) {
        var area = StoreArea.builder()
                .id(AREA_ID)
                .companyStore(store)
                .areaCode("A1")
                .areaName("Warehouse")
                .displayOrder(1)
                .enabled(true)
                .build();
        var zone = StoreZone.builder()
                .id(STORE_ZONE_ID)
                .storeArea(area)
                .zoneCode("Z1")
                .zoneName("Aisle")
                .displayOrder(1)
                .enabled(true)
                .build();
        var location = StoreLocation.builder()
                .id(locationId)
                .storeZone(zone)
                .locationCode("L1")
                .locationName("Receiving shelf")
                .enabled(true)
                .build();

        lenient().when(storeLocationRepository.findById(locationId)).thenReturn(Optional.of(location));
        lenient()
                .when(storeAreaRepository.findByIdAndCompanyStoreId(AREA_ID, STORE_ID))
                .thenReturn(Optional.of(area));
        return location;
    }

    private StoreInventorySettingsRequest request(UUID receivingLocationId, UUID salesLocationId) {
        return new StoreInventorySettingsRequest(receivingLocationId, salesLocationId);
    }

    @Nested
    @DisplayName("getSettings")
    class GetSettingsTests {

        @Test
        @DisplayName("should return the settings when the store is configured")
        void returnsSettingsWhenConfigured() {
            var settings = StoreInventorySettings.builder()
                    .companyStoreId(STORE_ID)
                    .receivingLocationId(LOCATION_ID)
                    .salesLocationId(OTHER_LOCATION_ID)
                    .build();
            when(storeInventorySettingsRepository.findById(STORE_ID)).thenReturn(Optional.of(settings));

            var response = service.getSettings(COMPANY_ID, COMPANY_COUNTRY_ID, REGION_ID, ZONE_ID, STORE_ID);

            assertThat(response.companyStoreId()).isEqualTo(STORE_ID);
            assertThat(response.receivingLocationId()).isEqualTo(LOCATION_ID);
            assertThat(response.salesLocationId()).isEqualTo(OTHER_LOCATION_ID);
            verify(currentUserContext)
                    .verifyCompanyStoreAccess(COMPANY_ID, COMPANY_COUNTRY_ID, REGION_ID, ZONE_ID, STORE_ID);
        }

        @Test
        @DisplayName("should throw the typed 404 when the store has never been configured")
        void throwsNotFoundWhenNeverConfigured() {
            when(storeInventorySettingsRepository.findById(STORE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getSettings(COMPANY_ID, COMPANY_COUNTRY_ID, REGION_ID, ZONE_ID, STORE_ID))
                    .isInstanceOf(StoreInventorySettingsNotFoundException.class)
                    .hasMessage("Store inventory settings not found for store id: " + STORE_ID);
        }
    }

    @Nested
    @DisplayName("upsertSettings")
    class UpsertSettingsTests {

        @Test
        @DisplayName("should create the settings when the store has none")
        void createsWhenAbsent() {
            stubOwnedLocation(LOCATION_ID);
            stubOwnedLocation(OTHER_LOCATION_ID);
            when(storeInventorySettingsRepository.findById(STORE_ID)).thenReturn(Optional.empty());
            when(storeInventorySettingsRepository.save(any(StoreInventorySettings.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            var response = service.upsertSettings(
                    COMPANY_ID,
                    COMPANY_COUNTRY_ID,
                    REGION_ID,
                    ZONE_ID,
                    STORE_ID,
                    request(LOCATION_ID, OTHER_LOCATION_ID));

            verify(storeInventorySettingsRepository).save(settingsCaptor.capture());
            assertThat(settingsCaptor.getValue().getCompanyStoreId()).isEqualTo(STORE_ID);
            assertThat(response.receivingLocationId()).isEqualTo(LOCATION_ID);
            assertThat(response.salesLocationId()).isEqualTo(OTHER_LOCATION_ID);
        }

        @Test
        @DisplayName("should update the existing settings in place and keep one row")
        void updatesWhenPresent() {
            stubOwnedLocation(LOCATION_ID);
            stubOwnedLocation(OTHER_LOCATION_ID);
            var existing = StoreInventorySettings.builder()
                    .companyStoreId(STORE_ID)
                    .receivingLocationId(LOCATION_ID)
                    .salesLocationId(LOCATION_ID)
                    .build();
            when(storeInventorySettingsRepository.findById(STORE_ID)).thenReturn(Optional.of(existing));
            when(storeInventorySettingsRepository.save(any(StoreInventorySettings.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            var response = service.upsertSettings(
                    COMPANY_ID,
                    COMPANY_COUNTRY_ID,
                    REGION_ID,
                    ZONE_ID,
                    STORE_ID,
                    request(LOCATION_ID, OTHER_LOCATION_ID));

            assertThat(response.companyStoreId()).isEqualTo(STORE_ID);
            assertThat(response.salesLocationId()).isEqualTo(OTHER_LOCATION_ID);
            // The update reuses the loaded instance instead of inserting a second row.
            verify(storeInventorySettingsRepository).save(existing);
            assertThat(existing.getSalesLocationId()).isEqualTo(OTHER_LOCATION_ID);
        }

        @Test
        @DisplayName("should reject a location of another store and write nothing")
        void rejectsForeignStoreLocation() {
            var location = stubOwnedLocation(LOCATION_ID);
            // The location exists, but its area does not resolve for THIS store.
            when(storeAreaRepository.findByIdAndCompanyStoreId(AREA_ID, STORE_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.upsertSettings(
                            COMPANY_ID,
                            COMPANY_COUNTRY_ID,
                            REGION_ID,
                            ZONE_ID,
                            STORE_ID,
                            request(LOCATION_ID, location.getId())))
                    .isInstanceOf(StoreLocationNotInStoreException.class)
                    .hasMessage("Store location not found with id: " + LOCATION_ID + " in the requested store");

            verify(storeInventorySettingsRepository, never()).save(any());
        }

        @Test
        @DisplayName("should reject an unknown location id")
        void rejectsUnknownLocation() {
            when(storeLocationRepository.findById(LOCATION_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.upsertSettings(
                            COMPANY_ID,
                            COMPANY_COUNTRY_ID,
                            REGION_ID,
                            ZONE_ID,
                            STORE_ID,
                            request(LOCATION_ID, OTHER_LOCATION_ID)))
                    .isInstanceOf(StoreLocationNotInStoreException.class);

            verify(storeInventorySettingsRepository, never()).save(any());
        }

        @Test
        @DisplayName("should reject an unknown store")
        void rejectsUnknownStore() {
            when(companyStoreRepository.findByIdAndCompanyZoneId(UNKNOWN_STORE_ID, ZONE_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.upsertSettings(
                            COMPANY_ID,
                            COMPANY_COUNTRY_ID,
                            REGION_ID,
                            ZONE_ID,
                            UNKNOWN_STORE_ID,
                            request(LOCATION_ID, OTHER_LOCATION_ID)))
                    .isInstanceOf(CompanyStoreNotFoundException.class);

            verifyNoInteractions(storeLocationRepository);
            verify(storeInventorySettingsRepository, never()).save(any());
        }

        @Test
        @DisplayName("should accept the same location for receiving and sales")
        void acceptsSameLocationForBoth() {
            stubOwnedLocation(LOCATION_ID);
            when(storeInventorySettingsRepository.findById(STORE_ID)).thenReturn(Optional.empty());
            when(storeInventorySettingsRepository.save(any(StoreInventorySettings.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            var response = service.upsertSettings(
                    COMPANY_ID, COMPANY_COUNTRY_ID, REGION_ID, ZONE_ID, STORE_ID, request(LOCATION_ID, LOCATION_ID));

            assertThat(response.receivingLocationId()).isEqualTo(LOCATION_ID);
            assertThat(response.salesLocationId()).isEqualTo(LOCATION_ID);
        }
    }

    @Nested
    @DisplayName("listStoreLocations")
    class ListStoreLocationsTests {

        @Test
        @DisplayName("should return the store's enabled locations with their zone and area")
        void returnsStoreLocations() {
            var location = stubOwnedLocation(LOCATION_ID);
            when(storeLocationRepository.findEnabledByCompanyStoreId(STORE_ID)).thenReturn(List.of(location));

            var response = service.listStoreLocations(COMPANY_ID, COMPANY_COUNTRY_ID, REGION_ID, ZONE_ID, STORE_ID);

            assertThat(response).hasSize(1);
            var summary = response.getFirst();
            assertThat(summary.id()).isEqualTo(LOCATION_ID);
            assertThat(summary.locationCode()).isEqualTo("L1");
            assertThat(summary.locationName()).isEqualTo("Receiving shelf");
            assertThat(summary.storeZoneId()).isEqualTo(STORE_ZONE_ID);
            assertThat(summary.zoneCode()).isEqualTo("Z1");
            assertThat(summary.storeAreaId()).isEqualTo(AREA_ID);
            assertThat(summary.areaCode()).isEqualTo("A1");
        }

        @Test
        @DisplayName("should return an empty list when the store has no locations")
        void returnsEmptyList() {
            when(storeLocationRepository.findEnabledByCompanyStoreId(STORE_ID)).thenReturn(List.of());

            assertThat(service.listStoreLocations(COMPANY_ID, COMPANY_COUNTRY_ID, REGION_ID, ZONE_ID, STORE_ID))
                    .isEmpty();
        }
    }
}
