package com.lifecontrol.api.fiscal.exception;

import com.lifecontrol.api.exception.ConflictException;
import java.util.Objects;
import java.util.UUID;

/**
 * A cancellation of this document already exists, so this one is refused before the authority is called
 * ({@code S7} of {@code odd/tasks/cfdi-fiscal-document.md}).
 *
 * <p><b>Why the schema is the rule and this is the read that states it.</b> {@code uq_fiscal_document_cancels}
 * ({@code V108}) makes {@code cancels_fiscal_document_id} unique, so a second cancellation row for one document is
 * refused by the database. The read is here for the reason {@code FiscalSaleAlreadyStampedException} gives about a
 * second stamp: the constraint answers <b>after</b> the authority has been asked, and a cancellation the SAT has
 * already registered is not something a rolled-back transaction takes back. Refusing first is what turns "the
 * transaction failed" into "nothing was sent".</p>
 *
 * <p><b>Why it names the state of the blocking row and not only its id.</b> Since {@code V113} there are two rows that
 * block a second cancellation, and they mean different things: a {@code CANCELLED} row says the authority cancelled
 * the document, and a {@code CANCELLATION_PENDING} one says the authority <b>has the request and has not decided</b>
 * ({@code S7}'s first clause: a pending cancellation cannot be cancelled again). A message that said "already
 * cancelled" for the second case would state something false about the document, and the row it names is what an
 * operator has to look at either way. The first paragraph of the message is derived from the state the row actually
 * holds, so the sentence and the row cannot disagree.</p>
 */
public class FiscalDocumentAlreadyCancelledException extends ConflictException {

    /**
     * @param fiscalDocumentId the document the caller asked to cancel
     * @param cancellationDocumentId the row that already carries its cancellation — the conflict the caller reads
     * @param cancellationStatus that row's state: {@code CANCELLED} when the authority granted it, or
     *     {@code CANCELLATION_PENDING} when it has the request and has not decided
     */
    public FiscalDocumentAlreadyCancelledException(
            UUID fiscalDocumentId, UUID cancellationDocumentId, String cancellationStatus) {
        super(message(fiscalDocumentId, cancellationDocumentId, cancellationStatus));
    }

    private static String message(UUID fiscalDocumentId, UUID cancellationDocumentId, String cancellationStatus) {
        Objects.requireNonNull(cancellationStatus, "the blocking row states its state");

        var blocking = "CANCELLED".equals(cancellationStatus)
                ? "Fiscal document " + fiscalDocumentId + " is already cancelled by document " + cancellationDocumentId
                        + ", which is CANCELLED."
                : "Fiscal document " + fiscalDocumentId + " has a cancellation already submitted: document "
                        + cancellationDocumentId + " is " + cancellationStatus + ", so the authority holds the request"
                        + " and has not decided it yet.";

        return blocking
                + " A document is cancelled once (uq_fiscal_document_cancels, V108) — a cancellation it already has is"
                + " not asked for a second time, whichever state it is in (S7) — so this request was refused before"
                + " the authority was called and nothing was sent a second time.";
    }
}
