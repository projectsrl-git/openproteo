package com.legalarchive.orchestrator.ds;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.springframework.stereotype.Component;

/**
 * JDBC helpers shared by the datasource UI (test/preview) and the SQL step.
 * The JTOpen driver (com.ibm.as400.access.AS400JDBCDriver) is on the classpath;
 * it is loaded by name so there is no compile-time dependency here.
 */
@Component
public class SqlSupport {

    public static class QueryResult {
        public List<String> columns = new ArrayList<String>();
        public List<List<String>> rows = new ArrayList<List<String>>();
        public int rowCount;
        public boolean truncated;
        public Long updateCount;   // for non-select statements
    }

    public Connection open(DataSourceDef d) throws Exception {
        if (d == null) throw new IllegalArgumentException("Unknown datasource");
        if ("custom".equalsIgnoreCase(d.type)) {
            if (d.driverClass != null && !d.driverClass.trim().isEmpty()) {
                Class.forName(d.driverClass.trim());
            }
            return DriverManager.getConnection(d.jdbcUrl, d.user, d.password);
        }
        // AS400 / DB2 for i via JTOpen
        Class.forName("com.ibm.as400.access.AS400JDBCDriver");
        StringBuilder url = new StringBuilder("jdbc:as400://").append(d.host);
        Properties p = new Properties();
        if (d.user != null) p.put("user", d.user);
        if (d.password != null) p.put("password", d.password);
        // "prompt=false" avoids a GUI sign-on dialog on headless servers
        p.put("prompt", "false");
        if (d.properties != null && !d.properties.trim().isEmpty()) {
            for (String kv : d.properties.split(";")) {
                int eq = kv.indexOf('=');
                if (eq > 0) p.put(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
            }
        }
        return DriverManager.getConnection(url.toString(), p);
    }

    public String testQuery(DataSourceDef d) {
        if (d.testQuery != null && !d.testQuery.trim().isEmpty()) return d.testQuery.trim();
        return "custom".equalsIgnoreCase(d.type) ? "SELECT 1" : "SELECT 1 FROM SYSIBM.SYSDUMMY1";
    }

    /** Run a query (or update) and capture up to maxRows rows. */
    public QueryResult run(DataSourceDef d, String sql, int maxRows) throws Exception { return run(d, sql, maxRows, true); }

    public QueryResult run(DataSourceDef d, String sql, int maxRows, boolean trim) throws Exception {
        QueryResult qr = new QueryResult();
        Connection c = null;
        Statement st = null;
        try {
            c = open(d);
            st = c.createStatement();
            boolean isResultSet = st.execute(sql);
            if (!isResultSet) {
                qr.updateCount = (long) st.getUpdateCount();
                return qr;
            }
            ResultSet rs = st.getResultSet();
            ResultSetMetaData md = rs.getMetaData();
            int cols = md.getColumnCount();
            for (int i = 1; i <= cols; i++) qr.columns.add(md.getColumnLabel(i));
            while (rs.next()) {
                if (maxRows > 0 && qr.rowCount >= maxRows) { qr.truncated = true; break; }
                List<String> row = new ArrayList<String>();
                for (int i = 1; i <= cols; i++) {
                    row.add(cellValue(rs.getObject(i), trim));
                }
                qr.rows.add(row);
                qr.rowCount++;
            }
            return qr;
        } finally {
            if (st != null) try { st.close(); } catch (Exception ignored) {}
            if (c != null) try { c.close(); } catch (Exception ignored) {}
        }
    }

    /** Connection test: returns null if OK, otherwise the error message. */
    public String test(DataSourceDef d) {
        try {
            run(d, testQuery(d), 1);
            return null;
        } catch (Exception e) {
            return e.getMessage() == null ? e.toString() : e.getMessage();
        }
    }

    /** First column of the first row, or null. */
    public static String firstValue(QueryResult qr) {
        if (qr.rows.isEmpty() || qr.rows.get(0).isEmpty()) return null;
        return qr.rows.get(0).get(0);
    }

    public static class ExportResult {
        public java.util.List<String> files = new java.util.ArrayList<String>();
        public long rows;
        public int parts;
    }

    /**
     * Stream a query result to one or more CSV files (UTF-8, optional BOM), without
     * buffering the whole result set. Optionally split into multiple files:
     *   maxRows  > 0  : start a new file every maxRows data rows
     *   maxBytes > 0  : start a new file when the next row would exceed maxBytes
     * (if both are 0, a single file = baseFile is written). Each part repeats the header.
     * Part files are named  stem_001.ext, stem_002.ext, ...
     */
    public ExportResult exportCsv(DataSourceDef d, String sql, java.io.File baseFile, char delim,
                                  boolean bom, long maxRows, long maxBytes, boolean trim) throws Exception {
        Connection c = null;
        Statement st = null;
        try {
            c = open(d);
            st = c.createStatement();
            ResultSet rs = st.executeQuery(sql);
            return exportResultSet(rs, baseFile, delim, bom, maxRows, maxBytes, trim);
        } finally {
            if (st != null) try { st.close(); } catch (Exception ignored) {}
            if (c != null) try { c.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * Stream an already-open ResultSet to one or more CSV files (UTF-8, optional BOM, CRLF, header),
     * honouring the same split rules as {@link #exportCsv}. The caller owns the ResultSet (and its
     * Statement/Connection) lifecycle. Shared by the DB2 {@code sql} executor and the H2 {@code csvsql}
     * executor so both produce byte-identical output.
     */
    public ExportResult exportResultSet(ResultSet rs, java.io.File baseFile, char delim,
                                        boolean bom, long maxRows, long maxBytes, boolean trim) throws Exception {
        boolean split = maxRows > 0 || maxBytes > 0;
        ExportResult res = new ExportResult();
        java.io.Writer w = null;
        try {
            ResultSetMetaData md = rs.getMetaData();
            int cols = md.getColumnCount();

            String[] headerCols = new String[cols];
            for (int i = 1; i <= cols; i++) headerCols[i - 1] = md.getColumnLabel(i);
            String headerLine = buildLine(headerCols, delim);
            long headerBytes = utf8Len(headerLine) + 2;

            int part = 0;
            long rowsInPart = 0, bytesInPart = 0;
            String[] cell = new String[cols];

            while (rs.next()) {
                for (int i = 1; i <= cols; i++) {
                    cell[i - 1] = cellValue(rs.getObject(i), trim);
                }
                String line = buildLine(cell, delim);
                long rb = utf8Len(line) + 2;

                boolean rollover = w == null
                        || (split && rowsInPart > 0 && (
                                (maxRows > 0 && rowsInPart >= maxRows) ||
                                (maxBytes > 0 && bytesInPart + rb > maxBytes)));

                if (rollover) {
                    if (w != null) { w.close(); w = null; }
                    part++;
                    java.io.File f = split ? partName(baseFile, part) : baseFile;
                    if (f.getParentFile() != null) f.getParentFile().mkdirs();
                    w = new java.io.BufferedWriter(new java.io.OutputStreamWriter(
                            new java.io.FileOutputStream(f), java.nio.charset.StandardCharsets.UTF_8), 1 << 16);
                    if (bom) w.write('\uFEFF');
                    w.write(headerLine);
                    w.write("\r\n");
                    res.files.add(f.getAbsolutePath());
                    rowsInPart = 0;
                    bytesInPart = headerBytes + (bom ? 1 : 0);
                }
                w.write(line);
                w.write("\r\n");
                rowsInPart++;
                bytesInPart += rb;
                res.rows++;
            }
            // no rows at all: still produce an (empty-but-header) first file
            if (w == null) {
                java.io.File f = split ? partName(baseFile, 1) : baseFile;
                if (f.getParentFile() != null) f.getParentFile().mkdirs();
                w = new java.io.BufferedWriter(new java.io.OutputStreamWriter(
                        new java.io.FileOutputStream(f), java.nio.charset.StandardCharsets.UTF_8), 1 << 16);
                if (bom) w.write('\uFEFF');
                w.write(headerLine);
                w.write("\r\n");
                res.files.add(f.getAbsolutePath());
            }
            w.flush();
            res.parts = res.files.size();
            return res;
        } finally {
            if (w != null) try { w.close(); } catch (Exception ignored) {}
        }
    }

    private static java.io.File partName(java.io.File base, int n) {
        String name = base.getName();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        String p = stem + "_" + String.format("%03d", n) + ext;
        return new java.io.File(base.getParentFile(), p);
    }

    private String buildLine(String[] cells, char delim) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append(delim);
            sb.append(csvField(cells[i], delim));
        }
        return sb.toString();
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

    /** Cell extraction: DB2/AS400 CHAR columns are blank-padded; trim removes the padding. */
    private static String cellValue(Object v, boolean trim) {
        if (v == null) return "";
        String s = v.toString();
        return trim ? s.trim() : s;
    }

    /** RFC 4180 quoting: wrap in double quotes when the field holds the delimiter, a quote or a newline. */
    private static String csvField(String s, char delim) {
        if (s == null) return "";
        boolean q = s.indexOf(delim) >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
        if (!q) return s;
        return '"' + s.replace("\"", "\"\"") + '"';
    }
}
