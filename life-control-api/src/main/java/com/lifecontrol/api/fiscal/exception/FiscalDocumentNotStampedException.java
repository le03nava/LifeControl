package com.lifecontrol.api.fiscal.exception;

import com.lifecontrol.api.exception.ResourceNotFoundException;
import java.util.UUID;

/**
 * The document a caller asked to cancel is not a stamped document of this company, so there is nothing to cancel
 * ({@code S7} of {@code odd/tasks/cfdi-fiscal-document.md}, decision {@code L20}(d) of the same record).
 *
 * <p><b>Why four different states are one answer.</b> A document that does not exist, one that belongs to
 * <b>another company</b>, one that is still a {@code DRAFT}, and a stamped row that kept no artefact are
 * indistinguishable to the caller, deliberately. The tenant policy makes another company's row invisible, so "not
 * found" is what the query <i>answers</i>; producing a 403 would need a second read outside the policy to learn that
 * the document exists, which is the leak {@code apply_tenant_isolation} exists to close ({@code L15}(c), the same
 * answer the reprint gives). A draft was never witnessed by the authority, so there is no document to ask it to
 * cancel; and a stamped row with no artefact is a defect — the cancellation is the receipt of a document whose
 * stamped bytes this tree should hold, and a receipt for bytes nothing kept is not one this tree can write.</p>
 *
 * <p><b>Why it is a {@link ResourceNotFoundException} and not a message.</b> It is the tree's 404 category, so the
 * status falls out of {@code GlobalExceptionHandler} without a mapping of its own — the same reason
 * {@link FiscalSaleNotFoundException} and {@code FiscalDocumentArtifactNotFoundException} extend it.</p>
 */
public class FiscalDocumentNotStampedException extends ResourceNotFoundException {

    /**
     * @param fiscalDocumentId the document the caller named, never null: the id is the only thing the caller stated,
     *     so it is the only thing the refusal can name
     */
    public FiscalDocumentNotStampedException(UUID fiscalDocumentId) {
        super("Fiscal document " + fiscalDocumentId + " is not a stamped document of this company, so there is "
                + "nothing to cancel: a draft, an absent document, another company's document and one whose stamped "
                + "artefact is missing all answer the same way, because a cancellation is the receipt of a document "
                + "the authority witnessed (S7).");
    }
}
