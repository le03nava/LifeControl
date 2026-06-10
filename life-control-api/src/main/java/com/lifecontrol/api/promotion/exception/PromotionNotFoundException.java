package com.lifecontrol.api.promotion.exception;

import java.util.UUID;

public class PromotionNotFoundException extends RuntimeException {

    public PromotionNotFoundException(UUID id) {
        super("Promotion not found with id: " + id);
    }
}
