package com.lifecontrol.api.salesorder.controller;

import com.lifecontrol.api.salesorder.dto.ChargeSalesOrderRequest;
import com.lifecontrol.api.salesorder.dto.SalesOrderItemRequest;
import com.lifecontrol.api.salesorder.dto.SalesOrderItemResponse;
import com.lifecontrol.api.salesorder.dto.SalesOrderRequest;
import com.lifecontrol.api.salesorder.dto.SalesOrderResponse;
import com.lifecontrol.api.salesorder.dto.UpdateSalesOrderStatusRequest;
import com.lifecontrol.api.salesorder.service.SalesOrderService;
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
@RequestMapping("/api/sales-orders")
@Tag(name = "Sales Orders", description = "API for managing sales orders and their line items")
public class SalesOrderController {

    private final SalesOrderService salesOrderService;

    public SalesOrderController(SalesOrderService salesOrderService) {
        this.salesOrderService = salesOrderService;
    }

    // ─── Sales Order CRUD ────────────────────────────────────────────────

    @GetMapping
    @PreAuthorize("hasAnyRole('lc-admin','lc-sales')")
    @Operation(summary = "Get all sales orders", description = "Returns a paginated list, optionally filtered by search term on order number")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Paginated list of sales orders")
    })
    public ResponseEntity<Page<SalesOrderResponse>> getAllSalesOrders(
            @PageableDefault(size = 12) Pageable pageable,
            @RequestParam(required = false) String search) {
        return ResponseEntity.ok(salesOrderService.getAllSalesOrders(pageable, search));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('lc-admin','lc-sales')")
    @Operation(summary = "Get sales order by ID", description = "Returns a single sales order with its items")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Sales order found"),
        @ApiResponse(responseCode = "404", description = "Sales order not found")
    })
    public ResponseEntity<SalesOrderResponse> getSalesOrderById(@PathVariable UUID id) {
        return ResponseEntity.ok(salesOrderService.getSalesOrderById(id));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('lc-admin','lc-sales')")
    @Operation(summary = "Create a sales order", description = "Creates a new sales order with Draft status. Items can be added via the nested endpoint.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Sales order created"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "404", description = "Referenced entity not found")
    })
    public ResponseEntity<SalesOrderResponse> createSalesOrder(
            @Valid @RequestBody SalesOrderRequest request) {
        var response = salesOrderService.createSalesOrder(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('lc-admin','lc-sales')")
    @Operation(summary = "Update a sales order", description = "Updates an existing sales order header. Optionally accepts an inline items array to atomically add, update, or delete line items.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Sales order updated"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "404", description = "Sales order or FK entity not found")
    })
    public ResponseEntity<SalesOrderResponse> updateSalesOrder(
            @PathVariable UUID id,
            @Valid @RequestBody SalesOrderRequest request) {
        return ResponseEntity.ok(salesOrderService.updateSalesOrder(id, request));
    }

    @PatchMapping("/{id}/enable")
    @PreAuthorize("hasAnyRole('lc-admin','lc-sales')")
    @Operation(summary = "Re-enable a sales order", description = "Re-enables a soft-deleted sales order")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Sales order re-enabled"),
        @ApiResponse(responseCode = "404", description = "Sales order not found")
    })
    public ResponseEntity<SalesOrderResponse> enableSalesOrder(@PathVariable UUID id) {
        return ResponseEntity.ok(salesOrderService.enableSalesOrder(id));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('lc-admin','lc-sales')")
    @Operation(summary = "Update sales order status", description = "Updates the status of a sales order. Validates status type and allowed transitions (Draft → Pending → Completed/Cancelled).")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Status updated"),
        @ApiResponse(responseCode = "400", description = "Wrong status type"),
        @ApiResponse(responseCode = "404", description = "Sales order or status not found"),
        @ApiResponse(responseCode = "409", description = "Invalid status transition")
    })
    public ResponseEntity<SalesOrderResponse> updateSalesOrderStatus(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateSalesOrderStatusRequest request) {
        return ResponseEntity.ok(salesOrderService.updateSalesOrderStatus(id, request));
    }

    @PatchMapping("/{id}/charge")
    @PreAuthorize("hasAnyRole('lc-admin','lc-sales')")
    @Operation(summary = "Charge a sales order", description = "Atomically transitions a Pending order to Completed, all non-Cancelled items to Added, and records the payment method. Validates that the order is in Pending status and the payment method exists.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Sales order charged successfully"),
        @ApiResponse(responseCode = "400", description = "Order is not in Pending status"),
        @ApiResponse(responseCode = "404", description = "Sales order or payment method not found")
    })
    public ResponseEntity<SalesOrderResponse> chargeSalesOrder(
            @PathVariable UUID id,
            @Valid @RequestBody ChargeSalesOrderRequest request) {
        return ResponseEntity.ok(salesOrderService.chargeSalesOrder(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('lc-admin','lc-sales')")
    @Operation(summary = "Delete a sales order", description = "Soft-deletes a sales order and all its items")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Sales order deleted"),
        @ApiResponse(responseCode = "404", description = "Sales order not found")
    })
    public ResponseEntity<Void> deleteSalesOrder(@PathVariable UUID id) {
        salesOrderService.deleteSalesOrder(id);
        return ResponseEntity.noContent().build();
    }

    // ─── Item Nested Endpoints ────────────────────────────────────────

    @GetMapping("/{id}/items")
    @PreAuthorize("hasAnyRole('lc-admin','lc-sales')")
    @Operation(summary = "Get sales order items", description = "Returns all items for a sales order")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "List of items"),
        @ApiResponse(responseCode = "404", description = "Sales order not found")
    })
    public ResponseEntity<List<SalesOrderItemResponse>> getSalesOrderItems(@PathVariable UUID id) {
        return ResponseEntity.ok(salesOrderService.getSalesOrderItems(id));
    }

    @PostMapping("/{id}/items")
    @PreAuthorize("hasAnyRole('lc-admin','lc-sales')")
    @Operation(summary = "Add an item to a sales order", description = "Adds a line item. finalPrice = listPrice - discountApplied. Only allowed when order is in Draft status. Auto-transitions the order from Draft to Pending when the first item is added.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Item created"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "404", description = "Sales order or product variant not found"),
        @ApiResponse(responseCode = "409", description = "Sales order is not in Draft status")
    })
    public ResponseEntity<SalesOrderItemResponse> addItem(
            @PathVariable UUID id,
            @Valid @RequestBody SalesOrderItemRequest request) {
        var response = salesOrderService.addSalesOrderItem(id, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}/items/{itemId}")
    @PreAuthorize("hasAnyRole('lc-admin','lc-sales')")
    @Operation(summary = "Update an item", description = "Updates a sales order item. Only allowed when order is in Draft status.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Item updated"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "404", description = "Sales order or item not found"),
        @ApiResponse(responseCode = "409", description = "Sales order is not in Draft status")
    })
    public ResponseEntity<SalesOrderItemResponse> updateItem(
            @PathVariable UUID id,
            @PathVariable UUID itemId,
            @Valid @RequestBody SalesOrderItemRequest request) {
        return ResponseEntity.ok(salesOrderService.updateSalesOrderItem(id, itemId, request));
    }

    @DeleteMapping("/{id}/items/{itemId}")
    @PreAuthorize("hasAnyRole('lc-admin','lc-sales')")
    @Operation(summary = "Delete an item", description = "Soft-deletes a sales order item. Only allowed when order is in Draft status.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Item deleted"),
        @ApiResponse(responseCode = "404", description = "Sales order or item not found"),
        @ApiResponse(responseCode = "409", description = "Sales order is not in Draft status")
    })
    public ResponseEntity<Void> deleteItem(
            @PathVariable UUID id,
            @PathVariable UUID itemId) {
        salesOrderService.deleteSalesOrderItem(id, itemId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/items/{itemId}/status")
    @PreAuthorize("hasAnyRole('lc-admin','lc-sales')")
    @Operation(summary = "Update item status", description = "Updates the status of a sales order item. Validates status type and allowed transitions (Pending → Added/Cancelled).")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Item status updated"),
        @ApiResponse(responseCode = "400", description = "Wrong status type"),
        @ApiResponse(responseCode = "404", description = "Sales order, item, or status not found"),
        @ApiResponse(responseCode = "409", description = "Invalid status transition")
    })
    public ResponseEntity<SalesOrderItemResponse> updateItemStatus(
            @PathVariable UUID id,
            @PathVariable UUID itemId,
            @Valid @RequestBody UpdateSalesOrderStatusRequest request) {
        return ResponseEntity.ok(salesOrderService.updateSalesOrderItemStatus(id, itemId, request));
    }
}
