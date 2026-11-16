package com.lifecontrol.api.fiscal.cancellation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.lifecontrol.api.fiscal.exception.FiscalSubstitutingFolioRequiredException;
import com.lifecontrol.api.fiscal.model.FiscalCancellationMotive;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * A cancellation request: the document being cancelled, the motive, and the <b>one-way</b> rule about the folio
 * that substitutes it ({@code S7} of {@code odd/tasks/cfdi-fiscal-document.md}, decision {@code L20}(f)).
 *
 * <p><b>Why the rule is one-way and the suite proves the second half too.</b> The authority's
 * {@code cancelación.xsd} makes {@code FolioSustitucion} {@code use="optional"} and documents it as required only
 * when the motive is {@code 01}; no retrieved source forbids it for {@code 02}–{@code 04}, and what the SAT
 * rejects (code {@code 312}) is a <i>wrong</i> UUID rather than the attribute's presence. So the symmetric rule
 * nobody stated is deliberately absent, and the cases that accept a folio beside a motive that does not require it
 * are what keep a later edit from adding it — the refusal is not allowed to be invented by whoever reads the
 * attribute's name next.</p>
 *
 * <p><b>Why the refusal is asserted by type and by vocabulary.</b> The field is named because an operator reading
 * a 400 has to know which attribute of a request to the <b>authority</b> to fill, and that name is
 * {@code FolioSustitucion} — not {@code substitutingFolio}, which is what this tree calls it. The type is
 * asserted to be an {@link IllegalArgumentException} for the same reason {@code FiscalDeferredPaymentNotSupported}
 * is: {@code GlobalExceptionHandler} already answers that family with a 400, so the mapping is inherited instead of
 * hand-written.</p>
 */
@DisplayName("A cancellation request: one folio, one motive, and the one-way rule (S7, L20(f))")
class CancellationRequestTest {

    private static final UUID CANCELLED = UUID.fromString("86b8719c-2a66-4f52-8b6c-36e69c57cf5a");

    private static final UUID SUBSTITUTE = UUID.fromString("8c3f076a-ce2a-4847-8566-43ab1cba228c");

    @Test
    @DisplayName("motive 01 without a substituting folio is refused, and the message names FolioSustitucion")
    void theMotiveThatReplacesADocumentWithoutItsFolioIsRefused() {
        var refusal = catchThrowableOfType(
                FiscalSubstitutingFolioRequiredException.class,
                () -> new CancellationRequest(CANCELLED, FiscalCancellationMotive.ERRORS_WITH_RELATION, null));

        assertThat(refusal)
                .as("01 is 'issued with errors and related to a substituting document', so the folio is not "
                        + "optional there")
                .isNotNull();
        assertThat(refusal)
                .as("the HTTP layer answers this family with a 400 and needs no handler of its own")
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(refusal.getMessage())
                .as("the field is named as the authority spells it, because that is the attribute to fill")
                .contains("FolioSustitucion")
                .contains("01");
    }

    @Test
    @DisplayName("motive 01 with a substituting folio is accepted")
    void theMotiveThatReplacesADocumentKeepsTheFolioItNames() {
        var request = new CancellationRequest(CANCELLED, FiscalCancellationMotive.ERRORS_WITH_RELATION, SUBSTITUTE);

        assertThat(request.uuid()).isEqualTo(CANCELLED);
        assertThat(request.motive()).isEqualTo(FiscalCancellationMotive.ERRORS_WITH_RELATION);
        assertThat(request.substitutingFolio()).isEqualTo(SUBSTITUTE);
        assertThat(request.statesSubstitutingFolio()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(
            value = FiscalCancellationMotive.class,
            names = {"ERRORS_WITHOUT_RELATION", "OPERATION_NOT_CARRIED_OUT", "NOMINAL_OPERATION_IN_GLOBAL_INVOICE"})
    @DisplayName("motives 02 to 04 are accepted with no substituting folio")
    void aMotiveThatDoesNotReplaceADocumentNeedsNoFolio(FiscalCancellationMotive motive) {
        var request = new CancellationRequest(CANCELLED, motive, null);

        assertThat(request.motive().requiresSubstitutingFolio()).isFalse();
        assertThat(request.substitutingFolio())
                .as("no folio is invented for a motive that does not require one")
                .isNull();
        assertThat(request.statesSubstitutingFolio()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(
            value = FiscalCancellationMotive.class,
            names = {"ERRORS_WITHOUT_RELATION", "OPERATION_NOT_CARRIED_OUT", "NOMINAL_OPERATION_IN_GLOBAL_INVOICE"})
    @DisplayName("motives 02 to 04 are ALSO accepted with a substituting folio, because no source forbids it")
    void aFolioBesideAMotiveThatDoesNotRequireItIsAccepted(FiscalCancellationMotive motive) {
        var request = new CancellationRequest(CANCELLED, motive, SUBSTITUTE);

        assertThat(request.substitutingFolio())
                .as("L20(f): the rule is one-way, and the symmetric one nobody stated is not implemented")
                .isEqualTo(SUBSTITUTE);
    }

    @Test
    @DisplayName("a request with no document to cancel is refused, naming UUID")
    void aRequestWithoutADocumentIsRefused() {
        var refusal = catchThrowableOfType(
                NullPointerException.class,
                () -> new CancellationRequest(null, FiscalCancellationMotive.ERRORS_WITHOUT_RELATION, null));

        assertThat(refusal).isNotNull();
        assertThat(refusal.getMessage()).contains("UUID").contains("cancel");
    }

    @Test
    @DisplayName("a request with no motive is refused, because the authority's schema requires one")
    void aRequestWithoutAMotiveIsRefused() {
        var refusal =
                catchThrowableOfType(NullPointerException.class, () -> new CancellationRequest(CANCELLED, null, null));

        assertThat(refusal).isNotNull();
        assertThat(refusal.getMessage()).contains("motivo");
    }
}
