package com.lifecontrol.api.inventory.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Request body for the store's inventory settings. The store is taken from the nested path, so only
 * the two location ids travel in the body.
 *
 * <p>Both ids are required. Validation that each location actually belongs to the addressed store is
 * a business rule enforced by {@code StoreInventorySettingsService}, not a bean-validation
 * constraint. The two ids may point at the same location: a small store may receive and sell from
 * the same place.</p>
 */
public record StoreInventorySettingsRequest(
        @NotNull(message = "receivingLocationId is required")
        UUID receivingLocationId,

        @NotNull(message = "salesLocationId is required") UUID salesLocationId) {}
