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
        public java.util.List<Long> partRows = new java.util.ArrayList<Long>();   // data rows per part, aligned with files
        public long rows;
        public int parts;
        public long newlinesSanitized;   // cells whose CR/LF were replaced while writing
    }

    /**
     * How a CR/LF found INSIDE an extracted value is written: space | strip | keep.
     * The DEFAULT IS KEEP (write the value exactly as the database returned it), so existing
     * production feeds are byte-for-byte unaffected; opt in per step to normalise.
     */
    public static String nlReplacement(String mode) {
        if ("space".equalsIgnoreCase(mode)) return " ";
        if ("strip".equalsIgnoreCase(mode)) return "";
        return null;                                             // keep (default): as-is
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
        return exportCsv(d, sql, baseFile, delim, bom, maxRows, maxBytes, trim, null, "keep");
    }

    /**
     * As {@link #exportCsv}, but reports a forcible abort action to {@code onAborter} (and null when
     * done). The action cancels the statement and closes the statement+connection, which unblocks a
     * running query/fetch even on drivers (AS400 jt400) where Statement.cancel() alone does nothing.
     * setFetchSize cuts JDBC round-trips while streaming.
     */
    public ExportResult exportCsv(DataSourceDef d, String sql, java.io.File baseFile, char delim,
                                  boolean bom, long maxRows, long maxBytes, boolean trim,
                                  java.util.function.Consumer<Runnable> onAborter) throws Exception {
        return exportCsv(d, sql, baseFile, delim, bom, maxRows, maxBytes, trim, onAborter, "keep");
    }

    /** As above, with the newline policy applied to every extracted value (see {@link #nlReplacement}). */
    public ExportResult exportCsv(DataSourceDef d, String sql, java.io.File baseFile, char delim,
                                  boolean bom, long maxRows, long maxBytes, boolean trim,
                                  java.util.function.Consumer<Runnable> onAborter, String nlMode) throws Exception {
        final Connection c = open(d);
        Statement st = null;
        try {
            st = c.createStatement();
            try { st.setFetchSize(1000); } catch (Exception ignored) {}
            final Statement fst = st;
            if (onAborter != null) {
                onAborter.accept(new Runnable() {
                    public void run() {
                        try { fst.cancel(); } catch (Exception ignored) {}
                        try { fst.close(); } catch (Exception ignored) {}
                        try { c.close(); } catch (Exception ignored) {}
                    }
                });
            }
            ResultSet rs = st.executeQuery(sql);
            return exportResultSet(rs, baseFile, delim, bom, maxRows, maxBytes, trim, nlMode);
        } finally {
            if (onAborter != null) try { onAborter.accept(null); } catch (Exception ignored) {}
            if (st != null) try { st.close(); } catch (Exception ignored) {}
            try { c.close(); } catch (Exception ignored) {}
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
        return exportResultSet(rs, baseFile, delim, bom, maxRows, maxBytes, trim, "keep");
    }

    /**
     * As above, but every value is normalised according to nlMode. The column count comes from the
     * ResultSetMetaData, so each record is written with exactly that many fields: removing the CR/LF
     * that a source column may contain is what keeps one record on one physical line downstream.
     */
    public ExportResult exportResultSet(ResultSet rs, java.io.File baseFile, char delim,
                                        boolean bom, long maxRows, long maxBytes, boolean trim,
                                        String nlMode) throws Exception {
        final String nlRepl = nlReplacement(nlMode);
        long sanitized = 0;
        ResultSetMetaData md = rs.getMetaData();
        int cols = md.getColumnCount();
        CsvWriter cw = new CsvWriter(baseFile, delim, bom, maxRows, maxBytes);
        try {
            String[] header = new String[cols];
            for (int i = 1; i <= cols; i++) header[i - 1] = md.getColumnLabel(i);
            cw.header(header);
            String[] cell = new String[cols];
            while (rs.next()) {
                for (int i = 1; i <= cols; i++) {
                    String v = cellValue(rs.getObject(i), trim);
                    if (nlRepl != null && (v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0)) {
                        v = v.replace("\r\n", nlRepl).replace("\n", nlRepl).replace("\r", nlRepl);
                        sanitized++;
                    }
                    cell[i - 1] = v;
                }
                cw.row(cell);
            }
        } finally {
            cw.close();
        }
        ExportResult res = new ExportResult();
        res.files.addAll(cw.files);
        res.partRows.addAll(cw.partRows);
        res.rows = cw.rows;
        res.parts = cw.parts;
        res.newlinesSanitized = sanitized;
        return res;
    }

    /** Cell extraction: DB2/AS400 CHAR columns are blank-padded; trim removes the padding. */
    private static String cellValue(Object v, boolean trim) {
        if (v == null) return "";
        String s = v.toString();
        return trim ? s.trim() : s;
    }

}
