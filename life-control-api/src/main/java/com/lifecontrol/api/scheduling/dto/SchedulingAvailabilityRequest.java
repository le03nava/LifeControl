package com.lifecontrol.api.scheduling.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Request body of the replace-availability operation.
 *
 * <p>The set is replaced as a whole: an empty list clears the activity's template, and every
 * supplied window takes the place of whatever was stored. The cap keeps one request bounded; the
 * service rejects an overlap inside a weekday before writing.</p>
 */
public record SchedulingAvailabilityRequest(
        @NotNull(message = "windows is required")
        @Size(max = 50, message = "windows must contain at most 50 entries")
        @Valid
        List<SchedulingAvailabilityWindowRequest> windows) {}
