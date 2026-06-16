package com.lifecontrol.api.salesorder.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ChargeSalesOrderRequest(
        @NotNull(message = "paymentMethodId is required") UUID paymentMethodId
) {}
