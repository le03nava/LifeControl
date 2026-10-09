package com.lifecontrol.api.provisioning.dto;

/**
 * The body of {@code POST …/requests/{taskId}/reject}: the reason an operator refuses a gated access
 * request.
 *
 * <p>It is a one-component record so the wire payload is exactly {@code {"reason": "..."}} and the
 * persisted value is the same string that arrives, mirroring the sibling records' style.</p>
 *
 * <p>It deliberately carries <b>no</b> bean-validation annotation. The blank check lives in
 * {@link com.lifecontrol.api.provisioning.service.AccessProvisioningTaskService#reject(java.util.UUID,
 * java.util.UUID, String, String)}, so the refusal does not depend on a second mechanism that could
 * drift from the service's rule — in particular, a rejection with no body at all must be refused by
 * that same rule rather than by message conversion.</p>
 *
 * @param reason the rejection reason, persisted in {@code last_error}; {@code null} or blank is
 *     refused by the service with {@code IllegalArgumentException} (400)
 */
public record AccessProvisioningRejectionRequest(String reason) {}
