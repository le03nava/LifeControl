package com.lifecontrol.api.store.controller;

import static com.lifecontrol.api.common.security.Roles.ADMIN;
import static com.lifecontrol.api.common.security.Roles.COMPANY;
import static com.lifecontrol.api.common.security.Roles.COMPANY_COUNTRY;
import static com.lifecontrol.api.common.security.Roles.COMPANY_REGION;
import static com.lifecontrol.api.common.security.Roles.COMPANY_STORE;
import static com.lifecontrol.api.common.security.Roles.COMPANY_STORE_READ;
import static com.lifecontrol.api.common.security.Roles.COMPANY_ZONE;

import com.lifecontrol.api.store.dto.CreateStoreAreaRequest;
import com.lifecontrol.api.store.dto.StoreAreaResponse;
import com.lifecontrol.api.store.dto.UpdateStoreAreaRequest;
import com.lifecontrol.api.store.service.StoreAreaService;
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
 * REST API for store areas (level 2 of the store location tree), nested under a company store.
 *
 * <p>Read endpoints additionally allow {@code lc-company-store-read}; write endpoints do not.</p>
 */
@RestController
@RequestMapping(
        "/api/companies/{companyId}/countries/{companyCountryId}/regions/{regionId}/zones/{zoneId}/stores/{storeId}/areas")
@Tag(name = "Store Area Management", description = "API for managing areas within a company's store")
public class StoreAreaController {

    private final StoreAreaService storeAreaService;

    public StoreAreaController(StoreAreaService storeAreaService) {
        this.storeAreaService = storeAreaService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + COMPANY + "','" + COMPANY_COUNTRY + "','" + COMPANY_REGION + "','"
            + COMPANY_ZONE + "','" + COMPANY_STORE + "','" + COMPANY_STORE_READ + "')")
    @Operation(
            summary = "List all store areas",
            description =
                    "Returns areas for a company's store, ordered by display order and area code. Soft-deleted areas are excluded unless includeDisabled=true.")
    @ApiResponse(responseCode = "200", description = "List of store areas")
    @ApiResponse(responseCode = "404", description = "Store or parent hierarchy not found")
    public ResponseEntity<List<StoreAreaResponse>> getAllAreas(
            @PathVariable UUID companyId,
            @PathVariable UUID companyCountryId,
            @PathVariable UUID regionId,
            @PathVariable UUID zoneId,
            @PathVariable UUID storeId,
            @RequestParam(defaultValue = "false") boolean includeDisabled) {
        return ResponseEntity.ok(
                storeAreaService.getAllAreas(companyId, companyCountryId, regionId, zoneId, storeId, includeDisabled));
    }

    /**
     * Reads a single area inside a store addressed by the full nested path.
     *
     * @deprecated the flat {@link StoreAreaFlatController#getAreaById(UUID)} endpoint
     *     ({@code GET /api/store-areas/{areaId}}) is the canonical read path for this resource. The
     *     nested route is retained for contract compatibility and is scheduled for removal in a
     *     later slice.
     */
    @Deprecated
    @GetMapping("/{areaId}")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + COMPANY + "','" + COMPANY_COUNTRY + "','" + COMPANY_REGION + "','"
            + COMPANY_ZONE + "','" + COMPANY_STORE + "','" + COMPANY_STORE_READ + "')")
    @Operation(
            summary = "Get a store area by ID",
            description =
                    "Returns a specific store area. Access is verified via verifyCompanyStoreAccess over the whole hierarchy.",
            deprecated = true)
    @ApiResponse(responseCode = "200", description = "Store area found")
    @ApiResponse(responseCode = "404", description = "Store area, store or parent hierarchy not found")
    public ResponseEntity<StoreAreaResponse> getAreaById(
            @PathVariable UUID companyId,
            @PathVariable UUID companyCountryId,
            @PathVariable UUID regionId,
            @PathVariable UUID zoneId,
            @PathVariable UUID storeId,
            @PathVariable UUID areaId) {
        return ResponseEntity.ok(
                storeAreaService.getAreaById(companyId, companyCountryId, regionId, zoneId, storeId, areaId));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + COMPANY + "','" + COMPANY_COUNTRY + "','" + COMPANY_REGION + "','"
            + COMPANY_ZONE + "','" + COMPANY_STORE + "')")
    @Operation(summary = "Create a new store area")
    @ApiResponse(responseCode = "201", description = "Store area created")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "404", description = "Store or parent hierarchy not found")
    @ApiResponse(responseCode = "409", description = "Duplicate area code in store, or the parent store is disabled")
    public ResponseEntity<StoreAreaResponse> createArea(
            @PathVariable UUID companyId,
            @PathVariable UUID companyCountryId,
            @PathVariable UUID regionId,
            @PathVariable UUID zoneId,
            @PathVariable UUID storeId,
            @Valid @RequestBody CreateStoreAreaRequest request) {
        var response = storeAreaService.createArea(companyId, companyCountryId, regionId, zoneId, storeId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{areaId}")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + COMPANY + "','" + COMPANY_COUNTRY + "','" + COMPANY_REGION + "','"
            + COMPANY_ZONE + "','" + COMPANY_STORE + "')")
    @Operation(summary = "Update a store area")
    @ApiResponse(responseCode = "200", description = "Store area updated")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "404", description = "Store area, store or parent hierarchy not found")
    @ApiResponse(responseCode = "409", description = "Duplicate area code in store")
    public ResponseEntity<StoreAreaResponse> updateArea(
            @PathVariable UUID companyId,
            @PathVariable UUID companyCountryId,
            @PathVariable UUID regionId,
            @PathVariable UUID zoneId,
            @PathVariable UUID storeId,
            @PathVariable UUID areaId,
            @Valid @RequestBody UpdateStoreAreaRequest request) {
        return ResponseEntity.ok(
                storeAreaService.updateArea(companyId, companyCountryId, regionId, zoneId, storeId, areaId, request));
    }

    @DeleteMapping("/{areaId}")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + COMPANY + "','" + COMPANY_COUNTRY + "','" + COMPANY_REGION + "','"
            + COMPANY_ZONE + "','" + COMPANY_STORE + "')")
    @Operation(summary = "Soft-delete a store area", description = "Sets enabled=false; the row is preserved.")
    @ApiResponse(responseCode = "204", description = "Store area deleted")
    @ApiResponse(responseCode = "404", description = "Store area, store or parent hierarchy not found")
    public ResponseEntity<Void> deleteArea(
            @PathVariable UUID companyId,
            @PathVariable UUID companyCountryId,
            @PathVariable UUID regionId,
            @PathVariable UUID zoneId,
            @PathVariable UUID storeId,
            @PathVariable UUID areaId) {
        storeAreaService.deleteArea(companyId, companyCountryId, regionId, zoneId, storeId, areaId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{areaId}/enable")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + COMPANY + "','" + COMPANY_COUNTRY + "','" + COMPANY_REGION + "','"
            + COMPANY_ZONE + "','" + COMPANY_STORE + "')")
    @Operation(summary = "Re-enable a soft-deleted store area")
    @ApiResponse(responseCode = "200", description = "Store area re-enabled")
    @ApiResponse(responseCode = "404", description = "Store area, store or parent hierarchy not found")
    @ApiResponse(responseCode = "409", description = "Parent store is disabled")
    public ResponseEntity<StoreAreaResponse> enableArea(
            @PathVariable UUID companyId,
            @PathVariable UUID companyCountryId,
            @PathVariable UUID regionId,
            @PathVariable UUID zoneId,
            @PathVariable UUID storeId,
            @PathVariable UUID areaId) {
        return ResponseEntity.ok(
                storeAreaService.enableArea(companyId, companyCountryId, regionId, zoneId, storeId, areaId));
    }
}
