package com.lifecontrol.api.store.controller;

import static com.lifecontrol.api.common.security.Roles.ADMIN;
import static com.lifecontrol.api.common.security.Roles.COMPANY;
import static com.lifecontrol.api.common.security.Roles.COMPANY_COUNTRY;
import static com.lifecontrol.api.common.security.Roles.COMPANY_REGION;
import static com.lifecontrol.api.common.security.Roles.COMPANY_STORE;
import static com.lifecontrol.api.common.security.Roles.COMPANY_STORE_READ;
import static com.lifecontrol.api.common.security.Roles.COMPANY_ZONE;

import com.lifecontrol.api.store.dto.CompanyStoreResponse;
import com.lifecontrol.api.store.service.CompanyStoreService;
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
 * Flat, id-addressed REST lookup for a store.
 *
 * <p>The nested {@link CompanyStoreController} covers reads and writes under the full
 * company &rarr; country &rarr; region &rarr; zone path, which a caller holding only a store id cannot
 * supply. Three callers are in exactly that position (W3/`T20` of {@code company-scope-local-fallback}):
 * the profile's free-mode cascade, the purchase-order cascade and the sales-order create screen. This
 * controller exposes the store from the {@code /api/stores/**} prefix so it can be fetched by id alone;
 * the service resolves the store's chain from its JPA associations and authorizes it through the same
 * {@link com.lifecontrol.api.common.auth.CurrentUserContext#verifyCompanyStoreAccess} check the nested
 * paths use, so the flat path is not a second gate — it is the same one, entered from an id.</p>
 *
 * <p>Read-only, so it allows {@code lc-company-store-read} like the nested read endpoints.</p>
 */
@RestController
@RequestMapping("/api/stores")
@Tag(name = "Store Lookup", description = "Flat, id-addressed lookup for stores")
public class CompanyStoreFlatController {

    private final CompanyStoreService companyStoreService;

    public CompanyStoreFlatController(CompanyStoreService companyStoreService) {
        this.companyStoreService = companyStoreService;
    }

    @GetMapping("/{storeId}")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + COMPANY + "','" + COMPANY_COUNTRY + "','" + COMPANY_REGION + "','"
            + COMPANY_ZONE + "','" + COMPANY_STORE + "','" + COMPANY_STORE_READ + "')")
    @Operation(
            summary = "Get a store by ID",
            description =
                    "Returns a store by its id. The store's company, country, region and zone chain is resolved from the store itself before access is verified via verifyCompanyStoreAccess.")
    @ApiResponse(responseCode = "200", description = "Store found")
    @ApiResponse(responseCode = "404", description = "Store not found, or not reachable by the caller")
    public ResponseEntity<CompanyStoreResponse> getStoreById(@PathVariable UUID storeId) {
        return ResponseEntity.ok(companyStoreService.getStoreById(storeId));
    }
}
