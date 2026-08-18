package com.legalarchive.orchestrator.elar;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * The PULL manifest: a small document, roughly a kilobyte, referencing its INDX by name.
 *
 * The legacy {@code PullGenerator} replaces the literal {@code [INDEX_NAME]} inside every attribute
 * value of every {@code Index} element in a hardcoded namespace. It also ignores the
 * {@code idms.namespace} property entirely and hardcodes a different one from the INDX template - an
 * inconsistency that works and is preserved rather than tidied, because the receiving system has been
 * accepting it and this rewrite is not the place to find out whether it would accept anything else.
 *
 * The substitution here is applied to <b>every attribute of every element</b>. That is a superset of
 * the legacy behaviour and cannot change any file the legacy tool produced, since a template that
 * carries the placeholder anywhere else was already broken.
 */
public final class PullTemplate {

    public static final String PLACEHOLDER = "[INDEX_NAME]";

    private final Document doc;
    private final String path;

    private PullTemplate(Document d, String p) { doc = d; path = p; }

    public String path() { return path; }

    public static PullTemplate parse(File templateFile) throws Exception {
        if (templateFile == null || !templateFile.isFile()) {
            throw new IllegalArgumentException("PULL template not found: "
                    + (templateFile == null ? "(null)" : templateFile.getAbsolutePath()));
        }
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Exception ignored) { }
        try { f.setXIncludeAware(false); } catch (Exception ignored) { }
        f.setExpandEntityReferences(false);
        DocumentBuilder b = f.newDocumentBuilder();
        Document d = b.parse(templateFile);
        return new PullTemplate(d, templateFile.getAbsolutePath());
    }

    /** True when the template contains the placeholder at all. */
    public boolean hasPlaceholder() { return countPlaceholders(doc.getDocumentElement()) > 0; }

    private int countPlaceholders(Element e) {
        int n = 0;
        NamedNodeMap m = e.getAttributes();
        for (int i = 0; i < m.getLength(); i++) {
            if (m.item(i).getNodeValue().contains(PLACEHOLDER)) n++;
        }
        List<Element> kids = IndxTemplate.elementChildren(e);
        for (int i = 0; i < kids.size(); i++) n += countPlaceholders(kids.get(i));
        return n;
    }

    /** Writes the PULL with {@code [INDEX_NAME]} replaced everywhere it appears in an attribute. */
    public void write(WrappingXmlOut out, String indexName) throws IOException {
        out.declaration();
        emit(out, doc.getDocumentElement(), indexName);
        out.newLine();
    }

    private void emit(WrappingXmlOut out, Element e, String indexName) throws IOException {
        String q = e.getNodeName();
        out.startElement(q);
        NamedNodeMap m = e.getAttributes();
        for (int i = 0; i < m.getLength(); i++) {
            Attr a = (Attr) m.item(i);
            out.attribute(a.getName(), a.getValue().replace(PLACEHOLDER, indexName));
        }
        List<Element> kids = IndxTemplate.elementChildren(e);
        String txt = IndxTemplate.directText(e);
        if (kids.isEmpty() && txt.isEmpty()) { out.selfClose(); return; }
        out.closeStartTag();
        if (!txt.isEmpty()) out.text(q, txt.replace(PLACEHOLDER, indexName));
        for (int i = 0; i < kids.size(); i++) emit(out, kids.get(i), indexName);
        out.endElement(q);
    }
}
