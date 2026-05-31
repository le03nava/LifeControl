package com.lifecontrol.api.company.controller;

import com.lifecontrol.api.company.dto.CompanyZoneResponse;
import com.lifecontrol.api.company.dto.CreateCompanyZoneRequest;
import com.lifecontrol.api.company.dto.UpdateCompanyZoneRequest;
import com.lifecontrol.api.company.service.CompanyZoneService;
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
@RequestMapping("/api/companies/{companyId}/countries/{companyCountryId}/regions/{regionId}/zones")
@Tag(name = "Company Zone Management", description = "API for managing zones within a company's region")
@PreAuthorize("hasAnyRole('life-control-admin','life-control-country')")
public class CompanyZoneController {

    private final CompanyZoneService companyZoneService;

    public CompanyZoneController(CompanyZoneService companyZoneService) {
        this.companyZoneService = companyZoneService;
    }

    @GetMapping
    @Operation(summary = "List all zones", description = "Returns zones for a company's region")
    @ApiResponse(responseCode = "200", description = "List of zones")
    public ResponseEntity<List<CompanyZoneResponse>> getAllZones(
            @PathVariable UUID companyId,
            @PathVariable UUID companyCountryId,
            @PathVariable UUID regionId,
            @RequestParam(defaultValue = "false") boolean includeDisabled) {
        return ResponseEntity.ok(companyZoneService.getAllZones(companyId, companyCountryId, regionId, includeDisabled));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a zone by ID")
    @ApiResponse(responseCode = "200", description = "Zone found")
    @ApiResponse(responseCode = "404", description = "Zone not found")
    public ResponseEntity<CompanyZoneResponse> getZoneById(
            @PathVariable UUID companyId,
            @PathVariable UUID companyCountryId,
            @PathVariable UUID regionId,
            @PathVariable UUID id) {
        return ResponseEntity.ok(companyZoneService.getZoneById(companyId, companyCountryId, regionId, id));
    }

    @PostMapping
    @Operation(summary = "Create a new zone")
    @ApiResponse(responseCode = "201", description = "Zone created")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "409", description = "Duplicate zone code")
    public ResponseEntity<CompanyZoneResponse> createZone(
            @PathVariable UUID companyId,
            @PathVariable UUID companyCountryId,
            @PathVariable UUID regionId,
            @Valid @RequestBody CreateCompanyZoneRequest request) {
        CompanyZoneResponse response = companyZoneService.createZone(companyId, companyCountryId, regionId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a zone")
    @ApiResponse(responseCode = "200", description = "Zone updated")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "404", description = "Zone not found")
    @ApiResponse(responseCode = "409", description = "Duplicate zone code")
    public ResponseEntity<CompanyZoneResponse> updateZone(
            @PathVariable UUID companyId,
            @PathVariable UUID companyCountryId,
            @PathVariable UUID regionId,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateCompanyZoneRequest request) {
        return ResponseEntity.ok(companyZoneService.updateZone(companyId, companyCountryId, regionId, id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Soft-delete a zone")
    @ApiResponse(responseCode = "204", description = "Zone deleted")
    @ApiResponse(responseCode = "404", description = "Zone not found")
    public ResponseEntity<Void> deleteZone(
            @PathVariable UUID companyId,
            @PathVariable UUID companyCountryId,
            @PathVariable UUID regionId,
            @PathVariable UUID id) {
        companyZoneService.deleteZone(companyId, companyCountryId, regionId, id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Re-enable a soft-deleted zone")
    @ApiResponse(responseCode = "200", description = "Zone re-enabled")
    @ApiResponse(responseCode = "404", description = "Zone not found")
    public ResponseEntity<CompanyZoneResponse> enableZone(
            @PathVariable UUID companyId,
            @PathVariable UUID companyCountryId,
            @PathVariable UUID regionId,
            @PathVariable UUID id) {
        return ResponseEntity.ok(companyZoneService.enableZone(companyId, companyCountryId, regionId, id));
    }
}
