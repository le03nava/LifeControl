package com.lifecontrol.api.fiscal.exception;

import java.util.UUID;

/**
 * The sale would be issued with the deferred payment method, and only a sale paid when it happens can be
 * invoiced ({@code S13} of {@code odd/tasks/cfdi-fiscal-document.md}, decision {@code L15}(d) of the same
 * record).
 *
 * <p><b>Why the method is refused and not half-modelled.</b> {@code PPD} — paid in instalments — makes
 * {@code FechaPago} and {@code FormaPago} conditional and forces a payment complement, which this tree has no
 * place for: {@code sales_orders} carries no receivable schedule and no document is ever issued against one. A
 * document that stated {@code PPD} with a made-up payment date would be a document the authority accepts and
 * nobody can honour, so the refusal is the honest half of the feature.</p>
 *
 * <p><b>Why {@link IllegalArgumentException} and not a conflict.</b> {@code S13} names this as a
 * <b>validation</b>: the caller asked to invoice something this tree cannot invoice, which is a bad request and
 * not a state the server's own data is in — the family {@code FiscalAttemptKeyRequiredException} already uses.
 * It is also what makes the HTTP answer a <b>400 without touching {@code GlobalExceptionHandler}</b>:
 * {@code handleBadRequest} serves every {@link IllegalArgumentException} with the message-only envelope, so no
 * mapping has to be added for this type. The message is the operator's only instruction, which is why it names
 * both the method and the sale.</p>
 */
public class FiscalDeferredPaymentNotSupportedException extends IllegalArgumentException {

    /**
     * @param salesOrderId the sale whose document would state the method, never null: a refusal an operator
     *     cannot locate is a refusal that costs a second query
     * @param metodoPago the method the document would state, rendered as "(none)" when it is absent so the
     *     sentence never reads as if the value were the word "null"
     */
    public FiscalDeferredPaymentNotSupportedException(UUID salesOrderId, String metodoPago) {
        super("Sales order " + salesOrderId + " would be issued with the payment method "
                + (metodoPago == null ? "(none)" : "'" + metodoPago + "'")
                + ", and only a sale paid when it happens can be invoiced in this tree (S13): PPD — paid in "
                + "instalments — makes the payment date and the payment form conditional and needs a payment "
                + "complement, so a deferred document is refused by name instead of stated with a placeholder.");
    }
}
