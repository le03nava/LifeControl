package com.lifecontrol.api.fiscal.exception;

import com.lifecontrol.api.exception.ResourceNotFoundException;
import java.util.UUID;

/**
 * The document has no submitted cancellation to reconcile, because none was ever sent ({@code S5} of
 * {@code odd/tasks/fiscal-cancellation-reconciliation.md}).
 *
 * <p><b>Why it is a 404 and not an empty answer.</b> A reconciliation resolves a cancellation that the submission
 * already remembered (a {@code CANCELLATION_PENDING} row, or the {@code CANCELLED} one a pass settled). Answering
 * "nothing to do" to a document that was never cancelled would report a settled state nobody reached, and the caller
 * would go on believing a cancellation exists. The absence has to be visible.</p>
 *
 * <p><b>Why it names the document and not a company or a cancellation id.</b> The document is the only thing the
 * caller stated: the cancellation row is what this read looked for and did not find, and naming its own search would
 * report an implementation detail as the caller's misunderstanding.</p>
 *
 * <p><b>Why it extends {@link ResourceNotFoundException}.</b> It is the tree's 404 category, so the status falls out
 * of {@code GlobalExceptionHandler} without a mapping of its own — the same reason
 * {@link FiscalDocumentNotStampedException} and {@code FiscalDocumentArtifactNotFoundException} extend it.</p>
 */
public class FiscalCancellationNotFoundException extends ResourceNotFoundException {

    /**
     * @param fiscalDocumentId the stamped document the caller named, never null: an id was stated and nothing
     *     cancels it
     */
    public FiscalCancellationNotFoundException(UUID fiscalDocumentId) {
        super("No cancellation was submitted for fiscal document " + fiscalDocumentId + ", so there is nothing to "
                + "reconcile: a cancellation is remembered when the authority receives the request "
                + "(CANCELLATION_PENDING), and this document has no such row in this company.");
    }
}
