package com.lifecontrol.api.product.supplier.exception;

import com.lifecontrol.api.exception.DuplicateResourceException;

public class DuplicateProductSupplierException extends DuplicateResourceException {

    public DuplicateProductSupplierException(String supplierName) {
        super("The product already has a relationship with supplier: " + supplierName);
    }
}
