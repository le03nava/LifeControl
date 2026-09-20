package com.lifecontrol.api.goodsreceipt.controller;

import static com.lifecontrol.api.common.security.Roles.ADMIN;
import static com.lifecontrol.api.common.security.Roles.COMPANY;
import static com.lifecontrol.api.common.security.Roles.COMPANY_COUNTRY;
import static com.lifecontrol.api.common.security.Roles.COMPANY_REGION;
import static com.lifecontrol.api.common.security.Roles.COMPANY_STORE;
import static com.lifecontrol.api.common.security.Roles.COMPANY_STORE_READ;
import static com.lifecontrol.api.common.security.Roles.COMPANY_ZONE;

import com.lifecontrol.api.goodsreceipt.dto.GoodsReceiptRequest;
import com.lifecontrol.api.goodsreceipt.dto.GoodsReceiptResponse;
import com.lifecontrol.api.goodsreceipt.service.GoodsReceiptService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for goods receipts raised against purchase orders.
 *
 * <p>Flat and id-addressed, like {@code PurchaseOrderController}: the create derives the store from
 * the purchase order it settles, and both reads resolve it through the service. Because no store
 * segment is in the path, {@code @PreAuthorize} alone proves only that the caller holds some
 * store-scoped role; {@link GoodsReceiptService} additionally scopes the list to the caller's
 * {@code company_store_ids} and authorizes each single receipt against its own store chain.</p>
 *
 * <p>Read endpoints additionally allow {@code lc-company-store-read}; the create does not.</p>
 */
@RestController
@RequestMapping("/api/goods-receipts")
@Tag(name = "Goods Receipts", description = "API for registering and reading goods receipts (receptions)")
public class GoodsReceiptController {

    private final GoodsReceiptService goodsReceiptService;

    public GoodsReceiptController(GoodsReceiptService goodsReceiptService) {
        this.goodsReceiptService = goodsReceiptService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + COMPANY + "','" + COMPANY_COUNTRY + "','" + COMPANY_REGION + "','"
            + COMPANY_ZONE + "','" + COMPANY_STORE + "')")
    @Operation(
            summary = "Register a goods receipt",
            description =
                    "Registers a reception against a purchase order in one transaction: the receipt, its inventory ledger movements and the purchase-order line statuses commit together.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Goods receipt created"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "403", description = "The caller cannot access the order's store"),
        @ApiResponse(responseCode = "404", description = "Purchase order, location, line or status not found"),
        @ApiResponse(responseCode = "409", description = "The purchase order is not receivable")
    })
    public ResponseEntity<GoodsReceiptResponse> createReceipt(@Valid @RequestBody GoodsReceiptRequest request) {
        var response = goodsReceiptService.createReceipt(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + COMPANY + "','" + COMPANY_COUNTRY + "','" + COMPANY_REGION + "','"
            + COMPANY_ZONE + "','" + COMPANY_STORE + "','" + COMPANY_STORE_READ + "')")
    @Operation(
            summary = "Get all goods receipts",
            description =
                    "Returns a paginated list, optionally filtered by search term on the receipt number or the order number. Non-admin callers only see receipts of the stores in their company_store_ids; an empty set yields an empty page.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Paginated list of goods receipts")})
    public ResponseEntity<Page<GoodsReceiptResponse>> getAllReceipts(
            @PageableDefault(size = 12) Pageable pageable, @RequestParam(required = false) String search) {
        return ResponseEntity.ok(goodsReceiptService.getAllReceipts(pageable, search));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + COMPANY + "','" + COMPANY_COUNTRY + "','" + COMPANY_REGION + "','"
            + COMPANY_ZONE + "','" + COMPANY_STORE + "','" + COMPANY_STORE_READ + "')")
    @Operation(
            summary = "Get a goods receipt by ID",
            description =
                    "Returns a single goods receipt with its lines. Access is verified against the receipt's own store chain, because the store is not in the URL.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Goods receipt found"),
        @ApiResponse(responseCode = "403", description = "The caller cannot access the receipt's store"),
        @ApiResponse(responseCode = "404", description = "Goods receipt not found")
    })
    public ResponseEntity<GoodsReceiptResponse> getReceiptById(@PathVariable UUID id) {
        return ResponseEntity.ok(goodsReceiptService.getReceipt(id));
    }
}
