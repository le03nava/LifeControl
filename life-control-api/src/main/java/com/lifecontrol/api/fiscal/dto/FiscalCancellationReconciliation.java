package com.lifecontrol.api.fiscal.dto;

import java.util.UUID;

/**
 * What one reconciliation pass answered about a submitted cancellation: what the row says now, what the authority
 * said, and whether anything is left to wait for ({@code S5} and {@code S6} of
 * {@code odd/tasks/fiscal-cancellation-reconciliation.md}).
 *
 * <p><b>Why the answer is the row and not only the authority's sentence.</b> A pass has three honest outcomes and
 * one of them writes nothing: an acceptance moves the pending row to {@code CANCELLED}, a rejection settles it where
 * it stands, and a still-pending answer or a refused registration changes nothing at all. So the caller has to be
 * able to read <b>what the pass decided</b> — {@link #documentStatus()} and {@link #settled()} — beside what it was
 * told, without a second read that could describe a different moment.</p>
 *
 * <p><b>Why {@link #settled()} is a field and not a state.</b> {@code L2}(d) makes settled
 * {@code cancellation_settled_at IS NOT NULL}, because a rejection keeps the row at {@code CANCELLATION_PENDING} and
 * is finished by that timestamp. A caller that had to infer "finished" from the state would read a settled rejection
 * as an unfinished one, which is the difference between "ask again tomorrow" and "this is the answer".</p>
 *
 * <p><b>Why {@link #authorityCode()} travels even when nothing was written.</b> A refused registration writes
 * nothing and moves nothing, and the operator still needs the authority's code to act on — it is the only thing that
 * says why the request has to change. The code is the authority's own vocabulary, never translated.</p>
 *
 * @param cancelledDocumentId the stamped document whose cancellation was reconciled — the id the caller named
 * @param documentStatus the cancellation row's state <b>after</b> this pass: {@code CANCELLED} once the authority
 *     accepted it, and {@code CANCELLATION_PENDING} while it is waiting or was rejected
 * @param authorityCode the code this pass read, or the one the row keeps when the pass read nothing new; {@code null}
 *     only if the row itself states none, which {@code ck_fiscal_document_cancellation_answer} makes unreachable for
 *     a cancellation row
 * @param message the authority's sentence for that code, untranslated
 * @param settled whether this cancellation is finished: true for an acceptance and for a rejection, false while the
 *     authority has not answered
 */
public record FiscalCancellationReconciliation(
        UUID cancelledDocumentId, String documentStatus, String authorityCode, String message, boolean settled) {

    /**
     * Refuses an answer that names nothing, because both facts are what a caller acts on.
     *
     * <p>A reconciliation is always about a document ({@code cancelledDocumentId}) and always states where its
     * cancellation stands ({@code documentStatus}): an answer with either absent would be a receipt for nothing, and
     * a caller could not tell "no row" from "a row in a state this pass did not read".</p>
     */
    public FiscalCancellationReconciliation {
        if (cancelledDocumentId == null) {
            throw new IllegalArgumentException(
                    "A reconciliation answer names the stamped document whose cancellation it is about.");
        }
        if (documentStatus == null || documentStatus.isBlank()) {
            throw new IllegalArgumentException("A reconciliation answer states the cancellation row's state after the "
                    + "pass, and the one it was given is absent: the row is never in no state at all.");
        }
    }
}
