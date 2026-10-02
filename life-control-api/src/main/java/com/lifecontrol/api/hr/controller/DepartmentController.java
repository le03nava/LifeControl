package com.lifecontrol.api.hr.controller;

import static com.lifecontrol.api.common.security.Roles.ADMIN;
import static com.lifecontrol.api.common.security.Roles.DEPARTMENT;

import com.lifecontrol.api.hr.dto.DepartmentRequest;
import com.lifecontrol.api.hr.dto.DepartmentResponse;
import com.lifecontrol.api.hr.service.DepartmentService;
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
 * Company-scoped department catalog endpoints. Every path nests under
 * {@code /api/companies/{companyId}} so the company scope is part of the wire contract. Reads require
 * only authentication; writes require {@code lc-admin} or {@code lc-department}.
 */
@RestController
@RequestMapping("/api/companies/{companyId}/departments")
@Tag(name = "Department Management", description = "API for managing departments within a company")
public class DepartmentController {

    private final DepartmentService departmentService;

    public DepartmentController(DepartmentService departmentService) {
        this.departmentService = departmentService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "List all departments",
            description = "Returns a company's departments sorted by display order then code ascending; "
                    + "disabled departments are excluded unless includeDisabled is true")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "List of departments")})
    public ResponseEntity<List<DepartmentResponse>> getAllDepartments(
            @PathVariable UUID companyId, @RequestParam(defaultValue = "false") boolean includeDisabled) {
        return ResponseEntity.ok(departmentService.getAllDepartments(companyId, includeDisabled));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Get a department by ID",
            description = "Returns a single department of the company; a department of another company is not found")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Department found"),
        @ApiResponse(responseCode = "404", description = "Department not found")
    })
    public ResponseEntity<DepartmentResponse> getDepartmentById(@PathVariable UUID companyId, @PathVariable UUID id) {
        return ResponseEntity.ok(departmentService.getDepartmentById(companyId, id));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + DEPARTMENT + "')")
    @Operation(
            summary = "Create a new department",
            description = "Creates a department inside the company; the code and name must be unique for that company")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Department created"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "404", description = "Company not found"),
        @ApiResponse(responseCode = "409", description = "Department code or name already exists in the company")
    })
    public ResponseEntity<DepartmentResponse> createDepartment(
            @PathVariable UUID companyId, @Valid @RequestBody DepartmentRequest request) {
        var response = departmentService.createDepartment(companyId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + DEPARTMENT + "')")
    @Operation(summary = "Update a department", description = "Updates an existing department of the company")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Department updated"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "404", description = "Department or company not found"),
        @ApiResponse(responseCode = "409", description = "Department code or name already exists in the company")
    })
    public ResponseEntity<DepartmentResponse> updateDepartment(
            @PathVariable UUID companyId, @PathVariable UUID id, @Valid @RequestBody DepartmentRequest request) {
        return ResponseEntity.ok(departmentService.updateDepartment(companyId, id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + DEPARTMENT + "')")
    @Operation(
            summary = "Delete a department",
            description = "Soft-deletes a department by setting enabled to false; the row is kept")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Department soft-deleted"),
        @ApiResponse(responseCode = "404", description = "Department or company not found")
    })
    public ResponseEntity<Void> deleteDepartment(@PathVariable UUID companyId, @PathVariable UUID id) {
        departmentService.deleteDepartment(companyId, id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/enable")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + DEPARTMENT + "')")
    @Operation(
            summary = "Enable or disable a department",
            description = "Sets the enabled flag of a department of the company")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Department status updated"),
        @ApiResponse(responseCode = "404", description = "Department or company not found")
    })
    public ResponseEntity<DepartmentResponse> setDepartmentEnabled(
            @PathVariable UUID companyId, @PathVariable UUID id, @RequestBody Map<String, Boolean> body) {
        var enabled = body.getOrDefault("enabled", true);
        return ResponseEntity.ok(departmentService.setDepartmentEnabled(companyId, id, enabled));
    }
}
