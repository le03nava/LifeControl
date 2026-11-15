package com.lifecontrol.api.fiscal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.fiscal.exception.FiscalDeferredPaymentNotSupportedException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one rule {@code S13} states about the payment method: only a sale paid when it happens can be invoiced,
 * and the deferred method is refused as a validation ({@code odd/tasks/cfdi-fiscal-document.md},
 * decision {@code L15}(d)).
 *
 * <p><b>Why this rule is pinned at the predicate and not through a stamp.</b> The method a document states
 * reaches it from the schema's own default — {@code V109} declares {@code metodo_pago VARCHAR(3) NOT NULL
 * DEFAULT 'PUE'} and {@code FiscalDocumentSnapshotWriter} never sets it — and no sale of this tree can state
 * the deferred one: {@code V87} dropped {@code sales_orders.payment_method_id} and the charge refuses anything
 * but an exact payment. So a sale whose document would be issued {@code PPD} is a state no API call and no
 * fixture can produce, and inventing one here (a raw {@code UPDATE} of a column the writer does not write, or a
 * completed sale with no payment at all) would be a test of a row the production path cannot create. What is
 * reachable is the rule itself, and it is the rule the writer calls before its first write.</p>
 *
 * <p><b>Why the parent type is an assertion and not a detail.</b> The refusal is a <b>400</b>, and it is a 400
 * because {@code FiscalDeferredPaymentNotSupportedException} is an {@link IllegalArgumentException} —
 * {@code GlobalExceptionHandler#handleBadRequest} serves that family with the message-only envelope, the same
 * way it serves {@code FiscalAttemptKeyRequiredException}. A case that only asserted the concrete type would
 * pass while the status silently became a 500.</p>
 */
@DisplayName("Only a sale paid when it happens can be invoiced (S13)")
class FiscalPaymentMethodTest {

    /** A sale the refusal is about: the message has to name it, or an operator has to go looking. */
    private static final UUID SALE = UUID.fromString("2a1f0d6e-8f1c-4a2b-9d3e-5c7b9a0e4d21");

    @Test
    @DisplayName("S13: PUE is the method of a sale paid when it happens, and the rule accepts it")
    void paidWhenItHappensIsAccepted() {
        assertThatCode(() -> FiscalPaymentMethod.requirePaidWhenItHappens(SALE, FiscalPaymentMethod.PUE))
                .as("a sale the charge settled is exactly what an invoice may derive from")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("S13/L15(d): the deferred method is refused naming the method and the sale, as a 400 and not a 500")
    void theDeferredMethodIsRefusedNamingTheMethodAndTheSale() {
        assertThatThrownBy(() -> FiscalPaymentMethod.requirePaidWhenItHappens(SALE, FiscalPaymentMethod.PPD))
                .as("the deferred method needs a placeholder date and a payment complement, so it is refused "
                        + "instead of half-modelled")
                .isInstanceOf(FiscalDeferredPaymentNotSupportedException.class)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(FiscalPaymentMethod.PPD)
                .hasMessageContaining(SALE.toString());
    }

    @Test
    @DisplayName("S13: a value that is no method at all is refused too, instead of being read as PUE")
    void aValueThatIsNoMethodIsRefused() {
        assertThatThrownBy(() -> FiscalPaymentMethod.requirePaidWhenItHappens(SALE, null))
                .as("the column is NOT NULL, so this is a caller's mistake and not a row's — and the rule "
                        + "refuses what it cannot recognise rather than assuming the permissive value")
                .isInstanceOf(FiscalDeferredPaymentNotSupportedException.class)
                .hasMessageContaining(SALE.toString());

        assertThatThrownBy(() -> FiscalPaymentMethod.requirePaidWhenItHappens(SALE, "PPX"))
                .isInstanceOf(FiscalDeferredPaymentNotSupportedException.class)
                .hasMessageContaining("PPX");
    }

    @Test
    @DisplayName("S13: the two methods are the authority's codes, and nothing else is declared here")
    void theVocabularyIsTheAuthorityTwoCodes() {
        assertThat(FiscalPaymentMethod.PUE).isEqualTo("PUE");
        assertThat(FiscalPaymentMethod.PPD)
                .as("the refused value is named by the same class that names the accepted one, so a refusal "
                        + "cannot cite a spelling the schema does not admit")
                .isEqualTo("PPD");
    }
}
