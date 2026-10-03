package com.lifecontrol.api.hr.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Write payload for an employee, shared by create and update.
 *
 * <p>{@code email} is optional: when it is blank the service generates one from the names and the
 * company's configured domain, and when it is present its domain must match the company's. There is
 * deliberately <b>no</b> {@code keycloakUserId} field (decision T17: the column is written only by
 * the access-provisioning flow) and no {@code enabled} field (the dedicated enable route sets it).</p>
 *
 * <p>{@code birthDate} and {@code hireDate} are required; {@code terminationDate}, {@code addressId}
 * and {@code statusId} are optional. A missing status defaults to the pinned {@code Active} row of
 * the {@code EMPLOYEE_STATUS} family (decision T5).</p>
 */
public record EmployeeRequest(
        @NotBlank(message = "employeeNumber is required") @Size(max = 30)
        String employeeNumber,

        @NotBlank(message = "firstName is required") @Size(max = 100)
        String firstName,

        @NotBlank(message = "paternalLastName is required") @Size(max = 100)
        String paternalLastName,

        @Size(max = 100) String maternalLastName,

        @Email(message = "email must have a valid format") @Size(max = 255)
        String email,

        @Size(max = 50) String phoneNumber,

        @NotNull(message = "birthDate is required") LocalDate birthDate,

        @NotNull(message = "hireDate is required") LocalDate hireDate,

        LocalDate terminationDate,

        UUID addressId,

        UUID statusId) {}
