package com.legalarchive.orchestrator.objpack;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * The submission's audit file, in the exact shape §3.1.1 of the specification prints.
 *
 * <p>The types are not uniform and the asymmetry is in the specification, not here:
 * {@code transmission_date} and {@code record_count} are JSON <b>numbers</b>, while
 * {@code sequence_number}, {@code version_number} and {@code object_id} are JSON <b>strings</b>.
 * {@code TargetDestination} is the only key in PascalCase. Writing this by hand rather than through
 * a mapper is what makes that asymmetry visible and deliberate instead of whatever a serialiser
 * happened to do with the field types.
 *
 * <p>{@link #validate} re-reads what was just written and checks it against the objects it was
 * built from. The PowerShell script does the same, and it is worth keeping: it catches an audit
 * file that serialised cleanly but disagrees with the package around it, which is precisely the
 * failure Transarch's ingestion validation rejects.
 */
public final class AuditJson {

    /** One entry of {@code submission_object_files}. */
    public static final class Entry {
        public final String fileName;
        public final String mimeType;
        public final int objectId;

        public Entry(String fileName, String mimeType, int objectId) {
            this.fileName = fileName;
            this.mimeType = mimeType;
            this.objectId = objectId;
        }
    }

    private AuditJson() {
    }

    /**
     * Writes the audit file as UTF-8 without BOM.
     *
     * @param targetDestination required by §3.1 for TAR-packaged submissions; never written as an
     *                          empty string, because an empty endpoint is worse than a refusal
     */
    public static void write(File out, SubmissionName name, String targetDestination,
                             List<Entry> entries) throws IOException {
        if (targetDestination == null || targetDestination.trim().isEmpty()) {
            throw new ObjPackException("targetDestination is required for a TAR-packaged submission (spec 3.1)");
        }
        if (entries == null || entries.isEmpty()) {
            throw new ObjPackException("the audit file needs at least one object");
        }
        // Written as it is produced. Built whole in memory first, the text of a 100 000-object
        // audit (16 MB) took several times its size in heap for a moment, at the point where the
        // packaging needs the least.
        java.io.Writer sb = new java.io.BufferedWriter(
                new java.io.OutputStreamWriter(new FileOutputStream(out), Charset.forName("UTF-8")), 1 << 16);
        try {
            sb.append("{\n");
            sb.append("    \"transmission_date\" : ").append(Long.toString(Long.parseLong(name.transmissionDate()))).append(",\n");
            sb.append("    \"sequence_number\" : \"").append(pad3(name.sequenceNr())).append("\",\n");
            sb.append("    \"version_number\" : \"").append(pad3(name.versionNr())).append("\",\n");
            sb.append("    \"record_count\" : ").append(Integer.toString(entries.size())).append(",\n");
            sb.append("    \"TargetDestination\": \"").append(esc(targetDestination.trim())).append("\",\n");
            sb.append("    \"metadata_file_name\" : \"").append(esc(name.metadataCsv())).append("\",\n");
            sb.append("    \"submission_object_files\" : [\n");
            for (int i = 0; i < entries.size(); i++) {
                Entry e = entries.get(i);
                sb.append("        {\n");
                sb.append("            \"file_name\" : \"").append(esc(e.fileName)).append("\",\n");
                sb.append("            \"mime_type\" : \"").append(esc(e.mimeType)).append("\",\n");
                sb.append("            \"object_id\" : \"").append(Integer.toString(e.objectId)).append("\"\n");
                sb.append("        }").append(i == entries.size() - 1 ? "\n" : ",\n");
            }
            sb.append("    ]\n");
            sb.append("}\n");
            sb.flush();
        } finally {
            sb.close();
        }
    }

    /**
     * Reads the file back and checks it against what it should contain.
     *
     * @throws ObjPackException naming the disagreement, not merely reporting that there is one
     */
    public static void validate(File auditFile, List<Entry> expected) throws IOException {
        String s = new String(readAll(auditFile), Charset.forName("UTF-8"));

        long recordCount = number(s, "record_count", auditFile);
        if (recordCount != expected.size()) {
            throw new ObjPackException("the audit file declares record_count=" + recordCount
                    + " but the submission has " + expected.size() + " objects: " + auditFile.getName());
        }
        long transmission = number(s, "transmission_date", auditFile);
        if (Long.toString(transmission).length() != 8) {
            throw new ObjPackException("the audit file's transmission_date is not 8 digits: " + transmission);
        }

        List<String> names = stringsOf(s, "file_name");
        if (names.size() != expected.size()) {
            throw new ObjPackException("the audit file lists " + names.size()
                    + " object files but the submission has " + expected.size() + ": " + auditFile.getName());
        }
        for (int i = 0; i < expected.size(); i++) {
            if (!names.get(i).equals(expected.get(i).fileName)) {
                throw new ObjPackException("the audit file's object " + (i + 1) + " is '" + names.get(i)
                        + "' but the submission has '" + expected.get(i).fileName + "'");
            }
        }
        List<String> ids = stringsOf(s, "object_id");
        if (ids.size() != expected.size()) {
            throw new ObjPackException("the audit file lists " + ids.size() + " object_id values but has "
                    + names.size() + " file names: " + auditFile.getName());
        }
        for (int i = 0; i < expected.size(); i++) {
            if (!ids.get(i).equals(Integer.toString(expected.get(i).objectId))) {
                throw new ObjPackException("the audit file's object_id " + (i + 1) + " is '" + ids.get(i)
                        + "' but the submission assigned " + expected.get(i).objectId);
            }
        }
    }

    // ---------------------------------------------------------------- reading (objunpack)

    /** One item of {@code submission_object_files}, as read. A key the item lacks is null. */
    public static final class FileItem {
        public final String fileName;
        public final String mimeType;
        /** As written: a JSON string's content, or a JSON integer's digits. */
        public final String objectId;

        FileItem(String fileName, String mimeType, String objectId) {
            this.fileName = fileName;
            this.mimeType = mimeType;
            this.objectId = objectId;
        }
    }

    /** An audit file as read by {@link #read}. A key the file lacks is null; nothing is defaulted. */
    public static final class Document {
        public String transmissionDate;
        public String sequenceNumber;
        public String versionNumber;
        public Long recordCount;
        public String targetDestination;
        public String metadataFileName;
        /** Null when the file has no {@code submission_object_files} array. */
        public List<FileItem> files;
    }

    /** Larger than any audit file a 100 000-object submission can have; a guard, not a format rule. */
    static final long MAX_AUDIT_BYTES = 256L * 1024 * 1024;

    /**
     * Reads an audit file written by ANY producer.
     *
     * <p>{@link #validate} is a self-check of what {@link #write} has just produced and relies on
     * that layout: it pairs {@code file_name} and {@code object_id} by their position in the text.
     * This is a JSON parser instead, because the other producer of these files is PowerShell's
     * {@code ConvertTo-Json}, whose indentation differs between versions, whose item keys come out
     * of an unordered hashtable in any order (measured), and which in Windows PowerShell 5.1
     * escapes {@code < > & '} as {@code \\uXXXX}. Each item is therefore read by key.
     *
     * <p>Types follow section 3.1.1 loosely on purpose, since the specification contradicts itself
     * on them (its section 6.5): {@code object_id}, {@code sequence_number}, {@code version_number}
     * and {@code transmission_date} are accepted as a string or as an integer, and returned as
     * text. Anything else - a fraction, an object where a scalar is expected - is refused.
     *
     * @throws ObjPackException naming what is wrong and where
     */
    public static Document read(File auditFile) throws IOException {
        return read(auditFile, auditFile.getName());
    }

    /**
     * As {@link #read(File)}, naming the file as {@code label} in messages: objunpack reads the
     * audit from a staging file whose own name says nothing.
     */
    public static Document read(File auditFile, String label) throws IOException {
        if (auditFile.length() > MAX_AUDIT_BYTES) {
            throw new ObjPackException("the audit file is " + auditFile.length() + " bytes, over the "
                    + MAX_AUDIT_BYTES + " this reader accepts: " + label);
        }
        byte[] bytes = readAll(auditFile);
        int skip = bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF ? 3 : 0;
        String text;
        try {
            text = Charset.forName("UTF-8").newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes, skip, bytes.length - skip)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new ObjPackException("the audit file is not valid UTF-8: " + label, e);
        }
        Object root;
        try {
            root = new Json(text).document();
        } catch (IllegalArgumentException e) {
            throw new ObjPackException("the audit file is not valid JSON (" + e.getMessage() + "): "
                    + label);
        }
        if (!(root instanceof java.util.Map)) {
            throw new ObjPackException("the audit file is not a JSON object: " + label);
        }
        java.util.Map<?, ?> m = (java.util.Map<?, ?>) root;
        Document d = new Document();
        d.transmissionDate = scalar(m, "transmission_date", label);
        d.sequenceNumber = scalar(m, "sequence_number", label);
        d.versionNumber = scalar(m, "version_number", label);
        String rc = scalar(m, "record_count", label);
        if (rc != null) {
            try {
                d.recordCount = Long.valueOf(rc.trim());
            } catch (NumberFormatException e) {
                throw new ObjPackException("the audit file's \"record_count\" is not an integer ('" + rc + "'): "
                        + label);
            }
        }
        d.targetDestination = scalar(m, "TargetDestination", label);
        d.metadataFileName = scalar(m, "metadata_file_name", label);
        Object files = m.get("submission_object_files");
        if (files != null && files != Json.NULL) {
            if (!(files instanceof List)) {
                throw new ObjPackException("the audit file's \"submission_object_files\" is not an array: "
                        + label);
            }
            d.files = new ArrayList<FileItem>();
            int i = 0;
            for (Object o : (List<?>) files) {
                i++;
                if (!(o instanceof java.util.Map)) {
                    throw new ObjPackException("item " + i + " of \"submission_object_files\" is not an object: "
                            + label);
                }
                java.util.Map<?, ?> it = (java.util.Map<?, ?>) o;
                d.files.add(new FileItem(scalar(it, "file_name", label), scalar(it, "mime_type", label),
                        scalar(it, "object_id", label)));
            }
        }
        return d;
    }

    /** A string's content or an integer's digits; null when absent or JSON null. */
    private static String scalar(java.util.Map<?, ?> m, String key, String label) {
        Object v = m.get(key);
        if (v == null || v == Json.NULL) {
            return null;
        }
        if (v instanceof String) {
            return (String) v;
        }
        if (v instanceof Json.Num) {
            String t = ((Json.Num) v).text;
            if (!t.matches("-?[0-9]+")) {
                throw new ObjPackException("the audit file's \"" + key + "\" is a number but not an integer ("
                        + t + "): " + label);
            }
            return t;
        }
        throw new ObjPackException("the audit file's \"" + key + "\" is neither a string nor a number: "
                + label);
    }

    /**
     * A strict JSON reader (RFC 8259), JDK only. Objects become insertion-ordered maps and refuse a
     * repeated key - "which of two file_name values is the object" must never be answered by
     * position. Numbers keep their text. Errors carry the character offset.
     */
    static final class Json {
        static final Object NULL = new Object();

        static final class Num {
            final String text;
            Num(String text) { this.text = text; }
        }

        private static final int MAX_DEPTH = 32;
        private final String s;
        private int i;

        Json(String s) {
            this.s = s;
        }

        Object document() {
            ws();
            Object v = value(0);
            ws();
            if (i != s.length()) {
                throw err("content after the end of the document");
            }
            return v;
        }

        private Object value(int depth) {
            if (depth > MAX_DEPTH) {
                throw err("nested deeper than " + MAX_DEPTH);
            }
            if (i >= s.length()) {
                throw err("unexpected end");
            }
            char c = s.charAt(i);
            if (c == '{') {
                return object(depth);
            }
            if (c == '[') {
                return array(depth);
            }
            if (c == '"') {
                return string();
            }
            if (c == '-' || (c >= '0' && c <= '9')) {
                return number();
            }
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return NULL; }
            throw err("unexpected character '" + c + "'");
        }

        private Object object(int depth) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<String, Object>();
            i++;
            ws();
            if (peek('}')) { i++; return m; }
            while (true) {
                ws();
                if (!peek('"')) {
                    throw err("expected a key");
                }
                String k = string();
                ws();
                if (!peek(':')) {
                    throw err("expected ':' after the key \"" + k + "\"");
                }
                i++;
                ws();
                Object v = value(depth + 1);
                if (m.containsKey(k)) {
                    throw err("the key \"" + k + "\" appears twice in one object");
                }
                m.put(k, v);
                ws();
                if (peek(',')) { i++; continue; }
                if (peek('}')) { i++; return m; }
                throw err("expected ',' or '}'");
            }
        }

        private Object array(int depth) {
            List<Object> l = new ArrayList<Object>();
            i++;
            ws();
            if (peek(']')) { i++; return l; }
            while (true) {
                ws();
                l.add(value(depth + 1));
                ws();
                if (peek(',')) { i++; continue; }
                if (peek(']')) { i++; return l; }
                throw err("expected ',' or ']'");
            }
        }

        private String string() {
            StringBuilder sb = new StringBuilder();
            i++;
            while (true) {
                if (i >= s.length()) {
                    throw err("unterminated string");
                }
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c < 0x20) {
                    throw err("raw control character in a string");
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (i >= s.length()) {
                    throw err("unterminated escape");
                }
                char e = s.charAt(i++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (i + 4 > s.length()) {
                            throw err("short \\u escape");
                        }
                        int cp = 0;
                        for (int k = 0; k < 4; k++) {
                            int d = Character.digit(s.charAt(i + k), 16);
                            if (d < 0) {
                                throw err("bad \\u escape");
                            }
                            cp = cp * 16 + d;
                        }
                        i += 4;
                        sb.append((char) cp);
                        break;
                    default:
                        throw err("unknown escape \\" + e);
                }
            }
        }

        private Num number() {
            int start = i;
            if (peek('-')) i++;
            if (peek('0')) {
                i++;
            } else if (i < s.length() && s.charAt(i) >= '1' && s.charAt(i) <= '9') {
                digits();
            } else {
                throw err("bad number");
            }
            if (peek('.')) {
                i++;
                if (digits() == 0) throw err("bad number");
            }
            if (peek('e') || peek('E')) {
                i++;
                if (peek('+') || peek('-')) i++;
                if (digits() == 0) throw err("bad number");
            }
            return new Num(s.substring(start, i));
        }

        private int digits() {
            int n = 0;
            while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') { i++; n++; }
            return n;
        }

        private boolean peek(char c) {
            return i < s.length() && s.charAt(i) == c;
        }

        private void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
                else break;
            }
        }

        private IllegalArgumentException err(String what) {
            return new IllegalArgumentException(what + " at character " + i);
        }
    }

    // ---------------------------------------------------------------- tiny reader

    private static long number(String s, String key, File f) {
        String needle = "\"" + key + "\"";
        int k = s.indexOf(needle);
        if (k < 0) {
            throw new ObjPackException("the audit file has no \"" + key + "\": " + f.getName());
        }
        int colon = s.indexOf(':', k + needle.length());
        int i = colon + 1;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        int start = i;
        while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '-')) {
            i++;
        }
        if (start == i) {
            throw new ObjPackException("the audit file's \"" + key + "\" is not a number: " + f.getName());
        }
        return Long.parseLong(s.substring(start, i));
    }

    /** Every value of a repeated string key, in document order. */
    private static List<String> stringsOf(String s, String key) {
        List<String> out = new ArrayList<String>();
        String needle = "\"" + key + "\"";
        int i = 0;
        while (true) {
            int k = s.indexOf(needle, i);
            if (k < 0) {
                break;
            }
            int colon = s.indexOf(':', k + needle.length());
            if (colon < 0) {
                break;
            }
            int q1 = s.indexOf('"', colon + 1);
            if (q1 < 0) {
                break;
            }
            StringBuilder sb = new StringBuilder();
            int j = q1 + 1;
            while (j < s.length()) {
                char c = s.charAt(j);
                if (c == '\\' && j + 1 < s.length()) {
                    char n = s.charAt(j + 1);
                    sb.append(n == 'n' ? '\n' : n == 't' ? '\t' : n);
                    j += 2;
                    continue;
                }
                if (c == '"') {
                    break;
                }
                sb.append(c);
                j++;
            }
            out.add(sb.toString());
            i = j + 1;
        }
        return out;
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

    private static String pad3(int v) {
        String s = Integer.toString(v);
        while (s.length() < 3) {
            s = "0" + s;
        }
        return s;
    }

    /** JSON string escaping. Control characters are escaped, not dropped. */
    private static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", Integer.valueOf(c)));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
