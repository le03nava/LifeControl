package com.lifecontrol.api.purchaseorder.exception;

public class DuplicatePurchaseOrderException extends RuntimeException {

    public DuplicatePurchaseOrderException(String orderNumber) {
        super("Purchase order with number '" + orderNumber + "' already exists");
    }
}
