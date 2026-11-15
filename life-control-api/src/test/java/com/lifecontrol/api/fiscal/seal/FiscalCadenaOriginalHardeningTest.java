package com.lifecontrol.api.fiscal.seal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The transform's external references, closed on purpose instead of by accident ({@code T7b}, decision
 * {@code L18}(d) of {@code odd/tasks/cfdi-fiscal-document.md)).
 *
 * <p><b>What an independent probe of the first implementation found.</b> The class set a {@code URIResolver} on
 * Saxon's factory and nothing else, and the protection that followed was a side effect of that resolver's
 * basename allowlist: an external entity, a parameter entity and an external {@code DOCTYPE} all reached it, and
 * a {@code DOCTYPE SYSTEM "file:///tmp/evil/utilerias.xslt"} was answered with the <b>committed</b>
 * {@code utilerias.xslt}. Nothing local was ever read — which is the good half — but a name collision substituted
 * a file nobody named, and a substitution that succeeds is a failure nobody sees.</p>
 *
 * <p><b>What these cases pin, and why each one is a case instead of a paragraph.</b> Every reference below is
 * something <i>this process</i> resolves rather than something the authority's stylesheet declares, so each one
 * has to be refused by name with a message that states the boundary: an external entity in element content, the
 * same in an attribute value, a {@code DOCTYPE SYSTEM} naming an {@code http} URI, one naming a {@code file:} URI,
 * and the collision above — a {@code file:} URI whose basename is a committed include. The last one is the one a
 * plain existence check answers instead of refusing, which is why it is the case that makes the gate a declared-set
 * gate and not an "is the file there" check.</p>
 *
 * <p><b>The last case is not about hardening: it is the fact the seal's premise rests on.</b> The document that is
 * signed carries no {@code Sello} ({@code L18}(d)), and the document that is sent carries the signature of that
 * first rendering. That pairing is only sound because the authority's stylesheet <b>ignores</b> {@code @Sello} —
 * adding a seal to a document cannot move its chain. Nothing stated that before this case, and a reader of the
 * pipeline had to take it on trust.</p>
 */
@DisplayName("The cadena original transform refuses every reference that is not a committed include (T7b)")
class FiscalCadenaOriginalHardeningTest {

    /**
     * The sentence the refusal has to carry, asserted as a phrase on purpose: a message naming only the offending
     * href would leave a reader to guess <i>why</i> it was refused, and the why is the rule.
     */
    private static final String BOUNDARY = "resolves only the committed include set";

    /** A basename that is committed as an include, so answering by basename alone finds a file for it. */
    private static final String COMMITTED_INCLUDE = "utilerias.xslt";

    // ──────────────────────────────────────
    // the references that are refused
    // ──────────────────────────────────────

    @Test
    @DisplayName("an external entity in element content is refused by name instead of read from the filesystem")
    void anExternalEntityInElementContentIsRefused() {
        var document = document(
                "<!DOCTYPE cfdi:Comprobante [<!ENTITY xxe SYSTEM \"file:///etc/hostname\">]>",
                "<cfdi:Emisor Rfc=\"AAA010101AAA\">&xxe;</cfdi:Emisor>");

        assertThatThrownBy(() -> new CadenaOriginal().of(document))
                .as("an entity is a reference this process resolves: the chain is a string, never a file read")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(BOUNDARY)
                .hasMessageContaining("file:///etc/hostname");
    }

    @Test
    @DisplayName("an external entity in an attribute value is refused instead of read from the filesystem")
    void anExternalEntityInAnAttributeIsRefused() {
        var document = document(
                "<!DOCTYPE cfdi:Comprobante [<!ENTITY xxe SYSTEM \"file:///etc/hostname\">]>",
                "<cfdi:Emisor Rfc=\"&xxe;\"/>");

        assertThatThrownBy(() -> CadenaOriginal.include("file:///etc/hostname"))
                .as("the reference the entity names is not a declared include, so the resolver refuses it by name "
                        + "whatever the engine does with the reference")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(BOUNDARY)
                .hasMessageContaining("file:///etc/hostname");
        assertThatThrownBy(() -> new CadenaOriginal().of(document))
                .as("and the document carrying it is never transformed: measured, the engine refuses this shape "
                        + "before it consults the resolver at all, because the XML specification forbids an external "
                        + "entity reference in an attribute value — a refusal one layer earlier than the one the "
                        + "resolver would have made, and this case states which of the two it saw")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("could not be transformed");
    }

    @Test
    @DisplayName("a DOCTYPE naming an http URI is refused, so the transform can never fetch the network")
    void aDoctypeNamingAnHttpUriIsRefused() {
        var document = documentWithDoctype("http://www.sat.gob.mx.example.test/cfd/4/utilerias.xslt");

        assertThatThrownBy(() -> new CadenaOriginal().of(document))
                .as("a transform that fetched one stylesheet would answer a different chain in a deployment "
                        + "with egress than in one without")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(BOUNDARY)
                .hasMessageContaining("http://www.sat.gob.mx.example.test/cfd/4/utilerias.xslt");
    }

    @Test
    @DisplayName("a DOCTYPE naming a file: URI is refused, so the transform can never read a local document")
    void aDoctypeNamingAFileUriIsRefused() {
        var document = documentWithDoctype("file:///tmp/not-committed/not-a-committed-include.xslt");

        assertThatThrownBy(() -> new CadenaOriginal().of(document))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(BOUNDARY)
                .hasMessageContaining("file:///tmp/not-committed/not-a-committed-include.xslt");
    }

    @Test
    @DisplayName("a DOCTYPE naming a committed include from another directory is refused instead of substituted")
    void aDoctypeNamingACommittedIncludeFromAnotherDirectoryIsRefused() {
        var document = documentWithDoctype("file:///tmp/evil/" + COMMITTED_INCLUDE);

        assertThatThrownBy(() -> new CadenaOriginal().of(document))
                .as(
                        "this is the collision the probe found: answering by basename hands the transform the "
                                + "committed %s when the reference named a file somewhere else, so the substitution is a "
                                + "null result that looks like a resolution",
                        COMMITTED_INCLUDE)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(BOUNDARY)
                .hasMessageContaining("file:///tmp/evil/" + COMMITTED_INCLUDE);
    }

    // ──────────────────────────────────────
    // the premise the seal rests on
    // ──────────────────────────────────────

    @Test
    @DisplayName("the authority's stylesheet ignores Sello, so a document with one states the chain of the same "
            + "document without one")
    void theStylesheetIgnoresTheSelloAttribute() {
        var sealed = FiscalCadenaOriginalTest.UNSIGNED.replace(
                "Version=\"4.0\"", "Version=\"4.0\" Sello=\"c2VsbG8tZGVsLWVtaXNvcg==\"");
        assertThat(sealed)
                .as("the case is vacuous unless the document really states a seal the stylesheet could read")
                .contains("Sello=\"c2VsbG8tZGVsLWVtaXNvcg==\"");

        var transform = new CadenaOriginal();
        var sealedChain = transform.of(sealed);
        var unsignedChain = transform.of(FiscalCadenaOriginalTest.UNSIGNED);

        assertThat(sealedChain)
                .as("this is why the seal signs the seal-less rendering (L18(d)): the seal is not one of the "
                        + "fields the chain states, so adding it cannot move the string it signs")
                .isEqualTo(unsignedChain)
                .startsWith("||4.0|");
    }

    // ──────────────────────────────────────
    // the documents, each one a reference and a well-formed comprobante around it
    // ──────────────────────────────────────

    /**
     * One comprobante whose only child is whatever the case wants to carry.
     *
     * <p>The root and the namespace are the minimum the authority's stylesheet matches on: these cases are about
     * what happens to a reference before a chain exists, so the document only has to be well formed and be a
     * {@code cfdi:Comprobante} in the 4.0 namespace.</p>
     */
    private static String documentWithDoctype(String systemId) {
        return document("<!DOCTYPE cfdi:Comprobante SYSTEM \"" + systemId + "\">", "");
    }

    private static String document(String declaration, String body) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + declaration + "\n"
                + "<cfdi:Comprobante xmlns:cfdi=\"http://www.sat.gob.mx/cfd/4\" Version=\"4.0\">"
                + body + "</cfdi:Comprobante>";
    }
}
