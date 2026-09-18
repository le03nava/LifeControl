package com.lifecontrol.api.store.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Partial update of a store area. Every field is optional: {@code null} means "leave unchanged".
 *
 * <p>{@code areaCode} / {@code areaName} keep the same upper bound as creation and reject blank
 * values when provided: {@code @Size(min = 1, max = n)} treats {@code null} as valid but a blank
 * string as a validation error (400).</p>
 */
public record UpdateStoreAreaRequest(
        @Size(min = 1, max = 10, message = "areaCode must not be blank")
        String areaCode,

        @Size(min = 1, max = 100, message = "areaName must not be blank")
        String areaName,

        @Size(max = 255) String description,
        @Min(0) Integer displayOrder) {}
