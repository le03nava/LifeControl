package com.lifecontrol.api.company.controller;

import com.lifecontrol.api.company.dto.CompanyRegionResponse;
import com.lifecontrol.api.company.dto.CreateCompanyRegionRequest;
import com.lifecontrol.api.company.dto.UpdateCompanyRegionRequest;
import com.lifecontrol.api.company.service.CompanyRegionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/companies/{companyId}/countries/{companyCountryId}/regions")
@Tag(name = "Company Region Management", description = "API for managing regions within a company's country presence")
@PreAuthorize("hasAnyRole('life-control-admin','life-control-country')")
public class CompanyRegionController {

    private final CompanyRegionService companyRegionService;

    public CompanyRegionController(CompanyRegionService companyRegionService) {
        this.companyRegionService = companyRegionService;
    }

    @GetMapping
    @Operation(summary = "List all regions", description = "Returns regions for a company's country presence")
    @ApiResponse(responseCode = "200", description = "List of regions")
    public ResponseEntity<List<CompanyRegionResponse>> getAllRegions(
            @PathVariable UUID companyId,
            @PathVariable UUID companyCountryId,
            @RequestParam(defaultValue = "false") boolean includeDisabled) {
        return ResponseEntity.ok(companyRegionService.getAllRegions(companyId, companyCountryId, includeDisabled));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a region by ID")
    @ApiResponse(responseCode = "200", description = "Region found")
    @ApiResponse(responseCode = "404", description = "Region not found")
    public ResponseEntity<CompanyRegionResponse> getRegionById(
            @PathVariable UUID companyId,
            @PathVariable UUID companyCountryId,
            @PathVariable UUID id) {
        return ResponseEntity.ok(companyRegionService.getRegionById(companyId, companyCountryId, id));
    }

    @PostMapping
    @Operation(summary = "Create a new region")
    @ApiResponse(responseCode = "201", description = "Region created")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "409", description = "Duplicate region code")
    public ResponseEntity<CompanyRegionResponse> createRegion(
            @PathVariable UUID companyId,
            @PathVariable UUID companyCountryId,
            @Valid @RequestBody CreateCompanyRegionRequest request) {
        CompanyRegionResponse response = companyRegionService.createRegion(companyId, companyCountryId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a region")
    @ApiResponse(responseCode = "200", description = "Region updated")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "404", description = "Region not found")
    @ApiResponse(responseCode = "409", description = "Duplicate region code")
    public ResponseEntity<CompanyRegionResponse> updateRegion(
            @PathVariable UUID companyId,
            @PathVariable UUID companyCountryId,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateCompanyRegionRequest request) {
        return ResponseEntity.ok(companyRegionService.updateRegion(companyId, companyCountryId, id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Soft-delete a region")
    @ApiResponse(responseCode = "204", description = "Region deleted")
    @ApiResponse(responseCode = "404", description = "Region not found")
    public ResponseEntity<Void> deleteRegion(
            @PathVariable UUID companyId,
            @PathVariable UUID companyCountryId,
            @PathVariable UUID id) {
        companyRegionService.deleteRegion(companyId, companyCountryId, id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Re-enable a soft-deleted region")
    @ApiResponse(responseCode = "200", description = "Region re-enabled")
    @ApiResponse(responseCode = "404", description = "Region not found")
    public ResponseEntity<CompanyRegionResponse> enableRegion(
            @PathVariable UUID companyId,
            @PathVariable UUID companyCountryId,
            @PathVariable UUID id) {
        return ResponseEntity.ok(companyRegionService.enableRegion(companyId, companyCountryId, id));
    }
}
