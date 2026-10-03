package com.lifecontrol.api.hr.exception;

import com.lifecontrol.api.exception.DuplicateResourceException;

/**
 * 409 category for an employee whose {@code employeeNumber} or {@code email} collides with an
 * existing row of the same company.
 *
 * <p>Both uniqueness rules are per company ({@code uq_employees_company_number},
 * {@code uq_employees_company_email}), so the message qualifies the collision with "for this
 * company" and the same value in another company is legal. The {@code field} argument names which of
 * the two natural keys collided: {@code employeeNumber} or {@code email}.</p>
 */
public class DuplicateEmployeeException extends DuplicateResourceException {

    public DuplicateEmployeeException(String field, Object value) {
        super("Employee with " + field + " '" + value + "' already exists for this company");
    }
}
