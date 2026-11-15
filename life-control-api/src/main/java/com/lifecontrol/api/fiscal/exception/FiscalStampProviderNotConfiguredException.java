package com.lifecontrol.api.fiscal.exception;

import java.util.UUID;

/**
 * Nothing answers the stamping port, so this deployment cannot stamp ({@code L12}(a) of
 * {@code odd/tasks/cfdi-fiscal-document.md}).
 *
 * <p><b>Why the absence is a named refusal and not a boot failure.</b> The port has no implementation in this
 * slice by design: the provider adapter with its credentials, its sandbox and its partial-failure state is
 * {@code T7}, and a context that refused to start until a vendor was configured would make the whole API
 * undeployable while the document, the builder and the attempt are finished. The stamper therefore asks for the
 * provider when it needs one and refuses <b>this attempt</b> by name, which is the answer an operator can act on
 * (configure a provider) instead of a stack trace at startup that says nothing about what is missing.</p>
 *
 * <p><b>Why it is not a {@code ConflictException}.</b> Nothing about the request or the document conflicts with
 * anything: the server is not configured to stamp. An {@code IllegalStateException} is the tree's shape for
 * "this instance cannot answer that" ({@code FiscalDocumentSnapshotWriter} uses the same family for a catalog
 * row the schema guarantees and the read cannot find).</p>
 */
public class FiscalStampProviderNotConfiguredException extends IllegalStateException {

    public FiscalStampProviderNotConfiguredException(UUID documentId) {
        super("Fiscal document " + documentId + " cannot be stamped: no FiscalStampProvider is registered, so "
                + "there is no authority to call. The provider adapter and its configuration are T7's; until one "
                + "is present every attempt is refused here, after the draft exists and before anything is sent.");
    }
}
