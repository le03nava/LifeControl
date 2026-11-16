package com.lifecontrol.api.fiscal.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Asks for one sale's fiscal document: the sale, and the attempt that names the stamp.
 *
 * <p>Issue {@code #927} of {@code odd/tasks/cfdi-fiscal-document.md}: the port, the builder, the sealing pipeline
 * and the attempt have existed since {@code T6}/{@code T7} and nothing exposed them, so a stamped document could
 * not exist in a deployed system.</p>
 *
 * <p><b>Why the attempt key has no bean-validation constraint while the sale does.</b> The two absences are
 * different facts. A request that names no sale is malformed — there is nothing to stamp, and the binding layer is
 * where a missing required field is refused. An attempt key that is absent or blank is a <b>domain</b> refusal the
 * service already states with {@code FiscalAttemptKeyRequiredException} and a message that names the field, and
 * restating that rule here would be a second copy free to drift from the one {@code S11} depends on: a stamp
 * without an attempt key is the second stamp of the day, which is the one thing that cannot be undone
 * ({@code L12}(h)). One rule, one place, one message.</p>
 *
 * @param salesOrderId the sale to invoice; it must be completed, it must be this tenant's, and its receiver must
 *     carry a complete fiscal profile, each refused by name rather than by a constraint
 * @param attemptKey the caller's key for this attempt; a replay of a key that already stamped answers the same
 *     document and does not call the authority again
 */
public record FiscalDocumentIssueRequest(@NotNull UUID salesOrderId, String attemptKey) {}
