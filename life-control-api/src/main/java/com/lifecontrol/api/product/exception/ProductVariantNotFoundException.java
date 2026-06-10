package com.lifecontrol.api.product.exception;

import java.util.UUID;

public class ProductVariantNotFoundException extends RuntimeException {

    public ProductVariantNotFoundException(UUID id) {
        super("Product variant not found with id: " + id);
    }
}
