package com.lifecontrol.api.status.controller;

import com.lifecontrol.api.status.dto.StatusRequest;
import com.lifecontrol.api.status.dto.StatusResponse;
import com.lifecontrol.api.status.service.StatusService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/statuses")
@Tag(name = "Statuses", description = "API for managing status values")
public class StatusController {

    private final StatusService statusService;

    public StatusController(StatusService statusService) {
        this.statusService = statusService;
    }

    @GetMapping
    @Operation(summary = "Get all statuses by type", description = "Returns a list of statuses for the given status type. ?statusTypeId= is required.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Statuses found"),
        @ApiResponse(responseCode = "400", description = "statusTypeId is required"),
        @ApiResponse(responseCode = "404", description = "Status type not found")
    })
    public ResponseEntity<List<StatusResponse>> getStatusesByTypeId(
            @RequestParam(value = "statusTypeId", required = false) UUID statusTypeId,
            @RequestParam(value = "includeDisabled", defaultValue = "false") boolean includeDisabled) {
        if (statusTypeId == null) {
            throw new IllegalArgumentException("statusTypeId is required");
        }
        return ResponseEntity.ok(statusService.getStatusesByTypeId(statusTypeId));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get status by ID", description = "Returns a single status by its UUID. Optionally scope to a type with ?statusTypeId=")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Status found"),
        @ApiResponse(responseCode = "404", description = "Status not found")
    })
    public ResponseEntity<StatusResponse> getStatusById(
            @PathVariable UUID id,
            @RequestParam(required = false) UUID statusTypeId) {
        if (statusTypeId != null) {
            return ResponseEntity.ok(statusService.getStatusByIdAndTypeId(id, statusTypeId));
        }
        return ResponseEntity.ok(statusService.getStatusById(id));
    }

    @PostMapping
    @Operation(summary = "Create a new status", description = "Creates a new status value within a status type")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Status created"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "404", description = "Status type not found"),
        @ApiResponse(responseCode = "409", description = "Status name already exists within this type")
    })
    public ResponseEntity<StatusResponse> createStatus(@Valid @RequestBody StatusRequest request) {
        var response = statusService.createStatus(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a status", description = "Updates an existing status value")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Status updated"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "404", description = "Status or status type not found"),
        @ApiResponse(responseCode = "409", description = "Status name already exists within this type")
    })
    public ResponseEntity<StatusResponse> updateStatus(
            @PathVariable UUID id,
            @Valid @RequestBody StatusRequest request) {
        var response = statusService.updateStatus(id, request);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a status", description = "Soft-deletes a status by setting enabled to false")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Status deleted"),
        @ApiResponse(responseCode = "404", description = "Status not found")
    })
    public ResponseEntity<Void> deleteStatus(@PathVariable UUID id) {
        statusService.deleteStatus(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/enable")
    @Operation(summary = "Enable a status", description = "Re-enables a soft-deleted status value")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Status enabled"),
        @ApiResponse(responseCode = "404", description = "Status not found")
    })
    public ResponseEntity<StatusResponse> enableStatus(@PathVariable UUID id) {
        return ResponseEntity.ok(statusService.enableStatus(id));
    }
}
