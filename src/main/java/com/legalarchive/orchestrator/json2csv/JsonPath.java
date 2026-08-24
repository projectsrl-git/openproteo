package com.legalarchive.orchestrator.json2csv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * One attribute path into a JSON document, parsed once and resolved many times.
 *
 * <p>The tree it walks is {@code Map} / {@code List} / scalar and nothing else — which is exactly
 * what {@code ObjectMapper.readValue(file, Object.class)} returns. Nothing here imports Jackson, and
 * nothing here imports Spring: the whole package compiles with {@code javac --release 8} against the
 * JDK alone, which is what lets it be exercised outside the application.
 *
 * <p>Syntax:
 * <pre>
 *   ndg                      a key at the root
 *   customer.name            a key inside an object
 *   conti[0].iban            an element chosen by an explicit index
 *   data['odd.key'].value    a key that itself contains a dot or a bracket
 *   conti[].iban             PARSED, and refused by MappingValidator — see hasAnyIndex()
 * </pre>
 *
 * <p>{@code []} is understood by the parser and rejected by validation rather than rejected here.
 * That split is deliberate: the day multi-row flattening arrives, the mapping format does not change
 * and no workflow XML has to be rewritten. Until then a path asking for it is refused with its own
 * message instead of being quietly read as {@code [0]}.
 *
 * <p>Keys are matched case-sensitively and exactly, as JSON keys are.
 */
public final class JsonPath {

    /** One step of a path. */
    public static final class Segment {
        public static final int KEY = 0;
        public static final int INDEX = 1;
        public static final int ANY_INDEX = 2;

        public final int kind;
        public final String key;    // KEY only
        public final int index;     // INDEX only

        private Segment(int kind, String key, int index) {
            this.kind = kind; this.key = key; this.index = index;
        }

        static Segment key(String k) { return new Segment(KEY, k, -1); }
        static Segment index(int i) { return new Segment(INDEX, null, i); }
        static Segment anyIndex() { return new Segment(ANY_INDEX, null, -1); }

        @Override public String toString() {
            if (kind == KEY) return key;
            if (kind == INDEX) return "[" + index + "]";
            return "[]";
        }
    }

    /**
     * What resolving a path against a document produced.
     *
     * <p>Three outcomes, and the difference between the last two is the whole point:
     * <ul>
     *   <li>{@link #FOUND} — a scalar, ready to be written.</li>
     *   <li>{@link #ABSENT} — <b>the document does not have it</b>: a key that is not there, an index
     *       past the end of an array, or an explicit JSON null. That is data, and it writes empty.</li>
     *   <li>{@link #MISMATCH} — <b>the document is not shaped the way the path assumes</b>: a key
     *       applied to something that is not an object, an index applied to something that is not an
     *       array, or a leaf that turned out to be an object or an array. That is a wrong mapping,
     *       and by default it stops the run.</li>
     * </ul>
     * Folding the two together would be the expensive mistake here: a mapping typo would deliver an
     * empty column for the whole feed and look exactly like a customer who happens to have no value.
     */
    public static final class Resolution {
        public static final int FOUND = 0;
        public static final int ABSENT = 1;
        public static final int MISMATCH = 2;

        public final int status;
        public final Object value;   // FOUND only
        public final String where;   // MISMATCH only: the path prefix at which the shape disagreed
        public final String found;   // MISMATCH only: what was there instead

        private Resolution(int status, Object value, String where, String found) {
            this.status = status; this.value = value; this.where = where; this.found = found;
        }

        static Resolution found(Object v) { return new Resolution(FOUND, v, null, null); }
        static Resolution absent() { return new Resolution(ABSENT, null, null, null); }
        static Resolution mismatch(String where, String found) {
            return new Resolution(MISMATCH, null, where, found);
        }
    }

    private final String text;
    private final List<Segment> segments;
    private final boolean anyIndex;

    private JsonPath(String text, List<Segment> segments, boolean anyIndex) {
        this.text = text;
        this.segments = Collections.unmodifiableList(segments);
        this.anyIndex = anyIndex;
    }

    public String text() { return text; }
    public List<Segment> segments() { return segments; }

    /** True when the path contains an unbounded {@code []}. Validation refuses those; see the class doc. */
    public boolean hasAnyIndex() { return anyIndex; }

    @Override public String toString() { return text; }

    // ------------------------------------------------------------------ parse

    /**
     * @throws Json2CsvException with a message naming the position, never a bare
     *         StringIndexOutOfBounds: these strings are typed by hand in the designer.
     */
    public static JsonPath parse(String raw) {
        if (raw == null) throw new Json2CsvException("path is missing");
        String s = raw.trim();
        if (s.isEmpty()) throw new Json2CsvException("path is empty");

        List<Segment> out = new ArrayList<Segment>();
        boolean any = false;
        int i = 0, n = s.length();

        while (true) {
            int start = i;
            while (i < n && s.charAt(i) != '.' && s.charAt(i) != '[') {
                char c = s.charAt(i);
                if (c == ']') throw new Json2CsvException(err(s, i, "']' without a matching '['"));
                if (c == '\'') throw new Json2CsvException(err(s, i, "a quote outside brackets; write a key with a dot in it as ['my.key']"));
                i++;
            }
            String name = s.substring(start, i);
            if (!name.isEmpty()) {
                out.add(Segment.key(name));
            } else if (i >= n || s.charAt(i) != '[') {
                throw new Json2CsvException(err(s, i, "an empty key"));
            }

            while (i < n && s.charAt(i) == '[') {
                i++;                                            // past '['
                if (i >= n) throw new Json2CsvException(err(s, i, "'[' is never closed"));
                char c = s.charAt(i);
                if (c == ']') {
                    out.add(Segment.anyIndex());
                    any = true;
                    i++;
                } else if (c == '\'') {
                    i++;                                        // past the opening quote
                    StringBuilder k = new StringBuilder();
                    boolean closed = false;
                    while (i < n) {
                        char q = s.charAt(i);
                        if (q == '\'') {
                            if (i + 1 < n && s.charAt(i + 1) == '\'') { k.append('\''); i += 2; continue; }
                            i++; closed = true; break;
                        }
                        k.append(q); i++;
                    }
                    if (!closed) throw new Json2CsvException(err(s, i, "a quoted key is never closed"));
                    if (i >= n || s.charAt(i) != ']') throw new Json2CsvException(err(s, i, "expected ']' after a quoted key"));
                    i++;
                    if (k.length() == 0) throw new Json2CsvException(err(s, i, "an empty quoted key"));
                    out.add(Segment.key(k.toString()));
                } else {
                    int ds = i;
                    while (i < n && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
                    if (i == ds) throw new Json2CsvException(err(s, i, "expected a number, ']' or a quoted key after '['"));
                    if (i >= n || s.charAt(i) != ']') throw new Json2CsvException(err(s, i, "'[' is never closed"));
                    String digits = s.substring(ds, i);
                    i++;
                    long v;
                    try { v = Long.parseLong(digits); }
                    catch (NumberFormatException e) { throw new Json2CsvException(err(s, ds, "index '" + digits + "' is not a number")); }
                    if (v > Integer.MAX_VALUE) throw new Json2CsvException(err(s, ds, "index " + digits + " is too large"));
                    out.add(Segment.index((int) v));
                }
            }

            if (i >= n) break;
            if (s.charAt(i) == '.') {
                i++;
                if (i >= n) throw new Json2CsvException(err(s, i, "the path ends with '.'"));
                continue;
            }
            throw new Json2CsvException(err(s, i, "unexpected '" + s.charAt(i) + "'"));
        }

        if (out.isEmpty()) throw new Json2CsvException("path '" + s + "' has no segments");
        return new JsonPath(s, out, any);
    }

    private static String err(String s, int pos, String what) {
        return "path '" + s + "' at position " + (pos + 1) + ": " + what;
    }

    // ---------------------------------------------------------------- resolve

    /**
     * Walks the path. Never throws for a shape it does not like — that is reported as
     * {@link Resolution#MISMATCH} so the caller's policy decides, and so the message can name the
     * column and the file the caller knows about and this class does not.
     */
    public Resolution resolve(Object root) {
        if (anyIndex) {
            // Unreachable through the executor: MappingValidator refuses these before a file is read.
            // Loud rather than silent, because reading [] as [0] is precisely the failure being avoided.
            throw new Json2CsvException("path '" + text + "' contains '[]' and cannot be resolved: "
                    + "multi-row flattening is not implemented");
        }
        Object cur = root;
        StringBuilder walked = new StringBuilder();
        for (int k = 0; k < segments.size(); k++) {
            Segment seg = segments.get(k);
            if (cur == null) return Resolution.absent();
            if (seg.kind == Segment.KEY) {
                if (!(cur instanceof Map)) {
                    return Resolution.mismatch(prefix(walked), describe(cur));
                }
                Map<?, ?> m = (Map<?, ?>) cur;
                if (!m.containsKey(seg.key)) return Resolution.absent();
                cur = m.get(seg.key);
                if (walked.length() > 0) walked.append('.');
                walked.append(seg.key);
            } else {
                if (!(cur instanceof List)) {
                    return Resolution.mismatch(prefix(walked), describe(cur));
                }
                List<?> l = (List<?>) cur;
                if (seg.index >= l.size()) return Resolution.absent();
                cur = l.get(seg.index);
                walked.append('[').append(seg.index).append(']');
            }
        }
        if (cur == null) return Resolution.absent();
        if (cur instanceof Map || cur instanceof List) {
            return Resolution.mismatch(text, describe(cur));
        }
        return Resolution.found(cur);
    }

    private static String prefix(StringBuilder walked) {
        return walked.length() == 0 ? "<root>" : walked.toString();
    }

    /** What was found there, in words, and never the value itself: these are banking documents. */
    static String describe(Object o) {
        if (o == null) return "null";
        if (o instanceof Map) return "an object";
        if (o instanceof List) return "an array of " + ((List<?>) o).size();
        if (o instanceof Boolean) return "a boolean";
        if (o instanceof CharSequence) return "a string";
        return "a number";
    }
}
