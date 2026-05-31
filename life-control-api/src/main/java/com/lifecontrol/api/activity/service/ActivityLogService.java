package com.lifecontrol.api.activity.service;

import com.lifecontrol.api.activity.dto.ActivityLogFilter;
import com.lifecontrol.api.activity.dto.ActivityLogResponse;
import com.lifecontrol.api.activity.event.ActivityLogEvent;
import com.lifecontrol.api.activity.model.ActivityLog;
import com.lifecontrol.api.activity.repository.ActivityEventRepository;
import com.lifecontrol.api.activity.repository.ActivityLogRepository;
import com.lifecontrol.api.activity.repository.ActivityProcessRepository;
import com.lifecontrol.api.activity.util.PayloadSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Service for the activity audit trail.
 * <p>
 * Handles persisting log entries published by the aspect and querying
 * the activity log with flexible filters.
 */
@Service
public class ActivityLogService {

    private static final Logger log = LoggerFactory.getLogger(ActivityLogService.class);

    private final ActivityLogRepository activityLogRepository;
    private final ActivityProcessRepository processRepository;
    private final ActivityEventRepository eventRepository;

    public ActivityLogService(ActivityLogRepository activityLogRepository,
                              ActivityProcessRepository processRepository,
                              ActivityEventRepository eventRepository) {
        this.activityLogRepository = activityLogRepository;
        this.processRepository = processRepository;
        this.eventRepository = eventRepository;
    }

    /**
     * Persists an activity log entry from the given event.
     * <p>
     * The payload is sanitized (sensitive fields redacted) before storage.
     * The process and event entities are looked up by name from the reference
     * tables; if either is not found, the entry is logged as a warning and skipped.
     */
    @Transactional
    public void save(ActivityLogEvent event) {
        var processOpt = processRepository.findByName(event.getProcessName());
        if (processOpt.isEmpty()) {
            log.warn("Activity process not found: {} — skipping log entry", event.getProcessName());
            return;
        }
        var eventOpt = eventRepository.findByName(event.getEventName());
        if (eventOpt.isEmpty()) {
            log.warn("Activity event not found: {} — skipping log entry", event.getEventName());
            return;
        }

        var sanitizedPayload = PayloadSanitizer.sanitize(event.getPayloadJson());

        var logEntry = ActivityLog.builder()
                .userId(event.getUserId())
                .username(event.getUsername())
                .activityProcess(processOpt.get())
                .activityEvent(eventOpt.get())
                .httpMethod(event.getHttpMethod())
                .httpStatus(event.getHttpStatus())
                .requestPath(event.getRequestPath())
                .ipAddress(event.getIpAddress())
                .userAgent(event.getUserAgent())
                .payloadJson(sanitizedPayload)
                .build();

        activityLogRepository.save(logEntry);

        log.debug("Activity log saved: {} {} {} -> {}",
                event.getHttpMethod(), event.getRequestPath(),
                event.getProcessName(), event.getEventName());
    }

    /**
     * Queries the activity log with optional filters and pagination.
     *
     * @param filter   the filter criteria (date range, process, event, user, method)
     * @param pageable pagination parameters
     * @return a page of activity log responses
     */
    @Transactional(readOnly = true)
    public Page<ActivityLogResponse> findAll(ActivityLogFilter filter, Pageable pageable) {
        var from = filter.from() != null
                ? filter.from().atStartOfDay()
                : null;
        var to = filter.to() != null
                ? filter.to().atTime(LocalTime.MAX)
                : null;

        var page = activityLogRepository.findByFilters(
                from, to,
                filter.process(),
                filter.event(),
                filter.userId(),
                filter.httpMethod(),
                pageable
        );

        return page.map(this::toResponse);
    }

    private ActivityLogResponse toResponse(ActivityLog logEntry) {
        return new ActivityLogResponse(
                logEntry.getId(),
                logEntry.getUserId(),
                logEntry.getUsername(),
                logEntry.getActivityProcess().getName(),
                logEntry.getActivityEvent().getName(),
                logEntry.getHttpMethod(),
                logEntry.getHttpStatus(),
                logEntry.getRequestPath(),
                logEntry.getIpAddress(),
                logEntry.getUserAgent(),
                logEntry.getPayloadJson(),
                logEntry.getCreatedAt()
        );
    }
}
