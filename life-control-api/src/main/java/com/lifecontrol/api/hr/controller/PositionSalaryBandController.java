package com.lifecontrol.api.hr.controller;

import static com.lifecontrol.api.common.security.Roles.ADMIN;
import static com.lifecontrol.api.common.security.Roles.POSITION;

import com.lifecontrol.api.hr.dto.PositionSalaryBandResponse;
import com.lifecontrol.api.hr.dto.PositionSalaryBandsRequest;
import com.lifecontrol.api.hr.service.PositionSalaryBandService;
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
 * Company-scoped salary-band endpoints of a position. The path nests under
 * {@code /api/companies/{companyId}/positions/{positionId}} so both the company scope and the
 * position are part of the wire contract. Reads require only authentication; the full-set save
 * requires {@code lc-admin} or {@code lc-position}.
 *
 * <p>The {@code PUT} body is the position's <b>complete</b> set of bands: a stored band the request
 * omits is disabled, never deleted. There is no {@code DELETE} endpoint (decision D13, record T10).
 * The {@code GET} returns disabled rows too.</p>
 */
@RestController
@RequestMapping("/api/companies/{companyId}/positions/{positionId}/salary-bands")
@Tag(name = "Position Salary Bands", description = "API for managing a position's salary bands")
public class PositionSalaryBandController {

    private final PositionSalaryBandService salaryBandService;

    public PositionSalaryBandController(PositionSalaryBandService salaryBandService) {
        this.salaryBandService = salaryBandService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "List a position's salary bands",
            description =
                    "Returns every stored band of the position, disabled rows included, ordered by " + "seniority rank")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "List of salary bands")})
    public ResponseEntity<List<PositionSalaryBandResponse>> getSalaryBands(
            @PathVariable UUID companyId, @PathVariable UUID positionId) {
        return ResponseEntity.ok(salaryBandService.getSalaryBands(companyId, positionId));
    }

    @PutMapping
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + POSITION + "')")
    @Operation(
            summary = "Replace a position's salary bands",
            description = "Upserts the request's complete set of bands on (position, seniority level); a stored "
                    + "band the request omits is disabled, never deleted")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Salary bands replaced"),
        @ApiResponse(responseCode = "400", description = "Validation error, invalid range or duplicated level"),
        @ApiResponse(responseCode = "404", description = "Company, position or seniority level not found")
    })
    public ResponseEntity<List<PositionSalaryBandResponse>> replaceSalaryBands(
            @PathVariable UUID companyId,
            @PathVariable UUID positionId,
            @Valid @RequestBody PositionSalaryBandsRequest request) {
        return ResponseEntity.ok(salaryBandService.replaceSalaryBands(companyId, positionId, request));
    }
}
