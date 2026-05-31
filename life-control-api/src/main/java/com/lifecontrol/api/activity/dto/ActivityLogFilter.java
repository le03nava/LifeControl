package com.lifecontrol.api.activity.dto;

import java.time.LocalDate;

/**
 * Filter criteria for querying the activity log.
 * <p>
 * All fields are optional — only non-null values are applied as query filters.
 */
public record ActivityLogFilter(
    LocalDate from,
    LocalDate to,
    String process,
    String event,
    String userId,
    String httpMethod
) {}
