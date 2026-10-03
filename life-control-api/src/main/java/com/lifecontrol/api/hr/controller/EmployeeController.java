package com.lifecontrol.api.hr.controller;

import static com.lifecontrol.api.common.security.Roles.ADMIN;
import static com.lifecontrol.api.common.security.Roles.EMPLOYEE;

import com.lifecontrol.api.hr.dto.EmployeeEmailSuggestionResponse;
import com.lifecontrol.api.hr.dto.EmployeeRequest;
import com.lifecontrol.api.hr.dto.EmployeeResponse;
import com.lifecontrol.api.hr.service.EmployeeService;
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
 * Company-scoped employee registry endpoints. Every path nests under
 * {@code /api/companies/{companyId}} so the company scope is part of the wire contract. Reads require
 * only authentication; writes require {@code lc-admin} or {@code lc-employee}.
 *
 * <p>The list filters by an optional {@code search}, {@code statusId} and {@code includeDisabled}
 * and is deliberately unpaginated, following every other company-scoped list in the repository.
 * {@code suggest-email} answers a candidate address or a machine-readable reason; it reserves
 * nothing.</p>
 */
@RestController
@RequestMapping("/api/companies/{companyId}/employees")
@Tag(name = "Employee Management", description = "API for managing employees within a company")
public class EmployeeController {

    private final EmployeeService employeeService;

    public EmployeeController(EmployeeService employeeService) {
        this.employeeService = employeeService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "List all employees",
            description = "Returns a company's employees ordered by employee number ascending, "
                    + "optionally filtered by a search term and a status; disabled employees are "
                    + "excluded unless includeDisabled is true")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "List of employees")})
    public ResponseEntity<List<EmployeeResponse>> getAllEmployees(
            @PathVariable UUID companyId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UUID statusId,
            @RequestParam(defaultValue = "false") boolean includeDisabled) {
        return ResponseEntity.ok(employeeService.getAllEmployees(companyId, search, statusId, includeDisabled));
    }

    @GetMapping("/suggest-email")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Suggest an employee email",
            description = "Returns the next free candidate address for the given names, or a reason "
                    + "(NO_EMAIL_DOMAIN, EMPTY_LOCAL_PART, NO_FREE_CANDIDATE); it reserves nothing")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Suggestion or reason")})
    public ResponseEntity<EmployeeEmailSuggestionResponse> suggestEmail(
            @PathVariable UUID companyId, @RequestParam String firstName, @RequestParam String paternalLastName) {
        return ResponseEntity.ok(employeeService.suggestEmail(companyId, firstName, paternalLastName));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Get an employee by ID",
            description = "Returns a single employee of the company; an employee of another company is not found")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Employee found"),
        @ApiResponse(responseCode = "404", description = "Employee not found")
    })
    public ResponseEntity<EmployeeResponse> getEmployeeById(@PathVariable UUID companyId, @PathVariable UUID id) {
        return ResponseEntity.ok(employeeService.getEmployeeById(companyId, id));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + EMPLOYEE + "')")
    @Operation(
            summary = "Create a new employee",
            description = "Creates an employee inside the company; the employee number and email must be "
                    + "unique for that company and a company without a configured email domain fails closed")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Employee created"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "404", description = "Company not found"),
        @ApiResponse(responseCode = "409", description = "Employee number or email already exists in the company")
    })
    public ResponseEntity<EmployeeResponse> createEmployee(
            @PathVariable UUID companyId, @Valid @RequestBody EmployeeRequest request) {
        var response = employeeService.createEmployee(companyId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + EMPLOYEE + "')")
    @Operation(
            summary = "Update an employee",
            description = "Updates an existing employee of the company; the email is frozen once the record "
                    + "is linked to a Keycloak account")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Employee updated"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "404", description = "Employee or company not found"),
        @ApiResponse(responseCode = "409", description = "Employee number already exists or the email is frozen")
    })
    public ResponseEntity<EmployeeResponse> updateEmployee(
            @PathVariable UUID companyId, @PathVariable UUID id, @Valid @RequestBody EmployeeRequest request) {
        return ResponseEntity.ok(employeeService.updateEmployee(companyId, id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + EMPLOYEE + "')")
    @Operation(
            summary = "Delete an employee",
            description = "Soft-deletes an employee by setting enabled to false; the row is kept")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Employee soft-deleted"),
        @ApiResponse(responseCode = "404", description = "Employee or company not found")
    })
    public ResponseEntity<Void> deleteEmployee(@PathVariable UUID companyId, @PathVariable UUID id) {
        employeeService.deleteEmployee(companyId, id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/enable")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + EMPLOYEE + "')")
    @Operation(
            summary = "Enable or disable an employee",
            description = "Sets the enabled flag of an employee of the company; it is a set, not a toggle")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Employee status updated"),
        @ApiResponse(responseCode = "404", description = "Employee or company not found")
    })
    public ResponseEntity<EmployeeResponse> setEmployeeEnabled(
            @PathVariable UUID companyId, @PathVariable UUID id, @RequestBody Map<String, Boolean> body) {
        var enabled = body.getOrDefault("enabled", true);
        return ResponseEntity.ok(employeeService.setEmployeeEnabled(companyId, id, enabled));
    }
}
