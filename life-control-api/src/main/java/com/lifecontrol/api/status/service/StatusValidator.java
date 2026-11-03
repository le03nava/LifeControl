package com.lifecontrol.api.status.service;

import com.lifecontrol.api.status.exception.StatusNotFoundException;
import com.lifecontrol.api.status.model.Status;
import com.lifecontrol.api.status.repository.StatusRepository;
import java.util.UUID;

/**
 * Shared status validation for the two families ADR-0004 leaves out of its typed state machine.
 *
 * <p><b>Who still calls this, and who must not.</b> {@code scheduling_appointments} and {@code employees}
 * keep their {@code status_id} by the ADR's own scope decision (invariant S1): an appointment and an
 * employment status are not commerce documents, so they have no domain enum, no closed transition set and no
 * {@code status_transition} row — a request names a catalog row and that row is the state. The five commerce
 * families do the opposite: their state is an enum constant and the catalog is only its label, so a commerce
 * service resolves a request through {@code DocumentStateCatalog} (decision 7) and never through this class.
 * That is why this helper survived the drop instead of moving: it is exactly the two families' contract.</p>
 *
 * <p>The check itself is unchanged: the status must exist and belong to the expected status type, and both
 * refusals keep their messages, because the two callers' screens and suites read them.</p>
 */
public final class StatusValidator {

    private StatusValidator() {}

    public static Status requireStatusOfType(
            StatusRepository statusRepository, UUID statusId, String expectedTypeName) {
        var status = statusRepository.findById(statusId).orElseThrow(() -> new StatusNotFoundException(statusId));

        var statusType = status.getStatusType();
        if (!expectedTypeName.equalsIgnoreCase(statusType.getStatusTypeName())) {
            throw new IllegalArgumentException("The provided status does not belong to type " + expectedTypeName);
        }

        return status;
    }
}
