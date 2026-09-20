package com.lifecontrol.api.goodsreceipt.exception;

import java.util.UUID;

/**
 * {@code 400} when the purchase order being received against is soft-deleted ({@code enabled =
 * false}).
 *
 * <p>{@code PurchaseOrderRepository#findByIdForUpdate} deliberately does not filter {@code enabled},
 * unlike the product-variant locking finder: a disabled order is found, so this check is what turns
 * it into a precise message instead of a confusing not-found.</p>
 */
public class DisabledPurchaseOrderException extends GoodsReceiptValidationException {

    public DisabledPurchaseOrderException(UUID purchaseOrderId, String orderNumber) {
        super("Purchase order " + orderNumber + " (" + purchaseOrderId + ") is disabled and cannot receive goods");
    }
}
