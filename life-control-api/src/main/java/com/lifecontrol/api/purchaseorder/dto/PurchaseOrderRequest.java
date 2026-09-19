package com.lifecontrol.api.purchaseorder.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

public record PurchaseOrderRequest(
        @NotNull(message = "supplierId is required") UUID supplierId,
        @NotNull(message = "companyStoreId is required") UUID companyStoreId,
        @NotNull(message = "paymentMethodId is required") UUID paymentMethodId,
        UUID statusId,
        String comments,
        @Valid List<@NotNull(message = "details must not contain null entries") PurchaseOrderDetailRequest> details) {}
