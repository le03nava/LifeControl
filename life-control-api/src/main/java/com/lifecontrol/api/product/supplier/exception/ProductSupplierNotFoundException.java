package com.lifecontrol.api.product.supplier.exception;

import java.util.UUID;

public class ProductSupplierNotFoundException extends RuntimeException {

    public ProductSupplierNotFoundException(UUID id) {
        super("Product-supplier relation not found with id: " + id);
    }
}
