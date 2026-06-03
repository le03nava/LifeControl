package com.lifecontrol.api.status.controller;

import com.lifecontrol.api.status.dto.StatusTypeRequest;
import com.lifecontrol.api.status.dto.StatusTypeResponse;
import com.lifecontrol.api.status.service.StatusTypeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/status-types")
@Tag(name = "Status Types", description = "API for managing status types")
public class StatusTypeController {

    private final StatusTypeService statusTypeService;

    public StatusTypeController(StatusTypeService statusTypeService) {
        this.statusTypeService = statusTypeService;
    }

    @GetMapping
    @Operation(summary = "Get all status types", description = "Returns a paginated list of status types. Use ?search= to filter by name.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Paginated list of status types")
    })
    public ResponseEntity<Page<StatusTypeResponse>> getAllStatusTypes(
            @PageableDefault(size = 12) Pageable pageable,
            @RequestParam(required = false) String search) {
        return ResponseEntity.ok(statusTypeService.getAllStatusTypes(pageable, search));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get status type by ID", description = "Returns a single status type by its UUID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Status type found"),
        @ApiResponse(responseCode = "404", description = "Status type not found")
    })
    public ResponseEntity<StatusTypeResponse> getStatusTypeById(@PathVariable UUID id) {
        return ResponseEntity.ok(statusTypeService.getStatusTypeById(id));
    }

    @PostMapping
    @Operation(summary = "Create a new status type", description = "Creates a new status type with the provided details")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Status type created"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "409", description = "Status type name already exists")
    })
    public ResponseEntity<StatusTypeResponse> createStatusType(@Valid @RequestBody StatusTypeRequest request) {
        var response = statusTypeService.createStatusType(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a status type", description = "Updates an existing status type")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Status type updated"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "404", description = "Status type not found"),
        @ApiResponse(responseCode = "409", description = "Status type name already exists")
    })
    public ResponseEntity<StatusTypeResponse> updateStatusType(
            @PathVariable UUID id,
            @Valid @RequestBody StatusTypeRequest request) {
        var response = statusTypeService.updateStatusType(id, request);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a status type", description = "Soft-deletes a status type by setting enabled to false")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Status type deleted"),
        @ApiResponse(responseCode = "404", description = "Status type not found")
    })
    public ResponseEntity<Void> deleteStatusType(@PathVariable UUID id) {
        statusTypeService.deleteStatusType(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/enable")
    @Operation(summary = "Enable a status type", description = "Re-enables a soft-deleted status type")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Status type enabled"),
        @ApiResponse(responseCode = "404", description = "Status type not found")
    })
    public ResponseEntity<StatusTypeResponse> enableStatusType(@PathVariable UUID id) {
        return ResponseEntity.ok(statusTypeService.enableStatusType(id));
    }
}
