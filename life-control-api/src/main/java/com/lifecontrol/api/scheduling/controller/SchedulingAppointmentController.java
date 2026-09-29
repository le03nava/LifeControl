package com.lifecontrol.api.scheduling.controller;

import static com.lifecontrol.api.common.security.Roles.ADMIN;
import static com.lifecontrol.api.common.security.Roles.SCHEDULING;
import static com.lifecontrol.api.common.security.Roles.SCHEDULING_READ;

import com.lifecontrol.api.scheduling.dto.SchedulingAppointmentRequest;
import com.lifecontrol.api.scheduling.dto.SchedulingAppointmentRescheduleRequest;
import com.lifecontrol.api.scheduling.dto.SchedulingAppointmentResponse;
import com.lifecontrol.api.scheduling.dto.SchedulingAppointmentStatusRequest;
import com.lifecontrol.api.scheduling.service.SchedulingAppointmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for the appointment booking lifecycle.
 *
 * <p>Flat and store-derived, like the rest of the scheduling domain: the endpoints are addressed by
 * appointment id, the booking request carries only a slot id, and the store is derived from the
 * slot's activity. {@code @PreAuthorize} proves only that the caller holds some scheduling-scoped
 * role; {@link SchedulingAppointmentService} authorizes every operation against the appointment's own
 * store.</p>
 *
 * <p>Reads allow {@code lc-scheduling-read}; the writes do not. Both reads and writes allow
 * {@code lc-scheduling} and {@code lc-admin}.</p>
 */
@RestController
@RequestMapping("/api/scheduling/appointments")
@Tag(name = "Scheduling Appointments", description = "API for booking and moving scheduling appointments")
public class SchedulingAppointmentController {

    private final SchedulingAppointmentService schedulingAppointmentService;

    public SchedulingAppointmentController(SchedulingAppointmentService schedulingAppointmentService) {
        this.schedulingAppointmentService = schedulingAppointmentService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + SCHEDULING + "')")
    @Operation(
            summary = "Book an appointment",
            description =
                    "Creates an appointment in the named slot with the Scheduled status. The store is derived from the slot's activity and the caller must be able to access it.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Appointment booked"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "403", description = "The caller cannot access the slot's store"),
        @ApiResponse(responseCode = "404", description = "Slot, activity, customer or status not found"),
        @ApiResponse(responseCode = "409", description = "The slot is disabled or full")
    })
    public ResponseEntity<SchedulingAppointmentResponse> createAppointment(
            @Valid @RequestBody SchedulingAppointmentRequest request) {
        var response = schedulingAppointmentService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + SCHEDULING + "','" + SCHEDULING_READ + "')")
    @Operation(
            summary = "Get an appointment by ID",
            description =
                    "Returns a single appointment with its slot window. Access is verified against the appointment's own store.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Appointment found"),
        @ApiResponse(responseCode = "403", description = "The caller cannot access the appointment's store"),
        @ApiResponse(responseCode = "404", description = "Appointment, slot or status not found")
    })
    public ResponseEntity<SchedulingAppointmentResponse> getAppointmentById(@PathVariable UUID id) {
        return ResponseEntity.ok(schedulingAppointmentService.getById(id));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + SCHEDULING + "','" + SCHEDULING_READ + "')")
    @Operation(
            summary = "List a store's appointments for a range",
            description =
                    "Returns the store's appointments whose slot's start_at falls in [from, to), ordered by slot start, optionally narrowed to one userId. Soft-deleted appointments are included with their enabled flag. The range must be non-empty and at most 90 days wide. The caller must be able to access the store scope.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The store's appointments in the requested range"),
        @ApiResponse(responseCode = "400", description = "The range is empty, inverted or wider than 90 days"),
        @ApiResponse(responseCode = "403", description = "The caller cannot access the store"),
        @ApiResponse(responseCode = "404", description = "Scheduling store, slot or status not found")
    })
    public ResponseEntity<List<SchedulingAppointmentResponse>> getAppointments(
            @RequestParam UUID storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) String userId) {
        return ResponseEntity.ok(schedulingAppointmentService.getAppointments(storeId, from, to, userId));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + SCHEDULING + "')")
    @Operation(
            summary = "Reschedule an appointment",
            description =
                    "Moves the appointment to another slot of the same activity, releasing the source and taking room on the target. Only allowed while the appointment's status is non-terminal (Scheduled or Confirmed).")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Appointment rescheduled"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "403", description = "The caller cannot access the appointment's store"),
        @ApiResponse(responseCode = "404", description = "Appointment, slot or status not found"),
        @ApiResponse(
                responseCode = "409",
                description = "The appointment is terminal or the target slot is not bookable")
    })
    public ResponseEntity<SchedulingAppointmentResponse> rescheduleAppointment(
            @PathVariable UUID id, @Valid @RequestBody SchedulingAppointmentRescheduleRequest request) {
        return ResponseEntity.ok(schedulingAppointmentService.reschedule(id, request));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + SCHEDULING + "')")
    @Operation(
            summary = "Update an appointment status",
            description =
                    "Validates the status type and the allowed transitions. A move into Cancelled or NoShow releases the slot's capacity.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Status updated"),
        @ApiResponse(responseCode = "400", description = "Validation error or wrong status type"),
        @ApiResponse(responseCode = "403", description = "The caller cannot access the appointment's store"),
        @ApiResponse(responseCode = "404", description = "Appointment, slot or status not found"),
        @ApiResponse(responseCode = "409", description = "Invalid status transition")
    })
    public ResponseEntity<SchedulingAppointmentResponse> updateAppointmentStatus(
            @PathVariable UUID id, @Valid @RequestBody SchedulingAppointmentStatusRequest request) {
        return ResponseEntity.ok(schedulingAppointmentService.updateStatus(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + SCHEDULING + "')")
    @Operation(
            summary = "Cancel an appointment",
            description =
                    "Soft-deletes the appointment. A Scheduled or Confirmed appointment is also moved to Cancelled and its capacity released; a terminal appointment keeps its status and its capacity. Idempotent on an already-released appointment.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Appointment cancelled and soft-deleted"),
        @ApiResponse(responseCode = "403", description = "The caller cannot access the appointment's store"),
        @ApiResponse(responseCode = "404", description = "Appointment, slot or status not found")
    })
    public ResponseEntity<Void> deleteAppointment(@PathVariable UUID id) {
        schedulingAppointmentService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
