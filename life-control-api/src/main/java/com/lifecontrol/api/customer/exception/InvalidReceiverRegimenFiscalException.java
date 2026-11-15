package com.lifecontrol.api.customer.exception;

import com.lifecontrol.api.exception.FieldViolationException;
import com.lifecontrol.api.fiscal.model.RegimenFiscal;

/**
 * Thrown when a customer states a fiscal regime the SAT catalog — as this tree declares it — does not contain
 * (S2 of {@code odd/tasks/cfdi-fiscal-document.md}, {@code V107__customer_fiscal_profile.sql}).
 *
 * <p><b>Why a 400 that names the field, and not a constraint violation.</b> The column carries a named
 * {@code CHECK} over {@link RegimenFiscal#codes()}, so an unknown code is refused by the database either way —
 * with SQLSTATE {@code 23514} and a message that names the constraint, after the row was built, which is a 500 a
 * client cannot act on. This refusal happens <b>before</b> the write and carries
 * {@code errors: {regimenFiscal: <message>}} beside the same sentence in {@code message}, so a screen can mark the
 * regime control without matching this wording.</p>
 *
 * <p><b>Why the receiver has its own class instead of the issuer's.</b> The family is one subclass per rule, and
 * the sentence differs because the rule does: for the issuer the regime is required to issue, while for the
 * receiver it is one of the four facts that are stated together or not at all. The vocabulary is shared — the
 * same {@link RegimenFiscal} enum, moved to the {@code fiscal} package by L8(a) precisely because this column is
 * its second user.</p>
 */
public class InvalidReceiverRegimenFiscalException extends FieldViolationException {

    /**
     * @param code the code the request stated, echoed so an operator sees the value and not only the rule
     */
    public InvalidReceiverRegimenFiscalException(String code) {
        super(
                "regimenFiscal",
                "regimenFiscal '" + code + "' is not in the SAT regime catalog ("
                        + String.join(", ", RegimenFiscal.codes())
                        + "). It is one of the four facts the customer's fiscal profile states together, and a "
                        + "customer with no fiscal profile leaves all four absent instead.");
    }
}
