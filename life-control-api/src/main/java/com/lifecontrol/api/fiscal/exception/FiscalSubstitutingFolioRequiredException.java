package com.lifecontrol.api.fiscal.exception;

import com.lifecontrol.api.fiscal.model.FiscalCancellationMotive;
import java.util.UUID;

/**
 * A cancellation request states a motive that replaces the document and names nothing to replace it with, so the
 * authority would refuse the request ({@code S7} of {@code odd/tasks/cfdi-fiscal-document.md}, decision
 * {@code L20}(f)).
 *
 * <p><b>Why this is a refusal and not a silent default.</b> Motive {@code 01} means "the document was issued with
 * errors and <b>is related to</b> a substituting one", so the folio is the other half of the statement: without it
 * the request claims a replacement it does not identify, and the SAT rejects it with code {@code 312}. There is
 * nothing to default to — a folio invented here would name some other company's document or none at all.</p>
 *
 * <p><b>Why the message names the authority's spelling of the field.</b> An operator fixes a request, and the
 * request's field is {@code FolioSustitucion}; {@code substitutingFolio} is this tree's name for it and appears in
 * no document, no XSD and no vendor page. It also states the motive that triggered the rule, because the same
 * record accepts a folio for the other three motives and a reader needs to know which half of the one-way rule was
 * broken.</p>
 *
 * <p><b>Why it extends {@link IllegalArgumentException}.</b> Nothing about the <b>instance</b> is wrong — the caller
 * asked for something the format cannot state — so it is a request-level refusal, and {@code GlobalExceptionHandler}
 * already answers that family with a 400 without this class needing a handler of its own. The precedent is
 * {@code FiscalDeferredPaymentNotSupportedException} and {@code FiscalAttemptKeyRequiredException} in the same
 * module.</p>
 */
public class FiscalSubstitutingFolioRequiredException extends IllegalArgumentException {

    /**
     * @param uuid the document whose cancellation was asked for, named so a batch caller knows which request failed
     * @param motive the motive that requires a substituting folio
     */
    public FiscalSubstitutingFolioRequiredException(UUID uuid, FiscalCancellationMotive motive) {
        super("The cancellation of " + uuid + " states motivo " + motive.code()
                + " and no FolioSustitucion. Motivo 01 means the document was issued with errors and is related to "
                + "the document that replaces it, so the authority's cancelación.xsd documents FolioSustitucion as "
                + "required for it and refuses the request without it. State the substituting document's UUID, or "
                + "state a motive that does not replace anything (02, 03 or 04) — those accept a FolioSustitucion "
                + "but do not require one.");
    }
}
