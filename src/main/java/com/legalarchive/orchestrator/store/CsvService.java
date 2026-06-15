package com.legalarchive.orchestrator.store;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Streams CSV/text files on the server so the browser never has to load millions
 * of rows. Provides metadata, paged windows (optionally filtered), and SQL-like
 * group-by aggregation with COUNT and DISTINCT-COUNT.
 *
 * Memory/IO design:
 *  - meta() makes ONE byte-level pass that counts lines AND records a line-offset
 *    index (one long every STRIDE physical lines, ~12KB per 1.5M lines). The scan
 *    looks for the 0x0A byte, which in UTF-8 never occurs inside a multi-byte
 *    sequence, so checkpoints always fall on valid line starts.
 *  - page()/lines() seek to the nearest checkpoint and skip at most STRIDE-1
 *    lines: deep scrolling is O(1) instead of O(offset).
 *  - aggregate() results are cached (small LRU keyed by file+mtime+columns+filter)
 *    and the total number of DISTINCT values tracked is capped to bound heap usage
 *    (truncatedDistinct flag set when the cap kicks in).
 *
 * Line-based: quoted fields are supported within a single physical line (typical
 * DB exports); embedded newlines inside quotes are not split-aware.
 */
@Component
public class CsvService {

    private static final int STRIDE = 1024;                     // index checkpoint every N physical lines
    private static final int MAX_GROUPS = 200000;               // distinct group combinations cap
    private static final long MAX_DISTINCT_VALUES = 2000000L;   // global cap of tracked distinct values
    private static final int AGG_CACHE_SIZE = 6;

    public static class Meta {
        public List<String> columns = new ArrayList<String>();
        public long totalRows;     // data rows (CSV) — header excluded
        public long totalLines;    // physical lines (text)
        public String delimiter;
        long mtime; long size;
        int bomLen;
        long[] index;              // byte offset of physical line k*STRIDE
    }

    public static class Page {
        public List<List<String>> rows = new ArrayList<List<String>>();
        public long total;
    }

    public static class Agg {
        public List<String> groupColumns = new ArrayList<String>();
        public List<String> distinctColumns = new ArrayList<String>();
        public List<Map<String, Object>> groups = new ArrayList<Map<String, Object>>();
        public long scanned;
        public boolean truncated;          // group combinations beyond MAX_GROUPS were dropped
        public boolean truncatedDistinct;  // distinct tracking hit MAX_DISTINCT_VALUES (counts = lower bound)
        public long totalCount;            // total matching rows (the filter applies)
        public long[] totalDistinct;       // global distinct count per distinct column
    }

    private final Map<String, Meta> metaCache = new ConcurrentHashMap<String, Meta>();
    private final LinkedHashMap<String, Agg> aggCache = new LinkedHashMap<String, Agg>(16, 0.75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, Agg> eldest) { return size() > AGG_CACHE_SIZE; }
    };

    private char detect(String firstLine, String name) {
        if (name != null && name.toLowerCase().endsWith(".tsv")) return '\t';
        int sc = count(firstLine, ';'), cc = count(firstLine, ','), tc = count(firstLine, '\t');
        if (tc >= sc && tc >= cc && tc > 0) return '\t';
        if (sc >= cc && sc > 0) return ';';
        return ',';
    }
    private int count(String s, char c) { int n = 0; for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++; return n; }

    /** Quote-aware split of a single line. */
    private List<String> split(String line, char delim) {
        List<String> out = new ArrayList<String>();
        StringBuilder sb = new StringBuilder();
        boolean inQ = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQ) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') { sb.append('"'); i++; }
                    else inQ = false;
                } else sb.append(c);
            } else {
                if (c == '"') inQ = true;
                else if (c == delim) { out.add(sb.toString()); sb.setLength(0); }
                else sb.append(c);
            }
        }
        out.add(sb.toString());
        return out;
    }

    /** Single byte-level pass: counts lines, captures the header, builds the offset index. */
    public Meta meta(File f) throws Exception {
        String key = f.getAbsolutePath();
        Meta m = metaCache.get(key);
        if (m != null && m.mtime == f.lastModified() && m.size == f.length()) return m;
        m = new Meta();
        m.mtime = f.lastModified();
        m.size = f.length();

        InputStream in = new BufferedInputStream(new FileInputStream(f), 1 << 16);
        try {
            in.mark(3);
            int b1 = in.read(), b2 = in.read(), b3 = in.read();
            if (b1 == 0xEF && b2 == 0xBB && b3 == 0xBF) m.bomLen = 3;
            else {
                in.reset();
                if (b1 == -1) { metaCache.put(key, m); return m; }   // empty file
            }

            java.io.ByteArrayOutputStream headerBytes = new java.io.ByteArrayOutputStream();
            List<Long> idx = new ArrayList<Long>();
            long pos = m.bomLen;
            long lineNo = 0;
            boolean atLineStart = true;
            boolean anyByte = false;
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                for (int i = 0; i < n; i++) {
                    if (atLineStart) {
                        if (lineNo % STRIDE == 0) idx.add(pos);
                        atLineStart = false;
                    }
                    byte b = buf[i];
                    anyByte = true;
                    if (b == 0x0A) { lineNo++; atLineStart = true; }
                    else if (lineNo == 0 && b != 0x0D && headerBytes.size() < (1 << 20)) headerBytes.write(b);
                    pos++;
                }
            }
            long totalLines = anyByte ? (lineNo + (atLineStart ? 0 : 1)) : 0;
            m.totalLines = totalLines;
            m.totalRows = Math.max(0, totalLines - 1);
            m.index = new long[idx.size()];
            for (int i = 0; i < idx.size(); i++) m.index[i] = idx.get(i);

            if (totalLines > 0) {
                String header = new String(headerBytes.toByteArray(), StandardCharsets.UTF_8);
                char d = detect(header, f.getName());
                m.delimiter = (d == '\t') ? "TAB" : String.valueOf(d);
                m.columns = split(header, d);
            }
        } finally { in.close(); }
        metaCache.put(key, m);
        return m;
    }

    private char delimChar(Meta m) { return "TAB".equals(m.delimiter) ? '\t' : (m.delimiter == null ? ',' : m.delimiter.charAt(0)); }

    /** Reader positioned at the given physical line via the offset index (O(1)-ish). */
    private BufferedReader readerAt(File f, Meta m, long physLine) throws Exception {
        long target = m.bomLen;
        long base = 0;
        if (m.index != null && m.index.length > 0) {
            int k = (int) Math.min(physLine / STRIDE, m.index.length - 1);
            target = m.index[k];
            base = (long) k * STRIDE;
        }
        FileInputStream fis = new FileInputStream(f);
        long toSkip = target;
        while (toSkip > 0) { long s = fis.skip(toSkip); if (s <= 0) break; toSkip -= s; }
        BufferedReader r = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8), 1 << 16);
        for (long i = base; i < physLine; i++) if (r.readLine() == null) break;
        return r;
    }

    private boolean matches(List<String> row, String q) {
        if (q == null || q.isEmpty()) return true;
        for (int i = 0; i < row.size(); i++) if (row.get(i).toLowerCase().indexOf(q) >= 0) return true;
        return false;
    }

    /** Paged CSV rows. Without a filter the window is reached by index seek; with a filter the file is scanned. */
    public Page page(File f, int offset, int limit, String q) throws Exception {
        Meta m = meta(f);
        char d = delimChar(m);
        String ql = q == null ? "" : q.toLowerCase();
        Page p = new Page();
        if (ql.isEmpty()) {
            BufferedReader r = readerAt(f, m, offset + 1L);    // +1 = skip the header line
            try {
                String line;
                while (p.rows.size() < limit && (line = r.readLine()) != null) p.rows.add(split(line, d));
            } finally { r.close(); }
            p.total = m.totalRows;
        } else {
            BufferedReader r = readerAt(f, m, 1L);
            try {
                String line; long matched = 0;
                while ((line = r.readLine()) != null) {
                    List<String> row = split(line, d);
                    if (!matches(row, ql)) continue;
                    if (matched >= offset && p.rows.size() < limit) p.rows.add(row);
                    matched++;
                }
                p.total = matched;
            } finally { r.close(); }
        }
        return p;
    }

    /** Group-by aggregation with COUNT and DISTINCT-COUNT; results LRU-cached per (file, cols, filter). */
    /** Backward-compatible overload: plain column indices, no substring transform. */
    public Agg aggregate(File f, int[] groupCols, int[] distinctCols, String q) throws Exception {
        String[] g = new String[groupCols.length]; for (int i = 0; i < g.length; i++) g[i] = String.valueOf(groupCols[i]);
        String[] d = new String[distinctCols.length]; for (int i = 0; i < d.length; i++) d[i] = String.valueOf(distinctCols[i]);
        return aggregate(f, g, d, q);
    }

    /** Column spec: "3" or "3:L4" (left 4 chars) or "3:R2" (right 2). Lets you group/count by a
     *  substring — e.g. year from a date column, or a code prefix. */
    public Agg aggregate(File f, String[] groupSpec, String[] distinctSpec, String q) throws Exception {
        Meta m = meta(f);
        String cacheKey = f.getAbsolutePath() + "|" + m.mtime + "|" + m.size + "|" +
                Arrays.toString(groupSpec) + "|" + Arrays.toString(distinctSpec) + "|" + (q == null ? "" : q);
        synchronized (aggCache) {
            Agg cached = aggCache.get(cacheKey);
            if (cached != null) return cached;
        }

        char d = delimChar(m);
        String ql = q == null ? "" : q.toLowerCase();
        Agg agg = new Agg();
        int[] groupCols = new int[groupSpec.length]; char[] gK = new char[groupSpec.length]; int[] gN = new int[groupSpec.length];
        int[] distinctCols = new int[distinctSpec.length]; char[] dK = new char[distinctSpec.length]; int[] dN = new int[distinctSpec.length];
        for (int i = 0; i < groupSpec.length; i++) { int[] pr = parseSpec(groupSpec[i]); groupCols[i] = pr[0]; gK[i] = (char) pr[1]; gN[i] = pr[2]; agg.groupColumns.add(colTxName(m, groupCols[i], gK[i], gN[i])); }
        for (int i = 0; i < distinctSpec.length; i++) { int[] pr = parseSpec(distinctSpec[i]); distinctCols[i] = pr[0]; dK[i] = (char) pr[1]; dN[i] = pr[2]; agg.distinctColumns.add(colTxName(m, distinctCols[i], dK[i], dN[i])); }

        Map<String, long[]> counts = new LinkedHashMap<String, long[]>();
        Map<String, List<Set<String>>> distincts = new LinkedHashMap<String, List<Set<String>>>();
        Map<String, String[]> keyVals = new LinkedHashMap<String, String[]>();
        char SEP = (char) 1;
        long distinctTracked = 0;
        boolean distinctCapped = false;
        boolean grouped = groupCols.length > 0;     // empty group = global distinct-only mode
        // global distinct sets feed the totals row; with no grouping they ARE the result
        List<Set<String>> globalSets = new ArrayList<Set<String>>();
        for (int k = 0; k < distinctCols.length; k++) globalSets.add(new HashSet<String>());

        BufferedReader r = readerAt(f, m, 1L);
        try {
            String line;
            while ((line = r.readLine()) != null) {
                List<String> row = split(line, d);
                if (!matches(row, ql)) continue;
                agg.scanned++;
                StringBuilder kb = new StringBuilder();
                String[] vals = new String[groupCols.length];
                for (int i = 0; i < groupCols.length; i++) {
                    String v = tx(cell(row, groupCols[i]), gK[i], gN[i]);
                    vals[i] = v;
                    if (i > 0) kb.append(SEP);
                    kb.append(v);
                }
                String key = kb.toString();
                long[] cnt = counts.get(key);
                if (cnt == null) {
                    if (counts.size() >= MAX_GROUPS) { agg.truncated = true; continue; }
                    cnt = new long[]{0};
                    counts.put(key, cnt);
                    keyVals.put(key, vals);
                    if (grouped && distinctCols.length > 0) {
                        List<Set<String>> sets = new ArrayList<Set<String>>();
                        for (int k = 0; k < distinctCols.length; k++) sets.add(new HashSet<String>());
                        distincts.put(key, sets);
                    }
                }
                cnt[0]++;
                if (distinctCols.length > 0 && !distinctCapped) {
                    List<Set<String>> sets = grouped ? distincts.get(key) : null;
                    for (int k = 0; k < distinctCols.length; k++) {
                        String v = tx(cell(row, distinctCols[k]), dK[k], dN[k]);
                        boolean added = globalSets.get(k).add(v);
                        if (sets != null && sets.get(k).add(v)) added = true;
                        if (added) {
                            distinctTracked++;
                            if (distinctTracked >= MAX_DISTINCT_VALUES) { distinctCapped = true; agg.truncatedDistinct = true; break; }
                        }
                    }
                }
            }
        } finally { r.close(); }

        agg.totalCount = agg.scanned;
        agg.totalDistinct = new long[distinctCols.length];
        for (int k = 0; k < distinctCols.length; k++) agg.totalDistinct[k] = globalSets.get(k).size();

        List<Map.Entry<String, long[]>> entries = new ArrayList<Map.Entry<String, long[]>>(counts.entrySet());
        entries.sort(new java.util.Comparator<Map.Entry<String, long[]>>() {
            public int compare(Map.Entry<String, long[]> a, Map.Entry<String, long[]> b) {
                return Long.compare(b.getValue()[0], a.getValue()[0]);
            }
        });
        for (Map.Entry<String, long[]> e : entries) {
            Map<String, Object> g = new LinkedHashMap<String, Object>();
            g.put("vals", keyVals.get(e.getKey()));
            g.put("count", e.getValue()[0]);
            if (distinctCols.length > 0) {
                List<Set<String>> sets = grouped ? distincts.get(e.getKey()) : globalSets;
                long[] dc = new long[sets.size()];
                for (int k = 0; k < sets.size(); k++) dc[k] = sets.get(k).size();
                g.put("distinct", dc);
            }
            agg.groups.add(g);
        }
        synchronized (aggCache) { aggCache.put(cacheKey, agg); }
        return agg;
    }

    /** Paged plain-text lines (for the text viewer), reached via index seek. */
    public Page lines(File f, int offset, int limit) throws Exception {
        Meta m = meta(f);
        Page p = new Page();
        BufferedReader r = readerAt(f, m, offset);
        try {
            String line;
            while (p.rows.size() < limit && (line = r.readLine()) != null) {
                List<String> one = new ArrayList<String>(1);
                one.add(line);
                p.rows.add(one);
            }
        } finally { r.close(); }
        p.total = m.totalLines;
        return p;
    }

    private String col(Meta m, int i) { return (i >= 0 && i < m.columns.size()) ? m.columns.get(i) : ("col" + i); }
    private String colTxName(Meta m, int i, char kind, int n) {
        String base = col(m, i);
        return kind == 'N' ? base : (base + "[" + kind + n + "]");
    }
    /** Parse "idx", "idx:L4", "idx:R2" -> {index, kindChar, n}. kind: 'N' none, 'L' left, 'R' right. */
    private int[] parseSpec(String spec) {
        int idx; char kind = 'N'; int n = 0;
        int colon = spec.indexOf(':');
        if (colon < 0) { idx = parseIntSafe(spec, 0); }
        else {
            idx = parseIntSafe(spec.substring(0, colon), 0);
            String t = spec.substring(colon + 1).trim();
            if (t.length() >= 2 && (t.charAt(0) == 'L' || t.charAt(0) == 'l')) { kind = 'L'; n = parseIntSafe(t.substring(1), 0); }
            else if (t.length() >= 2 && (t.charAt(0) == 'R' || t.charAt(0) == 'r')) { kind = 'R'; n = parseIntSafe(t.substring(1), 0); }
        }
        return new int[]{ idx, kind, n };
    }
    private static int parseIntSafe(String s, int dflt) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return dflt; } }
    private static String tx(String v, char kind, int n) {
        if (v == null) v = "";
        if (kind == 'N' || n <= 0) return v;
        if (kind == 'L') return v.length() <= n ? v : v.substring(0, n);
        return v.length() <= n ? v : v.substring(v.length() - n);   // R
    }
    private String cell(List<String> row, int i) { return (i >= 0 && i < row.size()) ? row.get(i) : ""; }
}
