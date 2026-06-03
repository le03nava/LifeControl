package com.lifecontrol.api.purchaseorder.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record PurchaseOrderRequest(

    @NotNull(message = "supplierId es requerido")
    UUID supplierId,

    @NotNull(message = "companyStoreId es requerido")
    UUID companyStoreId,

    @NotNull(message = "paymentMethodId es requerido")
    UUID paymentMethodId,

    UUID statusId,

    String comments,

    List<PurchaseOrderDetailRequest> details

) {}
