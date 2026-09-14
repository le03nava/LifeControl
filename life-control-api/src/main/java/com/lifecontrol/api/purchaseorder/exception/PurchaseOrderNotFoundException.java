package com.lifecontrol.api.purchaseorder.exception;

import com.lifecontrol.api.exception.ResourceNotFoundException;
import java.util.UUID;

public class PurchaseOrderNotFoundException extends ResourceNotFoundException {

    public PurchaseOrderNotFoundException(UUID id) {
        super("Orden de compra no encontrada con id: " + id);
    }
}
