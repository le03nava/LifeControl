package com.lifecontrol.api.purchaseorder.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record UpdatePurchaseOrderStatusRequest(
        @NotNull(message = "statusId is required") UUID statusId, Integer receivedQuantity) {

    public UpdatePurchaseOrderStatusRequest(UUID statusId) {
        this(statusId, null);
    }
}
