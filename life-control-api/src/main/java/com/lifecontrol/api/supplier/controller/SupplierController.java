package com.lifecontrol.api.supplier.controller;

import com.lifecontrol.api.supplier.dto.SupplierRequest;
import com.lifecontrol.api.supplier.dto.SupplierResponse;
import com.lifecontrol.api.supplier.service.SupplierService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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

import java.util.UUID;

@RestController
@RequestMapping("/api/suppliers")
@Tag(name = "Supplier Management", description = "API for managing suppliers")
public class SupplierController {

    private final SupplierService supplierService;

    public SupplierController(SupplierService supplierService) {
        this.supplierService = supplierService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get all suppliers", description = "Returns a paginated list of suppliers, optionally filtered by search term")
    public ResponseEntity<Page<SupplierResponse>> getAllSuppliers(
            @PageableDefault(size = 12) Pageable pageable,
            @RequestParam(required = false) String search) {
        return ResponseEntity.ok(supplierService.getAllSuppliers(pageable, search));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get supplier by ID", description = "Returns a single supplier by its UUID")
    public ResponseEntity<SupplierResponse> getSupplierById(@PathVariable UUID id) {
        return ResponseEntity.ok(supplierService.getSupplierById(id));
    }

    @PostMapping
    @PreAuthorize("hasRole('life-control-admin')")
    @Operation(summary = "Create a new supplier", description = "Creates a new supplier with the provided details")
    public ResponseEntity<SupplierResponse> createSupplier(@Valid @RequestBody SupplierRequest request) {
        var response = supplierService.createSupplier(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('life-control-admin')")
    @Operation(summary = "Update a supplier", description = "Updates an existing supplier with the provided details")
    public ResponseEntity<SupplierResponse> updateSupplier(@PathVariable UUID id, @Valid @RequestBody SupplierRequest request) {
        var response = supplierService.updateSupplier(id, request);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('life-control-admin')")
    @Operation(summary = "Delete a supplier", description = "Soft-deletes a supplier by setting enabled to false")
    public ResponseEntity<Void> deleteSupplier(@PathVariable UUID id) {
        supplierService.deleteSupplier(id);
        return ResponseEntity.noContent().build();
    }
}
