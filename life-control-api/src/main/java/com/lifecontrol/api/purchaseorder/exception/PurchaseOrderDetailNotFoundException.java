package com.lifecontrol.api.purchaseorder.exception;

import java.util.UUID;

public class PurchaseOrderDetailNotFoundException extends RuntimeException {

    public PurchaseOrderDetailNotFoundException(UUID id) {
        super("Detalle de orden de compra no encontrado con id: " + id);
    }
}
