package com.lifecontrol.api.product.exception;

import com.lifecontrol.api.exception.ResourceNotFoundException;
import java.util.UUID;

public class ProductVariantNotFoundException extends ResourceNotFoundException {

    public ProductVariantNotFoundException(UUID id) {
        super("Product variant not found with id: " + id);
    }
}
