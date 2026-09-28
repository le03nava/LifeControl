package com.lifecontrol.api.scheduling.controller;

import static com.lifecontrol.api.common.security.Roles.ADMIN;
import static com.lifecontrol.api.common.security.Roles.SCHEDULING;
import static com.lifecontrol.api.common.security.Roles.SCHEDULING_READ;

import com.lifecontrol.api.scheduling.dto.SchedulingActivityRequest;
import com.lifecontrol.api.scheduling.dto.SchedulingActivityResponse;
import com.lifecontrol.api.scheduling.service.SchedulingActivityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
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
 * REST API for the per-store scheduling activity catalogue.
 *
 * <p>Flat and store-derived, like {@code GoodsReceiptController}: the endpoints are addressed by
 * activity id and the store travels as a query or body parameter, so no store segment is in the
 * path. {@code @PreAuthorize} alone proves only that the caller holds some scheduling-scoped role;
 * {@link SchedulingActivityService} additionally derives the store chain from the store itself and
 * authorizes every read and write against it.</p>
 *
 * <p>Reads allow {@code lc-scheduling-read}; the writes do not. Both reads and writes allow
 * {@code lc-scheduling} and {@code lc-admin}.</p>
 */
@RestController
@RequestMapping("/api/scheduling/activities")
@Tag(name = "Scheduling Activities", description = "API for managing the store's bookable scheduling activities")
public class SchedulingActivityController {

    private final SchedulingActivityService schedulingActivityService;

    public SchedulingActivityController(SchedulingActivityService schedulingActivityService) {
        this.schedulingActivityService = schedulingActivityService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + SCHEDULING + "','" + SCHEDULING_READ + "')")
    @Operation(
            summary = "Get all scheduling activities",
            description =
                    "Returns a paginated list of the store's activities, ordered by name. Soft-deleted activities are omitted unless includeDisabled is true. The caller must be able to access the store's scope.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Paginated list of scheduling activities"),
        @ApiResponse(responseCode = "403", description = "The caller cannot access the store's scope"),
        @ApiResponse(responseCode = "404", description = "Store not found")
    })
    public ResponseEntity<Page<SchedulingActivityResponse>> getAllActivities(
            @RequestParam UUID storeId,
            @RequestParam(defaultValue = "false") boolean includeDisabled,
            @PageableDefault(size = 12) Pageable pageable) {
        return ResponseEntity.ok(schedulingActivityService.getActivities(storeId, includeDisabled, pageable));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + SCHEDULING + "','" + SCHEDULING_READ + "')")
    @Operation(
            summary = "Get a scheduling activity by ID",
            description = "Returns a single activity. Access is verified against the activity's own store chain.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Scheduling activity found"),
        @ApiResponse(responseCode = "403", description = "The caller cannot access the activity's store"),
        @ApiResponse(responseCode = "404", description = "Scheduling activity not found")
    })
    public ResponseEntity<SchedulingActivityResponse> getActivityById(@PathVariable UUID id) {
        return ResponseEntity.ok(schedulingActivityService.getActivity(id));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + SCHEDULING + "')")
    @Operation(
            summary = "Create a scheduling activity",
            description = "Creates an activity in the store named by companyStoreId. enabled defaults to true.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Scheduling activity created"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "403", description = "The caller cannot access the store's scope"),
        @ApiResponse(responseCode = "404", description = "Store not found"),
        @ApiResponse(responseCode = "409", description = "The store already has an activity with that name")
    })
    public ResponseEntity<SchedulingActivityResponse> createActivity(
            @Valid @RequestBody SchedulingActivityRequest request) {
        var response = schedulingActivityService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + SCHEDULING + "')")
    @Operation(
            summary = "Update a scheduling activity",
            description =
                    "Updates an activity in place. The store is immutable and ignored in the body; a non-null version is an optional precondition that yields a 412 when it no longer matches.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Scheduling activity updated"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "403", description = "The caller cannot access the activity's store"),
        @ApiResponse(responseCode = "404", description = "Scheduling activity not found"),
        @ApiResponse(responseCode = "409", description = "The store already has an activity with that name"),
        @ApiResponse(responseCode = "412", description = "The asserted version no longer holds")
    })
    public ResponseEntity<SchedulingActivityResponse> updateActivity(
            @PathVariable UUID id, @Valid @RequestBody SchedulingActivityRequest request) {
        return ResponseEntity.ok(schedulingActivityService.update(id, request));
    }

    @PatchMapping("/{id}/enable")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + SCHEDULING + "')")
    @Operation(summary = "Enable a scheduling activity", description = "Re-enables a soft-deleted activity")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Scheduling activity enabled"),
        @ApiResponse(responseCode = "403", description = "The caller cannot access the activity's store"),
        @ApiResponse(responseCode = "404", description = "Scheduling activity not found")
    })
    public ResponseEntity<SchedulingActivityResponse> enableActivity(@PathVariable UUID id) {
        return ResponseEntity.ok(schedulingActivityService.enable(id));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + SCHEDULING + "')")
    @Operation(
            summary = "Delete a scheduling activity",
            description = "Soft-deletes an activity by setting enabled to false")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Scheduling activity deleted"),
        @ApiResponse(responseCode = "403", description = "The caller cannot access the activity's store"),
        @ApiResponse(responseCode = "404", description = "Scheduling activity not found")
    })
    public ResponseEntity<Void> deleteActivity(@PathVariable UUID id) {
        schedulingActivityService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
