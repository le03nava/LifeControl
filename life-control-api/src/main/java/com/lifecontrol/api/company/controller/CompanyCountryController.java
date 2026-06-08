package com.lifecontrol.api.company.controller;

import com.lifecontrol.api.company.dto.CompanyCountryRequest;
import com.lifecontrol.api.company.dto.CompanyCountryResponse;
import com.lifecontrol.api.company.service.CompanyCountryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/companies/{companyId}/countries")
@Tag(name = "Company Country Management", description = "API for managing country associations for a company")
public class CompanyCountryController {

    private final CompanyCountryService companyCountryService;

    public CompanyCountryController(CompanyCountryService companyCountryService) {
        this.companyCountryService = companyCountryService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('lc-admin','lc-company','lc-company-country','lc-company-country-read')")
    @Operation(summary = "Get countries by company",
            description = "Returns countries associated with a company. For lc-company-country and lc-company-country-read roles, results are scoped to the company_country_id values in the JWT.")
    public ResponseEntity<List<CompanyCountryResponse>> getCompanyCountries(@PathVariable UUID companyId) {
        return ResponseEntity.ok(companyCountryService.getCountriesByCompanyId(companyId));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('lc-admin','lc-company','lc-company-country')")
    @Operation(summary = "Add country to company", description = "Associates a country with a company")
    public ResponseEntity<CompanyCountryResponse> addCompanyCountry(
            @PathVariable UUID companyId,
            @Valid @RequestBody CompanyCountryRequest request) {
        CompanyCountryResponse response = companyCountryService.addCountryToCompany(companyId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('lc-admin','lc-company','lc-company-country')")
    @Operation(summary = "Update country assignment", description = "Updates an existing country assignment for a company")
    public ResponseEntity<CompanyCountryResponse> updateCompanyCountry(
            @PathVariable UUID companyId,
            @PathVariable UUID id,
            @Valid @RequestBody CompanyCountryRequest request) {
        CompanyCountryResponse response = companyCountryService.updateCountry(companyId, id, request);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('lc-admin','lc-company','lc-company-country')")
    @Operation(summary = "Remove country from company", description = "Removes a country association from a company")
    public ResponseEntity<Void> removeCompanyCountry(
            @PathVariable UUID companyId,
            @PathVariable UUID id) {
        companyCountryService.removeCountryFromCompany(companyId, id);
        return ResponseEntity.noContent().build();
    }
}
