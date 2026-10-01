package com.lifecontrol.api.hr.exception;

import com.lifecontrol.api.exception.DuplicateResourceException;

/**
 * 409 category for a department whose {@code code} or {@code name} collides with an existing row of
 * the same company.
 *
 * <p>Both uniqueness rules are per company ({@code uq_departments_company_code},
 * {@code uq_departments_company_name}), so the message qualifies the collision with "for this
 * company" and the same value in another company is legal. The {@code field} argument names which of
 * the two natural keys collided: {@code code} or {@code name}.</p>
 */
public class DuplicateDepartmentException extends DuplicateResourceException {

    public DuplicateDepartmentException(String field, Object value) {
        super("Department with " + field + " '" + value + "' already exists for this company");
    }
}
