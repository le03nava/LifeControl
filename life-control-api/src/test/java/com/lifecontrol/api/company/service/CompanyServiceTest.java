package com.lifecontrol.api.company.service;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.dto.CompanyRequest;
import com.lifecontrol.api.company.dto.CompanyResponse;
import com.lifecontrol.api.company.event.CompanyCreatedEvent;
import com.lifecontrol.api.company.exception.CompanyNotFoundException;
import com.lifecontrol.api.company.exception.DuplicateCompanyException;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CompanyService Tests")
class CompanyServiceTest {

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private CurrentUserContext currentUserContext;

    @InjectMocks
    private CompanyService companyService;

    private Company testCompany;
    private CompanyRequest testCompanyRequest;
    private CompanyRequest testCompanyRequestWithAddress;
    private UUID testCompanyId;
    private UUID testCountryId;

    @BeforeEach
    void setUp() {
        lenient().when(currentUserContext.isAdmin()).thenReturn(true);
        testCompanyId = UUID.randomUUID();
        testCountryId = UUID.randomUUID();

        testCompany = Company.builder()
                .id(testCompanyId)
                .companyKey("1")
                .companyName("Test Company")
                .tipoPersonaId(1)
                .razonSocial("Razon Social Test SA de CV")
                .rfc("XAXX010101000")
                .phone("+1234567890")
                .email("test@company.com")
                .enabled(true)
                .street("Main St")
                .streetNumber("123")
                .internalNumber("A")
                .neighborhood("Downtown")
                .zipCode("12345")
                .city("Mexico City")
                .state("CDMX")
                .countryId(testCountryId)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        testCompanyRequest = new CompanyRequest(
                "1",
                "Test Company",
                1,
                "Razon Social Test SA de CV",
                "XAXX010101000",
                "+1234567890",
                "test@company.com",
                true,
                null, null, null, null, null, null, null, null
        );

        testCompanyRequestWithAddress = new CompanyRequest(
                "2",
                "Company With Address",
                1,
                "Addr S.A. de CV",
                "XAXX010101001",
                "+9876543210",
                "addr@company.com",
                true,
                "Av. Reforma",
                "456",
                "B",
                "Juarez",
                "67890",
                "Guadalajara",
                "Jalisco",
                testCountryId
        );
    }

    @Nested
    @DisplayName("getAllCompanies")
    class GetAllCompaniesTests {

        @Test
        @DisplayName("getAllCompanies - should return paginated companies without search")
        void getAllCompanies_Paginated() {
            // Arrange
            var pageable = PageRequest.of(0, 12);
            var companies = List.of(testCompany);
            var expectedPage = new PageImpl<>(companies, pageable, 1);

            when(companyRepository.findAll(pageable)).thenReturn(expectedPage);

            // Act
            Page<CompanyResponse> result = companyService.getAllCompanies(pageable, null);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).companyName()).isEqualTo("Test Company");
            assertThat(result.getTotalElements()).isEqualTo(1);
            assertThat(result.getTotalPages()).isEqualTo(1);
            verify(companyRepository).findAll(pageable);
        }

        @Test
        @DisplayName("getAllCompanies - should return filtered companies with search")
        void getAllCompanies_WithSearch() {
            // Arrange
            var pageable = PageRequest.of(0, 12);
            var companies = List.of(testCompany);
            var expectedPage = new PageImpl<>(companies, pageable, 1);

            when(companyRepository.findBySearchTerm(eq("Test"), eq(pageable))).thenReturn(expectedPage);

            // Act
            Page<CompanyResponse> result = companyService.getAllCompanies(pageable, "Test");

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).companyName()).isEqualTo("Test Company");
            verify(companyRepository).findBySearchTerm("Test", pageable);
        }

        @Test
        @DisplayName("getAllCompanies - should return empty page when search has no matches")
        void getAllCompanies_NoMatches() {
            // Arrange
            var pageable = PageRequest.of(0, 12);
            var expectedPage = new PageImpl<Company>(List.of(), pageable, 0);

            when(companyRepository.findBySearchTerm(eq("NonExistent"), eq(pageable))).thenReturn(expectedPage);

            // Act
            Page<CompanyResponse> result = companyService.getAllCompanies(pageable, "NonExistent");

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.getContent()).isEmpty();
            assertThat(result.getTotalElements()).isZero();
            verify(companyRepository).findBySearchTerm("NonExistent", pageable);
        }

        @Test
        @DisplayName("getAllCompanies - should ignore whitespace-only search and return all")
        void getAllCompanies_BlankSearch() {
            // Arrange
            var pageable = PageRequest.of(0, 12);
            var companies = List.of(testCompany);
            var expectedPage = new PageImpl<>(companies, pageable, 1);

            when(companyRepository.findAll(pageable)).thenReturn(expectedPage);

            // Act
            Page<CompanyResponse> result = companyService.getAllCompanies(pageable, "   ");

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(1);
            verify(companyRepository).findAll(pageable);
        }

        // ── Country-role filtering ────────────────────────────

        @Test
        @DisplayName("getAllCompanies - should filter by companyIds when country role")
        void getAllCompanies_CountryRole_FiltersByCompanyIds() {
            // Override admin default — simulate country-role user
            reset(currentUserContext);
            var companyId1 = UUID.randomUUID();
            var companyId2 = UUID.randomUUID();
            var ids = Set.of(companyId1, companyId2);

            lenient().when(currentUserContext.isAdmin()).thenReturn(false);
            lenient().when(currentUserContext.getCompanyIds()).thenReturn(ids);

            var pageable = PageRequest.of(0, 12);
            var companies = List.of(testCompany);
            var expectedPage = new PageImpl<>(companies, pageable, 1);

            when(companyRepository.findAllByIdIn(ids, pageable)).thenReturn(expectedPage);

            // Act
            Page<CompanyResponse> result = companyService.getAllCompanies(pageable, null);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(1);
            verify(companyRepository).findAllByIdIn(ids, pageable);
        }

        @Test
        @DisplayName("getAllCompanies - should return empty page when country-role has no companyIds")
        void getAllCompanies_CountryRole_NoCompanyIds_ReturnsEmpty() {
            // Override admin default — simulate country-role with empty companyIds
            reset(currentUserContext);
            lenient().when(currentUserContext.isAdmin()).thenReturn(false);
            lenient().when(currentUserContext.getCompanyIds()).thenReturn(Collections.emptySet());

            var pageable = PageRequest.of(0, 12);

            // Act
            Page<CompanyResponse> result = companyService.getAllCompanies(pageable, null);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.getContent()).isEmpty();
            assertThat(result.getTotalElements()).isZero();
            verify(companyRepository, never()).findAllByIdIn(anySet(), any());
            verify(companyRepository, never()).findAll(any(Pageable.class));
        }

        @Test
        @DisplayName("getAllCompanies - should filter by companyIds with search when country role")
        void getAllCompanies_CountryRole_WithSearch() {
            // Override admin default
            reset(currentUserContext);
            var ids = Set.of(testCompanyId);
            lenient().when(currentUserContext.isAdmin()).thenReturn(false);
            lenient().when(currentUserContext.getCompanyIds()).thenReturn(ids);

            var pageable = PageRequest.of(0, 12);
            var companies = List.of(testCompany);
            var expectedPage = new PageImpl<>(companies, pageable, 1);

            when(companyRepository.findBySearchTermAndIdIn(eq("Test"), eq(ids), eq(pageable)))
                    .thenReturn(expectedPage);

            // Act
            Page<CompanyResponse> result = companyService.getAllCompanies(pageable, "Test");

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(1);
            verify(companyRepository).findBySearchTermAndIdIn("Test", ids, pageable);
        }
    }

    @Nested
    @DisplayName("getCompanyById")
    class GetCompanyByIdTests {

        @Test
        @DisplayName("getCompanyById - should return company when exists")
        void getCompanyById_Success() {
            // Arrange
            when(companyRepository.findById(testCompanyId)).thenReturn(Optional.of(testCompany));

            // Act
            CompanyResponse result = companyService.getCompanyById(testCompanyId);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.companyKey()).isEqualTo(testCompany.getCompanyKey());
            assertThat(result.companyName()).isEqualTo(testCompany.getCompanyName());
            assertThat(result.street()).isEqualTo("Main St");
            assertThat(result.city()).isEqualTo("Mexico City");
            assertThat(result.countryId()).isEqualTo(testCountryId);
        }

        @Test
        @DisplayName("getCompanyById - should throw CompanyNotFoundException when not exists")
        void getCompanyById_NotFound_ThrowsException() {
            // Arrange
            when(companyRepository.findById(testCompanyId)).thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> companyService.getCompanyById(testCompanyId))
                    .isInstanceOf(CompanyNotFoundException.class)
                    .hasMessageContaining("Company not found with id");
        }
    }

    @Nested
    @DisplayName("updateCompany")
    class UpdateCompanyTests {

        @Test
        @DisplayName("updateCompany - should update company successfully")
        void updateCompany_Success() {
            // Arrange
            CompanyRequest updateRequest = new CompanyRequest(
                    "1",
                    "Updated Company Name",
                    2,
                    "Nueva Razon Social SA de CV",
                    "XAXX010101000",
                    "+9876543210",
                    "updated@company.com",
                    false,
                    null, null, null, null, null, null, null, null
            );

            when(companyRepository.findById(testCompanyId)).thenReturn(Optional.of(testCompany));
            when(companyRepository.existsByRfcAndIdNot(updateRequest.rfc(), testCompanyId)).thenReturn(false);
            when(companyRepository.save(any(Company.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            CompanyResponse result = companyService.updateCompany(testCompanyId, updateRequest);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.companyName()).isEqualTo("Updated Company Name");
            assertThat(result.tipoPersonaId()).isEqualTo(2);
            assertThat(result.razonSocial()).isEqualTo("Nueva Razon Social SA de CV");
            assertThat(result.email()).isEqualTo("updated@company.com");
            assertThat(result.enabled()).isFalse();
            // Address fields should remain unchanged (null in request)
            assertThat(result.street()).isNull();
            assertThat(result.countryId()).isNull();
            verify(companyRepository).save(any(Company.class));
        }

        @Test
        @DisplayName("updateCompany - should update address fields")
        void updateCompany_UpdatesAddressFields() {
            // Arrange
            CompanyRequest addressUpdateRequest = new CompanyRequest(
                    "1",
                    "Test Company",
                    null,
                    null,
                    "XAXX010101000",
                    null,
                    null,
                    null,
                    "Calle Nueva",
                    "999",
                    "1",
                    "Col Nueva",
                    "54321",
                    "Monterrey",
                    "Nuevo Leon",
                    testCountryId
            );

            when(companyRepository.findById(testCompanyId)).thenReturn(Optional.of(testCompany));
            when(companyRepository.existsByRfcAndIdNot(any(), eq(testCompanyId))).thenReturn(false);
            when(companyRepository.save(any(Company.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            CompanyResponse result = companyService.updateCompany(testCompanyId, addressUpdateRequest);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.street()).isEqualTo("Calle Nueva");
            assertThat(result.streetNumber()).isEqualTo("999");
            assertThat(result.internalNumber()).isEqualTo("1");
            assertThat(result.neighborhood()).isEqualTo("Col Nueva");
            assertThat(result.zipCode()).isEqualTo("54321");
            assertThat(result.city()).isEqualTo("Monterrey");
            assertThat(result.state()).isEqualTo("Nuevo Leon");
            assertThat(result.countryId()).isEqualTo(testCountryId);
            verify(companyRepository).save(any(Company.class));
        }

        @Test
        @DisplayName("updateCompany - should throw CompanyNotFoundException when ID not exists")
        void updateCompany_NotFound_ThrowsException() {
            // Arrange
            UUID nonExistentId = UUID.randomUUID();
            when(companyRepository.findById(nonExistentId)).thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> companyService.updateCompany(nonExistentId, testCompanyRequest))
                    .isInstanceOf(CompanyNotFoundException.class)
                    .hasMessageContaining("Company not found with id");
        }

        @Test
        @DisplayName("updateCompany - should throw DuplicateCompanyException when RFC is duplicate")
        void updateCompany_DuplicateRfc_ThrowsException() {
            // Arrange
            CompanyRequest duplicateRfcRequest = new CompanyRequest(
                    "1",
                    "Test Company",
                    null,
                    null,
                    "DUPLICATE_RFC",
                    null,
                    null,
                    null,
                    null, null, null, null, null, null, null, null
            );

            when(companyRepository.findById(testCompanyId)).thenReturn(Optional.of(testCompany));
            when(companyRepository.existsByRfcAndIdNot("DUPLICATE_RFC", testCompanyId)).thenReturn(true);

            // Act & Assert
            assertThatThrownBy(() -> companyService.updateCompany(testCompanyId, duplicateRfcRequest))
                    .isInstanceOf(DuplicateCompanyException.class)
                    .hasMessageContaining("Ya existe una compañía con RFC");
        }

        @Test
        @DisplayName("updateCompany - should ignore companyKey from request and use path ID")
        void updateCompany_IgnoreCompanyKeyFromRequest() {
            // Arrange - request has different companyKey but should be ignored
            CompanyRequest updateRequest = new CompanyRequest(
                    "999",  // Different from existing company
                    "Updated Company",
                    null,
                    null,
                    "XAXX010101000",
                    null,
                    null,
                    null,
                    null, null, null, null, null, null, null, null
            );

            when(companyRepository.findById(testCompanyId)).thenReturn(Optional.of(testCompany));
            when(companyRepository.existsByRfcAndIdNot(updateRequest.rfc(), testCompanyId)).thenReturn(false);
            when(companyRepository.save(any(Company.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            CompanyResponse result = companyService.updateCompany(testCompanyId, updateRequest);

            // Assert
            assertThat(result).isNotNull();
            // companyKey should remain "1", not "999" from request
            assertThat(result.companyKey()).isEqualTo("1");
            verify(companyRepository).save(any(Company.class));
        }

        @Test
        @DisplayName("updateCompany - should allow same RFC when updating same company")
        void updateCompany_SameRfcAllowed() {
            // Arrange - same RFC as current company should pass
            CompanyRequest sameRfcRequest = new CompanyRequest(
                    "1",
                    "Updated Name",
                    null,
                    null,
                    "XAXX010101000",  // Same as existing
                    null,
                    null,
                    null,
                    null, null, null, null, null, null, null, null
            );

            when(companyRepository.findById(testCompanyId)).thenReturn(Optional.of(testCompany));
            when(companyRepository.existsByRfcAndIdNot("XAXX010101000", testCompanyId)).thenReturn(false);
            when(companyRepository.save(any(Company.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act & Assert - should not throw
            CompanyResponse result = companyService.updateCompany(testCompanyId, sameRfcRequest);
            assertThat(result).isNotNull();
            assertThat(result.companyName()).isEqualTo("Updated Name");
        }
    }

    @Nested
    @DisplayName("deleteCompany")
    class DeleteCompanyTests {

        @Test
        @DisplayName("deleteCompany - should soft-delete company by setting enabled to false")
        void deleteCompany_Success() {
            // Arrange
            when(companyRepository.findById(testCompanyId)).thenReturn(Optional.of(testCompany));
            when(companyRepository.save(any(Company.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            companyService.deleteCompany(testCompanyId);

            // Assert
            verify(companyRepository).findById(testCompanyId);
            verify(companyRepository).save(any(Company.class));
        }

        @Test
        @DisplayName("deleteCompany - should throw CompanyNotFoundException when company not exists")
        void deleteCompany_NotFound_ThrowsException() {
            // Arrange
            UUID nonExistentId = UUID.randomUUID();
            when(companyRepository.findById(nonExistentId)).thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> companyService.deleteCompany(nonExistentId))
                    .isInstanceOf(CompanyNotFoundException.class)
                    .hasMessageContaining("Company not found with id");
            verify(companyRepository, never()).save(any(Company.class));
        }
    }

    @Nested
    @DisplayName("createCompany")
    class CreateCompanyTests {

        @Test
        @DisplayName("should publish CompanyCreatedEvent after saving company")
        void shouldPublishEvent() {
            // Arrange
            when(companyRepository.existsByCompanyKey(any())).thenReturn(false);
            when(companyRepository.existsByRfc(any())).thenReturn(false);
            when(companyRepository.save(any(Company.class))).thenReturn(testCompany);

            // Act
            companyService.createCompany(testCompanyRequest);

            // Assert
            verify(eventPublisher).publishEvent(any(CompanyCreatedEvent.class));
        }

        @Test
        @DisplayName("should publish CompanyCreatedEvent with correct company data")
        void shouldPublishEventWithCorrectData() {
            // Arrange
            when(companyRepository.existsByCompanyKey(any())).thenReturn(false);
            when(companyRepository.existsByRfc(any())).thenReturn(false);
            when(companyRepository.save(any(Company.class))).thenReturn(testCompany);

            // Act
            companyService.createCompany(testCompanyRequest);

            // Assert
            var captor = ArgumentCaptor.forClass(CompanyCreatedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            var event = captor.getValue();
            assertThat(event.getId()).isEqualTo(testCompanyId);
            assertThat(event.getCompanyKey()).isEqualTo(testCompany.getCompanyKey());
            assertThat(event.getCompanyName()).isEqualTo(testCompany.getCompanyName());
        }

        @Test
        @DisplayName("should map address fields when creating company with address")
        void shouldMapAddressFields() {
            // Arrange
            var companyWithAddress = Company.builder()
                    .id(UUID.randomUUID())
                    .companyKey("2")
                    .companyName("Company With Address")
                    .tipoPersonaId(1)
                    .razonSocial("Addr S.A. de CV")
                    .rfc("XAXX010101001")
                    .phone("+9876543210")
                    .email("addr@company.com")
                    .enabled(true)
                    .street("Av. Reforma")
                    .streetNumber("456")
                    .internalNumber("B")
                    .neighborhood("Juarez")
                    .zipCode("67890")
                    .city("Guadalajara")
                    .state("Jalisco")
                    .countryId(testCountryId)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();

            when(companyRepository.existsByCompanyKey(any())).thenReturn(false);
            when(companyRepository.existsByRfc(any())).thenReturn(false);
            when(companyRepository.save(any(Company.class))).thenReturn(companyWithAddress);

            // Act
            CompanyResponse result = companyService.createCompany(testCompanyRequestWithAddress);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.street()).isEqualTo("Av. Reforma");
            assertThat(result.streetNumber()).isEqualTo("456");
            assertThat(result.internalNumber()).isEqualTo("B");
            assertThat(result.neighborhood()).isEqualTo("Juarez");
            assertThat(result.zipCode()).isEqualTo("67890");
            assertThat(result.city()).isEqualTo("Guadalajara");
            assertThat(result.state()).isEqualTo("Jalisco");
            assertThat(result.countryId()).isEqualTo(testCountryId);
        }

        @Test
        @DisplayName("should create company with null address fields when not provided")
        void shouldCreateCompanyWithoutAddress() {
            // Arrange
            var companyWithoutAddress = Company.builder()
                    .id(UUID.randomUUID())
                    .companyKey("3")
                    .companyName("No Address Company")
                    .tipoPersonaId(1)
                    .razonSocial("No Addr S.A.")
                    .rfc("XAXX010101002")
                    .phone("+1111111111")
                    .email("noaddr@company.com")
                    .enabled(true)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();

            CompanyRequest noAddressRequest = new CompanyRequest(
                    "3",
                    "No Address Company",
                    1,
                    "No Addr S.A.",
                    "XAXX010101002",
                    "+1111111111",
                    "noaddr@company.com",
                    true,
                    null, null, null, null, null, null, null, null
            );

            when(companyRepository.existsByCompanyKey(any())).thenReturn(false);
            when(companyRepository.existsByRfc(any())).thenReturn(false);
            when(companyRepository.save(any(Company.class))).thenReturn(companyWithoutAddress);

            // Act
            CompanyResponse result = companyService.createCompany(noAddressRequest);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.street()).isNull();
            assertThat(result.streetNumber()).isNull();
            assertThat(result.internalNumber()).isNull();
            assertThat(result.neighborhood()).isNull();
            assertThat(result.zipCode()).isNull();
            assertThat(result.city()).isNull();
            assertThat(result.state()).isNull();
            assertThat(result.countryId()).isNull();
        }
    }
}