package com.lifecontrol.api.purchaseorder.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One purchase-order line, as the API answers it.
 *
 * @param statusId the catalog row the line's state is labelled by, <b>projected from {@code status}</b>: the
 *     line stores no pointer any more, so the pair is a lookup by code in the purchase-order detail family
 * @param statusName the label of that row — presentation, never the line's state
 * @param status the line's typed state as its code (ADR-0004 decision 3) — {@code PENDING},
 *     {@code IN_PROCESS}, {@code IN_TRANSIT}, {@code PARTIAL_RECEIVED}, {@code RECEIVED}, {@code REJECTED} or
 *     {@code CANCELLED} — and the authority the pair is derived from. This machine is <b>not</b> the sales-order
 *     item set: it has its own tail and no {@code ADDED}
 * @param taxCode the fiscal code the line was written with, copied from the product at creation (S2) and never
 *     re-read: {@code NA} means no tax applies
 * @param taxRate the rate as it stood on the document's date when the line was created (S3) — the snapshot the
 *     derived amounts come from, not a lookup
 * @param taxAmount {@code round(subtotal * taxRate, 4)}, derived by the schema from the stored rate and base
 *     (S6) and read back rather than computed here
 */
public record PurchaseOrderDetailResponse(
        UUID id,
        UUID purchaseOrderId,
        UUID productId,
        String productName,
        UUID productVariantId,
        String productVariantName,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal total,
        String taxCode,
        BigDecimal taxRate,
        BigDecimal taxAmount,
        BigDecimal receivedQuantity,
        String comments,
        UUID statusId,
        String statusName,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {}
