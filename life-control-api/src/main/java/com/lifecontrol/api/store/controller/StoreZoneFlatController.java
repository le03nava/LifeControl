package com.lifecontrol.api.store.controller;

import static com.lifecontrol.api.common.security.Roles.ADMIN;
import static com.lifecontrol.api.common.security.Roles.COMPANY;
import static com.lifecontrol.api.common.security.Roles.COMPANY_COUNTRY;
import static com.lifecontrol.api.common.security.Roles.COMPANY_REGION;
import static com.lifecontrol.api.common.security.Roles.COMPANY_STORE;
import static com.lifecontrol.api.common.security.Roles.COMPANY_STORE_READ;
import static com.lifecontrol.api.common.security.Roles.COMPANY_ZONE;

import com.lifecontrol.api.store.dto.StoreZoneResponse;
import com.lifecontrol.api.store.service.StoreZoneService;
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
 * Flat, id-addressed REST lookup for store zones (level 3 of the store location tree).
 *
 * <p>The nested {@link StoreZoneController} already covers reads under the full
 * company &rarr; country &rarr; region &rarr; zone &rarr; store &rarr; area path. This controller
 * exposes the same resource from the frontend's {@code /api/store-zones/**} prefix so a zone can
 * be fetched by id alone; the service resolves the zone's chain from its JPA associations and
 * authorizes it through the same {@code verifyCompanyStoreAccess} check.</p>
 *
 * <p>Read-only, so it allows {@code lc-company-store-read} like the nested read endpoints.</p>
 */
@RestController
@RequestMapping("/api/store-zones")
@Tag(name = "Store Zone Lookup", description = "Flat, id-addressed lookup for store zones")
public class StoreZoneFlatController {

    private final StoreZoneService storeZoneService;

    public StoreZoneFlatController(StoreZoneService storeZoneService) {
        this.storeZoneService = storeZoneService;
    }

    @GetMapping("/{storeZoneId}")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + COMPANY + "','" + COMPANY_COUNTRY + "','" + COMPANY_REGION + "','"
            + COMPANY_ZONE + "','" + COMPANY_STORE + "','" + COMPANY_STORE_READ + "')")
    @Operation(
            summary = "Get a store zone by ID",
            description =
                    "Returns a store zone by its id. The zone's company, country, region, zone, store and area chain is resolved before access is verified via verifyCompanyStoreAccess.")
    @ApiResponse(responseCode = "200", description = "Store zone found")
    @ApiResponse(responseCode = "404", description = "Store zone or its store hierarchy not found")
    public ResponseEntity<StoreZoneResponse> getZoneById(@PathVariable UUID storeZoneId) {
        return ResponseEntity.ok(storeZoneService.getZoneById(storeZoneId));
    }
}
