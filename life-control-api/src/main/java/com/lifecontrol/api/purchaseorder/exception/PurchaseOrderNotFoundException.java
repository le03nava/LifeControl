package com.lifecontrol.api.purchaseorder.exception;

import java.util.UUID;

public class PurchaseOrderNotFoundException extends RuntimeException {

    public PurchaseOrderNotFoundException(UUID id) {
        super("Orden de compra no encontrada con id: " + id);
    }
}
