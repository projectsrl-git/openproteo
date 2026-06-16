package com.legalarchive.orchestrator.parser;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates many near-identical workflow XML files from one template XML and a "feeds" CSV,
 * joined with a second "tables" CSV (feedId -> SQL table name).
 *
 * Column names in the CSVs are configurable via {@link Mapping} (so the source CSV can carry
 * extra columns and use arbitrary header names — only the mapped columns are consumed).
 *
 * Mapped feed fields: feedId (required, also the file name), name, sourceId, description,
 * dataschema, displayschema. dataschema/displayschema cells carry JSON (RFC4180-escaped in the
 * CSV — doubled quotes); the raw JSON is returned on the Item for the caller to validate and
 * write into the feed directory. The tables CSV maps feedId -> table, injected as a workflow
 * variable (default name "originTableName").
 *
 * Pure JDK (DOM + CSV), no Jackson — unit-testable.
 */
public final class BulkWorkflowGenerator {

    /** Names of the columns in the feeds CSV (blank = field not used; feedId is required). */
    public static final class Mapping {
        public String feedId = "feedId";
        public String name = "name";
        public String sourceId = "sourceId";
        public String description = "description";
        public String dataschema = "dataschema";
        public String displayschema = "displayschema";
    }

    public static final class Item {
        public String feedId;
        public String xml;                 // generated XML (null on error)
        public String error;               // null if ok
        public String dataschemaJson;      // raw JSON from CSV (null if absent) — caller validates + writes
        public String displayschemaJson;   // raw JSON from CSV (null if absent)
        public String tableName;           // joined from the tables CSV (null if not found)
    }

    /**
     * @param templateXml the template workflow XML
     * @param feedsCsv    CSV #1 (one row per feed)
     * @param delim       delimiter for feedsCsv
     * @param map         column-name mapping for feedsCsv
     * @param tableByFeed feedId -> table name (from CSV #2); may be empty
     * @param tableVar    workflow variable name to receive the table (default originTableName)
     */
    public static List<Item> generate(String templateXml, String feedsCsv, char delim, Mapping map,
                                       Map<String, String> tableByFeed, String tableVar) {
        List<Item> out = new ArrayList<Item>();
        if (map == null) map = new Mapping();
        if (tableByFeed == null) tableByFeed = new LinkedHashMap<String, String>();
        if (tableVar == null || tableVar.trim().isEmpty()) tableVar = "originTableName";

        List<List<String>> rows = parseCsv(feedsCsv, delim);
        if (rows.isEmpty()) return out;
        List<String> header = rows.get(0);

        int iFeed = indexOf(header, map.feedId);
        int iName = indexOf(header, map.name);
        int iSource = indexOf(header, map.sourceId);
        int iDesc = indexOf(header, map.description);
        int iData = indexOf(header, map.dataschema);
        int iDisp = indexOf(header, map.displayschema);

        if (iFeed < 0) {
            Item e = new Item();
            e.error = "feeds CSV: column '" + map.feedId + "' (feedId) not found in header";
            out.add(e);
            return out;
        }

        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            if (allBlank(row)) continue;
            Item it = new Item();
            try {
                String feedId = cell(row, iFeed);
                if (feedId == null || feedId.trim().isEmpty()) { it.error = "row " + r + ": empty feedId"; out.add(it); continue; }
                it.feedId = feedId.trim();
                it.tableName = tableByFeed.get(it.feedId);

                Document doc = parseXml(templateXml);
                Element root = doc.getDocumentElement();
                root.setAttribute("feedId", it.feedId);
                if (iName >= 0 && cell(row, iName) != null) root.setAttribute("name", cell(row, iName));
                if (iSource >= 0 && cell(row, iSource) != null) root.setAttribute("sourceId", cell(row, iSource));
                if (iDesc >= 0 && cell(row, iDesc) != null) setChildText(doc, root, "description", cell(row, iDesc));

                if (it.tableName != null && !it.tableName.trim().isEmpty()) {
                    setVariable(doc, root, tableVar, it.tableName.trim());
                }

                String ds = iData >= 0 ? cell(row, iData) : null;
                String dp = iDisp >= 0 ? cell(row, iDisp) : null;
                if (ds != null && !ds.trim().isEmpty()) it.dataschemaJson = ds.trim();
                if (dp != null && !dp.trim().isEmpty()) it.displayschemaJson = dp.trim();

                it.xml = serialize(doc);
            } catch (Exception ex) {
                it.error = "row " + r + ": " + ex.getMessage();
            }
            out.add(it);
        }
        return out;
    }

    /** Build feedId -> tableName from the tables CSV. */
    public static Map<String, String> joinTable(String tablesCsv, char delim, String feedIdCol, String tableCol) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        if (tablesCsv == null || tablesCsv.trim().isEmpty()) return m;
        List<List<String>> rows = parseCsv(tablesCsv, delim);
        if (rows.isEmpty()) return m;
        List<String> header = rows.get(0);
        int iFeed = indexOf(header, feedIdCol == null || feedIdCol.trim().isEmpty() ? "feedId" : feedIdCol);
        int iTab = indexOf(header, tableCol == null || tableCol.trim().isEmpty() ? "tableName" : tableCol);
        if (iFeed < 0 || iTab < 0) return m;
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            if (allBlank(row)) continue;
            String f = cell(row, iFeed), t = cell(row, iTab);
            if (f != null && !f.trim().isEmpty() && t != null) m.put(f.trim(), t.trim());
        }
        return m;
    }

    // ---- column resolution ----

    private static int indexOf(List<String> header, String name) {
        if (name == null) return -1;
        String want = name.trim().toLowerCase();
        if (want.isEmpty()) return -1;
        for (int i = 0; i < header.size(); i++) {
            if (header.get(i).trim().toLowerCase().equals(want)) return i;
        }
        return -1;
    }

    // ---- DOM helpers ----

    private static Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Exception ignore) {}
        try { f.setFeature("http://xml.org/sax/features/external-general-entities", false); } catch (Exception ignore) {}
        try { f.setFeature("http://xml.org/sax/features/external-parameter-entities", false); } catch (Exception ignore) {}
        DocumentBuilder b = f.newDocumentBuilder();
        return b.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static void setChildText(Document doc, Element root, String tag, String text) {
        Element el = firstChild(root, tag);
        if (el == null) {
            el = doc.createElement(tag);
            root.insertBefore(el, root.getFirstChild());
        }
        while (el.getFirstChild() != null) el.removeChild(el.getFirstChild());
        el.appendChild(doc.createTextNode(text));
    }

    private static void setVariable(Document doc, Element root, String key, String val) {
        Element vars = firstChild(root, "variables");
        if (vars == null) {
            vars = doc.createElement("variables");
            Element desc = firstChild(root, "description");
            if (desc != null && desc.getNextSibling() != null) root.insertBefore(vars, desc.getNextSibling());
            else root.insertBefore(vars, root.getFirstChild());
        }
        NodeList varNodes = vars.getElementsByTagName("var");
        for (int i = 0; i < varNodes.getLength(); i++) {
            Element v = (Element) varNodes.item(i);
            if (key.equals(v.getAttribute("name"))) { v.setAttribute("value", val); return; }
        }
        Element v = doc.createElement("var");
        v.setAttribute("name", key);
        v.setAttribute("value", val);
        vars.appendChild(v);
    }

    private static Element firstChild(Element parent, String tag) {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && tag.equals(n.getNodeName())) return (Element) n;
        }
        return null;
    }

    private static String serialize(Document doc) throws Exception {
        stripWhitespace(doc.getDocumentElement());
        Transformer t = TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(OutputKeys.INDENT, "yes");
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");
        StringWriter sw = new StringWriter();
        t.transform(new DOMSource(doc), new StreamResult(sw));
        return sw.toString();
    }

    private static void stripWhitespace(Node node) {
        NodeList kids = node.getChildNodes();
        for (int i = kids.getLength() - 1; i >= 0; i--) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.TEXT_NODE) {
                if (n.getNodeValue() == null || n.getNodeValue().trim().isEmpty()) node.removeChild(n);
            } else if (n.getNodeType() == Node.ELEMENT_NODE) {
                stripWhitespace(n);
            }
        }
    }

    // ---- CSV (quote-aware: embedded delimiters/quotes/newlines; "" -> ") ----

    static List<List<String>> parseCsv(String text, char delim) {
        List<List<String>> rows = new ArrayList<List<String>>();
        if (text == null) return rows;
        List<String> cur = new ArrayList<String>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') { field.append('"'); i++; }
                    else inQuotes = false;
                } else field.append(c);
            } else {
                if (c == '"') inQuotes = true;
                else if (c == delim) { cur.add(field.toString()); field.setLength(0); }
                else if (c == '\n') { cur.add(field.toString()); field.setLength(0); rows.add(cur); cur = new ArrayList<String>(); }
                else if (c == '\r') { /* handled with \n */ }
                else field.append(c);
            }
        }
        if (field.length() > 0 || !cur.isEmpty()) { cur.add(field.toString()); rows.add(cur); }
        return rows;
    }

    private static boolean allBlank(List<String> row) {
        for (String s : row) if (s != null && !s.trim().isEmpty()) return false;
        return true;
    }

    private static String cell(List<String> row, int idx) {
        if (idx < 0 || idx >= row.size()) return null;
        return row.get(idx);
    }

    private BulkWorkflowGenerator() {}
}
