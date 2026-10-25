package com.lifecontrol.api.hr.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Write payload for a new per-level scope assignment.
 *
 * <p>It carries exactly what the record decided: the node the person is assigned and the first day
 * covered. The level is the <b>shape</b> of the payload — exactly one of the four ids is present —
 * which the service validates and the database enforces with
 * {@code ck_employee_scope_assignments_one_node}. There is deliberately no {@code level} field and no
 * company id: a level field would be a second copy of the fact the four columns already carry, and
 * the company is the employee's own ({@code employees.company_id}), so it is taken from the employee
 * and never from the request. There is deliberately no company node either: the company level is the
 * employee's fact, exactly one, and is not assignable.</p>
 *
 * <p>{@code validFrom} is the first day the assignment covers and it is allowed to be in the future: a
 * future row is legal and simply derives nothing until it covers today. There is no {@code enabled}
 * field — a new assignment is always enabled, and the only mutation this history allows is being
 * closed once.</p>
 */
public record ScopeAssignmentRequest(
        UUID companyCountryId,
        UUID companyRegionId,
        UUID companyZoneId,
        UUID companyStoreId,

        @NotNull(message = "validFrom is required") LocalDate validFrom) {}
