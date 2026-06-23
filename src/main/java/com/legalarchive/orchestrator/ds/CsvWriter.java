package com.legalarchive.orchestrator.ds;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Row-oriented CSV writer shared by the {@code sql}, {@code csvsql} and {@code xlsx2csv} executors so
 * they all produce byte-identical output: UTF-8, optional BOM, CRLF line endings, RFC-4180 quoting
 * and the same split-by-rows / split-by-MB behaviour. Usage:
 * <pre>
 *   CsvWriter w = new CsvWriter(baseFile, ';', false, maxRows, maxBytes);
 *   try { w.header(cols); while (...) w.row(cells); } finally { w.close(); }
 *   // w.files / w.rows / w.parts
 * </pre>
 * Always call {@link #close()} (in a finally): it flushes and guarantees at least one file
 * (header-only) even when no data rows were written.
 */
public class CsvWriter implements AutoCloseable {

    private final File baseFile;
    private final char delim;
    private final boolean bom;
    private final long maxRows;
    private final long maxBytes;
    private final boolean split;

    private Writer w;
    private int part = 0;
    private long rowsInPart = 0;
    private long bytesInPart = 0;
    private String headerLine;
    private long headerBytes;

    public final List<String> files = new ArrayList<String>();
    public long rows = 0;
    public int parts = 0;

    public CsvWriter(File baseFile, char delim, boolean bom, long maxRows, long maxBytes) {
        this.baseFile = baseFile;
        this.delim = delim;
        this.bom = bom;
        this.maxRows = maxRows;
        this.maxBytes = maxBytes;
        this.split = maxRows > 0 || maxBytes > 0;
    }

    /** Set the header line. Call before the first {@link #row}. */
    public void header(String[] cols) {
        headerLine = buildLine(cols);
        headerBytes = utf8Len(headerLine) + 2;
    }

    public void row(String[] cells) throws IOException {
        String line = buildLine(cells);
        long rb = utf8Len(line) + 2;
        boolean rollover = w == null
                || (split && rowsInPart > 0 && (
                        (maxRows > 0 && rowsInPart >= maxRows) ||
                        (maxBytes > 0 && bytesInPart + rb > maxBytes)));
        if (rollover) openNext();
        w.write(line);
        w.write("\r\n");
        rowsInPart++;
        bytesInPart += rb;
        rows++;
    }

    private void openNext() throws IOException {
        if (w != null) { w.close(); w = null; }
        part++;
        File f = split ? partName(baseFile, part) : baseFile;
        if (f.getParentFile() != null) f.getParentFile().mkdirs();
        w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8), 1 << 16);
        if (bom) w.write('\uFEFF');
        if (headerLine != null) { w.write(headerLine); w.write("\r\n"); }
        files.add(f.getAbsolutePath());
        rowsInPart = 0;
        bytesInPart = headerBytes + (bom ? 1 : 0);
    }

    public void close() throws IOException {
        if (w == null) openNext();   // no data rows: still emit an (empty-but-header) first file
        if (w != null) { w.flush(); w.close(); w = null; }
        parts = files.size();
    }

    private String buildLine(String[] cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append(delim);
            sb.append(csvField(cells[i]));
        }
        return sb.toString();
    }

    /** RFC 4180 quoting: wrap in double quotes when the field holds the delimiter, a quote or a newline. */
    private String csvField(String s) {
        if (s == null) return "";
        boolean q = s.indexOf(delim) >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
        if (!q) return s;
        return '"' + s.replace("\"", "\"\"") + '"';
    }

    private static File partName(File base, int n) {
        String name = base.getName();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        return new File(base.getParentFile(), stem + "_" + String.format("%03d", n) + ext);
    }

    /** Exact UTF-8 byte length (surrogate pairs counted as 4 bytes). */
    private static long utf8Len(String s) {
        long n = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < 0x80) n += 1;
            else if (ch < 0x800) n += 2;
            else if (Character.isHighSurrogate(ch) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) { n += 4; i++; }
            else n += 3;
        }
        return n;
    }
}
