package com.lifecontrol.api.store.dto;

import jakarta.validation.constraints.Size;

/**
 * Partial update of a store location. Every field is optional: {@code null} means "leave
 * unchanged".
 *
 * <p>{@code locationCode} / {@code locationName} keep the same upper bound as creation and reject
 * blank values when provided: {@code @Size(min = 1, max = n)} treats {@code null} as valid but a
 * blank string as a validation error (400).</p>
 */
public record UpdateStoreLocationRequest(
        @Size(min = 1, max = 10, message = "locationCode must not be blank")
        String locationCode,

        @Size(min = 1, max = 100, message = "locationName must not be blank")
        String locationName,

        @Size(max = 255) String description,
        Integer displayOrder) {}
