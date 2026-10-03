package com.lifecontrol.api.hr.controller;

import static com.lifecontrol.api.common.security.Roles.ADMIN;
import static com.lifecontrol.api.common.security.Roles.EMPLOYEE;

import com.lifecontrol.api.hr.dto.CloseContractRequest;
import com.lifecontrol.api.hr.dto.ContractRequest;
import com.lifecontrol.api.hr.dto.ContractResponse;
import com.lifecontrol.api.hr.service.ContractService;
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
 * Company-scoped contract-history endpoints of an employee. Every path nests under
 * {@code /api/companies/{companyId}/employees/{employeeId}/contracts} so both scopes are part of the
 * wire contract. The read requires only authentication; the writes require {@code lc-admin} or
 * {@code lc-employee}.
 *
 * <p>There are exactly three routes (decision D12). There is deliberately <b>no</b> contract
 * {@code PUT}: a salary change or a promotion is a new contract, which is the reason the history
 * table exists. There is deliberately no contract {@code DELETE}: the record defines no soft-delete
 * route for a contract.</p>
 */
@RestController
@RequestMapping("/api/companies/{companyId}/employees/{employeeId}/contracts")
@Tag(name = "Contract Management", description = "API for managing the employment contract history of an employee")
public class ContractController {

    private final ContractService contractService;

    public ContractController(ContractService contractService) {
        this.contractService = contractService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "List an employee's contracts",
            description = "Returns the employee's contract history ordered newest first, closed and "
                    + "soft-deleted rows included; a foreign employee is not found")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "List of contracts"),
        @ApiResponse(responseCode = "404", description = "Employee or company not found")
    })
    public ResponseEntity<List<ContractResponse>> getContracts(
            @PathVariable UUID companyId, @PathVariable UUID employeeId) {
        return ResponseEntity.ok(contractService.getContracts(companyId, employeeId));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + EMPLOYEE + "')")
    @Operation(
            summary = "Open a new contract",
            description = "Creates a contract and closes the employee's previous one the day before its "
                    + "start date; the position must belong to the employee's company and a Terminated "
                    + "employee cannot open a contract")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Contract created"),
        @ApiResponse(responseCode = "400", description = "Validation error, inverted dates or an unknown contractType"),
        @ApiResponse(responseCode = "404", description = "Employee, company, position or seniority level not found"),
        @ApiResponse(responseCode = "409", description = "The employee is Terminated or the contract overlaps another")
    })
    public ResponseEntity<ContractResponse> createContract(
            @PathVariable UUID companyId, @PathVariable UUID employeeId, @Valid @RequestBody ContractRequest request) {
        var response = contractService.createContract(companyId, employeeId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PatchMapping("/{id}/close")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + EMPLOYEE + "')")
    @Operation(
            summary = "Close an open contract",
            description = "Closes a contract by setting its end date, defaulting to today when the body is "
                    + "omitted; only an open-ended contract can be closed and closing changes nothing else")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Contract closed"),
        @ApiResponse(responseCode = "400", description = "The end date precedes the contract's start date"),
        @ApiResponse(responseCode = "404", description = "Employee, company or contract not found"),
        @ApiResponse(responseCode = "409", description = "The contract is already closed")
    })
    public ResponseEntity<ContractResponse> closeContract(
            @PathVariable UUID companyId,
            @PathVariable UUID employeeId,
            @PathVariable UUID id,
            @RequestBody(required = false) CloseContractRequest request) {
        return ResponseEntity.ok(contractService.closeContract(companyId, employeeId, id, request));
    }
}
