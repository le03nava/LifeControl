package com.lifecontrol.api.salesorder.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record SalesOrderRequest(
        UUID customerId,
        @NotNull(message = "companyStoreId is required") UUID companyStoreId,
        UUID shiftId,
        String userId
) {
    private static final UUID DEFAULT_CUSTOMER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    public SalesOrderRequest {
        if (customerId == null) {
            customerId = DEFAULT_CUSTOMER_ID;
        }
    }
}
