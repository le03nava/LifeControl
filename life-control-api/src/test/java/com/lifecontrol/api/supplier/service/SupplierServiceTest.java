package com.lifecontrol.api.supplier.service;

import com.lifecontrol.api.common.address.dto.AddressRequest;
import com.lifecontrol.api.common.address.model.Address;
import com.lifecontrol.api.country.model.Country;
import com.lifecontrol.api.country.repository.CountryRepository;
import com.lifecontrol.api.supplier.dto.SupplierRequest;
import com.lifecontrol.api.supplier.dto.SupplierResponse;
import com.lifecontrol.api.supplier.exception.DuplicateSupplierException;
import com.lifecontrol.api.supplier.exception.SupplierNotFoundException;
import com.lifecontrol.api.supplier.model.Supplier;
import com.lifecontrol.api.supplier.repository.SupplierRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SupplierService Tests")
class SupplierServiceTest {

    @Mock
    private SupplierRepository supplierRepository;

    @Mock
    private CountryRepository countryRepository;

    @InjectMocks
    private SupplierService supplierService;

    private Supplier testSupplier;
    private SupplierRequest testSupplierRequest;
    private SupplierRequest testSupplierRequestWithAddress;
    private Address testAddress;
    private UUID testSupplierId;
    private UUID testCountryId;

    @BeforeEach
    void setUp() {
        testSupplierId = UUID.randomUUID();
        testCountryId = UUID.randomUUID();

        testAddress = Address.builder()
                .street("Main St")
                .streetNumber("123")
                .internalNumber("A")
                .neighborhood("Downtown")
                .zipCode("12345")
                .city("Mexico City")
                .state("CDMX")
                .enabled(true)
                .build();

        testSupplier = Supplier.builder()
                .id(testSupplierId)
                .supplierName("Test Supplier")
                .razonSocial("Test Razon Social SA de CV")
                .rfc("XAXX010101000")
                .email("test@supplier.com")
                .phoneNumber("+1234567890")
                .internalNumber("INT-001")
                .street("Calle Principal")
                .streetNumber("123")
                .neighborhood("Centro")
                .zipCode("12345")
                .city("Ciudad de Mexico")
                .state("CDMX")
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        testSupplierRequest = new SupplierRequest(
                "Test Supplier",
                "Test Razon Social SA de CV",
                "XAXX010101000",
                "test@supplier.com",
                "+1234567890",
                "INT-001",
                null,
                true
        );

        testSupplierRequestWithAddress = new SupplierRequest(
                "Supplier With Address",
                "Addr S.A. de CV",
                "XAXX010101001",
                "addr@supplier.com",
                "+9876543210",
                "INT-002",
                new AddressRequest(
                        "Av. Reforma",
                        "456",
                        "B",
                        "Juarez",
                        "67890",
                        "Guadalajara",
                        "Jalisco",
                        testCountryId
                ),
                true
        );
    }

    @Nested
    @DisplayName("getAllSuppliers")
    class GetAllSuppliersTests {

        @Test
        @DisplayName("getAllSuppliers - should return paginated results without search")
        void getAllSuppliers_Paginated() {
            // Arrange
            var pageable = PageRequest.of(0, 12);
            var suppliers = List.of(testSupplier);
            var expectedPage = new PageImpl<>(suppliers, pageable, 1);

            when(supplierRepository.findAll(pageable)).thenReturn(expectedPage);

            // Act
            Page<SupplierResponse> result = supplierService.getAllSuppliers(pageable, null);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).supplierName()).isEqualTo("Test Supplier");
            assertThat(result.getTotalElements()).isEqualTo(1);
            verify(supplierRepository).findAll(pageable);
        }

        @Test
        @DisplayName("getAllSuppliers - should search with term")
        void getAllSuppliers_WithSearch() {
            // Arrange
            var pageable = PageRequest.of(0, 12);
            var suppliers = List.of(testSupplier);
            var expectedPage = new PageImpl<>(suppliers, pageable, 1);

            when(supplierRepository.findBySearchTerm("Test", pageable)).thenReturn(expectedPage);

            // Act
            Page<SupplierResponse> result = supplierService.getAllSuppliers(pageable, "Test");

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).supplierName()).isEqualTo("Test Supplier");
            verify(supplierRepository).findBySearchTerm("Test", pageable);
        }

        @Test
        @DisplayName("getAllSuppliers - should return empty page when search has no matches")
        void getAllSuppliers_NoMatches() {
            // Arrange
            var pageable = PageRequest.of(0, 12);
            var expectedPage = new PageImpl<Supplier>(List.of(), pageable, 0);

            when(supplierRepository.findBySearchTerm("NonExistent", pageable)).thenReturn(expectedPage);

            // Act
            Page<SupplierResponse> result = supplierService.getAllSuppliers(pageable, "NonExistent");

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.getContent()).isEmpty();
            assertThat(result.getTotalElements()).isZero();
            verify(supplierRepository).findBySearchTerm("NonExistent", pageable);
        }

        @Test
        @DisplayName("getAllSuppliers - should ignore whitespace-only search and return all")
        void getAllSuppliers_BlankSearch() {
            // Arrange
            var pageable = PageRequest.of(0, 12);
            var suppliers = List.of(testSupplier);
            var expectedPage = new PageImpl<>(suppliers, pageable, 1);

            when(supplierRepository.findAll(pageable)).thenReturn(expectedPage);

            // Act
            Page<SupplierResponse> result = supplierService.getAllSuppliers(pageable, "   ");

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(1);
            verify(supplierRepository).findAll(pageable);
        }
    }

    @Nested
    @DisplayName("getSupplierById")
    class GetSupplierByIdTests {

        @Test
        @DisplayName("getSupplierById - should return supplier when found")
        void getSupplierById_Success() {
            // Arrange
            when(supplierRepository.findById(testSupplierId)).thenReturn(Optional.of(testSupplier));

            // Act
            SupplierResponse result = supplierService.getSupplierById(testSupplierId);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.supplierName()).isEqualTo(testSupplier.getSupplierName());
            assertThat(result.rfc()).isEqualTo(testSupplier.getRfc());
            // Legacy inline fallback: no Address entity, reads from inline columns
            assertThat(result.address()).isNotNull();
            assertThat(result.address().street()).isEqualTo("Calle Principal");
            assertThat(result.address().city()).isEqualTo("Ciudad de Mexico");
            assertThat(result.internalNumber()).isEqualTo("INT-001");
        }

        @Test
        @DisplayName("getSupplierById - should throw SupplierNotFoundException when not found")
        void getSupplierById_NotFound() {
            // Arrange
            when(supplierRepository.findById(testSupplierId)).thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> supplierService.getSupplierById(testSupplierId))
                    .isInstanceOf(SupplierNotFoundException.class)
                    .hasMessageContaining("Supplier not found with id");
        }
    }

    @Nested
    @DisplayName("createSupplier")
    class CreateSupplierTests {

        @Test
        @DisplayName("createSupplier - should create and return supplier")
        void createSupplier_Success() {
            // Arrange
            when(supplierRepository.existsByRfc(testSupplierRequest.rfc())).thenReturn(false);
            when(supplierRepository.save(any(Supplier.class))).thenReturn(testSupplier);

            // Act
            SupplierResponse result = supplierService.createSupplier(testSupplierRequest);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.supplierName()).isEqualTo(testSupplier.getSupplierName());
            assertThat(result.rfc()).isEqualTo(testSupplier.getRfc());
            assertThat(result.email()).isEqualTo(testSupplier.getEmail());
            assertThat(result.internalNumber()).isEqualTo("INT-001");
            verify(supplierRepository).existsByRfc(testSupplierRequest.rfc());
            verify(supplierRepository).save(any(Supplier.class));
        }

        @Test
        @DisplayName("createSupplier - should throw DuplicateSupplierException when RFC exists")
        void createSupplier_DuplicateRfc() {
            // Arrange
            when(supplierRepository.existsByRfc(testSupplierRequest.rfc())).thenReturn(true);

            // Act & Assert
            assertThatThrownBy(() -> supplierService.createSupplier(testSupplierRequest))
                    .isInstanceOf(DuplicateSupplierException.class)
                    .hasMessageContaining("Ya existe un proveedor con RFC");
            verify(supplierRepository).existsByRfc(testSupplierRequest.rfc());
            verify(supplierRepository, never()).save(any(Supplier.class));
        }

        @Test
        @DisplayName("createSupplier - should default enabled to true when null")
        void createSupplier_DefaultEnabled() {
            // Arrange
            var requestWithNullEnabled = new SupplierRequest(
                    "New Supplier",
                    "Razon Social",
                    "ABCD123456XYZ",
                    "new@supplier.com",
                    null, null, null,
                    null  // enabled = null
            );

            var savedSupplier = Supplier.builder()
                    .id(UUID.randomUUID())
                    .supplierName("New Supplier")
                    .razonSocial("Razon Social")
                    .rfc("ABCD123456XYZ")
                    .email("new@supplier.com")
                    .enabled(true)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();

            when(supplierRepository.existsByRfc("ABCD123456XYZ")).thenReturn(false);
            when(supplierRepository.save(any(Supplier.class))).thenReturn(savedSupplier);

            // Act
            SupplierResponse result = supplierService.createSupplier(requestWithNullEnabled);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.enabled()).isTrue();
        }

        @Test
        @DisplayName("createSupplier - should map address fields when creating with address")
        void createSupplier_WithAddress() {
            // Arrange
            var country = Country.builder()
                    .id(testCountryId)
                    .countryCode("MX")
                    .countryName("Mexico")
                    .enabled(true)
                    .build();

            when(supplierRepository.existsByRfc(testSupplierRequestWithAddress.rfc())).thenReturn(false);
            when(countryRepository.findById(testCountryId)).thenReturn(Optional.of(country));
            when(supplierRepository.save(any(Supplier.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            SupplierResponse result = supplierService.createSupplier(testSupplierRequestWithAddress);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.address()).isNotNull();
            assertThat(result.address().street()).isEqualTo("Av. Reforma");
            assertThat(result.address().streetNumber()).isEqualTo("456");
            assertThat(result.address().internalNumber()).isEqualTo("B");
            assertThat(result.address().neighborhood()).isEqualTo("Juarez");
            assertThat(result.address().zipCode()).isEqualTo("67890");
            assertThat(result.address().city()).isEqualTo("Guadalajara");
            assertThat(result.address().state()).isEqualTo("Jalisco");
            assertThat(result.address().countryId()).isEqualTo(testCountryId);
            assertThat(result.internalNumber()).isEqualTo("INT-002");
        }

        @Test
        @DisplayName("createSupplier - should create supplier without address when not provided")
        void createSupplier_WithoutAddress() {
            // Arrange
            var supplierWithoutAddress = Supplier.builder()
                    .id(UUID.randomUUID())
                    .supplierName("No Address Supplier")
                    .razonSocial("No Addr S.A.")
                    .rfc("XAXX010101002")
                    .email("noaddr@supplier.com")
                    .phoneNumber("+1111111111")
                    .enabled(true)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();

            when(supplierRepository.existsByRfc(testSupplierRequest.rfc())).thenReturn(false);
            when(supplierRepository.save(any(Supplier.class))).thenReturn(supplierWithoutAddress);

            // Act
            SupplierResponse result = supplierService.createSupplier(testSupplierRequest);

            // Assert
            assertThat(result).isNotNull();
            // testSupplierRequest has address=null, so no address should be mapped
        }
    }

    @Nested
    @DisplayName("updateSupplier")
    class UpdateSupplierTests {

        @Test
        @DisplayName("updateSupplier - should update and return supplier")
        void updateSupplier_Success() {
            // Arrange
            var updateRequest = new SupplierRequest(
                    "Updated Supplier",
                    "Updated Razon Social",
                    "XAXX010101000",
                    "updated@supplier.com",
                    "+9876543210",
                    "INT-003",
                    null,
                    false
            );

            when(supplierRepository.findById(testSupplierId)).thenReturn(Optional.of(testSupplier));
            when(supplierRepository.save(any(Supplier.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            SupplierResponse result = supplierService.updateSupplier(testSupplierId, updateRequest);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.supplierName()).isEqualTo("Updated Supplier");
            assertThat(result.email()).isEqualTo("updated@supplier.com");
            assertThat(result.internalNumber()).isEqualTo("INT-003");
            assertThat(result.enabled()).isFalse();
            verify(supplierRepository).save(any(Supplier.class));
        }

        @Test
        @DisplayName("updateSupplier - should update address fields when address request provided")
        void updateSupplier_UpdatesAddressFields() {
            // Arrange
            var addressUpdateRequest = new SupplierRequest(
                    "Test Supplier",
                    "Test Razon Social",
                    "XAXX010101000",
                    null,
                    null,
                    "INT-004",
                    new AddressRequest(
                            "Calle Nueva",
                            "999",
                            "1",
                            "Col Nueva",
                            "54321",
                            "Monterrey",
                            "Nuevo Leon",
                            testCountryId
                    ),
                    null
            );

            var country = Country.builder()
                    .id(testCountryId)
                    .countryCode("MX")
                    .countryName("Mexico")
                    .enabled(true)
                    .build();

            when(supplierRepository.findById(testSupplierId)).thenReturn(Optional.of(testSupplier));
            when(countryRepository.findById(testCountryId)).thenReturn(Optional.of(country));
            when(supplierRepository.save(any(Supplier.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            SupplierResponse result = supplierService.updateSupplier(testSupplierId, addressUpdateRequest);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.address()).isNotNull();
            assertThat(result.address().street()).isEqualTo("Calle Nueva");
            assertThat(result.address().streetNumber()).isEqualTo("999");
            assertThat(result.address().internalNumber()).isEqualTo("1");
            assertThat(result.address().neighborhood()).isEqualTo("Col Nueva");
            assertThat(result.address().zipCode()).isEqualTo("54321");
            assertThat(result.address().city()).isEqualTo("Monterrey");
            assertThat(result.address().state()).isEqualTo("Nuevo Leon");
            assertThat(result.address().countryId()).isEqualTo(testCountryId);
            assertThat(result.internalNumber()).isEqualTo("INT-004");
            verify(supplierRepository).save(any(Supplier.class));
        }

        @Test
        @DisplayName("updateSupplier - should throw SupplierNotFoundException when not found")
        void updateSupplier_NotFound() {
            // Arrange
            when(supplierRepository.findById(testSupplierId)).thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> supplierService.updateSupplier(testSupplierId, testSupplierRequest))
                    .isInstanceOf(SupplierNotFoundException.class)
                    .hasMessageContaining("Supplier not found with id");
            verify(supplierRepository, never()).save(any(Supplier.class));
        }

        @Test
        @DisplayName("updateSupplier - should throw DuplicateSupplierException when RFC conflicts with another supplier")
        void updateSupplier_DuplicateRfc() {
            // Arrange
            var requestWithDifferentRfc = new SupplierRequest(
                    "Test Supplier",
                    "Test Razon Social",
                    "DIFFERENTRFC123",
                    null, null, null, null,
                    true
            );

            when(supplierRepository.findById(testSupplierId)).thenReturn(Optional.of(testSupplier));
            when(supplierRepository.existsByRfcAndIdNot("DIFFERENTRFC123", testSupplierId)).thenReturn(true);

            // Act & Assert
            assertThatThrownBy(() -> supplierService.updateSupplier(testSupplierId, requestWithDifferentRfc))
                    .isInstanceOf(DuplicateSupplierException.class)
                    .hasMessageContaining("Ya existe un proveedor con RFC");
            verify(supplierRepository, never()).save(any(Supplier.class));
        }

        @Test
        @DisplayName("updateSupplier - should allow same RFC when updating same supplier")
        void updateSupplier_SameRfcAllowed() {
            // Arrange
            var requestWithSameRfc = new SupplierRequest(
                    "Updated Name",
                    "Updated Razon",
                    "XAXX010101000",  // Same RFC as existing
                    null, null, null, null,
                    true
            );

            when(supplierRepository.findById(testSupplierId)).thenReturn(Optional.of(testSupplier));
            when(supplierRepository.save(any(Supplier.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            SupplierResponse result = supplierService.updateSupplier(testSupplierId, requestWithSameRfc);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.supplierName()).isEqualTo("Updated Name");
            verify(supplierRepository).save(any(Supplier.class));
        }
    }

    @Nested
    @DisplayName("deleteSupplier")
    class DeleteSupplierTests {

        @Test
        @DisplayName("deleteSupplier - should soft-delete by setting enabled to false")
        void deleteSupplier_Success() {
            // Arrange
            when(supplierRepository.findById(testSupplierId)).thenReturn(Optional.of(testSupplier));
            when(supplierRepository.save(any(Supplier.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            supplierService.deleteSupplier(testSupplierId);

            // Assert
            verify(supplierRepository).findById(testSupplierId);
            verify(supplierRepository).save(any(Supplier.class));
            assertThat(testSupplier.getEnabled()).isFalse();
        }

        @Test
        @DisplayName("deleteSupplier - should throw SupplierNotFoundException when not found")
        void deleteSupplier_NotFound() {
            // Arrange
            when(supplierRepository.findById(testSupplierId)).thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> supplierService.deleteSupplier(testSupplierId))
                    .isInstanceOf(SupplierNotFoundException.class)
                    .hasMessageContaining("Supplier not found with id");
            verify(supplierRepository, never()).save(any(Supplier.class));
        }
    }
}
