package com.lifecontrol.api.goodsreceipt.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One reception line. It names the purchase-order line being settled, how much arrived and an
 * optional comment.
 *
 * <p>The "quantity must be a whole number" rule is NOT here: the service owns it, because its 400
 * has to name the offending line.</p>
 */
public record GoodsReceiptLineRequest(
        @NotNull(message = "purchaseOrderDetailId is required")
        UUID purchaseOrderDetailId,

        @NotNull(message = "quantityReceived is required")
        @DecimalMin(value = "0.01", message = "quantityReceived must be greater than or equal to 0.01")
        BigDecimal quantityReceived,

        String comments) {}
