package com.lifecontrol.api.fiscal.exception;

import com.lifecontrol.api.exception.ConflictException;
import java.util.UUID;

/**
 * The sale already has a live stamped document under a <b>different</b> attempt key, so this attempt is refused
 * before the provider is called ({@code S6} and {@code S11} of {@code odd/tasks/cfdi-fiscal-document.md}).
 *
 * <p><b>Why this is a service guard when the schema already refuses the second stamp.</b> The schema's refusal is
 * right and too late: {@code uq_fiscal_document_one_stamped} ({@code V108}) refuses the second {@code STAMPED}
 * row, but the provider has already been called by then — the authority stamped a document this tree is about
 * to lose, and a stamp is the one thing that cannot be undone or recomputed. Checking for the existing stamp
 * before the network call is what turns "the transaction rolled back" into "nothing was sent".</p>
 *
 * <p><b>Why not the same answer as a replay.</b> A replay is the <b>same</b> attempt asking again, and the
 * document it names is returned untouched. This is a second, separately keyed attempt at a sale that is already
 * invoiced — a cancelled-and-reissued document is a different operation, written by {@code T8} with a
 * cancellation row and not as a second stamp of the same sale.</p>
 */
public class FiscalSaleAlreadyStampedException extends ConflictException {

    public FiscalSaleAlreadyStampedException(UUID salesOrderId, UUID stampedDocumentId, UUID uuidFiscal) {
        super("Sales order " + salesOrderId + " already has a live stamped fiscal document (" + stampedDocumentId
                + ", UUID " + uuidFiscal + "). One sale has at most one stamped document (S6), and this attempt "
                + "was refused before the authority was called, so nothing was stamped twice: a cancellation and "
                + "its replacement are a different operation, not a second attempt at the same sale.");
    }
}
