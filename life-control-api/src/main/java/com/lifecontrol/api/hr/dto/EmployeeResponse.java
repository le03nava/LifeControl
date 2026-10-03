package com.lifecontrol.api.hr.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Read model of a company-scoped employee.
 *
 * <p>{@code keycloakUserId} is exposed <b>read-only</b> so the frontend can tell whether the email is
 * frozen (decision T9); it is never accepted on a write. There is deliberately no position or
 * department: both come from the employee's current contract (decision D4), and that table does not
 * exist yet.</p>
 */
public record EmployeeResponse(
        UUID id,
        UUID companyId,
        String employeeNumber,
        String firstName,
        String paternalLastName,
        String maternalLastName,
        String email,
        String phoneNumber,
        LocalDate birthDate,
        LocalDate hireDate,
        LocalDate terminationDate,
        UUID addressId,
        UUID statusId,
        String statusName,
        String keycloakUserId,
        Boolean enabled,
        Long version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {}
