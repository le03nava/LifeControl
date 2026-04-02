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
}
