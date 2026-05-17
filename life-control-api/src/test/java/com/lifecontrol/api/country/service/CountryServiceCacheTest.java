package com.lifecontrol.api.country.service;

import com.lifecontrol.api.country.dto.CountryRequest;
import com.lifecontrol.api.country.model.Country;
import com.lifecontrol.api.country.repository.CountryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ActiveProfiles;

import java.util.Objects;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Integration tests for CountryService caching behavior.
 * <p>
 * Uses {@link SpringBootTest} with the {@code test} profile so that
 * Redis auto-configuration is excluded and {@code spring.cache.type=simple}
 * applies. The {@link CacheManager} is a {@code SimpleCacheManager} backed by
 * in-memory {@code ConcurrentHashMap}.
 * <p>
 * Spring AOP processes the {@code @Cacheable} and {@code @CacheEvict}
 * annotations on {@link CountryService}, so these tests verify the actual
 * caching behavior: data is cached on first read, returned from cache on
 * subsequent reads, and evicted on mutation.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("CountryService Caching Integration Tests")
class CountryServiceCacheTest {

    @Autowired
    private CountryService countryService;

    @MockBean
    private CountryRepository countryRepository;

    @Autowired
    private CacheManager cacheManager;

    private final UUID testCountryId = UUID.randomUUID();

    @BeforeEach
    void clearCache() {
        // Clear all caches between tests to ensure clean state
        cacheManager.getCacheNames().stream()
                .map(cacheManager::getCache)
                .filter(Objects::nonNull)
                .forEach(cache -> cache.clear());
    }

    @Nested
    @DisplayName("Cache hit scenarios")
    class CacheHitTests {

        @Test
        @DisplayName("getAllCountries should return cached data on second call (no DB hit)")
        void getAllCountries_CacheHit_ReturnsCachedData() {
            // Arrange — first call populates cache
            var country = buildCountry(testCountryId, "MX", "México");
            when(countryRepository.findByEnabledTrue()).thenReturn(List.of(country));

            var firstResult = countryService.getAllCountries(false);

            // Re-mock repository to throw if called again
            when(countryRepository.findByEnabledTrue()).thenThrow(new RuntimeException("Should not reach DB"));

            // Act — second call should use cache
            var secondResult = countryService.getAllCountries(false);

            // Assert
            assertThat(secondResult).hasSize(1);
            assertThat(secondResult.get(0).countryCode()).isEqualTo("MX");
            verify(countryRepository, times(1)).findByEnabledTrue();
        }

        @Test
        @DisplayName("getCountryById should return cached data on second call (no DB hit)")
        void getCountryById_CacheHit_ReturnsCachedData() {
            // Arrange — first call populates cache
            var country = buildCountry(testCountryId, "MX", "México");
            when(countryRepository.findById(testCountryId)).thenReturn(java.util.Optional.of(country));

            countryService.getCountryById(testCountryId);

            // Re-mock repository to throw if called again
            when(countryRepository.findById(testCountryId)).thenThrow(new RuntimeException("Should not reach DB"));

            // Act — second call should use cache
            var secondResult = countryService.getCountryById(testCountryId);

            // Assert
            assertThat(secondResult).isNotNull();
            assertThat(secondResult.countryCode()).isEqualTo("MX");
            verify(countryRepository, times(1)).findById(testCountryId);
        }
    }

    @Nested
    @DisplayName("Cache miss scenarios")
    class CacheMissTests {

        @Test
        @DisplayName("getAllCountries should query DB on cache miss")
        void getAllCountries_CacheMiss_QueriesDatabase() {
            // Arrange
            var country = buildCountry(testCountryId, "MX", "México");
            when(countryRepository.findByEnabledTrue()).thenReturn(List.of(country));

            // Act — first call (cache miss)
            var result = countryService.getAllCountries(false);

            // Assert
            verify(countryRepository, times(1)).findByEnabledTrue();
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("getCountryById should query DB on cache miss")
        void getCountryById_CacheMiss_QueriesDatabase() {
            // Arrange
            var country = buildCountry(testCountryId, "MX", "México");
            when(countryRepository.findById(testCountryId)).thenReturn(java.util.Optional.of(country));

            // Act — first call (cache miss)
            var result = countryService.getCountryById(testCountryId);

            // Assert
            verify(countryRepository, times(1)).findById(testCountryId);
            assertThat(result).isNotNull();
        }
    }

    @Nested
    @DisplayName("Cache eviction scenarios")
    class CacheEvictionTests {

        @Test
        @DisplayName("createCountry should evict cache so next getAllCountries hits DB")
        void createCountry_EvictsCache() {
            // Arrange — populate cache
            var country = buildCountry(testCountryId, "MX", "México");
            when(countryRepository.findByEnabledTrue()).thenReturn(List.of(country));
            countryService.getAllCountries(false);

            // Create a new country (triggers @CacheEvict)
            var newCountry = buildCountry(UUID.randomUUID(), "US", "USA");
            when(countryRepository.existsByCountryCode("US")).thenReturn(false);
            when(countryRepository.save(any(Country.class))).thenReturn(newCountry);
            countryService.createCountry(new CountryRequest("US", "USA"));

            // Act — read after eviction should hit DB
            when(countryRepository.findByEnabledTrue()).thenReturn(List.of(country, newCountry));
            var result = countryService.getAllCountries(false);

            // Assert
            assertThat(result).hasSize(2);
            verify(countryRepository, times(2)).findByEnabledTrue();
        }

        @Test
        @DisplayName("deleteCountry should evict cache so next getAllCountries hits DB")
        void deleteCountry_EvictsCache() {
            // Arrange — populate cache
            var country = buildCountry(testCountryId, "MX", "México");
            when(countryRepository.findByEnabledTrue()).thenReturn(List.of(country));
            countryService.getAllCountries(false);

            // Delete a country (triggers @CacheEvict)
            when(countryRepository.findById(testCountryId)).thenReturn(java.util.Optional.of(country));
            when(countryRepository.save(any(Country.class))).thenReturn(country);
            countryService.deleteCountry(testCountryId);

            // Act — read after eviction should hit DB
            when(countryRepository.findByEnabledTrue()).thenReturn(List.of());
            var result = countryService.getAllCountries(false);

            // Assert
            assertThat(result).isEmpty();
            verify(countryRepository, times(2)).findByEnabledTrue();
        }
    }

    private Country buildCountry(UUID id, String code, String name) {
        return Country.builder()
                .id(id)
                .countryCode(code)
                .countryName(name)
                .enabled(true)
                .build();
    }
}
