package com.lifecontrol.api.hr.exception;

import com.lifecontrol.api.exception.ResourceNotFoundException;
import java.util.UUID;

/**
 * 404 category for a position that does not exist, does not belong to the requested company, or
 * does not belong to the requested department.
 *
 * <p>The service always looks a position up through a company- or department-scoped query, so a row
 * owned by another company resolves to this same not-found instead of a 200. The services that
 * surface the requirement use this same exception when a referenced department is not part of the
 * company in the path.</p>
 */
public class PositionNotFoundException extends ResourceNotFoundException {

    public PositionNotFoundException(UUID id) {
        super("Position not found with id: " + id);
    }
}
