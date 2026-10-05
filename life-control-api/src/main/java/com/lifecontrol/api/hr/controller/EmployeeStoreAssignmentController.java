package com.lifecontrol.api.hr.controller;

import static com.lifecontrol.api.common.security.Roles.ADMIN;
import static com.lifecontrol.api.common.security.Roles.EMPLOYEE;

import com.lifecontrol.api.hr.dto.CloseStoreAssignmentRequest;
import com.lifecontrol.api.hr.dto.StoreAssignmentRequest;
import com.lifecontrol.api.hr.dto.StoreAssignmentResponse;
import com.lifecontrol.api.hr.service.EmployeeStoreAssignmentService;
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
 * Company-scoped store-assignment endpoints of an employee. Every path nests under
 * {@code /api/companies/{companyId}/employees/{employeeId}/store-assignments} so both scopes are part
 * of the wire contract. The read requires only authentication; the writes require {@code lc-admin} or
 * {@code lc-employee}.
 *
 * <p>The controller is deliberately thin: the company scope is resolved inside
 * {@code EmployeeStoreAssignmentService} (which calls {@code verifyCompanyAccess} before loading
 * anything), so nothing here re-implements the scope check, the validation or the day arithmetic.
 * There is deliberately no {@code PUT} and no {@code DELETE} (decision T5): a transfer is a new
 * assignment and a mistake is closed, because rewriting a row would erase when someone stopped
 * working somewhere. The {@code PATCH …/{id}/close} route closes an open assignment exactly once,
 * with the body's end date defaulting to today (decisions T5 and D5).</p>
 */
@RestController
@RequestMapping("/api/companies/{companyId}/employees/{employeeId}/store-assignments")
@Tag(name = "Store Assignment Management", description = "API for managing the store assignments of an employee")
public class EmployeeStoreAssignmentController {

    private final EmployeeStoreAssignmentService employeeStoreAssignmentService;

    public EmployeeStoreAssignmentController(EmployeeStoreAssignmentService employeeStoreAssignmentService) {
        this.employeeStoreAssignmentService = employeeStoreAssignmentService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "List an employee's store assignments",
            description = "Returns the employee's store-assignment history newest first, closed rows "
                    + "included and soft-deleted rows only with includeDisabled=true; a foreign "
                    + "employee is not found")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "List of store assignments"),
        @ApiResponse(responseCode = "404", description = "Employee or company not found")
    })
    public ResponseEntity<List<StoreAssignmentResponse>> getAssignments(
            @PathVariable UUID companyId,
            @PathVariable UUID employeeId,
            @RequestParam(defaultValue = "false") boolean includeDisabled) {
        return ResponseEntity.ok(employeeStoreAssignmentService.getAssignments(companyId, employeeId, includeDisabled));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + EMPLOYEE + "')")
    @Operation(
            summary = "Open a new store assignment",
            description = "Creates a store assignment and closes the employee's previous assignment for "
                    + "the same store the day before its start date; the store must belong to the "
                    + "employee's company and a foreign store or employee is not found")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Store assignment created"),
        @ApiResponse(
                responseCode = "400",
                description = "Validation error or a start date on the previous assignment's start date"),
        @ApiResponse(responseCode = "404", description = "Employee, company or store not found"),
        @ApiResponse(responseCode = "409", description = "The assignment overlaps an existing one")
    })
    public ResponseEntity<StoreAssignmentResponse> createAssignment(
            @PathVariable UUID companyId,
            @PathVariable UUID employeeId,
            @Valid @RequestBody StoreAssignmentRequest request) {
        var response = employeeStoreAssignmentService.createAssignment(companyId, employeeId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PatchMapping("/{id}/close")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + EMPLOYEE + "')")
    @Operation(
            summary = "Close an open store assignment",
            description = "Closes a store assignment by setting its end date, defaulting to today when "
                    + "the body is omitted; only an open-ended assignment can be closed and closing "
                    + "changes nothing else")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Store assignment closed"),
        @ApiResponse(responseCode = "400", description = "The end date precedes the assignment's start date"),
        @ApiResponse(responseCode = "404", description = "Employee, company or store assignment not found"),
        @ApiResponse(responseCode = "409", description = "The assignment is already closed")
    })
    public ResponseEntity<StoreAssignmentResponse> closeAssignment(
            @PathVariable UUID companyId,
            @PathVariable UUID employeeId,
            @PathVariable UUID id,
            @RequestBody(required = false) CloseStoreAssignmentRequest request) {
        return ResponseEntity.ok(employeeStoreAssignmentService.closeAssignment(companyId, employeeId, id, request));
    }
}
