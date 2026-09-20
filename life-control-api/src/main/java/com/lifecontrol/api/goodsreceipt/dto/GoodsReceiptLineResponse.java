package com.lifecontrol.api.goodsreceipt.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** One line of a persisted goods receipt. */
public record GoodsReceiptLineResponse(
        UUID id, UUID purchaseOrderDetailId, UUID productVariantId, BigDecimal quantityReceived, String comments) {}
