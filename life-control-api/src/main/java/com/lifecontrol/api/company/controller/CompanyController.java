package com.lifecontrol.api.company.controller;

import com.lifecontrol.api.company.dto.CompanyCountryRequest;
import com.lifecontrol.api.company.dto.CompanyCountryResponse;
import com.lifecontrol.api.company.dto.CompanyRequest;
import com.lifecontrol.api.company.dto.CompanyResponse;
import com.lifecontrol.api.company.service.CompanyCountryService;
import com.lifecontrol.api.company.service.CompanyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/companies")
@Tag(name = "Company Management", description = "API for managing companies")
@PreAuthorize("hasAnyRole('lc-admin','lc-company','lc-company-country','lc-company-read')")
public class CompanyController {

    private final CompanyService companyService;
    private final CompanyCountryService companyCountryService;

    public CompanyController(CompanyService companyService, CompanyCountryService companyCountryService) {
        this.companyService = companyService;
        this.companyCountryService = companyCountryService;
    }

    @GetMapping
    @Operation(summary = "Get all companies", description = "Returns a paginated list of companies, optionally filtered by search term")
    public ResponseEntity<Page<CompanyResponse>> getAllCompanies(
            @PageableDefault(size = 12) Pageable pageable,
            @RequestParam(required = false) String search) {
        return ResponseEntity.ok(companyService.getAllCompanies(pageable, search));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get company by ID", description = "Returns a single company by its UUID")
    public ResponseEntity<CompanyResponse> getCompanyById(@PathVariable UUID id) {
        return ResponseEntity.ok(companyService.getCompanyById(id));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('lc-admin','lc-company')")
    @Operation(summary = "Create a new company", description = "Creates a new company with the provided details")
    public ResponseEntity<CompanyResponse> createCompany(@Valid @RequestBody CompanyRequest request) {
        CompanyResponse response = companyService.createCompany(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('lc-admin','lc-company')")
    @Operation(summary = "Update a company", description = "Updates an existing company with the provided details")
    public ResponseEntity<CompanyResponse> updateCompany(@PathVariable UUID id, @Valid @RequestBody CompanyRequest request) {
        CompanyResponse response = companyService.updateCompany(id, request);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('lc-admin','lc-company')")
    @Operation(summary = "Delete a company", description = "Soft-deletes a company by setting enabled to false")
    public ResponseEntity<Void> deleteCompany(@PathVariable UUID id) {
        companyService.deleteCompany(id);
        return ResponseEntity.noContent().build();
    }

    // --- CompanyCountry nested endpoints ---

    @GetMapping("/{companyId}/countries")
    @Operation(summary = "Get countries by company", description = "Returns all countries associated with a company")
    public ResponseEntity<List<CompanyCountryResponse>> getCompanyCountries(@PathVariable UUID companyId) {
        return ResponseEntity.ok(companyCountryService.getCountriesByCompanyId(companyId));
    }

    @PostMapping("/{companyId}/countries")
    @PreAuthorize("hasAnyRole('lc-admin','lc-company')")
    @Operation(summary = "Add country to company", description = "Associates a country with a company")
    public ResponseEntity<CompanyCountryResponse> addCompanyCountry(
            @PathVariable UUID companyId,
            @Valid @RequestBody CompanyCountryRequest request) {
        CompanyCountryResponse response = companyCountryService.addCountryToCompany(companyId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/{companyId}/countries/{id}")
    @PreAuthorize("hasAnyRole('lc-admin','lc-company','lc-company-country')")
    @Operation(summary = "Remove country from company", description = "Removes a country association from a company")
    public ResponseEntity<Void> removeCompanyCountry(
            @PathVariable UUID companyId,
            @PathVariable UUID id) {
        companyCountryService.removeCountryFromCompany(companyId, id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{companyId}/countries/{id}")
    @PreAuthorize("hasAnyRole('lc-admin','lc-company','lc-company-country')")
    @Operation(summary = "Update country assignment", description = "Updates an existing country assignment for a company")
    public ResponseEntity<CompanyCountryResponse> updateCompanyCountry(
            @PathVariable UUID companyId,
            @PathVariable UUID id,
            @Valid @RequestBody CompanyCountryRequest request) {
        CompanyCountryResponse response = companyCountryService.updateCountry(companyId, id, request);
        return ResponseEntity.ok(response);
    }
}