package com.lifecontrol.api.fiscal.seal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import net.sf.saxon.TransformerFactoryImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * egress.</p>
 *
 * <p><b>The boundary is the <i>declared</i> set, stated on purpose ({@code T7b}'s review).</b> The first version of
 * this class set a {@code URIResolver} and nothing else, so its protection was a side effect of that resolver
 * answering by basename: an external entity, a parameter entity and an external {@code DOCTYPE} all reached the
 * resolver, and a {@code DOCTYPE SYSTEM "file:///tmp/evil/utilerias.xslt"} was answered with the <b>committed</b>
 * {@code utilerias.xslt} — nothing local was read, but a name collision substituted a file nobody named, and a
 * substitution that succeeds is a failure nobody sees. So the resolver now accepts exactly two forms and refuses
 * everything else by name: an {@code href} the committed stylesheet declares verbatim (which is what Saxon passes
 * for a real {@code xsl:include}), and a bare name the stylesheet declares. An absolute URI, a {@code file:} URI, a
 * path carrying a directory, and a name that exists in the committed directory without being declared are all
 * refused with a message that states the boundary — see {@link #include(String)}.</p>
 *
 * <p><b>What the factory could be told, and what Saxon accepts.</b> The intent is stated where a reader looks for
 * it: {@code FEATURE_SECURE_PROCESSING} is turned on and both JAXP external-access controls are applied. The
 * engine's answers are recorded by {@link #restrictExternalAccess} rather than assumed, and they are not the same
 * answer twice: <b>Saxon 13.1 still refuses {@code ACCESS_EXTERNAL_DTD}</b> — the set is accepted and the read-back
 * throws {@code IllegalArgumentException: Unknown configuration property …accessExternalDTD} — so that control is
 * not in force, while <b>it honours {@code ACCESS_EXTERNAL_STYLESHEET}</b> — the empty string reads back as the empty
 * string, which denies every protocol (12.10 read it back as {@code all} and ignored it; Saxon corrects the
 * empty-string case in 13.1, release note 7269) — so the engine is a second layer for stylesheet references and
 * not only the resolver. Dropping the committed include resolution to keep the controls would be the wrong trade —
 * the include set is what makes the chain exact — so both are attempted (an engine that honours JAXP must not run
 * unhardened), what the engine did with them is logged, and {@link #include(String)} is what actually closes the
 * references: the resolver answers from the classpath and never through a protocol.</p>
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

    /**
     * The sentence every refusal carries, because the rule is what has to be readable and the offending href is
     * only its instance.
     */
    private static final String BOUNDARY = "resolves only the committed include set";

    /**
     * One {@code xsl:include} href of the committed stylesheet.
     *
     * <p>A regular expression and not a stylesheet parse, because the bytes it runs on are the ones {@link
     * #readPinnedStylesheet} has just compared with a published SHA-256: the declaration form cannot change without
     * the hash changing, so there is nothing left to be liberal about. A declaration this expression stops matching
     * fails loudly — the include is then undeclared and refused — instead of narrowing the gate.</p>
     */
    private static final Pattern DECLARED_INCLUDE = Pattern.compile("<xsl:include\\b[^>]*\\bhref\\s*=\\s*\"([^\"]+)\"");

    private static final Logger logger = LoggerFactory.getLogger(CadenaOriginal.class);

    /** Compiled once per sealer: a transformer is not thread-safe, but the compiled stylesheet is. */
    private final Templates templates;

    /** Compiles the committed stylesheet, refusing a resource whose bytes are not the pinned ones. */
    public CadenaOriginal() {
        var stylesheet = readPinnedStylesheet();
        this.templates = compile(stylesheet, declaredIncludesOf(stylesheet));
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
     * <p>The authority's stylesheet names its 33 includes by absolute URL under {@code www.sat.gob.mx} and all of
     * them are distinct by basename, so the committed set is keyed by it. But naming is not the boundary: a
     * reference is resolved only when it is <b>declared</b>, in one of exactly two forms — the {@code href} the
     * stylesheet names, byte for byte (which is what Saxon hands the resolver for a real {@code xsl:include}), or a
     * bare name the stylesheet declares. A {@code DOCTYPE SYSTEM} or an external entity is neither, so it is
     * refused here even when its basename happens to be a committed file: answering by basename alone, as a plain
     * existence check does, is what substituted {@code utilerias.xslt} for a {@code file:} URI naming a file
     * somewhere else.</p>
     *
     * @param href the reference the engine asks this transform to resolve
     * @return an open stream over the committed file; the caller closes it
     */
    static InputStream include(String href) {
        return include(href, declaredIncludesOf(readPinnedStylesheet()));
    }

    private static InputStream include(String href, DeclaredIncludes declared) {
        var name = declaredName(href, declared);
        var stream = CadenaOriginal.class.getClassLoader().getResourceAsStream(INCLUDES + name);
        if (stream == null) {
            throw new IllegalStateException(
                    "The cadena original transform " + BOUNDARY + ", and this build does not commit " + INCLUDES
                            + name + " for the declaration '" + href
                            + "'. A declared include that is not committed is a build defect: compiling without it "
                            + "would change the chain a document is signed over, so nothing is transformed at all.");
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
    private static ByteArrayInputStream readInclude(String href, DeclaredIncludes declared) {
        try (InputStream include = include(href, declared)) {
            return new ByteArrayInputStream(include.readAllBytes());
        } catch (IOException cannotRead) {
            throw new IllegalStateException("The committed include for '" + href + "' could not be read.", cannotRead);
        }
    }

    // ──────────────────────────────────────
    // the declared set, and what is refused
    // ──────────────────────────────────────

    /** The two forms of a reference this transform resolves: the declared hrefs, and their bare names. */
    private record DeclaredIncludes(Set<String> hrefs, Set<String> names) {}

    /** The includes the committed stylesheet declares, in document order. */
    private static DeclaredIncludes declaredIncludesOf(byte[] stylesheet) {
        var text = new String(stylesheet, StandardCharsets.UTF_8);
        var matcher = DECLARED_INCLUDE.matcher(text);
        Set<String> hrefs = new LinkedHashSet<>();
        while (matcher.find()) {
            hrefs.add(matcher.group(1));
        }
        if (hrefs.isEmpty()) {
            throw new IllegalStateException("The committed cadena original stylesheet declares no xsl:include at all, "
                    + "and the authority's stylesheet includes 33. A stylesheet with none is not the file this class "
                    + "was written against (SHA-256 " + SHA_256 + "), so no document can be transformed with it.");
        }
        Set<String> names = new LinkedHashSet<>();
        for (var href : hrefs) {
            names.add(basename(href));
        }
        return new DeclaredIncludes(Set.copyOf(hrefs), Set.copyOf(names));
    }

    /** The committed file one declared reference names, or a refusal stating the boundary. */
    private static String declaredName(String href, DeclaredIncludes declared) {
        if (href != null && declared.hrefs().contains(href)) {
            return basename(href);
        }
        if (href != null && declared.names().contains(href)) {
            return href;
        }
        throw new IllegalStateException("The cadena original transform " + BOUNDARY + ", and '" + href
                + "' is not in it. The SAT's stylesheet declares "
                + declared.hrefs().size()
                + " xsl:include hrefs and this tree commits one file per declared name (" + INCLUDES
                + "). A reference this transform answers has to be one of those declarations or the bare name of one "
                + "of them; anything else — an absolute URI, a file: URI, a path with a directory in it, or a name "
                + "the stylesheet does not declare even when a file of that name is committed — is a reference this "
                + "transform must not follow. Following it would read a local file, fetch the network, or answer "
                + "with a committed file the reference never named.");
    }

    private static String basename(String href) {
        return href.substring(href.lastIndexOf('/') + 1);
    }

    // ──────────────────────────────────────
    // the compile, once
    // ──────────────────────────────────────

    private static Templates compile(byte[] stylesheet, DeclaredIncludes declared) {
        var factory = hardenedFactory(declared);
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
     * The engine this transform runs on, with the hardening it can be given and the resolver it is closed by.
     *
     * <p>Both halves are here and not in {@link #include(String)} alone, because the refusal a reader should find
     * first is the intent: secure processing on, external access restricted, and a resolver that resolves the
     * committed set. What the engine does with the restrictions is {@link #restrictExternalAccess}'s job to record
     * — 13.1 refuses the DTD control and honours the stylesheet one — so this method never assumes they took
     * effect.</p>
     */
    private static TransformerFactoryImpl hardenedFactory(DeclaredIncludes declared) {
        var factory = new TransformerFactoryImpl();
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        } catch (TransformerConfigurationException refusedSecureProcessing) {
            throw new IllegalStateException(
                    "Saxon refused " + XMLConstants.FEATURE_SECURE_PROCESSING
                            + ", and a transform that cannot be told to run with secure processing is one this tree "
                            + "does not run at all: the input is an unsigned document whose references this class "
                            + "closes by hand, and running without the engine's own limits as well would leave the "
                            + "closing to a single layer.",
                    refusedSecureProcessing);
        }
        restrictExternalAccess(factory, XMLConstants.ACCESS_EXTERNAL_DTD);
        restrictExternalAccess(factory, XMLConstants.ACCESS_EXTERNAL_STYLESHEET);
        factory.setURIResolver((href, base) -> new StreamSource(readInclude(href, declared), href));
        return factory;
    }

    /**
     * One JAXP external-access restriction, and what the engine did with it.
     *
     * <p>The two controls are applied and they answer differently, so neither answer is assumed. Measured on this
     * tree's engine (Saxon-HE 13.1): {@code ACCESS_EXTERNAL_DTD} is <b>refused</b> when it is read back — the set is
     * accepted and {@code getAttribute} throws {@code IllegalArgumentException: Unknown configuration
     * property …accessExternalDTD} — so that control does nothing, and
     * {@code ACCESS_EXTERNAL_STYLESHEET} is <b>accepted and honoured</b>, reading the empty string back as the empty
     * string, which allows no protocol at all. 12.10 read that same value back as {@code all} and ignored it, which
     * is why this paragraph changed with the bump: Saxon corrects the empty-string case in 13.1 (release note 7269).
     * The outcome is logged when the pipeline is built — the refusal always, the acceptance only when the engine
     * reports back a value showing it was ignored — because the difference between a control that holds and one
     * that does nothing is exactly what a reader of this code cannot see.</p>
     */
    private static void restrictExternalAccess(TransformerFactoryImpl factory, String attribute) {
        try {
            factory.setAttribute(attribute, "");
            var effective = factory.getAttribute(attribute);
            if (!"".equals(effective)) {
                logger.info(
                        "Saxon accepted JAXP control {} and still reports '{}', so it is not in force; the URIResolver "
                                + "is what {}",
                        attribute,
                        effective,
                        BOUNDARY);
            }
        } catch (IllegalArgumentException refusedBySaxon) {
            logger.info(
                    "Saxon refused JAXP control {} ({}), so it is not in force; the URIResolver is what {}",
                    attribute,
                    refusedBySaxon.getMessage(),
                    BOUNDARY);
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
}
