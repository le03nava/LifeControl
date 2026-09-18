package com.lifecontrol.api.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.company.repository.CompanyCountryRepository;
import com.lifecontrol.api.company.repository.CompanyRegionRepository;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.company.repository.CompanyZoneRepository;
import com.lifecontrol.api.country.model.Country;
import com.lifecontrol.api.store.dto.CreateStoreLocationRequest;
import com.lifecontrol.api.store.dto.StoreLocationResponse;
import com.lifecontrol.api.store.dto.UpdateStoreLocationRequest;
import com.lifecontrol.api.store.exception.CompanyStoreNotFoundException;
import com.lifecontrol.api.store.exception.DisabledParentException;
import com.lifecontrol.api.store.exception.DuplicateStoreLocationException;
import com.lifecontrol.api.store.exception.StoreAreaNotFoundException;
import com.lifecontrol.api.store.exception.StoreLocationNotFoundException;
import com.lifecontrol.api.store.exception.StoreZoneNotFoundException;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.model.StoreArea;
import com.lifecontrol.api.store.model.StoreLocation;
import com.lifecontrol.api.store.model.StoreZone;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import com.lifecontrol.api.store.repository.StoreAreaRepository;
import com.lifecontrol.api.store.repository.StoreLocationRepository;
import com.lifecontrol.api.store.repository.StoreZoneRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;

@ExtendWith(MockitoExtension.class)
@DisplayName("StoreLocationService Tests")
class StoreLocationServiceTest {

    @Mock
    private StoreLocationRepository storeLocationRepository;

    @Mock
    private StoreZoneRepository storeZoneRepository;

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

    @InjectMocks
    private StoreLocationService storeLocationService;

    private UUID companyId;
    private UUID companyCountryId;
    private UUID regionId;
    private UUID zoneId;
    private UUID storeId;
    private UUID areaId;
    private UUID storeZoneId;
    private UUID storeLocationId;
    private Company testCompany;
    private CompanyCountry testCompanyCountry;
    private CompanyRegion testRegion;
    private CompanyZone testZone;
    private CompanyStore testStore;
    private StoreArea testArea;
    private StoreZone testStoreZone;
    private StoreLocation testStoreLocation;
    private CreateStoreLocationRequest createRequest;
    private UpdateStoreLocationRequest updateRequest;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        companyCountryId = UUID.randomUUID();
        regionId = UUID.randomUUID();
        zoneId = UUID.randomUUID();
        storeId = UUID.randomUUID();
        areaId = UUID.randomUUID();
        storeZoneId = UUID.randomUUID();
        storeLocationId = UUID.randomUUID();

        testCompany = Company.builder()
                .id(companyId)
                .companyKey("1")
                .companyName("Test Company")
                .rfc("XAXX010101000")
                .enabled(true)
                .build();

        var testCountry = Country.builder()
                .id(UUID.randomUUID())
                .countryCode("MX")
                .countryName("México")
                .enabled(true)
                .build();

        testCompanyCountry = CompanyCountry.builder()
                .id(companyCountryId)
                .company(testCompany)
                .country(testCountry)
                .localAlias("Oficina MX")
                .build();

        testRegion = CompanyRegion.builder()
                .id(regionId)
                .companyCountry(testCompanyCountry)
                .regionCode("NORTE")
                .regionName("Norte")
                .enabled(true)
                .build();

        testZone = CompanyZone.builder()
                .id(zoneId)
                .companyRegion(testRegion)
                .zoneCode("CEN")
                .zoneName("Centro")
                .enabled(true)
                .build();

        testStore = CompanyStore.builder()
                .id(storeId)
                .companyZone(testZone)
                .storeName("Tienda Principal")
                .enabled(true)
                .build();

        testArea = StoreArea.builder()
                .id(areaId)
                .companyStore(testStore)
                .areaCode("A01")
                .areaName("Bodega")
                .description("Área de almacenamiento")
                .displayOrder(1)
                .enabled(true)
                .build();

        testStoreZone = StoreZone.builder()
                .id(storeZoneId)
                .storeArea(testArea)
                .zoneCode("Z01")
                .zoneName("Pasillo")
                .description("Zona de pasillo")
                .displayOrder(1)
                .enabled(true)
                .build();

        testStoreLocation = StoreLocation.builder()
                .id(storeLocationId)
                .storeZone(testStoreZone)
                .locationCode("L01")
                .locationName("Estante")
                .description("Ubicación de estante")
                .displayOrder(1)
                .enabled(true)
                .build();

        createRequest = new CreateStoreLocationRequest("L02", "Estante 2", "Ubicación nueva", 2);
        updateRequest = new UpdateStoreLocationRequest("L03", "Estante renovado", "Actualizada", 3);
    }

    /**
     * Stubs the full company &rarr; country &rarr; region &rarr; zone &rarr; store chain with real
     * ids, because the service resolves each level using the parent entity's id.
     */
    private void mockStoreResolution() {
        when(companyRepository.findById(companyId)).thenReturn(Optional.of(testCompany));
        when(companyCountryRepository.findByCompanyIdAndId(companyId, companyCountryId))
                .thenReturn(Optional.of(testCompanyCountry));
        when(companyRegionRepository.findByIdAndCompanyCountryId(regionId, companyCountryId))
                .thenReturn(Optional.of(testRegion));
        when(companyZoneRepository.findByIdAndCompanyRegionId(zoneId, regionId)).thenReturn(Optional.of(testZone));
        when(companyStoreRepository.findByIdAndCompanyZoneId(storeId, zoneId)).thenReturn(Optional.of(testStore));
    }

    private void mockAreaResolution() {
        mockStoreResolution();
        when(storeAreaRepository.findByIdAndCompanyStoreId(areaId, storeId)).thenReturn(Optional.of(testArea));
    }

    private void mockZoneResolution() {
        mockAreaResolution();
        when(storeZoneRepository.findByIdAndStoreAreaId(storeZoneId, areaId)).thenReturn(Optional.of(testStoreZone));
    }

    @Nested
    @DisplayName("getAllLocations")
    class GetAllLocationsTests {

        @Test
        @DisplayName("should return only enabled locations when includeDisabled is false")
        void getAllLocations_ExcludeDisabled_ReturnsOnlyEnabled() {
            mockZoneResolution();
            when(storeLocationRepository.findByStoreZoneIdAndEnabledTrueOrderByDisplayOrderAscLocationCodeAsc(
                            storeZoneId))
                    .thenReturn(List.of(testStoreLocation));

            var result = storeLocationService.getAllLocations(
                    companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId, false);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).locationCode()).isEqualTo("L01");
            assertThat(result.get(0).storeZoneId()).isEqualTo(storeZoneId);
            assertThat(result.get(0).storeAreaId()).isEqualTo(areaId);
            assertThat(result.get(0).companyStoreId()).isEqualTo(storeId);
            verify(storeLocationRepository)
                    .findByStoreZoneIdAndEnabledTrueOrderByDisplayOrderAscLocationCodeAsc(storeZoneId);
            verify(storeLocationRepository, never()).findByStoreZoneIdOrderByDisplayOrderAscLocationCodeAsc(any());
        }

        @Test
        @DisplayName("should include disabled locations when includeDisabled is true")
        void getAllLocations_IncludeDisabled_ReturnsAll() {
            mockZoneResolution();
            var disabledLocation = StoreLocation.builder()
                    .id(UUID.randomUUID())
                    .storeZone(testStoreZone)
                    .locationCode("L09")
                    .locationName("Deshabilitada")
                    .enabled(false)
                    .build();
            when(storeLocationRepository.findByStoreZoneIdOrderByDisplayOrderAscLocationCodeAsc(storeZoneId))
                    .thenReturn(List.of(testStoreLocation, disabledLocation));

            var result = storeLocationService.getAllLocations(
                    companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId, true);

            assertThat(result).hasSize(2);
            verify(storeLocationRepository).findByStoreZoneIdOrderByDisplayOrderAscLocationCodeAsc(storeZoneId);
        }

        @Test
        @DisplayName("should throw StoreZoneNotFoundException when the zone does not belong to the area")
        void getAllLocations_ZoneNotFound() {
            mockAreaResolution();
            when(storeZoneRepository.findByIdAndStoreAreaId(storeZoneId, areaId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> storeLocationService.getAllLocations(
                            companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId, false))
                    .isInstanceOf(StoreZoneNotFoundException.class)
                    .hasMessage("Store zone not found with id: " + storeZoneId);

            verify(storeLocationRepository, never())
                    .findByStoreZoneIdAndEnabledTrueOrderByDisplayOrderAscLocationCodeAsc(any());
        }

        @Test
        @DisplayName("should throw StoreAreaNotFoundException when the area does not belong to the store")
        void getAllLocations_AreaNotFound() {
            mockStoreResolution();
            when(storeAreaRepository.findByIdAndCompanyStoreId(areaId, storeId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> storeLocationService.getAllLocations(
                            companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId, false))
                    .isInstanceOf(StoreAreaNotFoundException.class)
                    .hasMessage("Store area not found with id: " + areaId);

            verify(storeZoneRepository, never()).findByIdAndStoreAreaId(any(), any());
            verify(storeLocationRepository, never())
                    .findByStoreZoneIdAndEnabledTrueOrderByDisplayOrderAscLocationCodeAsc(any());
        }
    }

    @Nested
    @DisplayName("getLocationById (nested)")
    class GetLocationByIdTests {

        @Test
        @DisplayName("should return the location when found inside the zone")
        void getLocationById_Success() {
            mockZoneResolution();
            when(storeLocationRepository.findByIdAndStoreZoneId(storeLocationId, storeZoneId))
                    .thenReturn(Optional.of(testStoreLocation));

            var result = storeLocationService.getLocationById(
                    companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId, storeLocationId);

            assertThat(result.id()).isEqualTo(storeLocationId);
            assertThat(result.locationCode()).isEqualTo("L01");
            assertThat(result.locationName()).isEqualTo("Estante");
            assertThat(result.description()).isEqualTo("Ubicación de estante");
            assertThat(result.displayOrder()).isEqualTo(1);
            assertThat(result.enabled()).isTrue();
        }

        @Test
        @DisplayName("should throw StoreLocationNotFoundException when the location does not exist")
        void getLocationById_NotFound() {
            mockZoneResolution();
            when(storeLocationRepository.findByIdAndStoreZoneId(storeLocationId, storeZoneId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> storeLocationService.getLocationById(
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId,
                            storeLocationId))
                    .isInstanceOf(StoreLocationNotFoundException.class)
                    .hasMessage("Store location not found with id: " + storeLocationId);
        }

        @Test
        @DisplayName("should propagate CompanyStoreNotFoundException from the store resolution")
        void getLocationById_StoreNotFound() {
            mockStoreResolution();
            when(companyStoreRepository.findByIdAndCompanyZoneId(storeId, zoneId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> storeLocationService.getLocationById(
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId,
                            storeLocationId))
                    .isInstanceOf(CompanyStoreNotFoundException.class)
                    .hasMessage("Store not found with id: " + storeId);
        }
    }

    @Nested
    @DisplayName("getLocationById (flat)")
    class GetLocationByIdFlatTests {

        @Test
        @DisplayName("should return the location with its resolved chain ids")
        void getLocationByIdFlat_Success() {
            when(storeLocationRepository.findById(storeLocationId)).thenReturn(Optional.of(testStoreLocation));

            var result = storeLocationService.getLocationById(storeLocationId);

            assertThat(result.id()).isEqualTo(storeLocationId);
            assertThat(result.storeZoneId()).isEqualTo(storeZoneId);
            assertThat(result.storeAreaId()).isEqualTo(areaId);
            assertThat(result.companyStoreId()).isEqualTo(storeId);
            assertThat(result.companyId()).isEqualTo(companyId);
            assertThat(result.companyCountryId()).isEqualTo(companyCountryId);
            assertThat(result.regionId()).isEqualTo(regionId);
            assertThat(result.zoneId()).isEqualTo(zoneId);
            assertThat(result.locationCode()).isEqualTo("L01");
            assertThat(result.locationName()).isEqualTo("Estante");

            verify(currentUserContext).verifyCompanyStoreAccess(companyId, companyCountryId, regionId, zoneId, storeId);
        }

        @Test
        @DisplayName("should throw StoreLocationNotFoundException and never authorize when the location does not exist")
        void getLocationByIdFlat_NotFound() {
            when(storeLocationRepository.findById(storeLocationId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> storeLocationService.getLocationById(storeLocationId))
                    .isInstanceOf(StoreLocationNotFoundException.class)
                    .hasMessage("Store location not found with id: " + storeLocationId);

            verify(currentUserContext, never()).verifyCompanyStoreAccess(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("should propagate AccessDeniedException from verifyCompanyStoreAccess")
        void getLocationByIdFlat_AccessDenied() {
            when(storeLocationRepository.findById(storeLocationId)).thenReturn(Optional.of(testStoreLocation));
            doThrow(new AccessDeniedException("Access denied"))
                    .when(currentUserContext)
                    .verifyCompanyStoreAccess(companyId, companyCountryId, regionId, zoneId, storeId);

            assertThatThrownBy(() -> storeLocationService.getLocationById(storeLocationId))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessage("Access denied");
        }
    }

    @Nested
    @DisplayName("createLocation")
    class CreateLocationTests {

        @Test
        @DisplayName("should create an enabled location and log the outcome")
        void createLocation_Success() {
            mockZoneResolution();
            when(storeLocationRepository.existsByStoreZoneIdAndLocationCode(storeZoneId, "L02"))
                    .thenReturn(false);
            when(storeLocationRepository.save(any(StoreLocation.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            var result = storeLocationService.createLocation(
                    companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId, createRequest);

            assertThat(result.locationCode()).isEqualTo("L02");
            assertThat(result.locationName()).isEqualTo("Estante 2");
            assertThat(result.description()).isEqualTo("Ubicación nueva");
            assertThat(result.displayOrder()).isEqualTo(2);
            assertThat(result.storeZoneId()).isEqualTo(storeZoneId);
            assertThat(result.storeAreaId()).isEqualTo(areaId);
            assertThat(result.enabled()).isTrue();

            var captor = org.mockito.ArgumentCaptor.forClass(StoreLocation.class);
            verify(storeLocationRepository).save(captor.capture());
            assertThat(captor.getValue().getStoreZone()).isEqualTo(testStoreZone);
            assertThat(captor.getValue().getEnabled()).isTrue();
        }

        @Test
        @DisplayName("should throw DuplicateStoreLocationException when the location code already exists in the zone")
        void createLocation_DuplicateCode() {
            mockZoneResolution();
            when(storeLocationRepository.existsByStoreZoneIdAndLocationCode(storeZoneId, "L02"))
                    .thenReturn(true);

            assertThatThrownBy(() -> storeLocationService.createLocation(
                            companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId, createRequest))
                    .isInstanceOf(DuplicateStoreLocationException.class)
                    .hasMessage("Store location with code 'L02' already exists in this zone");

            verify(storeLocationRepository, never()).save(any(StoreLocation.class));
        }

        @Test
        @DisplayName("should throw DisabledParentException when the parent zone is disabled")
        void createLocation_DisabledZone_ThrowsException() {
            testStoreZone.setEnabled(false);
            mockZoneResolution();

            assertThatThrownBy(() -> storeLocationService.createLocation(
                            companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId, createRequest))
                    .isInstanceOf(DisabledParentException.class)
                    .hasMessage("Cannot create a store location: store zone with id " + storeZoneId + " is disabled");

            verify(storeLocationRepository, never()).existsByStoreZoneIdAndLocationCode(any(), any());
            verify(storeLocationRepository, never()).save(any(StoreLocation.class));
        }

        @Test
        @DisplayName("should throw DisabledParentException when the zone is enabled but its area is disabled")
        void createLocation_DisabledArea_ThrowsException() {
            testArea.setEnabled(false);
            mockZoneResolution();

            assertThatThrownBy(() -> storeLocationService.createLocation(
                            companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId, createRequest))
                    .isInstanceOf(DisabledParentException.class)
                    .hasMessage("Cannot create a store location: store area with id " + areaId + " is disabled");

            verify(storeLocationRepository, never()).existsByStoreZoneIdAndLocationCode(any(), any());
            verify(storeLocationRepository, never()).save(any(StoreLocation.class));
        }

        @Test
        @DisplayName("should throw DisabledParentException when zone and area are enabled but the store is disabled")
        void createLocation_DisabledStore_ThrowsException() {
            testStore.setEnabled(false);
            mockZoneResolution();

            assertThatThrownBy(() -> storeLocationService.createLocation(
                            companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId, createRequest))
                    .isInstanceOf(DisabledParentException.class)
                    .hasMessage("Cannot create a store location: store with id " + storeId + " is disabled");

            verify(storeLocationRepository, never()).existsByStoreZoneIdAndLocationCode(any(), any());
            verify(storeLocationRepository, never()).save(any(StoreLocation.class));
        }

        @Test
        @DisplayName("should propagate DataIntegrityViolationException from save instead of swallowing it")
        void createLocation_DataIntegrityViolation_Propagates() {
            mockZoneResolution();
            when(storeLocationRepository.existsByStoreZoneIdAndLocationCode(storeZoneId, "L02"))
                    .thenReturn(false);
            when(storeLocationRepository.save(any(StoreLocation.class)))
                    .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

            assertThatThrownBy(() -> storeLocationService.createLocation(
                            companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId, createRequest))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("duplicate key value violates unique constraint");
        }

        @Test
        @DisplayName("should throw StoreAreaNotFoundException when the area does not belong to the store")
        void createLocation_AreaNotFound() {
            mockStoreResolution();
            when(storeAreaRepository.findByIdAndCompanyStoreId(areaId, storeId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> storeLocationService.createLocation(
                            companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId, createRequest))
                    .isInstanceOf(StoreAreaNotFoundException.class)
                    .hasMessage("Store area not found with id: " + areaId);

            verify(storeZoneRepository, never()).findByIdAndStoreAreaId(any(), any());
            verify(storeLocationRepository, never()).save(any(StoreLocation.class));
        }
    }

    @Nested
    @DisplayName("updateLocation")
    class UpdateLocationTests {

        @Test
        @DisplayName("should update the provided fields")
        void updateLocation_Success() {
            mockZoneResolution();
            when(storeLocationRepository.findByIdAndStoreZoneId(storeLocationId, storeZoneId))
                    .thenReturn(Optional.of(testStoreLocation));
            when(storeLocationRepository.existsByStoreZoneIdAndLocationCodeAndIdNot(
                            storeZoneId, "L03", storeLocationId))
                    .thenReturn(false);
            when(storeLocationRepository.save(any(StoreLocation.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            var result = storeLocationService.updateLocation(
                    companyId,
                    companyCountryId,
                    regionId,
                    zoneId,
                    storeId,
                    areaId,
                    storeZoneId,
                    storeLocationId,
                    updateRequest);

            assertThat(result.locationCode()).isEqualTo("L03");
            assertThat(result.locationName()).isEqualTo("Estante renovado");
            assertThat(result.description()).isEqualTo("Actualizada");
            assertThat(result.displayOrder()).isEqualTo(3);
        }

        @Test
        @DisplayName("should skip the uniqueness check when the code is unchanged")
        void updateLocation_UnchangedCode_SkipsUniquenessCheck() {
            mockZoneResolution();
            when(storeLocationRepository.findByIdAndStoreZoneId(storeLocationId, storeZoneId))
                    .thenReturn(Optional.of(testStoreLocation));
            when(storeLocationRepository.save(any(StoreLocation.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            var result = storeLocationService.updateLocation(
                    companyId,
                    companyCountryId,
                    regionId,
                    zoneId,
                    storeId,
                    areaId,
                    storeZoneId,
                    storeLocationId,
                    new UpdateStoreLocationRequest("L01", "Estante renombrado", null, null));

            assertThat(result.locationCode()).isEqualTo("L01");
            assertThat(result.locationName()).isEqualTo("Estante renombrado");
            assertThat(result.description()).isEqualTo("Ubicación de estante");
            assertThat(result.displayOrder()).isEqualTo(1);
            verify(storeLocationRepository, never()).existsByStoreZoneIdAndLocationCodeAndIdNot(any(), any(), any());
        }

        @Test
        @DisplayName("should throw DuplicateStoreLocationException when the new code collides")
        void updateLocation_DuplicateCode() {
            mockZoneResolution();
            when(storeLocationRepository.findByIdAndStoreZoneId(storeLocationId, storeZoneId))
                    .thenReturn(Optional.of(testStoreLocation));
            when(storeLocationRepository.existsByStoreZoneIdAndLocationCodeAndIdNot(
                            storeZoneId, "L03", storeLocationId))
                    .thenReturn(true);

            assertThatThrownBy(() -> storeLocationService.updateLocation(
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId,
                            storeLocationId,
                            updateRequest))
                    .isInstanceOf(DuplicateStoreLocationException.class)
                    .hasMessage("Store location with code 'L03' already exists in this zone");

            verify(storeLocationRepository, never()).save(any(StoreLocation.class));
        }

        @Test
        @DisplayName("should throw StoreLocationNotFoundException when the location does not exist")
        void updateLocation_NotFound() {
            mockZoneResolution();
            when(storeLocationRepository.findByIdAndStoreZoneId(storeLocationId, storeZoneId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> storeLocationService.updateLocation(
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId,
                            storeLocationId,
                            updateRequest))
                    .isInstanceOf(StoreLocationNotFoundException.class)
                    .hasMessage("Store location not found with id: " + storeLocationId);
        }
    }

    @Nested
    @DisplayName("deleteLocation")
    class DeleteLocationTests {

        @Test
        @DisplayName("should soft-delete by setting enabled=false and keeping the row")
        void deleteLocation_SoftDeletes() {
            mockZoneResolution();
            when(storeLocationRepository.findByIdAndStoreZoneId(storeLocationId, storeZoneId))
                    .thenReturn(Optional.of(testStoreLocation));
            when(storeLocationRepository.save(any(StoreLocation.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            storeLocationService.deleteLocation(
                    companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId, storeLocationId);

            var captor = org.mockito.ArgumentCaptor.forClass(StoreLocation.class);
            verify(storeLocationRepository).save(captor.capture());
            assertThat(captor.getValue().getEnabled()).isFalse();
            verify(storeLocationRepository, never()).deleteById(any());
            verify(storeLocationRepository, never()).delete(any());
        }

        @Test
        @DisplayName("should throw StoreLocationNotFoundException when the location does not exist")
        void deleteLocation_NotFound() {
            mockZoneResolution();
            when(storeLocationRepository.findByIdAndStoreZoneId(storeLocationId, storeZoneId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> storeLocationService.deleteLocation(
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId,
                            storeLocationId))
                    .isInstanceOf(StoreLocationNotFoundException.class)
                    .hasMessage("Store location not found with id: " + storeLocationId);

            verify(storeLocationRepository, never()).save(any(StoreLocation.class));
        }
    }

    @Nested
    @DisplayName("enableLocation")
    class EnableLocationTests {

        @Test
        @DisplayName("should re-enable a soft-deleted location")
        void enableLocation_Success() {
            mockZoneResolution();
            testStoreLocation.setEnabled(false);
            when(storeLocationRepository.findByIdAndStoreZoneId(storeLocationId, storeZoneId))
                    .thenReturn(Optional.of(testStoreLocation));
            when(storeLocationRepository.save(any(StoreLocation.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            StoreLocationResponse result = storeLocationService.enableLocation(
                    companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId, storeLocationId);

            assertThat(result.enabled()).isTrue();
            var captor = org.mockito.ArgumentCaptor.forClass(StoreLocation.class);
            verify(storeLocationRepository).save(captor.capture());
            assertThat(captor.getValue().getEnabled()).isTrue();
        }

        @Test
        @DisplayName("should throw StoreLocationNotFoundException when the location does not exist")
        void enableLocation_NotFound() {
            mockZoneResolution();
            when(storeLocationRepository.findByIdAndStoreZoneId(storeLocationId, storeZoneId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> storeLocationService.enableLocation(
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId,
                            storeLocationId))
                    .isInstanceOf(StoreLocationNotFoundException.class)
                    .hasMessage("Store location not found with id: " + storeLocationId);
        }

        @Test
        @DisplayName("should throw DisabledParentException when the parent zone is disabled")
        void enableLocation_DisabledZone_ThrowsException() {
            testStoreZone.setEnabled(false);
            mockZoneResolution();

            assertThatThrownBy(() -> storeLocationService.enableLocation(
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId,
                            storeLocationId))
                    .isInstanceOf(DisabledParentException.class)
                    .hasMessage(
                            "Cannot re-enable a store location: store zone with id " + storeZoneId + " is disabled");

            verify(storeLocationRepository, never()).findByIdAndStoreZoneId(any(), any());
            verify(storeLocationRepository, never()).save(any(StoreLocation.class));
        }

        @Test
        @DisplayName("should throw DisabledParentException when the zone is enabled but its area is disabled")
        void enableLocation_DisabledArea_ThrowsException() {
            testArea.setEnabled(false);
            mockZoneResolution();

            assertThatThrownBy(() -> storeLocationService.enableLocation(
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId,
                            storeLocationId))
                    .isInstanceOf(DisabledParentException.class)
                    .hasMessage("Cannot re-enable a store location: store area with id " + areaId + " is disabled");

            verify(storeLocationRepository, never()).findByIdAndStoreZoneId(any(), any());
            verify(storeLocationRepository, never()).save(any(StoreLocation.class));
        }

        @Test
        @DisplayName("should throw DisabledParentException when zone and area are enabled but the store is disabled")
        void enableLocation_DisabledStore_ThrowsException() {
            testStore.setEnabled(false);
            mockZoneResolution();

            assertThatThrownBy(() -> storeLocationService.enableLocation(
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId,
                            storeLocationId))
                    .isInstanceOf(DisabledParentException.class)
                    .hasMessage("Cannot re-enable a store location: store with id " + storeId + " is disabled");

            verify(storeLocationRepository, never()).findByIdAndStoreZoneId(any(), any());
            verify(storeLocationRepository, never()).save(any(StoreLocation.class));
        }
    }
}
