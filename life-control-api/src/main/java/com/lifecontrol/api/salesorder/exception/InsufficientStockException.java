package com.lifecontrol.api.salesorder.exception;

import java.math.BigDecimal;
import java.util.UUID;

public class InsufficientStockException extends RuntimeException {

    private final UUID variantId;
    private final BigDecimal requested;
    private final BigDecimal available;

    public InsufficientStockException(UUID variantId, BigDecimal requested, BigDecimal available) {
        super("Insufficient stock for variant %s: requested %s, available %s"
                .formatted(variantId, requested, available));
        this.variantId = variantId;
        this.requested = requested;
        this.available = available;
    }

    public UUID getVariantId() {
        return variantId;
    }

    public BigDecimal getRequested() {
        return requested;
    }

    public BigDecimal getAvailable() {
        return available;
    }
}
