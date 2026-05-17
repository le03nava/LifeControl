package com.lifecontrol.api.country.service;

import com.lifecontrol.api.country.dto.CountryRequest;
import com.lifecontrol.api.country.dto.CountryResponse;
import com.lifecontrol.api.country.exception.CountryNotFoundException;
import com.lifecontrol.api.country.exception.DuplicateCountryException;
import com.lifecontrol.api.country.model.Country;
import com.lifecontrol.api.country.repository.CountryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class CountryService {

    private static final Logger logger = LoggerFactory.getLogger(CountryService.class);

    private final CountryRepository countryRepository;

    public CountryService(CountryRepository countryRepository) {
        this.countryRepository = countryRepository;
    }

    @Cacheable(value = "countries", key = "'all-' + #includeDisabled")
    @Transactional(readOnly = true)
    public List<CountryResponse> getAllCountries(boolean includeDisabled) {
        if (includeDisabled) {
            return countryRepository.findAll().stream()
                    .map(this::toResponse)
                    .toList();
        }
        return countryRepository.findByEnabledTrue().stream()
                .map(this::toResponse)
                .toList();
    }

    @Cacheable(value = "countries", key = "#id")
    @Transactional(readOnly = true)
    public CountryResponse getCountryById(UUID id) {
        return countryRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new CountryNotFoundException(id));
    }

    @CacheEvict(value = "countries", allEntries = true)
    @Transactional
    public CountryResponse createCountry(CountryRequest request) {
        logger.info("Creating country with code: {}", request.countryCode());

        if (countryRepository.existsByCountryCode(request.countryCode())) {
            throw new DuplicateCountryException(
                    "Ya existe un país con código: " + request.countryCode());
        }

        Country country = Country.builder()
                .countryCode(request.countryCode().toUpperCase())
                .countryName(request.countryName())
                .enabled(true)
                .build();

        Country saved = countryRepository.save(country);
        logger.info("Country created successfully with id: {}", saved.getId());

        return toResponse(saved);
    }

    @CacheEvict(value = "countries", allEntries = true)
    @Transactional
    public CountryResponse updateCountry(UUID id, CountryRequest request) {
        logger.info("Updating country with id: {}", id);

        Country country = countryRepository.findById(id)
                .orElseThrow(() -> new CountryNotFoundException(id));

        // Validate uniqueness of countryCode (excluding current)
        if (!country.getCountryCode().equals(request.countryCode())
                && countryRepository.existsByCountryCode(request.countryCode())) {
            throw new DuplicateCountryException(
                    "Ya existe un país con código: " + request.countryCode());
        }

        country.setCountryCode(request.countryCode().toUpperCase());
        country.setCountryName(request.countryName());

        Country updated = countryRepository.save(country);
        logger.info("Country updated successfully with id: {}", updated.getId());

        return toResponse(updated);
    }

    @CacheEvict(value = "countries", allEntries = true)
    @Transactional
    public void deleteCountry(UUID id) {
        logger.info("Soft-deleting country with id: {}", id);

        Country country = countryRepository.findById(id)
                .orElseThrow(() -> new CountryNotFoundException(id));

        country.setEnabled(false);
        countryRepository.save(country);

        logger.info("Country soft-deleted: id={}, code={}", id, country.getCountryCode());
    }

    private CountryResponse toResponse(Country country) {
        return new CountryResponse(
                country.getId(),
                country.getCountryCode(),
                country.getCountryName(),
                country.getEnabled(),
                country.getCreatedAt(),
                country.getUpdatedAt()
        );
    }
}
