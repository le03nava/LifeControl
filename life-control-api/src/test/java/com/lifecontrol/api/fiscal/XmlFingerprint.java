package com.lifecontrol.api.fiscal;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

/**
 * Two parsed documents compared attribute by attribute, for the one claim two renderings of a stamp make:
 * <b>they differ in {@code Sello} alone</b> (decision {@code L18}(d) of {@code odd/tasks/cfdi-fiscal-document.md)).
 *
 * <p>It exists because that claim is made twice — by the builder, which can render a document with and without a
 * seal, and by the attempt, which does exactly that around one call to the sealer — and two copies of a
 * comparison this specific are two copies free to disagree about what "alone" means. The comparison is on the
 * parsed tree and never on the string: an attribute inserted in another position, a child that moved or an
 * attribute that changed value would all leave a substring check green.</p>
 */
final class XmlFingerprint {

    private XmlFingerprint() {}

    static Document parse(String xml) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception cannotParse) {
            throw new IllegalStateException("a rendered CFDI has to be well formed XML", cannotParse);
        }
    }

    /** Every attribute of one element except the seal, so two renderings can be compared without it. */
    static Map<String, String> attributesExceptSello(Element element) {
        Map<String, String> attributes = new LinkedHashMap<>();
        var named = element.getAttributes();
        for (int index = 0; index < named.getLength(); index++) {
            var attribute = named.item(index);
            if ("Sello".equals(attribute.getLocalName())) {
                continue;
            }
            attributes.put(attribute.getNodeName(), attribute.getNodeValue());
        }
        return attributes;
    }

    /**
     * The whole subtree below an element, one row per element, path and attributes included.
     *
     * <p>A list of strings rather than a recursive comparison so a failure names the one child that moved instead
     * of answering "the trees differ". The path carries the position, so a reordered concept is a difference even
     * when both documents carry the same set of them.</p>
     */
    static List<String> childrenOf(Element root) {
        List<String> rows = new ArrayList<>();
        collect(root, "", rows);
        return rows;
    }

    private static void collect(Node node, String path, List<String> rows) {
        var children = node.getChildNodes();
        var position = 0;
        for (int index = 0; index < children.getLength(); index++) {
            if (children.item(index) instanceof Element element) {
                var childPath = path + "/" + element.getLocalName() + "[" + position++ + "]";
                rows.add(childPath + attributesExceptSello(element));
                collect(element, childPath, rows);
            }
        }
    }
}
