package com.lifecontrol.api.store.controller;

import static com.lifecontrol.api.common.security.Roles.ADMIN;
import static com.lifecontrol.api.common.security.Roles.COMPANY;
import static com.lifecontrol.api.common.security.Roles.COMPANY_COUNTRY;
import static com.lifecontrol.api.common.security.Roles.COMPANY_REGION;
import static com.lifecontrol.api.common.security.Roles.COMPANY_STORE;
import static com.lifecontrol.api.common.security.Roles.COMPANY_STORE_READ;
import static com.lifecontrol.api.common.security.Roles.COMPANY_ZONE;

import com.lifecontrol.api.store.dto.StoreLocationResponse;
import com.lifecontrol.api.store.service.StoreLocationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Flat, id-addressed REST lookup for store locations (level 4 of the store location tree).
 *
 * <p>The nested {@link StoreLocationController} already covers reads under the full
 * company &rarr; country &rarr; region &rarr; zone &rarr; store &rarr; area &rarr; zone path.
 * This controller exposes the same resource from the frontend's
 * {@code /api/store-locations/**} prefix so a location can be fetched by id alone; the service
 * resolves the location's chain from its JPA associations and authorizes it through the same
 * {@code verifyCompanyStoreAccess} check.</p>
 *
 * <p>Read-only, so it allows {@code lc-company-store-read} like the nested read endpoints.</p>
 */
@RestController
@RequestMapping("/api/store-locations")
@Tag(name = "Store Location Lookup", description = "Flat, id-addressed lookup for store locations")
public class StoreLocationFlatController {

    private final StoreLocationService storeLocationService;

    public StoreLocationFlatController(StoreLocationService storeLocationService) {
        this.storeLocationService = storeLocationService;
    }

    @GetMapping("/{storeLocationId}")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + COMPANY + "','" + COMPANY_COUNTRY + "','" + COMPANY_REGION + "','"
            + COMPANY_ZONE + "','" + COMPANY_STORE + "','" + COMPANY_STORE_READ + "')")
    @Operation(
            summary = "Get a store location by ID",
            description =
                    "Returns a store location by its id. The location's company, country, region, zone, store, area and store zone chain is resolved before access is verified via verifyCompanyStoreAccess.")
    @ApiResponse(responseCode = "200", description = "Store location found")
    @ApiResponse(responseCode = "404", description = "Store location or its store hierarchy not found")
    public ResponseEntity<StoreLocationResponse> getLocationById(@PathVariable UUID storeLocationId) {
        return ResponseEntity.ok(storeLocationService.getLocationById(storeLocationId));
    }
}
