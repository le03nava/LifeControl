package com.lifecontrol.api.inventory.controller;

import static com.lifecontrol.api.common.security.Roles.ADMIN;
import static com.lifecontrol.api.common.security.Roles.COMPANY;
import static com.lifecontrol.api.common.security.Roles.COMPANY_COUNTRY;
import static com.lifecontrol.api.common.security.Roles.COMPANY_REGION;
import static com.lifecontrol.api.common.security.Roles.COMPANY_STORE;
import static com.lifecontrol.api.common.security.Roles.COMPANY_STORE_READ;
import static com.lifecontrol.api.common.security.Roles.COMPANY_ZONE;

import com.lifecontrol.api.inventory.dto.StoreLocationSummaryResponse;
import com.lifecontrol.api.inventory.service.StoreInventorySettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lists the enabled locations of a whole store, nested under the store.
 *
 * <p>Every existing location listing is scoped to a store zone, so this is the only store-scoped
 * listing. It is the data source of the receiving/sales location picker for a store's inventory
 * settings. The path is nested for the same reason as
 * {@link StoreInventorySettingsController}: {@code /api/companies/**} is already routed by the API
 * gateway and the full path feeds
 * {@link com.lifecontrol.api.common.auth.CurrentUserContext#verifyCompanyStoreAccess}.</p>
 */
@RestController
@RequestMapping(
        "/api/companies/{companyId}/countries/{companyCountryId}/regions/{regionId}/zones/{zoneId}/stores/{storeId}/store-locations")
@Tag(name = "Store Inventory Settings Management", description = "API for a store's receiving and sales locations")
public class StoreLocationByStoreController {

    private final StoreInventorySettingsService storeInventorySettingsService;

    public StoreLocationByStoreController(StoreInventorySettingsService storeInventorySettingsService) {
        this.storeInventorySettingsService = storeInventorySettingsService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + COMPANY + "','" + COMPANY_COUNTRY + "','" + COMPANY_REGION + "','"
            + COMPANY_ZONE + "','" + COMPANY_STORE + "','" + COMPANY_STORE_READ + "')")
    @Operation(
            summary = "List the enabled locations of a store",
            description =
                    "Returns the store's enabled locations ordered by area, zone and then location, so the client can offer them as receiving or sales locations.")
    @ApiResponse(responseCode = "200", description = "List of store locations")
    @ApiResponse(responseCode = "404", description = "Store not found")
    public ResponseEntity<List<StoreLocationSummaryResponse>> listStoreLocations(
            @PathVariable UUID companyId,
            @PathVariable UUID companyCountryId,
            @PathVariable UUID regionId,
            @PathVariable UUID zoneId,
            @PathVariable UUID storeId) {
        return ResponseEntity.ok(storeInventorySettingsService.listStoreLocations(
                companyId, companyCountryId, regionId, zoneId, storeId));
    }
}
