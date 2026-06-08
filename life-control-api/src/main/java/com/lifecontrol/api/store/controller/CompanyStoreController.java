package com.lifecontrol.api.store.controller;

import com.lifecontrol.api.store.dto.CompanyStoreResponse;
import com.lifecontrol.api.store.dto.CreateCompanyStoreRequest;
import com.lifecontrol.api.store.dto.UpdateCompanyStoreRequest;
import com.lifecontrol.api.store.service.CompanyStoreService;
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
@RequestMapping("/api/companies/{companyId}/countries/{companyCountryId}/regions/{regionId}/zones/{zoneId}/stores")
@Tag(name = "Company Store Management", description = "API for managing stores within a company's zone")
public class CompanyStoreController {

    private final CompanyStoreService companyStoreService;

    public CompanyStoreController(CompanyStoreService companyStoreService) {
        this.companyStoreService = companyStoreService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('lc-admin','lc-company','lc-company-country','lc-company-region','lc-company-zone','lc-company-store','lc-company-store-read')")
    @Operation(summary = "List all stores",
            description = "Returns stores for a company's zone. For lc-company-store and lc-company-store-read roles, results are scoped to the company_store_id values in the JWT.")
    @ApiResponse(responseCode = "200", description = "List of stores")
    public ResponseEntity<List<CompanyStoreResponse>> getAllStores(
            @PathVariable UUID companyId,
            @PathVariable UUID companyCountryId,
            @PathVariable UUID regionId,
            @PathVariable UUID zoneId,
            @RequestParam(defaultValue = "false") boolean includeDisabled) {
        return ResponseEntity.ok(companyStoreService.getAllStores(companyId, companyCountryId, regionId, zoneId, includeDisabled));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('lc-admin','lc-company','lc-company-country','lc-company-region','lc-company-zone','lc-company-store','lc-company-store-read')")
    @Operation(summary = "Get a store by ID",
            description = "Returns a specific store. Access is verified via verifyCompanyStoreAccess which checks store ownership for scoped roles.")
    @ApiResponse(responseCode = "200", description = "Store found")
    @ApiResponse(responseCode = "404", description = "Store not found")
    public ResponseEntity<CompanyStoreResponse> getStoreById(
            @PathVariable UUID companyId,
            @PathVariable UUID companyCountryId,
            @PathVariable UUID regionId,
            @PathVariable UUID zoneId,
            @PathVariable UUID id) {
        return ResponseEntity.ok(companyStoreService.getStoreById(companyId, companyCountryId, regionId, zoneId, id));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('lc-admin','lc-company','lc-company-country','lc-company-region','lc-company-zone','lc-company-store')")
    @Operation(summary = "Create a new store")
    @ApiResponse(responseCode = "201", description = "Store created")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "409", description = "Duplicate store name in zone")
    public ResponseEntity<CompanyStoreResponse> createStore(
            @PathVariable UUID companyId,
            @PathVariable UUID companyCountryId,
            @PathVariable UUID regionId,
            @PathVariable UUID zoneId,
            @Valid @RequestBody CreateCompanyStoreRequest request) {
        var response = companyStoreService.createStore(companyId, companyCountryId, regionId, zoneId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('lc-admin','lc-company','lc-company-country','lc-company-region','lc-company-zone','lc-company-store')")
    @Operation(summary = "Update a store")
    @ApiResponse(responseCode = "200", description = "Store updated")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "404", description = "Store not found")
    @ApiResponse(responseCode = "409", description = "Duplicate store name in zone")
    public ResponseEntity<CompanyStoreResponse> updateStore(
            @PathVariable UUID companyId,
            @PathVariable UUID companyCountryId,
            @PathVariable UUID regionId,
            @PathVariable UUID zoneId,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateCompanyStoreRequest request) {
        return ResponseEntity.ok(companyStoreService.updateStore(companyId, companyCountryId, regionId, zoneId, id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('lc-admin','lc-company','lc-company-country','lc-company-region','lc-company-zone','lc-company-store')")
    @Operation(summary = "Soft-delete a store")
    @ApiResponse(responseCode = "204", description = "Store deleted")
    @ApiResponse(responseCode = "404", description = "Store not found")
    public ResponseEntity<Void> deleteStore(
            @PathVariable UUID companyId,
            @PathVariable UUID companyCountryId,
            @PathVariable UUID regionId,
            @PathVariable UUID zoneId,
            @PathVariable UUID id) {
        companyStoreService.deleteStore(companyId, companyCountryId, regionId, zoneId, id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('lc-admin','lc-company','lc-company-country','lc-company-region','lc-company-zone','lc-company-store')")
    @Operation(summary = "Re-enable a soft-deleted store")
    @ApiResponse(responseCode = "200", description = "Store re-enabled")
    @ApiResponse(responseCode = "404", description = "Store not found")
    public ResponseEntity<CompanyStoreResponse> enableStore(
            @PathVariable UUID companyId,
            @PathVariable UUID companyCountryId,
            @PathVariable UUID regionId,
            @PathVariable UUID zoneId,
            @PathVariable UUID id) {
        return ResponseEntity.ok(companyStoreService.enableStore(companyId, companyCountryId, regionId, zoneId, id));
    }
}
