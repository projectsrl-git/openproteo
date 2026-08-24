package com.legalarchive.orchestrator.json2csv;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The catalogue of attribute paths offered by the mapper's right-hand dropdown.
 *
 * <p>Built by walking one or more documents and merging what they contain. Takes a
 * {@code Map}/{@code List}/scalar tree, so it is Spring-free, Jackson-free and exercised outside the
 * application like the rest of this package.
 *
 * <h3>A sample is not a schema, and the catalogue says so</h3>
 *
 * A sample instance only reveals attributes that are <b>present in that sample</b>. An empty array
 * hides everything under it; a field absent from one record is absent from the catalogue. Reading a
 * catalogue as complete when it is not is how a column silently ends up empty for a third of a feed.
 *
 * <p>So every entry carries {@link Entry#seenIn}, the number of scanned documents that contained it,
 * against {@link #scanned()}. Seen in 3 of 20 is a different thing from seen in 20 of 20, and the
 * person mapping the column is the only one who can tell which is expected. The dropdown shows the
 * count; it does not decide for anybody.
 */
public final class PathCatalog {

    public static final class Entry {
        /** The path, in the syntax {@link JsonPath} parses — already quoted where a key needs it. */
        public final String path;
        /** How many scanned documents contained it. */
        public int seenIn;
        /** The scalar kinds seen: "string", "number", "boolean", "null", or "" for a container. */
        public final List<String> kinds = new ArrayList<String>();
        /** True when the path is inside an array: unbounded, and therefore not selectable. */
        public boolean inArray;
        /** True when the path is an array or an object rather than a value. */
        public boolean container;
        /** Set when the path cannot be mapped, in words the person can act on. */
        public String unavailable;

        Entry(String path) { this.path = path; }

        public String kind() {
            if (kinds.isEmpty()) return "";
            if (kinds.size() == 1) return kinds.get(0);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < kinds.size(); i++) { if (i > 0) sb.append('/'); sb.append(kinds.get(i)); }
            return sb.toString();
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<String, Entry>();
    private int scanned;

    public int scanned() { return scanned; }

    public List<Entry> entries() { return new ArrayList<Entry>(entries.values()); }

    /** Walks one document and merges its paths into the catalogue. */
    public void add(Object document) {
        scanned++;
        java.util.Set<String> here = new java.util.HashSet<String>();
        walk(document, "", here, false, 0);
    }

    private static final int MAX_DEPTH = 24;

    private void walk(Object node, String prefix, java.util.Set<String> here, boolean inArray, int depth) {
        if (depth > MAX_DEPTH) return;
        if (node instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) node;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String key = String.valueOf(e.getKey());
                String p = prefix.isEmpty() ? quote(key) : prefix + "." + quote(key);
                Object v = e.getValue();
                if (v instanceof Map) {
                    note(p, here, inArray, true, null);
                    walk(v, p, here, inArray, depth + 1);
                } else if (v instanceof List) {
                    array(p, (List<?>) v, here, inArray, depth);
                } else {
                    note(p, here, inArray, false, kindOf(v));
                }
            }
            return;
        }
        if (node instanceof List) {
            array(prefix.isEmpty() ? "<root>" : prefix, (List<?>) node, here, inArray, depth);
        }
    }

    /**
     * An array yields two things: the unbounded {@code []} path, recorded as NOT selectable, and —
     * when the array holds objects — the paths of its first element under {@code [0]}, which ARE
     * selectable.
     *
     * <p>The second is there because the real documents carry arrays of exactly one object. Offering
     * only {@code []} would leave those values unreachable and send somebody to type the index by
     * hand; offering {@code [0]} silently as if it were the whole array would be the mistake §6.2
     * exists to prevent. Both are listed, and only one can be picked.
     */
    private void array(String p, List<?> list, java.util.Set<String> here, boolean inArray, int depth) {
        Entry any = note(p + "[]", here, true, true, null);
        any.unavailable = "[] asks for one row per element and multi-row flattening is not implemented";
        if (list.isEmpty()) return;
        Object first = list.get(0);
        String idx = p + "[0]";
        if (first instanceof Map) {
            note(idx, here, inArray, true, null);
            walk(first, idx, here, inArray, depth + 1);
        } else if (!(first instanceof List)) {
            note(idx, here, inArray, false, kindOf(first));
        }
    }

    private Entry note(String path, java.util.Set<String> here, boolean inArray, boolean container, String kind) {
        Entry e = entries.get(path);
        if (e == null) { e = new Entry(path); entries.put(path, e); }
        if (here.add(path)) e.seenIn++;
        if (inArray) e.inArray = true;
        if (container) {
            e.container = true;
            if (e.unavailable == null) e.unavailable = "this is an object or an array, not a value";
        } else if (kind != null && !e.kinds.contains(kind)) {
            e.kinds.add(kind);
        }
        return e;
    }

    private static String kindOf(Object v) {
        if (v == null) return "null";
        if (v instanceof Boolean) return "boolean";
        if (v instanceof BigDecimal || v instanceof Number) return "number";
        return "string";
    }

    /**
     * Quotes a key that {@link JsonPath} could not otherwise read back.
     *
     * <p>The real documents carry keys like {@code VM.CAP.DATE.CHARGE} and {@code VM.ALT.ACCT.TYPE} —
     * dots inside the name, not nesting. Emitting {@code VM.CAP.DATE.CHARGE} bare would parse as four
     * nested keys and resolve to nothing at all, on a path the dropdown itself handed over. This is
     * why the catalogue produces the quoted form rather than leaving it to be typed.
     */
    static String quote(String key) {
        if (key.indexOf('.') < 0 && key.indexOf('[') < 0 && key.indexOf(']') < 0 && key.indexOf('\'') < 0) {
            return key;
        }
        return "['" + key.replace("'", "''") + "']";
    }

    /**
     * Names shared by a dataschema column and a JSON attribute, for the mapper's "map by exact name".
     *
     * <p>The two vocabularies are the same one in this feed — {@code ACCOUNT_NUMBER},
     * {@code CS_CIF_BRN}, {@code POSTING_RESTRICT_DESCRIPTION} — and there are about a hundred of
     * them. Matching is exact and case-sensitive, because JSON keys are, and because a near-match
     * offered as a match is worse than no suggestion: it would be accepted without being read.
     *
     * @return dataschema column name to attribute path, for the columns that match
     */
    public Map<String, String> exactMatches(List<String> dataschemaColumns) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (dataschemaColumns == null) return out;
        for (int i = 0; i < dataschemaColumns.size(); i++) {
            String col = dataschemaColumns.get(i);
            if (col == null) continue;
            Entry e = entries.get(quote(col));
            if (e != null && !e.container && e.unavailable == null && !e.inArray) {
                out.put(col, e.path);
            }
        }
        return out;
    }
}
