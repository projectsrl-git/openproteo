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
 * and the same split-by-rows / split-by-MB behaviour. Always call {@link #close()} in a finally.
 *
 * <p>Write-path optimisations (output stays byte-identical):
 * <ul>
 *   <li>UTF-8 byte counting per row only when splitting by bytes (maxBytes &gt; 0).</li>
 *   <li>One reusable StringBuilder + char[] across rows (no per-row String/StringBuilder alloc).</li>
 *   <li>Single-pass field quoting; 512 KB output buffer.</li>
 * </ul>
 */
public class CsvWriter implements AutoCloseable {

    private static final int BUFFER_BYTES = 1 << 19;   // 512 KB

    private final File baseFile;
    private final char delim;
    private final boolean bom;
    private final long maxRows;
    private final long maxBytes;
    private final boolean split;
    private final boolean countBytes;

    private Writer w;
    private int part = 0;
    private long rowsInPart = 0;
    private long bytesInPart = 0;
    private String headerLine;
    private long headerBytes;

    private final StringBuilder sb = new StringBuilder(512);
    private char[] cbuf = new char[8192];

    public final List<String> files = new ArrayList<String>();
    public final List<Long> partRows = new ArrayList<Long>();   // data rows per part, aligned with files
    public long rows = 0;
    public int parts = 0;

    public CsvWriter(File baseFile, char delim, boolean bom, long maxRows, long maxBytes) {
        this.baseFile = baseFile;
        this.delim = delim;
        this.bom = bom;
        this.maxRows = maxRows;
        this.maxBytes = maxBytes;
        this.split = maxRows > 0 || maxBytes > 0;
        this.countBytes = maxBytes > 0;
    }

    public void header(String[] cols) {
        sb.setLength(0);
        appendFields(sb, cols);
        headerLine = sb.toString();
        headerBytes = countBytes ? utf8Len(headerLine) + 2 : 0;
    }

    public void row(String[] cells) throws IOException {
        sb.setLength(0);
        appendFields(sb, cells);
        long rb = countBytes ? utf8Len(sb) + 2 : 0;
        boolean rollover = w == null
                || (split && rowsInPart > 0 && (
                        (maxRows > 0 && rowsInPart >= maxRows) ||
                        (countBytes && bytesInPart + rb > maxBytes)));
        if (rollover) openNext();
        sb.append('\r').append('\n');
        int len = sb.length();
        if (cbuf.length < len) cbuf = new char[Math.max(len, cbuf.length * 2)];
        sb.getChars(0, len, cbuf, 0);
        w.write(cbuf, 0, len);
        rowsInPart++;
        if (countBytes) bytesInPart += rb;
        rows++;
    }

    private void openNext() throws IOException {
        if (w != null) { partRows.add(rowsInPart); w.close(); w = null; }
        part++;
        File f = split ? partName(baseFile, part) : baseFile;
        if (f.getParentFile() != null) f.getParentFile().mkdirs();
        w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8), BUFFER_BYTES);
        if (bom) w.write('\uFEFF');
        if (headerLine != null) { w.write(headerLine); w.write("\r\n"); }
        files.add(f.getAbsolutePath());
        rowsInPart = 0;
        bytesInPart = headerBytes + (bom ? 1 : 0);
    }

    public void close() throws IOException {
        if (w == null) openNext();
        if (w != null) { partRows.add(rowsInPart); w.flush(); w.close(); w = null; }
        parts = files.size();
    }

    private void appendFields(StringBuilder out, String[] cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) out.append(delim);
            appendField(out, cells[i]);
        }
    }

    private void appendField(StringBuilder out, String s) {
        if (s == null || s.isEmpty()) return;
        boolean q = false;
        for (int i = 0, n = s.length(); i < n; i++) {
            char ch = s.charAt(i);
            if (ch == delim || ch == '"' || ch == '\n' || ch == '\r') { q = true; break; }
        }
        if (!q) { out.append(s); return; }
        out.append('"');
        for (int i = 0, n = s.length(); i < n; i++) {
            char ch = s.charAt(i);
            if (ch == '"') out.append('"');
            out.append(ch);
        }
        out.append('"');
    }

    private static File partName(File base, int n) {
        String name = base.getName();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        return new File(base.getParentFile(), stem + "_" + String.format("%03d", n) + ext);
    }

    private static long utf8Len(CharSequence s) {
        long n = 0;
        for (int i = 0, L = s.length(); i < L; i++) {
            char ch = s.charAt(i);
            if (ch < 0x80) n += 1;
            else if (ch < 0x800) n += 2;
            else if (Character.isHighSurrogate(ch) && i + 1 < L && Character.isLowSurrogate(s.charAt(i + 1))) { n += 4; i++; }
            else n += 3;
        }
        return n;
    }
}
