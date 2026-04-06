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

        testCompanyRequest = CompanyRequest.builder()
                .companyId(1)
                .companyName("Test Company")
                .tipoPersonaId(1)
                .razonSocial("Razon Social Test SA de CV")
                .rfc("XAXX010101000")
                .phone("+1234567890")
                .email("test@company.com")
                .enabled(true)
                .build();
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
            assertThat(result.getCompanyId()).isEqualTo(testCompany.getCompanyId());
            assertThat(result.getCompanyKey()).isEqualTo(testCompany.getCompanyKey());
            assertThat(result.getCompanyName()).isEqualTo(testCompany.getCompanyName());
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
            CompanyRequest updateRequest = CompanyRequest.builder()
                    .companyId(1)
                    .companyName("Updated Company Name")
                    .tipoPersonaId(2)
                    .razonSocial("Nueva Razon Social SA de CV")
                    .rfc("XAXX010101000")  // Same RFC - should pass
                    .phone("+9876543210")
                    .email("updated@company.com")
                    .enabled(false)
                    .build();

            when(companyRepository.findById(testCompanyId)).thenReturn(Optional.of(testCompany));
            when(companyRepository.existsByRfcAndIdNot(updateRequest.getRfc(), testCompanyId)).thenReturn(false);
            when(companyRepository.save(any(Company.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            CompanyResponse result = companyService.updateCompany(testCompanyId, updateRequest);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.getCompanyName()).isEqualTo("Updated Company Name");
            assertThat(result.getTipoPersonaId()).isEqualTo(2);
            assertThat(result.getRazonSocial()).isEqualTo("Nueva Razon Social SA de CV");
            assertThat(result.getEmail()).isEqualTo("updated@company.com");
            assertThat(result.getEnabled()).isFalse();
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
            CompanyRequest duplicateRfcRequest = CompanyRequest.builder()
                    .companyId(1)
                    .companyName("Test Company")
                    .rfc("DUPLICATE_RFC")
                    .build();

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
            CompanyRequest updateRequest = CompanyRequest.builder()
                    .companyId(999)  // Different from existing company
                    .companyName("Updated Company")
                    .rfc("XAXX010101000")
                    .build();

            when(companyRepository.findById(testCompanyId)).thenReturn(Optional.of(testCompany));
            when(companyRepository.existsByRfcAndIdNot(updateRequest.getRfc(), testCompanyId)).thenReturn(false);
            when(companyRepository.save(any(Company.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            CompanyResponse result = companyService.updateCompany(testCompanyId, updateRequest);

            // Assert
            assertThat(result).isNotNull();
            // companyId should remain 1, not 999 from request
            assertThat(result.getCompanyId()).isEqualTo(1);
            verify(companyRepository).save(any(Company.class));
        }

        @Test
        @DisplayName("updateCompany - should allow same RFC when updating same company")
        void updateCompany_SameRfcAllowed() {
            // Arrange - same RFC as current company should pass
            CompanyRequest sameRfcRequest = CompanyRequest.builder()
                    .companyId(1)
                    .companyName("Updated Name")
                    .rfc("XAXX010101000")  // Same as existing
                    .build();

            when(companyRepository.findById(testCompanyId)).thenReturn(Optional.of(testCompany));
            when(companyRepository.existsByRfcAndIdNot("XAXX010101000", testCompanyId)).thenReturn(false);
            when(companyRepository.save(any(Company.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act & Assert - should not throw
            CompanyResponse result = companyService.updateCompany(testCompanyId, sameRfcRequest);
            assertThat(result).isNotNull();
            assertThat(result.getCompanyName()).isEqualTo("Updated Name");
        }
    }
}
