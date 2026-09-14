package com.lifecontrol.api.supplier.exception;

import com.lifecontrol.api.exception.DuplicateResourceException;

public class DuplicateSupplierException extends DuplicateResourceException {

    public DuplicateSupplierException(String message) {
        super(message);
    }
}
