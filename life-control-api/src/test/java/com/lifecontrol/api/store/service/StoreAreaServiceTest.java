package com.lifecontrol.api.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import com.lifecontrol.api.store.dto.CreateStoreAreaRequest;
import com.lifecontrol.api.store.dto.StoreAreaResponse;
import com.lifecontrol.api.store.dto.UpdateStoreAreaRequest;
import com.lifecontrol.api.store.exception.CompanyStoreNotFoundException;
import com.lifecontrol.api.store.exception.DisabledParentException;
import com.lifecontrol.api.store.exception.DuplicateStoreAreaException;
import com.lifecontrol.api.store.exception.StoreAreaNotFoundException;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.model.StoreArea;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import com.lifecontrol.api.store.repository.StoreAreaRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;

@ExtendWith(MockitoExtension.class)
@DisplayName("StoreAreaService Tests")
class StoreAreaServiceTest {

    @Mock
    private StoreAreaRepository storeAreaRepository;

    @Mock
    private StoreZoneService storeZoneService;

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
    private StoreAreaService storeAreaService;

    private UUID companyId;
    private UUID companyCountryId;
    private UUID regionId;
    private UUID zoneId;
    private UUID storeId;
    private UUID areaId;
    private Company testCompany;
    private CompanyCountry testCompanyCountry;
    private CompanyRegion testRegion;
    private CompanyZone testZone;
    private CompanyStore testStore;
    private StoreArea testArea;
    private CreateStoreAreaRequest createRequest;
    private UpdateStoreAreaRequest updateRequest;

    private ListAppender<ILoggingEvent> logAppender;
    private Logger serviceLogger;
    private Level previousLogLevel;

    @BeforeEach
    void attachLogCapture() {
        serviceLogger = (Logger) LoggerFactory.getLogger(StoreAreaService.class);
        previousLogLevel = serviceLogger.getLevel();
        serviceLogger.setLevel(Level.INFO);
        logAppender = new ListAppender<>();
        logAppender.start();
        serviceLogger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogCapture() {
        serviceLogger.detachAppender(logAppender);
        serviceLogger.setLevel(previousLogLevel);
    }

    private String capturedLogs() {
        return logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        companyCountryId = UUID.randomUUID();
        regionId = UUID.randomUUID();
        zoneId = UUID.randomUUID();
        storeId = UUID.randomUUID();
        areaId = UUID.randomUUID();

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

        createRequest = new CreateStoreAreaRequest("A02", "Piso de venta", "Área de clientes", 2);
        updateRequest = new UpdateStoreAreaRequest("A03", "Piso de venta renovado", "Actualizada", 3);
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

    @Nested
    @DisplayName("getAllAreas")
    class GetAllAreasTests {

        @Test
        @DisplayName("should return only enabled areas when includeDisabled is false")
        void getAllAreas_ExcludeDisabled_ReturnsOnlyEnabled() {
            mockStoreResolution();
            var disabledArea = StoreArea.builder()
                    .id(UUID.randomUUID())
                    .companyStore(testStore)
                    .areaCode("A09")
                    .areaName("Deshabilitada")
                    .enabled(false)
                    .build();
            when(storeAreaRepository.findByCompanyStoreIdAndEnabledTrueOrderByDisplayOrderAscAreaCodeAsc(storeId))
                    .thenReturn(List.of(testArea));

            var result = storeAreaService.getAllAreas(companyId, companyCountryId, regionId, zoneId, storeId, false);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).areaCode()).isEqualTo("A01");
            assertThat(result.get(0).companyStoreId()).isEqualTo(storeId);
            verify(storeAreaRepository).findByCompanyStoreIdAndEnabledTrueOrderByDisplayOrderAscAreaCodeAsc(storeId);
            verify(storeAreaRepository, never()).findByCompanyStoreIdOrderByDisplayOrderAscAreaCodeAsc(any());
            assertThat(disabledArea.getEnabled()).isFalse();
        }

        @Test
        @DisplayName("should include disabled areas when includeDisabled is true")
        void getAllAreas_IncludeDisabled_ReturnsAll() {
            mockStoreResolution();
            var disabledArea = StoreArea.builder()
                    .id(UUID.randomUUID())
                    .companyStore(testStore)
                    .areaCode("A09")
                    .areaName("Deshabilitada")
                    .enabled(false)
                    .build();
            when(storeAreaRepository.findByCompanyStoreIdOrderByDisplayOrderAscAreaCodeAsc(storeId))
                    .thenReturn(List.of(testArea, disabledArea));

            var result = storeAreaService.getAllAreas(companyId, companyCountryId, regionId, zoneId, storeId, true);

            assertThat(result).hasSize(2);
            verify(storeAreaRepository).findByCompanyStoreIdOrderByDisplayOrderAscAreaCodeAsc(storeId);
        }

        @Test
        @DisplayName("should throw CompanyStoreNotFoundException when the store does not belong to the zone")
        void getAllAreas_StoreNotFound() {
            mockStoreResolution();
            when(companyStoreRepository.findByIdAndCompanyZoneId(storeId, zoneId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                            storeAreaService.getAllAreas(companyId, companyCountryId, regionId, zoneId, storeId, false))
                    .isInstanceOf(CompanyStoreNotFoundException.class)
                    .hasMessage("Store not found with id: " + storeId);

            verify(storeAreaRepository, never())
                    .findByCompanyStoreIdAndEnabledTrueOrderByDisplayOrderAscAreaCodeAsc(any());
        }
    }

    @Nested
    @DisplayName("getAreaById")
    class GetAreaByIdTests {

        @Test
        @DisplayName("should return the area when found inside the store")
        void getAreaById_Success() {
            mockStoreResolution();
            when(storeAreaRepository.findByIdAndCompanyStoreId(areaId, storeId)).thenReturn(Optional.of(testArea));

            var result = storeAreaService.getAreaById(companyId, companyCountryId, regionId, zoneId, storeId, areaId);

            assertThat(result.id()).isEqualTo(areaId);
            assertThat(result.areaCode()).isEqualTo("A01");
            assertThat(result.areaName()).isEqualTo("Bodega");
            assertThat(result.description()).isEqualTo("Área de almacenamiento");
            assertThat(result.displayOrder()).isEqualTo(1);
            assertThat(result.enabled()).isTrue();
        }

        @Test
        @DisplayName("should throw StoreAreaNotFoundException when the area does not exist")
        void getAreaById_NotFound() {
            mockStoreResolution();
            when(storeAreaRepository.findByIdAndCompanyStoreId(areaId, storeId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> storeAreaService.getAreaById(
                            companyId, companyCountryId, regionId, zoneId, storeId, areaId))
                    .isInstanceOf(StoreAreaNotFoundException.class)
                    .hasMessage("Store area not found with id: " + areaId);
        }
    }

    @Nested
    @DisplayName("getAreaById (flat)")
    class GetAreaByIdFlatTests {

        @Test
        @DisplayName("should return the area with its resolved chain ids")
        void getAreaByIdFlat_Success() {
            when(storeAreaRepository.findById(areaId)).thenReturn(Optional.of(testArea));

            var result = storeAreaService.getAreaById(areaId);

            assertThat(result.id()).isEqualTo(areaId);
            assertThat(result.companyStoreId()).isEqualTo(storeId);
            assertThat(result.companyId()).isEqualTo(companyId);
            assertThat(result.companyCountryId()).isEqualTo(companyCountryId);
            assertThat(result.regionId()).isEqualTo(regionId);
            assertThat(result.zoneId()).isEqualTo(zoneId);
            assertThat(result.areaCode()).isEqualTo("A01");
            assertThat(result.areaName()).isEqualTo("Bodega");

            verify(currentUserContext).verifyCompanyStoreAccess(companyId, companyCountryId, regionId, zoneId, storeId);
        }

        @Test
        @DisplayName("should throw StoreAreaNotFoundException and never authorize when the area does not exist")
        void getAreaByIdFlat_NotFound() {
            when(storeAreaRepository.findById(areaId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> storeAreaService.getAreaById(areaId))
                    .isInstanceOf(StoreAreaNotFoundException.class)
                    .hasMessage("Store area not found with id: " + areaId);

            verify(currentUserContext, never()).verifyCompanyStoreAccess(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("should mask a denied flat lookup as the identical not-found error of the same id")
        void getAreaByIdFlat_AccessDenied() {
            // Genuinely missing: findById returns nothing.
            when(storeAreaRepository.findById(areaId)).thenReturn(Optional.empty());
            var genuinelyMissing = catchThrowable(() -> storeAreaService.getAreaById(areaId));

            // Out of scope: the area exists, but the resolved store is not accessible to the user.
            when(storeAreaRepository.findById(areaId)).thenReturn(Optional.of(testArea));
            doThrow(new AccessDeniedException("Access denied"))
                    .when(currentUserContext)
                    .verifyCompanyStoreAccess(companyId, companyCountryId, regionId, zoneId, storeId);
            when(currentUserContext.getUsername()).thenReturn("jdoe");
            var denied = catchThrowable(() -> storeAreaService.getAreaById(areaId));

            // The two responses must be indistinguishable: same type, same message, same status.
            assertThat(genuinelyMissing).isInstanceOf(StoreAreaNotFoundException.class);
            assertThat(denied).isInstanceOf(StoreAreaNotFoundException.class);
            assertThat(denied.getClass()).isEqualTo(genuinelyMissing.getClass());
            assertThat(denied.getMessage()).isEqualTo(genuinelyMissing.getMessage());
            assertThat(denied.getMessage()).isEqualTo("Store area not found with id: " + areaId);
            assertThat(capturedLogs())
                    .contains("StoreArea access denied")
                    .contains("id=" + areaId)
                    .contains("actor=jdoe");
        }
    }

    @Nested
    @DisplayName("createArea")
    class CreateAreaTests {

        @Test
        @DisplayName("should create an enabled area and log the outcome")
        void createArea_Success() {
            mockStoreResolution();
            when(storeAreaRepository.existsByCompanyStoreIdAndAreaCode(storeId, "A02"))
                    .thenReturn(false);
            when(storeAreaRepository.saveAndFlush(any(StoreArea.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            var result =
                    storeAreaService.createArea(companyId, companyCountryId, regionId, zoneId, storeId, createRequest);

            assertThat(result.areaCode()).isEqualTo("A02");
            assertThat(result.areaName()).isEqualTo("Piso de venta");
            assertThat(result.description()).isEqualTo("Área de clientes");
            assertThat(result.displayOrder()).isEqualTo(2);
            assertThat(result.companyStoreId()).isEqualTo(storeId);
            assertThat(result.enabled()).isTrue();

            var captor = org.mockito.ArgumentCaptor.forClass(StoreArea.class);
            verify(storeAreaRepository).saveAndFlush(captor.capture());
            assertThat(captor.getValue().getCompanyStore()).isEqualTo(testStore);
            assertThat(captor.getValue().getEnabled()).isTrue();
        }

        @Test
        @DisplayName("should throw DuplicateStoreAreaException when the area code already exists in the store")
        void createArea_DuplicateCode() {
            mockStoreResolution();
            when(storeAreaRepository.existsByCompanyStoreIdAndAreaCode(storeId, "A02"))
                    .thenReturn(true);

            assertThatThrownBy(() -> storeAreaService.createArea(
                            companyId, companyCountryId, regionId, zoneId, storeId, createRequest))
                    .isInstanceOf(DuplicateStoreAreaException.class)
                    .hasMessage("Store area with code 'A02' already exists in this store");

            verify(storeAreaRepository, never()).saveAndFlush(any(StoreArea.class));
        }

        @Test
        @DisplayName("should throw DisabledParentException when the parent store is disabled")
        void createArea_DisabledStore_ThrowsException() {
            testStore.setEnabled(false);
            mockStoreResolution();

            assertThatThrownBy(() -> storeAreaService.createArea(
                            companyId, companyCountryId, regionId, zoneId, storeId, createRequest))
                    .isInstanceOf(DisabledParentException.class)
                    .hasMessage("Cannot create a store area: store with id " + storeId + " is disabled");

            verify(storeAreaRepository, never()).existsByCompanyStoreIdAndAreaCode(any(), any());
            verify(storeAreaRepository, never()).saveAndFlush(any(StoreArea.class));
        }
    }

    @Nested
    @DisplayName("updateArea")
    class UpdateAreaTests {

        @Test
        @DisplayName("should update the provided fields")
        void updateArea_Success() {
            mockStoreResolution();
            when(storeAreaRepository.findByIdAndCompanyStoreId(areaId, storeId)).thenReturn(Optional.of(testArea));
            when(storeAreaRepository.existsByCompanyStoreIdAndAreaCodeAndIdNot(storeId, "A03", areaId))
                    .thenReturn(false);
            when(storeAreaRepository.saveAndFlush(any(StoreArea.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            var result = storeAreaService.updateArea(
                    companyId, companyCountryId, regionId, zoneId, storeId, areaId, updateRequest);

            assertThat(result.areaCode()).isEqualTo("A03");
            assertThat(result.areaName()).isEqualTo("Piso de venta renovado");
            assertThat(result.description()).isEqualTo("Actualizada");
            assertThat(result.displayOrder()).isEqualTo(3);
        }

        @Test
        @DisplayName("should keep untouched fields when the request omits them")
        void updateArea_PartialRequest_KeepsExistingValues() {
            mockStoreResolution();
            when(storeAreaRepository.findByIdAndCompanyStoreId(areaId, storeId)).thenReturn(Optional.of(testArea));
            when(storeAreaRepository.saveAndFlush(any(StoreArea.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            var result = storeAreaService.updateArea(
                    companyId,
                    companyCountryId,
                    regionId,
                    zoneId,
                    storeId,
                    areaId,
                    new UpdateStoreAreaRequest(null, "Nuevo nombre", null, null));

            assertThat(result.areaCode()).isEqualTo("A01");
            assertThat(result.areaName()).isEqualTo("Nuevo nombre");
            assertThat(result.description()).isEqualTo("Área de almacenamiento");
            assertThat(result.displayOrder()).isEqualTo(1);
            verify(storeAreaRepository, never()).existsByCompanyStoreIdAndAreaCodeAndIdNot(any(), any(), any());
        }

        @Test
        @DisplayName("should throw DuplicateStoreAreaException when the new code collides")
        void updateArea_DuplicateCode() {
            mockStoreResolution();
            when(storeAreaRepository.findByIdAndCompanyStoreId(areaId, storeId)).thenReturn(Optional.of(testArea));
            when(storeAreaRepository.existsByCompanyStoreIdAndAreaCodeAndIdNot(storeId, "A03", areaId))
                    .thenReturn(true);

            assertThatThrownBy(() -> storeAreaService.updateArea(
                            companyId, companyCountryId, regionId, zoneId, storeId, areaId, updateRequest))
                    .isInstanceOf(DuplicateStoreAreaException.class)
                    .hasMessage("Store area with code 'A03' already exists in this store");

            verify(storeAreaRepository, never()).saveAndFlush(any(StoreArea.class));
        }

        @Test
        @DisplayName("should throw StoreAreaNotFoundException when the area does not exist")
        void updateArea_NotFound() {
            mockStoreResolution();
            when(storeAreaRepository.findByIdAndCompanyStoreId(areaId, storeId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> storeAreaService.updateArea(
                            companyId, companyCountryId, regionId, zoneId, storeId, areaId, updateRequest))
                    .isInstanceOf(StoreAreaNotFoundException.class)
                    .hasMessage("Store area not found with id: " + areaId);
        }
    }

    @Nested
    @DisplayName("deleteArea")
    class DeleteAreaTests {

        @Test
        @DisplayName("should soft-delete by setting enabled=false and keeping the row")
        void deleteArea_SoftDeletes() {
            mockStoreResolution();
            when(storeAreaRepository.findByIdAndCompanyStoreId(areaId, storeId)).thenReturn(Optional.of(testArea));
            when(storeAreaRepository.save(any(StoreArea.class))).thenAnswer(invocation -> invocation.getArgument(0));

            storeAreaService.deleteArea(companyId, companyCountryId, regionId, zoneId, storeId, areaId);

            var captor = org.mockito.ArgumentCaptor.forClass(StoreArea.class);
            verify(storeAreaRepository).save(captor.capture());
            assertThat(captor.getValue().getEnabled()).isFalse();
            verify(storeAreaRepository, never()).deleteById(any());
            verify(storeAreaRepository, never()).delete(any());
        }

        @Test
        @DisplayName("should cascade the soft delete to the area's still-enabled zones through StoreZoneService")
        void deleteArea_CascadesToEnabledZones() {
            mockStoreResolution();
            when(storeAreaRepository.findByIdAndCompanyStoreId(areaId, storeId)).thenReturn(Optional.of(testArea));
            when(storeAreaRepository.save(any(StoreArea.class))).thenAnswer(invocation -> invocation.getArgument(0));

            storeAreaService.deleteArea(companyId, companyCountryId, regionId, zoneId, storeId, areaId);

            var captor = org.mockito.ArgumentCaptor.forClass(List.class);
            verify(storeZoneService).disableZonesOfAreas(captor.capture());
            assertThat(captor.getValue()).hasSize(1).containsExactly(testArea);
        }

        @Test
        @DisplayName("should record the acting username in the soft-delete audit log")
        void deleteArea_LogsActingUsername() {
            mockStoreResolution();
            when(storeAreaRepository.findByIdAndCompanyStoreId(areaId, storeId)).thenReturn(Optional.of(testArea));
            when(storeAreaRepository.save(any(StoreArea.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(currentUserContext.getUsername()).thenReturn("jdoe");

            storeAreaService.deleteArea(companyId, companyCountryId, regionId, zoneId, storeId, areaId);

            assertThat(capturedLogs())
                    .contains("StoreArea soft-deleted")
                    .contains("id=" + areaId)
                    .contains("actor=jdoe");
        }

        @Test
        @DisplayName("should throw StoreAreaNotFoundException when the area does not exist")
        void deleteArea_NotFound() {
            mockStoreResolution();
            when(storeAreaRepository.findByIdAndCompanyStoreId(areaId, storeId)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                            storeAreaService.deleteArea(companyId, companyCountryId, regionId, zoneId, storeId, areaId))
                    .isInstanceOf(StoreAreaNotFoundException.class)
                    .hasMessage("Store area not found with id: " + areaId);

            verify(storeAreaRepository, never()).save(any(StoreArea.class));
        }
    }

    @Nested
    @DisplayName("disableAreasOfStore")
    class DisableAreasOfStoreTests {

        @Test
        @DisplayName("should disable the store's areas and delegate their zones to StoreZoneService")
        void disableAreasOfStore_DisablesAreasAndZones() {
            var secondArea = StoreArea.builder()
                    .id(UUID.randomUUID())
                    .companyStore(testStore)
                    .areaCode("A02")
                    .areaName("Piso de venta")
                    .enabled(true)
                    .build();

            when(storeAreaRepository.findByCompanyStoreIdAndEnabledTrueOrderByDisplayOrderAscAreaCodeAsc(storeId))
                    .thenReturn(new ArrayList<>(List.of(testArea, secondArea)));

            storeAreaService.disableAreasOfStore(storeId);

            assertThat(testArea.getEnabled()).isFalse();
            assertThat(secondArea.getEnabled()).isFalse();
            var captor = org.mockito.ArgumentCaptor.forClass(List.class);
            verify(storeZoneService).disableZonesOfAreas(captor.capture());
            assertThat(captor.getValue()).hasSize(2).containsExactly(testArea, secondArea);
            verify(storeAreaRepository).saveAll(List.of(testArea, secondArea));
        }

        @Test
        @DisplayName("should do nothing when the store has no enabled areas")
        void disableAreasOfStore_NoEnabledAreas() {
            when(storeAreaRepository.findByCompanyStoreIdAndEnabledTrueOrderByDisplayOrderAscAreaCodeAsc(storeId))
                    .thenReturn(new ArrayList<>());

            storeAreaService.disableAreasOfStore(storeId);

            verify(storeZoneService).disableZonesOfAreas(List.of());
            verify(storeAreaRepository).saveAll(List.of());
        }
    }

    @Nested
    @DisplayName("enableArea")
    class EnableAreaTests {

        @Test
        @DisplayName("should re-enable a soft-deleted area")
        void enableArea_Success() {
            mockStoreResolution();
            testArea.setEnabled(false);
            when(storeAreaRepository.findByIdAndCompanyStoreId(areaId, storeId)).thenReturn(Optional.of(testArea));
            when(storeAreaRepository.saveAndFlush(any(StoreArea.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            StoreAreaResponse result =
                    storeAreaService.enableArea(companyId, companyCountryId, regionId, zoneId, storeId, areaId);

            assertThat(result.enabled()).isTrue();
            var captor = org.mockito.ArgumentCaptor.forClass(StoreArea.class);
            verify(storeAreaRepository).saveAndFlush(captor.capture());
            assertThat(captor.getValue().getEnabled()).isTrue();
        }

        @Test
        @DisplayName("should not cascade to zones when re-enabling the area")
        void enableArea_DoesNotCascadeToZones() {
            mockStoreResolution();
            testArea.setEnabled(false);
            when(storeAreaRepository.findByIdAndCompanyStoreId(areaId, storeId)).thenReturn(Optional.of(testArea));
            when(storeAreaRepository.saveAndFlush(any(StoreArea.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            storeAreaService.enableArea(companyId, companyCountryId, regionId, zoneId, storeId, areaId);

            // D8: re-enable is explicit and ordered (store -> area -> zone -> location); it never
            // cascades down.
            verify(storeZoneService, never()).disableZonesOfAreas(any());
        }

        @Test
        @DisplayName("should throw StoreAreaNotFoundException when the area does not exist")
        void enableArea_NotFound() {
            mockStoreResolution();
            when(storeAreaRepository.findByIdAndCompanyStoreId(areaId, storeId)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                            storeAreaService.enableArea(companyId, companyCountryId, regionId, zoneId, storeId, areaId))
                    .isInstanceOf(StoreAreaNotFoundException.class)
                    .hasMessage("Store area not found with id: " + areaId);
        }

        @Test
        @DisplayName("should throw DisabledParentException when the parent store is disabled")
        void enableArea_DisabledStore_ThrowsException() {
            testStore.setEnabled(false);
            mockStoreResolution();

            assertThatThrownBy(() ->
                            storeAreaService.enableArea(companyId, companyCountryId, regionId, zoneId, storeId, areaId))
                    .isInstanceOf(DisabledParentException.class)
                    .hasMessage("Cannot re-enable a store area: store with id " + storeId + " is disabled");

            verify(storeAreaRepository, never()).findByIdAndCompanyStoreId(any(), any());
            verify(storeAreaRepository, never()).saveAndFlush(any(StoreArea.class));
        }
    }
}
