package com.lifecontrol.api.scheduling.dto;

import java.util.List;
import java.util.UUID;

/**
 * The whole availability template of one activity, ordered by weekday and then by start time.
 *
 * <p>{@code activityId} travels so the response identifies the activity it belongs to without the
 * client echoing the path.</p>
 */
public record SchedulingAvailabilityResponse(UUID activityId, List<SchedulingAvailabilityWindowResponse> windows) {}
