package com.legalarchive.orchestrator.engine;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Turns one column of a CSV file into the list of IFS paths an {@code ifscopy} step must fetch.
 *
 * <p>No Spring, no JTOpen, no orchestrator types: everything of substance in the "copy the files
 * named in this CSV" option lives here precisely so it compiles and RUNS standalone in a test, the
 * way {@code SqlReportSupport} and the {@code elar} package already do. What is left in
 * {@code InternalSteps} is parameter translation, and what is left in {@code IfsSupport} is the
 * transfer itself, which needs a real IBM i to exercise.</p>
 *
 * <p>The field splitter is a local one rather than a call into {@code InternalSteps.parseCsvLine},
 * for the same reason {@code FlatCsvReader} has its own: a helper this class cannot be tested
 * without would drag Spring into the harness. The delimiter, by contrast, is NOT sniffed here - the
 * caller passes the character it obtained from {@code InternalSteps.detectDelimiter}, so detection
 * stays single-sourced.</p>
 *
 * <p>One physical line is one row. A file name cannot contain a line break, so the record
 * reassembly {@code dequote} needs is deliberately absent; a quoted field spanning two lines would
 * be read as two rows and reported as such rather than silently joined.</p>
 */
public final class IfsListSupport {

    private IfsListSupport() { }

    /** How many offending line numbers are listed individually; the counts themselves are uncapped. */
    public static final int MAX_REPORTED = 50;

    public static class ListResult {
        /** IFS paths in list order, duplicates already collapsed. */
        public final List<String> paths = new ArrayList<String>();
        /** Data rows read (the header, and physically empty lines, are not rows). */
        public int dataRows;
        /** Rows whose chosen cell was empty or absent - counted, never silently dropped. */
        public int blankRows;
        /** Repeated paths collapsed. */
        public int duplicates;
        /** Line numbers (1-based, header included in the count) of the first blank rows. */
        public final List<String> blankLines = new ArrayList<String>();
        /** Human-readable descriptions of two listed files that would land on the same local name. */
        public final List<String> collisions = new ArrayList<String>();
        /** How the column was identified, for the log. */
        public String columnLabel = "";
        /** Zero-based index of the column actually used. */
        public int columnIndex = -1;
        /** Non-null means the step must fail with this message; nothing else is meaningful then. */
        public String error;
    }

    /**
     * Reads the list.
     *
     * @param csv        the CSV file, already resolved to a real path
     * @param charsetName charset of the CSV (names are usually ASCII, but a wrong charset would
     *                    corrupt a name into a file that does not exist, so it is explicit)
     * @param delim      field separator, decided by the caller
     * @param column     column name (when the file has a header) or 1-based column index
     * @param hasHeader  whether the first line is a header
     * @param base       path prepended to a name that is not already absolute; may be null
     */
    public static ListResult read(File csv, String charsetName, char delim, String column,
                                  boolean hasHeader, String base) {
        ListResult r = new ListResult();
        if (column == null || column.trim().isEmpty()) {
            r.error = "no column given: set the column holding the file name";
            return r;
        }
        String spec = column.trim();
        BufferedReader in = null;
        try {
            in = new BufferedReader(new InputStreamReader(new FileInputStream(csv), charsetName));
            long lineNo = 0;
            List<String> header = null;
            if (hasHeader) {
                String h = in.readLine();
                lineNo++;
                if (h == null) { r.error = "the file list is empty: " + csv.getPath(); return r; }
                header = split(stripBom(h), delim);
                r.columnIndex = indexOfName(header, spec);
                if (r.columnIndex >= 0) {
                    r.columnLabel = "'" + header.get(r.columnIndex).trim() + "' (column " + (r.columnIndex + 1) + ")";
                } else {
                    int byPos = asIndex(spec);
                    if (byPos < 0) {
                        r.error = "column '" + spec + "' not found in " + csv.getName()
                                + "; the header has: " + joinNames(header);
                        return r;
                    }
                    if (byPos >= header.size()) {
                        r.error = "column " + spec + " is past the end of " + csv.getName()
                                + ", which has " + header.size() + " column(s): " + joinNames(header);
                        return r;
                    }
                    r.columnIndex = byPos;
                    r.columnLabel = "column " + spec + " ('" + header.get(byPos).trim() + "')";
                }
            } else {
                int byPos = asIndex(spec);
                if (byPos < 0) {
                    r.error = "the file list is declared to have no header, so the column must be a"
                            + " 1-based number, not a name ('" + spec + "')";
                    return r;
                }
                r.columnIndex = byPos;
                r.columnLabel = "column " + spec;
            }

            LinkedHashSet<String> seen = new LinkedHashSet<String>();
            Map<String, String> byLocal = new LinkedHashMap<String, String>();
            String ln;
            while ((ln = in.readLine()) != null) {
                lineNo++;
                if (lineNo == 1) ln = stripBom(ln);
                if (ln.trim().isEmpty()) continue;   // a trailing newline is not a row
                r.dataRows++;
                List<String> cells = split(ln, delim);
                String v = r.columnIndex < cells.size() ? cells.get(r.columnIndex).trim() : "";
                if (v.isEmpty()) {
                    r.blankRows++;
                    if (r.blankLines.size() < MAX_REPORTED) r.blankLines.add(String.valueOf(lineNo));
                    continue;
                }
                String path = join(base, v);
                if (!seen.add(path)) { r.duplicates++; continue; }
                r.paths.add(path);
                String local = localName(path);
                String first = byLocal.get(local);
                if (first != null) {
                    if (r.collisions.size() < MAX_REPORTED) {
                        r.collisions.add(local + " <- " + first + " and " + path);
                    }
                } else {
                    byLocal.put(local, path);
                }
            }
            return r;
        } catch (java.io.UnsupportedEncodingException e) {
            r.error = "unknown charset for the file list: " + charsetName;
            return r;
        } catch (Exception e) {
            r.error = "cannot read the file list " + csv.getPath() + ": " + e;
            return r;
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) { }
        }
    }

    /**
     * Builds the IFS path of one listed name.
     *
     * <p>A name starting with {@code /} is already an absolute IFS path and the base is NOT applied
     * - that is what makes one step able to read a list whose column sometimes holds a full path.
     * Backslashes are left alone: they are legal in an IFS name, and rewriting them would corrupt a
     * genuine one to accommodate a Windows-flavoured list that this source system does not produce.</p>
     */
    public static String join(String base, String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) return n;
        if (n.charAt(0) == '/') return n;
        while (n.startsWith("./")) n = n.substring(2);
        if (base == null) return n;
        String b = base.trim();
        if (b.isEmpty()) return n;
        while (b.length() > 1 && b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if ("/".equals(b)) return "/" + n;
        return b + "/" + n;
    }

    /** The name the file will have in the local destination directory. */
    public static String localName(String ifsPath) {
        if (ifsPath == null) return "";
        int i = ifsPath.lastIndexOf('/');
        return i >= 0 ? ifsPath.substring(i + 1) : ifsPath;
    }

    // ------------------------------------------------------------------ internals

    /** Case-insensitive header lookup, quotes and spaces already removed by the splitter/trim. */
    private static int indexOfName(List<String> header, String name) {
        for (int i = 0; i < header.size(); i++) {
            if (header.get(i).trim().equalsIgnoreCase(name)) return i;
        }
        return -1;
    }

    /** A 1-based column number, or -1 when the text is not one. */
    private static int asIndex(String spec) {
        for (int i = 0; i < spec.length(); i++) {
            if (spec.charAt(i) < '0' || spec.charAt(i) > '9') return -1;
        }
        try {
            int n = Integer.parseInt(spec);
            return n >= 1 ? n - 1 : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private static String joinNames(List<String> header) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < header.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(header.get(i).trim());
        }
        return sb.toString();
    }

    private static String stripBom(String s) {
        if (s != null && !s.isEmpty() && s.charAt(0) == '\uFEFF') return s.substring(1);
        return s;
    }

    /** RFC-4180 field splitting of one physical line. */
    static List<String> split(String line, char delim) {
        List<String> out = new ArrayList<String>();
        if (line == null) return out;
        StringBuilder cur = new StringBuilder();
        boolean inQ = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQ) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                    else inQ = false;
                } else {
                    cur.append(c);
                }
            } else {
                if (c == '"') inQ = true;
                else if (c == delim) { out.add(cur.toString()); cur.setLength(0); }
                else cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }
}
