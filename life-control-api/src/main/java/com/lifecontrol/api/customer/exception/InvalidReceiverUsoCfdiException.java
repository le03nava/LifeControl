package com.lifecontrol.api.customer.exception;

import com.lifecontrol.api.exception.FieldViolationException;
import com.lifecontrol.api.fiscal.model.UsoCfdi;

/**
 * Thrown when a customer states a default use of the voucher the SAT catalog — as this tree declares it — does not
 * contain (S2 of {@code odd/tasks/cfdi-fiscal-document.md}, {@code V107__customer_fiscal_profile.sql}).
 *
 * <p><b>Why the same shape as the regime's refusal.</b> The two codes come from the same authority, are validated
 * the same way and owe the client the same answer: a 400 with {@code errors: {defaultUsoCfdi: <message>}} beside
 * the sentence, before the row is built, instead of a {@code ck_customers_default_uso_cfdi} violation after it.</p>
 *
 * <p><b>Why a bad use is refused rather than dropped.</b> {@code default_uso_cfdi} is part of the required set
 * (L8(b)): a default the stamp cannot use is worse than no default, so a customer that states one the catalog does
 * not carry is refused and the client either corrects it or leaves the whole profile absent.</p>
 */
public class InvalidReceiverUsoCfdiException extends FieldViolationException {

    /**
     * @param code the code the request stated, echoed so an operator sees the value and not only the rule
     */
    public InvalidReceiverUsoCfdiException(String code) {
        super(
                "defaultUsoCfdi",
                "defaultUsoCfdi '" + code + "' is not in the SAT use-of-voucher catalog ("
                        + String.join(", ", UsoCfdi.codes())
                        + "). It is the default use a stamped document proposes, and it is one of the four facts "
                        + "the customer's fiscal profile states together.");
    }
}
