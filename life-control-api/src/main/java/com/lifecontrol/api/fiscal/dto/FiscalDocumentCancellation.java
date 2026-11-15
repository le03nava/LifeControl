package com.lifecontrol.api.fiscal.dto;

import java.util.UUID;

/**
 * What a cancellation answers: the row it wrote, the document it cancelled, the motive, the state and the
 * authority's own code ({@code S7} of {@code odd/tasks/cfdi-fiscal-document.md}, decisions {@code L20}(c) and
 * {@code L20}(d) of the same record).
 *
 * <p><b>Why one record answers two outcomes.</b> The authority has three answers and this tree writes a row for
 * exactly one of them ({@code L20}(d)): a {@code Cancelled} writes the receipt of {@code S7}, while an
 * {@code InProcess} — a request the receiver has not answered yet — writes <b>nothing</b> and is answered
 * {@code 202 Accepted} with the authority's code. The two answers therefore differ in the one field that matters:
 * {@link #cancellationDocumentId()} is the row when there is one and {@code null} when nothing was written, and
 * {@link #wrote()} states which of the two the caller is holding. A second record would force either a
 * {@code null} in the same place or two endpoints for one command.</p>
 *
 * <p><b>Why the authority's {@code statusUUID} travels.</b> It is the SAT's verdict — {@code 201} cancelled,
 * {@code 202} already cancelled, {@code 211} in process — and it is what an operator quotes when a customer asks
 * what happened to an invoice. It is carried as the string the authority states and never translated, so a code this
 * tree has not seen arrives as itself ({@code L20}(c)).</p>
 *
 * <p><b>Why the motive and the state are here and not only in the database.</b> A receipt that named no motive would
 * be a receipt a reader has to join to answer the question it exists for; and {@link #status()} is the row's state as
 * the schema wrote it, which for this operation is {@code CANCELLED} and is the one word an operator looks for.</p>
 *
 * @param cancellationDocumentId the new row that names the cancelled document, or {@code null} when the authority
 *     reported the request in process and nothing was written
 * @param cancelledDocumentId the document this cancellation is about — the one the caller named
 * @param motive the authority's motive code, {@code 01} to {@code 04}
 * @param status the new row's state, {@code CANCELLED}, or {@code null} when there is no row
 * @param statusUuid the authority's own code for what it did, or for what it has not decided yet
 * @param message the authority's sentence for that code, untranslated
 */
public record FiscalDocumentCancellation(
        UUID cancellationDocumentId,
        UUID cancelledDocumentId,
        String motive,
        String status,
        String statusUuid,
        String message) {

    /**
     * Refuses the one combination that would be a receipt for a row nobody wrote, or a row with no receipt.
     *
     * <p>The two halves are one fact: a written cancellation has a row and a state, and a submission in process has
     * neither. A record that admitted half of either would let a caller read "cancelled" off an answer that only
     * means "asked".</p>
     */
    public FiscalDocumentCancellation {
        if ((cancellationDocumentId == null) != (status == null)) {
            throw new IllegalArgumentException("A cancellation answer either states the row it wrote and its state ("
                    + "CANCELLED) or neither of them, because nothing was written: got row "
                    + cancellationDocumentId + " with state " + status + ".");
        }
    }

    /**
     * The answer for a cancellation the authority granted and this tree recorded.
     *
     * @param cancellationDocumentId the row that names the cancelled document
     * @param cancelledDocumentId the document it cancels
     * @param motive the motive the row carries
     * @param status the row's state, {@code CANCELLED}
     * @param statusUuid the authority's code, {@code 201} or {@code 202}
     * @param message the authority's sentence for it
     * @return the receipt
     */
    public static FiscalDocumentCancellation written(
            UUID cancellationDocumentId,
            UUID cancelledDocumentId,
            String motive,
            String status,
            String statusUuid,
            String message) {
        return new FiscalDocumentCancellation(
                cancellationDocumentId, cancelledDocumentId, motive, status, statusUuid, message);
    }

    /**
     * The answer for a request the authority received and has not decided.
     *
     * <p>{@code cancellationDocumentId} and {@code status} are {@code null} because nothing was written: writing
     * {@code CANCELLED} on a pending request would be the lie {@code L20}(d) refuses, and the schema has no state
     * for it.</p>
     *
     * @param cancelledDocumentId the document the pending request is about
     * @param motive the motive the request stated
     * @param statusUuid the authority's code, {@code 211}
     * @param message the authority's sentence for it
     * @return the answer, with no row
     */
    public static FiscalDocumentCancellation submitted(
            UUID cancelledDocumentId, String motive, String statusUuid, String message) {
        return new FiscalDocumentCancellation(null, cancelledDocumentId, motive, null, statusUuid, message);
    }

    /** Whether this answer is the receipt of a written row, as opposed to a submission still in process. */
    public boolean wrote() {
        return cancellationDocumentId != null;
    }
}
