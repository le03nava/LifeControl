package com.lifecontrol.api.scheduling.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One scheduling activity, as returned by the read and write operations.
 *
 * <p>{@code version} is the entity's optimistic-locking version. It always travels back to the
 * client so a caller can echo it in a later {@link SchedulingActivityRequest} and detect a lost
 * update with a 412 instead of silently overwriting another writer.</p>
 *
 * <p>{@code companyStoreId} is included so a client can round-trip the body of a GET into a PUT: the
 * update ignores it, but the symmetric shape keeps the DTOs mirror images.</p>
 */
public record SchedulingActivityResponse(
        UUID id,
        UUID companyStoreId,
        String userId,
        String activityName,
        String description,
        Integer durationMinutes,
        Integer capacityPerSlot,
        Boolean enabled,
        long version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {}
