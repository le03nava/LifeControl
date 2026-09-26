package com.lifecontrol.api.store.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Partial update of a store area. Every field is optional: {@code null} means "leave unchanged".
 *
 * <p>{@code areaCode} / {@code areaName} keep the same upper bound as creation and reject blank
 * values when provided: {@code @Size(min = 1, max = n)} treats {@code null} as valid but a blank
 * string as a validation error (400).</p>
 *
 * <p>{@code version} is an <b>optional precondition</b>, the body equivalent of an {@code If-Match}
 * header. Absent ({@code null}) means "no precondition, today's behaviour". Present means "reject
 * the request with a 409 if the stored area is not at this version", which lets a client that read
 * a version detect and refuse a lost update.</p>
 */
public record UpdateStoreAreaRequest(
        @Size(min = 1, max = 10, message = "areaCode must not be blank")
        String areaCode,

        @Size(min = 1, max = 100, message = "areaName must not be blank")
        String areaName,

        @Size(max = 255) String description,
        @Min(0) Integer displayOrder,

        Long version) {

    /**
     * Backward-compatible construction without a version precondition, equivalent to passing a
     * {@code null} version. Keeps existing callers that never assert a version compiling unchanged.
     */
    public UpdateStoreAreaRequest(String areaCode, String areaName, String description, Integer displayOrder) {
        this(areaCode, areaName, description, displayOrder, null);
    }
}
