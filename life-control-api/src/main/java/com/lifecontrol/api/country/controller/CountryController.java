package com.lifecontrol.api.country.controller;

import com.lifecontrol.api.country.dto.CountryRequest;
import com.lifecontrol.api.country.dto.CountryResponse;
import com.lifecontrol.api.country.service.CountryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/countries")
@Tag(name = "Country Management", description = "API for managing countries")
public class CountryController {

    private final CountryService countryService;

    public CountryController(CountryService countryService) {
        this.countryService = countryService;
    }

    @GetMapping
    @Operation(summary = "Get all countries", description = "Returns a list of countries. Use ?includeDisabled=true to include soft-deleted countries.")
    public ResponseEntity<List<CountryResponse>> getAllCountries(
            @RequestParam(name = "includeDisabled", defaultValue = "false") boolean includeDisabled) {
        return ResponseEntity.ok(countryService.getAllCountries(includeDisabled));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get country by ID", description = "Returns a single country by its UUID")
    public ResponseEntity<CountryResponse> getCountryById(@PathVariable UUID id) {
        return ResponseEntity.ok(countryService.getCountryById(id));
    }

    @PostMapping
    @Operation(summary = "Create a new country", description = "Creates a new country with the provided details")
    public ResponseEntity<CountryResponse> createCountry(@Valid @RequestBody CountryRequest request) {
        CountryResponse response = countryService.createCountry(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a country", description = "Updates an existing country")
    public ResponseEntity<CountryResponse> updateCountry(@PathVariable UUID id, @Valid @RequestBody CountryRequest request) {
        CountryResponse response = countryService.updateCountry(id, request);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a country", description = "Soft-deletes a country by setting enabled to false")
    public ResponseEntity<Void> deleteCountry(@PathVariable UUID id) {
        countryService.deleteCountry(id);
        return ResponseEntity.noContent().build();
    }
}
