package com.lifecontrol.api.product.exception;

import com.lifecontrol.api.exception.ResourceNotFoundException;
import java.util.UUID;

public class ProductNotFoundException extends ResourceNotFoundException {

    public ProductNotFoundException(UUID id) {
        super("Product not found with id: " + id);
    }
}
