package com.lifecontrol.api.store.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Partial update of a store location. Every field is optional: {@code null} means "leave
 * unchanged".
 *
 * <p>{@code locationCode} / {@code locationName} keep the same upper bound as creation and reject
 * blank values when provided: {@code @Size(min = 1, max = n)} treats {@code null} as valid but a
 * blank string as a validation error (400).</p>
 *
 * <p>{@code version} is an <b>optional precondition</b>, the body equivalent of an {@code If-Match}
 * header. Absent ({@code null}) means "no precondition, today's behaviour". Present means "reject
 * the request with a 412 if the stored location is not at this version", which lets a client that
 * read a version detect and refuse a lost update.</p>
 */
public record UpdateStoreLocationRequest(
        @Size(min = 1, max = 10, message = "locationCode must not be blank")
        String locationCode,

        @Size(min = 1, max = 100, message = "locationName must not be blank")
        String locationName,

        @Size(max = 255) String description,
        @Min(0) Integer displayOrder,

        Long version) {

    /**
     * Backward-compatible construction without a version precondition, equivalent to passing a
     * {@code null} version. Keeps existing callers that never assert a version compiling unchanged.
     */
    public UpdateStoreLocationRequest(
            String locationCode, String locationName, String description, Integer displayOrder) {
        this(locationCode, locationName, description, displayOrder, null);
    }
}
