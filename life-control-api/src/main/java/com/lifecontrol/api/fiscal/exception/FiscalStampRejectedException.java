package com.lifecontrol.api.fiscal.exception;

import com.lifecontrol.api.exception.ConflictException;
import com.lifecontrol.api.fiscal.FiscalStampResult;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The stamping authority refused the document, and the document stays a {@code DRAFT} with no artefact
 * ({@code S11} of {@code odd/tasks/cfdi-fiscal-document.md}, decision {@code L12}(g) of the same record).
 *
 * <p><b>Why the issues are the payload and not only part of the sentence.</b> A PAC's catalogue is the
 * operator's only way to fix the request, and an operator fixes a request one code at a time: the list is what
 * a screen or a log can read row by row, and a message built from it is what a human reads first. Nothing is
 * translated and nothing is renumbered — the provider's codes are the provider's vocabulary, and inventing this
 * tree's own would be a second catalogue to keep in step.</p>
 *
 * <p><b>Why a refusal is raised after the provider answered and not before.</b> A rejection is the authority's
 * verdict on a real, built document; the refusals this tree raises before calling anyone (no issuer profile, no
 * fiscal key, no tax mapping) are {@code FiscalDocumentSnapshotWriter}'s. Mixing them would make "the PAC said
 * no" and "we could not build it" the same failure, and only one of them is fixed by editing the company.</p>
 *
 * <p><b>Why it is a 409.</b> The document conflicts with the authority's rules — the request is well formed and
 * the state of the server's own data is what it names, exactly like the sibling refusals of this module. The
 * HTTP surface that serves it arrives with {@code T7}'s endpoint, not here.</p>
 */
public class FiscalStampRejectedException extends ConflictException {

    /**
     * Marked {@code transient} because the entries are not serializable and this refusal is read, never
     * deserialized: a stack trace that travels keeps its sentence, which is the half a log needs.
     */
    private final transient List<FiscalStampResult.Issue> issues;

    public FiscalStampRejectedException(UUID documentId, UUID salesOrderId, List<FiscalStampResult.Issue> issues) {
        super("The stamping authority rejected fiscal document " + documentId + " of sale " + salesOrderId + " with "
                + issues.size() + " issue(s), and the document stays a DRAFT with no artefact: "
                + issues.stream()
                        .map(issue -> issue.code() + " " + issue.message())
                        .collect(Collectors.joining("; "))
                + ". The codes are the provider's catalogue, which is what the request is fixed from (S11).");
        this.issues = List.copyOf(issues);
    }

    /** The provider's codes and sentences, exactly as it answered them. */
    public List<FiscalStampResult.Issue> issues() {
        return issues;
    }
}
