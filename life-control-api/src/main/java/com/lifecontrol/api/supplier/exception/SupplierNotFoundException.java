package com.lifecontrol.api.supplier.exception;

public class SupplierNotFoundException extends RuntimeException {

    public SupplierNotFoundException(java.util.UUID id) {
        super("Supplier not found with id: " + id);
    }
}
