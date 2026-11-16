package com.lifecontrol.api.fiscal.cancellation;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What the authority answered a cancellation: cancelled, in process, or refused ({@code S7} and decisions
 * {@code L20}(c) and {@code L20}(d) of {@code odd/tasks/cfdi-fiscal-document.md}).
 *
 * <p><b>Why three shapes, when a stamp's answer has two.</b> A cancellation has three outcomes and the third is not
 * a shade of the other two. {@code 201} and {@code 202} are the authority stating the document <b>is</b> cancelled;
 * {@code 211} is a request it has received and not decided, and it is the value that must never be written as a
 * cancellation row — {@code ck_fiscal_document_status} has no state for it, and writing {@code CANCELLED} on a
 * pending request is the lie {@code V108} exists to prevent ({@code L20}(d)). A two-way answer would force exactly
 * that lie, because the caller would have to pick one of the two.</p>
 *
 * <p><b>Why the authority's code travels as a string.</b> {@code statusUUID} is the SAT's verdict and its catalogue
 * is the operator's only way to act ({@code L20}(c)); the same reasoning {@code L12}(g) gives the stamping port. This
 * tree therefore names the two codes it <b>acts</b> on and carries every other one untranslated, which is what lets
 * a code the SAT adds tomorrow arrive as a {@link Rejected} instead of as an unknown constant.</p>
 *
 * <p><b>What a {@link Cancelled} deliberately does not claim.</b> It states the authority cancelled the document; it
 * does not state that a row was written. The transaction that writes {@code CANCELLED}, the motive and the pointer to
 * the cancelled document belongs to the service ({@code T8d}), so this value can be inspected by a test without a
 * database.</p>
 */
public sealed interface FiscalCancellationResult
        permits FiscalCancellationResult.Cancelled,
                FiscalCancellationResult.InProcess,
                FiscalCancellationResult.Rejected {

    /**
     * The authority states the document is cancelled.
     *
     * @param uuid the UUID of the document this answer is about — the request's own, checked against the answer
     * @param statusUuid the authority's code, {@code 201} (<i>se ha cancelado con éxito</i>) or {@code 202}
     *     (<i>ya se había cancelado previamente</i>): the only two codes that mean cancelled
     * @param message the authority's sentence for that code, untranslated
     */
    record Cancelled(UUID uuid, String statusUuid, String message) implements FiscalCancellationResult {

        public Cancelled {
            Objects.requireNonNull(uuid, "a cancelled document names the UUID the authority cancelled");
            statusUuid = required(statusUuid, "a cancellation states the authority's statusUUID");
        }
    }

    /**
     * The authority received the request and has not decided: nothing is cancelled and nothing is refused.
     *
     * <p>Why it exists as its own branch instead of a {@code Rejected} with a code: a submission is not the
     * authority's refusal, and the HTTP answer for it is {@code 202 Accepted} — a different answer to a different
     * question. It also carries the document's UUID for the same reason {@link Cancelled} does: the caller reports
     * <b>this</b> document as pending, and the poll that will decide it ({@code L20}(i)) starts from the folio.</p>
     *
     * @param uuid the UUID of the document the pending request is about
     * @param statusUuid the authority's code, {@code 211} (<i>la cancelación está en proceso</i>)
     * @param message the authority's sentence for that code, untranslated
     */
    record InProcess(UUID uuid, String statusUuid, String message) implements FiscalCancellationResult {

        public InProcess {
            Objects.requireNonNull(uuid, "a pending cancellation names the document it is about");
            statusUuid = required(statusUuid, "a pending cancellation states the authority's statusUUID");
        }
    }

    /**
     * The authority refused, and nothing was cancelled.
     *
     * @param issues at least one provider code with its message — the operator's only way to fix the request
     */
    record Rejected(List<Issue> issues) implements FiscalCancellationResult {

        public Rejected {
            issues = List.copyOf(issues);
            if (issues.isEmpty()) {
                throw new IllegalArgumentException(
                        "A rejection names at least one issue: the authority's codes are the only way the request is "
                                + "fixed, and a refusal that names none is a provider defect.");
            }
        }
    }

    /**
     * One reason the authority refused a cancellation, in the authority's own vocabulary.
     *
     * @param code the code — a {@code statusUUID} such as {@code 205}, or a call-level code such as {@code 1802} —
     *     never translated: it is what an operator looks up in the vendor's catalogue
     * @param message the authority's sentence for that code
     */
    record Issue(String code, String message) {}

    private static String required(String value, String why) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(why + ", and it is absent: a verdict with no code is not one this "
                    + "tree can act on, and inventing a code would put it in the operator's catalogue.");
        }
        return value;
    }
}
