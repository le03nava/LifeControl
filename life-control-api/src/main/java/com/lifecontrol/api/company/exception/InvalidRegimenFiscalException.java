package com.lifecontrol.api.company.exception;

import com.lifecontrol.api.exception.FieldViolationException;
import com.lifecontrol.api.fiscal.model.RegimenFiscal;

/**
 * Thrown when a company states a fiscal regime the SAT catalog — as this tree declares it — does not
 * contain (S1 of {@code odd/tasks/cfdi-fiscal-document.md}, {@code V102__company_fiscal_identity.sql}).
 *
 * <p><b>Why a 400 that names the field, and not a constraint violation.</b> The column carries a named
 * {@code CHECK} over {@link RegimenFiscal#codes()}, so an unknown code is refused by the database either
 * way — with SQLSTATE {@code 23514} and a message that names the constraint, after the row was built, which
 * is a 500 a client cannot act on. This refusal happens <b>before</b> the write and carries
 * {@code errors: {regimenFiscal: <message>}} next to the same sentence in {@code message}, so the company
 * screen can mark the regime control without matching this wording.</p>
 *
 * <p><b>Why it is not a "not found".</b> The catalog is a closed vocabulary compiled into this tree, not a
 * table with rows to look up: an unknown code is a value the request invented, so the answer is a bad
 * request and not a 404 on a resource nobody named. The field is one the caller can fix, which is exactly
 * the shape {@link FieldViolationException} exists for.</p>
 */
public class InvalidRegimenFiscalException extends FieldViolationException {

    /**
     * @param code the code the request stated, echoed so an operator sees the value and not only the rule
     */
    public InvalidRegimenFiscalException(String code) {
        super(
                "regimenFiscal",
                "regimenFiscal '" + code + "' is not in the SAT regime catalog ("
                        + String.join(", ", RegimenFiscal.codes())
                        + "). Companies.regimen_fiscal is required to issue a fiscal document and optional to "
                        + "sell, so an issuer that has not declared its regime leaves the field null instead.");
    }
}
