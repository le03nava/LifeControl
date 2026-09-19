package com.lifecontrol.api.inventory.controller;

import static com.lifecontrol.api.common.security.Roles.ADMIN;
import static com.lifecontrol.api.common.security.Roles.COMPANY;
import static com.lifecontrol.api.common.security.Roles.COMPANY_COUNTRY;
import static com.lifecontrol.api.common.security.Roles.COMPANY_REGION;
import static com.lifecontrol.api.common.security.Roles.COMPANY_STORE;
import static com.lifecontrol.api.common.security.Roles.COMPANY_STORE_READ;
import static com.lifecontrol.api.common.security.Roles.COMPANY_ZONE;

import com.lifecontrol.api.inventory.dto.StoreInventorySettingsRequest;
import com.lifecontrol.api.inventory.dto.StoreInventorySettingsResponse;
import com.lifecontrol.api.inventory.service.StoreInventorySettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for a store's inventory settings, nested under the store.
 *
 * <p>The path is nested on purpose: {@code /api/companies/**} is already routed by the API gateway
 * (a flat {@code /api/company-stores/**} path is not), and the full path is what feeds
 * {@link com.lifecontrol.api.common.auth.CurrentUserContext#verifyCompanyStoreAccess}.</p>
 *
 * <p>Read endpoints additionally allow {@code lc-company-store-read}; write endpoints do not.</p>
 */
@RestController
@RequestMapping(
        "/api/companies/{companyId}/countries/{companyCountryId}/regions/{regionId}/zones/{zoneId}/stores/{storeId}/inventory-settings")
@Tag(name = "Store Inventory Settings Management", description = "API for a store's receiving and sales locations")
public class StoreInventorySettingsController {

    private final StoreInventorySettingsService storeInventorySettingsService;

    public StoreInventorySettingsController(StoreInventorySettingsService storeInventorySettingsService) {
        this.storeInventorySettingsService = storeInventorySettingsService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + COMPANY + "','" + COMPANY_COUNTRY + "','" + COMPANY_REGION + "','"
            + COMPANY_ZONE + "','" + COMPANY_STORE + "','" + COMPANY_STORE_READ + "')")
    @Operation(
            summary = "Get a store's inventory settings",
            description =
                    "Returns the store's receiving and sales locations. A store that has never been configured answers 404.")
    @ApiResponse(responseCode = "200", description = "Settings found")
    @ApiResponse(responseCode = "404", description = "Store not found, or the store has no settings yet")
    public ResponseEntity<StoreInventorySettingsResponse> getSettings(
            @PathVariable UUID companyId,
            @PathVariable UUID companyCountryId,
            @PathVariable UUID regionId,
            @PathVariable UUID zoneId,
            @PathVariable UUID storeId) {
        return ResponseEntity.ok(
                storeInventorySettingsService.getSettings(companyId, companyCountryId, regionId, zoneId, storeId));
    }

    @PutMapping
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + COMPANY + "','" + COMPANY_COUNTRY + "','" + COMPANY_REGION + "','"
            + COMPANY_ZONE + "','" + COMPANY_STORE + "')")
    @Operation(
            summary = "Create or update a store's inventory settings",
            description =
                    "Validates that both locations belong to the store, then creates the settings or updates them in place.")
    @ApiResponse(responseCode = "200", description = "Settings saved")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "404", description = "Store not found, or a location does not belong to the store")
    public ResponseEntity<StoreInventorySettingsResponse> upsertSettings(
            @PathVariable UUID companyId,
            @PathVariable UUID companyCountryId,
            @PathVariable UUID regionId,
            @PathVariable UUID zoneId,
            @PathVariable UUID storeId,
            @Valid @RequestBody StoreInventorySettingsRequest request) {
        return ResponseEntity.ok(storeInventorySettingsService.upsertSettings(
                companyId, companyCountryId, regionId, zoneId, storeId, request));
    }
}
