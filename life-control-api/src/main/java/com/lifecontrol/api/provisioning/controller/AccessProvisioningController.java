package com.lifecontrol.api.provisioning.controller;

import static com.lifecontrol.api.common.security.Roles.ADMIN;
import static com.lifecontrol.api.common.security.Roles.EMPLOYEE_ACCESS;

import com.lifecontrol.api.provisioning.dto.AccessProvisioningOverview;
import com.lifecontrol.api.provisioning.dto.AccessProvisioningRejectionRequest;
import com.lifecontrol.api.provisioning.service.AccessProvisioningGateService;
import com.lifecontrol.api.provisioning.service.AccessProvisioningQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The employee-access surface: the read route {@code GET …/access} (unit W5a) answering the whole
 * {@link AccessProvisioningOverview}, and the two decision routes (unit W5b)
 * {@code POST …/requests/{taskId}/approve} and {@code POST …/requests/{taskId}/reject}, which run the
 * decision on {@link AccessProvisioningGateService} and answer the same overview the {@code GET}
 * returns — one round trip for the UI, and no new DTO or mapping.
 *
 * <p>The URL nests under the employee resource, but the <b>package does not follow the URL</b>: this
 * projection owns its own tables, state machine, worker and gate, so it lives in its domain package
 * {@code com.lifecontrol.api.provisioning} rather than inside {@code hr} (record T16).</p>
 *
 * <p>The role set is {@code lc-admin} plus this record's own {@code lc-employee-access}, and there is
 * deliberately <b>no read-only pair</b>: the screen shows role names, not PII, so those two are the
 * only gate for now (record {@code employee-access-provisioning} O5). The method annotation <b>is</b>
 * the gate — {@code SecurityConfig} leaves {@code /api/**} as {@code authenticated()} only.</p>
 */
@RestController
@RequestMapping("/api/companies/{companyId}/employees/{employeeId}/access")
@Tag(name = "Employee Access Provisioning", description = "Read API for an employee's identity-provisioning state")
public class AccessProvisioningController {

    private final AccessProvisioningQueryService accessProvisioningQueryService;
    private final AccessProvisioningGateService accessProvisioningGateService;

    public AccessProvisioningController(
            AccessProvisioningQueryService accessProvisioningQueryService,
            AccessProvisioningGateService accessProvisioningGateService) {
        this.accessProvisioningQueryService = accessProvisioningQueryService;
        this.accessProvisioningGateService = accessProvisioningGateService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + EMPLOYEE_ACCESS + "')")
    @Operation(
            summary = "Get an employee's access overview",
            description = "Returns the linked account, the required roles against the current ones and "
                    + "their diff, the open task (with its attempts against the ceiling) and the history, "
                    + "and the five derived claim values")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Access overview"),
        @ApiResponse(responseCode = "403", description = "Insufficient role"),
        @ApiResponse(responseCode = "404", description = "Employee or company not found")
    })
    public ResponseEntity<AccessProvisioningOverview> getAccessOverview(
            @PathVariable UUID companyId, @PathVariable UUID employeeId) {
        return ResponseEntity.ok(accessProvisioningQueryService.getAccessOverview(companyId, employeeId));
    }

    @PostMapping("/requests/{taskId}/approve")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + EMPLOYEE_ACCESS + "')")
    @Operation(
            summary = "Approve a gated access request",
            description = "Approves an APPROVAL_PENDING task for the current user and returns the "
                    + "reloaded access overview. The requester of the request cannot approve it.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Access overview after the approval"),
        @ApiResponse(responseCode = "400", description = "The decision carries no deciding actor"),
        @ApiResponse(responseCode = "403", description = "Insufficient role"),
        @ApiResponse(responseCode = "404", description = "Employee, company or task not found"),
        @ApiResponse(
                responseCode = "409",
                description = "The requester cannot approve their own request, or the task is not awaiting approval")
    })
    public ResponseEntity<AccessProvisioningOverview> approve(
            @PathVariable UUID companyId, @PathVariable UUID employeeId, @PathVariable UUID taskId) {
        accessProvisioningGateService.approve(companyId, employeeId, taskId);
        return ResponseEntity.ok(accessProvisioningQueryService.getAccessOverview(companyId, employeeId));
    }

    @PostMapping("/requests/{taskId}/reject")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + EMPLOYEE_ACCESS + "')")
    @Operation(
            summary = "Reject a gated access request",
            description = "Rejects an APPROVAL_PENDING task for the current user and returns the "
                    + "reloaded access overview. A reason is required and is persisted on the task.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Access overview after the rejection"),
        @ApiResponse(responseCode = "400", description = "The decision carries no deciding actor, or no reason"),
        @ApiResponse(responseCode = "403", description = "Insufficient role"),
        @ApiResponse(responseCode = "404", description = "Employee, company or task not found"),
        @ApiResponse(responseCode = "409", description = "The task is not awaiting approval")
    })
    public ResponseEntity<AccessProvisioningOverview> reject(
            @PathVariable UUID companyId,
            @PathVariable UUID employeeId,
            @PathVariable UUID taskId,
            @RequestBody(required = false) AccessProvisioningRejectionRequest request) {
        accessProvisioningGateService.reject(companyId, employeeId, taskId, request == null ? null : request.reason());
        return ResponseEntity.ok(accessProvisioningQueryService.getAccessOverview(companyId, employeeId));
    }
}
