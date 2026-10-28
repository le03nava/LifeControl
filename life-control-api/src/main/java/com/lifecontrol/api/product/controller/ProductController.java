package com.lifecontrol.api.product.controller;

import static com.lifecontrol.api.common.security.Roles.ADMIN;
import static com.lifecontrol.api.common.security.Roles.PRODUCT_SUPPLIER;
import static com.lifecontrol.api.common.security.Roles.SALES;

import com.lifecontrol.api.product.dto.ProductRequest;
import com.lifecontrol.api.product.dto.ProductResponse;
import com.lifecontrol.api.product.dto.ProductVariantRequest;
import com.lifecontrol.api.product.dto.ProductVariantResponse;
import com.lifecontrol.api.product.service.ProductService;
import com.lifecontrol.api.product.service.ProductVariantService;
import com.lifecontrol.api.product.supplier.dto.ProductSupplierRequest;
import com.lifecontrol.api.product.supplier.dto.ProductSupplierResponse;
import com.lifecontrol.api.product.supplier.dto.SupplierProductResponse;
import com.lifecontrol.api.product.supplier.service.ProductSupplierService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Company-scoped product catalog endpoints, plus the supplier relations and variant definitions that
 * hang off one product.
 *
 * <p>The path nests under {@code /api/companies/{companyId}} so the company scope is part of the wire
 * contract, exactly as {@link com.lifecontrol.api.product.category.controller.ProductCategoryController}
 * does. Every handler forwards the company to its service, which verifies the caller's access to it
 * before it loads or writes anything; the {@code @PreAuthorize} role checks below therefore stay what
 * they were — a role is the gate, never the scope.</p>
 *
 * <p>Because the product is addressed <b>through</b> its company, a product of another company is a
 * 404 on every one of these paths, and a categorical refusal is never distinguishable from a product
 * that does not exist. The nested paths ({@code {productId}/suppliers}, {@code {productId}/variants})
 * resolve the product first, for the same reason.</p>
 */
@RestController
@RequestMapping("/api/companies/{companyId}/products")
@Tag(name = "Product Management", description = "API for managing a company's products")
public class ProductController {

    private final ProductService productService;
    private final ProductSupplierService productSupplierService;
    private final ProductVariantService productVariantService;

    public ProductController(
            ProductService productService,
            ProductSupplierService productSupplierService,
            ProductVariantService productVariantService) {
        this.productService = productService;
        this.productSupplierService = productSupplierService;
        this.productVariantService = productVariantService;
    }

    @GetMapping
    @Operation(
            summary = "Get the company's products",
            description =
                    "Returns a paginated list of the company's products. Use ?search=term to filter by name or SKU and ?includeDisabled=true to include soft-deleted products. A product of another company is never listed.")
    public ResponseEntity<Page<ProductResponse>> listProducts(
            @PathVariable UUID companyId,
            @PageableDefault(size = 12) Pageable pageable,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "false") boolean includeDisabled) {
        return ResponseEntity.ok(productService.listProducts(companyId, pageable, search, includeDisabled));
    }

    @GetMapping("/by-supplier/{supplierId}")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + PRODUCT_SUPPLIER + "')")
    @Operation(
            summary = "Get the company's products by supplier",
            description =
                    "Returns the company's products associated with a supplier, optionally filtered by product name or SKU. Products of other companies that share the supplier are excluded.")
    public ResponseEntity<List<SupplierProductResponse>> getProductsBySupplier(
            @PathVariable UUID companyId,
            @PathVariable UUID supplierId,
            @RequestParam(required = false) String search) {
        return ResponseEntity.ok(productSupplierService.listProductsBySupplier(companyId, supplierId, search));
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "Get product by ID",
            description = "Returns a single product of the company; a product of another company is not found")
    public ResponseEntity<ProductResponse> getProduct(@PathVariable UUID companyId, @PathVariable UUID id) {
        return ResponseEntity.ok(productService.findProduct(companyId, id));
    }

    @PostMapping
    @PreAuthorize("hasRole('" + ADMIN + "')")
    @Operation(
            summary = "Create a new product",
            description =
                    "Creates a product owned by the company. The SKU must be free within the company and the category, when one is sent, must belong to it.")
    public ResponseEntity<ProductResponse> createProduct(
            @PathVariable UUID companyId, @Valid @RequestBody ProductRequest request) {
        var response = productService.createProduct(companyId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('" + ADMIN + "')")
    @Operation(
            summary = "Update a product",
            description =
                    "Updates a product of the company. Attributes are partially merged — only provided keys are updated. A category of another company is refused.")
    public ResponseEntity<ProductResponse> updateProduct(
            @PathVariable UUID companyId, @PathVariable UUID id, @Valid @RequestBody ProductRequest request) {
        return ResponseEntity.ok(productService.updateProduct(companyId, id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('" + ADMIN + "')")
    @Operation(summary = "Delete a product", description = "Soft-deletes a product by setting enabled to false")
    public ResponseEntity<Void> deleteProduct(@PathVariable UUID companyId, @PathVariable UUID id) {
        productService.deleteProduct(companyId, id);
        return ResponseEntity.noContent().build();
    }

    // --- ProductSupplier nested endpoints ---

    @GetMapping("/{productId}/suppliers")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + PRODUCT_SUPPLIER + "')")
    @Operation(summary = "Get suppliers by product", description = "Returns all suppliers associated with a product")
    public ResponseEntity<List<ProductSupplierResponse>> getProductSuppliers(
            @PathVariable UUID companyId, @PathVariable UUID productId) {
        return ResponseEntity.ok(productSupplierService.listSuppliersByProductId(companyId, productId));
    }

    @PostMapping("/{productId}/suppliers")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + PRODUCT_SUPPLIER + "')")
    @Operation(
            summary = "Add supplier to product",
            description = "Associates a supplier with a product including pricing metadata")
    public ResponseEntity<ProductSupplierResponse> addProductSupplier(
            @PathVariable UUID companyId,
            @PathVariable UUID productId,
            @Valid @RequestBody ProductSupplierRequest request) {
        var response = productSupplierService.addSupplierToProduct(companyId, productId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{productId}/suppliers/{id}")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + PRODUCT_SUPPLIER + "')")
    @Operation(
            summary = "Update supplier assignment",
            description = "Updates pricing and main flag for a product-supplier relation")
    public ResponseEntity<ProductSupplierResponse> updateProductSupplier(
            @PathVariable UUID companyId,
            @PathVariable UUID productId,
            @PathVariable UUID id,
            @Valid @RequestBody ProductSupplierRequest request) {
        var response = productSupplierService.updateSupplier(companyId, productId, id, request);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{productId}/suppliers/{id}")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + PRODUCT_SUPPLIER + "')")
    @Operation(
            summary = "Remove supplier from product",
            description = "Removes a supplier association from a product (hard delete)")
    public ResponseEntity<Void> removeProductSupplier(
            @PathVariable UUID companyId, @PathVariable UUID productId, @PathVariable UUID id) {
        productSupplierService.removeSupplierFromProduct(companyId, productId, id);
        return ResponseEntity.noContent().build();
    }

    // --- ProductVariant nested endpoints ---

    @GetMapping("/{productId}/variants")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + SALES + "')")
    @Operation(
            summary = "List variants for a product",
            description =
                    "Returns a paginated list of variants for a given product. Use ?storeId=<uuid> to narrow the list to one store and populate the store prices and stock. Omitting storeId returns the product's GLOBAL definitions with the store-scoped fields null; ?includeDisabled=true then also lists soft-deleted definitions so they can be re-enabled. The store-scoped and search reads always exclude disabled variants.")
    public ResponseEntity<Page<ProductVariantResponse>> listVariants(
            @PathVariable UUID companyId,
            @PathVariable UUID productId,
            @RequestParam(required = false) UUID storeId,
            @RequestParam(defaultValue = "false") boolean includeDisabled,
            @PageableDefault(size = 12) Pageable pageable) {
        return ResponseEntity.ok(
                productVariantService.listVariants(companyId, productId, storeId, includeDisabled, pageable));
    }

    @PostMapping("/{productId}/variants")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + SALES + "')")
    @Operation(
            summary = "Create variant for a product",
            description =
                    "Creates the GLOBAL definition of a product variant (barcode + name). It does not take a store: stock and prices are written per store through PUT /api/variants/{variantId}/stores/{storeId}.")
    public ResponseEntity<ProductVariantResponse> createVariant(
            @PathVariable UUID companyId,
            @PathVariable UUID productId,
            @Valid @RequestBody ProductVariantRequest request) {
        var response = productVariantService.createVariant(companyId, productId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{productId}/variants/{variantId}")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + SALES + "')")
    @Operation(
            summary = "Get variant by ID",
            description = "Returns a single product variant by its UUID, scoped to a product of the company")
    public ResponseEntity<ProductVariantResponse> getVariant(
            @PathVariable UUID companyId, @PathVariable UUID productId, @PathVariable UUID variantId) {
        return ResponseEntity.ok(productVariantService.getVariant(companyId, productId, variantId));
    }

    @PutMapping("/{productId}/variants/{variantId}")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + SALES + "')")
    @Operation(
            summary = "Update variant",
            description =
                    "Updates the GLOBAL definition of a product variant. The per-store stock and prices are not part of this payload.")
    public ResponseEntity<ProductVariantResponse> updateVariant(
            @PathVariable UUID companyId,
            @PathVariable UUID productId,
            @PathVariable UUID variantId,
            @Valid @RequestBody ProductVariantRequest request) {
        return ResponseEntity.ok(productVariantService.updateVariant(companyId, productId, variantId, request));
    }

    @DeleteMapping("/{productId}/variants/{variantId}")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + SALES + "')")
    @Operation(summary = "Delete variant", description = "Soft-deletes a product variant by setting enabled to false")
    public ResponseEntity<Void> deleteVariant(
            @PathVariable UUID companyId, @PathVariable UUID productId, @PathVariable UUID variantId) {
        productVariantService.deleteVariant(companyId, productId, variantId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{productId}/variants/{variantId}/enable")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + SALES + "')")
    @Operation(summary = "Re-enable a variant", description = "Re-enables a soft-deleted product variant")
    public ResponseEntity<ProductVariantResponse> enableVariant(
            @PathVariable UUID companyId, @PathVariable UUID productId, @PathVariable UUID variantId) {
        return ResponseEntity.ok(productVariantService.enableVariant(companyId, productId, variantId));
    }
}
