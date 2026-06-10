package com.lifecontrol.api.product.controller;

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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/products")
@Tag(name = "Product Management", description = "API for managing products")
public class ProductController {

    private final ProductService productService;
    private final ProductSupplierService productSupplierService;
    private final ProductVariantService productVariantService;

    public ProductController(ProductService productService,
                             ProductSupplierService productSupplierService,
                             ProductVariantService productVariantService) {
        this.productService = productService;
        this.productSupplierService = productSupplierService;
        this.productVariantService = productVariantService;
    }

    @GetMapping
    @Operation(summary = "Get all products", description = "Returns a paginated list of products. Use ?search=term to filter by name or SKU and ?includeDisabled=true to include soft-deleted products.")
    public ResponseEntity<Page<ProductResponse>> listProducts(
            @PageableDefault(size = 12) Pageable pageable,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "false") boolean includeDisabled) {
        return ResponseEntity.ok(productService.listProducts(pageable, search, includeDisabled));
    }

    @GetMapping("/by-supplier/{supplierId}")
    @PreAuthorize("hasAnyRole('life-control-admin','life-control-country')")
    @Operation(summary = "Get products by supplier",
               description = "Returns all products associated with a supplier, optionally filtered by product name or SKU")
    public ResponseEntity<List<SupplierProductResponse>> getProductsBySupplier(
            @PathVariable UUID supplierId,
            @RequestParam(required = false) String search) {
        return ResponseEntity.ok(productSupplierService.listProductsBySupplier(supplierId, search));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get product by ID", description = "Returns a single product by its UUID")
    public ResponseEntity<ProductResponse> getProduct(@PathVariable UUID id) {
        return ResponseEntity.ok(productService.findProduct(id));
    }

    @PostMapping
    @Operation(summary = "Create a new product", description = "Creates a new product with the provided details. SKU must be unique.")
    public ResponseEntity<ProductResponse> createProduct(@Valid @RequestBody ProductRequest request) {
        var response = productService.createProduct(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a product", description = "Updates an existing product. Attributes are partially merged — only provided keys are updated.")
    public ResponseEntity<ProductResponse> updateProduct(@PathVariable UUID id, @Valid @RequestBody ProductRequest request) {
        return ResponseEntity.ok(productService.updateProduct(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a product", description = "Soft-deletes a product by setting enabled to false")
    public ResponseEntity<Void> deleteProduct(@PathVariable UUID id) {
        productService.deleteProduct(id);
        return ResponseEntity.noContent().build();
    }

    // --- ProductSupplier nested endpoints ---

    @GetMapping("/{productId}/suppliers")
    @PreAuthorize("hasAnyRole('life-control-admin','life-control-country')")
    @Operation(summary = "Get suppliers by product", description = "Returns all suppliers associated with a product")
    public ResponseEntity<List<ProductSupplierResponse>> getProductSuppliers(@PathVariable UUID productId) {
        return ResponseEntity.ok(productSupplierService.listSuppliersByProductId(productId));
    }

    @PostMapping("/{productId}/suppliers")
    @PreAuthorize("hasAnyRole('life-control-admin','life-control-country')")
    @Operation(summary = "Add supplier to product", description = "Associates a supplier with a product including pricing metadata")
    public ResponseEntity<ProductSupplierResponse> addProductSupplier(
            @PathVariable UUID productId,
            @Valid @RequestBody ProductSupplierRequest request) {
        var response = productSupplierService.addSupplierToProduct(productId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{productId}/suppliers/{id}")
    @PreAuthorize("hasAnyRole('life-control-admin','life-control-country')")
    @Operation(summary = "Update supplier assignment", description = "Updates pricing and main flag for a product-supplier relation")
    public ResponseEntity<ProductSupplierResponse> updateProductSupplier(
            @PathVariable UUID productId,
            @PathVariable UUID id,
            @Valid @RequestBody ProductSupplierRequest request) {
        var response = productSupplierService.updateSupplier(productId, id, request);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{productId}/suppliers/{id}")
    @PreAuthorize("hasAnyRole('life-control-admin','life-control-country')")
    @Operation(summary = "Remove supplier from product", description = "Removes a supplier association from a product (hard delete)")
    public ResponseEntity<Void> removeProductSupplier(
            @PathVariable UUID productId,
            @PathVariable UUID id) {
        productSupplierService.removeSupplierFromProduct(productId, id);
        return ResponseEntity.noContent().build();
    }

    // --- ProductVariant nested endpoints ---

    @GetMapping("/{productId}/variants")
    @PreAuthorize("hasRole('lc-sales')")
    @Operation(summary = "List variants for a product", description = "Returns a paginated list of variants for a given product")
    public ResponseEntity<Page<ProductVariantResponse>> listVariants(
            @PathVariable UUID productId,
            @PageableDefault(size = 12) Pageable pageable) {
        return ResponseEntity.ok(productVariantService.listVariants(productId, pageable));
    }

    @PostMapping("/{productId}/variants")
    @PreAuthorize("hasRole('lc-sales')")
    @Operation(summary = "Create variant for a product", description = "Creates a new product variant for the specified product")
    public ResponseEntity<ProductVariantResponse> createVariant(
            @PathVariable UUID productId,
            @Valid @RequestBody ProductVariantRequest request) {
        var response = productVariantService.createVariant(productId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{productId}/variants/{variantId}")
    @PreAuthorize("hasRole('lc-sales')")
    @Operation(summary = "Get variant by ID", description = "Returns a single product variant by its UUID, scoped to a product")
    public ResponseEntity<ProductVariantResponse> getVariant(
            @PathVariable UUID productId,
            @PathVariable UUID variantId) {
        return ResponseEntity.ok(productVariantService.getVariant(productId, variantId));
    }

    @PutMapping("/{productId}/variants/{variantId}")
    @PreAuthorize("hasRole('lc-sales')")
    @Operation(summary = "Update variant", description = "Updates an existing product variant")
    public ResponseEntity<ProductVariantResponse> updateVariant(
            @PathVariable UUID productId,
            @PathVariable UUID variantId,
            @Valid @RequestBody ProductVariantRequest request) {
        return ResponseEntity.ok(productVariantService.updateVariant(productId, variantId, request));
    }

    @DeleteMapping("/{productId}/variants/{variantId}")
    @PreAuthorize("hasRole('lc-sales')")
    @Operation(summary = "Delete variant", description = "Soft-deletes a product variant by setting enabled to false")
    public ResponseEntity<Void> deleteVariant(
            @PathVariable UUID productId,
            @PathVariable UUID variantId) {
        productVariantService.deleteVariant(productId, variantId);
        return ResponseEntity.noContent().build();
    }
}
