package com.lifecontrol.api.fiscal.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * What a sale's fiscal document reads as: the identity the sale produced and, when there is one, the
 * cancellation that ended it ({@code S1} of {@code odd/tasks/fiscal-document-read.md}).
 *
 * <p><b>Why the sale is enough to address this.</b> {@code POST /api/fiscal-documents} answers a
 * {@code documentId} to the caller who issued it and to nobody else, {@code SalesOrderResponse} carries no fiscal
 * pointer and {@code FiscalDocumentDraft} is returned by no read — so a document could be issued and then lost,
 * while {@code GET /{id}/xml} and {@code POST /{id}/cancellation} waited for an id nobody could produce. The sale
 * is the one identifier every caller already holds, which is why it is the address ({@code T1} of the record).</p>
 *
 * <p><b>Why there is no {@code xml} and never will be.</b> {@code S4} keeps the stamped bytes the reprint's
 * business: {@code GET /{id}/xml} is the only carrier, and a read that carried them too would be a second carrier
 * of the one artefact the tree promises is byte-for-byte the authority's.</p>
 *
 * <p><b>Why the cancellation block states a motive and a date and not the authority's code.</b> The
 * cancellation row stores which document it cancels, the motive and when it was written
 * ({@code V108}, {@code V112}); the authority's numeric {@code statusUuid} and its message are arguments of
 * {@code FiscalDocumentCancellation.written(...)} that nothing persists ({@code L4}). A stored read can state that
 * it was cancelled, which motive and when; promising a code here would mean inventing one.</p>
 *
 * @param documentId the stamped document the sale produced
 * @param salesOrderId the sale it derives from, which is the address the caller used
 * @param status the document's state: always {@code STAMPED}, because a cancelled document keeps its stamped row
 *     and its cancellation arrives beside it ({@code S3})
 * @param uuidFiscal the authority's UUID, frozen on the stamped row
 * @param stampedAt when the authority stamped it, frozen on the stamped row
 * @param folio the optional local convention the document states, or {@code null} when it states none
 *     (ADR-0007 decision 2 makes it optional)
 * @param cancellation the cancellation that names this document, or {@code null} when it was never cancelled
 */
public record FiscalDocumentRead(
        UUID documentId,
        UUID salesOrderId,
        String status,
        UUID uuidFiscal,
        OffsetDateTime stampedAt,
        String folio,
        Cancellation cancellation) {

    /**
     * The cancellation that ended a document, as the row that names it states it.
     *
     * <p><b>Why {@code cancellationDocumentId} is the cancellation row's own id and not the cancelled document's.</b>
     * A caller already holds the document it asked about, so it is {@code documentId} above; what this block adds
     * is the row that was written afterwards — the one a reprint of the cancellation, or an auditor, names. Two
     * ids in this block would be the same id twice.</p>
     *
     * <p><b>Why the name differs from {@link FiscalDocumentCancellation#cancelledDocumentId()}.</b> That component,
     * on the cancellation's own answer, is the <b>cancelled</b> document — the one the caller named when it asked to
     * cancel — while this component is the <b>cancellation</b> row that names this document. They are the two ends
     * of one pointer, so each record states its own end by name instead of sharing a word that would read as the
     * same id twice.</p>
     *
     * @param cancellationDocumentId the cancellation row that names {@link #documentId}
     * @param motive the authority's motive the row carries, as the schema stores it ({@code 01}–{@code 04})
     * @param cancelledAt when the cancellation row was written
     */
    public record Cancellation(UUID cancellationDocumentId, String motive, OffsetDateTime cancelledAt) {}
}
