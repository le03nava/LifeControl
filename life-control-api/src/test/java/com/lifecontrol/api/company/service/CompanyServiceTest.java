package com.lifecontrol.api.company.service;

import com.lifecontrol.api.company.dto.CompanyRequest;
import com.lifecontrol.api.company.dto.CompanyResponse;
import com.lifecontrol.api.company.exception.CompanyNotFoundException;
import com.lifecontrol.api.company.exception.DuplicateCompanyException;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CompanyService Tests")
class CompanyServiceTest {

    @Mock
    private CompanyRepository companyRepository;

    @InjectMocks
    private CompanyService companyService;

    private Company testCompany;
    private CompanyRequest testCompanyRequest;
    private UUID testCompanyId;

    @BeforeEach
    void setUp() {
        testCompanyId = UUID.randomUUID();
        
        testCompany = Company.builder()
                .id(testCompanyId)
                .companyId(1)
                .companyKey("COMPANY_1")
                .companyName("Test Company")
                .tipoPersonaId(1)
                .razonSocial("Razon Social Test SA de CV")
                .rfc("XAXX010101000")
                .phone("+1234567890")
                .email("test@company.com")
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        testCompanyRequest = new CompanyRequest(
                1,
                "Test Company",
                1,
                "Razon Social Test SA de CV",
                "XAXX010101000",
                "+1234567890",
                "test@company.com",
                true
        );
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
            assertThat(result.companyId()).isEqualTo(testCompany.getCompanyId());
            assertThat(result.companyKey()).isEqualTo(testCompany.getCompanyKey());
            assertThat(result.companyName()).isEqualTo(testCompany.getCompanyName());
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
                    1,
                    "Updated Company Name",
                    2,
                    "Nueva Razon Social SA de CV",
                    "XAXX010101000",
                    "+9876543210",
                    "updated@company.com",
                    false
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
                    1,
                    "Test Company",
                    null,
                    null,
                    "DUPLICATE_RFC",
                    null,
                    null,
                    null
            );

            when(companyRepository.findById(testCompanyId)).thenReturn(Optional.of(testCompany));
            when(companyRepository.existsByRfcAndIdNot("DUPLICATE_RFC", testCompanyId)).thenReturn(true);

            // Act & Assert
            assertThatThrownBy(() -> companyService.updateCompany(testCompanyId, duplicateRfcRequest))
                    .isInstanceOf(DuplicateCompanyException.class)
                    .hasMessageContaining("Ya existe una compañía con RFC");
        }

        @Test
        @DisplayName("updateCompany - should ignore companyId from request and use path ID")
        void updateCompany_IgnoreCompanyIdFromRequest() {
            // Arrange - request has different companyId but should be ignored
            CompanyRequest updateRequest = new CompanyRequest(
                    999,  // Different from existing company
                    "Updated Company",
                    null,
                    null,
                    "XAXX010101000",
                    null,
                    null,
                    null
            );

            when(companyRepository.findById(testCompanyId)).thenReturn(Optional.of(testCompany));
            when(companyRepository.existsByRfcAndIdNot(updateRequest.rfc(), testCompanyId)).thenReturn(false);
            when(companyRepository.save(any(Company.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            CompanyResponse result = companyService.updateCompany(testCompanyId, updateRequest);

            // Assert
            assertThat(result).isNotNull();
            // companyId should remain 1, not 999 from request
            assertThat(result.companyId()).isEqualTo(1);
            verify(companyRepository).save(any(Company.class));
        }

        @Test
        @DisplayName("updateCompany - should allow same RFC when updating same company")
        void updateCompany_SameRfcAllowed() {
            // Arrange - same RFC as current company should pass
            CompanyRequest sameRfcRequest = new CompanyRequest(
                    1,
                    "Updated Name",
                    null,
                    null,
                    "XAXX010101000",  // Same as existing
                    null,
                    null,
                    null
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
}