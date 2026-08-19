package com.legalarchive.orchestrator.elar;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Compares new output against the legacy tool's, semantically.
 *
 * <p><b>Why not byte-identical.</b> That criterion is unattainable by construction rather than by
 * choice: the filename counter derives from wall-clock time so no two runs agree, the
 * {@code maxLineLength} default moved, line breaks now fall at safe boundaries instead of blind
 * offsets, and correct XML escaping differs from the legacy output wherever the legacy output was
 * wrong. A criterion that can never be met is not a criterion.
 *
 * <p><b>What is compared instead.</b> Line breaks are stripped from both sides and each is re-parsed,
 * so wrapping differences cannot register. Then: the set of document ids; per document the emitted
 * tags and their values, template constants included; per document the digest and the decoded
 * content, checked against the source file itself rather than against the other side, so a shared
 * mistake cannot pass; the document count, the batch count and the distribution of documents across
 * batches; and the PULL structurally with the index name substituted.
 *
 * <p><b>Run it with the same batching rule the legacy tool had</b> - {@code batchBy=DOCUMENTS}, which
 * is the default. Under {@code BYTES} the distribution comparison is meaningless by construction,
 * because that trigger did not exist.
 */
public final class ElarEquivalence {

    public static final class Report {
        public final List<String> differences = new ArrayList<String>();
        public final List<String> notes = new ArrayList<String>();
        public int documentsCompared = 0;
        public int legacyBatches = 0;
        public int newBatches = 0;

        public boolean equivalent() { return differences.isEmpty(); }

        void diff(String s) { differences.add(s); }
        void note(String s) { notes.add(s); }

        public String message() {
            StringBuilder sb = new StringBuilder();
            sb.append(equivalent() ? "EQUIVALENT" : "NOT EQUIVALENT").append(": ")
              .append(documentsCompared).append(" document(s), ")
              .append(legacyBatches).append(" legacy batch(es) vs ").append(newBatches).append(" new");
            for (int i = 0; i < notes.size(); i++) sb.append(nl()).append("  note: ").append(notes.get(i));
            for (int i = 0; i < differences.size(); i++) sb.append(nl()).append("  DIFF: ").append(differences.get(i));
            return sb.toString();
        }
    }

    private static String nl() { return String.valueOf((char) 10); }

    /** One document, flattened to tag -> value. */
    static final class Doc {
        final Map<String, String> tags = new LinkedHashMap<String, String>();
        String file;
    }

    private ElarEquivalence() { }

    /**
     * @param legacyDir  where the legacy JAR wrote its INDX/PULL pairs
     * @param newDir     where this executor wrote its own
     * @param docIdTag   the tag carrying the document id
     * @param contentTag the tag carrying the Base64 payload
     * @param hashTag    the tag carrying the digest
     * @param sourceDir  the content directory, so payloads are checked against the real files
     */
    public static Report compare(File legacyDir, File newDir, String docIdTag, String contentTag,
                                 String hashTag, File sourceDir) throws Exception {
        Report rep = new Report();

        List<File> legacyIdx = indexFiles(legacyDir);
        List<File> newIdx = indexFiles(newDir);
        rep.legacyBatches = legacyIdx.size();
        rep.newBatches = newIdx.size();

        Map<String, Doc> legacy = new LinkedHashMap<String, Doc>();
        Map<String, Doc> fresh = new LinkedHashMap<String, Doc>();
        List<Integer> legacyDist = new ArrayList<Integer>();
        List<Integer> newDist = new ArrayList<Integer>();

        readAll(legacyIdx, docIdTag, contentTag, legacy, legacyDist, rep, "legacy");
        readAll(newIdx, docIdTag, contentTag, fresh, newDist, rep, "new");

        // --- the document set ---
        Set<String> onlyLegacy = new LinkedHashSet<String>(legacy.keySet());
        onlyLegacy.removeAll(fresh.keySet());
        Set<String> onlyNew = new LinkedHashSet<String>(fresh.keySet());
        onlyNew.removeAll(legacy.keySet());
        if (!onlyLegacy.isEmpty()) rep.diff(onlyLegacy.size() + " document(s) only in the legacy output: " + first(onlyLegacy));
        if (!onlyNew.isEmpty()) rep.diff(onlyNew.size() + " document(s) only in the new output: " + first(onlyNew));
        if (legacy.size() != fresh.size()) {
            rep.diff("document count differs: legacy " + legacy.size() + ", new " + fresh.size());
        }

        // --- the batch shape ---
        if (!legacyDist.equals(newDist)) {
            rep.diff("documents are distributed across batches differently: legacy " + legacyDist
                    + ", new " + newDist + " (run with batchBy=DOCUMENTS, the only rule legacy had)");
        }

        // --- per document ---
        for (Map.Entry<String, Doc> e : legacy.entrySet()) {
            Doc l = e.getValue();
            Doc n = fresh.get(e.getKey());
            if (n == null) continue;
            rep.documentsCompared++;

            Set<String> tags = new LinkedHashSet<String>(l.tags.keySet());
            tags.addAll(n.tags.keySet());
            for (String tag : tags) {
                if (contentTag.equals(tag)) continue;              // compared as bytes below
                String lv = l.tags.get(tag), nv = n.tags.get(tag);
                if (lv == null) { rep.diff(e.getKey() + ": <" + tag + "> only in the new output"); continue; }
                if (nv == null) { rep.diff(e.getKey() + ": <" + tag + "> missing from the new output"); continue; }
                if (!lv.equals(nv)) {
                    if (hashTag.equals(tag)) {
                        rep.diff(e.getKey() + ": the digest differs");
                    } else {
                        // values can carry customer data, so the tag is named and the value is not
                        rep.diff(e.getKey() + ": <" + tag + "> differs");
                    }
                }
            }

            // the payload is checked against the SOURCE FILE, not against the other side: a mistake
            // both tools share would otherwise compare equal and pass
            String b64 = n.tags.get(contentTag);
            String hash = n.tags.get(hashTag);
            if (b64 != null && sourceDir != null) {
                byte[] decoded;
                try {
                    decoded = Base64.getDecoder().decode(b64.replaceAll("\\s", ""));
                } catch (RuntimeException ex) {
                    rep.diff(e.getKey() + ": the embedded payload is not valid Base64");
                    continue;
                }
                File src = n.file == null ? null : new File(sourceDir, n.file);
                if (src != null && src.isFile()) {
                    byte[] real = Files.readAllBytes(src.toPath());
                    if (!Arrays.equals(decoded, real)) {
                        rep.diff(e.getKey() + ": the decoded payload does not match " + src.getName());
                    }
                    String expect = ContentEmbedder.hex(MessageDigest.getInstance("SHA-256").digest(real));
                    if (hash != null && !expect.equalsIgnoreCase(hash)) {
                        rep.diff(e.getKey() + ": the digest is not the SHA-256 of " + src.getName() + "'s raw bytes");
                    }
                } else {
                    String d = ContentEmbedder.hex(MessageDigest.getInstance("SHA-256").digest(decoded));
                    if (hash != null && !d.equalsIgnoreCase(hash)) {
                        rep.diff(e.getKey() + ": the digest does not match its own embedded payload");
                    } else {
                        rep.note(e.getKey() + ": source file not found, so the payload was only checked"
                                + " against its own digest");
                    }
                }
            }
        }

        // --- the PULLs ---
        List<File> lp = pullFiles(legacyDir), np = pullFiles(newDir);
        if (lp.size() != np.size()) {
            rep.diff("PULL count differs: legacy " + lp.size() + ", new " + np.size());
        }
        for (int i = 0; i < Math.min(lp.size(), np.size()); i++) {
            String a = structure(parse(lp.get(i)));
            String b = structure(parse(np.get(i)));
            if (!a.equals(b)) rep.diff("PULL " + np.get(i).getName() + " differs structurally from the legacy one");
        }
        return rep;
    }

    private static String first(Set<String> s) {
        int n = 0;
        StringBuilder sb = new StringBuilder();
        for (String x : s) { if (n++ == 5) { sb.append(", ..."); break; } sb.append(n == 1 ? "" : ", ").append(x); }
        return sb.toString();
    }

    private static void readAll(List<File> files, String docIdTag, String contentTag,
                                Map<String, Doc> into, List<Integer> dist, Report rep, String side) throws Exception {
        for (int i = 0; i < files.size(); i++) {
            Document d = parse(files.get(i));
            List<Element> blocks = docBlocks(d.getDocumentElement(), docIdTag);
            dist.add(Integer.valueOf(blocks.size()));
            for (int b = 0; b < blocks.size(); b++) {
                Doc doc = new Doc();
                collect(blocks.get(b), doc.tags);
                String id = doc.tags.get(docIdTag);
                if (id == null) { rep.diff(side + " " + files.get(i).getName() + ": a document has no " + docIdTag); continue; }
                doc.file = guessFileName(doc.tags, contentTag);
                if (into.put(id, doc) != null) rep.diff(side + ": document id " + id + " appears more than once");
            }
        }
    }

    /**
     * A file name for the payload, when a tag carries one. Not every family does, and the comparison
     * degrades to checking the payload against its own digest rather than failing.
     */
    private static String guessFileName(Map<String, String> tags, String contentTag) {
        for (Map.Entry<String, String> e : tags.entrySet()) {
            if (contentTag.equals(e.getKey())) continue;
            String v = e.getValue();
            if (v != null && v.length() > 4 && v.indexOf('.') > 0 && v.indexOf(' ') < 0
                    && v.length() < 260 && !v.contains("/") && !v.contains("\\")) {
                String lower = v.toLowerCase();
                if (lower.endsWith(".pdf") || lower.endsWith(".tif") || lower.endsWith(".tiff")
                        || lower.endsWith(".xml") || lower.endsWith(".txt")) return v;
            }
        }
        return null;
    }

    /**
     * The repeated per-document blocks.
     *
     * Found by walking UP from each id element for as long as the ancestor still contains exactly one
     * of them, and stopping at the first ancestor that contains two or more. That ancestor is the
     * container, and the element below it is the block - whatever the family happens to call it, and
     * however deeply the id sits inside it.
     *
     * The obvious alternative, descending to the element that contains the id tag, finds the id tag
     * itself: every ancestor "contains" it too, so the recursion has no reason to stop earlier. That
     * is what this did first, and it made every document look as though it carried one field.
     */
    static List<Element> docBlocks(Element root, String docIdTag) {
        List<Element> ids = new ArrayList<Element>();
        collectNamed(root, docIdTag, ids);
        List<Element> out = new ArrayList<Element>();
        for (int i = 0; i < ids.size(); i++) {
            Element block = ids.get(i);
            while (true) {
                Node p = block.getParentNode();
                if (p == null || p.getNodeType() != Node.ELEMENT_NODE) break;
                Element parent = (Element) p;
                if (countNamed(parent, docIdTag) != 1) break;   // the parent is the container
                block = parent;
            }
            if (!out.contains(block)) out.add(block);
        }
        return out;
    }
    private static void collectNamed(Element e, String tag, List<Element> out) {
        if (tag.equals(e.getNodeName())) { out.add(e); return; }
        List<Element> kids = children(e);
        for (int i = 0; i < kids.size(); i++) collectNamed(kids.get(i), tag, out);
    }
    private static int countNamed(Element e, String tag) {
        if (tag.equals(e.getNodeName())) return 1;
        int n = 0;
        List<Element> kids = children(e);
        for (int i = 0; i < kids.size(); i++) n += countNamed(kids.get(i), tag);
        return n;
    }

    private static void collect(Element e, Map<String, String> into) {
        List<Element> kids = children(e);
        if (kids.isEmpty()) {
            into.put(e.getNodeName(), text(e));
            return;
        }
        for (int i = 0; i < kids.size(); i++) collect(kids.get(i), into);
    }

    private static List<Element> children(Element e) {
        List<Element> out = new ArrayList<Element>();
        NodeList n = e.getChildNodes();
        for (int i = 0; i < n.getLength(); i++) if (n.item(i).getNodeType() == Node.ELEMENT_NODE) out.add((Element) n.item(i));
        return out;
    }
    private static String text(Element e) {
        StringBuilder sb = new StringBuilder();
        NodeList n = e.getChildNodes();
        for (int i = 0; i < n.getLength(); i++) {
            short t = n.item(i).getNodeType();
            if (t == Node.TEXT_NODE || t == Node.CDATA_SECTION_NODE) sb.append(n.item(i).getNodeValue());
        }
        return sb.toString().trim();
    }

    /** Element names, nesting and sorted attributes: attribute order and whitespace are normalised away. */
    static String structure(Document d) {
        StringBuilder sb = new StringBuilder();
        struct(d.getDocumentElement(), sb);
        return sb.toString();
    }
    private static void struct(Element e, StringBuilder sb) {
        sb.append('<').append(e.getNodeName());
        Map<String, String> attrs = new TreeMap<String, String>();
        NamedNodeMap m = e.getAttributes();
        for (int i = 0; i < m.getLength(); i++) {
            Attr a = (Attr) m.item(i);
            attrs.put(a.getName(), a.getValue());
        }
        for (Map.Entry<String, String> a : attrs.entrySet()) sb.append(' ').append(a.getKey()).append('=').append(a.getValue());
        sb.append('>');
        String t = text(e);
        if (!t.isEmpty()) sb.append(t);
        List<Element> kids = children(e);
        for (int i = 0; i < kids.size(); i++) struct(kids.get(i), sb);
        sb.append("</").append(e.getNodeName()).append('>');
    }

    /**
     * Reads a file with its line breaks removed before parsing.
     *
     * The legacy wrapper chopped the serialized XML at blind offsets, so a break can fall anywhere,
     * including inside a text node. Stripping them first is what makes the comparison about content
     * rather than about wrapping - and it is also why this cannot be done with a diff.
     */
    static Document parse(File f) throws Exception {
        byte[] raw = Files.readAllBytes(f.toPath());
        String charset = declaredCharset(raw);
        String text = new String(raw, charset);
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != (char) 10 && c != (char) 13) sb.append(c);
        }
        DocumentBuilderFactory bf = DocumentBuilderFactory.newInstance();
        bf.setNamespaceAware(true);
        try { bf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Exception ignored) { }
        return bf.newDocumentBuilder().parse(new ByteArrayInputStream(sb.toString().getBytes("UTF-8")
                .length > 0 ? sb.toString().getBytes("UTF-8") : new byte[0]));
    }

    /** The charset the file itself declares, so it is read as it was written. */
    static String declaredCharset(byte[] raw) {
        int n = Math.min(raw.length, 200);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append((char) (raw[i] & 0xFF));
        String head = sb.toString();
        int i = head.indexOf("encoding=");
        if (i < 0) return "UTF-8";
        int q = head.indexOf('"', i);
        int q2 = q >= 0 ? head.indexOf('"', q + 1) : -1;
        if (q < 0 || q2 < 0) return "UTF-8";
        String cs = head.substring(q + 1, q2).trim();
        try { java.nio.charset.Charset.forName(cs); return cs; } catch (Exception e) { return "UTF-8"; }
    }

    static List<File> indexFiles(File dir) { return listing(dir, "INDX"); }
    static List<File> pullFiles(File dir) { return listing(dir, "PULL"); }
    private static List<File> listing(File dir, String marker) {
        List<File> out = new ArrayList<File>();
        if (dir == null || !dir.isDirectory()) return out;
        File[] all = dir.listFiles();
        if (all == null) return out;
        for (int i = 0; i < all.length; i++) {
            if (all[i].isFile() && all[i].getName().contains(marker)) out.add(all[i]);
        }
        Collections.sort(out, new java.util.Comparator<File>() {
            public int compare(File a, File b) { return a.getName().compareTo(b.getName()); }
        });
        return out;
    }

    /** {@code java ... ElarEquivalence <legacyDir> <newDir> <sourceDir> [docIdTag] [contentTag] [hashTag]} */
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("usage: ElarEquivalence <legacyDir> <newDir> <sourceDir>"
                    + " [docIdTag] [contentTag] [hashTag]");
            System.exit(2);
        }
        String docId = args.length > 3 ? args[3] : "ELAR:RecordId";
        String content = args.length > 4 ? args[4] : "ELAR:Content";
        String hash = args.length > 5 ? args[5] : "ELAR:HashValue";
        Report r = compare(new File(args[0]), new File(args[1]), docId, content, hash, new File(args[2]));
        System.out.println(r.message());
        System.exit(r.equivalent() ? 0 : 1);
    }
}
