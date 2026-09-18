package com.lifecontrol.api.store.controller;

import static com.lifecontrol.api.common.security.Roles.ADMIN;
import static com.lifecontrol.api.common.security.Roles.COMPANY;
import static com.lifecontrol.api.common.security.Roles.COMPANY_COUNTRY;
import static com.lifecontrol.api.common.security.Roles.COMPANY_REGION;
import static com.lifecontrol.api.common.security.Roles.COMPANY_STORE;
import static com.lifecontrol.api.common.security.Roles.COMPANY_STORE_READ;
import static com.lifecontrol.api.common.security.Roles.COMPANY_ZONE;

import com.lifecontrol.api.store.dto.CreateStoreLocationRequest;
import com.lifecontrol.api.store.dto.StoreLocationResponse;
import com.lifecontrol.api.store.dto.UpdateStoreLocationRequest;
import com.lifecontrol.api.store.service.StoreLocationService;
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
 * REST API for store locations (level 4 of the store location tree), nested under a store zone.
 *
 * <p>Read endpoints additionally allow {@code lc-company-store-read}; write endpoints do not.</p>
 *
 * <p>The path segment {@code zones} belongs to the company hierarchy
 * ({@link com.lifecontrol.api.company.model.CompanyZone}); this resource is addressed by the
 * distinct {@code store-zones} / {@code store-locations} segments and the {@code storeZoneId} /
 * {@code storeLocationId} variables.</p>
 */
@RestController
@RequestMapping(
        "/api/companies/{companyId}/countries/{companyCountryId}/regions/{regionId}/zones/{zoneId}/stores/{storeId}/areas/{areaId}/store-zones/{storeZoneId}/store-locations")
@Tag(name = "Store Location Management", description = "API for managing locations within a company's store zone")
public class StoreLocationController {

    private final StoreLocationService storeLocationService;

    public StoreLocationController(StoreLocationService storeLocationService) {
        this.storeLocationService = storeLocationService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + COMPANY + "','" + COMPANY_COUNTRY + "','" + COMPANY_REGION + "','"
            + COMPANY_ZONE + "','" + COMPANY_STORE + "','" + COMPANY_STORE_READ + "')")
    @Operation(
            summary = "List all store locations",
            description =
                    "Returns locations for a company's store zone, ordered by display order and location code. Soft-deleted locations are excluded unless includeDisabled=true.")
    @ApiResponse(responseCode = "200", description = "List of store locations")
    @ApiResponse(responseCode = "404", description = "Store zone or parent hierarchy not found")
    public ResponseEntity<List<StoreLocationResponse>> getAllLocations(
            @PathVariable UUID companyId,
            @PathVariable UUID companyCountryId,
            @PathVariable UUID regionId,
            @PathVariable UUID zoneId,
            @PathVariable UUID storeId,
            @PathVariable UUID areaId,
            @PathVariable UUID storeZoneId,
            @RequestParam(defaultValue = "false") boolean includeDisabled) {
        return ResponseEntity.ok(storeLocationService.getAllLocations(
                companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId, includeDisabled));
    }

    /**
     * Reads a single location inside a zone addressed by the full nested path.
     *
     * @deprecated the flat {@link StoreLocationFlatController#getLocationById(UUID)} endpoint
     *     ({@code GET /api/store-locations/{storeLocationId}}) is the canonical read path for this
     *     resource. The nested route is retained for contract compatibility and is scheduled for
     *     removal in a later slice.
     */
    @Deprecated
    @GetMapping("/{storeLocationId}")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + COMPANY + "','" + COMPANY_COUNTRY + "','" + COMPANY_REGION + "','"
            + COMPANY_ZONE + "','" + COMPANY_STORE + "','" + COMPANY_STORE_READ + "')")
    @Operation(
            summary = "Get a store location by ID",
            description =
                    "Returns a specific store location. Access is verified via verifyCompanyStoreAccess over the whole hierarchy.",
            deprecated = true)
    @ApiResponse(responseCode = "200", description = "Store location found")
    @ApiResponse(responseCode = "404", description = "Store location, store zone or parent hierarchy not found")
    public ResponseEntity<StoreLocationResponse> getLocationById(
            @PathVariable UUID companyId,
            @PathVariable UUID companyCountryId,
            @PathVariable UUID regionId,
            @PathVariable UUID zoneId,
            @PathVariable UUID storeId,
            @PathVariable UUID areaId,
            @PathVariable UUID storeZoneId,
            @PathVariable UUID storeLocationId) {
        return ResponseEntity.ok(storeLocationService.getLocationById(
                companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId, storeLocationId));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + COMPANY + "','" + COMPANY_COUNTRY + "','" + COMPANY_REGION + "','"
            + COMPANY_ZONE + "','" + COMPANY_STORE + "')")
    @Operation(summary = "Create a new store location")
    @ApiResponse(responseCode = "201", description = "Store location created")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "404", description = "Store zone or parent hierarchy not found")
    @ApiResponse(
            responseCode = "409",
            description = "Duplicate location code in zone, or the parent zone, area or store is disabled")
    public ResponseEntity<StoreLocationResponse> createLocation(
            @PathVariable UUID companyId,
            @PathVariable UUID companyCountryId,
            @PathVariable UUID regionId,
            @PathVariable UUID zoneId,
            @PathVariable UUID storeId,
            @PathVariable UUID areaId,
            @PathVariable UUID storeZoneId,
            @Valid @RequestBody CreateStoreLocationRequest request) {
        var response = storeLocationService.createLocation(
                companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{storeLocationId}")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + COMPANY + "','" + COMPANY_COUNTRY + "','" + COMPANY_REGION + "','"
            + COMPANY_ZONE + "','" + COMPANY_STORE + "')")
    @Operation(summary = "Update a store location")
    @ApiResponse(responseCode = "200", description = "Store location updated")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "404", description = "Store location, store zone or parent hierarchy not found")
    @ApiResponse(responseCode = "409", description = "Duplicate location code in zone")
    public ResponseEntity<StoreLocationResponse> updateLocation(
            @PathVariable UUID companyId,
            @PathVariable UUID companyCountryId,
            @PathVariable UUID regionId,
            @PathVariable UUID zoneId,
            @PathVariable UUID storeId,
            @PathVariable UUID areaId,
            @PathVariable UUID storeZoneId,
            @PathVariable UUID storeLocationId,
            @Valid @RequestBody UpdateStoreLocationRequest request) {
        return ResponseEntity.ok(storeLocationService.updateLocation(
                companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId, storeLocationId, request));
    }

    @DeleteMapping("/{storeLocationId}")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + COMPANY + "','" + COMPANY_COUNTRY + "','" + COMPANY_REGION + "','"
            + COMPANY_ZONE + "','" + COMPANY_STORE + "')")
    @Operation(summary = "Soft-delete a store location", description = "Sets enabled=false; the row is preserved.")
    @ApiResponse(responseCode = "204", description = "Store location deleted")
    @ApiResponse(responseCode = "404", description = "Store location, store zone or parent hierarchy not found")
    public ResponseEntity<Void> deleteLocation(
            @PathVariable UUID companyId,
            @PathVariable UUID companyCountryId,
            @PathVariable UUID regionId,
            @PathVariable UUID zoneId,
            @PathVariable UUID storeId,
            @PathVariable UUID areaId,
            @PathVariable UUID storeZoneId,
            @PathVariable UUID storeLocationId) {
        storeLocationService.deleteLocation(
                companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId, storeLocationId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{storeLocationId}/enable")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + COMPANY + "','" + COMPANY_COUNTRY + "','" + COMPANY_REGION + "','"
            + COMPANY_ZONE + "','" + COMPANY_STORE + "')")
    @Operation(summary = "Re-enable a soft-deleted store location")
    @ApiResponse(responseCode = "200", description = "Store location re-enabled")
    @ApiResponse(responseCode = "404", description = "Store location, store zone or parent hierarchy not found")
    @ApiResponse(responseCode = "409", description = "Parent store zone, store area or store is disabled")
    public ResponseEntity<StoreLocationResponse> enableLocation(
            @PathVariable UUID companyId,
            @PathVariable UUID companyCountryId,
            @PathVariable UUID regionId,
            @PathVariable UUID zoneId,
            @PathVariable UUID storeId,
            @PathVariable UUID areaId,
            @PathVariable UUID storeZoneId,
            @PathVariable UUID storeLocationId) {
        return ResponseEntity.ok(storeLocationService.enableLocation(
                companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId, storeLocationId));
    }
}
