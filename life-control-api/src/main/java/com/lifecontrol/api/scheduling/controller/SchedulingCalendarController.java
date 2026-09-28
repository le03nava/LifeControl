package com.lifecontrol.api.scheduling.controller;

import static com.lifecontrol.api.common.security.Roles.ADMIN;
import static com.lifecontrol.api.common.security.Roles.SCHEDULING;
import static com.lifecontrol.api.common.security.Roles.SCHEDULING_READ;

import com.lifecontrol.api.scheduling.dto.SchedulingCalendarSlotResponse;
import com.lifecontrol.api.scheduling.service.SchedulingCalendarService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for the store's scheduling calendar projection.
 *
 * <p>Flat and store-scoped like the rest of the scheduling domain: {@code storeId} travels as a
 * query parameter and {@link SchedulingCalendarService} authorizes the caller against that store's
 * whole scope. The read is a projection only (D33): it never materializes a slot, so a range nobody
 * has asked {@code GET /slots} for yet renders as fewer or no slots (G19).</p>
 *
 * <p>The read admits the write and the read-only scheduling role: it mutates nothing, so the
 * read-only role is sufficient.</p>
 */
@RestController
@RequestMapping("/api/scheduling/calendar")
@Tag(name = "Scheduling Calendar", description = "API for projecting a store's scheduled slots and appointments")
public class SchedulingCalendarController {

    private final SchedulingCalendarService schedulingCalendarService;

    public SchedulingCalendarController(SchedulingCalendarService schedulingCalendarService) {
        this.schedulingCalendarService = schedulingCalendarService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + SCHEDULING + "','" + SCHEDULING_READ + "')")
    @Operation(
            summary = "Project a store's calendar for a range",
            description =
                    "Returns one entry per materialized slot of the store whose start_at falls in [from, to), each with its appointments, capacity, booked, derived available, status and the activity's activityEnabled flag. Never materializes a slot, and never filters by the activity's enabled flag (D37): a booked slot survives its activity's soft delete, so hiding it would make the calendar contradict the booked/available numbers it renders. The range must be non-empty and at most 90 days wide. The caller must be able to access the store scope.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The store's calendar entries in the requested range"),
        @ApiResponse(responseCode = "400", description = "The range is empty, inverted or wider than 90 days"),
        @ApiResponse(responseCode = "403", description = "The caller cannot access the store"),
        @ApiResponse(responseCode = "404", description = "Scheduling store or appointment status not found")
    })
    public ResponseEntity<List<SchedulingCalendarSlotResponse>> getCalendar(
            @RequestParam UUID storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) UUID activityId) {
        return ResponseEntity.ok(schedulingCalendarService.getCalendar(storeId, from, to, userId, activityId));
    }
}
