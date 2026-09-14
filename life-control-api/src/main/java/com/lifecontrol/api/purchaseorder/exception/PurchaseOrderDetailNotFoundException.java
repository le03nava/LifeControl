package com.lifecontrol.api.purchaseorder.exception;

import com.lifecontrol.api.exception.ResourceNotFoundException;
import java.util.UUID;

public class PurchaseOrderDetailNotFoundException extends ResourceNotFoundException {

    public PurchaseOrderDetailNotFoundException(UUID id) {
        super("Detalle de orden de compra no encontrado con id: " + id);
    }
}
