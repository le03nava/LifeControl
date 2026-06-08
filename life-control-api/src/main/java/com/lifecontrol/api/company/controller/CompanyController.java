package com.lifecontrol.api.company.controller;

import com.lifecontrol.api.company.dto.CompanyRequest;
import com.lifecontrol.api.company.dto.CompanyResponse;
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

import java.util.UUID;

@RestController
@RequestMapping("/api/companies")
@Tag(name = "Company Management", description = "API for managing companies")
public class CompanyController {

    private final CompanyService companyService;

    public CompanyController(CompanyService companyService) {
        this.companyService = companyService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('lc-admin','lc-company','lc-company-read')")
    @Operation(summary = "Get all companies", description = "Returns a paginated list of companies, optionally filtered by search term")
    public ResponseEntity<Page<CompanyResponse>> getAllCompanies(
            @PageableDefault(size = 12) Pageable pageable,
            @RequestParam(required = false) String search) {
        return ResponseEntity.ok(companyService.getAllCompanies(pageable, search));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('lc-admin','lc-company','lc-company-read')")
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

}