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
import com.lifecontrol.api.store.dto.CreateStoreZoneRequest;
import com.lifecontrol.api.store.dto.StoreZoneResponse;
import com.lifecontrol.api.store.dto.UpdateStoreZoneRequest;
import com.lifecontrol.api.store.exception.CompanyStoreNotFoundException;
import com.lifecontrol.api.store.exception.DisabledParentException;
import com.lifecontrol.api.store.exception.DuplicateStoreZoneException;
import com.lifecontrol.api.store.exception.StoreAreaNotFoundException;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;

@ExtendWith(MockitoExtension.class)
@DisplayName("StoreZoneService Tests")
class StoreZoneServiceTest {

    @Mock
    private StoreZoneRepository storeZoneRepository;

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

    @InjectMocks
    private StoreZoneService storeZoneService;

    private UUID companyId;
    private UUID companyCountryId;
    private UUID regionId;
    private UUID zoneId;
    private UUID storeId;
    private UUID areaId;
    private UUID storeZoneId;
    private Company testCompany;
    private CompanyCountry testCompanyCountry;
    private CompanyRegion testRegion;
    private CompanyZone testZone;
    private CompanyStore testStore;
    private StoreArea testArea;
    private StoreZone testStoreZone;
    private CreateStoreZoneRequest createRequest;
    private UpdateStoreZoneRequest updateRequest;

    private ListAppender<ILoggingEvent> logAppender;
    private Logger serviceLogger;
    private Level previousLogLevel;

    @BeforeEach
    void attachLogCapture() {
        serviceLogger = (Logger) LoggerFactory.getLogger(StoreZoneService.class);
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
        storeZoneId = UUID.randomUUID();

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

        createRequest = new CreateStoreZoneRequest("Z02", "Estante", "Zona de estantes", 2);
        updateRequest = new UpdateStoreZoneRequest("Z03", "Pasillo renovado", "Actualizada", 3);
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

    @Nested
    @DisplayName("getAllZones")
    class GetAllZonesTests {

        @Test
        @DisplayName("should return only enabled zones when includeDisabled is false")
        void getAllZones_ExcludeDisabled_ReturnsOnlyEnabled() {
            mockAreaResolution();
            when(storeZoneRepository.findByStoreAreaIdAndEnabledTrueOrderByDisplayOrderAscZoneCodeAsc(areaId))
                    .thenReturn(List.of(testStoreZone));

            var result =
                    storeZoneService.getAllZones(companyId, companyCountryId, regionId, zoneId, storeId, areaId, false);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).zoneCode()).isEqualTo("Z01");
            assertThat(result.get(0).storeAreaId()).isEqualTo(areaId);
            assertThat(result.get(0).companyStoreId()).isEqualTo(storeId);
            verify(storeZoneRepository).findByStoreAreaIdAndEnabledTrueOrderByDisplayOrderAscZoneCodeAsc(areaId);
            verify(storeZoneRepository, never()).findByStoreAreaIdOrderByDisplayOrderAscZoneCodeAsc(any());
        }

        @Test
        @DisplayName("should include disabled zones when includeDisabled is true")
        void getAllZones_IncludeDisabled_ReturnsAll() {
            mockAreaResolution();
            var disabledZone = StoreZone.builder()
                    .id(UUID.randomUUID())
                    .storeArea(testArea)
                    .zoneCode("Z09")
                    .zoneName("Deshabilitada")
                    .enabled(false)
                    .build();
            when(storeZoneRepository.findByStoreAreaIdOrderByDisplayOrderAscZoneCodeAsc(areaId))
                    .thenReturn(List.of(testStoreZone, disabledZone));

            var result =
                    storeZoneService.getAllZones(companyId, companyCountryId, regionId, zoneId, storeId, areaId, true);

            assertThat(result).hasSize(2);
            verify(storeZoneRepository).findByStoreAreaIdOrderByDisplayOrderAscZoneCodeAsc(areaId);
        }

        @Test
        @DisplayName("should throw StoreAreaNotFoundException when the area does not belong to the store")
        void getAllZones_AreaNotFound() {
            mockStoreResolution();
            when(storeAreaRepository.findByIdAndCompanyStoreId(areaId, storeId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> storeZoneService.getAllZones(
                            companyId, companyCountryId, regionId, zoneId, storeId, areaId, false))
                    .isInstanceOf(StoreAreaNotFoundException.class)
                    .hasMessage("Store area not found with id: " + areaId);

            verify(storeZoneRepository, never())
                    .findByStoreAreaIdAndEnabledTrueOrderByDisplayOrderAscZoneCodeAsc(any());
        }
    }

    @Nested
    @DisplayName("getZoneById (nested)")
    class GetZoneByIdTests {

        @Test
        @DisplayName("should return the zone when found inside the area")
        void getZoneById_Success() {
            mockAreaResolution();
            when(storeZoneRepository.findByIdAndStoreAreaId(storeZoneId, areaId))
                    .thenReturn(Optional.of(testStoreZone));

            var result = storeZoneService.getZoneById(
                    companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId);

            assertThat(result.id()).isEqualTo(storeZoneId);
            assertThat(result.zoneCode()).isEqualTo("Z01");
            assertThat(result.zoneName()).isEqualTo("Pasillo");
            assertThat(result.description()).isEqualTo("Zona de pasillo");
            assertThat(result.displayOrder()).isEqualTo(1);
            assertThat(result.enabled()).isTrue();
        }

        @Test
        @DisplayName("should throw StoreZoneNotFoundException when the zone does not exist")
        void getZoneById_NotFound() {
            mockAreaResolution();
            when(storeZoneRepository.findByIdAndStoreAreaId(storeZoneId, areaId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> storeZoneService.getZoneById(
                            companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId))
                    .isInstanceOf(StoreZoneNotFoundException.class)
                    .hasMessage("Store zone not found with id: " + storeZoneId);
        }

        @Test
        @DisplayName("should propagate CompanyStoreNotFoundException from the store resolution")
        void getZoneById_StoreNotFound() {
            mockStoreResolution();
            when(companyStoreRepository.findByIdAndCompanyZoneId(storeId, zoneId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> storeZoneService.getZoneById(
                            companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId))
                    .isInstanceOf(CompanyStoreNotFoundException.class)
                    .hasMessage("Store not found with id: " + storeId);
        }
    }

    @Nested
    @DisplayName("getZoneById (flat)")
    class GetZoneByIdFlatTests {

        @Test
        @DisplayName("should return the zone with its resolved chain ids")
        void getZoneByIdFlat_Success() {
            when(storeZoneRepository.findById(storeZoneId)).thenReturn(Optional.of(testStoreZone));

            var result = storeZoneService.getZoneById(storeZoneId);

            assertThat(result.id()).isEqualTo(storeZoneId);
            assertThat(result.storeAreaId()).isEqualTo(areaId);
            assertThat(result.companyStoreId()).isEqualTo(storeId);
            assertThat(result.companyId()).isEqualTo(companyId);
            assertThat(result.companyCountryId()).isEqualTo(companyCountryId);
            assertThat(result.regionId()).isEqualTo(regionId);
            assertThat(result.zoneId()).isEqualTo(zoneId);
            assertThat(result.zoneCode()).isEqualTo("Z01");
            assertThat(result.zoneName()).isEqualTo("Pasillo");

            verify(currentUserContext).verifyCompanyStoreAccess(companyId, companyCountryId, regionId, zoneId, storeId);
        }

        @Test
        @DisplayName("should throw StoreZoneNotFoundException and never authorize when the zone does not exist")
        void getZoneByIdFlat_NotFound() {
            when(storeZoneRepository.findById(storeZoneId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> storeZoneService.getZoneById(storeZoneId))
                    .isInstanceOf(StoreZoneNotFoundException.class)
                    .hasMessage("Store zone not found with id: " + storeZoneId);

            verify(currentUserContext, never()).verifyCompanyStoreAccess(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("should mask a denied flat lookup as the identical not-found error of the same id")
        void getZoneByIdFlat_AccessDenied() {
            // Genuinely missing: findById returns nothing.
            when(storeZoneRepository.findById(storeZoneId)).thenReturn(Optional.empty());
            var genuinelyMissing = catchThrowable(() -> storeZoneService.getZoneById(storeZoneId));

            // Out of scope: the zone exists, but the resolved store is not accessible to the user.
            when(storeZoneRepository.findById(storeZoneId)).thenReturn(Optional.of(testStoreZone));
            doThrow(new AccessDeniedException("Access denied"))
                    .when(currentUserContext)
                    .verifyCompanyStoreAccess(companyId, companyCountryId, regionId, zoneId, storeId);
            when(currentUserContext.getUsername()).thenReturn("jdoe");
            var denied = catchThrowable(() -> storeZoneService.getZoneById(storeZoneId));

            // The two responses must be indistinguishable: same type, same message, same status.
            assertThat(genuinelyMissing).isInstanceOf(StoreZoneNotFoundException.class);
            assertThat(denied).isInstanceOf(StoreZoneNotFoundException.class);
            assertThat(denied.getClass()).isEqualTo(genuinelyMissing.getClass());
            assertThat(denied.getMessage()).isEqualTo(genuinelyMissing.getMessage());
            assertThat(denied.getMessage()).isEqualTo("Store zone not found with id: " + storeZoneId);
            assertThat(capturedLogs())
                    .contains("StoreZone access denied")
                    .contains("id=" + storeZoneId)
                    .contains("actor=jdoe");
        }
    }

    @Nested
    @DisplayName("createZone")
    class CreateZoneTests {

        @Test
        @DisplayName("should create an enabled zone and log the outcome")
        void createZone_Success() {
            mockAreaResolution();
            when(storeZoneRepository.existsByStoreAreaIdAndZoneCode(areaId, "Z02"))
                    .thenReturn(false);
            when(storeZoneRepository.saveAndFlush(any(StoreZone.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            var result = storeZoneService.createZone(
                    companyId, companyCountryId, regionId, zoneId, storeId, areaId, createRequest);

            assertThat(result.zoneCode()).isEqualTo("Z02");
            assertThat(result.zoneName()).isEqualTo("Estante");
            assertThat(result.description()).isEqualTo("Zona de estantes");
            assertThat(result.displayOrder()).isEqualTo(2);
            assertThat(result.storeAreaId()).isEqualTo(areaId);
            assertThat(result.enabled()).isTrue();

            var captor = org.mockito.ArgumentCaptor.forClass(StoreZone.class);
            verify(storeZoneRepository).saveAndFlush(captor.capture());
            assertThat(captor.getValue().getStoreArea()).isEqualTo(testArea);
            assertThat(captor.getValue().getEnabled()).isTrue();
        }

        @Test
        @DisplayName("should throw DuplicateStoreZoneException when the zone code already exists in the area")
        void createZone_DuplicateCode() {
            mockAreaResolution();
            when(storeZoneRepository.existsByStoreAreaIdAndZoneCode(areaId, "Z02"))
                    .thenReturn(true);

            assertThatThrownBy(() -> storeZoneService.createZone(
                            companyId, companyCountryId, regionId, zoneId, storeId, areaId, createRequest))
                    .isInstanceOf(DuplicateStoreZoneException.class)
                    .hasMessage("Store zone with code 'Z02' already exists in this area");

            verify(storeZoneRepository, never()).saveAndFlush(any(StoreZone.class));
        }

        @Test
        @DisplayName("should throw DisabledParentException when the parent area is disabled")
        void createZone_DisabledArea_ThrowsException() {
            testArea.setEnabled(false);
            mockAreaResolution();

            assertThatThrownBy(() -> storeZoneService.createZone(
                            companyId, companyCountryId, regionId, zoneId, storeId, areaId, createRequest))
                    .isInstanceOf(DisabledParentException.class)
                    .hasMessage("Cannot create a store zone: store area with id " + areaId + " is disabled");

            verify(storeZoneRepository, never()).existsByStoreAreaIdAndZoneCode(any(), any());
            verify(storeZoneRepository, never()).saveAndFlush(any(StoreZone.class));
        }

        @Test
        @DisplayName("should throw DisabledParentException when the area is enabled but its store is disabled")
        void createZone_EnabledAreaDisabledStore_ThrowsException() {
            testStore.setEnabled(false);
            mockAreaResolution();

            assertThatThrownBy(() -> storeZoneService.createZone(
                            companyId, companyCountryId, regionId, zoneId, storeId, areaId, createRequest))
                    .isInstanceOf(DisabledParentException.class)
                    .hasMessage("Cannot create a store zone: store with id " + storeId + " is disabled");

            verify(storeZoneRepository, never()).existsByStoreAreaIdAndZoneCode(any(), any());
            verify(storeZoneRepository, never()).saveAndFlush(any(StoreZone.class));
        }

        @Test
        @DisplayName("should propagate DataIntegrityViolationException from save instead of swallowing it")
        void createZone_DataIntegrityViolation_Propagates() {
            mockAreaResolution();
            when(storeZoneRepository.existsByStoreAreaIdAndZoneCode(areaId, "Z02"))
                    .thenReturn(false);
            when(storeZoneRepository.saveAndFlush(any(StoreZone.class)))
                    .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

            assertThatThrownBy(() -> storeZoneService.createZone(
                            companyId, companyCountryId, regionId, zoneId, storeId, areaId, createRequest))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("duplicate key value violates unique constraint");
        }
    }

    @Nested
    @DisplayName("updateZone")
    class UpdateZoneTests {

        @Test
        @DisplayName("should update the provided fields")
        void updateZone_Success() {
            mockAreaResolution();
            when(storeZoneRepository.findByIdAndStoreAreaId(storeZoneId, areaId))
                    .thenReturn(Optional.of(testStoreZone));
            when(storeZoneRepository.existsByStoreAreaIdAndZoneCodeAndIdNot(areaId, "Z03", storeZoneId))
                    .thenReturn(false);
            when(storeZoneRepository.saveAndFlush(any(StoreZone.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            var result = storeZoneService.updateZone(
                    companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId, updateRequest);

            assertThat(result.zoneCode()).isEqualTo("Z03");
            assertThat(result.zoneName()).isEqualTo("Pasillo renovado");
            assertThat(result.description()).isEqualTo("Actualizada");
            assertThat(result.displayOrder()).isEqualTo(3);
        }

        @Test
        @DisplayName("should skip the uniqueness check when the code is unchanged")
        void updateZone_UnchangedCode_SkipsUniquenessCheck() {
            mockAreaResolution();
            when(storeZoneRepository.findByIdAndStoreAreaId(storeZoneId, areaId))
                    .thenReturn(Optional.of(testStoreZone));
            when(storeZoneRepository.saveAndFlush(any(StoreZone.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            var result = storeZoneService.updateZone(
                    companyId,
                    companyCountryId,
                    regionId,
                    zoneId,
                    storeId,
                    areaId,
                    storeZoneId,
                    new UpdateStoreZoneRequest("Z01", "Pasillo renombrado", null, null));

            assertThat(result.zoneCode()).isEqualTo("Z01");
            assertThat(result.zoneName()).isEqualTo("Pasillo renombrado");
            assertThat(result.description()).isEqualTo("Zona de pasillo");
            assertThat(result.displayOrder()).isEqualTo(1);
            verify(storeZoneRepository, never()).existsByStoreAreaIdAndZoneCodeAndIdNot(any(), any(), any());
        }

        @Test
        @DisplayName("should throw DuplicateStoreZoneException when the new code collides")
        void updateZone_DuplicateCode() {
            mockAreaResolution();
            when(storeZoneRepository.findByIdAndStoreAreaId(storeZoneId, areaId))
                    .thenReturn(Optional.of(testStoreZone));
            when(storeZoneRepository.existsByStoreAreaIdAndZoneCodeAndIdNot(areaId, "Z03", storeZoneId))
                    .thenReturn(true);

            assertThatThrownBy(() -> storeZoneService.updateZone(
                            companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId, updateRequest))
                    .isInstanceOf(DuplicateStoreZoneException.class)
                    .hasMessage("Store zone with code 'Z03' already exists in this area");

            verify(storeZoneRepository, never()).saveAndFlush(any(StoreZone.class));
        }

        @Test
        @DisplayName("should throw StoreZoneNotFoundException when the zone does not exist")
        void updateZone_NotFound() {
            mockAreaResolution();
            when(storeZoneRepository.findByIdAndStoreAreaId(storeZoneId, areaId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> storeZoneService.updateZone(
                            companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId, updateRequest))
                    .isInstanceOf(StoreZoneNotFoundException.class)
                    .hasMessage("Store zone not found with id: " + storeZoneId);
        }
    }

    @Nested
    @DisplayName("deleteZone")
    class DeleteZoneTests {

        @Test
        @DisplayName("should soft-delete by setting enabled=false and keeping the row")
        void deleteZone_SoftDeletes() {
            mockAreaResolution();
            when(storeZoneRepository.findByIdAndStoreAreaId(storeZoneId, areaId))
                    .thenReturn(Optional.of(testStoreZone));
            when(storeZoneRepository.save(any(StoreZone.class))).thenAnswer(invocation -> invocation.getArgument(0));

            storeZoneService.deleteZone(companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId);

            var captor = org.mockito.ArgumentCaptor.forClass(StoreZone.class);
            verify(storeZoneRepository).save(captor.capture());
            assertThat(captor.getValue().getEnabled()).isFalse();
            verify(storeZoneRepository, never()).deleteById(any());
            verify(storeZoneRepository, never()).delete(any());
        }

        @Test
        @DisplayName("should record the acting username in the soft-delete audit log")
        void deleteZone_LogsActingUsername() {
            mockAreaResolution();
            when(storeZoneRepository.findByIdAndStoreAreaId(storeZoneId, areaId))
                    .thenReturn(Optional.of(testStoreZone));
            when(storeZoneRepository.save(any(StoreZone.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(currentUserContext.getUsername()).thenReturn("jdoe");

            storeZoneService.deleteZone(companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId);

            assertThat(capturedLogs())
                    .contains("StoreZone soft-deleted")
                    .contains("id=" + storeZoneId)
                    .contains("actor=jdoe");
        }

        @Test
        @DisplayName("should throw StoreZoneNotFoundException when the zone does not exist")
        void deleteZone_NotFound() {
            mockAreaResolution();
            when(storeZoneRepository.findByIdAndStoreAreaId(storeZoneId, areaId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> storeZoneService.deleteZone(
                            companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId))
                    .isInstanceOf(StoreZoneNotFoundException.class)
                    .hasMessage("Store zone not found with id: " + storeZoneId);

            verify(storeZoneRepository, never()).save(any(StoreZone.class));
        }
    }

    @Nested
    @DisplayName("enableZone")
    class EnableZoneTests {

        @Test
        @DisplayName("should re-enable a soft-deleted zone")
        void enableZone_Success() {
            mockAreaResolution();
            testStoreZone.setEnabled(false);
            when(storeZoneRepository.findByIdAndStoreAreaId(storeZoneId, areaId))
                    .thenReturn(Optional.of(testStoreZone));
            when(storeZoneRepository.saveAndFlush(any(StoreZone.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            StoreZoneResponse result = storeZoneService.enableZone(
                    companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId);

            assertThat(result.enabled()).isTrue();
            var captor = org.mockito.ArgumentCaptor.forClass(StoreZone.class);
            verify(storeZoneRepository).saveAndFlush(captor.capture());
            assertThat(captor.getValue().getEnabled()).isTrue();
        }

        @Test
        @DisplayName("should throw StoreZoneNotFoundException when the zone does not exist")
        void enableZone_NotFound() {
            mockAreaResolution();
            when(storeZoneRepository.findByIdAndStoreAreaId(storeZoneId, areaId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> storeZoneService.enableZone(
                            companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId))
                    .isInstanceOf(StoreZoneNotFoundException.class)
                    .hasMessage("Store zone not found with id: " + storeZoneId);
        }

        @Test
        @DisplayName("should throw DisabledParentException when the parent area is disabled")
        void enableZone_DisabledArea_ThrowsException() {
            testArea.setEnabled(false);
            mockAreaResolution();

            assertThatThrownBy(() -> storeZoneService.enableZone(
                            companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId))
                    .isInstanceOf(DisabledParentException.class)
                    .hasMessage("Cannot re-enable a store zone: store area with id " + areaId + " is disabled");

            verify(storeZoneRepository, never()).findByIdAndStoreAreaId(any(), any());
            verify(storeZoneRepository, never()).saveAndFlush(any(StoreZone.class));
        }

        @Test
        @DisplayName("should not cascade to locations when re-enabling the zone")
        void enableZone_DoesNotCascadeToLocations() {
            mockAreaResolution();
            testStoreZone.setEnabled(false);
            when(storeZoneRepository.findByIdAndStoreAreaId(storeZoneId, areaId))
                    .thenReturn(Optional.of(testStoreZone));
            when(storeZoneRepository.saveAndFlush(any(StoreZone.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            storeZoneService.enableZone(companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId);

            // D8: re-enable is explicit and ordered (store -> area -> zone -> location); it never
            // cascades down.
            verify(storeLocationRepository, never()).findByStoreZoneIdAndEnabledTrue(any());
            verify(storeLocationRepository, never()).saveAll(any());
        }
    }

    @Nested
    @DisplayName("disableZonesOfAreas")
    class DisableZonesOfAreasTests {

        /**
         * Typed captor for the {@code saveAll(List<StoreLocation>)} argument. A raw
         * {@code forClass(List.class)} is the only way to bind the generic type, so the unchecked
         * conversion is confined to this helper instead of every assertion site.
         */
        @SuppressWarnings("unchecked")
        private static org.mockito.ArgumentCaptor<List<StoreLocation>> locationListCaptor() {
            return org.mockito.ArgumentCaptor.forClass(List.class);
        }

        @Test
        @DisplayName("should disable every enabled zone of the area and their enabled locations")
        void disableZonesOfAreas_DisablesZonesAndTheirLocations() {
            var firstZoneId = UUID.randomUUID();
            var secondZoneId = UUID.randomUUID();
            var firstZone = StoreZone.builder()
                    .id(firstZoneId)
                    .storeArea(testArea)
                    .zoneCode("Z01")
                    .zoneName("Pasillo")
                    .enabled(true)
                    .build();
            var secondZone = StoreZone.builder()
                    .id(secondZoneId)
                    .storeArea(testArea)
                    .zoneCode("Z02")
                    .zoneName("Estante")
                    .enabled(true)
                    .build();
            var firstLocation = StoreLocation.builder()
                    .id(UUID.randomUUID())
                    .storeZone(firstZone)
                    .locationCode("L01")
                    .locationName("Estante")
                    .enabled(true)
                    .build();
            var secondLocation = StoreLocation.builder()
                    .id(UUID.randomUUID())
                    .storeZone(secondZone)
                    .locationCode("L01")
                    .locationName("Estante")
                    .enabled(true)
                    .build();

            when(storeZoneRepository.findByStoreAreaIdAndEnabledTrue(areaId))
                    .thenReturn(List.of(firstZone, secondZone));
            when(storeLocationRepository.findByStoreZoneIdAndEnabledTrue(firstZoneId))
                    .thenReturn(List.of(firstLocation));
            when(storeLocationRepository.findByStoreZoneIdAndEnabledTrue(secondZoneId))
                    .thenReturn(List.of(secondLocation));
            when(storeZoneRepository.save(any(StoreZone.class))).thenAnswer(invocation -> invocation.getArgument(0));

            var disabledZones = storeZoneService.disableZonesOfAreas(List.of(testArea));

            assertThat(disabledZones).isEqualTo(2);

            var zoneCaptor = org.mockito.ArgumentCaptor.forClass(StoreZone.class);
            verify(storeZoneRepository, times(2)).save(zoneCaptor.capture());
            assertThat(zoneCaptor.getAllValues())
                    .hasSize(2)
                    .allSatisfy(zone -> assertThat(zone.getEnabled()).isFalse());

            var locationCaptor = locationListCaptor();
            verify(storeLocationRepository, times(2)).saveAll(locationCaptor.capture());
            assertThat(locationCaptor.getAllValues())
                    .flatExtracting(saved -> saved)
                    .hasSize(2)
                    .allSatisfy(location -> assertThat(location.getEnabled()).isFalse());
        }

        @Test
        @DisplayName("should return zero and persist nothing when the area has no enabled zones")
        void disableZonesOfAreas_NoEnabledZones_ReturnsZero() {
            when(storeZoneRepository.findByStoreAreaIdAndEnabledTrue(areaId)).thenReturn(List.of());

            var disabledZones = storeZoneService.disableZonesOfAreas(List.of(testArea));

            assertThat(disabledZones).isZero();
            verify(storeZoneRepository, never()).save(any(StoreZone.class));
            verify(storeLocationRepository, never()).findByStoreZoneIdAndEnabledTrue(any());
            verify(storeLocationRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("should sum the disabled zones across areas and query each area by its own id")
        void disableZonesOfAreas_MultipleAreas() {
            var secondAreaId = UUID.randomUUID();
            var secondArea = StoreArea.builder()
                    .id(secondAreaId)
                    .companyStore(testStore)
                    .areaCode("A02")
                    .areaName("Piso de venta")
                    .enabled(true)
                    .build();
            var firstAreaZone = StoreZone.builder()
                    .id(UUID.randomUUID())
                    .storeArea(testArea)
                    .zoneCode("Z01")
                    .zoneName("Pasillo")
                    .enabled(true)
                    .build();
            var secondAreaZone = StoreZone.builder()
                    .id(UUID.randomUUID())
                    .storeArea(secondArea)
                    .zoneCode("Z02")
                    .zoneName("Estante")
                    .enabled(true)
                    .build();

            when(storeZoneRepository.findByStoreAreaIdAndEnabledTrue(areaId)).thenReturn(List.of(firstAreaZone));
            when(storeZoneRepository.findByStoreAreaIdAndEnabledTrue(secondAreaId))
                    .thenReturn(List.of(secondAreaZone));
            when(storeZoneRepository.save(any(StoreZone.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(storeLocationRepository.findByStoreZoneIdAndEnabledTrue(any())).thenReturn(List.of());

            var disabledZones = storeZoneService.disableZonesOfAreas(List.of(testArea, secondArea));

            assertThat(disabledZones).isEqualTo(2);
            verify(storeZoneRepository).findByStoreAreaIdAndEnabledTrue(areaId);
            verify(storeZoneRepository).findByStoreAreaIdAndEnabledTrue(secondAreaId);
            verify(storeZoneRepository, times(2)).save(any(StoreZone.class));
        }

        @Test
        @DisplayName("should disable the zone and its enabled locations through the shared cascade")
        void deleteZone_AlsoDisablesItsLocations() {
            mockAreaResolution();
            var enabledLocation = StoreLocation.builder()
                    .id(UUID.randomUUID())
                    .storeZone(testStoreZone)
                    .locationCode("L01")
                    .locationName("Estante")
                    .enabled(true)
                    .build();
            when(storeZoneRepository.findByIdAndStoreAreaId(storeZoneId, areaId))
                    .thenReturn(Optional.of(testStoreZone));
            when(storeZoneRepository.save(any(StoreZone.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(storeLocationRepository.findByStoreZoneIdAndEnabledTrue(storeZoneId))
                    .thenReturn(List.of(enabledLocation));

            storeZoneService.deleteZone(companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId);

            var zoneCaptor = org.mockito.ArgumentCaptor.forClass(StoreZone.class);
            verify(storeZoneRepository).save(zoneCaptor.capture());
            assertThat(zoneCaptor.getValue().getEnabled()).isFalse();

            // The single-zone delete and the area/store cascade share disableZone: both persist the
            // zone and then its still-enabled locations through the same code path.
            var locationCaptor = locationListCaptor();
            verify(storeLocationRepository).findByStoreZoneIdAndEnabledTrue(storeZoneId);
            verify(storeLocationRepository).saveAll(locationCaptor.capture());
            assertThat(locationCaptor.getValue())
                    .hasSize(1)
                    .allSatisfy(location -> assertThat(location.getEnabled()).isFalse());
        }
    }
}
