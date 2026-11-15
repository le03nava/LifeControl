package com.lifecontrol.api.fiscal.exception;

import com.lifecontrol.api.exception.ConflictException;
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
 * <p><b>Why it names both rows.</b> The caller asked about one document and the conflict is another: the existing
 * cancellation is a row an operator can go and read, and naming it is the difference between "conflict" and "this
 * document was cancelled on the date that row states".</p>
 */
public class FiscalDocumentAlreadyCancelledException extends ConflictException {

    /**
     * @param fiscalDocumentId the document the caller asked to cancel
     * @param cancellationDocumentId the row that already cancels it, which is the conflict the caller reads
     */
    public FiscalDocumentAlreadyCancelledException(UUID fiscalDocumentId, UUID cancellationDocumentId) {
        super("Fiscal document " + fiscalDocumentId + " is already cancelled by document " + cancellationDocumentId
                + ". A document is cancelled once (uq_fiscal_document_cancels, V108), so this request was refused "
                + "before the authority was called and nothing was sent a second time.");
    }
}
