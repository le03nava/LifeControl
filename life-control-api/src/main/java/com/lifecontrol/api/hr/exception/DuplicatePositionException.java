package com.lifecontrol.api.hr.exception;

import com.lifecontrol.api.exception.DuplicateResourceException;

/**
 * 409 category for a position whose {@code code} or {@code name} collides with an existing row of
 * the same department.
 *
 * <p>Both uniqueness rules are per department ({@code uq_positions_department_code},
 * {@code uq_positions_department_name}), so the message qualifies the collision with "for this
 * department" and the same value in another department is legal. The {@code field} argument names
 * which of the two natural keys collided: {@code code} or {@code name}.</p>
 */
public class DuplicatePositionException extends DuplicateResourceException {

    public DuplicatePositionException(String field, Object value) {
        super("Position with " + field + " '" + value + "' already exists for this department");
    }
}
