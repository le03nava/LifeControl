package com.lifecontrol.api.goodsreceipt.exception;

import java.util.UUID;

/**
 * {@code 400} when the resolved receiving location is soft-deleted ({@code enabled = false}).
 *
 * <p>This closes W2b's debt #1: the settings screen accepts a disabled location, so a
 * decommissioned leaf could be configured and then received into. The check runs before the
 * ownership check on purpose (see {@code GoodsReceiptService.createReceipt}).</p>
 */
public class DisabledReceivingLocationException extends GoodsReceiptValidationException {

    public DisabledReceivingLocationException(UUID storeLocationId) {
        super("Receiving store location " + storeLocationId + " is disabled and cannot receive goods");
    }
}
