package com.lifecontrol.api.purchaseorder.exception;

import com.lifecontrol.api.exception.ResourceNotFoundException;
import java.util.UUID;

public class PurchaseOrderDetailNotFoundException extends ResourceNotFoundException {

    public PurchaseOrderDetailNotFoundException(UUID id) {
        super("Purchase order detail not found with id: " + id);
    }
}
