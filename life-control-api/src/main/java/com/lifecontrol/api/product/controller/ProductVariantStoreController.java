package com.lifecontrol.api.product.controller;

import static com.lifecontrol.api.common.security.Roles.ADMIN;
import static com.lifecontrol.api.common.security.Roles.SALES;

import com.lifecontrol.api.product.dto.ProductVariantStoreStockRequest;
import com.lifecontrol.api.product.dto.ProductVariantStoreStockResponse;
import com.lifecontrol.api.product.service.ProductVariantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Store-scoped endpoint of the variant split (decision D6(b)).
 *
 * <p>It lives in its own controller because the path root is {@code /api/variants}, not
 * {@code /api/products}: the variant definition create/update stays under
 * {@code ProductController}, while this upsert addresses the variant directly. The roles mirror the
 * variant routes ({@code lc-admin} / {@code lc-sales}).</p>
 */
@RestController
@RequestMapping("/api/variants")
@Tag(name = "Product Variant Store Stock", description = "API for the per-store stock and pricing of a variant")
public class ProductVariantStoreController {

    private final ProductVariantService productVariantService;

    public ProductVariantStoreController(ProductVariantService productVariantService) {
        this.productVariantService = productVariantService;
    }

    @PutMapping("/{variantId}/stores/{storeId}")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + SALES + "')")
    @Operation(
            summary = "Upsert the per-store row of a variant",
            description =
                    "Creates or updates the stock and prices of a product variant in one company store. The variant must exist (404) and the store must exist (404). Omitted fields keep their stored value.")
    public ResponseEntity<ProductVariantStoreStockResponse> upsertStoreStock(
            @PathVariable UUID variantId,
            @PathVariable UUID storeId,
            @Valid @RequestBody ProductVariantStoreStockRequest request) {
        return ResponseEntity.ok(productVariantService.upsertStoreStock(variantId, storeId, request));
    }
}
