package com.lifecontrol.api.paymentmethod.controller;

import com.lifecontrol.api.paymentmethod.dto.PaymentMethodRequest;
import com.lifecontrol.api.paymentmethod.dto.PaymentMethodResponse;
import com.lifecontrol.api.paymentmethod.service.PaymentMethodService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/payment-methods")
@Tag(name = "Payment Methods", description = "API for managing payment methods")
public class PaymentMethodController {

    private final PaymentMethodService paymentMethodService;

    public PaymentMethodController(PaymentMethodService paymentMethodService) {
        this.paymentMethodService = paymentMethodService;
    }

    @GetMapping
    @Operation(summary = "Get all payment methods", description = "Returns a list of all payment methods sorted by name")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "List of payment methods")
    })
    public ResponseEntity<List<PaymentMethodResponse>> getAllPaymentMethods() {
        return ResponseEntity.ok(paymentMethodService.getAllPaymentMethods());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get payment method by ID", description = "Returns a single payment method by its UUID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Payment method found"),
        @ApiResponse(responseCode = "404", description = "Payment method not found")
    })
    public ResponseEntity<PaymentMethodResponse> getPaymentMethodById(@PathVariable UUID id) {
        return ResponseEntity.ok(paymentMethodService.getPaymentMethodById(id));
    }

    @PostMapping
    @Operation(summary = "Create a new payment method", description = "Creates a new payment method with the provided details")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Payment method created"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "409", description = "Payment method name already exists")
    })
    public ResponseEntity<PaymentMethodResponse> createPaymentMethod(@Valid @RequestBody PaymentMethodRequest request) {
        var response = paymentMethodService.createPaymentMethod(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a payment method", description = "Updates an existing payment method")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Payment method updated"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "404", description = "Payment method not found"),
        @ApiResponse(responseCode = "409", description = "Payment method name already exists")
    })
    public ResponseEntity<PaymentMethodResponse> updatePaymentMethod(
            @PathVariable UUID id,
            @Valid @RequestBody PaymentMethodRequest request) {
        var response = paymentMethodService.updatePaymentMethod(id, request);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a payment method", description = "Hard-deletes a payment method")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Payment method deleted"),
        @ApiResponse(responseCode = "404", description = "Payment method not found")
    })
    public ResponseEntity<Void> deletePaymentMethod(@PathVariable UUID id) {
        paymentMethodService.deletePaymentMethod(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/enable")
    @Operation(summary = "Enable or disable a payment method", description = "Sets the enabled flag of a payment method")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Payment method status updated"),
        @ApiResponse(responseCode = "404", description = "Payment method not found")
    })
    public ResponseEntity<PaymentMethodResponse> setPaymentMethodEnabled(
            @PathVariable UUID id,
            @RequestBody Map<String, Boolean> body) {
        var enabled = body.getOrDefault("enabled", true);
        return ResponseEntity.ok(paymentMethodService.setPaymentMethodEnabled(id, enabled));
    }
}
