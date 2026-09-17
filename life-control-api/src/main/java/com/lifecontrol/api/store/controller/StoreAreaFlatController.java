package com.lifecontrol.api.store.controller;

import static com.lifecontrol.api.common.security.Roles.ADMIN;
import static com.lifecontrol.api.common.security.Roles.COMPANY;
import static com.lifecontrol.api.common.security.Roles.COMPANY_COUNTRY;
import static com.lifecontrol.api.common.security.Roles.COMPANY_REGION;
import static com.lifecontrol.api.common.security.Roles.COMPANY_STORE;
import static com.lifecontrol.api.common.security.Roles.COMPANY_STORE_READ;
import static com.lifecontrol.api.common.security.Roles.COMPANY_ZONE;

import com.lifecontrol.api.store.dto.StoreAreaResponse;
import com.lifecontrol.api.store.service.StoreAreaService;
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
 * Flat, id-addressed REST lookup for store areas (level 2 of the store location tree).
 *
 * <p>The nested {@link StoreAreaController} already covers reads under the full
 * company &rarr; country &rarr; region &rarr; zone &rarr; store path. This controller exposes the
 * same resource from the frontend's {@code /api/store-areas/**} prefix so an area can be fetched by
 * id alone; the service resolves the area's chain from its JPA associations and authorizes it
 * through the same {@code verifyCompanyStoreAccess} check.</p>
 *
 * <p>Read-only, so it allows {@code lc-company-store-read} like the nested read endpoints.</p>
 */
@RestController
@RequestMapping("/api/store-areas")
@Tag(name = "Store Area Lookup", description = "Flat, id-addressed lookup for store areas")
public class StoreAreaFlatController {

    private final StoreAreaService storeAreaService;

    public StoreAreaFlatController(StoreAreaService storeAreaService) {
        this.storeAreaService = storeAreaService;
    }

    @GetMapping("/{areaId}")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + COMPANY + "','" + COMPANY_COUNTRY + "','" + COMPANY_REGION + "','"
            + COMPANY_ZONE + "','" + COMPANY_STORE + "','" + COMPANY_STORE_READ + "')")
    @Operation(
            summary = "Get a store area by ID",
            description =
                    "Returns a store area by its id. The area's company, country, region, zone and store chain is resolved before access is verified via verifyCompanyStoreAccess.")
    @ApiResponse(responseCode = "200", description = "Store area found")
    @ApiResponse(responseCode = "404", description = "Store area or its store hierarchy not found")
    public ResponseEntity<StoreAreaResponse> getAreaById(@PathVariable UUID areaId) {
        return ResponseEntity.ok(storeAreaService.getAreaById(areaId));
    }
}
