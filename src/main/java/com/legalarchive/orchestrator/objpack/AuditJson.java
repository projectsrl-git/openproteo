package com.legalarchive.orchestrator.objpack;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
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
        StringBuilder sb = new StringBuilder(256 + entries.size() * 160);
        sb.append("{\n");
        sb.append("    \"transmission_date\" : ").append(Long.parseLong(name.transmissionDate())).append(",\n");
        sb.append("    \"sequence_number\" : \"").append(pad3(name.sequenceNr())).append("\",\n");
        sb.append("    \"version_number\" : \"").append(pad3(name.versionNr())).append("\",\n");
        sb.append("    \"record_count\" : ").append(entries.size()).append(",\n");
        sb.append("    \"TargetDestination\": \"").append(esc(targetDestination.trim())).append("\",\n");
        sb.append("    \"metadata_file_name\" : \"").append(esc(name.metadataCsv())).append("\",\n");
        sb.append("    \"submission_object_files\" : [\n");
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            sb.append("        {\n");
            sb.append("            \"file_name\" : \"").append(esc(e.fileName)).append("\",\n");
            sb.append("            \"mime_type\" : \"").append(esc(e.mimeType)).append("\",\n");
            sb.append("            \"object_id\" : \"").append(e.objectId).append("\"\n");
            sb.append("        }").append(i == entries.size() - 1 ? "\n" : ",\n");
        }
        sb.append("    ]\n");
        sb.append("}\n");

        byte[] bytes = sb.toString().getBytes(Charset.forName("UTF-8"));
        OutputStream os = new FileOutputStream(out);
        try {
            os.write(bytes);
            os.flush();
        } finally {
            os.close();
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
