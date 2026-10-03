package com.lifecontrol.api.hr.dto;

import com.lifecontrol.api.hr.model.ContractType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Read model of one row of an employee's contract history.
 *
 * <p>It carries what the employee detail needs to render the history without a second read: the
 * contract id, the employee id, the position <b>and its name</b>, the seniority level <b>and its
 * name</b>, the legal form, the salary and the validity range. Naming the two associations is the
 * departure from the id-only catalog convention ({@code PositionSalaryBandResponse}) that the
 * history view forces: a contract row is shown as text, and resolving the position and level names
 * from their own endpoints would cost one call per row.</p>
 *
 * <p>{@code endDate} is {@code null} for an open-ended contract, which is <b>not</b> the same fact
 * as "current" (decision T11). {@code enabled} is exposed because the read returns disabled rows too
 * (decision T12's partial predicate): the history is the whole record, and the client can tell a
 * soft-deleted contract from a live one.</p>
 */
public record ContractResponse(
        UUID id,
        UUID employeeId,
        UUID positionId,
        String positionName,
        UUID seniorityLevelId,
        String seniorityLevelName,
        ContractType contractType,
        BigDecimal monthlySalary,
        LocalDate startDate,
        LocalDate endDate,
        Boolean enabled) {}
