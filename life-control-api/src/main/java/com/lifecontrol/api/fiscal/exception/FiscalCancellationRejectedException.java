package com.lifecontrol.api.fiscal.exception;

import com.lifecontrol.api.exception.ConflictException;
import com.lifecontrol.api.fiscal.cancellation.FiscalCancellationResult;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The authority refused the cancellation, and nothing is written ({@code S7} of
 * {@code odd/tasks/cfdi-fiscal-document.md}, decisions {@code L20}(c)–(d) of the same record).
 *
 * <p><b>Why this mirrors {@link FiscalStampRejectedException} instead of inventing a status.</b> A refusal is a
 * refusal: the request is well formed, the authority's own rules are what it names, and the server's state is the
 * thing in conflict — so it is the same <b>409</b>, raised by the same category, with the same shape. What differs
 * is only what was left behind: a refused stamp leaves the {@code DRAFT} it had already written, and a refused
 * cancellation leaves <b>nothing</b>, because the row is written after the answer and never before.</p>
 *
 * <p><b>Why the issues are the payload and not only part of the sentence.</b> The catalogue is the operator's only
 * way to fix the request — {@code 205} is "the SAT has not processed it, wait", {@code 312} is a wrong substituting
 * folio — and an operator acts on one code at a time. Nothing is translated and nothing is renumbered: the codes are
 * the authority's vocabulary, and inventing this tree's own would be a second catalogue to keep in step
 * ({@code L12}(g)).</p>
 *
 * <p><b>Why it is raised after the provider answered and not before.</b> The refusals this tree raises before
 * calling anyone — an unknown motive, a folio that resolves to nothing, a document that is not stamped — are the
 * service's own, and they are refused with the fields they name. Mixing them would make "the PAC said no" and "we
 * could not ask" the same failure, and only the first is fixed by reading the authority's catalogue.</p>
 */
public class FiscalCancellationRejectedException extends ConflictException {

    /**
     * Marked {@code transient} because the entries are not serializable and this refusal is read, never
     * deserialized: a stack trace that travels keeps its sentence, which is the half a log needs.
     */
    private final transient List<FiscalCancellationResult.Issue> issues;

    /**
     * @param fiscalDocumentId the document the cancellation was about
     * @param issues the authority's codes and sentences, exactly as it answered them; at least one
     */
    public FiscalCancellationRejectedException(UUID fiscalDocumentId, List<FiscalCancellationResult.Issue> issues) {
        super("The authority rejected the cancellation of fiscal document " + fiscalDocumentId + " with "
                + issues.size() + " issue(s), and nothing was written: "
                + issues.stream()
                        .map(issue -> issue.code() + " " + issue.message())
                        .collect(Collectors.joining("; "))
                + ". The codes are the authority's catalogue, which is what the request is fixed from, and the "
                + "document is not cancelled until it answers that it is.");
        this.issues = List.copyOf(issues);
    }

    /** The authority's codes and sentences, exactly as it answered them. */
    public List<FiscalCancellationResult.Issue> issues() {
        return issues;
    }
}
