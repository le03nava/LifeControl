package com.lifecontrol.api.customer.controller;

import com.lifecontrol.api.customer.dto.CustomerRequest;
import com.lifecontrol.api.customer.dto.CustomerResponse;
import com.lifecontrol.api.customer.service.CustomerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/customers")
@Tag(name = "Customer Management", description = "API for managing customers")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @GetMapping
    @PreAuthorize("hasRole('lc-sales')")
    @Operation(summary = "Get all customers", description = "Returns a paginated list, optionally filtered by search term on name or email")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Paginated list of customers")
    })
    public ResponseEntity<Page<CustomerResponse>> getAllCustomers(
            @PageableDefault(size = 12) Pageable pageable,
            @RequestParam(required = false) String search) {
        return ResponseEntity.ok(customerService.getAllCustomers(pageable, search));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('lc-sales')")
    @Operation(summary = "Get customer by ID", description = "Returns a single customer")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Customer found"),
        @ApiResponse(responseCode = "404", description = "Customer not found")
    })
    public ResponseEntity<CustomerResponse> getCustomerById(@PathVariable UUID id) {
        return ResponseEntity.ok(customerService.getCustomerById(id));
    }

    @PostMapping
    @PreAuthorize("hasRole('lc-sales')")
    @Operation(summary = "Create a customer", description = "Creates a new customer")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Customer created"),
        @ApiResponse(responseCode = "400", description = "Validation error")
    })
    public ResponseEntity<CustomerResponse> createCustomer(
            @Valid @RequestBody CustomerRequest request) {
        var response = customerService.createCustomer(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('lc-sales')")
    @Operation(summary = "Update a customer", description = "Updates an existing customer")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Customer updated"),
        @ApiResponse(responseCode = "404", description = "Customer not found")
    })
    public ResponseEntity<CustomerResponse> updateCustomer(
            @PathVariable UUID id,
            @Valid @RequestBody CustomerRequest request) {
        return ResponseEntity.ok(customerService.updateCustomer(id, request));
    }

    @PatchMapping("/{id}/enable")
    @PreAuthorize("hasRole('lc-sales')")
    @Operation(summary = "Re-enable a customer", description = "Re-enables a soft-deleted customer")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Customer re-enabled"),
        @ApiResponse(responseCode = "404", description = "Customer not found")
    })
    public ResponseEntity<CustomerResponse> enableCustomer(@PathVariable UUID id) {
        return ResponseEntity.ok(customerService.enableCustomer(id));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('lc-sales')")
    @Operation(summary = "Delete a customer", description = "Soft-deletes a customer")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Customer deleted"),
        @ApiResponse(responseCode = "404", description = "Customer not found")
    })
    public ResponseEntity<Void> deleteCustomer(@PathVariable UUID id) {
        customerService.deleteCustomer(id);
        return ResponseEntity.noContent().build();
    }
}
