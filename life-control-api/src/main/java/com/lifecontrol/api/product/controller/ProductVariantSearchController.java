package com.lifecontrol.api.product.controller;

import com.lifecontrol.api.product.dto.ProductVariantSearchResponse;
import com.lifecontrol.api.product.service.ProductVariantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/product-variants")
@Tag(name = "Product Variant Search", description = "API for searching product variants across stores")
public class ProductVariantSearchController {

    private final ProductVariantService productVariantService;

    public ProductVariantSearchController(ProductVariantService productVariantService) {
        this.productVariantService = productVariantService;
    }

    @GetMapping("/search")
    @PreAuthorize("hasAnyRole('lc-admin','lc-sales')")
    @Operation(summary = "Search product variants",
               description = "Searches product variants by barcode, SKU, variant name, or product name, scoped to a company store. Returns paginated results enriched with product name and SKU.")
    public ResponseEntity<Page<ProductVariantSearchResponse>> searchVariants(
            @RequestParam String q,
            @RequestParam UUID storeId,
            @PageableDefault(size = 20) Pageable pageable) {
        var result = productVariantService.searchVariants(q, storeId, pageable);
        return ResponseEntity.ok(result);
    }
}
