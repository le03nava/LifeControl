package com.lifecontrol.api.purchaseorder.controller;

import com.lifecontrol.api.purchaseorder.dto.PurchaseOrderDetailRequest;
import com.lifecontrol.api.purchaseorder.dto.PurchaseOrderDetailResponse;
import com.lifecontrol.api.purchaseorder.dto.PurchaseOrderRequest;
import com.lifecontrol.api.purchaseorder.dto.PurchaseOrderResponse;
import com.lifecontrol.api.purchaseorder.dto.UpdatePurchaseOrderStatusRequest;
import com.lifecontrol.api.purchaseorder.service.PurchaseOrderService;
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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/purchase-orders")
@Tag(name = "Purchase Orders", description = "API for managing purchase orders and their line items")
public class PurchaseOrderController {

    private final PurchaseOrderService purchaseOrderService;

    public PurchaseOrderController(PurchaseOrderService purchaseOrderService) {
        this.purchaseOrderService = purchaseOrderService;
    }

    // ─── Purchase Order CRUD ────────────────────────────────────────────

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get all purchase orders", description = "Returns a paginated list, optionally filtered by search term on supplier or store name")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Paginated list of purchase orders")
    })
    public ResponseEntity<Page<PurchaseOrderResponse>> getAllPurchaseOrders(
            @PageableDefault(size = 12) Pageable pageable,
            @RequestParam(required = false) String search) {
        return ResponseEntity.ok(purchaseOrderService.getAllPurchaseOrders(pageable, search));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get purchase order by ID", description = "Returns a single purchase order with its details")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Purchase order found"),
        @ApiResponse(responseCode = "404", description = "Purchase order not found")
    })
    public ResponseEntity<PurchaseOrderResponse> getPurchaseOrderById(@PathVariable UUID id) {
        return ResponseEntity.ok(purchaseOrderService.getPurchaseOrderById(id));
    }

    @PostMapping
    @PreAuthorize("hasRole('life-control-admin')")
    @Operation(summary = "Create a purchase order", description = "Creates a new purchase order with Draft status. Detail totals are auto-calculated.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Purchase order created"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "404", description = "Referenced entity not found")
    })
    public ResponseEntity<PurchaseOrderResponse> createPurchaseOrder(
            @Valid @RequestBody PurchaseOrderRequest request) {
        var response = purchaseOrderService.createPurchaseOrder(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('life-control-admin')")
    @Operation(summary = "Update a purchase order", description = "Updates an existing purchase order header")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Purchase order updated"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "404", description = "Purchase order or FK entity not found")
    })
    public ResponseEntity<PurchaseOrderResponse> updatePurchaseOrder(
            @PathVariable UUID id,
            @Valid @RequestBody PurchaseOrderRequest request) {
        return ResponseEntity.ok(purchaseOrderService.updatePurchaseOrder(id, request));
    }

    @PatchMapping("/{id}/enable")
    @PreAuthorize("hasRole('life-control-admin')")
    @Operation(summary = "Re-enable a purchase order", description = "Re-enables a soft-deleted purchase order and all its details")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Purchase order re-enabled"),
        @ApiResponse(responseCode = "404", description = "Purchase order not found")
    })
    public ResponseEntity<PurchaseOrderResponse> enablePurchaseOrder(@PathVariable UUID id) {
        return ResponseEntity.ok(purchaseOrderService.enablePurchaseOrder(id));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('life-control-admin')")
    @Operation(summary = "Update purchase order status", description = "Updates the status of a purchase order. Validates status type and allowed transitions.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Status updated"),
        @ApiResponse(responseCode = "400", description = "Wrong status type"),
        @ApiResponse(responseCode = "404", description = "Purchase order or status not found"),
        @ApiResponse(responseCode = "409", description = "Invalid status transition")
    })
    public ResponseEntity<PurchaseOrderResponse> updatePurchaseOrderStatus(
            @PathVariable UUID id,
            @Valid @RequestBody UpdatePurchaseOrderStatusRequest request) {
        return ResponseEntity.ok(purchaseOrderService.updatePurchaseOrderStatus(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('life-control-admin')")
    @Operation(summary = "Delete a purchase order", description = "Soft-deletes a purchase order and all its details")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Purchase order deleted"),
        @ApiResponse(responseCode = "404", description = "Purchase order not found")
    })
    public ResponseEntity<Void> deletePurchaseOrder(@PathVariable UUID id) {
        purchaseOrderService.deletePurchaseOrder(id);
        return ResponseEntity.noContent().build();
    }

    // ─── Detail Nested Endpoints ────────────────────────────────────────

    @GetMapping("/{id}/details")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get purchase order details", description = "Returns all details for a purchase order")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "List of details"),
        @ApiResponse(responseCode = "404", description = "Purchase order not found")
    })
    public ResponseEntity<List<PurchaseOrderDetailResponse>> getPurchaseOrderDetails(@PathVariable UUID id) {
        return ResponseEntity.ok(purchaseOrderService.getPurchaseOrderDetails(id));
    }

    @PostMapping("/{id}/details")
    @PreAuthorize("hasRole('life-control-admin')")
    @Operation(summary = "Add a detail to a purchase order", description = "Adds a line item to a purchase order. Only allowed when order is in Draft status.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Detail created"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "404", description = "Purchase order or product not found"),
        @ApiResponse(responseCode = "409", description = "Purchase order is not in Draft status")
    })
    public ResponseEntity<PurchaseOrderDetailResponse> addDetail(
            @PathVariable UUID id,
            @Valid @RequestBody PurchaseOrderDetailRequest request) {
        var response = purchaseOrderService.addPurchaseOrderDetail(id, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}/details/{detailId}")
    @PreAuthorize("hasRole('life-control-admin')")
    @Operation(summary = "Update a detail", description = "Updates a purchase order detail. Only allowed when order is in Draft status.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Detail updated"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "404", description = "Purchase order or detail not found"),
        @ApiResponse(responseCode = "409", description = "Purchase order is not in Draft status")
    })
    public ResponseEntity<PurchaseOrderDetailResponse> updateDetail(
            @PathVariable UUID id,
            @PathVariable UUID detailId,
            @Valid @RequestBody PurchaseOrderDetailRequest request) {
        return ResponseEntity.ok(purchaseOrderService.updatePurchaseOrderDetail(id, detailId, request));
    }

    @DeleteMapping("/{id}/details/{detailId}")
    @PreAuthorize("hasRole('life-control-admin')")
    @Operation(summary = "Delete a detail", description = "Soft-deletes a purchase order detail. Only allowed when order is in Draft status.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Detail deleted"),
        @ApiResponse(responseCode = "404", description = "Purchase order or detail not found"),
        @ApiResponse(responseCode = "409", description = "Purchase order is not in Draft status")
    })
    public ResponseEntity<Void> deleteDetail(
            @PathVariable UUID id,
            @PathVariable UUID detailId) {
        purchaseOrderService.deletePurchaseOrderDetail(id, detailId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/details/{detailId}/status")
    @PreAuthorize("hasRole('life-control-admin')")
    @Operation(summary = "Update detail status", description = "Updates the status of a purchase order detail. Validates status type and allowed transitions.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Detail status updated"),
        @ApiResponse(responseCode = "400", description = "Wrong status type"),
        @ApiResponse(responseCode = "404", description = "Purchase order, detail, or status not found"),
        @ApiResponse(responseCode = "409", description = "Invalid status transition")
    })
    public ResponseEntity<PurchaseOrderDetailResponse> updateDetailStatus(
            @PathVariable UUID id,
            @PathVariable UUID detailId,
            @Valid @RequestBody UpdatePurchaseOrderStatusRequest request) {
        return ResponseEntity.ok(purchaseOrderService.updatePurchaseOrderDetailStatus(id, detailId, request));
    }
}
