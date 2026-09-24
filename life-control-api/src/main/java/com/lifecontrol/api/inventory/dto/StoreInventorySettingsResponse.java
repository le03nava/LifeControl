package com.lifecontrol.api.inventory.dto;

import java.util.UUID;

/**
 * Store inventory settings of one store.
 *
 * <p>{@code version} is the entity's optimistic-locking version. It always travels back to the
 * client so a caller can echo it in a later {@link StoreInventorySettingsRequest} and detect a lost
 * update.</p>
 */
public record StoreInventorySettingsResponse(
        UUID companyStoreId, UUID receivingLocationId, UUID salesLocationId, long version) {}
