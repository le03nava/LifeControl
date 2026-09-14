package com.lifecontrol.api.purchaseorder.exception;

import java.util.UUID;

public class PurchaseOrderDetailNotFoundException extends RuntimeException {

    public PurchaseOrderDetailNotFoundException(UUID id) {
        super("Purchase order detail not found with id: " + id);
    }
}
