package com.lifecontrol.api.store.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Partial update of a store zone. Every field is optional: {@code null} means "leave unchanged".
 *
 * <p>{@code zoneCode} / {@code zoneName} keep the same upper bound as creation and reject blank
 * values when provided: {@code @Size(min = 1, max = n)} treats {@code null} as valid but a blank
 * string as a validation error (400).</p>
 *
 * <p>{@code version} is an <b>optional precondition</b>, the body equivalent of an {@code If-Match}
 * header. Absent ({@code null}) means "no precondition, today's behaviour". Present means "reject
 * the request with a 412 if the stored zone is not at this version", which lets a client that read
 * a version detect and refuse a lost update.</p>
 */
public record UpdateStoreZoneRequest(
        @Size(min = 1, max = 10, message = "zoneCode must not be blank")
        String zoneCode,

        @Size(min = 1, max = 100, message = "zoneName must not be blank")
        String zoneName,

        @Size(max = 255) String description,
        @Min(0) Integer displayOrder,

        Long version) {

    /**
     * Backward-compatible construction without a version precondition, equivalent to passing a
     * {@code null} version. Keeps existing callers that never assert a version compiling unchanged.
     */
    public UpdateStoreZoneRequest(String zoneCode, String zoneName, String description, Integer displayOrder) {
        this(zoneCode, zoneName, description, displayOrder, null);
    }
}
