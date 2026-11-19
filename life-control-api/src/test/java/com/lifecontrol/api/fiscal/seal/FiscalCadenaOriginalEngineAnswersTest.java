package com.lifecontrol.api.fiscal.seal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import javax.xml.XMLConstants;
import javax.xml.transform.TransformerException;
import javax.xml.transform.stream.StreamSource;
import net.sf.saxon.TransformerFactoryImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The three JAXP answers {@link CadenaOriginal} records in prose, pinned so that a version bump cannot rewrite them
 * in silence.
 *
 * <p><b>Why this exists.</b> {@code CadenaOriginal} states, and its callers rely on, what this engine does with
 * {@code FEATURE_SECURE_PROCESSING} and with the two external-access controls: the constructor aborts the boot of a
 * deployment when the first is refused, and the class's whole hardening story is written in terms of which of the
 * other two is actually in force. Those sentences were measurements of a specific engine, and no test held them: the
 * engine's answers could change with a patch version and every other test in this tree would stay green, because the
 * committed include set resolves either way. This class makes the prose falsifiable.</p>
 *
 * <p><b>What the bump changed, and why the assertions look the way they do.</b> Measured on {@code 12.10} and
 * {@code 13.1} side by side: {@code ACCESS_EXTERNAL_DTD} is refused by both, and the refusal is only visible on the
 * read-back — the set is accepted and {@code getAttribute} throws {@code IllegalArgumentException: Unknown
 * configuration property …accessExternalDTD}, which is why this case reads the control instead of trusting the set;
 * {@code ACCESS_EXTERNAL_STYLESHEET} was <b>accepted and then ignored</b> by {@code 12.10}, which reported the empty
 * string back as {@code all}, and is <b>honoured</b> by {@code 13.1}, which reports back the empty string it was
 * given — no protocol allowed. That single difference is the reason {@code CadenaOriginal} now calls the engine a
 * second layer instead of only the resolver, and it is the change a reader of a dependency bump would otherwise have
 * to take on trust.</p>
 *
 * <p><b>And a read-back is not an enforcement.</b> The last case compiles a stylesheet whose only reference is
 * external and asks who refuses it: on this engine it is the engine that says the URI "has been prohibited", and
 * the same stylesheet compiled with no control at all is attempted instead — which is what makes the prohibition
 * the control's doing rather than the network's.</p>
 *
 * <p><b>Each assertion builds its own factory</b>, because these are settings on an instance: sharing one would make
 * the answers order-dependent and the test would pin the order rather than the engine.</p>
 */
@DisplayName("The engine's JAXP answers are the ones CadenaOriginal records")
class FiscalCadenaOriginalEngineAnswersTest {

    /**
     * A stylesheet whose only reference is external, on the reserved {@code .invalid} domain: it can never load, so
     * the question is always <i>who</i> refused it rather than whether the network answered.
     */
    private static final String EXTERNAL_INCLUDE = "<?xml version='1.0'?><xsl:stylesheet version='2.0' "
            + "xmlns:xsl='http://www.w3.org/1999/XSL/Transform'>"
            + "<xsl:include href='http://example.invalid/x.xsl'/>"
            + "<xsl:template match='/'><x/></xsl:template></xsl:stylesheet>";

    @Test
    @DisplayName("secure processing is accepted, because a refusal aborts the boot of the deployment")
    void secureProcessingIsAccepted() throws Exception {
        var factory = new TransformerFactoryImpl();

        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);

        assertThat(factory.getFeature(XMLConstants.FEATURE_SECURE_PROCESSING))
                .as("CadenaOriginal throws when this is refused, so the engine accepting it is what lets it boot")
                .isTrue();
    }

    @Test
    @DisplayName("the DTD external-access control is refused when it is read back, so it is not in force")
    void theExternalDtdControlIsRefused() {
        var factory = new TransformerFactoryImpl();

        assertThatNoException()
                .as("the set is accepted, which is why a reader who only sets the control would believe it is in "
                        + "force")
                .isThrownBy(() -> factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""));

        assertThatThrownBy(() -> factory.getAttribute(XMLConstants.ACCESS_EXTERNAL_DTD))
                .as("this refusal is what CadenaOriginal catches and logs: the control does nothing, and the "
                        + "resolver is what closes the references")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown configuration property");
    }

    @Test
    @DisplayName("the stylesheet external-access control is honoured, and the empty string means no protocol")
    void theStylesheetControlIsHonouredForTheEmptyString() throws Exception {
        var factory = new TransformerFactoryImpl();

        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");

        assertThat(factory.getAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET))
                .as("an engine that reports 'all' here ignores the control; the empty string is the answer that "
                        + "says the second layer is real")
                .isEqualTo("");

        var given = new TransformerFactoryImpl();
        given.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "file,http");
        assertThat(given.getAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET))
                .as("and it echoes what it was given, which is what makes the case above an answer rather than a "
                        + "constant")
                .isEqualTo("file,http");
    }

    @Test
    @DisplayName("the stylesheet control is enforced, not merely echoed: an external include is prohibited")
    void theStylesheetControlIsEnforcedAgainstAnExternalInclude() {
        var factory = new TransformerFactoryImpl();
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");

        assertThatThrownBy(() -> factory.newTemplates(new StreamSource(new StringReader(EXTERNAL_INCLUDE))))
                .as("the engine is what refuses the reference once the empty string is honoured, which is the "
                        + "second layer CadenaOriginal's javadoc claims")
                .isInstanceOf(TransformerException.class)
                .hasMessageContaining("prohibited");

        var uncontrolled = new TransformerFactoryImpl();
        assertThatThrownBy(() -> uncontrolled.newTemplates(new StreamSource(new StringReader(EXTERNAL_INCLUDE))))
                .as("and without the control the same reference is attempted instead of prohibited, which is what "
                        + "makes the refusal above the control's doing rather than the network's")
                .isInstanceOf(TransformerException.class)
                .hasMessageNotContaining("prohibited");
    }
}
