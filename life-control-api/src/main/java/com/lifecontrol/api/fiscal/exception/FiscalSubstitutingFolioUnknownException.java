package com.lifecontrol.api.fiscal.exception;

import com.lifecontrol.api.exception.FieldViolationException;
import java.util.UUID;

/**
 * The substituting folio a cancellation states does not resolve to a stamped document of this company, so the
 * request is refused before anything is written or sent ({@code S7} of {@code odd/tasks/cfdi-fiscal-document.md},
 * decision {@code L20}(f) of the same record).
 *
 * <p><b>Why it is a 400 that names the field.</b> The value is the caller's and the format is right — it <i>is</i> a
 * fiscal UUID — so what is wrong is that no document of this company carries it: either the folio is another
 * company's, which the tenant read cannot see, or the document it names was never stamped, or it does not exist. None
 * of those is a server-side failure, and a client marks the control from the field map instead of parsing the
 * sentence.</p>
 *
 * <p><b>Why the refusal happens here and not at the insert.</b> {@code fiscal_document.replaces_fiscal_document_id}
 * is a composite foreign key to {@code fiscal_document (company_id, id)} ({@code V108}), so a folio that resolved to
 * nothing would be refused by PostgreSQL <b>after</b> the authority had been asked to cancel the document — and the
 * message would name a constraint and no fact. The read that resolves the folio comes before the call for exactly
 * that reason, and this is the refusal it produces.</p>
 *
 * <p><b>Why it is a {@link FieldViolationException}.</b> The abstract parent is the tree's shape for "a refusal that
 * names a field, served as a 400 with an {@code errors} entry", and {@code GlobalExceptionHandler} answers it as one;
 * a bare {@code IllegalArgumentException} would serve the sentence with no field for a client to mark.</p>
 */
public class FiscalSubstitutingFolioUnknownException extends FieldViolationException {

    /**
     * The name of the request field the refusal is about: the record states it as {@code substitutingFolio}, and a
     * field map key has to be the name a client's form knows.
     */
    public static final String FIELD = "substitutingFolio";

    /**
     * @param substitutingFolio the folio the request stated, as the caller spelled it, so the sentence names the value
     */
    public FiscalSubstitutingFolioUnknownException(UUID substitutingFolio) {
        super(
                FIELD,
                "Substituting folio " + substitutingFolio + " is not a stamped fiscal document of this company, "
                        + "so it cannot substitute the document being cancelled. FolioSustitucion names the document that "
                        + "replaces this one, and fiscal_document.replaces_fiscal_document_id stores a row of this company "
                        + "and not a folio string (V108): a folio of another company, one that was never stamped and one that "
                        + "does not exist are all refused here, before the authority is asked to cancel anything.");
    }
}
