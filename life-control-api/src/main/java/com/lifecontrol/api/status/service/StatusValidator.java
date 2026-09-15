package com.lifecontrol.api.status.service;

import com.lifecontrol.api.status.exception.StatusNotFoundException;
import com.lifecontrol.api.status.model.Status;
import com.lifecontrol.api.status.repository.StatusRepository;
import java.util.UUID;

/**
 * Shared status validation for order flows: the status must exist and belong to
 * the expected status type. Centralizes the check and its error message.
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
