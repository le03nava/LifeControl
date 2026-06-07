package com.lifecontrol.api.store.service;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.exception.CompanyCountryNotFoundException;
import com.lifecontrol.api.company.exception.CompanyNotFoundException;
import com.lifecontrol.api.company.exception.CompanyRegionNotFoundException;
import com.lifecontrol.api.company.exception.CompanyZoneNotFoundException;
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
import com.lifecontrol.api.store.dto.CompanyStoreResponse;
import com.lifecontrol.api.store.dto.CreateCompanyStoreRequest;
import com.lifecontrol.api.store.dto.UpdateCompanyStoreRequest;
import com.lifecontrol.api.store.event.CompanyStoreCreatedEvent;
import com.lifecontrol.api.store.exception.CompanyStoreNotFoundException;
import com.lifecontrol.api.store.exception.DuplicateCompanyStoreException;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.model.CompanyStoreAddress;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.context.ApplicationEventPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CompanyStoreService Tests")
class CompanyStoreServiceTest {

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
    private CountryRepository countryRepository;
    @Mock
    private CurrentUserContext currentUserContext;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private CompanyStoreService companyStoreService;

    private UUID companyId;
    private UUID companyCountryId;
    private UUID regionId;
    private UUID zoneId;
    private UUID storeId;
    private UUID addressId;
    private Company testCompany;
    private Country testCountry;
    private CompanyCountry testCompanyCountry;
    private CompanyRegion testRegion;
    private CompanyZone testZone;
    private CompanyStore testStore;
    private CompanyStoreAddress testAddress;
    private CreateCompanyStoreRequest createWithAddressRequest;
    private CreateCompanyStoreRequest createWithoutAddressRequest;
    private UpdateCompanyStoreRequest updateRequest;
    private LocalDateTime now;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        companyCountryId = UUID.randomUUID();
        regionId = UUID.randomUUID();
        zoneId = UUID.randomUUID();
        storeId = UUID.randomUUID();
        addressId = UUID.randomUUID();
        now = LocalDateTime.now();

        testCompany = Company.builder()
                .id(companyId)
                .companyKey("1")
                .companyName("Test Company")
                .rfc("XAXX010101000")
                .enabled(true)
                .build();

        testCountry = Country.builder()
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

        testAddress = CompanyStoreAddress.builder()
                .id(addressId)
                .street("Calle Principal")
                .streetNumber("123")
                .neighborhood("Centro")
                .zipCode("12345")
                .city("Ciudad de México")
                .state("CDMX")
                .country(testCountry)
                .enabled(true)
                .build();

        testStore = CompanyStore.builder()
                .id(storeId)
                .companyZone(testZone)
                .storeName("Tienda Principal")
                .email("tienda@example.com")
                .phoneNumber("555-1234")
                .address(testAddress)
                .enabled(true)
                .build();

        createWithAddressRequest = new CreateCompanyStoreRequest(
                "Tienda Nueva", "nueva@example.com", "555-5678",
                "Otra Calle", "456", "A", "Colonia Nueva", "67890",
                "Monterrey", "NL", testCountry.getId());

        createWithoutAddressRequest = new CreateCompanyStoreRequest(
                "Tienda Nueva", "nueva@example.com", "555-5678",
                null, null, null, null, null,
                null, null, null);

        updateRequest = new UpdateCompanyStoreRequest(
                "Tienda Actualizada", "actualizada@example.com", "555-9999",
                "Calle Nueva", "789", null, "Col Nueva", "54321",
                "Guadalajara", "JAL", testCountry.getId());
    }

    private void mockZoneResolution() {
        when(companyRepository.findById(companyId)).thenReturn(Optional.of(testCompany));
        when(companyCountryRepository.findByCompanyIdAndId(companyId, companyCountryId))
                .thenReturn(Optional.of(testCompanyCountry));
        when(companyRegionRepository.findByIdAndCompanyCountryId(regionId, companyCountryId))
                .thenReturn(Optional.of(testRegion));
        when(companyZoneRepository.findByIdAndCompanyRegionId(zoneId, regionId))
                .thenReturn(Optional.of(testZone));
    }

    @Nested
    @DisplayName("getAllStores")
    class GetAllStoresTests {

        @Test
        @DisplayName("should return enabled stores when includeDisabled is false")
        void getAllStores_ExcludeDisabled_ReturnsOnlyEnabled() {
            // Arrange
            mockZoneResolution();

            var enabledStore = CompanyStore.builder()
                    .id(storeId)
                    .companyZone(testZone)
                    .storeName("Tienda Principal")
                    .enabled(true)
                    .build();
            var disabledStore = CompanyStore.builder()
                    .id(UUID.randomUUID())
                    .companyZone(testZone)
                    .storeName("Tienda Secundaria")
                    .enabled(false)
                    .build();

            when(companyStoreRepository.findByCompanyZoneIdAndEnabledTrue(zoneId))
                    .thenReturn(List.of(enabledStore));

            // Act
            List<CompanyStoreResponse> result = companyStoreService.getAllStores(
                    companyId, companyCountryId, regionId, zoneId, false);

            // Assert
            assertThat(result).hasSize(1);
            assertThat(result.get(0).storeName()).isEqualTo("Tienda Principal");
            verify(companyStoreRepository).findByCompanyZoneIdAndEnabledTrue(zoneId);
            verify(companyStoreRepository, never()).findByCompanyZoneId(any());
        }

        @Test
        @DisplayName("should return all stores when includeDisabled is true")
        void getAllStores_IncludeDisabled_ReturnsAll() {
            // Arrange
            mockZoneResolution();

            var enabledStore = CompanyStore.builder()
                    .id(storeId)
                    .companyZone(testZone)
                    .storeName("Tienda Principal")
                    .enabled(true)
                    .build();
            var disabledStore = CompanyStore.builder()
                    .id(UUID.randomUUID())
                    .companyZone(testZone)
                    .storeName("Tienda Secundaria")
                    .enabled(false)
                    .build();

            when(companyStoreRepository.findByCompanyZoneId(zoneId))
                    .thenReturn(List.of(enabledStore, disabledStore));

            // Act
            List<CompanyStoreResponse> result = companyStoreService.getAllStores(
                    companyId, companyCountryId, regionId, zoneId, true);

            // Assert
            assertThat(result).hasSize(2);
            verify(companyStoreRepository).findByCompanyZoneId(zoneId);
        }

        @Test
        @DisplayName("should throw CompanyZoneNotFoundException when zone hierarchy invalid")
        void getAllStores_ZoneNotFound_ThrowsException() {
            // Arrange
            when(companyRepository.findById(companyId)).thenReturn(Optional.of(testCompany));
            when(companyCountryRepository.findByCompanyIdAndId(companyId, companyCountryId))
                    .thenReturn(Optional.of(testCompanyCountry));
            when(companyRegionRepository.findByIdAndCompanyCountryId(regionId, companyCountryId))
                    .thenReturn(Optional.of(testRegion));
            when(companyZoneRepository.findByIdAndCompanyRegionId(zoneId, regionId))
                    .thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> companyStoreService.getAllStores(
                    companyId, companyCountryId, regionId, zoneId, false))
                    .isInstanceOf(CompanyZoneNotFoundException.class);
        }

        @Test
        @DisplayName("should filter by store IDs when user has store role")
        void getAllStores_StoreRole_FiltersByStoreIds() {
            // Arrange
            mockZoneResolution();
            UUID assignedStoreId = UUID.randomUUID();
            when(currentUserContext.hasCompanyStoreRole()).thenReturn(true);
            when(currentUserContext.getCompanyStoreIds()).thenReturn(Set.of(assignedStoreId));

            var store = CompanyStore.builder()
                    .id(assignedStoreId)
                    .companyZone(testZone)
                    .storeName("Mi Tienda")
                    .enabled(true)
                    .build();
            when(companyStoreRepository.findByIdInAndCompanyZoneId(Set.of(assignedStoreId), zoneId))
                    .thenReturn(List.of(store));

            // Act
            List<CompanyStoreResponse> result = companyStoreService.getAllStores(
                    companyId, companyCountryId, regionId, zoneId, false);

            // Assert
            assertThat(result).hasSize(1);
            assertThat(result.get(0).storeName()).isEqualTo("Mi Tienda");
            assertThat(result.get(0).id()).isEqualTo(assignedStoreId);
            verify(companyStoreRepository).findByIdInAndCompanyZoneId(Set.of(assignedStoreId), zoneId);
        }

        @Test
        @DisplayName("should filter by zone when user has zone role")
        void getAllStores_ZoneRole_UsesZoneFilter() {
            // Arrange
            mockZoneResolution();
            when(currentUserContext.hasCompanyStoreRole()).thenReturn(false);
            when(currentUserContext.hasCompanyZoneRole()).thenReturn(true);

            var store = CompanyStore.builder()
                    .id(storeId)
                    .companyZone(testZone)
                    .storeName("Tienda Zona")
                    .enabled(true)
                    .build();
            when(companyStoreRepository.findByCompanyZoneIdAndEnabledTrue(zoneId))
                    .thenReturn(List.of(store));

            // Act
            List<CompanyStoreResponse> result = companyStoreService.getAllStores(
                    companyId, companyCountryId, regionId, zoneId, false);

            // Assert
            assertThat(result).hasSize(1);
            assertThat(result.get(0).storeName()).isEqualTo("Tienda Zona");
            verify(companyStoreRepository).findByCompanyZoneIdAndEnabledTrue(zoneId);
        }
    }

    @Nested
    @DisplayName("getStoreById")
    class GetStoreByIdTests {

        @Test
        @DisplayName("should return store when found")
        void getStoreById_Success() {
            // Arrange
            mockZoneResolution();
            when(companyStoreRepository.findByIdAndCompanyZoneId(storeId, zoneId))
                    .thenReturn(Optional.of(testStore));

            // Act
            CompanyStoreResponse result = companyStoreService.getStoreById(
                    companyId, companyCountryId, regionId, zoneId, storeId);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.storeName()).isEqualTo("Tienda Principal");
            assertThat(result.email()).isEqualTo("tienda@example.com");
            assertThat(result.companyId()).isEqualTo(companyId);
            assertThat(result.companyCountryId()).isEqualTo(companyCountryId);
            assertThat(result.regionId()).isEqualTo(regionId);
            assertThat(result.zoneId()).isEqualTo(zoneId);
        }

        @Test
        @DisplayName("should throw CompanyStoreNotFoundException when not found")
        void getStoreById_NotFound_ThrowsException() {
            // Arrange
            mockZoneResolution();
            when(companyStoreRepository.findByIdAndCompanyZoneId(storeId, zoneId))
                    .thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> companyStoreService.getStoreById(
                    companyId, companyCountryId, regionId, zoneId, storeId))
                    .isInstanceOf(CompanyStoreNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("createStore")
    class CreateStoreTests {

        @Test
        @DisplayName("should create store with address when address fields provided")
        void createStore_WithAddress_Success() {
            // Arrange
            mockZoneResolution();
            when(companyStoreRepository.existsByStoreNameAndCompanyZoneId(
                    createWithAddressRequest.storeName(), zoneId))
                    .thenReturn(false);
            when(countryRepository.findById(createWithAddressRequest.countryId()))
                    .thenReturn(Optional.of(testCountry));
            when(companyStoreRepository.save(any(CompanyStore.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            CompanyStoreResponse result = companyStoreService.createStore(
                    companyId, companyCountryId, regionId, zoneId, createWithAddressRequest);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.storeName()).isEqualTo("Tienda Nueva");
            assertThat(result.email()).isEqualTo("nueva@example.com");
            assertThat(result.phoneNumber()).isEqualTo("555-5678");
            assertThat(result.street()).isEqualTo("Otra Calle");
            assertThat(result.streetNumber()).isEqualTo("456");
            assertThat(result.enabled()).isTrue();
            verify(companyStoreRepository).save(any(CompanyStore.class));
            verify(eventPublisher).publishEvent(any(CompanyStoreCreatedEvent.class));
        }

        @Test
        @DisplayName("should create store without address when address fields are null")
        void createStore_WithoutAddress_Success() {
            // Arrange
            mockZoneResolution();
            when(companyStoreRepository.existsByStoreNameAndCompanyZoneId(
                    createWithoutAddressRequest.storeName(), zoneId))
                    .thenReturn(false);
            when(companyStoreRepository.save(any(CompanyStore.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            CompanyStoreResponse result = companyStoreService.createStore(
                    companyId, companyCountryId, regionId, zoneId, createWithoutAddressRequest);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.storeName()).isEqualTo("Tienda Nueva");
            assertThat(result.street()).isNull();
            assertThat(result.addressId()).isNull();
            assertThat(result.enabled()).isTrue();
            verify(companyStoreRepository).save(any(CompanyStore.class));
            verify(eventPublisher).publishEvent(any(CompanyStoreCreatedEvent.class));
        }

        @Test
        @DisplayName("should throw DuplicateCompanyStoreException when name exists in zone")
        void createStore_DuplicateName_ThrowsException() {
            // Arrange
            mockZoneResolution();
            when(companyStoreRepository.existsByStoreNameAndCompanyZoneId(
                    createWithAddressRequest.storeName(), zoneId))
                    .thenReturn(true);

            // Act & Assert
            assertThatThrownBy(() -> companyStoreService.createStore(
                    companyId, companyCountryId, regionId, zoneId, createWithAddressRequest))
                    .isInstanceOf(DuplicateCompanyStoreException.class);
            verify(companyStoreRepository, never()).save(any());
            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("should throw CompanyZoneNotFoundException when zone hierarchy invalid")
        void createStore_ZoneNotFound_ThrowsException() {
            // Arrange
            when(companyRepository.findById(companyId)).thenReturn(Optional.of(testCompany));
            when(companyCountryRepository.findByCompanyIdAndId(companyId, companyCountryId))
                    .thenReturn(Optional.of(testCompanyCountry));
            when(companyRegionRepository.findByIdAndCompanyCountryId(regionId, companyCountryId))
                    .thenReturn(Optional.of(testRegion));
            when(companyZoneRepository.findByIdAndCompanyRegionId(zoneId, regionId))
                    .thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> companyStoreService.createStore(
                    companyId, companyCountryId, regionId, zoneId, createWithAddressRequest))
                    .isInstanceOf(CompanyZoneNotFoundException.class);
            verify(companyStoreRepository, never()).save(any());
            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("should throw AccessDeniedException when user has store role")
        void createStore_StoreRole_ThrowsAccessDenied() {
            // Arrange
            when(currentUserContext.hasCompanyStoreRole()).thenReturn(true);

            // Act & Assert
            assertThatThrownBy(() -> companyStoreService.createStore(
                    companyId, companyCountryId, regionId, zoneId, createWithAddressRequest))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessage("Store-scoped users cannot create stores");
            verify(companyStoreRepository, never()).save(any());
            verify(eventPublisher, never()).publishEvent(any());
        }
    }

    @Nested
    @DisplayName("updateStore")
    class UpdateStoreTests {

        @Test
        @DisplayName("should update store fields and address")
        void updateStore_Success() {
            // Arrange
            mockZoneResolution();
            when(companyStoreRepository.findByIdAndCompanyZoneId(storeId, zoneId))
                    .thenReturn(Optional.of(testStore));
            // storeName changes from "Tienda Principal" to "Tienda Actualizada" — check duplicate
            when(companyStoreRepository.existsByStoreNameAndCompanyZoneIdAndIdNot(
                    updateRequest.storeName(), zoneId, storeId))
                    .thenReturn(false);
            when(countryRepository.findById(updateRequest.countryId()))
                    .thenReturn(Optional.of(testCountry));
            when(companyStoreRepository.save(any(CompanyStore.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            CompanyStoreResponse result = companyStoreService.updateStore(
                    companyId, companyCountryId, regionId, zoneId, storeId, updateRequest);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.storeName()).isEqualTo("Tienda Actualizada");
            assertThat(result.email()).isEqualTo("actualizada@example.com");
            assertThat(result.phoneNumber()).isEqualTo("555-9999");
            assertThat(result.street()).isEqualTo("Calle Nueva");
            assertThat(result.streetNumber()).isEqualTo("789");
            verify(companyStoreRepository).save(any(CompanyStore.class));
        }

        @Test
        @DisplayName("should throw CompanyStoreNotFoundException when store not found")
        void updateStore_NotFound_ThrowsException() {
            // Arrange
            mockZoneResolution();
            when(companyStoreRepository.findByIdAndCompanyZoneId(storeId, zoneId))
                    .thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> companyStoreService.updateStore(
                    companyId, companyCountryId, regionId, zoneId, storeId, updateRequest))
                    .isInstanceOf(CompanyStoreNotFoundException.class);
            verify(companyStoreRepository, never()).save(any());
        }

        @Test
        @DisplayName("should throw DuplicateCompanyStoreException when duplicate name (excluding self)")
        void updateStore_DuplicateName_ThrowsException() {
            // Arrange
            mockZoneResolution();
            when(companyStoreRepository.findByIdAndCompanyZoneId(storeId, zoneId))
                    .thenReturn(Optional.of(testStore));
            when(companyStoreRepository.existsByStoreNameAndCompanyZoneIdAndIdNot(
                    updateRequest.storeName(), zoneId, storeId))
                    .thenReturn(true);

            // Act & Assert
            assertThatThrownBy(() -> companyStoreService.updateStore(
                    companyId, companyCountryId, regionId, zoneId, storeId, updateRequest))
                    .isInstanceOf(DuplicateCompanyStoreException.class);
            verify(companyStoreRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("deleteStore")
    class DeleteStoreTests {

        @Test
        @DisplayName("should soft-delete store (set enabled=false)")
        void deleteStore_Success() {
            // Arrange
            mockZoneResolution();
            when(companyStoreRepository.findByIdAndCompanyZoneId(storeId, zoneId))
                    .thenReturn(Optional.of(testStore));
            when(companyStoreRepository.save(any(CompanyStore.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            companyStoreService.deleteStore(companyId, companyCountryId, regionId, zoneId, storeId);

            // Assert
            assertThat(testStore.getEnabled()).isFalse();
            verify(companyStoreRepository).save(testStore);
        }

        @Test
        @DisplayName("should throw CompanyStoreNotFoundException when not found")
        void deleteStore_NotFound_ThrowsException() {
            // Arrange
            mockZoneResolution();
            when(companyStoreRepository.findByIdAndCompanyZoneId(storeId, zoneId))
                    .thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> companyStoreService.deleteStore(
                    companyId, companyCountryId, regionId, zoneId, storeId))
                    .isInstanceOf(CompanyStoreNotFoundException.class);
            verify(companyStoreRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("enableStore")
    class EnableStoreTests {

        @Test
        @DisplayName("should re-enable store (set enabled=true)")
        void enableStore_Success() {
            // Arrange
            testStore.setEnabled(false); // Start as disabled
            mockZoneResolution();
            when(companyStoreRepository.findByIdAndCompanyZoneId(storeId, zoneId))
                    .thenReturn(Optional.of(testStore));
            when(companyStoreRepository.save(any(CompanyStore.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            CompanyStoreResponse result = companyStoreService.enableStore(
                    companyId, companyCountryId, regionId, zoneId, storeId);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.enabled()).isTrue();
            assertThat(result.storeName()).isEqualTo("Tienda Principal");
            verify(companyStoreRepository).save(any(CompanyStore.class));
        }

        @Test
        @DisplayName("should throw CompanyStoreNotFoundException when not found")
        void enableStore_NotFound_ThrowsException() {
            // Arrange
            mockZoneResolution();
            when(companyStoreRepository.findByIdAndCompanyZoneId(storeId, zoneId))
                    .thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> companyStoreService.enableStore(
                    companyId, companyCountryId, regionId, zoneId, storeId))
                    .isInstanceOf(CompanyStoreNotFoundException.class);
            verify(companyStoreRepository, never()).save(any());
        }
    }
}
