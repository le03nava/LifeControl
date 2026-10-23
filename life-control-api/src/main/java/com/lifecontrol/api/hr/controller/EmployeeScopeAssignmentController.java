package com.lifecontrol.api.hr.controller;

import static com.lifecontrol.api.common.security.Roles.ADMIN;
import static com.lifecontrol.api.common.security.Roles.EMPLOYEE;

import com.lifecontrol.api.hr.dto.CloseScopeAssignmentRequest;
import com.lifecontrol.api.hr.dto.ScopeAssignmentRequest;
import com.lifecontrol.api.hr.dto.ScopeAssignmentResponse;
import com.lifecontrol.api.hr.service.EmployeeScopeAssignmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Company-scoped per-level scope-assignment endpoints of an employee. Every path nests under
 * {@code /api/companies/{companyId}/employees/{employeeId}/scope-assignments} so both scopes are part of
 * the wire contract. The read requires only authentication; the writes require {@code lc-admin} or
 * {@code lc-employee} — the same pair the store-assignment controller carries, because the same two
 * roles assign scope (S4).
 *
 * <p>The controller is deliberately thin: the company scope is resolved inside
 * {@link EmployeeScopeAssignmentService} (which calls {@code verifyCompanyAccess} before loading
 * anything), so nothing here re-implements the scope check, the one-node rule, the same-company rule or
 * the day arithmetic. There is deliberately no {@code PUT} and no {@code DELETE}, matching the store
 * surface: a move is a new assignment and a mistake is closed, because rewriting a row would erase when
 * someone stopped being assigned somewhere. The {@code PATCH …/{id}/close} route closes an open
 * assignment exactly once, with the body's end date defaulting to today.</p>
 */
@RestController
@RequestMapping("/api/companies/{companyId}/employees/{employeeId}/scope-assignments")
@Tag(
        name = "Scope Assignment Management",
        description = "API for managing the per-level (country, region, zone) scope assignments of an employee")
public class EmployeeScopeAssignmentController {

    private final EmployeeScopeAssignmentService employeeScopeAssignmentService;

    public EmployeeScopeAssignmentController(EmployeeScopeAssignmentService employeeScopeAssignmentService) {
        this.employeeScopeAssignmentService = employeeScopeAssignmentService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "List an employee's scope assignments",
            description = "Returns the employee's per-level assignment history newest first, closed rows "
                    + "included and soft-deleted rows only with includeDisabled=true; each row carries "
                    + "its level and its derived ancestor chain, and a foreign employee is not found")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "List of scope assignments"),
        @ApiResponse(responseCode = "404", description = "Employee or company not found")
    })
    public ResponseEntity<List<ScopeAssignmentResponse>> getAssignments(
            @PathVariable UUID companyId,
            @PathVariable UUID employeeId,
            @RequestParam(defaultValue = "false") boolean includeDisabled) {
        return ResponseEntity.ok(employeeScopeAssignmentService.getAssignments(companyId, employeeId, includeDisabled));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + EMPLOYEE + "')")
    @Operation(
            summary = "Open a new scope assignment",
            description = "Assigns one company-country, company-region or company-zone and closes the "
                    + "employee's previous assignment for the same node the day before its start date; "
                    + "exactly one node id is required, the node must belong to the employee's company, "
                    + "and a foreign node or employee is not found")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Scope assignment created"),
        @ApiResponse(
                responseCode = "400",
                description = "Validation error, no exactly-one node, or a start date on the previous "
                        + "assignment's start date"),
        @ApiResponse(responseCode = "404", description = "Employee, company or node not found"),
        @ApiResponse(responseCode = "409", description = "The assignment overlaps an existing one")
    })
    public ResponseEntity<ScopeAssignmentResponse> createAssignment(
            @PathVariable UUID companyId,
            @PathVariable UUID employeeId,
            @Valid @RequestBody ScopeAssignmentRequest request) {
        var response = employeeScopeAssignmentService.createAssignment(companyId, employeeId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PatchMapping("/{id}/close")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + EMPLOYEE + "')")
    @Operation(
            summary = "Close an open scope assignment",
            description = "Closes a scope assignment by setting its end date, defaulting to today when "
                    + "the body is omitted; only an open-ended assignment can be closed and closing "
                    + "changes nothing else")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Scope assignment closed"),
        @ApiResponse(responseCode = "400", description = "The end date precedes the assignment's start date"),
        @ApiResponse(responseCode = "404", description = "Employee, company or scope assignment not found"),
        @ApiResponse(responseCode = "409", description = "The assignment is already closed")
    })
    public ResponseEntity<ScopeAssignmentResponse> closeAssignment(
            @PathVariable UUID companyId,
            @PathVariable UUID employeeId,
            @PathVariable UUID id,
            @RequestBody(required = false) CloseScopeAssignmentRequest request) {
        return ResponseEntity.ok(employeeScopeAssignmentService.closeAssignment(companyId, employeeId, id, request));
    }
}
