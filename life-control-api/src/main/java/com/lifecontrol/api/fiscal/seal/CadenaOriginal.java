package com.lifecontrol.api.fiscal.seal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import net.sf.saxon.TransformerFactoryImpl;

/**
 * The SAT's official {@code cadena original} transform, compiled once from the stylesheet committed beside this
 * class ({@code T7b}, decision {@code L18}(d) of {@code odd/tasks/cfdi-fiscal-document.md)).
 *
 * <p><b>Why the transform is a class and not four lines in the sealer.</b> The {@code cadena original} is the one
 * string this tree must never invent ({@code L12}(j)): it is what the {@code Sello} signs, and a chain that is
 * wrong by a whitespace produces a signature the authority refuses while looking correct. So the stylesheet, its
 * includes, its pin and the engine that runs it live in one place with their reasons, and the sealer only asks for
 * the string.</p>
 *
 * <p><b>Why Saxon-HE and not the transformer the JDK ships.</b> The authority's stylesheet declares
 * {@code version="2.0"}, and the JDK's transformer is XSLT 1.0 only — it does not refuse a 2.0 stylesheet, it runs
 * it with 1.0 semantics and answers a <b>different</b> chain. That is the worst failure shape available: a
 * document that validates, signs and is refused by the PAC with an error about the seal. Saxon-HE (Mozilla Public
 * License 2.0) is the smallest engine that runs the file as published.</p>
 *
 * <p><b>Why every include is committed and resolved locally.</b> The stylesheet statically includes 33 stylesheets
 * by absolute URL under {@code www.sat.gob.mx}, and an {@code xsl:include} is resolved at <b>compile</b> time: a
 * missing one is not a warning but a transform that never runs. Committing them (verbatim, hashes in the README
 * beside the stylesheet) is what makes the chain offline, deterministic and buildable in a container with no
 * egress. {@link #include} refuses any URL outside that committed set by name, so the transform cannot silently
 * depend on the network in one environment and on a stale local file in another.</p>
 *
 * <p><b>Why the resource's SHA-256 is checked here and not only in a test.</b> The hash is the pin: the test
 * asserts it so a substitution fails CI, and this constructor asserts it so a substitution fails the <b>boot</b>
 * of the deployment that would otherwise stamp with it. Both exist because the failure they prevent — a valid
 * signature over the wrong chain — is invisible until a PAC rejects a real document.</p>
 */
public final class CadenaOriginal {

    /** Where the committed stylesheet lives on the classpath. */
    public static final String RESOURCE = "fiscal/cadenaoriginal_4_0/cadenaoriginal_4_0.xslt";

    /** The committed SHA-256 of that stylesheet, as the SAT publishes it and the README records it. */
    public static final String SHA_256 = "b0559b380e73b850ca8a3da53b077a6051a09d87068d8d98e82dfd4acfba7565";

    /** The directory holding the stylesheet's own {@code xsl:include} targets. */
    private static final String INCLUDES = "fiscal/cadenaoriginal_4_0/includes/";

    /** Compiled once per sealer: a transformer is not thread-safe, but the compiled stylesheet is. */
    private final Templates templates;

    /** Compiles the committed stylesheet, refusing a resource whose bytes are not the pinned ones. */
    public CadenaOriginal() {
        this.templates = compile();
    }

    /**
     * The {@code cadena original} of one document.
     *
     * @param unsignedXml a CFDI 4.0 rendered without a {@code Sello}
     * @return the authority's chain: one line, {@code ||4.0|…||}, ready to be signed as UTF-8
     */
    public String of(String unsignedXml) {
        Objects.requireNonNull(unsignedXml, "a cadena original is the transform of a document, not of nothing");
        try {
            Transformer transformer = templates.newTransformer();
            var output = new StringWriter();
            transformer.transform(new StreamSource(new StringReader(unsignedXml)), new StreamResult(output));
            return output.toString();
        } catch (TransformerException cannotTransform) {
            throw new IllegalStateException(
                    "The CFDI could not be transformed into its cadena original. The document has to be well "
                            + "formed XML whose root is cfdi:Comprobante in " + "http://www.sat.gob.mx/cfd/4; "
                            + "anything else is a document the authority would refuse anyway.",
                    cannotTransform);
        }
    }

    /**
     * One of the stylesheet's includes, resolved to the file committed for it.
     *
     * <p>The authority's stylesheet names its includes by absolute URL and all 33 are distinct by basename, so the
     * committed set is keyed by it. A name that is not committed — or a {@code href} that is not a plain file name
     * — is refused here rather than left to a URL connection: the transform compiles offline or it does not
     * compile at all.</p>
     *
     * @param href the URL the stylesheet names in its {@code xsl:include}
     * @return an open stream over the committed file; the caller closes it
     */
    static InputStream include(String href) {
        var name = href == null ? "" : href.substring(href.lastIndexOf('/') + 1);
        var stream = isPlainFileName(name)
                ? CadenaOriginal.class.getClassLoader().getResourceAsStream(INCLUDES + name)
                : null;
        if (stream == null) {
            throw new IllegalStateException("The SAT's cadena original stylesheet includes '" + href
                    + "' and this tree has not committed it (" + INCLUDES + name + "). The transform is compiled "
                    + "from committed files only: fetching it at compile time would make a stamp depend on the "
                    + "network, and skipping it would change the cadena original a document is signed over.");
        }
        return stream;
    }

    /**
     * One include's bytes, read and closed here.
     *
     * <p>The resolver hands the engine a stream it does not promise to close, so the file is read once and the
     * handle released before Saxon sees it: a compile that left 33 open descriptors behind would be a leak in the
     * one place a deployment runs at boot and never again.</p>
     */
    private static ByteArrayInputStream readInclude(String href) {
        try (InputStream include = include(href)) {
            return new ByteArrayInputStream(include.readAllBytes());
        } catch (IOException cannotRead) {
            throw new IllegalStateException("The committed include for '" + href + "' could not be read.", cannotRead);
        }
    }

    // ──────────────────────────────────────
    // the compile, once
    // ──────────────────────────────────────

    private static Templates compile() {
        byte[] stylesheet = readPinnedStylesheet();
        var factory = new TransformerFactoryImpl();
        factory.setURIResolver((href, base) -> new StreamSource(readInclude(href), href));
        try {
            return factory.newTemplates(new StreamSource(new ByteArrayInputStream(stylesheet), RESOURCE));
        } catch (TransformerException cannotCompile) {
            throw new IllegalStateException(
                    "The committed cadena original stylesheet could not be compiled, so no document can be "
                            + "signed. It is the SAT's file as published (SHA-256 " + SHA_256
                            + ") and Saxon-HE runs it as XSLT 2.0; a compile failure here means the resource or the "
                            + "engine is not what this class was written against.",
                    cannotCompile);
        }
    }

    /**
     * The committed stylesheet, or a refusal naming both hashes.
     *
     * <p>The message states what was read and what was expected, because the interesting case is not a missing
     * resource — that is a build defect — but a <b>different</b> one, which is a substitution nobody meant to
     * review.</p>
     */
    private static byte[] readPinnedStylesheet() {
        byte[] bytes;
        try (InputStream stream = CadenaOriginal.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("The SAT's cadena original stylesheet is not on the classpath ("
                        + RESOURCE + "), so no document can be sealed.");
            }
            bytes = stream.readAllBytes();
        } catch (IOException cannotRead) {
            throw new IllegalStateException(
                    "The SAT's cadena original stylesheet could not be read from " + RESOURCE + ".", cannotRead);
        }
        var actual = sha256Of(bytes);
        if (!SHA_256.equals(actual)) {
            throw new IllegalStateException("The committed cadena original stylesheet is not the pinned one: its "
                    + "SHA-256 is " + actual + " and the documented one is " + SHA_256 + ". A different stylesheet "
                    + "compiles, transforms and signs a document the authority refuses, so this is a boot failure "
                    + "and not a warning.");
        }
        return bytes;
    }

    private static String sha256Of(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException cannotHash) {
            throw new IllegalStateException(
                    "SHA-256 is a mandatory MessageDigest algorithm of every Java platform.", cannotHash);
        }
    }

    private static boolean isPlainFileName(String name) {
        return !name.isBlank() && name.indexOf('/') < 0 && name.indexOf('\\') < 0 && !name.contains("..");
    }
}
