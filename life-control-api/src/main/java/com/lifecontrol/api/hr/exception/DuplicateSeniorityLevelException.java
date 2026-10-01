package com.lifecontrol.api.hr.exception;

import com.lifecontrol.api.exception.DuplicateResourceException;

/**
 * 409 category for a seniority level whose {@code code}, {@code name} or {@code rank} collides with
 * an existing row.
 *
 * <p>The {@code field} argument names which of the three natural keys collided, so the message
 * identifies the cause precisely: {@code code}, {@code name} or {@code rank}.</p>
 */
public class DuplicateSeniorityLevelException extends DuplicateResourceException {

    public DuplicateSeniorityLevelException(String field, Object value) {
        super("Seniority level with " + field + " '" + value + "' already exists");
    }
}
