package com.lifecontrol.api.provisioning.controller;

import static com.lifecontrol.api.common.security.Roles.ADMIN;
import static com.lifecontrol.api.common.security.Roles.EMPLOYEE_ACCESS;

import com.lifecontrol.api.provisioning.dto.AccessProvisioningQueueEntry;
import com.lifecontrol.api.provisioning.service.AccessProvisioningQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The company's <b>decision queue</b>: the tasks of its employees waiting for a human, as one read
 * (unit W5d, slice B, record T94).
 *
 * <p>It is a controller of its own rather than a route on {@link AccessProvisioningController}, and
 * the reason is the URL: that class fixes
 * {@code /api/companies/{companyId}/employees/{employeeId}/access} at the type level, because every
 * route it carries is <b>about one employee</b>, while this one is about a company and has no employee
 * in its path. Folding it in would mean moving the sibling's type-level mapping into each of its seven
 * methods — a rewrite of a landed, natively reviewed surface to gain nothing — so the queue gets its
 * own class and the sibling keeps its shape.</p>
 *
 * <p><b>The read is scoped by the caller's own company access and by nothing else.</b> The service
 * verifies the company before any load (the E12 convention every sibling follows), which is what keeps
 * a queue from being a cross-company read: the path's {@code companyId} is verified against the
 * caller's claims, and the rows come from that company's employees only. The gate is the same
 * {@code lc-admin} plus {@code lc-employee-access} pair the sibling carries, with the same reasoning
 * and the same deliberate absence of a read-only role (O5); the method annotation <b>is</b> the gate,
 * because {@code SecurityConfig} leaves {@code /api/**} as {@code authenticated()} only.</p>
 */
@RestController
@RequestMapping("/api/companies/{companyId}/access/requests")
@Tag(name = "Employee Access Provisioning", description = "The company's pending access decisions, as one read")
public class AccessProvisioningQueueController {

    private final AccessProvisioningQueryService accessProvisioningQueryService;

    public AccessProvisioningQueueController(AccessProvisioningQueryService accessProvisioningQueryService) {
        this.accessProvisioningQueryService = accessProvisioningQueryService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + EMPLOYEE_ACCESS + "')")
    @Operation(
            summary = "List the company's pending access decisions",
            description = "Returns the tasks of this company's employees waiting for a human decision, oldest"
                    + " request first, with the employee each one belongs to and the requester's name")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The company's decision queue"),
        @ApiResponse(responseCode = "403", description = "Insufficient role or no access to the company")
    })
    public ResponseEntity<List<AccessProvisioningQueueEntry>> getDecisionQueue(@PathVariable UUID companyId) {
        return ResponseEntity.ok(accessProvisioningQueryService.getDecisionQueue(companyId));
    }
}
