package com.lifecontrol.api.promotion.exception;

import com.lifecontrol.api.exception.ResourceNotFoundException;
import java.util.UUID;

public class PromotionNotFoundException extends ResourceNotFoundException {

    public PromotionNotFoundException(UUID id) {
        super("Promotion not found with id: " + id);
    }
}
