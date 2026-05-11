package com.lifecontrol.api.country.service;

import com.lifecontrol.api.country.dto.CountryRequest;
import com.lifecontrol.api.country.dto.CountryResponse;
import com.lifecontrol.api.country.exception.CountryNotFoundException;
import com.lifecontrol.api.country.exception.DuplicateCountryException;
import com.lifecontrol.api.country.model.Country;
import com.lifecontrol.api.country.repository.CountryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CountryService Tests")
class CountryServiceTest {

    @Mock
    private CountryRepository countryRepository;

    @InjectMocks
    private CountryService countryService;

    private Country testCountry;
    private CountryRequest testCountryRequest;
    private UUID testCountryId;

    @BeforeEach
    void setUp() {
        testCountryId = UUID.randomUUID();

        testCountry = Country.builder()
                .id(testCountryId)
                .countryCode("MX")
                .countryName("México")
                .enabled(true)
                .build();

        testCountryRequest = new CountryRequest("MX", "México");
    }

    @Nested
    @DisplayName("getAllCountries")
    class GetAllCountriesTests {

        @Test
        @DisplayName("should return only enabled countries by default")
        void getAllCountries_Default_FiltersDisabled() {
            // Arrange
            var enabled = Country.builder().id(UUID.randomUUID()).countryCode("MX").countryName("México").enabled(true).build();
            var disabled = Country.builder().id(UUID.randomUUID()).countryCode("US").countryName("USA").enabled(false).build();
            when(countryRepository.findByEnabledTrue()).thenReturn(List.of(enabled));

            // Act
            var result = countryService.getAllCountries(false);

            // Assert
            assertThat(result).hasSize(1);
            assertThat(result.get(0).countryCode()).isEqualTo("MX");
            verify(countryRepository).findByEnabledTrue();
            verify(countryRepository, never()).findAll();
        }

        @Test
        @DisplayName("should return all countries when includeDisabled is true")
        void getAllCountries_IncludeDisabled_ReturnsAll() {
            // Arrange
            var enabled = Country.builder().id(UUID.randomUUID()).countryCode("MX").countryName("México").enabled(true).build();
            var disabled = Country.builder().id(UUID.randomUUID()).countryCode("US").countryName("USA").enabled(false).build();
            when(countryRepository.findAll()).thenReturn(List.of(enabled, disabled));

            // Act
            var result = countryService.getAllCountries(true);

            // Assert
            assertThat(result).hasSize(2);
            verify(countryRepository).findAll();
            verify(countryRepository, never()).findByEnabledTrue();
        }
    }

    @Nested
    @DisplayName("getCountryById")
    class GetCountryByIdTests {

        @Test
        @DisplayName("should return country when exists")
        void getCountryById_Success() {
            // Arrange
            when(countryRepository.findById(testCountryId)).thenReturn(Optional.of(testCountry));

            // Act
            CountryResponse result = countryService.getCountryById(testCountryId);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.countryCode()).isEqualTo("MX");
            assertThat(result.countryName()).isEqualTo("México");
        }

        @Test
        @DisplayName("should throw CountryNotFoundException when not exists")
        void getCountryById_NotFound_ThrowsException() {
            // Arrange
            when(countryRepository.findById(testCountryId)).thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> countryService.getCountryById(testCountryId))
                    .isInstanceOf(CountryNotFoundException.class)
                    .hasMessageContaining("Country not found with id");
        }
    }

    @Nested
    @DisplayName("createCountry")
    class CreateCountryTests {

        @Test
        @DisplayName("should create country successfully")
        void createCountry_Success() {
            // Arrange
            when(countryRepository.existsByCountryCode("MX")).thenReturn(false);
            when(countryRepository.save(any(Country.class))).thenAnswer(inv -> {
                Country c = inv.getArgument(0);
                return Country.builder()
                        .id(testCountryId)
                        .countryCode(c.getCountryCode())
                        .countryName(c.getCountryName())
                        .enabled(true)
                        .build();
            });

            // Act
            CountryResponse result = countryService.createCountry(testCountryRequest);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.countryCode()).isEqualTo("MX");
            assertThat(result.countryName()).isEqualTo("México");
            assertThat(result.enabled()).isTrue();
            verify(countryRepository).save(any(Country.class));
        }

        @Test
        @DisplayName("should uppercase countryCode on creation")
        void createCountry_UppercasesCode() {
            // Arrange
            CountryRequest lowerCaseRequest = new CountryRequest("mx", "México");
            when(countryRepository.existsByCountryCode("mx")).thenReturn(false);
            when(countryRepository.save(any(Country.class))).thenAnswer(inv -> {
                Country c = inv.getArgument(0);
                return Country.builder().id(UUID.randomUUID()).countryCode(c.getCountryCode()).countryName(c.getCountryName()).enabled(true).build();
            });

            // Act
            CountryResponse result = countryService.createCountry(lowerCaseRequest);

            // Assert
            assertThat(result.countryCode()).isEqualTo("MX");
        }

        @Test
        @DisplayName("should throw DuplicateCountryException when countryCode exists")
        void createCountry_DuplicateCode_ThrowsException() {
            // Arrange
            when(countryRepository.existsByCountryCode("MX")).thenReturn(true);

            // Act & Assert
            assertThatThrownBy(() -> countryService.createCountry(testCountryRequest))
                    .isInstanceOf(DuplicateCountryException.class)
                    .hasMessageContaining("Ya existe un país con código");
            verify(countryRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("updateCountry")
    class UpdateCountryTests {

        @Test
        @DisplayName("should update country successfully")
        void updateCountry_Success() {
            // Arrange
            CountryRequest updateRequest = new CountryRequest("MX", "México (actualizado)");
            when(countryRepository.findById(testCountryId)).thenReturn(Optional.of(testCountry));
            when(countryRepository.save(any(Country.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            CountryResponse result = countryService.updateCountry(testCountryId, updateRequest);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.countryName()).isEqualTo("México (actualizado)");
            verify(countryRepository).save(any(Country.class));
        }

        @Test
        @DisplayName("should throw CountryNotFoundException when ID not exists")
        void updateCountry_NotFound_ThrowsException() {
            // Arrange
            when(countryRepository.findById(testCountryId)).thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> countryService.updateCountry(testCountryId, testCountryRequest))
                    .isInstanceOf(CountryNotFoundException.class)
                    .hasMessageContaining("Country not found with id");
        }

        @Test
        @DisplayName("should throw DuplicateCountryException when new countryCode conflicts")
        void updateCountry_DuplicateCode_ThrowsException() {
            // Arrange
            CountryRequest newCodeRequest = new CountryRequest("US", "USA");
            when(countryRepository.findById(testCountryId)).thenReturn(Optional.of(testCountry));
            when(countryRepository.existsByCountryCode("US")).thenReturn(true);

            // Act & Assert
            assertThatThrownBy(() -> countryService.updateCountry(testCountryId, newCodeRequest))
                    .isInstanceOf(DuplicateCountryException.class)
                    .hasMessageContaining("Ya existe un país con código");
        }
    }

    @Nested
    @DisplayName("deleteCountry")
    class DeleteCountryTests {

        @Test
        @DisplayName("should soft-delete country by setting enabled to false")
        void deleteCountry_Success() {
            // Arrange
            when(countryRepository.findById(testCountryId)).thenReturn(Optional.of(testCountry));
            when(countryRepository.save(any(Country.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            countryService.deleteCountry(testCountryId);

            // Assert
            verify(countryRepository).findById(testCountryId);
            verify(countryRepository).save(any(Country.class));
        }

        @Test
        @DisplayName("should throw CountryNotFoundException when not exists")
        void deleteCountry_NotFound_ThrowsException() {
            // Arrange
            when(countryRepository.findById(testCountryId)).thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> countryService.deleteCountry(testCountryId))
                    .isInstanceOf(CountryNotFoundException.class)
                    .hasMessageContaining("Country not found with id");
            verify(countryRepository, never()).save(any());
        }
    }
}
