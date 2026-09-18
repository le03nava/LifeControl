package com.lifecontrol.api.store.controller;

import static com.lifecontrol.api.common.security.Roles.ADMIN;
import static com.lifecontrol.api.common.security.Roles.COMPANY;
import static com.lifecontrol.api.common.security.Roles.COMPANY_COUNTRY;
import static com.lifecontrol.api.common.security.Roles.COMPANY_REGION;
import static com.lifecontrol.api.common.security.Roles.COMPANY_STORE;
import static com.lifecontrol.api.common.security.Roles.COMPANY_STORE_READ;
import static com.lifecontrol.api.common.security.Roles.COMPANY_ZONE;

import com.lifecontrol.api.store.dto.CreateStoreZoneRequest;
import com.lifecontrol.api.store.dto.StoreZoneResponse;
import com.lifecontrol.api.store.dto.UpdateStoreZoneRequest;
import com.lifecontrol.api.store.service.StoreZoneService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * REST API for store zones (level 3 of the store location tree), nested under a store area.
 *
 * <p>Read endpoints additionally allow {@code lc-company-store-read}; write endpoints do not.</p>
 *
 * <p>The path segment {@code zones} belongs to the company hierarchy
 * ({@link com.lifecontrol.api.company.model.CompanyZone}); this resource is addressed by the
 * distinct {@code store-zones} segment and {@code storeZoneId} variable.</p>
 */
@RestController
@RequestMapping(
        "/api/companies/{companyId}/countries/{companyCountryId}/regions/{regionId}/zones/{zoneId}/stores/{storeId}/areas/{areaId}/store-zones")
@Tag(name = "Store Zone Management", description = "API for managing zones within a company's store area")
public class StoreZoneController {

    private final StoreZoneService storeZoneService;

    public StoreZoneController(StoreZoneService storeZoneService) {
        this.storeZoneService = storeZoneService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + COMPANY + "','" + COMPANY_COUNTRY + "','" + COMPANY_REGION + "','"
            + COMPANY_ZONE + "','" + COMPANY_STORE + "','" + COMPANY_STORE_READ + "')")
    @Operation(
            summary = "List all store zones",
            description =
                    "Returns zones for a company's store area, ordered by display order and zone code. Soft-deleted zones are excluded unless includeDisabled=true.")
    @ApiResponse(responseCode = "200", description = "List of store zones")
    @ApiResponse(responseCode = "404", description = "Store area or parent hierarchy not found")
    public ResponseEntity<List<StoreZoneResponse>> getAllZones(
            @PathVariable UUID companyId,
            @PathVariable UUID companyCountryId,
            @PathVariable UUID regionId,
            @PathVariable UUID zoneId,
            @PathVariable UUID storeId,
            @PathVariable UUID areaId,
            @RequestParam(defaultValue = "false") boolean includeDisabled) {
        return ResponseEntity.ok(storeZoneService.getAllZones(
                companyId, companyCountryId, regionId, zoneId, storeId, areaId, includeDisabled));
    }

    @GetMapping("/{storeZoneId}")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + COMPANY + "','" + COMPANY_COUNTRY + "','" + COMPANY_REGION + "','"
            + COMPANY_ZONE + "','" + COMPANY_STORE + "','" + COMPANY_STORE_READ + "')")
    @Operation(
            summary = "Get a store zone by ID",
            description =
                    "Returns a specific store zone. Access is verified via verifyCompanyStoreAccess over the whole hierarchy.")
    @ApiResponse(responseCode = "200", description = "Store zone found")
    @ApiResponse(responseCode = "404", description = "Store zone, store area or parent hierarchy not found")
    public ResponseEntity<StoreZoneResponse> getZoneById(
            @PathVariable UUID companyId,
            @PathVariable UUID companyCountryId,
            @PathVariable UUID regionId,
            @PathVariable UUID zoneId,
            @PathVariable UUID storeId,
            @PathVariable UUID areaId,
            @PathVariable UUID storeZoneId) {
        return ResponseEntity.ok(storeZoneService.getZoneById(
                companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + COMPANY + "','" + COMPANY_COUNTRY + "','" + COMPANY_REGION + "','"
            + COMPANY_ZONE + "','" + COMPANY_STORE + "')")
    @Operation(summary = "Create a new store zone")
    @ApiResponse(responseCode = "201", description = "Store zone created")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "404", description = "Store area or parent hierarchy not found")
    @ApiResponse(
            responseCode = "409",
            description = "Duplicate zone code in area, or the parent area or store is disabled")
    public ResponseEntity<StoreZoneResponse> createZone(
            @PathVariable UUID companyId,
            @PathVariable UUID companyCountryId,
            @PathVariable UUID regionId,
            @PathVariable UUID zoneId,
            @PathVariable UUID storeId,
            @PathVariable UUID areaId,
            @Valid @RequestBody CreateStoreZoneRequest request) {
        var response =
                storeZoneService.createZone(companyId, companyCountryId, regionId, zoneId, storeId, areaId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{storeZoneId}")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + COMPANY + "','" + COMPANY_COUNTRY + "','" + COMPANY_REGION + "','"
            + COMPANY_ZONE + "','" + COMPANY_STORE + "')")
    @Operation(summary = "Update a store zone")
    @ApiResponse(responseCode = "200", description = "Store zone updated")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "404", description = "Store zone, store area or parent hierarchy not found")
    @ApiResponse(responseCode = "409", description = "Duplicate zone code in area")
    public ResponseEntity<StoreZoneResponse> updateZone(
            @PathVariable UUID companyId,
            @PathVariable UUID companyCountryId,
            @PathVariable UUID regionId,
            @PathVariable UUID zoneId,
            @PathVariable UUID storeId,
            @PathVariable UUID areaId,
            @PathVariable UUID storeZoneId,
            @Valid @RequestBody UpdateStoreZoneRequest request) {
        return ResponseEntity.ok(storeZoneService.updateZone(
                companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId, request));
    }

    @DeleteMapping("/{storeZoneId}")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + COMPANY + "','" + COMPANY_COUNTRY + "','" + COMPANY_REGION + "','"
            + COMPANY_ZONE + "','" + COMPANY_STORE + "')")
    @Operation(summary = "Soft-delete a store zone", description = "Sets enabled=false; the row is preserved.")
    @ApiResponse(responseCode = "204", description = "Store zone deleted")
    @ApiResponse(responseCode = "404", description = "Store zone, store area or parent hierarchy not found")
    public ResponseEntity<Void> deleteZone(
            @PathVariable UUID companyId,
            @PathVariable UUID companyCountryId,
            @PathVariable UUID regionId,
            @PathVariable UUID zoneId,
            @PathVariable UUID storeId,
            @PathVariable UUID areaId,
            @PathVariable UUID storeZoneId) {
        storeZoneService.deleteZone(companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{storeZoneId}/enable")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + COMPANY + "','" + COMPANY_COUNTRY + "','" + COMPANY_REGION + "','"
            + COMPANY_ZONE + "','" + COMPANY_STORE + "')")
    @Operation(summary = "Re-enable a soft-deleted store zone")
    @ApiResponse(responseCode = "200", description = "Store zone re-enabled")
    @ApiResponse(responseCode = "404", description = "Store zone, store area or parent hierarchy not found")
    @ApiResponse(responseCode = "409", description = "Parent store area or store is disabled")
    public ResponseEntity<StoreZoneResponse> enableZone(
            @PathVariable UUID companyId,
            @PathVariable UUID companyCountryId,
            @PathVariable UUID regionId,
            @PathVariable UUID zoneId,
            @PathVariable UUID storeId,
            @PathVariable UUID areaId,
            @PathVariable UUID storeZoneId) {
        return ResponseEntity.ok(storeZoneService.enableZone(
                companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId));
    }
}
