package com.lifecontrol.api.hr.controller;

import static com.lifecontrol.api.common.security.Roles.ADMIN;
import static com.lifecontrol.api.common.security.Roles.POSITION;

import com.lifecontrol.api.hr.dto.PositionRoleResponse;
import com.lifecontrol.api.hr.dto.PositionRolesRequest;
import com.lifecontrol.api.hr.service.PositionRoleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Company-scoped role-template endpoints of a position. The path nests under
 * {@code /api/companies/{companyId}/positions/{positionId}} so both the company scope and the
 * position are part of the wire contract. Reads require only authentication; the full-set save
 * requires {@code lc-admin} or {@code lc-position}.
 *
 * <p>The {@code PUT} body is the position's <b>complete</b> set of roles: a stored role the request
 * omits is disabled, never deleted. There is no {@code DELETE} endpoint (record T20). The {@code GET}
 * returns disabled rows too. The two sections of "Configuración del puesto" — salary bands and roles —
 * stay independent endpoints (decision T9).</p>
 */
@RestController
@RequestMapping("/api/companies/{companyId}/positions/{positionId}/roles")
@Tag(name = "Position Roles", description = "API for managing a position's role template")
public class PositionRoleController {

    private final PositionRoleService positionRoleService;

    public PositionRoleController(PositionRoleService positionRoleService) {
        this.positionRoleService = positionRoleService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "List a position's role template",
            description = "Returns every stored role of the position, disabled rows included, ordered by role name")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "List of roles")})
    public ResponseEntity<List<PositionRoleResponse>> getRoles(
            @PathVariable UUID companyId, @PathVariable UUID positionId) {
        return ResponseEntity.ok(positionRoleService.getRoles(companyId, positionId));
    }

    @PutMapping
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + POSITION + "')")
    @Operation(
            summary = "Replace a position's role template",
            description = "Upserts the request's complete set of roles on (position, role name); a stored role the "
                    + "request omits is disabled, never deleted")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Role template replaced"),
        @ApiResponse(
                responseCode = "400",
                description = "Validation error, duplicated name, role outside the allowlist, or unknown client role"),
        @ApiResponse(responseCode = "404", description = "Company or position not found")
    })
    public ResponseEntity<List<PositionRoleResponse>> replaceRoles(
            @PathVariable UUID companyId,
            @PathVariable UUID positionId,
            @Valid @RequestBody PositionRolesRequest request) {
        return ResponseEntity.ok(positionRoleService.replaceRoles(companyId, positionId, request));
    }
}
