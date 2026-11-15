package com.lifecontrol.api.fiscal.exception;

import com.lifecontrol.api.exception.ResourceNotFoundException;
import java.util.UUID;

/**
 * The document has no stamped artefact to reprint, so there is nothing to serve ({@code S10} of
 * {@code odd/tasks/cfdi-fiscal-document.md}, decision {@code L15}(c) of the same record).
 *
 * <p><b>Why every "no artefact" case is this one answer.</b> A {@code DRAFT}, a document whose stamp the
 * authority rejected, and a document of <b>another company</b> differ in the database and are indistinguishable
 * to the caller, deliberately. The tenant policy makes another company's row invisible, so "not found" is what
 * the query <i>answers</i>; a 403 would need a second read outside the policy to learn that the document exists,
 * which is exactly the leak {@code apply_tenant_isolation} exists to close. A draft and a rejected attempt have
 * no artefact because no bytes were witnessed, and a reprint of one is a reprint of nothing.</p>
 *
 * <p><b>Why a {@link ResourceNotFoundException} and not a message.</b> It is the tree's 404 category, so the
 * status falls out of the handler without a mapping of its own — the same reason
 * {@code FiscalSaleNotFoundException} extends it.</p>
 */
public class FiscalDocumentArtifactNotFoundException extends ResourceNotFoundException {

    /**
     * @param fiscalDocumentId the document the caller named, never null: the id is the only thing the caller
     *     stated, so it is the only thing the refusal can name
     */
    public FiscalDocumentArtifactNotFoundException(UUID fiscalDocumentId) {
        super("Fiscal document " + fiscalDocumentId + " has no stamped XML to reprint in this company: a draft, "
                + "an attempt the authority rejected, and a document of another company all answer the same way, "
                + "because a reprint returns the bytes that were stamped and nothing else (S10).");
    }
}
