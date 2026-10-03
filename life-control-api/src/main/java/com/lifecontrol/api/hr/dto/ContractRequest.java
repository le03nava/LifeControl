package com.lifecontrol.api.hr.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Write payload for a new employment contract.
 *
 * <p>{@code contractType} is a plain {@code String} parsed by the service with
 * {@code ContractType.valueOf(...)}, following the {@code MeasureUnitService} precedent, so an
 * unknown value is an {@code IllegalArgumentException} and therefore a 400 (decision D13). The
 * values are {@code PERMANENT}, {@code FIXED_TERM}, {@code TEMPORARY}, {@code INTERNSHIP} and
 * {@code CONTRACTOR}.</p>
 *
 * <p>{@code endDate} is optional and is the <b>last day covered</b> (inclusive), the way
 * {@code employees.termination_date} reads: an absent value is an open-ended contract, not "the
 * current one" (decision T11). The column stores the exclusive bound — the first day NOT covered
 * (decision D14) — and the service converts between the two at this DTO boundary, so the value the
 * client sends is the value it reads back. {@code startDate} is required. The request carries no
 * {@code enabled} field: a new contract is always enabled, and the only mutation this history allows
 * is being closed once (decision T2).</p>
 *
 * <p>{@code monthlySalary} is required and validated non-negative with the repository's established
 * decimal convention ({@code @DecimalMin("0.00")}), mirroring the column's
 * {@code ck_employee_contracts_salary >= 0} CHECK so the operator gets a 400 instead of the
 * database's 409. A salary change is a new contract, never an edit: there is deliberately no update
 * payload.</p>
 */
public record ContractRequest(
        @NotNull(message = "positionId is required") UUID positionId,

        @NotNull(message = "seniorityLevelId is required") UUID seniorityLevelId,

        @NotBlank(message = "contractType is required") String contractType,

        @NotNull(message = "monthlySalary is required")
        @DecimalMin(value = "0.00", message = "monthlySalary cannot be negative")
        BigDecimal monthlySalary,

        @NotNull(message = "startDate is required") LocalDate startDate,

        // The last day covered (inclusive). The column stores the first day NOT covered, one day
        // later (decision D14): see ContractService.toStoredEndDate.
        LocalDate endDate) {}
