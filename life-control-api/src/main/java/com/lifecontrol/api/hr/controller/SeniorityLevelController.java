package com.lifecontrol.api.hr.controller;

import static com.lifecontrol.api.common.security.Roles.ADMIN;
import static com.lifecontrol.api.common.security.Roles.SENIORITY_LEVEL;

import com.lifecontrol.api.hr.dto.SeniorityLevelRequest;
import com.lifecontrol.api.hr.dto.SeniorityLevelResponse;
import com.lifecontrol.api.hr.service.SeniorityLevelService;
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
 * Global seniority-level catalog endpoints. Reads require authentication; writes require {@code lc-admin}
 * or {@code lc-seniority-level}. The catalog is not company-scoped (decision D1), so no path or query
 * carries a company id.
 */
@RestController
@RequestMapping("/api/seniority-levels")
@Tag(name = "Seniority Levels", description = "API for managing the global seniority-level catalog")
public class SeniorityLevelController {

    private final SeniorityLevelService seniorityLevelService;

    public SeniorityLevelController(SeniorityLevelService seniorityLevelService) {
        this.seniorityLevelService = seniorityLevelService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Get all seniority levels",
            description = "Returns all seniority levels sorted by rank ascending; disabled levels are excluded "
                    + "unless includeDisabled is true")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "List of seniority levels")})
    public ResponseEntity<List<SeniorityLevelResponse>> getAllSeniorityLevels(
            @RequestParam(defaultValue = "false") boolean includeDisabled) {
        return ResponseEntity.ok(seniorityLevelService.getAllSeniorityLevels(includeDisabled));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get seniority level by ID", description = "Returns a single seniority level by its UUID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Seniority level found"),
        @ApiResponse(responseCode = "404", description = "Seniority level not found")
    })
    public ResponseEntity<SeniorityLevelResponse> getSeniorityLevelById(@PathVariable UUID id) {
        return ResponseEntity.ok(seniorityLevelService.getSeniorityLevelById(id));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + SENIORITY_LEVEL + "')")
    @Operation(
            summary = "Create a new seniority level",
            description = "Creates a new seniority level with the provided details")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Seniority level created"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "409", description = "Seniority level code, name or rank already exists")
    })
    public ResponseEntity<SeniorityLevelResponse> createSeniorityLevel(
            @Valid @RequestBody SeniorityLevelRequest request) {
        var response = seniorityLevelService.createSeniorityLevel(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + SENIORITY_LEVEL + "')")
    @Operation(summary = "Update a seniority level", description = "Updates an existing seniority level")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Seniority level updated"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "404", description = "Seniority level not found"),
        @ApiResponse(responseCode = "409", description = "Seniority level code, name or rank already exists")
    })
    public ResponseEntity<SeniorityLevelResponse> updateSeniorityLevel(
            @PathVariable UUID id, @Valid @RequestBody SeniorityLevelRequest request) {
        var response = seniorityLevelService.updateSeniorityLevel(id, request);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + SENIORITY_LEVEL + "')")
    @Operation(
            summary = "Delete a seniority level",
            description = "Soft-deletes a seniority level by setting enabled to false")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Seniority level soft-deleted"),
        @ApiResponse(responseCode = "404", description = "Seniority level not found")
    })
    public ResponseEntity<Void> deleteSeniorityLevel(@PathVariable UUID id) {
        seniorityLevelService.deleteSeniorityLevel(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/enable")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + SENIORITY_LEVEL + "')")
    @Operation(
            summary = "Enable or disable a seniority level",
            description = "Sets the enabled flag of a seniority level")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Seniority level status updated"),
        @ApiResponse(responseCode = "404", description = "Seniority level not found")
    })
    public ResponseEntity<SeniorityLevelResponse> setSeniorityLevelEnabled(
            @PathVariable UUID id, @RequestBody Map<String, Boolean> body) {
        var enabled = body.getOrDefault("enabled", true);
        return ResponseEntity.ok(seniorityLevelService.setSeniorityLevelEnabled(id, enabled));
    }
}
