package com.lifecontrol.api.scheduling.controller;

import static com.lifecontrol.api.common.security.Roles.ADMIN;
import static com.lifecontrol.api.common.security.Roles.SCHEDULING;
import static com.lifecontrol.api.common.security.Roles.SCHEDULING_READ;

import com.lifecontrol.api.scheduling.dto.SchedulingSlotResponse;
import com.lifecontrol.api.scheduling.service.SchedulingSlotService;
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
 * REST API for an activity's bookable slot instances.
 *
 * <p>Addressed by activity id as a query parameter, like the activity catalogue: no store segment is
 * in the path. {@code @PreAuthorize} proves only that the caller holds some scheduling-scoped role;
 * {@link SchedulingSlotService} loads the activity and authorizes the caller against its store
 * before deriving any slot.</p>
 *
 * <p>The read admits the write and the read-only role because it materializes rows: the GET is the
 * documented consequence of reading a range (G11), so splitting the privilege here would only force
 * the read-only role through a write endpoint it is already denied.</p>
 */
@RestController
@RequestMapping("/api/scheduling/slots")
@Tag(name = "Scheduling Slots", description = "API for reading and materializing an activity's bookable slots")
public class SchedulingSlotController {

    private final SchedulingSlotService schedulingSlotService;

    public SchedulingSlotController(SchedulingSlotService schedulingSlotService) {
        this.schedulingSlotService = schedulingSlotService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + SCHEDULING + "','" + SCHEDULING_READ + "')")
    @Operation(
            summary = "Materialize and read an activity's slots for a range",
            description =
                    "Materializes the activity's availability template over [from, to) and returns the slots ordered by start. The range must be non-empty and at most 90 days wide. The caller must be able to access the activity's store scope.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The activity's slots in the requested range"),
        @ApiResponse(responseCode = "400", description = "The range is empty, inverted or wider than 90 days"),
        @ApiResponse(responseCode = "403", description = "The caller cannot access the activity's store"),
        @ApiResponse(responseCode = "404", description = "Scheduling activity not found")
    })
    public ResponseEntity<List<SchedulingSlotResponse>> getSlots(
            @RequestParam UUID activityId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return ResponseEntity.ok(schedulingSlotService.getSlots(activityId, from, to));
    }
}
