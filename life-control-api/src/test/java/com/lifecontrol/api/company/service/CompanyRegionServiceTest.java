package com.lifecontrol.api.company.service;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.dto.CompanyRegionResponse;
import com.lifecontrol.api.company.dto.CreateCompanyRegionRequest;
import com.lifecontrol.api.company.dto.UpdateCompanyRegionRequest;
import com.lifecontrol.api.company.exception.CompanyNotFoundException;
import com.lifecontrol.api.company.exception.CompanyRegionNotFoundException;
import com.lifecontrol.api.company.exception.DuplicateCompanyRegionException;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.repository.CompanyCountryRepository;
import com.lifecontrol.api.company.repository.CompanyRegionRepository;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.company.event.CompanyRegionCreatedEvent;
import com.lifecontrol.api.country.model.Country;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CompanyRegionService Tests")
class CompanyRegionServiceTest {

    @Mock
    private CompanyRegionRepository companyRegionRepository;
    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private CompanyCountryRepository companyCountryRepository;
    @Mock
    private CurrentUserContext currentUserContext;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private CompanyRegionService companyRegionService;

    private UUID companyId;
    private UUID companyCountryId;
    private UUID regionId;
    private Company testCompany;
    private Country testCountry;
    private CompanyCountry testCompanyCountry;
    private CompanyRegion testRegion;
    private CreateCompanyRegionRequest createRequest;
    private UpdateCompanyRegionRequest updateRequest;
    private LocalDateTime now;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        companyCountryId = UUID.randomUUID();
        regionId = UUID.randomUUID();
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

        createRequest = new CreateCompanyRegionRequest("NORTE", "Norte");
        updateRequest = new UpdateCompanyRegionRequest("NORTE", "Norte Actualizado");
    }

    @Nested
    @DisplayName("getAllRegions")
    class GetAllRegionsTests {

        @Test
        @DisplayName("should return enabled regions when includeDisabled is false")
        void getAllRegions_ExcludeDisabled_ReturnsOnlyEnabled() {
            // Arrange
            var companyCountry = CompanyCountry.builder()
                    .id(companyCountryId)
                    .company(testCompany)
                    .country(testCountry)
                    .build();
            when(companyCountryRepository.findByCompanyIdAndId(companyId, companyCountryId))
                    .thenReturn(Optional.of(companyCountry));

            var enabledRegion = CompanyRegion.builder()
                    .id(regionId)
                    .companyCountry(testCompanyCountry)
                    .regionCode("NORTE")
                    .regionName("Norte")
                    .enabled(true)
                    .build();
            var disabledRegion = CompanyRegion.builder()
                    .id(UUID.randomUUID())
                    .companyCountry(testCompanyCountry)
                    .regionCode("SUR")
                    .regionName("Sur")
                    .enabled(false)
                    .build();

            when(companyRegionRepository.findByCompanyCountryIdOrderByRegionNameAsc(companyCountryId))
                    .thenReturn(List.of(enabledRegion, disabledRegion));

            // Act
            List<CompanyRegionResponse> result = companyRegionService.getAllRegions(companyId, companyCountryId, false);

            // Assert
            assertThat(result).hasSize(1);
            assertThat(result.get(0).regionCode()).isEqualTo("NORTE");
            verify(companyCountryRepository).findByCompanyIdAndId(companyId, companyCountryId);
        }

        @Test
        @DisplayName("should return all regions when includeDisabled is true")
        void getAllRegions_IncludeDisabled_ReturnsAll() {
            // Arrange
            var companyCountry = CompanyCountry.builder()
                    .id(companyCountryId)
                    .company(testCompany)
                    .country(testCountry)
                    .build();
            when(companyCountryRepository.findByCompanyIdAndId(companyId, companyCountryId))
                    .thenReturn(Optional.of(companyCountry));

            var enabledRegion = CompanyRegion.builder()
                    .id(regionId)
                    .companyCountry(testCompanyCountry)
                    .regionCode("NORTE")
                    .regionName("Norte")
                    .enabled(true)
                    .build();
            var disabledRegion = CompanyRegion.builder()
                    .id(UUID.randomUUID())
                    .companyCountry(testCompanyCountry)
                    .regionCode("SUR")
                    .regionName("Sur")
                    .enabled(false)
                    .build();

            when(companyRegionRepository.findByCompanyCountryIdOrderByRegionNameAsc(companyCountryId))
                    .thenReturn(List.of(enabledRegion, disabledRegion));

            // Act
            List<CompanyRegionResponse> result = companyRegionService.getAllRegions(companyId, companyCountryId, true);

            // Assert
            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("should throw CompanyCountryNotFoundException when company-country not found")
        void getAllRegions_CompanyCountryNotFound_ThrowsException() {
            // Arrange
            when(companyCountryRepository.findByCompanyIdAndId(companyId, companyCountryId))
                    .thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> companyRegionService.getAllRegions(companyId, companyCountryId, false))
                    .isInstanceOf(com.lifecontrol.api.company.exception.CompanyCountryNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("getRegionById")
    class GetRegionByIdTests {

        @Test
        @DisplayName("should return region when found and scoped to company-country")
        void getRegionById_Success() {
            // Arrange
            var companyCountry = CompanyCountry.builder()
                    .id(companyCountryId)
                    .company(testCompany)
                    .country(testCountry)
                    .build();
            when(companyCountryRepository.findByCompanyIdAndId(companyId, companyCountryId))
                    .thenReturn(Optional.of(companyCountry));
            when(companyRegionRepository.findByIdAndCompanyCountryId(regionId, companyCountryId))
                    .thenReturn(Optional.of(testRegion));

            // Act
            CompanyRegionResponse result = companyRegionService.getRegionById(companyId, companyCountryId, regionId);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.regionCode()).isEqualTo("NORTE");
            assertThat(result.companyCountryId()).isEqualTo(companyCountryId);
            assertThat(result.companyId()).isEqualTo(companyId);
            assertThat(result.countryId()).isEqualTo(testCountry.getId());
        }

        @Test
        @DisplayName("should throw CompanyRegionNotFoundException when region not found")
        void getRegionById_NotFound_ThrowsException() {
            // Arrange
            var companyCountry = CompanyCountry.builder()
                    .id(companyCountryId)
                    .company(testCompany)
                    .country(testCountry)
                    .build();
            when(companyCountryRepository.findByCompanyIdAndId(companyId, companyCountryId))
                    .thenReturn(Optional.of(companyCountry));
            when(companyRegionRepository.findByIdAndCompanyCountryId(regionId, companyCountryId))
                    .thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> companyRegionService.getRegionById(companyId, companyCountryId, regionId))
                    .isInstanceOf(CompanyRegionNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("createRegion")
    class CreateRegionTests {

        @Test
        @DisplayName("should create region successfully")
        void createRegion_Success() {
            // Arrange
            when(companyRepository.findById(companyId)).thenReturn(Optional.of(testCompany));
            var companyCountry = CompanyCountry.builder()
                    .id(companyCountryId)
                    .company(testCompany)
                    .country(testCountry)
                    .build();
            when(companyCountryRepository.findByCompanyIdAndId(companyId, companyCountryId))
                    .thenReturn(Optional.of(companyCountry));
            when(companyRegionRepository.existsByCompanyCountryIdAndRegionCode(companyCountryId, "NORTE"))
                    .thenReturn(false);
            when(companyRegionRepository.save(any(CompanyRegion.class))).thenAnswer(inv -> {
                CompanyRegion saved = inv.getArgument(0);
                return CompanyRegion.builder()
                        .id(regionId)
                        .companyCountry(saved.getCompanyCountry())
                        .regionCode(saved.getRegionCode())
                        .regionName(saved.getRegionName())
                        .enabled(saved.getEnabled())
                        .build();
            });

            // Act
            CompanyRegionResponse result = companyRegionService.createRegion(companyId, companyCountryId, createRequest);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.regionCode()).isEqualTo("NORTE");
            assertThat(result.regionName()).isEqualTo("Norte");
            assertThat(result.enabled()).isTrue();
            verify(companyRegionRepository).save(any(CompanyRegion.class));
            verify(eventPublisher).publishEvent(any(CompanyRegionCreatedEvent.class));
        }

        @Test
        @DisplayName("should throw CompanyNotFoundException when company not found")
        void createRegion_CompanyNotFound_ThrowsException() {
            // Arrange
            when(companyRepository.findById(companyId)).thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> companyRegionService.createRegion(companyId, companyCountryId, createRequest))
                    .isInstanceOf(CompanyNotFoundException.class);
        }

        @Test
        @DisplayName("should throw DuplicateCompanyRegionException when region code already exists")
        void createRegion_DuplicateCode_ThrowsException() {
            // Arrange
            when(companyRepository.findById(companyId)).thenReturn(Optional.of(testCompany));
            var companyCountry = CompanyCountry.builder()
                    .id(companyCountryId)
                    .company(testCompany)
                    .country(testCountry)
                    .build();
            when(companyCountryRepository.findByCompanyIdAndId(companyId, companyCountryId))
                    .thenReturn(Optional.of(companyCountry));
            when(companyRegionRepository.existsByCompanyCountryIdAndRegionCode(companyCountryId, "NORTE"))
                    .thenReturn(true);

            // Act & Assert
            assertThatThrownBy(() -> companyRegionService.createRegion(companyId, companyCountryId, createRequest))
                    .isInstanceOf(DuplicateCompanyRegionException.class);
            verify(companyRegionRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("updateRegion")
    class UpdateRegionTests {

        @Test
        @DisplayName("should update region successfully")
        void updateRegion_Success() {
            // Arrange
            when(companyRepository.findById(companyId)).thenReturn(Optional.of(testCompany));
            var companyCountry = CompanyCountry.builder()
                    .id(companyCountryId)
                    .company(testCompany)
                    .country(testCountry)
                    .build();
            when(companyCountryRepository.findByCompanyIdAndId(companyId, companyCountryId))
                    .thenReturn(Optional.of(companyCountry));
            when(companyRegionRepository.findByIdAndCompanyCountryId(regionId, companyCountryId))
                    .thenReturn(Optional.of(testRegion));
            // Same regionCode, no duplicate check needed
            when(companyRegionRepository.save(any(CompanyRegion.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            CompanyRegionResponse result = companyRegionService.updateRegion(companyId, companyCountryId, regionId, updateRequest);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.regionName()).isEqualTo("Norte Actualizado");
            assertThat(result.regionCode()).isEqualTo("NORTE");
            verify(companyRegionRepository).save(any(CompanyRegion.class));
        }

        @Test
        @DisplayName("should throw DuplicateCompanyRegionException when new code conflicts with existing")
        void updateRegion_DuplicateCode_ThrowsException() {
            // Arrange
            UUID otherRegionId = UUID.randomUUID();
            var changeCodeRequest = new UpdateCompanyRegionRequest("SUR", "Sur");

            when(companyRepository.findById(companyId)).thenReturn(Optional.of(testCompany));
            var companyCountry = CompanyCountry.builder()
                    .id(companyCountryId)
                    .company(testCompany)
                    .country(testCountry)
                    .build();
            when(companyCountryRepository.findByCompanyIdAndId(companyId, companyCountryId))
                    .thenReturn(Optional.of(companyCountry));
            when(companyRegionRepository.findByIdAndCompanyCountryId(regionId, companyCountryId))
                    .thenReturn(Optional.of(testRegion));
            // Code changed from NORTE to SUR
            when(companyRegionRepository.existsByCompanyCountryIdAndRegionCodeAndIdNot(
                    companyCountryId, "SUR", regionId))
                    .thenReturn(true);

            // Act & Assert
            assertThatThrownBy(() -> companyRegionService.updateRegion(companyId, companyCountryId, regionId, changeCodeRequest))
                    .isInstanceOf(DuplicateCompanyRegionException.class);
            verify(companyRegionRepository, never()).save(any());
        }

        @Test
        @DisplayName("should allow updating with same code (self-duplicate)")
        void updateRegion_SameCode_AllowsUpdate() {
            // Arrange
            when(companyRepository.findById(companyId)).thenReturn(Optional.of(testCompany));
            var companyCountry = CompanyCountry.builder()
                    .id(companyCountryId)
                    .company(testCompany)
                    .country(testCountry)
                    .build();
            when(companyCountryRepository.findByCompanyIdAndId(companyId, companyCountryId))
                    .thenReturn(Optional.of(companyCountry));
            when(companyRegionRepository.findByIdAndCompanyCountryId(regionId, companyCountryId))
                    .thenReturn(Optional.of(testRegion));
            // Code is NORTE, request code is also NORTE — no duplicate check
            when(companyRegionRepository.save(any(CompanyRegion.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            CompanyRegionResponse result = companyRegionService.updateRegion(companyId, companyCountryId, regionId, updateRequest);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.regionCode()).isEqualTo("NORTE");
            verify(companyRegionRepository).save(any(CompanyRegion.class));
        }

        @Test
        @DisplayName("should throw CompanyRegionNotFoundException when region not found")
        void updateRegion_NotFound_ThrowsException() {
            // Arrange
            when(companyRepository.findById(companyId)).thenReturn(Optional.of(testCompany));
            var companyCountry = CompanyCountry.builder()
                    .id(companyCountryId)
                    .company(testCompany)
                    .country(testCountry)
                    .build();
            when(companyCountryRepository.findByCompanyIdAndId(companyId, companyCountryId))
                    .thenReturn(Optional.of(companyCountry));
            when(companyRegionRepository.findByIdAndCompanyCountryId(regionId, companyCountryId))
                    .thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> companyRegionService.updateRegion(companyId, companyCountryId, regionId, updateRequest))
                    .isInstanceOf(CompanyRegionNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("deleteRegion")
    class DeleteRegionTests {

        @Test
        @DisplayName("should soft-delete region successfully")
        void deleteRegion_Success() {
            // Arrange
            when(companyRepository.findById(companyId)).thenReturn(Optional.of(testCompany));
            var companyCountry = CompanyCountry.builder()
                    .id(companyCountryId)
                    .company(testCompany)
                    .country(testCountry)
                    .build();
            when(companyCountryRepository.findByCompanyIdAndId(companyId, companyCountryId))
                    .thenReturn(Optional.of(companyCountry));
            when(companyRegionRepository.findByIdAndCompanyCountryId(regionId, companyCountryId))
                    .thenReturn(Optional.of(testRegion));
            when(companyRegionRepository.save(any(CompanyRegion.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            companyRegionService.deleteRegion(companyId, companyCountryId, regionId);

            // Assert
            assertThat(testRegion.getEnabled()).isFalse();
            verify(companyRegionRepository).save(testRegion);
        }

        @Test
        @DisplayName("should throw CompanyRegionNotFoundException when region not found")
        void deleteRegion_NotFound_ThrowsException() {
            // Arrange
            when(companyRepository.findById(companyId)).thenReturn(Optional.of(testCompany));
            var companyCountry = CompanyCountry.builder()
                    .id(companyCountryId)
                    .company(testCompany)
                    .country(testCountry)
                    .build();
            when(companyCountryRepository.findByCompanyIdAndId(companyId, companyCountryId))
                    .thenReturn(Optional.of(companyCountry));
            when(companyRegionRepository.findByIdAndCompanyCountryId(regionId, companyCountryId))
                    .thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> companyRegionService.deleteRegion(companyId, companyCountryId, regionId))
                    .isInstanceOf(CompanyRegionNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("enableRegion")
    class EnableRegionTests {

        @Test
        @DisplayName("should re-enable a soft-deleted region successfully")
        void enableRegion_Success() {
            // Arrange
            testRegion.setEnabled(false); // Start as disabled
            when(companyRepository.findById(companyId)).thenReturn(Optional.of(testCompany));
            var companyCountry = CompanyCountry.builder()
                    .id(companyCountryId)
                    .company(testCompany)
                    .country(testCountry)
                    .build();
            when(companyCountryRepository.findByCompanyIdAndId(companyId, companyCountryId))
                    .thenReturn(Optional.of(companyCountry));
            when(companyRegionRepository.findByIdAndCompanyCountryId(regionId, companyCountryId))
                    .thenReturn(Optional.of(testRegion));
            when(companyRegionRepository.save(any(CompanyRegion.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            CompanyRegionResponse result = companyRegionService.enableRegion(companyId, companyCountryId, regionId);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.enabled()).isTrue();
            assertThat(result.regionCode()).isEqualTo("NORTE");
            verify(companyRegionRepository).save(any(CompanyRegion.class));
        }

        @Test
        @DisplayName("should throw CompanyNotFoundException when company not found")
        void enableRegion_CompanyNotFound_ThrowsException() {
            // Arrange
            when(companyRepository.findById(companyId)).thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> companyRegionService.enableRegion(companyId, companyCountryId, regionId))
                    .isInstanceOf(CompanyNotFoundException.class);
            verify(companyRegionRepository, never()).save(any());
        }

        @Test
        @DisplayName("should throw CompanyRegionNotFoundException when region not found")
        void enableRegion_NotFound_ThrowsException() {
            // Arrange
            when(companyRepository.findById(companyId)).thenReturn(Optional.of(testCompany));
            var companyCountry = CompanyCountry.builder()
                    .id(companyCountryId)
                    .company(testCompany)
                    .country(testCountry)
                    .build();
            when(companyCountryRepository.findByCompanyIdAndId(companyId, companyCountryId))
                    .thenReturn(Optional.of(companyCountry));
            when(companyRegionRepository.findByIdAndCompanyCountryId(regionId, companyCountryId))
                    .thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> companyRegionService.enableRegion(companyId, companyCountryId, regionId))
                    .isInstanceOf(CompanyRegionNotFoundException.class);
            verify(companyRegionRepository, never()).save(any());
        }
    }
}
