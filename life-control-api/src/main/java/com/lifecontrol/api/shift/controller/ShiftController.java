package com.lifecontrol.api.shift.controller;

import com.lifecontrol.api.shift.dto.ShiftRequest;
import com.lifecontrol.api.shift.dto.ShiftResponse;
import com.lifecontrol.api.shift.service.ShiftService;
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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/shifts")
@Tag(name = "Shift Management", description = "API for managing cash register shifts (turnos)")
public class ShiftController {

    private final ShiftService shiftService;

    public ShiftController(ShiftService shiftService) {
        this.shiftService = shiftService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('lc-admin','lc-sales')")
    @Operation(summary = "Get all shifts", description = "Returns a paginated list of shifts")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Paginated list of shifts")
    })
    public ResponseEntity<Page<ShiftResponse>> getAllShifts(
            @PageableDefault(size = 12) Pageable pageable) {
        return ResponseEntity.ok(shiftService.getAllShifts(pageable));
    }

    @GetMapping("/open")
    @PreAuthorize("hasAnyRole('lc-admin','lc-sales')")
    @Operation(summary = "Get open shifts", description = "Returns all currently open shifts")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "List of open shifts")
    })
    public ResponseEntity<List<ShiftResponse>> getOpenShifts() {
        return ResponseEntity.ok(shiftService.getOpenShifts());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('lc-admin','lc-sales')")
    @Operation(summary = "Get shift by ID", description = "Returns a single shift")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Shift found"),
        @ApiResponse(responseCode = "404", description = "Shift not found")
    })
    public ResponseEntity<ShiftResponse> getShiftById(@PathVariable UUID id) {
        return ResponseEntity.ok(shiftService.getShiftById(id));
    }

    @PostMapping("/open")
    @PreAuthorize("hasAnyRole('lc-admin','lc-sales')")
    @Operation(summary = "Open a new shift", description = "Opens a new shift for a store. A store can only have one open shift at a time.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Shift opened"),
        @ApiResponse(responseCode = "409", description = "An open shift already exists for this store"),
        @ApiResponse(responseCode = "400", description = "Validation error")
    })
    public ResponseEntity<ShiftResponse> openShift(@Valid @RequestBody ShiftRequest request) {
        var response = shiftService.openShift(request.companyStoreId(), request.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAnyRole('lc-admin','lc-sales')")
    @Operation(summary = "Close a shift", description = "Closes an open shift by setting closedAt and changing status to CERRADO")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Shift closed"),
        @ApiResponse(responseCode = "404", description = "Shift not found"),
        @ApiResponse(responseCode = "409", description = "Shift is not open")
    })
    public ResponseEntity<ShiftResponse> closeShift(@PathVariable UUID id) {
        return ResponseEntity.ok(shiftService.closeShift(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('lc-admin','lc-sales')")
    @Operation(summary = "Update a shift", description = "Updates an existing shift")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Shift updated"),
        @ApiResponse(responseCode = "404", description = "Shift not found")
    })
    public ResponseEntity<ShiftResponse> updateShift(
            @PathVariable UUID id,
            @Valid @RequestBody ShiftRequest request) {
        return ResponseEntity.ok(shiftService.updateShift(id, request));
    }

    @PatchMapping("/{id}/enable")
    @PreAuthorize("hasAnyRole('lc-admin','lc-sales')")
    @Operation(summary = "Re-enable a shift", description = "Re-enables a soft-deleted shift")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Shift re-enabled"),
        @ApiResponse(responseCode = "404", description = "Shift not found")
    })
    public ResponseEntity<ShiftResponse> enableShift(@PathVariable UUID id) {
        return ResponseEntity.ok(shiftService.enableShift(id));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('lc-admin','lc-sales')")
    @Operation(summary = "Delete a shift", description = "Soft-deletes a shift")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Shift deleted"),
        @ApiResponse(responseCode = "404", description = "Shift not found")
    })
    public ResponseEntity<Void> deleteShift(@PathVariable UUID id) {
        shiftService.deleteShift(id);
        return ResponseEntity.noContent().build();
    }
}
