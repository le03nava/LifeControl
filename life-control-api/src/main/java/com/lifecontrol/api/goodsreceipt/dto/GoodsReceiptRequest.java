package com.lifecontrol.api.goodsreceipt.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * Request body to register a reception against a purchase order.
 *
 * <p>{@code purchaseOrderId} and a non-empty {@code lines} list are required. {@code
 * receivingLocationId} is optional: a {@code null} means "use the store's configured receiving
 * location" (its {@code store_inventory_settings.receiving_location_id}).</p>
 */
public record GoodsReceiptRequest(
        @NotNull(message = "purchaseOrderId is required") UUID purchaseOrderId,

        UUID receivingLocationId,

        String comments,

        @NotEmpty(message = "lines must not be empty") @Valid
        List<GoodsReceiptLineRequest> lines) {}
