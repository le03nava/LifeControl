package com.lifecontrol.api.purchaseorder.exception;

public class DuplicatePurchaseOrderException extends RuntimeException {

    public DuplicatePurchaseOrderException(String orderNumber) {
        super("Ya existe una orden de compra con el número: " + orderNumber);
    }
}
