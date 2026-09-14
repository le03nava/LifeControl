package com.lifecontrol.api.product.supplier.exception;

import com.lifecontrol.api.exception.ResourceNotFoundException;
import java.util.UUID;

public class ProductSupplierNotFoundException extends ResourceNotFoundException {

    public ProductSupplierNotFoundException(UUID id) {
        super("Product-supplier relation not found with id: " + id);
    }
}
