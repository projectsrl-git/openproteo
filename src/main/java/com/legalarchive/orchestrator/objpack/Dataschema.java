package com.legalarchive.orchestrator.objpack;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the feed's shared {@code dataschema.json}.
 *
 * <p>Measured rather than assumed: this repository's {@code samples/dataschema.json} and the
 * Transarch "correct txt schema for Metadata example" have the <b>same</b> shape —
 * {@code [{"name":…,"nullable":…,"type":"string"}, …]}. So the schema a feed already ships is
 * directly usable as the pre-flight contract for the metadata CSV, with no conversion.
 *
 * <p>It is <b>not</b> a submission member. It appears in no tar listing in the specification, and
 * adding it would put an unexpected file in the package.
 *
 * <p>The parse is deliberately small and tolerant of surrounding whitespace and of the file being
 * a bare comma-separated run of objects without the enclosing brackets, which is how the Transarch
 * page prints it. It reads three keys and ignores everything else: a schema is a contract about
 * column names, order and nullability, and inventing meaning for other keys would be guessing.
 */
public final class Dataschema {

    /** One declared column. */
    public static final class Column {
        public final String name;
        public final boolean nullable;
        public final String type;

        Column(String name, boolean nullable, String type) {
            this.name = name;
            this.nullable = nullable;
            this.type = type;
        }
    }

    private final List<Column> columns;

    private Dataschema(List<Column> columns) {
        this.columns = columns;
    }

    public List<Column> columns() {
        return columns;
    }

    public int size() {
        return columns.size();
    }

    public List<String> names() {
        List<String> out = new ArrayList<String>(columns.size());
        for (Column c : columns) {
            out.add(c.name);
        }
        return out;
    }

    public static Dataschema read(File f) throws IOException {
        if (f == null || !f.isFile()) {
            throw new ObjPackException("dataschema not found: " + (f == null ? "null" : f.getAbsolutePath()));
        }
        byte[] raw = readAll(f);
        String s = new String(raw, Charset.forName("UTF-8"));
        if (s.length() > 0 && s.charAt(0) == '\uFEFF') {
            s = s.substring(1);
        }
        List<Column> out = new ArrayList<Column>();
        int i = 0;
        while (true) {
            int open = s.indexOf('{', i);
            if (open < 0) {
                break;
            }
            int close = s.indexOf('}', open);
            if (close < 0) {
                throw new ObjPackException("dataschema has an unterminated object at character "
                        + open + ": " + f.getName());
            }
            String obj = s.substring(open + 1, close);
            String name = stringValue(obj, "name");
            if (name == null) {
                throw new ObjPackException("dataschema entry " + (out.size() + 1)
                        + " has no \"name\": " + f.getName());
            }
            String nul = rawValue(obj, "nullable");
            String type = stringValue(obj, "type");
            out.add(new Column(name, nul == null || "true".equalsIgnoreCase(nul.trim()),
                    type == null ? "string" : type));
            i = close + 1;
        }
        if (out.isEmpty()) {
            throw new ObjPackException("dataschema declares no columns: " + f.getAbsolutePath());
        }
        return new Dataschema(out);
    }

    /** The value of a quoted string key, or null when the key is absent. */
    private static String stringValue(String obj, String key) {
        String v = rawValue(obj, key);
        if (v == null) {
            return null;
        }
        v = v.trim();
        if (v.length() >= 2 && v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"') {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    /** The raw text after {@code "key" :} up to the next comma at depth zero. */
    private static String rawValue(String obj, String key) {
        String needle = "\"" + key + "\"";
        int k = obj.indexOf(needle);
        if (k < 0) {
            return null;
        }
        int colon = obj.indexOf(':', k + needle.length());
        if (colon < 0) {
            return null;
        }
        int i = colon + 1;
        boolean inStr = false;
        StringBuilder sb = new StringBuilder();
        while (i < obj.length()) {
            char c = obj.charAt(i);
            if (c == '"') {
                inStr = !inStr;
            }
            if (c == ',' && !inStr) {
                break;
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private static byte[] readAll(File f) throws IOException {
        java.io.InputStream in = new java.io.FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) {
                bo.write(b, 0, n);
            }
            return bo.toByteArray();
        } finally {
            in.close();
        }
    }
}
