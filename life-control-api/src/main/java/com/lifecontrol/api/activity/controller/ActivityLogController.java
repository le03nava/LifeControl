package com.lifecontrol.api.activity.controller;

import com.lifecontrol.api.activity.dto.ActivityLogFilter;
import com.lifecontrol.api.activity.dto.ActivityLogResponse;
import com.lifecontrol.api.activity.service.ActivityLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * REST controller for querying the activity audit trail.
 * <p>
 * Only accessible by users with the {@code life-control-admin} realm role.
 */
@RestController
@RequestMapping("/api/activity-logs")
@Tag(name = "Activity Log", description = "Audit trail of user actions across all domains")
@PreAuthorize("hasRole('life-control-admin')")
public class ActivityLogController {

    private final ActivityLogService activityLogService;

    public ActivityLogController(ActivityLogService activityLogService) {
        this.activityLogService = activityLogService;
    }

    @GetMapping
    @Operation(summary = "Query activity logs", description = "Returns a paginated list of activity log entries, optionally filtered by date range, process, event, user, or HTTP method")
    public ResponseEntity<Page<ActivityLogResponse>> getActivityLogs(
            @PageableDefault(size = 20) Pageable pageable,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String process,
            @RequestParam(required = false) String event,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String httpMethod) {

        var filter = new ActivityLogFilter(from, to, process, event, userId, httpMethod);
        return ResponseEntity.ok(activityLogService.findAll(filter, pageable));
    }
}
