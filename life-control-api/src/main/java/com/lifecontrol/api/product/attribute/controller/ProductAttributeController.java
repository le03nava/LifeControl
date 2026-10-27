package com.lifecontrol.api.product.attribute.controller;

import com.lifecontrol.api.product.attribute.dto.ProductAttributeProfileResponse;
import com.lifecontrol.api.product.attribute.service.ProductAttributeCatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * The attribute profile that applies to one product category, as the forms need it.
 *
 * <p>Nested under the category it resolves for, and under {@code /api/companies/{companyId}} like every
 * other company-scoped catalog, so the company scope is part of the wire contract and a category of
 * another company is a {@code 404} rather than a {@code 200}.</p>
 *
 * <p>{@code 204 No Content} is the answer when the branch declares no profile, and that is a legitimate
 * outcome rather than an error: the seeded catalog attaches four profiles to eleven roots and leaves the
 * rest without one. A form that receives it renders no attribute inputs, which is exactly right.</p>
 *
 * <p>Reads require only authentication — the same gate the category tree uses, which verifies the company
 * scope inside the service on every call. Nothing here writes, so no {@code lc-admin} is asked for.</p>
 */
@RestController
@RequestMapping("/api/companies/{companyId}/product-categories/{categoryId}/attribute-profile")
@Tag(name = "Product Attribute Catalog", description = "Attributes a product category declares")
public class ProductAttributeController {

    private final ProductAttributeCatalogService productAttributeCatalogService;

    public ProductAttributeController(ProductAttributeCatalogService productAttributeCatalogService) {
        this.productAttributeCatalogService = productAttributeCatalogService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Get the attribute profile of a product category",
            description =
                    "Resolves the profile attached to the category's root — a company's own attachment wins over the seeded global one — and returns its enabled definitions partitioned by level: productAttributes for the product form, variantAttributes for the variant dialog. Each definition carries its kind, its required flag, its unit hint for measurements and its allowed values for enums. Answers 204 when the branch declares no profile.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The profile that applies to the category"),
        @ApiResponse(responseCode = "204", description = "The category's branch declares no profile"),
        @ApiResponse(responseCode = "404", description = "Category not found in that company")
    })
    public ResponseEntity<ProductAttributeProfileResponse> getAttributeProfile(
            @PathVariable UUID companyId, @PathVariable UUID categoryId) {
        return productAttributeCatalogService
                .findProfile(companyId, categoryId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
