package com.lifecontrol.api.purchaseorder.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record UpdatePurchaseOrderStatusRequest(

    @NotNull(message = "statusId es requerido")
    UUID statusId,

    Integer receivedQuantity

) {
    public UpdatePurchaseOrderStatusRequest(UUID statusId) {
        this(statusId, null);
    }
}