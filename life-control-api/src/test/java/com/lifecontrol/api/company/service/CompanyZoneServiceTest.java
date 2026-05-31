package com.lifecontrol.api.company.service;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.dto.CompanyZoneResponse;
import com.lifecontrol.api.company.dto.CreateCompanyZoneRequest;
import com.lifecontrol.api.company.dto.UpdateCompanyZoneRequest;
import com.lifecontrol.api.company.exception.CompanyCountryNotFoundException;
import com.lifecontrol.api.company.exception.CompanyNotFoundException;
import com.lifecontrol.api.company.exception.CompanyRegionNotFoundException;
import com.lifecontrol.api.company.exception.CompanyZoneNotFoundException;
import com.lifecontrol.api.company.exception.DuplicateCompanyZoneException;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.company.repository.CompanyCountryRepository;
import com.lifecontrol.api.company.repository.CompanyRegionRepository;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.company.repository.CompanyZoneRepository;
import com.lifecontrol.api.country.model.Country;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CompanyZoneService Tests")
class CompanyZoneServiceTest {

    @Mock
    private CompanyZoneRepository companyZoneRepository;
    @Mock
    private CompanyRegionRepository companyRegionRepository;
    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private CompanyCountryRepository companyCountryRepository;
    @Mock
    private CurrentUserContext currentUserContext;

    @InjectMocks
    private CompanyZoneService companyZoneService;

    private UUID companyId;
    private UUID companyCountryId;
    private UUID regionId;
    private UUID zoneId;
    private Company testCompany;
    private Country testCountry;
    private CompanyCountry testCompanyCountry;
    private CompanyRegion testRegion;
    private CompanyZone testZone;
    private CreateCompanyZoneRequest createRequest;
    private UpdateCompanyZoneRequest updateRequest;
    private LocalDateTime now;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        companyCountryId = UUID.randomUUID();
        regionId = UUID.randomUUID();
        zoneId = UUID.randomUUID();
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

        createRequest = new CreateCompanyZoneRequest("CEN", "Centro", null, null);
        updateRequest = new UpdateCompanyZoneRequest("CEN", "Centro Actualizado", null, null);
    }

    @Nested
    @DisplayName("getAllZones")
    class GetAllZonesTests {

        @Test
        @DisplayName("should return enabled zones when includeDisabled is false")
        void getAllZones_ExcludeDisabled_ReturnsOnlyEnabled() {
            // Arrange
            when(companyCountryRepository.findByCompanyIdAndId(companyId, companyCountryId))
                    .thenReturn(Optional.of(testCompanyCountry));
            when(companyRegionRepository.findByIdAndCompanyCountryId(regionId, companyCountryId))
                    .thenReturn(Optional.of(testRegion));

            var enabledZone = CompanyZone.builder()
                    .id(zoneId)
                    .companyRegion(testRegion)
                    .zoneCode("CEN")
                    .zoneName("Centro")
                    .enabled(true)
                    .build();
            var disabledZone = CompanyZone.builder()
                    .id(UUID.randomUUID())
                    .companyRegion(testRegion)
                    .zoneCode("NTE")
                    .zoneName("Norte")
                    .enabled(false)
                    .build();

            when(companyZoneRepository.findByCompanyRegionIdOrderByZoneNameAsc(regionId))
                    .thenReturn(List.of(enabledZone, disabledZone));

            // Act
            List<CompanyZoneResponse> result = companyZoneService.getAllZones(companyId, companyCountryId, regionId, false);

            // Assert
            assertThat(result).hasSize(1);
            assertThat(result.get(0).zoneCode()).isEqualTo("CEN");
            verify(companyCountryRepository).findByCompanyIdAndId(companyId, companyCountryId);
        }

        @Test
        @DisplayName("should return all zones when includeDisabled is true")
        void getAllZones_IncludeDisabled_ReturnsAll() {
            // Arrange
            when(companyCountryRepository.findByCompanyIdAndId(companyId, companyCountryId))
                    .thenReturn(Optional.of(testCompanyCountry));
            when(companyRegionRepository.findByIdAndCompanyCountryId(regionId, companyCountryId))
                    .thenReturn(Optional.of(testRegion));

            var enabledZone = CompanyZone.builder()
                    .id(zoneId)
                    .companyRegion(testRegion)
                    .zoneCode("CEN")
                    .zoneName("Centro")
                    .enabled(true)
                    .build();
            var disabledZone = CompanyZone.builder()
                    .id(UUID.randomUUID())
                    .companyRegion(testRegion)
                    .zoneCode("NTE")
                    .zoneName("Norte")
                    .enabled(false)
                    .build();

            when(companyZoneRepository.findByCompanyRegionIdOrderByZoneNameAsc(regionId))
                    .thenReturn(List.of(enabledZone, disabledZone));

            // Act
            List<CompanyZoneResponse> result = companyZoneService.getAllZones(companyId, companyCountryId, regionId, true);

            // Assert
            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("should throw CompanyCountryNotFoundException when company-country not found")
        void getAllZones_CompanyCountryNotFound_ThrowsException() {
            // Arrange
            when(companyCountryRepository.findByCompanyIdAndId(companyId, companyCountryId))
                    .thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> companyZoneService.getAllZones(companyId, companyCountryId, regionId, false))
                    .isInstanceOf(CompanyCountryNotFoundException.class);
        }

        @Test
        @DisplayName("should throw CompanyRegionNotFoundException when region not found")
        void getAllZones_CompanyRegionNotFound_ThrowsException() {
            // Arrange
            when(companyCountryRepository.findByCompanyIdAndId(companyId, companyCountryId))
                    .thenReturn(Optional.of(testCompanyCountry));
            when(companyRegionRepository.findByIdAndCompanyCountryId(regionId, companyCountryId))
                    .thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> companyZoneService.getAllZones(companyId, companyCountryId, regionId, false))
                    .isInstanceOf(CompanyRegionNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("getZoneById")
    class GetZoneByIdTests {

        @Test
        @DisplayName("should return zone when found and scoped to region")
        void getZoneById_Success() {
            // Arrange
            when(companyCountryRepository.findByCompanyIdAndId(companyId, companyCountryId))
                    .thenReturn(Optional.of(testCompanyCountry));
            when(companyRegionRepository.findByIdAndCompanyCountryId(regionId, companyCountryId))
                    .thenReturn(Optional.of(testRegion));
            when(companyZoneRepository.findByIdAndCompanyRegionId(zoneId, regionId))
                    .thenReturn(Optional.of(testZone));

            // Act
            CompanyZoneResponse result = companyZoneService.getZoneById(companyId, companyCountryId, regionId, zoneId);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.zoneCode()).isEqualTo("CEN");
            assertThat(result.companyRegionId()).isEqualTo(regionId);
            assertThat(result.companyCountryId()).isEqualTo(companyCountryId);
            assertThat(result.companyId()).isEqualTo(companyId);
            assertThat(result.countryId()).isEqualTo(testCountry.getId());
        }

        @Test
        @DisplayName("should throw CompanyZoneNotFoundException when zone not found")
        void getZoneById_NotFound_ThrowsException() {
            // Arrange
            when(companyCountryRepository.findByCompanyIdAndId(companyId, companyCountryId))
                    .thenReturn(Optional.of(testCompanyCountry));
            when(companyRegionRepository.findByIdAndCompanyCountryId(regionId, companyCountryId))
                    .thenReturn(Optional.of(testRegion));
            when(companyZoneRepository.findByIdAndCompanyRegionId(zoneId, regionId))
                    .thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> companyZoneService.getZoneById(companyId, companyCountryId, regionId, zoneId))
                    .isInstanceOf(CompanyZoneNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("createZone")
    class CreateZoneTests {

        @Test
        @DisplayName("should create zone successfully")
        void createZone_Success() {
            // Arrange
            when(companyRepository.findById(companyId)).thenReturn(Optional.of(testCompany));
            when(companyCountryRepository.findByCompanyIdAndId(companyId, companyCountryId))
                    .thenReturn(Optional.of(testCompanyCountry));
            when(companyRegionRepository.findByIdAndCompanyCountryId(regionId, companyCountryId))
                    .thenReturn(Optional.of(testRegion));
            when(companyZoneRepository.existsByCompanyRegionIdAndZoneCode(regionId, "CEN"))
                    .thenReturn(false);
            when(companyZoneRepository.save(any(CompanyZone.class))).thenAnswer(inv -> {
                CompanyZone saved = inv.getArgument(0);
                return CompanyZone.builder()
                        .id(zoneId)
                        .companyRegion(saved.getCompanyRegion())
                        .zoneCode(saved.getZoneCode())
                        .zoneName(saved.getZoneName())
                        .description(saved.getDescription())
                        .displayOrder(saved.getDisplayOrder())
                        .enabled(saved.getEnabled())
                        .build();
            });

            // Act
            CompanyZoneResponse result = companyZoneService.createZone(companyId, companyCountryId, regionId, createRequest);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.zoneCode()).isEqualTo("CEN");
            assertThat(result.zoneName()).isEqualTo("Centro");
            assertThat(result.enabled()).isTrue();
            verify(companyZoneRepository).save(any(CompanyZone.class));
        }

        @Test
        @DisplayName("should throw CompanyNotFoundException when company not found")
        void createZone_CompanyNotFound_ThrowsException() {
            // Arrange
            when(companyRepository.findById(companyId)).thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> companyZoneService.createZone(companyId, companyCountryId, regionId, createRequest))
                    .isInstanceOf(CompanyNotFoundException.class);
        }

        @Test
        @DisplayName("should throw DuplicateCompanyZoneException when zone code already exists")
        void createZone_DuplicateCode_ThrowsException() {
            // Arrange
            when(companyRepository.findById(companyId)).thenReturn(Optional.of(testCompany));
            when(companyCountryRepository.findByCompanyIdAndId(companyId, companyCountryId))
                    .thenReturn(Optional.of(testCompanyCountry));
            when(companyRegionRepository.findByIdAndCompanyCountryId(regionId, companyCountryId))
                    .thenReturn(Optional.of(testRegion));
            when(companyZoneRepository.existsByCompanyRegionIdAndZoneCode(regionId, "CEN"))
                    .thenReturn(true);

            // Act & Assert
            assertThatThrownBy(() -> companyZoneService.createZone(companyId, companyCountryId, regionId, createRequest))
                    .isInstanceOf(DuplicateCompanyZoneException.class);
            verify(companyZoneRepository, never()).save(any());
        }

        @Test
        @DisplayName("should throw CompanyRegionNotFoundException when parent region does not exist")
        void createZone_CompanyRegionNotFound_ThrowsException() {
            // Arrange
            when(companyRepository.findById(companyId)).thenReturn(Optional.of(testCompany));
            when(companyCountryRepository.findByCompanyIdAndId(companyId, companyCountryId))
                    .thenReturn(Optional.of(testCompanyCountry));
            when(companyRegionRepository.findByIdAndCompanyCountryId(regionId, companyCountryId))
                    .thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> companyZoneService.createZone(companyId, companyCountryId, regionId, createRequest))
                    .isInstanceOf(CompanyRegionNotFoundException.class);
            verify(companyZoneRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("updateZone")
    class UpdateZoneTests {

        @Test
        @DisplayName("should update zone successfully")
        void updateZone_Success() {
            // Arrange
            when(companyRepository.findById(companyId)).thenReturn(Optional.of(testCompany));
            when(companyCountryRepository.findByCompanyIdAndId(companyId, companyCountryId))
                    .thenReturn(Optional.of(testCompanyCountry));
            when(companyRegionRepository.findByIdAndCompanyCountryId(regionId, companyCountryId))
                    .thenReturn(Optional.of(testRegion));
            when(companyZoneRepository.findByIdAndCompanyRegionId(zoneId, regionId))
                    .thenReturn(Optional.of(testZone));
            // Same zoneCode, no duplicate check needed
            when(companyZoneRepository.save(any(CompanyZone.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            CompanyZoneResponse result = companyZoneService.updateZone(companyId, companyCountryId, regionId, zoneId, updateRequest);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.zoneName()).isEqualTo("Centro Actualizado");
            assertThat(result.zoneCode()).isEqualTo("CEN");
            verify(companyZoneRepository).save(any(CompanyZone.class));
        }

        @Test
        @DisplayName("should throw DuplicateCompanyZoneException when new code conflicts with existing")
        void updateZone_DuplicateCode_ThrowsException() {
            // Arrange
            UUID otherZoneId = UUID.randomUUID();
            var changeCodeRequest = new UpdateCompanyZoneRequest("SUR", "Sur", null, null);

            when(companyRepository.findById(companyId)).thenReturn(Optional.of(testCompany));
            when(companyCountryRepository.findByCompanyIdAndId(companyId, companyCountryId))
                    .thenReturn(Optional.of(testCompanyCountry));
            when(companyRegionRepository.findByIdAndCompanyCountryId(regionId, companyCountryId))
                    .thenReturn(Optional.of(testRegion));
            when(companyZoneRepository.findByIdAndCompanyRegionId(zoneId, regionId))
                    .thenReturn(Optional.of(testZone));
            // Code changed from CEN to SUR
            when(companyZoneRepository.existsByCompanyRegionIdAndZoneCodeAndIdNot(
                    regionId, "SUR", zoneId))
                    .thenReturn(true);

            // Act & Assert
            assertThatThrownBy(() -> companyZoneService.updateZone(companyId, companyCountryId, regionId, zoneId, changeCodeRequest))
                    .isInstanceOf(DuplicateCompanyZoneException.class);
            verify(companyZoneRepository, never()).save(any());
        }

        @Test
        @DisplayName("should allow updating with same code (self-duplicate)")
        void updateZone_SameCode_AllowsUpdate() {
            // Arrange
            when(companyRepository.findById(companyId)).thenReturn(Optional.of(testCompany));
            when(companyCountryRepository.findByCompanyIdAndId(companyId, companyCountryId))
                    .thenReturn(Optional.of(testCompanyCountry));
            when(companyRegionRepository.findByIdAndCompanyCountryId(regionId, companyCountryId))
                    .thenReturn(Optional.of(testRegion));
            when(companyZoneRepository.findByIdAndCompanyRegionId(zoneId, regionId))
                    .thenReturn(Optional.of(testZone));
            // Code is CEN, request code is also CEN — no duplicate check
            when(companyZoneRepository.save(any(CompanyZone.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            CompanyZoneResponse result = companyZoneService.updateZone(companyId, companyCountryId, regionId, zoneId, updateRequest);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.zoneCode()).isEqualTo("CEN");
            verify(companyZoneRepository).save(any(CompanyZone.class));
        }

        @Test
        @DisplayName("should throw CompanyZoneNotFoundException when zone not found")
        void updateZone_NotFound_ThrowsException() {
            // Arrange
            when(companyRepository.findById(companyId)).thenReturn(Optional.of(testCompany));
            when(companyCountryRepository.findByCompanyIdAndId(companyId, companyCountryId))
                    .thenReturn(Optional.of(testCompanyCountry));
            when(companyRegionRepository.findByIdAndCompanyCountryId(regionId, companyCountryId))
                    .thenReturn(Optional.of(testRegion));
            when(companyZoneRepository.findByIdAndCompanyRegionId(zoneId, regionId))
                    .thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> companyZoneService.updateZone(companyId, companyCountryId, regionId, zoneId, updateRequest))
                    .isInstanceOf(CompanyZoneNotFoundException.class);
            verify(companyZoneRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("deleteZone")
    class DeleteZoneTests {

        @Test
        @DisplayName("should soft-delete zone successfully")
        void deleteZone_Success() {
            // Arrange
            when(companyRepository.findById(companyId)).thenReturn(Optional.of(testCompany));
            when(companyCountryRepository.findByCompanyIdAndId(companyId, companyCountryId))
                    .thenReturn(Optional.of(testCompanyCountry));
            when(companyRegionRepository.findByIdAndCompanyCountryId(regionId, companyCountryId))
                    .thenReturn(Optional.of(testRegion));
            when(companyZoneRepository.findByIdAndCompanyRegionId(zoneId, regionId))
                    .thenReturn(Optional.of(testZone));
            when(companyZoneRepository.save(any(CompanyZone.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            companyZoneService.deleteZone(companyId, companyCountryId, regionId, zoneId);

            // Assert
            assertThat(testZone.getEnabled()).isFalse();
            verify(companyZoneRepository).save(testZone);
        }

        @Test
        @DisplayName("should throw CompanyZoneNotFoundException when zone not found")
        void deleteZone_NotFound_ThrowsException() {
            // Arrange
            when(companyRepository.findById(companyId)).thenReturn(Optional.of(testCompany));
            when(companyCountryRepository.findByCompanyIdAndId(companyId, companyCountryId))
                    .thenReturn(Optional.of(testCompanyCountry));
            when(companyRegionRepository.findByIdAndCompanyCountryId(regionId, companyCountryId))
                    .thenReturn(Optional.of(testRegion));
            when(companyZoneRepository.findByIdAndCompanyRegionId(zoneId, regionId))
                    .thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> companyZoneService.deleteZone(companyId, companyCountryId, regionId, zoneId))
                    .isInstanceOf(CompanyZoneNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("enableZone")
    class EnableZoneTests {

        @Test
        @DisplayName("should re-enable a disabled zone successfully")
        void enableZone_Success() {
            // Arrange
            testZone.setEnabled(false); // Start as disabled
            when(companyRepository.findById(companyId)).thenReturn(Optional.of(testCompany));
            when(companyCountryRepository.findByCompanyIdAndId(companyId, companyCountryId))
                    .thenReturn(Optional.of(testCompanyCountry));
            when(companyRegionRepository.findByIdAndCompanyCountryId(regionId, companyCountryId))
                    .thenReturn(Optional.of(testRegion));
            when(companyZoneRepository.findByIdAndCompanyRegionId(zoneId, regionId))
                    .thenReturn(Optional.of(testZone));
            when(companyZoneRepository.save(any(CompanyZone.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            CompanyZoneResponse result = companyZoneService.enableZone(companyId, companyCountryId, regionId, zoneId);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.enabled()).isTrue();
            assertThat(result.zoneCode()).isEqualTo("CEN");
            verify(companyZoneRepository).save(any(CompanyZone.class));
        }

        @Test
        @DisplayName("should throw CompanyNotFoundException when company not found")
        void enableZone_CompanyNotFound_ThrowsException() {
            // Arrange
            when(companyRepository.findById(companyId)).thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> companyZoneService.enableZone(companyId, companyCountryId, regionId, zoneId))
                    .isInstanceOf(CompanyNotFoundException.class);
            verify(companyZoneRepository, never()).save(any());
        }

        @Test
        @DisplayName("should throw CompanyZoneNotFoundException when zone not found")
        void enableZone_NotFound_ThrowsException() {
            // Arrange
            when(companyRepository.findById(companyId)).thenReturn(Optional.of(testCompany));
            when(companyCountryRepository.findByCompanyIdAndId(companyId, companyCountryId))
                    .thenReturn(Optional.of(testCompanyCountry));
            when(companyRegionRepository.findByIdAndCompanyCountryId(regionId, companyCountryId))
                    .thenReturn(Optional.of(testRegion));
            when(companyZoneRepository.findByIdAndCompanyRegionId(zoneId, regionId))
                    .thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> companyZoneService.enableZone(companyId, companyCountryId, regionId, zoneId))
                    .isInstanceOf(CompanyZoneNotFoundException.class);
            verify(companyZoneRepository, never()).save(any());
        }
    }
}
