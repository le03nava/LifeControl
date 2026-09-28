package com.lifecontrol.api.scheduling.controller;

import static com.lifecontrol.api.common.security.Roles.ADMIN;
import static com.lifecontrol.api.common.security.Roles.SCHEDULING;
import static com.lifecontrol.api.common.security.Roles.SCHEDULING_READ;

import com.lifecontrol.api.scheduling.dto.SchedulingAvailabilityRequest;
import com.lifecontrol.api.scheduling.dto.SchedulingAvailabilityResponse;
import com.lifecontrol.api.scheduling.service.SchedulingAvailabilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for an activity's availability template.
 *
 * <p>Addressed by activity id, like the activity CRUD it extends: no store segment is in the path.
 * {@code @PreAuthorize} proves only that the caller holds some scheduling-scoped role;
 * {@link SchedulingAvailabilityService} loads the activity and authorizes the caller against its
 * store before reading or writing any window.</p>
 *
 * <p>Reads allow {@code lc-scheduling-read}; the write does not. Both allow {@code lc-scheduling}
 * and {@code lc-admin}.</p>
 */
@RestController
@RequestMapping("/api/scheduling/activities")
@Tag(
        name = "Scheduling Availability",
        description = "API for managing the availability template of a scheduling activity")
public class SchedulingAvailabilityController {

    private final SchedulingAvailabilityService schedulingAvailabilityService;

    public SchedulingAvailabilityController(SchedulingAvailabilityService schedulingAvailabilityService) {
        this.schedulingAvailabilityService = schedulingAvailabilityService;
    }

    @GetMapping("/{activityId}/availability")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + SCHEDULING + "','" + SCHEDULING_READ + "')")
    @Operation(
            summary = "Get an activity's availability",
            description =
                    "Returns every availability window of the activity, ordered by weekday and then by start time. The caller must be able to access the activity's store scope.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The activity's availability template"),
        @ApiResponse(responseCode = "403", description = "The caller cannot access the activity's store"),
        @ApiResponse(responseCode = "404", description = "Scheduling activity not found")
    })
    public ResponseEntity<SchedulingAvailabilityResponse> getAvailability(@PathVariable UUID activityId) {
        return ResponseEntity.ok(schedulingAvailabilityService.getAvailability(activityId));
    }

    @PutMapping("/{activityId}/availability")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + SCHEDULING + "')")
    @Operation(
            summary = "Replace an activity's availability",
            description =
                    "Replaces the whole template with the request's set; an empty list clears it. Windows that overlap inside the same weekday are rejected with a 400.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The stored availability template"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "403", description = "The caller cannot access the activity's store"),
        @ApiResponse(responseCode = "404", description = "Scheduling activity not found")
    })
    public ResponseEntity<SchedulingAvailabilityResponse> replaceAvailability(
            @PathVariable UUID activityId, @Valid @RequestBody SchedulingAvailabilityRequest request) {
        return ResponseEntity.ok(schedulingAvailabilityService.replaceAvailability(activityId, request));
    }
}
