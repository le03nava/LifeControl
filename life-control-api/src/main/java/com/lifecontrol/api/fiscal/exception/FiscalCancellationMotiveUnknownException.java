package com.lifecontrol.api.fiscal.exception;

import com.lifecontrol.api.exception.FieldViolationException;
import com.lifecontrol.api.fiscal.model.FiscalCancellationMotive;

/**
 * The cancellation request states a motive code that is not one of the authority's four, so it is refused as a 400
 * that names the field ({@code S7} of {@code odd/tasks/cfdi-fiscal-document.md}, decision {@code L20}(e) of the same
 * record).
 *
 * <p><b>Why the code is checked and not the enum.</b> The wire states a two-character code — {@code 01} to
 * {@code 04} — and {@link FiscalCancellationMotive#fromCode(String)} answers empty for anything else precisely so the
 * caller can say which field is wrong. A refusal that answered "unrecognised value" would leave a client guessing
 * between the motive, the folio and the document id.</p>
 *
 * <p><b>Why the sentence lists the vocabulary.</b> {@code c_MotivoCancelacion} is closed and this tree has read it
 * from the authority's own {@code cancelación.xsd} ({@code L20}(e), {@code V112}), so the four codes are a fact to
 * state rather than to make the caller look up: an operator who typed the code by hand is the caller most likely to
 * be here, and the list is the fix.</p>
 */
public class FiscalCancellationMotiveUnknownException extends FieldViolationException {

    /** The name of the request field the refusal is about: the request DTO states it as {@code motive}. */
    public static final String FIELD = "motive";

    /**
     * @param code the value the request stated, or {@code null} when it stated none; named so the sentence quotes
     *     what was sent
     */
    public FiscalCancellationMotiveUnknownException(String code) {
        super(
                FIELD,
                "Cancellation motive " + (code == null ? "(none)" : "\"" + code + "\"")
                        + " is not one of the authority's motives. c_MotivoCancelacion declares exactly "
                        + String.join(", ", FiscalCancellationMotive.codes())
                        + " and a request carries one of them (V112): 01 replaces the document and requires a "
                        + "substituting folio, 02 and 03 cancel it without a replacement, and 04 marks a nominal "
                        + "operation invoiced in a global document.");
    }
}
