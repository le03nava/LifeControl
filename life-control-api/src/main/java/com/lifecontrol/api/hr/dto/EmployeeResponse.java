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
 *
 * <p>{@code accessState} is the one provisioning field this payload carries: the state of the
 * employee's <b>most recent</b> provisioning task, either {@code "PENDING"}, {@code "FAILED"} or
 * {@code null}, and never a role name. It lets a caller see that something is waiting without
 * reading the Access section, which needs {@code lc-employee-access}. It is enriched on the
 * employee <b>detail</b> path only, by {@code EmployeeService.getEmployeeById}: the company-scoped
 * list and the create/update/enable paths all build the response through the private
 * {@code toResponse(Employee)} overload, which emits {@code null}, because a per-row provisioning
 * read on the list would be the N+1 that the batch-user-fetch gap (G3) exists to avoid. The detail
 * route is gated by {@code isAuthenticated()}, so this field is exactly as wide as the payload it
 * travels in — it adds no role name and no PII the payload did not already carry.</p>
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
        LocalDateTime updatedAt,
        String accessState) {}
