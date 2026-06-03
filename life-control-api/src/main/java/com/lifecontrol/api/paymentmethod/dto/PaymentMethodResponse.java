package com.lifecontrol.api.paymentmethod.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record PaymentMethodResponse(
    UUID id,
    String paymentMethodName,
    String paymentMethodShortName,
    Boolean enabled,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}
