package com.lifecontrol.api.exception;

import java.util.UUID;

/**
 * Generic 404 category. Domain-specific not-found exceptions extend this class
 * so {@code GlobalExceptionHandler} can handle the whole category with a single
 * handler.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }

    public <T> ResourceNotFoundException(Class<T> resource, UUID id) {
        super(resource.getSimpleName() + " not found with id: " + id);
    }
}
