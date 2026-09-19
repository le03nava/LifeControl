package com.lifecontrol.api.inventory.dto;

import java.util.UUID;

/**
 * Lightweight read model of one store location of a store, for the receiving/sales location picker.
 *
 * <p>Carries the zone and area code/name as well as the location id, code and name, so the client
 * can disambiguate two locations that share a name across different zones or areas.</p>
 */
public record StoreLocationSummaryResponse(
        UUID id,
        String locationCode,
        String locationName,
        UUID storeZoneId,
        String zoneCode,
        String zoneName,
        UUID storeAreaId,
        String areaCode,
        String areaName) {}
