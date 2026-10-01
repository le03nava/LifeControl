package com.lifecontrol.api.hr.controller;

import static com.lifecontrol.api.common.security.Roles.ADMIN;
import static com.lifecontrol.api.common.security.Roles.POSITION;

import com.lifecontrol.api.hr.dto.PositionRequest;
import com.lifecontrol.api.hr.dto.PositionResponse;
import com.lifecontrol.api.hr.service.PositionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Company-scoped position catalog endpoints. Every path nests under
 * {@code /api/companies/{companyId}} so the company scope is part of the wire contract. Reads require
 * only authentication; writes require {@code lc-admin} or {@code lc-position}.
 *
 * <p>The list filters by an optional {@code departmentId}. A position's {@code reportsToPositionId}
 * is validated by the service against the company and against reporting cycles, so the 400 responses
 * listed below cover both.</p>
 */
@RestController
@RequestMapping("/api/companies/{companyId}/positions")
@Tag(name = "Position Management", description = "API for managing positions within a company")
public class PositionController {

    private final PositionService positionService;

    public PositionController(PositionService positionService) {
        this.positionService = positionService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "List all positions",
            description = "Returns a company's positions sorted by display order then code ascending, "
                    + "optionally filtered by department; disabled positions are excluded unless "
                    + "includeDisabled is true")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "List of positions")})
    public ResponseEntity<List<PositionResponse>> getAllPositions(
            @PathVariable UUID companyId,
            @RequestParam(required = false) UUID departmentId,
            @RequestParam(defaultValue = "false") boolean includeDisabled) {
        return ResponseEntity.ok(positionService.getAllPositions(companyId, departmentId, includeDisabled));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Get a position by ID",
            description = "Returns a single position of the company; a position of another company is not found")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Position found"),
        @ApiResponse(responseCode = "404", description = "Position not found")
    })
    public ResponseEntity<PositionResponse> getPositionById(@PathVariable UUID companyId, @PathVariable UUID id) {
        return ResponseEntity.ok(positionService.getPositionById(companyId, id));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + POSITION + "')")
    @Operation(
            summary = "Create a new position",
            description = "Creates a position inside a department of the company; the code and name must be "
                    + "unique for that department, and the reporting position must belong to the same company "
                    + "without closing a cycle")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Position created"),
        @ApiResponse(responseCode = "400", description = "Validation error, cross-company parent or reporting cycle"),
        @ApiResponse(responseCode = "404", description = "Company, department or reporting position not found"),
        @ApiResponse(responseCode = "409", description = "Position code or name already exists in the department")
    })
    public ResponseEntity<PositionResponse> createPosition(
            @PathVariable UUID companyId, @Valid @RequestBody PositionRequest request) {
        var response = positionService.createPosition(companyId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + POSITION + "')")
    @Operation(
            summary = "Update a position",
            description = "Updates an existing position of the company, including its department and reporting "
                    + "position; a move re-checks the code and name against the new department")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Position updated"),
        @ApiResponse(responseCode = "400", description = "Validation error, cross-company parent or reporting cycle"),
        @ApiResponse(
                responseCode = "404",
                description = "Position, company, department or reporting position not found"),
        @ApiResponse(responseCode = "409", description = "Position code or name already exists in the department")
    })
    public ResponseEntity<PositionResponse> updatePosition(
            @PathVariable UUID companyId, @PathVariable UUID id, @Valid @RequestBody PositionRequest request) {
        return ResponseEntity.ok(positionService.updatePosition(companyId, id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + POSITION + "')")
    @Operation(
            summary = "Delete a position",
            description = "Soft-deletes a position by setting enabled to false; the row is kept")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Position soft-deleted"),
        @ApiResponse(responseCode = "404", description = "Position or company not found")
    })
    public ResponseEntity<Void> deletePosition(@PathVariable UUID companyId, @PathVariable UUID id) {
        positionService.deletePosition(companyId, id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/enable")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + POSITION + "')")
    @Operation(
            summary = "Enable or disable a position",
            description = "Sets the enabled flag of a position of the company")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Position status updated"),
        @ApiResponse(responseCode = "404", description = "Position or company not found")
    })
    public ResponseEntity<PositionResponse> setPositionEnabled(
            @PathVariable UUID companyId, @PathVariable UUID id, @RequestBody Map<String, Boolean> body) {
        var enabled = body.getOrDefault("enabled", true);
        return ResponseEntity.ok(positionService.setPositionEnabled(companyId, id, enabled));
    }
}
