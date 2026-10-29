package com.lifecontrol.api.supplier.controller;

import static com.lifecontrol.api.common.security.Roles.ADMIN;
import static com.lifecontrol.api.common.security.Roles.PRODUCT_SUPPLIER;

import com.lifecontrol.api.supplier.dto.SupplierRequest;
import com.lifecontrol.api.supplier.dto.SupplierResponse;
import com.lifecontrol.api.supplier.service.SupplierService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Company-scoped supplier catalog endpoints.
 *
 * <p>The path nests under {@code /api/companies/{companyId}} so the company scope is part of the wire
 * contract, exactly as {@link com.lifecontrol.api.product.controller.ProductController} does. Every
 * handler forwards the company to its service, which verifies the caller's access to it before it loads
 * or writes anything; the {@code @PreAuthorize} role checks below therefore stay what they were — a
 * role is the gate, never the scope. The unscoped {@code /api/suppliers} surface is gone rather than
 * kept beside this one.</p>
 *
 * <p>Because the supplier is addressed <b>through</b> its company, a supplier of another company is a
 * 404 on every one of these paths, and a categorical refusal is never distinguishable from a supplier
 * that does not exist.</p>
 */
@RestController
@RequestMapping("/api/companies/{companyId}/suppliers")
@Tag(name = "Supplier Management", description = "API for managing a company's suppliers")
public class SupplierController {

    private final SupplierService supplierService;

    public SupplierController(SupplierService supplierService) {
        this.supplierService = supplierService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Get the company's suppliers",
            description =
                    "Returns a paginated list of the company's suppliers. Use ?search=term to filter by name, RFC, razon social or email and ?includeDisabled=true to include soft-deleted suppliers. A supplier of another company is never listed.")
    public ResponseEntity<Page<SupplierResponse>> getAllSuppliers(
            @PathVariable UUID companyId,
            @PageableDefault(size = 12) Pageable pageable,
            @RequestParam(required = false) String search,
            @RequestParam(name = "includeDisabled", defaultValue = "false") boolean includeDisabled) {
        return ResponseEntity.ok(supplierService.getAllSuppliers(companyId, pageable, search, includeDisabled));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Get one of the company's suppliers by ID",
            description = "Returns a single supplier of the company named by the path, by its UUID")
    public ResponseEntity<SupplierResponse> getSupplierById(@PathVariable UUID companyId, @PathVariable UUID id) {
        return ResponseEntity.ok(supplierService.getSupplierById(companyId, id));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + PRODUCT_SUPPLIER + "')")
    @Operation(
            summary = "Create a supplier in the company",
            description =
                    "Creates a supplier owned by the company named by the path. The RFC is unique inside the company, not globally.")
    public ResponseEntity<SupplierResponse> createSupplier(
            @PathVariable UUID companyId, @Valid @RequestBody SupplierRequest request) {
        var response = supplierService.createSupplier(companyId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + PRODUCT_SUPPLIER + "')")
    @Operation(
            summary = "Update one of the company's suppliers",
            description = "Updates a supplier of the company named by the path with the provided details")
    public ResponseEntity<SupplierResponse> updateSupplier(
            @PathVariable UUID companyId, @PathVariable UUID id, @Valid @RequestBody SupplierRequest request) {
        var response = supplierService.updateSupplier(companyId, id, request);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + PRODUCT_SUPPLIER + "')")
    @Operation(
            summary = "Delete one of the company's suppliers",
            description = "Soft-deletes a supplier of the company named by the path by setting enabled to false")
    public ResponseEntity<Void> deleteSupplier(@PathVariable UUID companyId, @PathVariable UUID id) {
        supplierService.deleteSupplier(companyId, id);
        return ResponseEntity.noContent().build();
    }
}
