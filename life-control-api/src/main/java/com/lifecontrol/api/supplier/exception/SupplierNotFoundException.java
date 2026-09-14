package com.lifecontrol.api.supplier.exception;

import com.lifecontrol.api.exception.ResourceNotFoundException;

public class SupplierNotFoundException extends ResourceNotFoundException {

    public SupplierNotFoundException(java.util.UUID id) {
        super("Supplier not found with id: " + id);
    }
}
