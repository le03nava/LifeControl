package com.lifecontrol.api.company.service;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.dto.CompanyCountryRequest;
import com.lifecontrol.api.company.dto.CompanyCountryResponse;
import com.lifecontrol.api.company.event.CompanyCountryCreatedEvent;
import com.lifecontrol.api.company.exception.CompanyCountryNotFoundException;
import com.lifecontrol.api.company.exception.CompanyNotFoundException;
import com.lifecontrol.api.company.exception.DuplicateCompanyCountryException;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.repository.CompanyCountryRepository;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.country.model.Country;
import com.lifecontrol.api.country.repository.CountryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import org.springframework.security.access.AccessDeniedException;

@ExtendWith(MockitoExtension.class)
@DisplayName("CompanyCountryService Tests")
class CompanyCountryServiceTest {

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
    private CompanyCountryService companyCountryService;

    private UUID companyId;
    private UUID countryId;
    private UUID companyCountryId;
    private Company testCompany;
    private Country testCountry;
    private CompanyCountry testCompanyCountry;
    private CompanyCountryRequest testRequest;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        countryId = UUID.randomUUID();
        companyCountryId = UUID.randomUUID();

        testCompany = Company.builder()
                .id(companyId)
                .companyKey("1")
                .companyName("Test Company")
                .rfc("XAXX010101000")
                .enabled(true)
                .build();

        testCountry = Country.builder()
                .id(countryId)
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

        testRequest = new CompanyCountryRequest("MX", "Oficina MX");
    }

    @Nested
    @DisplayName("getCountriesByCompanyId")
    class GetCountriesByCompanyIdTests {

        @Test
        @DisplayName("should return countries for existing company")
        void getCountriesByCompanyId_Success() {
            // Arrange
            when(companyRepository.existsById(companyId)).thenReturn(true);
            when(companyCountryRepository.findByCompanyId(companyId))
                    .thenReturn(List.of(testCompanyCountry));

            // Act
            List<CompanyCountryResponse> result = companyCountryService.getCountriesByCompanyId(companyId);

            // Assert
            assertThat(result).hasSize(1);
            assertThat(result.get(0).countryCode()).isEqualTo("MX");
            assertThat(result.get(0).localAlias()).isEqualTo("Oficina MX");
        }

        @Test
        @DisplayName("should throw CompanyNotFoundException when company not exists")
        void getCountriesByCompanyId_CompanyNotFound_ThrowsException() {
            // Arrange
            when(companyRepository.existsById(companyId)).thenReturn(false);

            // Act & Assert
            assertThatThrownBy(() -> companyCountryService.getCountriesByCompanyId(companyId))
                    .isInstanceOf(CompanyNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("addCountryToCompany")
    class AddCountryToCompanyTests {

        @Test
        @DisplayName("should add country to company successfully")
        void addCountryToCompany_Success() {
            // Arrange
            when(companyRepository.findById(companyId)).thenReturn(Optional.of(testCompany));
            when(countryRepository.findByCountryCode("MX")).thenReturn(Optional.of(testCountry));
            when(companyCountryRepository.existsByCompanyIdAndCountryId(companyId, countryId)).thenReturn(false);
            when(companyCountryRepository.save(any(CompanyCountry.class))).thenAnswer(inv -> {
                CompanyCountry cc = inv.getArgument(0);
                return CompanyCountry.builder()
                        .id(companyCountryId)
                        .company(cc.getCompany())
                        .country(cc.getCountry())
                        .localAlias(cc.getLocalAlias())
                        .build();
            });

            // Act
            CompanyCountryResponse result = companyCountryService.addCountryToCompany(companyId, testRequest);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.countryCode()).isEqualTo("MX");
            assertThat(result.localAlias()).isEqualTo("Oficina MX");
            verify(companyCountryRepository).save(any(CompanyCountry.class));
            verify(eventPublisher).publishEvent(any(CompanyCountryCreatedEvent.class));
        }

        @Test
        @DisplayName("should publish CompanyCountryCreatedEvent with correct fields")
        void addCountryToCompany_PublishesEvent() {
            // Arrange
            when(companyRepository.findById(companyId)).thenReturn(Optional.of(testCompany));
            when(countryRepository.findByCountryCode("MX")).thenReturn(Optional.of(testCountry));
            when(companyCountryRepository.existsByCompanyIdAndCountryId(companyId, countryId)).thenReturn(false);
            when(companyCountryRepository.save(any(CompanyCountry.class))).thenAnswer(inv -> {
                CompanyCountry cc = inv.getArgument(0);
                return CompanyCountry.builder()
                        .id(companyCountryId)
                        .company(cc.getCompany())
                        .country(cc.getCountry())
                        .localAlias(cc.getLocalAlias())
                        .build();
            });

            // Act
            companyCountryService.addCountryToCompany(companyId, testRequest);

            // Assert
            var captor = ArgumentCaptor.forClass(CompanyCountryCreatedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            var event = captor.getValue();
            assertThat(event.getCompanyCountryId()).isEqualTo(companyCountryId);
            assertThat(event.getCompanyId()).isEqualTo(companyId);
            assertThat(event.getCountryName()).isEqualTo("México");
        }

        @Test
        @DisplayName("should throw CompanyNotFoundException when company not found")
        void addCountryToCompany_CompanyNotFound_ThrowsException() {
            // Arrange
            when(companyRepository.findById(companyId)).thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> companyCountryService.addCountryToCompany(companyId, testRequest))
                    .isInstanceOf(CompanyNotFoundException.class);
        }

        @Test
        @DisplayName("should throw CountryNotFoundException when countryCode not found")
        void addCountryToCompany_CountryNotFound_ThrowsException() {
            // Arrange
            when(companyRepository.findById(companyId)).thenReturn(Optional.of(testCompany));
            when(countryRepository.findByCountryCode("MX")).thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> companyCountryService.addCountryToCompany(companyId, testRequest))
                    .isInstanceOf(com.lifecontrol.api.country.exception.CountryNotFoundException.class);
        }

        @Test
        @DisplayName("should throw DuplicateCompanyCountryException when relation already exists")
        void addCountryToCompany_Duplicate_ThrowsException() {
            // Arrange
            when(companyRepository.findById(companyId)).thenReturn(Optional.of(testCompany));
            when(countryRepository.findByCountryCode("MX")).thenReturn(Optional.of(testCountry));
            when(companyCountryRepository.existsByCompanyIdAndCountryId(companyId, countryId)).thenReturn(true);

            // Act & Assert
            assertThatThrownBy(() -> companyCountryService.addCountryToCompany(companyId, testRequest))
                    .isInstanceOf(DuplicateCompanyCountryException.class);
        }
    }

    @Nested
    @DisplayName("removeCountryFromCompany")
    class RemoveCountryFromCompanyTests {

        @Test
        @DisplayName("should remove country from company successfully")
        void removeCountryFromCompany_Success() {
            // Arrange
            when(companyCountryRepository.findById(companyCountryId))
                    .thenReturn(Optional.of(testCompanyCountry));

            // Act
            companyCountryService.removeCountryFromCompany(companyId, companyCountryId);

            // Assert
            verify(companyCountryRepository).delete(testCompanyCountry);
        }

        @Test
        @DisplayName("should throw CompanyCountryNotFoundException when relation not found")
        void removeCountryFromCompany_NotFound_ThrowsException() {
            // Arrange
            when(companyCountryRepository.findById(companyCountryId)).thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> companyCountryService.removeCountryFromCompany(companyId, companyCountryId))
                    .isInstanceOf(CompanyCountryNotFoundException.class);
        }

        @Test
        @DisplayName("should throw CompanyCountryNotFoundException when relation does not belong to company")
        void removeCountryFromCompany_WrongCompany_ThrowsException() {
            // Arrange
            UUID wrongCompanyId = UUID.randomUUID();
            when(companyCountryRepository.findById(companyCountryId))
                    .thenReturn(Optional.of(testCompanyCountry));

            // Act & Assert
            assertThatThrownBy(() -> companyCountryService.removeCountryFromCompany(wrongCompanyId, companyCountryId))
                    .isInstanceOf(CompanyCountryNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("getCountriesByCompanyId with lc-company-country role")
    class GetCountriesByCompanyIdCountryRoleTests {

        @Test
        @DisplayName("should return only records matching user's company_country_id claim")
        void getCountriesByCompanyId_CountryRole_ReturnsFilteredRecords() {
            when(currentUserContext.isAdmin()).thenReturn(false);
            when(currentUserContext.hasCompanyCountryRole()).thenReturn(true);
            when(currentUserContext.getCompanyCountryIds()).thenReturn(Set.of(companyCountryId));
            when(companyRepository.existsById(companyId)).thenReturn(true);
            when(companyCountryRepository.findByIdInAndCompanyId(Set.of(companyCountryId), companyId))
                    .thenReturn(List.of(testCompanyCountry));

            List<CompanyCountryResponse> result = companyCountryService.getCountriesByCompanyId(companyId);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).id()).isEqualTo(companyCountryId);
            assertThat(result.get(0).countryCode()).isEqualTo("MX");
            verify(companyCountryRepository).findByIdInAndCompanyId(any(), eq(companyId));
            verify(companyCountryRepository, never()).findByCompanyId(any());
        }

        @Test
        @DisplayName("should return empty when no records match user's company_country_id claim")
        void getCountriesByCompanyId_CountryRole_NoMatch_ReturnsEmpty() {
            var nonMatchingId = UUID.randomUUID();
            when(currentUserContext.isAdmin()).thenReturn(false);
            when(currentUserContext.hasCompanyCountryRole()).thenReturn(true);
            when(currentUserContext.getCompanyCountryIds()).thenReturn(Set.of(nonMatchingId));
            when(companyRepository.existsById(companyId)).thenReturn(true);
            when(companyCountryRepository.findByIdInAndCompanyId(Set.of(nonMatchingId), companyId))
                    .thenReturn(List.of());

            List<CompanyCountryResponse> result = companyCountryService.getCountriesByCompanyId(companyId);

            assertThat(result).isEmpty();
            verify(companyCountryRepository).findByIdInAndCompanyId(any(), eq(companyId));
        }

        @Test
        @DisplayName("should return empty when company_country_id claim is empty")
        void getCountriesByCompanyId_CountryRole_EmptyClaim_ReturnsEmpty() {
            when(currentUserContext.isAdmin()).thenReturn(false);
            when(currentUserContext.hasCompanyCountryRole()).thenReturn(true);
            when(currentUserContext.getCompanyCountryIds()).thenReturn(Set.of());
            when(companyRepository.existsById(companyId)).thenReturn(true);

            List<CompanyCountryResponse> result = companyCountryService.getCountriesByCompanyId(companyId);

            assertThat(result).isEmpty();
            verify(companyCountryRepository, never()).findByIdInAndCompanyId(any(), any());
            verify(companyCountryRepository, never()).findByCompanyId(any());
        }
    }

    @Nested
    @DisplayName("removeCountryFromCompany with lc-company-country role")
    class RemoveCountryFromCompanyCountryRoleTests {

        @Test
        @DisplayName("should succeed when verifyCompanyCountryAccess passes")
        void removeCountryFromCompany_CountryRole_Success() {
            when(companyCountryRepository.findById(companyCountryId))
                    .thenReturn(Optional.of(testCompanyCountry));

            companyCountryService.removeCountryFromCompany(companyId, companyCountryId);

            verify(companyCountryRepository).delete(testCompanyCountry);
        }

        @Test
        @DisplayName("should throw AccessDeniedException when verifyCompanyCountryAccess fails")
        void removeCountryFromCompany_CountryRole_AccessDenied() {
            var nonMatchingId = UUID.randomUUID();
            doThrow(new AccessDeniedException("Access denied"))
                    .when(currentUserContext).verifyCompanyCountryAccess(companyId, nonMatchingId);

            assertThatThrownBy(() -> companyCountryService.removeCountryFromCompany(companyId, nonMatchingId))
                    .isInstanceOf(AccessDeniedException.class);
            verify(companyCountryRepository, never()).findById(any());
        }
    }

    @Nested
    @DisplayName("updateCountry with lc-company-country role")
    class UpdateCountryCountryRoleTests {

        @Test
        @DisplayName("should succeed when verifyCompanyCountryAccess passes")
        void updateCountry_CountryRole_Success() {
            when(companyCountryRepository.findById(companyCountryId))
                    .thenReturn(Optional.of(testCompanyCountry));
            when(countryRepository.findByCountryCode("MX")).thenReturn(Optional.of(testCountry));
            when(companyCountryRepository.existsByCompanyIdAndCountryId(companyId, countryId)).thenReturn(false);
            when(companyCountryRepository.save(any(CompanyCountry.class))).thenReturn(testCompanyCountry);

            CompanyCountryResponse result = companyCountryService.updateCountry(companyId, companyCountryId, testRequest);

            assertThat(result).isNotNull();
            assertThat(result.countryCode()).isEqualTo("MX");
            verify(companyCountryRepository).save(any(CompanyCountry.class));
        }

        @Test
        @DisplayName("should throw AccessDeniedException when verifyCompanyCountryAccess fails")
        void updateCountry_CountryRole_AccessDenied() {
            var nonMatchingId = UUID.randomUUID();
            doThrow(new AccessDeniedException("Access denied"))
                    .when(currentUserContext).verifyCompanyCountryAccess(companyId, nonMatchingId);

            assertThatThrownBy(() -> companyCountryService.updateCountry(companyId, nonMatchingId, testRequest))
                    .isInstanceOf(AccessDeniedException.class);
            verify(companyCountryRepository, never()).findById(any());
        }
    }

    @Nested
    @DisplayName("Backward compatibility regression")
    class BackwardCompatibilityTests {

        @Test
        @DisplayName("lc-admin bypasses country filtering in GET")
        void getCountriesByCompanyId_Admin_BypassesCountryFiltering() {
            when(currentUserContext.isAdmin()).thenReturn(true);
            when(companyRepository.existsById(companyId)).thenReturn(true);
            when(companyCountryRepository.findByCompanyId(companyId))
                    .thenReturn(List.of(testCompanyCountry));

            List<CompanyCountryResponse> result = companyCountryService.getCountriesByCompanyId(companyId);

            assertThat(result).hasSize(1);
            verify(companyCountryRepository).findByCompanyId(companyId);
            verify(companyCountryRepository, never()).findByIdInAndCompanyId(any(), any());
        }

        @Test
        @DisplayName("lc-company user uses findByCompanyId in GET (not country-filtered)")
        void getCountriesByCompanyId_CompanyRole_UsesFindByCompanyId() {
            when(currentUserContext.hasCompanyCountryRole()).thenReturn(false);
            when(companyRepository.existsById(companyId)).thenReturn(true);
            when(companyCountryRepository.findByCompanyId(companyId))
                    .thenReturn(List.of(testCompanyCountry));

            List<CompanyCountryResponse> result = companyCountryService.getCountriesByCompanyId(companyId);

            assertThat(result).hasSize(1);
            verify(companyCountryRepository).findByCompanyId(companyId);
            verify(companyCountryRepository, never()).findByIdInAndCompanyId(any(), any());
        }

        @Test
        @DisplayName("addCountryToCompany still uses verifyCompanyAccess (not verifyCompanyCountryAccess)")
        void addCountryToCompany_UsesVerifyCompanyAccess() {
            when(companyRepository.findById(companyId)).thenReturn(Optional.of(testCompany));
            when(countryRepository.findByCountryCode("MX")).thenReturn(Optional.of(testCountry));
            when(companyCountryRepository.existsByCompanyIdAndCountryId(companyId, countryId)).thenReturn(false);
            when(companyCountryRepository.save(any(CompanyCountry.class))).thenReturn(testCompanyCountry);

            companyCountryService.addCountryToCompany(companyId, testRequest);

            verify(currentUserContext).verifyCompanyAccess(companyId);
        }
    }
}
