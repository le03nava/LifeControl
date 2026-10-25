package com.lifecontrol.api.product.category.controller;

import static com.lifecontrol.api.common.security.Roles.ADMIN;

import com.lifecontrol.api.product.category.dto.ProductCategoryRequest;
import com.lifecontrol.api.product.category.dto.ProductCategoryResponse;
import com.lifecontrol.api.product.category.service.ProductCategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Company-scoped product category tree endpoints. Every path nests under
 * {@code /api/companies/{companyId}} so the company scope is part of the wire contract, exactly as the
 * department catalog does.
 *
 * <p>Reads require only authentication — the same gate the other company-scoped catalogs use, with the
 * company scope verified inside the service on every call. Writes require {@code lc-admin}: the
 * products ABM is admin-only ({@code ProductController}) and this tree is its classification, so the
 * two cannot disagree about who may curate the catalog. No dedicated {@code lc-product-category} role
 * is introduced, because that would widen the frozen position-role allowlist
 * ({@code GrantablePositionRoles}), a security boundary its own test defines as a deliberate decision
 * (record L5).</p>
 *
 * <p>{@code PATCH /{id}/enable} takes the {@code enabled} flag in the body and defaults to
 * {@code true} when absent, matching {@code DepartmentController}: the flag is explicit rather than
 * inferred from the verb, so one endpoint covers both directions.</p>
 */
@RestController
@RequestMapping("/api/companies/{companyId}/product-categories")
@Tag(name = "Product Category Management", description = "API for managing a company's product category tree")
public class ProductCategoryController {

    private final ProductCategoryService productCategoryService;

    public ProductCategoryController(ProductCategoryService productCategoryService) {
        this.productCategoryService = productCategoryService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "List the product category tree",
            description =
                    "Returns the company's categories as a depth-first ordered flat list: a parent immediately precedes its children, and each entry carries its depth and parentCategoryId. Disabled categories, and the subtree below them, are excluded unless includeDisabled is true.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "The company's category tree")})
    public ResponseEntity<List<ProductCategoryResponse>> getCategories(
            @PathVariable UUID companyId, @RequestParam(defaultValue = "false") boolean includeDisabled) {
        return ResponseEntity.ok(productCategoryService.getCategories(companyId, includeDisabled));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Get a product category by ID",
            description = "Returns one category of the company; a category of another company is not found")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Category found"),
        @ApiResponse(responseCode = "404", description = "Category not found")
    })
    public ResponseEntity<ProductCategoryResponse> getCategoryById(
            @PathVariable UUID companyId, @PathVariable UUID id) {
        return ResponseEntity.ok(productCategoryService.getCategoryById(companyId, id));
    }

    @PostMapping
    @PreAuthorize("hasRole('" + ADMIN + "')")
    @Operation(
            summary = "Create a product category",
            description =
                    "Creates a category in the company. Omit parentCategoryId for a root. The name must be free among its siblings, the parent must belong to the same company and be enabled, and the resulting level must not exceed three.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Category created"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "404", description = "Company or parent category not found"),
        @ApiResponse(responseCode = "409", description = "Duplicate sibling name, disabled parent, or level 4")
    })
    public ResponseEntity<ProductCategoryResponse> createCategory(
            @PathVariable UUID companyId, @Valid @RequestBody ProductCategoryRequest request) {
        var response = productCategoryService.createCategory(companyId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
