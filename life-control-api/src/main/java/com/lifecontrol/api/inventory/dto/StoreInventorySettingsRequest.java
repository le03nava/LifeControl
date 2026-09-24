package com.lifecontrol.api.inventory.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Request body for the store's inventory settings. The store is taken from the nested path, so only
 * the two location ids and the optional version travel in the body.
 *
 * <p>Both ids are required. Validation that each location actually belongs to the addressed store is
 * a business rule enforced by {@code StoreInventorySettingsService}, not a bean-validation
 * constraint. The two ids may point at the same location: a small store may receive and sell from
 * the same place.</p>
 *
 * <p>{@code version} is an <b>optional precondition</b>, the body equivalent of an {@code If-Match}
 * header. Absent ({@code null}) means "no precondition, today's behaviour". Present means "reject
 * the request with a 409 if the stored settings are not at this version", which lets a client that
 * read a version detect and refuse a lost update.</p>
 */
public record StoreInventorySettingsRequest(
        @NotNull(message = "receivingLocationId is required")
        UUID receivingLocationId,

        @NotNull(message = "salesLocationId is required") UUID salesLocationId,

        Long version) {

    /**
     * Backward-compatible construction without a version precondition, equivalent to passing a
     * {@code null} version. Keeps existing callers that never assert a version compiling unchanged.
     */
    public StoreInventorySettingsRequest(UUID receivingLocationId, UUID salesLocationId) {
        this(receivingLocationId, salesLocationId, null);
    }
}
