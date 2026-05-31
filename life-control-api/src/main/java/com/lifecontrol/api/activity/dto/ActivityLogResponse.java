package com.lifecontrol.api.activity.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DTO returned by the activity log query endpoint.
 * Matches the shape of a single {@code activity_logs} row with resolved
 * process and event names.
 */
public record ActivityLogResponse(
    UUID id,
    String userId,
    String username,
    String process,
    String event,
    String httpMethod,
    Integer httpStatus,
    String requestPath,
    String ipAddress,
    String userAgent,
    String payloadJson,
    LocalDateTime createdAt
) {}
