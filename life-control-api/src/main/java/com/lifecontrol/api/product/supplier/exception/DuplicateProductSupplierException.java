package com.lifecontrol.api.product.supplier.exception;

public class DuplicateProductSupplierException extends RuntimeException {

    public DuplicateProductSupplierException(String supplierName) {
        super("The product already has a relationship with supplier: " + supplierName);
    }
}
