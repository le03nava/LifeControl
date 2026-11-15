package com.lifecontrol.api.fiscal.cancellation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.fiscal.model.FiscalCancellationMotive;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Anexo 20 cancellation request, rendered by hand: the document the authority is asked to cancel, and the two
 * renderings a signature needs ({@code T8b}, decision {@code L20}(g) of {@code odd/tasks/cfdi-fiscal-document.md}).
 *
 * <p><b>Why the expected values are literals and not a composition of the builder's own constants.</b> The subject
 * is a document whose <b>bytes</b> are digested: the digest covers the c14n of the seal-less rendering, so a
 * renderer that moved one attribute, one space or one quote would produce a request the authority refuses and a
 * signature over something else. An expected value assembled from the same parts the renderer uses would follow
 * the renderer wherever it went, which is precisely what a pin must not do. The last case in
 * {@link VendorCancellationKnownAnswerTest} closes the loop by comparing these same bytes with the vendor's own
 * published example.</p>
 *
 * <p><b>Why motives {@code 01} with and without a folio are not both here.</b> {@code 01} without a substituting
 * folio is <b>unconstructible</b>: {@link CancellationRequest} refuses it ({@code L20}(f)). The remaining six
 * combinations — {@code 02}, {@code 03}, {@code 04} with and without — are all legal, so all six are pinned, and
 * the seventh case is {@code 01} <b>with</b> its folio.</p>
 *
 * <p><b>Why the attributes are ordered as they are.</b> XML gives attribute order no meaning, and an inclusive
 * C14N sorts every attribute before it is digested, so the order below cannot change either the document or its
 * signature. It is the vendor's own order — {@code FolioSustitucion}, {@code Motivo}, {@code UUID} — so that the
 * comparison with the published example is a diff a reader can follow line by line.</p>
 */
@DisplayName("The Anexo 20 cancellation request builder (T8b, L20(g))")
class CfdiCancellationRequestBuilderTest {

    private static final String DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n";

    private static final UUID CANCELLED = UUID.fromString("86b8719c-2a66-4f52-8b6c-36e69c57cf5a");

    private static final UUID SUBSTITUTE = UUID.fromString("8c3f076a-ce2a-4847-8566-43ab1cba228c");

    private static final String RFC = "AAA010101AAA";

    private static final LocalDateTime FECHA = LocalDateTime.of(2026, 11, 15, 10, 15, 30);

    /**
     * The header both renderings share: the declaration, the three namespace declarations the vendor's example
     * states, the date of the request and the issuer.
     */
    private static final String HEADER = DECLARATION
            + "<Cancelacion xmlns=\"http://cancelacfd.sat.gob.mx\" xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\" "
            + "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" Fecha=\"2026-11-15T10:15:30\" "
            + "RfcEmisor=\"AAA010101AAA\"><Folios>";

    /**
     * The {@code Folio} a motive {@code 01} request states, with the motive left as a placeholder so the three
     * one-way cases below can pin the same bytes with the only attribute that differs.
     */
    private static final String SUBSTITUTING_FOLIO =
            "<Folio FolioSustitucion=\"8C3F076A-CE2A-4847-8566-43AB1CBA228C\" Motivo=\"MOTIVO\" "
                    + "UUID=\"86B8719C-2A66-4F52-8B6C-36E69C57CF5A\" />";

    private static final String FOLIO_02 = "<Folio Motivo=\"02\" UUID=\"86B8719C-2A66-4F52-8B6C-36E69C57CF5A\" />";

    private static final String FOLIO_03 = "<Folio Motivo=\"03\" UUID=\"86B8719C-2A66-4F52-8B6C-36E69C57CF5A\" />";

    private static final String FOLIO_04 = "<Folio Motivo=\"04\" UUID=\"86B8719C-2A66-4F52-8B6C-36E69C57CF5A\" />";

    private static final String FOLIO_01 = SUBSTITUTING_FOLIO.replace("MOTIVO", "01");

    private static final String FOLIO_02_WITH_SUBSTITUTE = SUBSTITUTING_FOLIO.replace("MOTIVO", "02");

    private static final String FOLIO_03_WITH_SUBSTITUTE = SUBSTITUTING_FOLIO.replace("MOTIVO", "03");

    private static final String FOLIO_04_WITH_SUBSTITUTE = SUBSTITUTING_FOLIO.replace("MOTIVO", "04");

    private static final String CLOSING = "</Folios></Cancelacion>";

    /** A stand-in for what the sealer answers, so the sealed rendering is pinned without any cryptography. */
    private static final String SIGNATURE =
            "<Signature xmlns=\"http://www.w3.org/2000/09/xmldsig#\"><SignedInfo/></Signature>";

    private final CfdiCancellationRequestBuilder builder = new CfdiCancellationRequestBuilder();

    // ──────────────────────────────────────
    // the seal-less rendering: the bytes that get digested
    // ──────────────────────────────────────

    @Test
    @DisplayName("motive 01 with its substituting folio renders the exact bytes")
    void motiveOneWithItsSubstitutingFolio() {
        var request = new CancellationRequest(CANCELLED, FiscalCancellationMotive.ERRORS_WITH_RELATION, SUBSTITUTE);

        assertThat(builder.build(request, RFC, FECHA)).isEqualTo(HEADER + FOLIO_01 + CLOSING);
    }

    @Test
    @DisplayName("motive 02 renders the exact bytes, and states no FolioSustitucion at all")
    void motiveTwo() {
        var request = new CancellationRequest(CANCELLED, FiscalCancellationMotive.ERRORS_WITHOUT_RELATION, null);

        assertThat(builder.build(request, RFC, FECHA)).isEqualTo(HEADER + FOLIO_02 + CLOSING);
    }

    @Test
    @DisplayName("motive 03 renders the exact bytes")
    void motiveThree() {
        var request = new CancellationRequest(CANCELLED, FiscalCancellationMotive.OPERATION_NOT_CARRIED_OUT, null);

        assertThat(builder.build(request, RFC, FECHA)).isEqualTo(HEADER + FOLIO_03 + CLOSING);
    }

    @Test
    @DisplayName("motive 04 renders the exact bytes")
    void motiveFour() {
        var request =
                new CancellationRequest(CANCELLED, FiscalCancellationMotive.NOMINAL_OPERATION_IN_GLOBAL_INVOICE, null);

        assertThat(builder.build(request, RFC, FECHA)).isEqualTo(HEADER + FOLIO_04 + CLOSING);
    }

    @Test
    @DisplayName("motive 02 with a folio renders it too, because a folio is not forbidden there")
    void motiveTwoWithAFolio() {
        var request = new CancellationRequest(CANCELLED, FiscalCancellationMotive.ERRORS_WITHOUT_RELATION, SUBSTITUTE);

        assertThat(builder.build(request, RFC, FECHA)).isEqualTo(HEADER + FOLIO_02_WITH_SUBSTITUTE + CLOSING);
    }

    @Test
    @DisplayName("motive 03 with a folio renders it too")
    void motiveThreeWithAFolio() {
        var request =
                new CancellationRequest(CANCELLED, FiscalCancellationMotive.OPERATION_NOT_CARRIED_OUT, SUBSTITUTE);

        assertThat(builder.build(request, RFC, FECHA)).isEqualTo(HEADER + FOLIO_03_WITH_SUBSTITUTE + CLOSING);
    }

    @Test
    @DisplayName("motive 04 with a folio renders it too")
    void motiveFourWithAFolio() {
        var request = new CancellationRequest(
                CANCELLED, FiscalCancellationMotive.NOMINAL_OPERATION_IN_GLOBAL_INVOICE, SUBSTITUTE);

        assertThat(builder.build(request, RFC, FECHA)).isEqualTo(HEADER + FOLIO_04_WITH_SUBSTITUTE + CLOSING);
    }

    @Test
    @DisplayName("the seal-less rendering states no Signature, no Sello and no certificate of its own")
    void theSealLessRenderingCarriesNoSignature() {
        var sealLess = builder.build(
                new CancellationRequest(CANCELLED, FiscalCancellationMotive.ERRORS_WITHOUT_RELATION, null), RFC, FECHA);

        assertThat(sealLess)
                .as("the signature is an element inside the document, so a seal-less document has none")
                .doesNotContain("Signature")
                .doesNotContain("Sello")
                .doesNotContain("Certificado")
                .doesNotContain("http://www.w3.org/2000/09/xmldsig#");
        assertThat(sealLess).startsWith(DECLARATION).endsWith(CLOSING);
    }

    // ──────────────────────────────────────
    // the sealed rendering: the same bytes plus the signature it was given
    // ──────────────────────────────────────

    @Test
    @DisplayName("the sealed rendering is the seal-less one with the Signature written before the closing tag")
    void theSealedRenderingAddsNothingButTheSignature() {
        var request = new CancellationRequest(CANCELLED, FiscalCancellationMotive.ERRORS_WITH_RELATION, SUBSTITUTE);
        var sealLess = builder.build(request, RFC, FECHA);

        var sealed = builder.build(request, RFC, FECHA, SIGNATURE);

        assertThat(sealed)
                .as("the two renderings differ in the signature alone, which is what makes the digest cover the "
                        + "bytes that were signed (L18(d))")
                .isEqualTo(HEADER + FOLIO_01 + "</Folios>" + SIGNATURE + "</Cancelacion>");
        assertThat(sealed)
                .as("everything before the signature is byte-identical to the seal-less rendering")
                .startsWith(sealLess.substring(0, sealLess.length() - "</Cancelacion>".length()))
                .endsWith("</Cancelacion>");
    }

    @Test
    @DisplayName("a sealed rendering with no signature is refused instead of writing an empty one")
    void aSealedRenderingWithoutASignatureIsRefused() {
        var request = new CancellationRequest(CANCELLED, FiscalCancellationMotive.ERRORS_WITHOUT_RELATION, null);

        assertThatThrownBy(() -> builder.build(request, RFC, FECHA, " "))
                .as("a blank signature is not a seal-less document; it is a document that claims one")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Signature");
    }

    // ──────────────────────────────────────
    // the two values that have to be there
    // ──────────────────────────────────────

    @Test
    @DisplayName("the five characters XML cannot carry literally are escaped in the issuer's RFC")
    void theIssuerRfcIsEscaped() {
        var request = new CancellationRequest(CANCELLED, FiscalCancellationMotive.ERRORS_WITHOUT_RELATION, null);

        assertThat(builder.build(request, "A&M010101<A1", FECHA))
                .as("a legal entity's RFC may carry an ampersand, which is XML markup until it is escaped")
                .contains("RfcEmisor=\"A&amp;M010101&lt;A1\"");
    }

    @Test
    @DisplayName("a request with no issuer is refused, naming RfcEmisor")
    void aRequestWithoutAnIssuerIsRefused() {
        var request = new CancellationRequest(CANCELLED, FiscalCancellationMotive.ERRORS_WITHOUT_RELATION, null);

        assertThatThrownBy(() -> builder.build(request, "  ", FECHA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RfcEmisor");
        assertThatThrownBy(() -> builder.build(request, null, FECHA))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("RfcEmisor");
    }

    @Test
    @DisplayName("a request with no instant is refused, because Fecha has no default")
    void aRequestWithoutAnInstantIsRefused() {
        var request = new CancellationRequest(CANCELLED, FiscalCancellationMotive.ERRORS_WITHOUT_RELATION, null);

        assertThatThrownBy(() -> builder.build(request, RFC, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Fecha");
    }
}
