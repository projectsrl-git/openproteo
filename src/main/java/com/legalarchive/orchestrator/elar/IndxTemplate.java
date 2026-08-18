package com.legalarchive.orchestrator.elar;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The INDX template, read once per step and split into prologue, per-document block and epilogue.
 *
 * Every family has its own template with its own tags, and only one of them has been read, so nothing
 * about tag names, tag counts or nesting is hardcoded and nothing about the other families is inferred
 * from that one. The model is <b>discovered</b>:
 *
 * <ol>
 *   <li>find the document container by namespace and local name, both from the properties;</li>
 *   <li>require exactly one element child - that child is the per-document block, whatever it is
 *       called;</li>
 *   <li>everything before it is the prologue and everything after is the epilogue, emitted verbatim.</li>
 * </ol>
 *
 * Anything else fails at step start naming the template, what was looked for and what was found.
 * Failing before any output exists is cheap; discovering it mid-run is not.
 *
 * Because the block is emitted from the parsed template rather than from a hand-written element list,
 * a family's constants come through on their own - the tags that carry fixed values and are in no
 * mapping. Only two tags are treated specially, and both come from configuration.
 */
public final class IndxTemplate {

    private final Document doc;
    private final Element container;
    private final Element block;
    private final String path;

    private IndxTemplate(Document doc, Element container, Element block, String path) {
        this.doc = doc; this.container = container; this.block = block; this.path = path;
    }

    public Element block() { return block; }
    public String path() { return path; }

    public static IndxTemplate parse(File templateFile, String idmsNamespace, String containerLocalName)
            throws Exception {
        if (templateFile == null || !templateFile.isFile()) {
            throw new IllegalArgumentException("INDX template not found: "
                    + (templateFile == null ? "(null)" : templateFile.getAbsolutePath()));
        }
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        // the template is ours and local, but an XML parser reading a file should still not be
        // reachable by external entities
        try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Exception ignored) { }
        try { f.setXIncludeAware(false); } catch (Exception ignored) { }
        f.setExpandEntityReferences(false);
        DocumentBuilder b = f.newDocumentBuilder();
        Document d = b.parse(templateFile);

        Element cont = findByNs(d.getDocumentElement(), idmsNamespace, containerLocalName);
        if (cont == null) {
            throw new IllegalArgumentException("no <" + containerLocalName + "> element in namespace '"
                    + idmsNamespace + "' was found in " + templateFile.getAbsolutePath()
                    + " - that element is where per-document blocks are placed, so the template cannot be used."
                    + " Check idms.namespace in the properties file against the template's own namespaces.");
        }
        List<Element> kids = elementChildren(cont);
        if (kids.size() != 1) {
            throw new IllegalArgumentException("<" + containerLocalName + "> in " + templateFile.getAbsolutePath()
                    + " must hold exactly one element child, which is used as the per-document block; found "
                    + kids.size() + (kids.isEmpty() ? "" : (" (" + names(kids) + ")"))
                    + ". A template of a different shape needs the model revisiting rather than working around.");
        }
        return new IndxTemplate(d, cont, kids.get(0), templateFile.getAbsolutePath());
    }

    private static String names(List<Element> els) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < els.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(els.get(i).getNodeName());
        }
        return sb.toString();
    }

    private static Element findByNs(Element from, String ns, String local) {
        if (from == null) return null;
        if (local.equals(from.getLocalName()) && eq(ns, from.getNamespaceURI())) return from;
        NodeList ns2 = from.getChildNodes();
        for (int i = 0; i < ns2.getLength(); i++) {
            Node n = ns2.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element hit = findByNs((Element) n, ns, local);
            if (hit != null) return hit;
        }
        return null;
    }
    private static boolean eq(String a, String b) { return a == null ? b == null : a.equals(b); }

    static List<Element> elementChildren(Element e) {
        List<Element> out = new ArrayList<Element>();
        NodeList n = e.getChildNodes();
        for (int i = 0; i < n.getLength(); i++) {
            if (n.item(i).getNodeType() == Node.ELEMENT_NODE) out.add((Element) n.item(i));
        }
        return out;
    }

    // ------------------------------------------------------------------ emission

    /** Supplies the per-document values, and writes the content payload itself. */
    public interface DocSource {
        /** Text for a mapped tag, or null when the template's own value should stand. */
        String value(String qname) throws IOException;
        /** Writes the content tag's payload. Batch 3 streams Base64 here. */
        void writeContent(WrappingXmlOut out, String qname) throws IOException;
        /** True for the tag that carries the document content. */
        boolean isContentTag(String qname);
    }

    /** Emits the whole document: prologue, one block per document, epilogue. */
    public void write(WrappingXmlOut out, List<DocSource> docs) throws IOException {
        out.declaration();
        emit(out, doc.getDocumentElement(), docs);
        out.newLine();
    }

    private void emit(WrappingXmlOut out, Element e, List<DocSource> docs) throws IOException {
        if (e == block) return;                       // the placeholder itself is never emitted as-is
        String q = e.getNodeName();
        out.startElement(q);
        writeAttrs(out, e);
        List<Element> kids = elementChildren(e);
        String txt = directText(e);

        if (e == container) {
            out.closeStartTag();
            for (int i = 0; i < docs.size(); i++) emitBlock(out, block, docs.get(i));
            out.endElement(q);
            return;
        }
        if (kids.isEmpty() && txt.isEmpty()) { out.selfClose(); return; }
        out.closeStartTag();
        if (!txt.isEmpty()) out.text(q, txt);
        for (int i = 0; i < kids.size(); i++) emit(out, kids.get(i), docs);
        out.endElement(q);
    }

    /**
     * One document. Template constants survive because the block is walked as parsed: a tag the row
     * has no value for keeps whatever the template put there.
     */
    private void emitBlock(WrappingXmlOut out, Element e, DocSource src) throws IOException {
        String q = e.getNodeName();
        out.startElement(q);
        writeAttrs(out, e);
        List<Element> kids = elementChildren(e);

        if (src.isContentTag(q)) {
            out.closeStartTag();
            src.writeContent(out, q);
            out.endElement(q);
            return;
        }
        if (!kids.isEmpty()) {
            out.closeStartTag();
            for (int i = 0; i < kids.size(); i++) emitBlock(out, kids.get(i), src);
            out.endElement(q);
            return;
        }
        String v = src.value(q);
        // null means "the row says nothing about this tag", so the template's own text stands - which
        // is how a family's constants survive. An empty string is a value, and overrides.
        if (v == null) v = directText(e);
        if (v.isEmpty()) { out.selfClose(); return; }
        out.closeStartTag();
        out.text(q, v);
        out.endElement(q);
    }

    private void writeAttrs(WrappingXmlOut out, Element e) throws IOException {
        NamedNodeMap m = e.getAttributes();
        for (int i = 0; i < m.getLength(); i++) {
            Attr a = (Attr) m.item(i);
            out.attribute(a.getName(), a.getValue());
        }
    }

    /** Text directly inside this element, ignoring whitespace-only formatting between child elements. */
    static String directText(Element e) {
        StringBuilder sb = new StringBuilder();
        NodeList n = e.getChildNodes();
        for (int i = 0; i < n.getLength(); i++) {
            Node c = n.item(i);
            if (c.getNodeType() == Node.TEXT_NODE || c.getNodeType() == Node.CDATA_SECTION_NODE) {
                sb.append(c.getNodeValue());
            }
        }
        String s = sb.toString();
        return s.trim().isEmpty() ? "" : s.trim();
    }

    /** Every tag name in the block, so a mapping naming a tag the template lacks can be reported. */
    public List<String> blockTagNames() {
        List<String> out = new ArrayList<String>();
        collect(block, out);
        return out;
    }
    private void collect(Element e, List<String> out) {
        if (!out.contains(e.getNodeName())) out.add(e.getNodeName());
        List<Element> k = elementChildren(e);
        for (int i = 0; i < k.size(); i++) collect(k.get(i), out);
    }

    /** Tags a mapping refers to that the template does not contain: a silent no-op otherwise. */
    public List<String> unknownMappedTags(Map<String, String> mapping) {
        List<String> known = blockTagNames();
        List<String> out = new ArrayList<String>();
        for (Map.Entry<String, String> en : mapping.entrySet()) {
            if (!known.contains(en.getValue()) && !out.contains(en.getValue())) out.add(en.getValue());
        }
        return out;
    }
}
