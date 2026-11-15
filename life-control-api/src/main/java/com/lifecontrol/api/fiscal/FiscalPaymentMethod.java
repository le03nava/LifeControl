package com.lifecontrol.api.fiscal;

import com.lifecontrol.api.fiscal.exception.FiscalDeferredPaymentNotSupportedException;
import java.util.Objects;
import java.util.UUID;

/**
 * The authority's payment method, and the one rule this tree states about it: only a sale paid when it happens
 * can be invoiced ({@code S13} of {@code odd/tasks/cfdi-fiscal-document.md}, decision {@code L15}(d) of the same
 * record).
 *
 * <p><b>Why the vocabulary is here and not on the entity.</b> {@code V109} puts the closed set in
 * {@code ck_fiscal_document_metodo_pago}, and the tree's convention is a constant beside the reader that
 * refuses a value — the shape {@code TaxCodeService.ZERO_RATE_CODES} has. A {@code String} and not an enum,
 * because {@code L9}(i) and {@code L12} keep the vocabularies in the schema until a second reader needs more
 * than one value: {@code FiscalDocument.metodoPago} is {@code insertable = false, updatable = false} and the
 * schema's {@code DEFAULT 'PUE'} writes it, so nothing in this slice chooses between the two.</p>
 *
 * <p><b>Why the predicate lives in its own class instead of inside the writer.</b> It is a domain rule with no
 * dependency — it reads no catalog, no sale and no schema — so it is the one half of {@code S13} a test can pin
 * without inventing a row the production path cannot write: the method a document states reaches it from the
 * schema's default, and no sale of this tree can state the deferred one ({@code V87} dropped
 * {@code sales_orders.payment_method_id} and the charge refuses anything but an exact payment). See
 * {@code FiscalPaymentMethodTest} for what that means for the evidence.</p>
 *
 * <p><b>Why {@code L15}(d) puts it in the writer and not in the stamper or the endpoint.</b> A refusal in the
 * stamper would run after the draft existed, leaving a row in a table that refuses {@code DELETE}; a refusal in
 * the endpoint would leave the rule to whoever remembers to call it. The writer is the one place a sale becomes
 * a document, so the rule is a call before its first write and cannot be skipped by any caller of the stamp.</p>
 */
public final class FiscalPaymentMethod {

    /**
     * {@code c_MetodoPago} {@code PUE}: paid in a single exhibition, which is what a sale charged at the till
     * is — and the only method this tree can issue, since the charge refuses anything but an exact payment.
     */
    public static final String PUE = "PUE";

    /**
     * {@code c_MetodoPago} {@code PPD}: paid in instalments. Admitted by the schema's vocabulary
     * ({@code ck_fiscal_document_metodo_pago}) and refused by {@link #requirePaidWhenItHappens}, which is the
     * split {@code V109} states: the column is the schema's, the refusal is the domain's.
     */
    public static final String PPD = "PPD";

    private FiscalPaymentMethod() {}

    /**
     * Accepts the method a document would be issued with, or refuses the sale by name.
     *
     * <p>Everything that is not exactly {@link #PUE} is refused, and not only {@link #PPD}: a {@code null} or an
     * unrecognised code is a caller stating a method the schema does not admit, and reading it as the permissive
     * value would be a guess on the one document that can never be recomputed.</p>
     *
     * @param salesOrderId the sale the document would derive from, named in the refusal
     * @param metodoPago the method the document would state
     * @throws FiscalDeferredPaymentNotSupportedException when the method is not {@code PUE}
     */
    public static void requirePaidWhenItHappens(UUID salesOrderId, String metodoPago) {
        Objects.requireNonNull(salesOrderId, "a refusal about a payment method names the sale it is about");
        if (!PUE.equals(metodoPago)) {
            throw new FiscalDeferredPaymentNotSupportedException(salesOrderId, metodoPago);
        }
    }
}
