package com.lifecontrol.api.inventory.dto;

import java.util.UUID;

/** Store inventory settings of one store. */
public record StoreInventorySettingsResponse(UUID companyStoreId, UUID receivingLocationId, UUID salesLocationId) {}
