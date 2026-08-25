package com.legalarchive.orchestrator.engine;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.legalarchive.orchestrator.ds.DataSourceDef;
import com.legalarchive.orchestrator.ds.DataSourceStore;
import com.legalarchive.orchestrator.ds.IfsSupport;
import com.legalarchive.orchestrator.ds.SqlSupport;
import com.legalarchive.orchestrator.model.def.StepDef;
import com.legalarchive.orchestrator.model.def.Replacement;

/**
 * Executes the built-in step kinds in-process (no external process):
 *   sql      - run a query on a datasource; emit rowCount and first-row columns as vars
 *   ifscopy  - native IFS copy from AS400 to a local directory (JTOpen)
 *   filecopy - copy/move/list files between local directories by glob pattern
 *   setvar   - assign/compute run variables (supports simple +/- integer math)
 *
 * Each method writes a timestamped log to the step log file and returns a
 * StepExecutor.Result so the engine treats internal and external steps uniformly.
 */
@Component
public class InternalSteps {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final DataSourceStore dataSources;
    private final SqlSupport sql;
    private final IfsSupport ifs;
    private final com.legalarchive.orchestrator.config.AppProperties props;

    public InternalSteps(DataSourceStore dataSources, SqlSupport sql, IfsSupport ifs,
                         com.legalarchive.orchestrator.config.AppProperties props) {
        this.dataSources = dataSources;
        this.sql = sql;
        this.ifs = ifs;
        this.props = props;
    }

    public StepExecutor.Result run(String kind, StepDef step, Map<String, String> resolvedParams,
                                   Map<String, String> vars, Path logFile, RunControl control,
                                   com.legalarchive.orchestrator.model.run.StepExec se, Runnable onProgress) {
        StepExecutor.Result res = new StepExecutor.Result();
        BufferedWriter log = null;
        try {
            Files.createDirectories(logFile.getParent());
            log = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            final BufferedWriter flog = log;
            java.util.function.Consumer<String> line = new java.util.function.Consumer<String>() {
                public void accept(String s) {
                    try { flog.write("O\t" + LocalDateTime.now().format(TS) + "\t" + s); flog.newLine(); flog.flush(); }
                    catch (Exception ignored) {}
                }
            };

            if (control != null && control.aborted) { line.accept("aborted before start"); res.exitCode = -997; return res; }

            if ("sql".equals(kind)) {
                runSql(step, resolvedParams, vars, res, line, control);
            } else if ("ifscopy".equals(kind)) {
                runIfsCopy(step, resolvedParams, vars, res, line);
            } else if ("filecopy".equals(kind)) {
                runFileCopy(step, vars, res, line);
            } else if ("setvar".equals(kind)) {
                runSetVar(resolvedParams, vars, res, line);
            } else if ("validate".equals(kind)) {
                runValidate(step, resolvedParams, vars, res, line, se, onProgress);
            } else if ("encoding".equals(kind)) {
                runEncoding(step, resolvedParams, vars, res, line);
            } else if ("anonymize".equals(kind)) {
                runAnonymize(step, resolvedParams, vars, res, line, se, onProgress);
            } else if ("mask".equals(kind)) {
                runMask(step, resolvedParams, vars, res, line, se, onProgress);
            } else if ("csvreplace".equals(kind)) {
                runReplace(step, resolvedParams, vars, res, line);
            } else if ("split".equals(kind)) {
                runSplit(step, resolvedParams, vars, res, line);
            } else if ("safecopy".equals(kind)) {
                runSafeCopy(step, resolvedParams, vars, res, line);
            } else if ("dequote".equals(kind)) {
                runDequote(step, resolvedParams, vars, res, line);
            } else if ("elarxml".equals(kind)) {
                runElarXml(step, resolvedParams, vars, res, line);
            } else if ("elarcheck".equals(kind)) {
                runElarCheck(step, resolvedParams, vars, res, line);
            } else if ("json2csv".equals(kind)) {
                runJson2Csv(step, resolvedParams, vars, res, line);
            } else if ("csvsql".equals(kind)) {
                runCsvSql(step, resolvedParams, vars, res, line, control);
            } else if ("xlsx2csv".equals(kind)) {
                runXlsx2Csv(step, resolvedParams, vars, res, line);
            } else if ("diff".equals(kind)) {
                runDiff(step, resolvedParams, vars, res, line, control);
            } else if ("sqlreport".equals(kind)) {
                runSqlReport(step, resolvedParams, vars, res, line, control);
            } else {
                line.accept("unknown internal step kind: " + kind);
                res.exitCode = -996;
            }
        } catch (java.sql.SQLTimeoutException te) {
            res.timedOut = true;
            res.exitCode = res.exitCode == 0 ? 1 : res.exitCode;
            res.lastLines = "query exceeded the timeout (TIMEOUT SEC) and was aborted";
            safeLine(log, "ERROR: query timed out and was aborted - simplify the query (see notes) or raise TIMEOUT SEC");
        } catch (Exception e) {
            res.exitCode = res.exitCode == 0 ? 1 : res.exitCode;
            res.lastLines = e.getMessage();
            safeLine(log, "ERROR: " + e.getMessage());
        } finally {
            if (log != null) try { log.close(); } catch (Exception ignored) {}
        }
        return res;
    }

    // -------------------------------------------------------------- csvsql
    /**
     * Run an arbitrary SQL query (joins, aggregates, CTEs, window functions) across several CSV files
     * via a temporary file-mode H2 database, streaming the result through the shared CSV exporter.
     * The user writes only the SELECT over the table aliases; all H2 plumbing is generated here and
     * is invisible. Pure JDBC: the project compiles with no H2 on the classpath; H2 is needed only at
     * runtime (a missing driver fails this step with a clear message, like ARX).
     */
    private void runCsvSql(StepDef step, Map<String, String> params, Map<String, String> vars,
                           StepExecutor.Result res, java.util.function.Consumer<String> line, RunControl control) throws Exception {
        // 1) resolve output + delimiter + inputs
        String csvFile = VarResolver.resolve(step.csvFile, vars);
        if (csvFile == null || csvFile.trim().isEmpty()) { line.accept("csvsql: csvFile (output) is required"); res.exitCode = 2; return; }
        char delim = (step.delimiter != null && !step.delimiter.isEmpty()) ? step.delimiter.charAt(0) : ';';
        String query = VarResolver.resolve(step.query, vars);
        if (query == null || query.trim().isEmpty()) { line.accept("csvsql: query is required"); res.exitCode = 2; return; }

        if (step.inputs == null || step.inputs.isEmpty()) { line.accept("csvsql: at least one <input> is required"); res.exitCode = 2; return; }
        java.util.List<String[]> ins = new java.util.ArrayList<String[]>();   // {table, resolvedCsv}
        java.util.Set<String> seenTables = new java.util.HashSet<String>();
        for (com.legalarchive.orchestrator.model.def.CsvInput ci : step.inputs) {
            String table = ci.table == null ? "" : ci.table.trim();
            String csv = VarResolver.resolve(ci.csv, vars);
            if (table.isEmpty() || csv == null || csv.trim().isEmpty()) { line.accept("csvsql: every <input> needs both csv and table"); res.exitCode = 2; return; }
            if (!table.matches("[A-Za-z_][A-Za-z0-9_]*")) { line.accept("csvsql: invalid table name '" + table + "' (use letters, digits, underscore; not starting with a digit)"); res.exitCode = 2; return; }
            if (!seenTables.add(table.toUpperCase(java.util.Locale.ROOT))) { line.accept("csvsql: duplicate table name '" + table + "'"); res.exitCode = 2; return; }
            ins.add(new String[]{table, rebaseRel(csv, vars), VarResolver.resolve(ci.delimiter, vars), VarResolver.resolve(ci.index, vars)});
        }

        // 2) choose the H2 engine: in-memory (fast, no disk I/O) vs on-disk (for very large inputs).
        //    'auto' uses memory below the configured size threshold. LOCK_MODE=0 + a large CACHE_SIZE
        //    suit a single-threaded, throwaway DB.
        String stepDir = VarResolver.resolve("${stepDir}", vars);
        if (stepDir == null || stepDir.trim().isEmpty()) stepDir = new java.io.File(csvFile).getParent();
        String runId = vars.get("runId"); if (runId == null) runId = "run";

        long totalBytes = 0;
        for (String[] in : ins) { java.io.File cf = new java.io.File(in[1]); if (cf.isFile()) totalBytes += cf.length(); }
        String engineMode = xStr(params.get("engine"), props != null ? props.getCsvsqlEngine() : "auto").toLowerCase(java.util.Locale.ROOT);
        long memMaxBytes = (long) (props != null ? props.getCsvsqlMemMaxMb() : 700) * 1024L * 1024L;
        boolean useMem = "mem".equals(engineMode) || (!"file".equals(engineMode) && totalBytes < memMaxBytes);

        String tuning = ";LOCK_MODE=0;CACHE_SIZE=262144";
        java.io.File h2dir = null;
        String url;
        if (useMem) {
            url = "jdbc:h2:mem:csvsql_" + runId + "_" + step.id + "_" + System.nanoTime() + ";DB_CLOSE_DELAY=0" + tuning;
        } else {
            h2dir = new java.io.File(stepDir, "_h2");
            h2dir.mkdirs();
            url = "jdbc:h2:file:" + new java.io.File(h2dir, "q_" + runId + "_" + step.id).getAbsolutePath().replace('\\', '/')
                    + ";AUTO_SERVER=FALSE" + tuning;
        }
        line.accept("csvsql: engine=" + (useMem ? "mem" : "file") + ", inputs " + (totalBytes / 1048576) + " MB");
        // Query/statement timeout (seconds): the step's TIMEOUT SEC, else the app default. H2 aborts a
        // runaway query (e.g. an OR-join that degrades to a nested loop) instead of running for hours.
        final int qto = stepTimeoutSec(step.id, step.timeoutSec, vars, props != null ? props.getDefaultStepTimeoutSec() : 0);
        if (qto > 0) line.accept("csvsql: query timeout " + qto + "s");

        // 3) driver presence (pure JDBC; H2 is runtime-only)
        try {
            Class.forName("org.h2.Driver");
        } catch (ClassNotFoundException e) {
            line.accept("csvsql: H2 driver not on classpath — add com.h2database:h2 (see h2/README_H2.md)");
            res.exitCode = 2; return;
        }

        java.sql.Connection conn = null;
        try {
            conn = java.sql.DriverManager.getConnection(url, "sa", "");
            // 4) stage each input: CREATE TABLE <t> AS SELECT * FROM CSVREAD('path', NULL, 'opts')
            //    H2 reads the CSV header to derive the table columns at statement-PREPARE time, before
            //    bound parameters are applied; a bound file name therefore fails with
            //    'Parameter "fileName" is not set [90012]'. So inline the path and options as escaped
            //    SQL string literals (the table name is already regex-validated).
            //    The INPUT field separator is independent of the output one: use the per-input
            //    delimiter when set, otherwise auto-detect it from the header (comma/semicolon/tab/pipe).
            for (String[] in : ins) {
                String table = in[0], csv = in[1];
                String idelim = in.length > 2 ? in[2] : null;
                char sep = (idelim != null && !idelim.trim().isEmpty())
                        ? idelim.trim().charAt(0)
                        : detectDelim(new java.io.File(csv), delim);
                String csvOpts = "fieldSeparator=" + sep + " charset=UTF-8";
                java.sql.Statement ps = null;
                try {
                    ps = conn.createStatement();
                    if (qto > 0) ps.setQueryTimeout(qto);
                    if (control != null) control.statement = ps;
                    String sql = "CREATE TABLE " + table + " AS SELECT * FROM CSVREAD("
                            + sqlLit(csv) + ", NULL, " + sqlLit(csvOpts) + ")";
                    int n = ps.executeUpdate(sql);
                    line.accept("staged " + table + " <- " + csv + " (sep='" + sep + "', " + n + " rows)");
                } finally {
                    if (control != null) control.statement = null;
                    if (ps != null) try { ps.close(); } catch (Exception ignored) {}
                }
            }

            // 4b) optional indexes on join/filter columns — big speed-up for complex queries
            for (String[] in : ins) {
                String table = in[0];
                String idx = in.length > 3 ? in[3] : null;
                if (idx == null || idx.trim().isEmpty()) continue;
                for (String col : idx.split(",")) {
                    String c = col.trim();
                    if (c.isEmpty()) continue;
                    if (!c.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                        line.accept("csvsql: ignoring invalid index column '" + c + "' on " + table);
                        continue;
                    }
                    java.sql.Statement ix = null;
                    try {
                        ix = conn.createStatement();
                        if (qto > 0) ix.setQueryTimeout(qto);
                        ix.executeUpdate("CREATE INDEX IF NOT EXISTS ix_" + table + "_" + c + " ON " + table + "(" + c + ")");
                        line.accept("indexed " + table + "(" + c + ")");
                    } catch (Exception e) {
                        line.accept("csvsql: could not index " + table + "(" + c + "): " + e.getMessage());
                    } finally {
                        if (ix != null) try { ix.close(); } catch (Exception ignored) {}
                    }
                }
            }

            // 4c) refresh optimizer statistics (cheap; helps H2 pick join order/indexes)
            try {
                java.sql.Statement an = conn.createStatement();
                if (qto > 0) an.setQueryTimeout(qto);
                an.execute("ANALYZE");
                an.close();
            } catch (Exception ignored) {}

            // 5) run the user query verbatim and stream through the shared exporter
            line.accept("query: " + query);
            java.io.File out = new java.io.File(csvFile);
            if (out.getParentFile() != null) out.getParentFile().mkdirs();
            long maxRows = step.csvSplitRows > 0 ? step.csvSplitRows : 0;
            long maxBytes = step.csvSplitMb > 0 ? (long) step.csvSplitMb * 1024L * 1024L : 0;
            boolean trim = !"false".equalsIgnoreCase(params.get("trim"));
            java.sql.Statement st = null;
            try {
                st = conn.createStatement();
                if (qto > 0) st.setQueryTimeout(qto);
                if (control != null) control.statement = st;
                java.sql.ResultSet rs = st.executeQuery(query);
                // csvsql output is written WITHOUT BOM so it is safe to feed into another csvsql input
                SqlSupport.ExportResult er = sql.exportResultSet(rs, out, delim, false, maxRows, maxBytes, trim,
                        params.get("newlinesInValues"));
                res.outVars.put("rowCount", String.valueOf(er.rows));
                res.outVars.put("csvParts", String.valueOf(er.parts));
                res.outVars.put("csvFile", er.files.isEmpty() ? out.getAbsolutePath() : er.files.get(0));
                res.outVars.put("csvFiles", String.join(step.delimiter == null ? ";" : step.delimiter, er.files));
                res.outVars.put("csvRowCounts", joinCounts(er.partRows, step.delimiter == null ? ";" : step.delimiter));
                res.outVars.put("newlinesSanitized", String.valueOf(er.newlinesSanitized));
                if (er.parts > 1) line.accept("exported " + er.rows + " row(s) into " + er.parts + " CSV part(s)");
                else line.accept("exported " + er.rows + " row(s) to " + res.outVars.get("csvFile"));
                for (String f : er.files) line.accept("  " + f);
                for (Map.Entry<String, String> e : res.outVars.entrySet()) line.accept("##VAR " + e.getKey() + "=" + e.getValue());
                res.exitCode = 0;
            } finally {
                if (control != null) control.statement = null;
                if (st != null) try { st.close(); } catch (Exception ignored) {}
            }
        } finally {
            if (conn != null) try { conn.close(); } catch (Exception ignored) {}
            // 6) on-disk engine only: delete the temp DB files and the _h2 dir (mem engine leaves nothing)
            if (h2dir != null) {
                String dbBase = "q_" + runId + "_" + step.id;
                java.io.File[] dbFiles = h2dir.listFiles();
                if (dbFiles != null) for (java.io.File f : dbFiles) {
                    if (f.getName().startsWith(dbBase)) try { f.delete(); } catch (Exception ignored) {}
                }
                try { h2dir.delete(); } catch (Exception ignored) {}
            }
        }
    }

    // -------------------------------------------------------------- xlsx2csv
    /**
     * Read one sheet of an .xlsx workbook (POI streaming event API), project the selected columns,
     * and write a CSV through the shared {@link CsvWriter}. All cells are rendered to text
     * deterministically (see {@link com.legalarchive.orchestrator.xlsx.XlsxSheetReader}). Output has
     * no BOM so it drops straight into a {@code csvsql} {@code <input>}.
     */
    private void runDiff(StepDef step, Map<String, String> params, Map<String, String> vars,
                         StepExecutor.Result res, java.util.function.Consumer<String> line, RunControl control) throws Exception {
        String mode = blankToNull(params.get("mode"));
        if (mode == null) mode = "CSV_POSITIONAL";
        String fileA = params.get("fileA");
        String fileB = params.get("fileB");
        if (fileA == null || fileA.trim().isEmpty() || fileB == null || fileB.trim().isEmpty()) {
            line.accept("diff: both fileA and fileB are required"); res.exitCode = 2; return;
        }
        java.io.File fa = new java.io.File(fileA.trim());
        java.io.File fb = new java.io.File(fileB.trim());
        if (!fa.isFile()) { line.accept("diff: file A not found: " + fa.getAbsolutePath()); res.exitCode = 2; return; }
        if (!fb.isFile()) { line.accept("diff: file B not found: " + fb.getAbsolutePath()); res.exitCode = 2; return; }
        String dl = params.get("delimiter");
        char delim = (dl != null && dl.length() > 0) ? dl.charAt(0) : ';';
        String reportName = blankToNull(params.get("reportName"));
        if (reportName == null) reportName = step.id;
        boolean failOnDiff = "true".equalsIgnoreCase(params.get("failOnDifferences"));

        String stepDir = vars.get("stepDir");
        java.io.File outDir = (stepDir != null && !stepDir.trim().isEmpty()) ? new java.io.File(stepDir) : fa.getParentFile();
        if (outDir != null) outDir.mkdirs();
        java.io.File reportMd = new java.io.File(outDir, reportName + "_recon_report.md");
        java.io.File diffCsv = new java.io.File(outDir, reportName + "_recon_differences.csv");

        if (control != null && control.aborted) { line.accept("diff: aborted before start"); res.exitCode = -997; return; }
        int qto = stepTimeoutSec(step.id, step.timeoutSec, vars, props != null ? props.getDefaultStepTimeoutSec() : 0);
        if ("TEXT_SET".equalsIgnoreCase(mode)) { runDiffTextSet(params, fa, fb, reportName, reportMd, diffCsv, failOnDiff, res, line, control); return; }
        if ("TEXT".equalsIgnoreCase(mode)) { runDiffText(params, fa, fb, reportName, reportMd, diffCsv, failOnDiff, res, line, control); return; }
        if ("CSV_KEY".equalsIgnoreCase(mode)) { runDiffKey(params, fa, fb, delim, reportName, reportMd, diffCsv, failOnDiff, res, line, qto, control); return; }
        if (!"CSV_POSITIONAL".equalsIgnoreCase(mode)) { line.accept("diff: mode '" + mode + "' not implemented yet"); res.exitCode = 2; return; }

        java.io.BufferedReader ra = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(fa), java.nio.charset.StandardCharsets.UTF_8));
        java.io.BufferedReader rb = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(fb), java.nio.charset.StandardCharsets.UTF_8));
        java.io.BufferedWriter dw = new java.io.BufferedWriter(new java.io.OutputStreamWriter(new java.io.FileOutputStream(diffCsv), java.nio.charset.StandardCharsets.UTF_8));
        try {
            List<String> colsA = parseCsvLine(stripBom(ra.readLine()), delim);
            List<String> colsB = parseCsvLine(stripBom(rb.readLine()), delim);
            Map<String, Integer> idxB = new java.util.LinkedHashMap<String, Integer>();
            for (int i = 0; i < colsB.size(); i++) if (!idxB.containsKey(colsB.get(i))) idxB.put(colsB.get(i), i);
            List<String> shared = new ArrayList<String>();
            List<int[]> pairs = new ArrayList<int[]>();
            for (int i = 0; i < colsA.size(); i++) {
                String c = colsA.get(i);
                if (idxB.containsKey(c)) { shared.add(c); pairs.add(new int[]{ i, idxB.get(c) }); }
            }
            if (shared.isEmpty()) { line.accept("diff: the two files share no column names - nothing to compare"); res.exitCode = 2; return; }
            long[] attrDiff = new long[shared.size()];
            long rowsCompared = 0, valueMismatch = 0, missingInA = 0, missingInB = 0;
            dw.write("rowIndex,attribute,valueA,valueB,category"); dw.write("\r\n");
            long idx = 0;
            while (true) {
                String la = ra.readLine();
                String lb = rb.readLine();
                if (la == null && lb == null) break;
                idx++;
                if (control != null && control.aborted && (idx & 8191L) == 0L) { line.accept("diff: aborted"); res.exitCode = -997; return; }
                if (la != null && lb != null) {
                    rowsCompared++;
                    List<String> rowA = parseCsvLine(la, delim);
                    List<String> rowB = parseCsvLine(lb, delim);
                    for (int k = 0; k < pairs.size(); k++) {
                        int ai = pairs.get(k)[0], bi = pairs.get(k)[1];
                        String va = ai < rowA.size() ? rowA.get(ai) : "";
                        String vb = bi < rowB.size() ? rowB.get(bi) : "";
                        if (!va.equals(vb)) { valueMismatch++; attrDiff[k]++; diffRow(dw, idx, shared.get(k), va, vb, "value_mismatch"); }
                    }
                } else if (la != null) { missingInB++; diffRow(dw, idx, "(row)", la, "", "missing_in_B"); }
                else { missingInA++; diffRow(dw, idx, "(row)", "", lb, "missing_in_A"); }
            }
            long totalDiff = valueMismatch + missingInA + missingInB;
            long cells = rowsCompared * (long) shared.size();
            dw.flush();

            StringBuilder md = new StringBuilder();
            md.append("# Reconciliation report - ").append(reportName).append("\n\n");
            md.append(totalDiff == 0 ? "**PERFECT MATCH**\n\n" : "**DIFFERENCES**\n\n");
            md.append("## Configuration\n\n");
            md.append("- Mode: `CSV_POSITIONAL`\n");
            md.append("- File A: `").append(fa.getAbsolutePath()).append("`\n");
            md.append("- File B: `").append(fb.getAbsolutePath()).append("`\n");
            md.append("- Sources produced: A @ ").append(fileStamp(fa)).append(", B @ ").append(fileStamp(fb)).append("\n");
            md.append("- Delimiter: `").append(delim).append("`\n\n");
            md.append("## Summary\n\n");
            md.append("- Rows compared (aligned by position): ").append(rowsCompared).append("\n");
            md.append("- Attributes compared (shared columns): ").append(shared.size()).append("\n");
            md.append("- Shared columns: ").append(String.join(", ", shared)).append("\n");
            md.append("- Rows in A: ").append(rowsCompared + missingInB).append(", rows in B: ").append(rowsCompared + missingInA).append("\n");
            md.append("- Total attributes checked (shared columns x rows, summed over both files): ").append((long) shared.size() * (2 * rowsCompared + missingInA + missingInB)).append("\n\n");
            md.append("## Totals\n\n");
            md.append("- Cell mismatches: ").append(valueMismatch);
            if (cells > 0) md.append(" (").append(pct(valueMismatch, cells)).append("% of ").append(cells).append(" checked cells)");
            md.append("\n");
            md.append("- Rows only in A (missing in B): ").append(missingInB).append("\n");
            md.append("- Rows only in B (missing in A): ").append(missingInA).append("\n");
            md.append("- Total differences: ").append(totalDiff).append("\n\n");
            md.append("## Per-attribute\n\n");
            md.append("| Attribute | Differing rows | % of rows compared |\n");
            md.append("| --- | ---: | ---: |\n");
            for (int k = 0; k < shared.size(); k++) {
                md.append("| ").append(shared.get(k)).append(" | ").append(attrDiff[k]).append(" | ")
                  .append(rowsCompared > 0 ? pct(attrDiff[k], rowsCompared) : "0.00").append(" |\n");
            }
            java.nio.file.Files.write(reportMd.toPath(), md.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));

            res.outVars.put("diffResult", totalDiff == 0 ? "PERFECT_MATCH" : "DIFFERENCES");
            res.outVars.put("diffCount", String.valueOf(totalDiff));
            res.outVars.put("rowsCompared", String.valueOf(rowsCompared));
            res.outVars.put("attributesCompared", String.valueOf(shared.size()));
            res.outVars.put("attributesChecked", String.valueOf((long) shared.size() * (2 * rowsCompared + missingInA + missingInB)));
            res.outVars.put("valueMismatches", String.valueOf(valueMismatch));
            res.outVars.put("missingInA", String.valueOf(missingInA));
            res.outVars.put("missingInB", String.valueOf(missingInB));
            res.outVars.put("reportFile", reportMd.getAbsolutePath());
            res.outVars.put("differencesFile", diffCsv.getAbsolutePath());
            line.accept("diff: " + (totalDiff == 0 ? "PERFECT MATCH" : ("DIFFERENCES (" + totalDiff + ")"))
                    + " over " + rowsCompared + " aligned row(s), " + shared.size() + " shared column(s)");
            line.accept("diff: report " + reportMd.getAbsolutePath());
            for (Map.Entry<String, String> e : res.outVars.entrySet()) line.accept("##VAR " + e.getKey() + "=" + e.getValue());
            res.exitCode = (failOnDiff && totalDiff > 0) ? 1 : 0;
        } finally {
            try { ra.close(); } catch (Exception ignored) {}
            try { rb.close(); } catch (Exception ignored) {}
            try { dw.close(); } catch (Exception ignored) {}
        }
    }

    private static String fileStamp(java.io.File f) {
        try { return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(f.lastModified())); }
        catch (Exception e) { return "?"; }
    }
    private static String pct(long n, long d) {
        if (d <= 0) return "0.00";
        return String.format(java.util.Locale.ROOT, "%.2f", (100.0 * n) / d);
    }
    private static String stripBom(String s) {
        if (s == null) return "";
        if (!s.isEmpty() && s.charAt(0) == '\uFEFF') return s.substring(1);
        return s;
    }
    private void runDiffKey(Map<String, String> params, java.io.File fa, java.io.File fb, char delim,
                            String reportName, java.io.File reportMd, java.io.File diffCsv, boolean failOnDiff,
                            StepExecutor.Result res, java.util.function.Consumer<String> line, int qto, RunControl control) throws Exception {
        String keysA = blankToNull(params.get("keysA"));
        String keysB = blankToNull(params.get("keysB"));
        if (keysA == null || keysB == null) { line.accept("diff CSV_KEY: keysA and keysB are required"); res.exitCode = 2; return; }
        List<String> kaCols = splitCols(keysA);
        List<String> kbCols = splitCols(keysB);
        if (kaCols.isEmpty() || kbCols.isEmpty()) { line.accept("diff CSV_KEY: keysA and keysB must name at least one column"); res.exitCode = 2; return; }
        List<String> kaExpr = new ArrayList<String>();
        for (String cc : kaCols) { String e = keyColSql(cc); if (e == null) { line.accept("diff CSV_KEY: invalid key column '" + cc + "' (use COL, COL:L<n> or COL:R<n>)"); res.exitCode = 2; return; } kaExpr.add(e); }
        List<String> kbExpr = new ArrayList<String>();
        for (String cc : kbCols) { String e = keyColSql(cc); if (e == null) { line.accept("diff CSV_KEY: invalid key column '" + cc + "' (use COL, COL:L<n> or COL:R<n>)"); res.exitCode = 2; return; } kbExpr.add(e); }
        List<String> mLabel = new ArrayList<String>();
        List<String> mAexpr = new ArrayList<String>();
        List<String> mBexpr = new ArrayList<String>();
        List<Boolean> mNum = new ArrayList<Boolean>();
        List<String> mAgg = new ArrayList<String>();
        java.util.TreeSet<Integer> midx = new java.util.TreeSet<Integer>();
        for (String key : params.keySet()) {
            if (key.startsWith("match.") && key.endsWith(".a")) {
                try { midx.add(Integer.parseInt(key.substring(6, key.length() - 2))); } catch (NumberFormatException ignore) {}
            }
        }
        for (Integer n : midx) {
            String a = params.get("match." + n + ".a");
            if (a == null) continue;
            String b = params.get("match." + n + ".b");
            List<String> ac = splitCols(a);
            List<String> bc = splitCols(b == null ? "" : b);
            if (ac.isEmpty() || bc.isEmpty()) { line.accept("diff CSV_KEY: match " + n + " needs at least one A column and one B column"); res.exitCode = 2; return; }
            List<String> acExpr = new ArrayList<String>();
            for (String cc : ac) { String e = keyColSql(cc); if (e == null) { line.accept("diff CSV_KEY: invalid A column '" + cc + "' in match " + n + " (use COL, COL:L<n> or COL:R<n>)"); res.exitCode = 2; return; } acExpr.add(e); }
            List<String> bcExpr = new ArrayList<String>();
            for (String cc : bc) { String e = keyColSql(cc); if (e == null) { line.accept("diff CSV_KEY: invalid B column '" + cc + "' in match " + n + " (use COL, COL:L<n> or COL:R<n>)"); res.exitCode = 2; return; } bcExpr.add(e); }
            String sep = params.get("match." + n + ".sep"); if (sep == null) sep = " ";
            String agg = blankToNull(params.get("match." + n + ".agg")); if (agg == null) agg = "value"; agg = agg.toLowerCase();
            if (!agg.equals("value") && !agg.equals("sum") && !agg.equals("count") && !agg.equals("count_distinct")) { line.accept("diff CSV_KEY: match " + n + " has unknown agg '" + agg + "' (value|sum|count|count_distinct)"); res.exitCode = 2; return; }
            boolean num = "numeric".equalsIgnoreCase(params.get("match." + n + ".type")) || !agg.equals("value");
            String label = blankToNull(params.get("match." + n + ".label"));
            if (label == null) label = String.join("+", ac);
            mLabel.add(label);
            mAexpr.add(concatExpr(acExpr, sqlStr(sep)));
            mBexpr.add(concatExpr(bcExpr, sqlStr(sep)));
            mNum.add(num);
            mAgg.add(agg);
        }
        if (mLabel.isEmpty()) { line.accept("diff CSV_KEY: at least one match is required"); res.exitCode = 2; return; }

        String keyExprA = concatExpr(kaExpr, "CHAR(1)");
        String keyExprB = concatExpr(kbExpr, "CHAR(1)");
        StringBuilder ag = new StringBuilder("SELECT (" + keyExprA + ") k");
        StringBuilder bg = new StringBuilder("SELECT (" + keyExprB + ") k");
        for (int m = 0; m < mLabel.size(); m++) {
            String[] eA = aggExprs(mAexpr.get(m), mAgg.get(m));
            String[] eB = aggExprs(mBexpr.get(m), mAgg.get(m));
            ag.append(", ").append(eA[0]).append(" d").append(m).append(", ").append(eA[1]).append(" v").append(m);
            bg.append(", ").append(eB[0]).append(" d").append(m).append(", ").append(eB[1]).append(" v").append(m);
        }
        ag.append(" FROM ta GROUP BY (").append(keyExprA).append(")");
        bg.append(" FROM tb GROUP BY (").append(keyExprB).append(")");
        StringBuilder sel1 = new StringBuilder("SELECT ag.k k, ag.k ak, bg.k bk");
        StringBuilder sel2 = new StringBuilder("SELECT bg.k k, CAST(NULL AS VARCHAR) ak, bg.k bk");
        for (int m = 0; m < mLabel.size(); m++) {
            sel1.append(", ag.d").append(m).append(" ad").append(m).append(", ag.v").append(m).append(" av").append(m)
                .append(", bg.d").append(m).append(" bd").append(m).append(", bg.v").append(m).append(" bv").append(m);
            sel2.append(", CAST(NULL AS INT) ad").append(m).append(", CAST(NULL AS VARCHAR) av").append(m)
                .append(", bg.d").append(m).append(" bd").append(m).append(", bg.v").append(m).append(" bv").append(m);
        }
        String query = sel1 + " FROM ag LEFT JOIN bg ON ag.k=bg.k "
                + "UNION ALL " + sel2 + " FROM bg LEFT JOIN ag ON ag.k=bg.k WHERE ag.k IS NULL";

        try { Class.forName("org.h2.Driver"); }
        catch (Throwable t) { line.accept("diff CSV_KEY: H2 driver not on classpath - add com.h2database:h2 (see h2/README_H2.md)"); res.exitCode = 2; return; }
        String url = "jdbc:h2:mem:diff_" + System.nanoTime() + ";DB_CLOSE_DELAY=0";
        String opts = "fieldSeparator=" + delim + " charset=UTF-8";
        java.sql.Connection conn = null;
        java.io.BufferedWriter dw = new java.io.BufferedWriter(new java.io.OutputStreamWriter(new java.io.FileOutputStream(diffCsv), java.nio.charset.StandardCharsets.UTF_8));
        try {
            conn = java.sql.DriverManager.getConnection(url, "sa", "");
            java.sql.Statement st = conn.createStatement();
            if (qto > 0) st.setQueryTimeout(qto);
            if (control != null) control.statement = st;
            if (control != null && control.aborted) { line.accept("diff CSV_KEY: aborted"); res.exitCode = -997; return; }
            st.executeUpdate("CREATE TABLE ta AS SELECT * FROM CSVREAD(" + sqlStr(fa.getAbsolutePath().replace('\\', '/')) + ", NULL, " + sqlStr(opts) + ")");
            st.executeUpdate("CREATE TABLE tb AS SELECT * FROM CSVREAD(" + sqlStr(fb.getAbsolutePath().replace('\\', '/')) + ", NULL, " + sqlStr(opts) + ")");
            st.executeUpdate("CREATE LOCAL TEMPORARY TABLE ag AS " + ag);
            st.executeUpdate("CREATE LOCAL TEMPORARY TABLE bg AS " + bg);
            st.executeUpdate("CREATE INDEX ix_ag ON ag(k)");
            st.executeUpdate("CREATE INDEX ix_bg ON bg(k)");
            long rowsA = 0, rowsB = 0;
            { java.sql.ResultSet rc = st.executeQuery("SELECT COUNT(*) FROM ta"); if (rc.next()) rowsA = rc.getLong(1); }
            { java.sql.ResultSet rc = st.executeQuery("SELECT COUNT(*) FROM tb"); if (rc.next()) rowsB = rc.getLong(1); }
            dw.write("key,match,valueA,valueB,category"); dw.write("\r\n");
            long[] mDiff = new long[mLabel.size()];
            long keysCompared = 0, valueMismatch = 0, inconsistent = 0, missingInA = 0, missingInB = 0;
            java.sql.ResultSet r = st.executeQuery(query);
            while (r.next()) {
                String k = r.getString("k");
                String ak = r.getString("ak");
                String bk = r.getString("bk");
                if (ak == null) { missingInA++; diffRow2(dw, k, "(key)", "", "", "missing_in_A"); continue; }
                if (bk == null) { missingInB++; diffRow2(dw, k, "(key)", "", "", "missing_in_B"); continue; }
                keysCompared++;
                for (int m = 0; m < mLabel.size(); m++) {
                    int ad = r.getInt("ad" + m), bd = r.getInt("bd" + m);
                    String av = r.getString("av" + m), bv = r.getString("bv" + m);
                    if (ad > 1 || bd > 1) { inconsistent++; mDiff[m]++; diffRow2(dw, k, mLabel.get(m), av == null ? "" : av, bv == null ? "" : bv, "inconsistent_key"); continue; }
                    boolean eq = mNum.get(m) ? numEq(av, bv) : java.util.Objects.equals(av, bv);
                    if (!eq) { valueMismatch++; mDiff[m]++; diffRow2(dw, k, mLabel.get(m), av == null ? "" : av, bv == null ? "" : bv, "value_mismatch"); }
                }
            }
            dw.flush();
            long totalDiff = valueMismatch + inconsistent + missingInA + missingInB;
            long cells = keysCompared * (long) mLabel.size();
            StringBuilder md = new StringBuilder();
            md.append("# Reconciliation report - ").append(reportName).append("\n\n");
            md.append(totalDiff == 0 ? "**PERFECT MATCH**\n\n" : "**DIFFERENCES**\n\n");
            md.append("## Configuration\n\n");
            md.append("- Mode: `CSV_KEY`\n");
            md.append("- File A: `").append(fa.getAbsolutePath()).append("`\n");
            md.append("- File B: `").append(fb.getAbsolutePath()).append("`\n");
            md.append("- Sources produced: A @ ").append(fileStamp(fa)).append(", B @ ").append(fileStamp(fb)).append("\n");
            md.append("- Key A: `").append(String.join(", ", kaCols)).append("`\n");
            md.append("- Key B: `").append(String.join(", ", kbCols)).append("`\n");
            md.append("- Matches:\n");
            for (int m = 0; m < mLabel.size(); m++) md.append("  - ").append(mLabel.get(m)).append(" (").append(mAgg.get(m)).append(", ").append(mNum.get(m) ? "numeric" : "text").append(")\n");
            md.append("\n## Summary\n\n");
            md.append("- Keys compared (present on both sides): ").append(keysCompared).append("\n");
            md.append("- Attributes compared (matches): ").append(mLabel.size()).append("\n");
            md.append("- Rows in A: ").append(rowsA).append(", rows in B: ").append(rowsB).append("\n");
            md.append("- Total attributes checked (matches x rows, summed over both files): ").append(mLabel.size() * (rowsA + rowsB)).append("\n\n");
            md.append("## Totals\n\n");
            md.append("- Value mismatches: ").append(valueMismatch);
            if (cells > 0) md.append(" (").append(pct(valueMismatch, cells)).append("% of ").append(cells).append(" checked cells)");
            md.append("\n");
            md.append("- Inconsistent keys (a match differs within one side): ").append(inconsistent).append("\n");
            md.append("- Keys only in A (missing in B): ").append(missingInB).append("\n");
            md.append("- Keys only in B (missing in A): ").append(missingInA).append("\n");
            md.append("- Total differences: ").append(totalDiff).append("\n\n");
            md.append("## Per-match\n\n");
            md.append("| Match | Differing keys | % of keys compared |\n| --- | ---: | ---: |\n");
            for (int m = 0; m < mLabel.size(); m++) md.append("| ").append(mLabel.get(m)).append(" | ").append(mDiff[m]).append(" | ").append(keysCompared > 0 ? pct(mDiff[m], keysCompared) : "0.00").append(" |\n");
            java.nio.file.Files.write(reportMd.toPath(), md.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));

            res.outVars.put("diffResult", totalDiff == 0 ? "PERFECT_MATCH" : "DIFFERENCES");
            res.outVars.put("diffCount", String.valueOf(totalDiff));
            res.outVars.put("keysCompared", String.valueOf(keysCompared));
            res.outVars.put("attributesChecked", String.valueOf(mLabel.size() * (rowsA + rowsB)));
            res.outVars.put("attributesCompared", String.valueOf(mLabel.size()));
            res.outVars.put("valueMismatches", String.valueOf(valueMismatch));
            res.outVars.put("inconsistentKeys", String.valueOf(inconsistent));
            res.outVars.put("missingInA", String.valueOf(missingInA));
            res.outVars.put("missingInB", String.valueOf(missingInB));
            res.outVars.put("reportFile", reportMd.getAbsolutePath());
            res.outVars.put("differencesFile", diffCsv.getAbsolutePath());
            line.accept("diff CSV_KEY: " + (totalDiff == 0 ? "PERFECT MATCH" : ("DIFFERENCES (" + totalDiff + ")")) + " over " + keysCompared + " key(s), " + mLabel.size() + " match(es)");
            line.accept("diff: report " + reportMd.getAbsolutePath());
            for (Map.Entry<String, String> e : res.outVars.entrySet()) line.accept("##VAR " + e.getKey() + "=" + e.getValue());
            res.exitCode = (failOnDiff && totalDiff > 0) ? 1 : 0;
        } finally {
            if (control != null) control.statement = null;
            try { dw.close(); } catch (Exception ignored) {}
            if (conn != null) try { conn.close(); } catch (Exception ignored) {}
        }
    }

    private static boolean numEq(String a, String b) {
        if (a == null || b == null) return false;
        try { return new java.math.BigDecimal(a.trim()).compareTo(new java.math.BigDecimal(b.trim())) == 0; }
        catch (Exception e) { return false; }
    }
    private static List<String> splitCols(String csv) {
        List<String> out = new ArrayList<String>();
        if (csv == null) return out;
        for (String p : csv.split(",")) { String t = p.trim(); if (!t.isEmpty()) out.add(t); }
        return out;
    }
    private static boolean isIdent(String s) { return s != null && s.matches("[A-Za-z_][A-Za-z0-9_]*"); }
    private static String keyColSql(String tok) {
        if (tok == null) return null;
        int c = tok.indexOf(':');
        if (c < 0) return isIdent(tok) ? tok : null;
        String col = tok.substring(0, c).trim();
        String suf = tok.substring(c + 1).trim();
        if (!isIdent(col) || suf.length() < 2) return null;
        char side = Character.toUpperCase(suf.charAt(0));
        int nlen;
        try { nlen = Integer.parseInt(suf.substring(1).trim()); } catch (NumberFormatException e) { return null; }
        if (nlen < 1) return null;
        if (side == 'L') return "LEFT(" + col + ", " + nlen + ")";
        if (side == 'R') return "RIGHT(" + col + ", " + nlen + ")";
        return null;
    }
    private static String concatExpr(List<String> cols, String sepSql) {
        if (cols.size() == 1) return cols.get(0);
        return "CONCAT_WS(" + sepSql + ", " + String.join(", ", cols) + ")";
    }
    private static String[] aggExprs(String expr, String agg) {
        if ("sum".equals(agg)) return new String[]{ "1", "CAST(SUM(CAST(NULLIF(TRIM(" + expr + "), '') AS DECIMAL(38,10))) AS VARCHAR)" };
        if ("count".equals(agg)) return new String[]{ "1", "CAST(COUNT(*) AS VARCHAR)" };
        if ("count_distinct".equals(agg)) return new String[]{ "1", "CAST(COUNT(DISTINCT (" + expr + ")) AS VARCHAR)" };
        return new String[]{ "COUNT(DISTINCT (" + expr + "))", "MAX(" + expr + ")" };
    }
    private static String sqlStr(String s) { return "'" + (s == null ? "" : s.replace("'", "''")) + "'"; }
    private static void diffRow2(java.io.BufferedWriter w, String key, String match, String va, String vb, String cat) throws java.io.IOException {
        w.write(dq(key)); w.write(',');
        w.write(dq(match)); w.write(',');
        w.write(dq(va)); w.write(',');
        w.write(dq(vb)); w.write(',');
        w.write(dq(cat)); w.write("\r\n");
    }

    private void runDiffTextSet(Map<String, String> params, java.io.File fa, java.io.File fb,
                                String reportName, java.io.File reportMd, java.io.File diffCsv, boolean failOnDiff,
                                StepExecutor.Result res, java.util.function.Consumer<String> line, RunControl control) throws Exception {
        if (control != null && control.aborted) { line.accept("diff: aborted before start"); res.exitCode = -997; return; }
        List<String> a = readLines(fa);
        List<String> b = readLines(fb);
        int n = a.size(), m = b.size();
        java.util.HashSet<String> setA = new java.util.HashSet<String>(a);
        java.util.HashSet<String> setB = new java.util.HashSet<String>(b);
        java.util.LinkedHashSet<String> onlyA = new java.util.LinkedHashSet<String>();
        for (String ln : a) if (!setB.contains(ln)) onlyA.add(ln);
        java.util.LinkedHashSet<String> onlyB = new java.util.LinkedHashSet<String>();
        for (String ln : b) if (!setA.contains(ln)) onlyB.add(ln);
        long common = 0;
        for (String ln : setA) if (setB.contains(ln)) common++;
        java.io.BufferedWriter dw = new java.io.BufferedWriter(new java.io.OutputStreamWriter(new java.io.FileOutputStream(diffCsv), java.nio.charset.StandardCharsets.UTF_8));
        try {
            dw.write("line,label,valueA,valueB,category"); dw.write("\r\n");
            for (String ln : onlyA) diffRow2(dw, "", "(line)", ln, "", "only_in_A");
            for (String ln : onlyB) diffRow2(dw, "", "(line)", "", ln, "only_in_B");
            dw.flush();
            long totalDiff = onlyA.size() + onlyB.size();
            StringBuilder md = new StringBuilder();
            md.append("# Reconciliation report - ").append(reportName).append("\n\n");
            md.append(totalDiff == 0 ? "**PERFECT MATCH**\n\n" : "**DIFFERENCES**\n\n");
            md.append("## Configuration\n\n");
            md.append("- Mode: `TEXT_SET` (line membership, order-independent)\n");
            md.append("- File A: `").append(fa.getAbsolutePath()).append("`\n");
            md.append("- File B: `").append(fb.getAbsolutePath()).append("`\n");
            md.append("- Sources produced: A @ ").append(fileStamp(fa)).append(", B @ ").append(fileStamp(fb)).append("\n\n");
            md.append("## Summary\n\n");
            md.append("- Lines in A: ").append(n).append(" (distinct ").append(setA.size()).append(")\n");
            md.append("- Lines in B: ").append(m).append(" (distinct ").append(setB.size()).append(")\n");
            md.append("- Common distinct lines: ").append(common).append("\n\n");
            md.append("## Totals\n\n");
            md.append("- Lines only in A (A -> B, not present in B): ").append(onlyA.size()).append("\n");
            md.append("- Lines only in B (B -> A, not present in A): ").append(onlyB.size()).append("\n");
            md.append("- Total differing lines: ").append(totalDiff).append("\n");
            java.nio.file.Files.write(reportMd.toPath(), md.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));

            res.outVars.put("diffResult", totalDiff == 0 ? "PERFECT_MATCH" : "DIFFERENCES");
            res.outVars.put("diffCount", String.valueOf(totalDiff));
            res.outVars.put("linesA", String.valueOf(n));
            res.outVars.put("linesB", String.valueOf(m));
            res.outVars.put("distinctA", String.valueOf(setA.size()));
            res.outVars.put("distinctB", String.valueOf(setB.size()));
            res.outVars.put("commonLines", String.valueOf(common));
            res.outVars.put("onlyInA", String.valueOf(onlyA.size()));
            res.outVars.put("onlyInB", String.valueOf(onlyB.size()));
            res.outVars.put("reportFile", reportMd.getAbsolutePath());
            res.outVars.put("differencesFile", diffCsv.getAbsolutePath());
            line.accept("diff TEXT_SET: " + (totalDiff == 0 ? "PERFECT MATCH" : ("DIFFERENCES (" + totalDiff + ")")) + " - only_in_A=" + onlyA.size() + " only_in_B=" + onlyB.size());
            line.accept("diff: report " + reportMd.getAbsolutePath());
            for (Map.Entry<String, String> e : res.outVars.entrySet()) line.accept("##VAR " + e.getKey() + "=" + e.getValue());
            res.exitCode = (failOnDiff && totalDiff > 0) ? 1 : 0;
        } finally {
            try { dw.close(); } catch (Exception ignored) {}
        }
    }

    private void runDiffText(Map<String, String> params, java.io.File fa, java.io.File fb,
                             String reportName, java.io.File reportMd, java.io.File diffCsv, boolean failOnDiff,
                             StepExecutor.Result res, java.util.function.Consumer<String> line, RunControl control) throws Exception {
        if (control != null && control.aborted) { line.accept("diff: aborted before start"); res.exitCode = -997; return; }
        int cap = 2000;
        try { String cc = blankToNull(params.get("textMaxLines")); if (cc != null) cap = Integer.parseInt(cc.trim()); } catch (NumberFormatException ignore) {}
        if (cap < 1) cap = 1;
        List<String> a = readLines(fa);
        List<String> b = readLines(fb);
        int n = a.size(), m = b.size();
        boolean fallback = (n > cap || m > cap);
        java.io.BufferedWriter dw = new java.io.BufferedWriter(new java.io.OutputStreamWriter(new java.io.FileOutputStream(diffCsv), java.nio.charset.StandardCharsets.UTF_8));
        long onlyInA = 0, onlyInB = 0, changed = 0, common = 0;
        try {
            dw.write("line,label,valueA,valueB,category"); dw.write("\r\n");
            if (!fallback) {
                int[][] dp = new int[n + 1][m + 1];
                for (int i = n - 1; i >= 0; i--) for (int j = m - 1; j >= 0; j--)
                    dp[i][j] = a.get(i).equals(b.get(j)) ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
                common = dp[0][0];
                int i = 0, j = 0;
                while (i < n && j < m) {
                    if (a.get(i).equals(b.get(j))) { i++; j++; }
                    else if (dp[i + 1][j] >= dp[i][j + 1]) { onlyInA++; diffRow2(dw, String.valueOf(i + 1), "(line)", a.get(i), "", "only_in_A"); i++; }
                    else { onlyInB++; diffRow2(dw, String.valueOf(j + 1), "(line)", "", b.get(j), "only_in_B"); j++; }
                }
                while (i < n) { onlyInA++; diffRow2(dw, String.valueOf(i + 1), "(line)", a.get(i), "", "only_in_A"); i++; }
                while (j < m) { onlyInB++; diffRow2(dw, String.valueOf(j + 1), "(line)", "", b.get(j), "only_in_B"); j++; }
            } else {
                int min = Math.min(n, m);
                for (int i = 0; i < min; i++) {
                    if (a.get(i).equals(b.get(i))) common++;
                    else { changed++; diffRow2(dw, String.valueOf(i + 1), "(line)", a.get(i), b.get(i), "line_changed"); }
                }
                for (int i = min; i < n; i++) { onlyInA++; diffRow2(dw, String.valueOf(i + 1), "(line)", a.get(i), "", "only_in_A"); }
                for (int j = min; j < m; j++) { onlyInB++; diffRow2(dw, String.valueOf(j + 1), "(line)", "", b.get(j), "only_in_B"); }
            }
            dw.flush();
            long totalDiff = onlyInA + onlyInB + changed;
            int larger = Math.max(n, m);
            String pctd = larger > 0 ? pct(larger - (int) common, larger) : "0.00";
            StringBuilder md = new StringBuilder();
            md.append("# Reconciliation report - ").append(reportName).append("\n\n");
            md.append(totalDiff == 0 ? "**PERFECT MATCH**\n\n" : "**DIFFERENCES**\n\n");
            md.append("## Configuration\n\n");
            md.append("- Mode: `TEXT`").append(fallback ? " (streaming positional fallback: a file exceeds textMaxLines=" + cap + ")" : "").append("\n");
            md.append("- File A: `").append(fa.getAbsolutePath()).append("`\n");
            md.append("- File B: `").append(fb.getAbsolutePath()).append("`\n");
            md.append("- Sources produced: A @ ").append(fileStamp(fa)).append(", B @ ").append(fileStamp(fb)).append("\n\n");
            md.append("## Summary\n\n");
            md.append("- Lines in A: ").append(n).append("\n");
            md.append("- Lines in B: ").append(m).append("\n");
            md.append("- Common lines: ").append(common).append("\n\n");
            md.append("## Totals\n\n");
            md.append("- Lines only in A: ").append(onlyInA).append("\n");
            md.append("- Lines only in B: ").append(onlyInB).append("\n");
            if (fallback) md.append("- Changed lines (same position): ").append(changed).append("\n");
            md.append("- Total differing lines: ").append(totalDiff);
            if (larger > 0) md.append(" (").append(pctd).append("% over the larger file)");
            md.append("\n");
            java.nio.file.Files.write(reportMd.toPath(), md.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));

            res.outVars.put("diffResult", totalDiff == 0 ? "PERFECT_MATCH" : "DIFFERENCES");
            res.outVars.put("diffCount", String.valueOf(totalDiff));
            res.outVars.put("linesA", String.valueOf(n));
            res.outVars.put("linesB", String.valueOf(m));
            res.outVars.put("commonLines", String.valueOf(common));
            res.outVars.put("onlyInA", String.valueOf(onlyInA));
            res.outVars.put("onlyInB", String.valueOf(onlyInB));
            if (fallback) res.outVars.put("changedLines", String.valueOf(changed));
            res.outVars.put("reportFile", reportMd.getAbsolutePath());
            res.outVars.put("differencesFile", diffCsv.getAbsolutePath());
            line.accept("diff TEXT: " + (totalDiff == 0 ? "PERFECT MATCH" : ("DIFFERENCES (" + totalDiff + ")")) + " - A=" + n + " B=" + m + " common=" + common + (fallback ? " [positional fallback]" : ""));
            line.accept("diff: report " + reportMd.getAbsolutePath());
            for (Map.Entry<String, String> e : res.outVars.entrySet()) line.accept("##VAR " + e.getKey() + "=" + e.getValue());
            res.exitCode = (failOnDiff && totalDiff > 0) ? 1 : 0;
        } finally {
            try { dw.close(); } catch (Exception ignored) {}
        }
    }

    private static List<String> readLines(java.io.File f) throws java.io.IOException {
        List<String> out = new ArrayList<String>();
        java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(f), java.nio.charset.StandardCharsets.UTF_8));
        try {
            String ln; boolean first = true;
            while ((ln = r.readLine()) != null) { if (first) { ln = stripBom(ln); first = false; } out.add(ln); }
        } finally { try { r.close(); } catch (Exception ignored) {} }
        return out;
    }

    private static java.util.List<String> parseCsvLine(String line, char delim) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (line == null) return out;
        StringBuilder cur = new StringBuilder();
        boolean inQ = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQ) {
                if (c == '"') { if (i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; } else inQ = false; }
                else cur.append(c);
            } else {
                if (c == '"') inQ = true;
                else if (c == delim) { out.add(cur.toString()); cur.setLength(0); }
                else cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }
    private static void diffRow(java.io.BufferedWriter w, long idx, String attr, String va, String vb, String cat) throws java.io.IOException {
        w.write(dq(String.valueOf(idx))); w.write(',');
        w.write(dq(attr)); w.write(',');
        w.write(dq(va)); w.write(',');
        w.write(dq(vb)); w.write(',');
        w.write(dq(cat)); w.write("\r\n");
    }
    private static String dq(String v) {
        if (v == null) v = "";
        boolean q = v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0;
        if (q) return "\"" + v.replace("\"", "\"\"") + "\"";
        return v;
    }

    // -------------------------------------------------------------- json2csv
    /**
     * JSON to CSV: read the JSON files matching a wildcard mask in a directory and write ONE flat CSV
     * whose shape is the feed's dataschema, filling its columns from JSON attribute paths.
     *
     * <p><b>One file is one document is one row.</b> Multi-row flattening is specified in
     * {@code .claude/JSON_TO_CSV_EXECUTOR.md} §6.3-§6.5 and not implemented; a path containing
     * {@code []} is refused before a file is opened, never read as {@code [0]} and never ignored.
     *
     * <p>Everything decidable is decided here, before the first read: the mapping, the dataschema, the
     * date masks. All of it fails as the step starts rather than on the first row of a delivery.
     */
    private void runJson2Csv(StepDef step, Map<String, String> params, Map<String, String> vars,
                             StepExecutor.Result res, java.util.function.Consumer<String> line) throws Exception {
        String inputDir = VarResolver.resolve(xStr(params.get("inputDir"), null), vars);
        String csvFile = VarResolver.resolve(step.csvFile, vars);
        if (inputDir == null || inputDir.trim().isEmpty()) { line.accept("json2csv: inputDir is required"); res.exitCode = 2; return; }
        if (csvFile == null || csvFile.trim().isEmpty()) { line.accept("json2csv: csvFile (output) is required"); res.exitCode = 2; return; }
        java.io.File dir = new java.io.File(rebaseRel(inputDir, vars));
        if (!dir.isDirectory()) { line.accept("json2csv: inputDir is not a directory: " + inputDir); res.exitCode = 2; return; }

        String filePattern = xStr(VarResolver.resolve(params.get("filePattern"), vars), "*.json");
        char delim = (step.delimiter != null && !step.delimiter.isEmpty()) ? step.delimiter.charAt(0) : ';';
        String onNonScalarS = xStr(params.get("onNonScalar"), "FAIL");
        String onBadFile = xStr(params.get("onBadFile"), "FAIL").toUpperCase(java.util.Locale.ROOT);
        int maxFileMB = xInt(params.get("maxFileMB"), 16);
        long serialStart = xInt(params.get("serialStart"), 1);
        int serialPad = xInt(params.get("serialPad"), 0);
        String inputCharset = xStr(VarResolver.resolve(params.get("inputCharset"), vars), null);
        boolean renameProcessed = "true".equalsIgnoreCase(params.get("renameProcessed"));
        if (!"FAIL".equals(onBadFile) && !"SKIP".equals(onBadFile)) {
            line.accept("json2csv: onBadFile must be FAIL or SKIP, not '" + onBadFile + "'"); res.exitCode = 2; return;
        }

        // 1) the dataschema decides the header and its order (requirement 1)
        java.util.List<String> schemaCols = null;
        String columnsSchema = VarResolver.resolve(params.get("columnsSchema"), vars);
        if (columnsSchema != null && !columnsSchema.trim().isEmpty()) {
            java.io.File sf = new java.io.File(rebaseRel(columnsSchema, vars));
            if (!sf.isFile()) { line.accept("json2csv: columnsSchema not found: " + columnsSchema); res.exitCode = 2; return; }
            schemaCols = readSchemaColumnNames(sf);
            if (schemaCols.isEmpty()) { line.accept("json2csv: columnsSchema has no columns: " + columnsSchema); res.exitCode = 2; return; }
        }

        // 2) the mapping, placed at its dataschema position. A dataschema column nobody maps is
        //    written EMPTY and not dropped: the CSV keeps the schema's shape, which is what ELAR gets.
        java.util.List<com.legalarchive.orchestrator.json2csv.ColumnMapping> mapped =
                new java.util.ArrayList<com.legalarchive.orchestrator.json2csv.ColumnMapping>();
        try {
            if (step.columns != null) for (com.legalarchive.orchestrator.model.def.ColumnSel c : step.columns) {
                mapped.add(new com.legalarchive.orchestrator.json2csv.ColumnMapping(
                        VarResolver.resolve(c.as, vars),
                        VarResolver.resolve(c.src, vars),
                        com.legalarchive.orchestrator.json2csv.ColumnType.parse(c.type),
                        VarResolver.resolve(c.from, vars),
                        com.legalarchive.orchestrator.json2csv.MimeMode.parse(c.mode),
                        VarResolver.resolve(c.value, vars)));
            }
        } catch (com.legalarchive.orchestrator.json2csv.Json2CsvException e) {
            line.accept("json2csv: " + e.getMessage()); res.exitCode = 2; return;
        }
        if (mapped.isEmpty()) { line.accept("json2csv: at least one <column> is required"); res.exitCode = 2; return; }

        java.util.List<com.legalarchive.orchestrator.json2csv.ColumnMapping> columns;
        if (schemaCols != null) {
            java.util.Map<String, com.legalarchive.orchestrator.json2csv.ColumnMapping> byName =
                    new java.util.HashMap<String, com.legalarchive.orchestrator.json2csv.ColumnMapping>();
            for (com.legalarchive.orchestrator.json2csv.ColumnMapping m : mapped) byName.put(m.as, m);
            columns = new java.util.ArrayList<com.legalarchive.orchestrator.json2csv.ColumnMapping>();
            for (String name : schemaCols) {
                com.legalarchive.orchestrator.json2csv.ColumnMapping m = byName.get(name);
                columns.add(m != null ? m : com.legalarchive.orchestrator.json2csv.ColumnMapping.unmapped(name));
            }
        } else {
            columns = mapped;
        }

        // 3) everything checkable without a file, reported all at once
        try {
            com.legalarchive.orchestrator.json2csv.MappingValidator.check(mapped, schemaCols);
        } catch (com.legalarchive.orchestrator.json2csv.Json2CsvException e) {
            line.accept("json2csv: " + e.getMessage()); res.exitCode = 2; return;
        }

        // 4) dates. The mask translator is InternalSteps.fmtToJavaPattern and NOT a second copy of it:
        //    recordBusinessDateFormat is a MASK (YYYY/MM/DD, where DD is day-of-month), and a private
        //    reimplementation is how businessDateNotBefore was silently broken for years.
        com.legalarchive.orchestrator.json2csv.DateCoercion dates = null;
        if (com.legalarchive.orchestrator.json2csv.MappingValidator.needsDates(mapped)) {
            String outMask = VarResolver.resolve("${recordBusinessDateFormat}", vars);
            try {
                dates = com.legalarchive.orchestrator.json2csv.DateCoercion.create(
                        new com.legalarchive.orchestrator.json2csv.MaskTranslator() {
                            public String toJavaPattern(String mask) { return fmtToJavaPattern(mask); }
                        },
                        outMask,
                        com.legalarchive.orchestrator.json2csv.MappingValidator.inputMasks(mapped));
            } catch (com.legalarchive.orchestrator.json2csv.Json2CsvException e) {
                line.accept("json2csv: " + e.getMessage()); res.exitCode = 2; return;
            }
        }

        com.legalarchive.orchestrator.json2csv.OnNonScalar onNonScalar;
        com.legalarchive.orchestrator.json2csv.ObjectNameValue objectNameValue;
        try {
            onNonScalar = com.legalarchive.orchestrator.json2csv.OnNonScalar.parse(onNonScalarS);
            objectNameValue = com.legalarchive.orchestrator.json2csv.ObjectNameValue.parse(params.get("objectNameValue"));
        } catch (com.legalarchive.orchestrator.json2csv.Json2CsvException e) {
            line.accept("json2csv: " + e.getMessage()); res.exitCode = 2; return;
        }

        // 5) hand off to the run loop, which lives in json2csv precisely so it can be exercised
        //    outside the application: reading and writing are seams, and everything between them -
        //    the file order, what the counters do when one file fails, when the rename may happen -
        //    is tested there against fakes.
        com.legalarchive.orchestrator.json2csv.Json2CsvRun.Options o =
                new com.legalarchive.orchestrator.json2csv.Json2CsvRun.Options();
        o.inputDir = dir;
        o.filePattern = filePattern;
        o.failOnBadFile = "FAIL".equals(onBadFile);
        o.columns = columns;
        o.onNonScalar = onNonScalar;
        o.objectNameValue = objectNameValue;
        o.dates = dates;
        o.serialStart = serialStart;
        o.serialPad = serialPad;

        java.io.File out = new java.io.File(rebaseRel(csvFile, vars));
        if (out.getParentFile() != null) out.getParentFile().mkdirs();
        long maxRows = step.csvSplitRows > 0 ? step.csvSplitRows : 0;
        long maxBytes = step.csvSplitMb > 0 ? (long) step.csvSplitMb * 1024L * 1024L : 0;
        final JsonDocumentReader reader = new JsonDocumentReader(maxFileMB, inputCharset);
        final com.legalarchive.orchestrator.ds.CsvWriter cw =
                new com.legalarchive.orchestrator.ds.CsvWriter(out, delim, false, maxRows, maxBytes);

        com.legalarchive.orchestrator.json2csv.Json2CsvCounters counters;
        try {
            counters = com.legalarchive.orchestrator.json2csv.Json2CsvRun.run(o,
                    new com.legalarchive.orchestrator.json2csv.Json2CsvRun.DocumentReader() {
                        public Object read(java.io.File f) throws Exception { return reader.read(f); }
                    },
                    new com.legalarchive.orchestrator.json2csv.Json2CsvRun.RowSink() {
                        public void header(String[] cols) throws Exception { cw.header(cols); }
                        public void row(String[] cells) throws Exception { cw.row(cells); }
                    },
                    line);
        } catch (com.legalarchive.orchestrator.json2csv.Json2CsvException e) {
            line.accept("json2csv: " + e.getMessage());
            res.exitCode = 2;
            return;
        } finally {
            try { cw.close(); } catch (Exception ignored) { }
        }

        // 6) the rename, and only now: the CSV is closed above, in the finally.
        if (renameProcessed) {
            int renamed = com.legalarchive.orchestrator.json2csv.Json2CsvRun.renameProcessed(counters, line);
            line.accept("json2csv: renamed " + renamed + " of " + counters.processed.size() + " input file(s) to .done");
        }

        line.accept("json2csv: " + counters.toString());
        // The invariant, said out loud rather than left to be worked out: it is the cheapest possible
        // assertion that the executor did what it claims, and the one number a gate can branch on.
        if (counters.rowPerFileHolds()) {
            line.accept("json2csv: one row per file (filesRead = rowsWritten = " + counters.rowsWritten + ")");
        } else {
            line.accept("json2csv: WARNING filesRead=" + counters.filesRead + " but rowsWritten="
                    + counters.rowsWritten + " - one file should produce exactly one row");
        }

        res.outVars.put("rowCount", String.valueOf(cw.rows));
        res.outVars.put("csvParts", String.valueOf(cw.parts));
        if (!cw.files.isEmpty()) res.outVars.put("csvFile", cw.files.get(0));
        res.outVars.put("csvFiles", String.join(step.delimiter == null ? ";" : step.delimiter, cw.files));
        res.outVars.put("filesRead", String.valueOf(counters.filesRead));
        res.outVars.put("filesFailed", String.valueOf(counters.filesFailed));
        res.outVars.put("rowsWritten", String.valueOf(counters.rowsWritten));
        res.outVars.put("valuesMissing", String.valueOf(counters.valuesMissing));
        res.outVars.put("valuesNonScalar", String.valueOf(counters.valuesNonScalar));
        res.exitCode = 0;
    }

    private void runXlsx2Csv(StepDef step, Map<String, String> params, Map<String, String> vars,
                             StepExecutor.Result res, java.util.function.Consumer<String> line) throws Exception {
        String source = VarResolver.resolve(step.source, vars);
        String csvFile = VarResolver.resolve(step.csvFile, vars);
        if (source == null || source.trim().isEmpty()) { line.accept("xlsx2csv: source (xlsx) is required"); res.exitCode = 2; return; }
        if (!source.toLowerCase(java.util.Locale.ROOT).endsWith(".xlsx")) { line.accept("xlsx2csv: source must be an .xlsx file"); res.exitCode = 2; return; }
        source = rebaseRel(source, vars);
        java.io.File xlsx = new java.io.File(source);
        if (!xlsx.isFile()) { line.accept("xlsx2csv: file not found: " + source); res.exitCode = 2; return; }
        if (csvFile == null || csvFile.trim().isEmpty()) { line.accept("xlsx2csv: csvFile (output) is required"); res.exitCode = 2; return; }

        char delim = (step.delimiter != null && !step.delimiter.isEmpty()) ? step.delimiter.charAt(0) : ';';
        String sheet = VarResolver.resolve(params.get("sheet"), vars);
        int sheetIndex = xInt(params.get("sheetIndex"), 0);
        final int headerRow = xInt(params.get("headerRow"), 1);
        final int firstDataRow = xInt(params.get("firstDataRow"), 2);
        final String selectBy = xStr(params.get("selectBy"), "header");
        String dateFormat = xStr(params.get("dateFormat"), "yyyyMMdd");
        boolean rawValues = "true".equalsIgnoreCase(params.get("rawValues"));
        final boolean skipEmptyRows = !"false".equalsIgnoreCase(params.get("skipEmptyRows"));

        try { Class.forName("org.apache.poi.openxml4j.opc.OPCPackage"); }
        catch (Throwable t) { line.accept("xlsx2csv: Apache POI not on classpath — add org.apache.poi:poi-ooxml (see xlsx/README_POI.md)"); res.exitCode = 2; return; }

        final java.util.List<String> srcs = new java.util.ArrayList<String>();
        final java.util.List<String> ases = new java.util.ArrayList<String>();
        if (step.columns != null) for (com.legalarchive.orchestrator.model.def.ColumnSel c : step.columns) {
            srcs.add(VarResolver.resolve(c.src, vars));
            ases.add(VarResolver.resolve(c.as, vars));
        }

        java.io.File out = new java.io.File(csvFile);
        if (out.getParentFile() != null) out.getParentFile().mkdirs();
        long maxRows = step.csvSplitRows > 0 ? step.csvSplitRows : 0;
        long maxBytes = step.csvSplitMb > 0 ? (long) step.csvSplitMb * 1024L * 1024L : 0;
        final com.legalarchive.orchestrator.ds.CsvWriter cw = new com.legalarchive.orchestrator.ds.CsvWriter(out, delim, false, maxRows, maxBytes);   // no BOM (feeds csvsql)
        final com.legalarchive.orchestrator.xlsx.XlsxSheetReader.Plan[] plan = {null};

        line.accept("xlsx2csv: " + source + " sheet=" + ((sheet != null && !sheet.trim().isEmpty()) ? sheet : ("#" + sheetIndex)));
        try {
            com.legalarchive.orchestrator.xlsx.XlsxSheetReader.read(xlsx, sheet, sheetIndex, dateFormat, rawValues,
                new com.legalarchive.orchestrator.xlsx.XlsxSheetReader.RowSink() {
                    public void row(int rowNum, java.util.List<String> cells) throws Exception {
                        if (headerRow >= 1 && rowNum == headerRow) {
                            plan[0] = com.legalarchive.orchestrator.xlsx.XlsxSheetReader.plan(cells, srcs.isEmpty() ? null : srcs, ases, selectBy);
                            cw.header(plan[0].out);
                            return;
                        }
                        if (rowNum >= firstDataRow) {
                            if (plan[0] == null) {
                                plan[0] = com.legalarchive.orchestrator.xlsx.XlsxSheetReader.plan(cells, srcs.isEmpty() ? null : srcs, ases, selectBy);
                                cw.header(plan[0].out);
                            }
                            int[] idx = plan[0].idx;
                            String[] projected = new String[idx.length];
                            boolean allEmpty = true;
                            for (int k = 0; k < idx.length; k++) {
                                int ci = idx[k];
                                String v = (ci >= 0 && ci < cells.size()) ? cells.get(ci) : "";
                                if (v == null) v = "";
                                if (!v.isEmpty()) allEmpty = false;
                                projected[k] = v;
                            }
                            if (skipEmptyRows && allEmpty) return;
                            cw.row(projected);
                        }
                    }
                });
        } finally {
            cw.close();
        }

        res.outVars.put("rowCount", String.valueOf(cw.rows));
        res.outVars.put("csvParts", String.valueOf(cw.parts));
        String first = cw.files.isEmpty() ? out.getAbsolutePath() : cw.files.get(0);
        res.outVars.put("csvFile", first);
        res.outVars.put("csvFiles", String.join(step.delimiter == null ? ";" : step.delimiter, cw.files));
        res.outVars.put("outputFile", first);
        if (cw.parts > 1) line.accept("wrote " + cw.rows + " data row(s) into " + cw.parts + " CSV part(s)");
        else line.accept("wrote " + cw.rows + " data row(s) to " + first);
        for (String fpath : cw.files) line.accept("  " + fpath);
        for (Map.Entry<String, String> e : res.outVars.entrySet()) line.accept("##VAR " + e.getKey() + "=" + e.getValue());
        res.exitCode = 0;
    }

    private static int xInt(String s, int def) { if (s == null || s.trim().isEmpty()) return def; try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; } }
    private static String xStr(String s, String def) { return (s == null || s.trim().isEmpty()) ? def : s.trim(); }

    /** SQL string literal with single quotes doubled (standard SQL / H2). */
    private static String sqlLit(String s) { return s == null ? "NULL" : "'" + s.replace("'", "''") + "'"; }

    /**
     * Effective step timeout in seconds. Precedence: the explicit per-step TIMEOUT SEC field (if &gt; 0),
     * else the variable {@code stepTimeoutMins.<stepId>} (minutes), else {@code stepTimeoutMins} (minutes),
     * else {@code defaultSec}. The standard variable {@code stepTimeoutMins} is seeded to 5 for every run
     * and can be overridden globally, per-workflow, or per-step.
     */
    public static int stepTimeoutSec(String stepId, int stepTimeoutSecField, Map<String, String> vars, int defaultSec) {
        if (stepTimeoutSecField > 0) return stepTimeoutSecField;
        String m = vars != null ? vars.get("stepTimeoutMins." + stepId) : null;
        if (m == null || m.trim().isEmpty()) m = vars != null ? vars.get("stepTimeoutMins") : null;
        if (m != null) {
            try { double mins = Double.parseDouble(m.trim()); if (mins > 0) return (int) Math.round(mins * 60.0); }
            catch (Exception ignored) {}
        }
        return defaultSec;
    }

    /** Public entry point so the csvsql preview reuses the exact same detection as the executor. */
    public static char detectDelimiter(java.io.File f, char def) { return detectDelim(f, def); }

    /**
     * Best-effort detection of a CSV field separator from the header line. Counts comma, semicolon,
     * tab and pipe outside double quotes and returns the most frequent; falls back to {@code def}
     * when the header has no recognisable separator (single-column file). A leading UTF-8 BOM is
     * ignored. This lets a {@code csvsql} input be read correctly whether it is comma- or
     * semicolon-separated, without forcing the user to match the output delimiter.
     */
    private static char detectDelim(java.io.File f, char def) {
        java.io.BufferedReader r = null;
        try {
            r = new java.io.BufferedReader(new java.io.InputStreamReader(
                    new java.io.FileInputStream(f), java.nio.charset.StandardCharsets.UTF_8));
            String line = r.readLine();
            if (line == null) return def;
            if (!line.isEmpty() && line.charAt(0) == '\uFEFF') line = line.substring(1);
            char[] cands = {',', ';', '\t', '|'};
            char best = def; int bestN = 0;
            for (char c : cands) {
                int n = 0; boolean inQ = false;
                for (int i = 0; i < line.length(); i++) {
                    char ch = line.charAt(i);
                    if (ch == '"') inQ = !inQ;
                    else if (ch == c && !inQ) n++;
                }
                if (n > bestN) { bestN = n; best = c; }
            }
            return bestN > 0 ? best : def;
        } catch (Exception e) {
            return def;
        } finally {
            if (r != null) try { r.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * Rebase a bare relative path against {@code ${feedDir}} so a feed-relative path copied from the
     * Feed Files panel (e.g. {@code 10_SQL_EXTRACTION/tf0003819_SQL_EXTRACTION.csv}) resolves to the
     * right file. Absolute paths (and {@code ${var}}-expanded absolute paths) are returned unchanged.
     */
    private static String rebaseRel(String path, Map<String, String> vars) {
        if (path == null) return null;
        String p = path.trim();
        if (p.isEmpty()) return p;
        if (new java.io.File(p).isAbsolute()) return p;
        String feedDir = vars.get("feedDir");
        if (feedDir != null && !feedDir.trim().isEmpty()) return new java.io.File(feedDir.trim(), p).getPath();
        return p;
    }

    /**
     * Publishes the datasource a step ran against, so it is recoverable afterwards as
     * {@code ${<stepId>.dataSource}} (and, unqualified, as {@code ${dataSource}} like every other step
     * output). Called as the FIRST thing an executor does, before the datasource is even resolved, so
     * the value is there even when the step then fails - "which database did this run hit" is a
     * question that matters most precisely when something went wrong, and it is what the audit report
     * needs to be evidence rather than a summary.
     *
     * Two names are published for one value. The XML attribute is spelled {@code datasource} all
     * lowercase while every other step output is camelCase, so whichever of the two an author types
     * would otherwise resolve to an empty string in silence - the failure mode this project has been
     * bitten by before. The alias costs one line; the wrong guess costs a debugging session.
     */
    private static void publishDataSource(StepDef step, StepExecutor.Result res) {
        String ds = (step == null) ? null : blankToNull(step.datasource);
        if (ds == null || res == null) return;
        res.outVars.put("dataSource", ds);
        res.outVars.put("datasource", ds);
    }

    // ----------------------------------------------------------------- sql
    private void runSql(StepDef step, Map<String, String> params, Map<String, String> vars,
                        StepExecutor.Result res, java.util.function.Consumer<String> line, RunControl control) throws Exception {
        publishDataSource(step, res);
        DataSourceDef d = dataSources.get(step.datasource);
        if (d == null) { line.accept("datasource not found: " + step.datasource); res.exitCode = 2; return; }
        // {{columns}} expansion: build the SELECT column list from a (per-feed) dataschema JSON.
        String rawQuery = step.query;
        if (rawQuery != null && rawQuery.contains("{{columns}}")) {
            String columnsSchema = blankToNull(VarResolver.resolve(params.get("columnsSchema"), vars));
            if (columnsSchema == null) {
                line.accept("sql: query uses {{columns}} but param 'columnsSchema' (path to dataschema JSON) is missing");
                res.exitCode = 2; return;
            }
            java.util.List<String> names = readSchemaColumnNames(new java.io.File(columnsSchema));
            if (names.isEmpty()) {
                line.accept("sql: no columns found in dataschema " + columnsSchema);
                res.exitCode = 2; return;
            }
            String cols = buildColumnList(names, params.get("columnQuote"));
            rawQuery = rawQuery.replace("{{columns}}", cols);
            line.accept("sql: expanded {{columns}} -> " + names.size() + " columns from " + columnsSchema);
        }
        String query = VarResolver.resolve(rawQuery, vars);
        line.accept("datasource: " + d.id + " (" + d.type + ")");
        line.accept("query: " + query);

        // CSV export path: stream the full result set to a file
        String csvFile = VarResolver.resolve(step.csvFile, vars);
        boolean trim = !"false".equalsIgnoreCase(params.get("trim"));   // CHAR padding: trimmed by default
        if (csvFile != null && !csvFile.trim().isEmpty()) {
            char delim = (step.delimiter != null && !step.delimiter.isEmpty()) ? step.delimiter.charAt(0) : ';';
            java.io.File out = new java.io.File(csvFile);
            if (out.getParentFile() != null) out.getParentFile().mkdirs();
            long maxRows = step.csvSplitRows > 0 ? step.csvSplitRows : 0;
            long maxBytes = step.csvSplitMb > 0 ? (long) step.csvSplitMb * 1024L * 1024L : 0;
            // CR/LF inside a source column would split the record over several physical lines: the column
            // count is known here from the ResultSet, so values are normalised while writing (see nlReplacement)
            SqlSupport.ExportResult er = sql.exportCsv(d, query, out, delim, true, maxRows, maxBytes, trim,
                    r -> { if (control != null) control.aborter = r; }, params.get("newlinesInValues"));
            res.outVars.put("rowCount", String.valueOf(er.rows));
            res.outVars.put("csvParts", String.valueOf(er.parts));
            res.outVars.put("csvFile", er.files.isEmpty() ? out.getAbsolutePath() : er.files.get(0));
            res.outVars.put("csvFiles", String.join(step.delimiter == null ? ";" : step.delimiter, er.files));
            res.outVars.put("csvRowCounts", joinCounts(er.partRows, step.delimiter == null ? ";" : step.delimiter));
            res.outVars.put("newlinesSanitized", String.valueOf(er.newlinesSanitized));
            if (er.newlinesSanitized > 0) line.accept("normalised line breaks inside " + er.newlinesSanitized + " value(s)");
            if (er.parts > 1) line.accept("exported " + er.rows + " row(s) into " + er.parts + " CSV part(s)");
            else line.accept("exported " + er.rows + " row(s) to " + res.outVars.get("csvFile"));
            for (String f : er.files) line.accept("  " + f);
            for (Map.Entry<String, String> e : res.outVars.entrySet()) line.accept("##VAR " + e.getKey() + "=" + e.getValue());
            res.exitCode = 0;
            return;
        }

        SqlSupport.QueryResult qr = sql.run(d, query, 1000, trim);
        if (qr.updateCount != null) {
            line.accept("update count: " + qr.updateCount);
            res.outVars.put("updateCount", String.valueOf(qr.updateCount));
        } else {
            line.accept("returned rows: " + qr.rowCount + (qr.truncated ? " (truncated at 1000)" : ""));
            res.outVars.put("rowCount", String.valueOf(qr.rowCount));
            // first row columns exposed as ${col_<NAME>}
            if (!qr.rows.isEmpty()) {
                List<String> r0 = qr.rows.get(0);
                for (int i = 0; i < qr.columns.size() && i < r0.size(); i++) {
                    res.outVars.put("col_" + qr.columns.get(i), r0.get(i));
                }
                res.outVars.put("firstValue", SqlSupport.firstValue(qr));
            }
            // optional: store all first-column values joined, for downstream steps
            if (step.outputVar != null && !step.outputVar.trim().isEmpty() && !qr.columns.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (List<String> r : qr.rows) { if (sb.length() > 0) sb.append(step.delimiter == null ? ";" : step.delimiter); sb.append(r.get(0)); }
                res.outVars.put(step.outputVar.trim(), sb.toString());
            }
        }
        for (Map.Entry<String, String> e : res.outVars.entrySet()) line.accept("##VAR " + e.getKey() + "=" + e.getValue());
        res.exitCode = 0;
    }

    private static final java.util.Set<String> STEP_OUTPUT_VARS = new java.util.HashSet<String>(
            java.util.Arrays.asList("reportFile", "reportDocxFile", "queriesExecuted", "rowsTotal",
                    "dataSource", "datasource"));

    // ------------------------------------------------------------- sqlreport
    /**
     * Runs a list of READ-ONLY queries against one datasource and writes a single Markdown evidence
     * report. No CSV, no data file: the report IS the deliverable, so it carries everything a reader
     * needs to trust it - the statement as executed (after ${var} substitution), its own timestamp
     * and duration, the datasource / host / user / database, the run id, and the real row count even
     * when the rendered table is truncated.
     *
     * Read-only is enforced at two levels, both before anything runs: every statement must survive
     * SqlReportSupport.readOnlyError, and the JDBC connection is put in read-only mode. Both are a
     * net against mistakes rather than a guarantee - the report says so itself, because the only real
     * guarantee is the rights of the database account.
     *
     * Batch 2 adds variable collection. A query with a 'collect' list publishes each named column as
     * a run variable; with a 'keyColumn' it publishes the ';'-separated values in row order plus the
     * companion '<name>.keys' list, which is the pair VarResolver.keyed() needs to resolve
     * ${COL@key}. A single-column result with no 'collect' is published implicitly under its own
     * label (the scalar case). Collection is NOT limited by maxRows, which caps only the rendered
     * table; it is limited by collectMaxRows, and overflowing publishes NOTHING for that query
     * rather than a partial list, because a partial list in a reconciliation is a trap.
     */
    private void runSqlReport(StepDef step, Map<String, String> params, Map<String, String> vars,
                              StepExecutor.Result res, java.util.function.Consumer<String> line,
                              RunControl control) throws Exception {
        publishDataSource(step, res);
        DataSourceDef d = dataSources.get(step.datasource);
        if (d == null) { line.accept("sqlreport: datasource not found: " + step.datasource); res.exitCode = 2; return; }
        List<com.legalarchive.orchestrator.model.def.ReportQuery> queries = step.reportQueries;
        if (queries == null || queries.isEmpty()) {
            line.accept("sqlreport: no queries configured - add at least one with [+ Add query]");
            res.exitCode = 2; return;
        }

        int defaultMaxRows = (int) pLong(params.get("maxRows"), 200L);
        if (defaultMaxRows < 0) defaultMaxRows = 0;
        int collectMaxRows = (int) pLong(params.get("collectMaxRows"), 5000L);
        if (collectMaxRows < 0) collectMaxRows = 0;
        List<String> collectErrors = new ArrayList<String>();
        boolean failOnEmpty = "true".equalsIgnoreCase(params.get("failOnEmpty"));
        boolean trim = !"false".equalsIgnoreCase(params.get("trim"));
        int qto = stepTimeoutSec(step.id, step.timeoutSec, vars, props != null ? props.getDefaultStepTimeoutSec() : 0);

        String feedId = nz(vars.get("feedId"));
        String stepId = step.id == null ? "step" : step.id;
        String reportFile = blankToNull(VarResolver.resolve(params.get("reportFile"), vars));
        if (reportFile == null) {
            String stepDir = blankToNull(vars.get("stepDir"));
            String base = feedId.isEmpty() ? stepId : (feedId + "_" + stepId);
            reportFile = (stepDir == null ? base : (stepDir + java.io.File.separator + base)) + ".md";
        }

        // 1) resolve and validate EVERY statement before opening a connection: a rejected query must
        //    not leave half the report written and half the queries executed.
        List<String> sqls = new ArrayList<String>();
        List<String> titles = new ArrayList<String>();
        List<String> problems = new ArrayList<String>();
        for (int i = 0; i < queries.size(); i++) {
            com.legalarchive.orchestrator.model.def.ReportQuery rq = queries.get(i);
            String title = blankToNull(VarResolver.resolve(rq == null ? null : rq.title, vars));
            String stmt = rq == null ? null : VarResolver.resolve(rq.sql, vars);
            titles.add(title == null ? ("Query " + (i + 1)) : title);
            sqls.add(stmt == null ? "" : stmt);
            String err = SqlReportSupport.readOnlyError(stmt);
            if (err != null) problems.add("query " + (i + 1) + " (" + titles.get(i) + "): " + err);
        }
        if (!problems.isEmpty()) {
            for (String p : problems) line.accept("sqlreport: REJECTED - " + p);
            line.accept("sqlreport: nothing was executed; a report query must be a single read-only SELECT (or WITH)");
            res.exitCode = 2;
            return;
        }

        String zone = java.util.TimeZone.getDefault().getID();
        StringBuilder md = new StringBuilder();
        md.append("# SQL report - ").append(feedId.isEmpty() ? stepId : feedId).append("\n");
        md.append("\n");
        md.append("Run: ").append(nz(vars.get("runId"))).append(" - executed ")
          .append(LocalDateTime.now().format(TS)).append(" (").append(zone).append(")").append("\n");
        md.append("\n");

        long rowsTotal = 0;
        int executed = 0;
        int emptyQueries = 0;
        java.sql.Connection conn = null;
        try {
            conn = sql.open(d);
            boolean readOnlySet = false;
            try { conn.setReadOnly(true); readOnlySet = conn.isReadOnly(); }
            catch (Exception e) { line.accept("sqlreport: the driver refused setReadOnly(true) (" + e.getMessage() + ") - statement validation still applies"); }

            String host = "as400".equalsIgnoreCase(d.type) ? nz(d.host) : SqlReportSupport.redactJdbcUrl(d.jdbcUrl);
            String database = "";
            String product = "";
            try { database = nz(conn.getCatalog()); } catch (Exception ignored) { }
            try { product = nz(conn.getMetaData().getDatabaseProductName()); } catch (Exception ignored) { }

            md.append("Datasource: ").append(nz(d.id)).append(" (").append(nz(d.type)).append(")")
              .append(" - host ").append(host.isEmpty() ? "(n/a)" : host)
              .append(" - user ").append(nz(d.user).isEmpty() ? "(n/a)" : nz(d.user))
              .append(" - database ").append(database.isEmpty() ? "(n/a)" : database);
            if (!product.isEmpty()) md.append(" - ").append(product);
            md.append("\n").append("\n");
            md.append("Workflow: ").append(feedId).append(" \"").append(nz(vars.get("feedName")))
              .append("\" - step ").append(stepId).append("\n").append("\n");
            md.append("Read-only: every statement was checked to be a single SELECT/WITH before execution")
              .append(readOnlySet ? " and the JDBC connection was set read-only." : " (the driver did not honour a read-only connection).")
              .append(" This is a guard against mistakes, not a guarantee: a SELECT can still call a function with side effects.")
              .append(" The real guarantee is the rights of the database account.").append("\n").append("\n");

            line.accept("sqlreport: datasource " + d.id + " (" + d.type + "), " + queries.size() + " query(ies), maxRows " + defaultMaxRows
                    + (qto > 0 ? (", query timeout " + qto + "s") : ""));

            for (int i = 0; i < sqls.size(); i++) {
                if (control != null && control.aborted) { line.accept("sqlreport: aborted by user"); res.exitCode = -997; return; }
                com.legalarchive.orchestrator.model.def.ReportQuery rq = queries.get(i);
                int cap = (rq != null && rq.maxRows > 0) ? rq.maxRows : defaultMaxRows;
                String stmt = sqls.get(i);

                md.append("## ").append(i + 1).append(". ").append(titles.get(i)).append("\n").append("\n");

                List<String> cols = new ArrayList<String>();
                List<List<String>> rows = new ArrayList<List<String>>();
                List<List<String>> collected = null;   // one list per collected column, ALL rows (not capped by maxRows)
                List<String> collectedKeys = null;
                SqlReportSupport.CollectPlan plan = null;
                boolean collectOverflow = false;
                int sanitized = 0;
                long count = 0;
                String startedAt = LocalDateTime.now().format(TS);
                long t0 = System.currentTimeMillis();
                java.sql.Statement st = null;
                try {
                    st = conn.createStatement();
                    if (qto > 0) { try { st.setQueryTimeout(qto); } catch (Exception ignored) { } }
                    if (control != null) control.statement = st;   // so an operator Stop can cancel it
                    java.sql.ResultSet rs = st.executeQuery(stmt);
                    java.sql.ResultSetMetaData mdta = rs.getMetaData();
                    int nc = mdta.getColumnCount();
                    for (int c = 1; c <= nc; c++) cols.add(mdta.getColumnLabel(c));

                    plan = SqlReportSupport.planCollect(cols, rq == null ? null : rq.collect, rq == null ? null : rq.keyColumn);
                    if (plan.error != null) {
                        collectErrors.add("query " + (i + 1) + " (" + titles.get(i) + "): " + plan.error);
                    } else if (plan.active()) {
                        collected = new ArrayList<List<String>>();
                        for (int k = 0; k < plan.names.size(); k++) collected.add(new ArrayList<String>());
                        if (plan.keyed()) collectedKeys = new ArrayList<String>();
                    }
                    for (String note : plan.notes) line.accept("sqlreport: query " + (i + 1) + ": " + note);

                    while (rs.next()) {
                        count++;
                        List<String> full = null;
                        boolean keepForTable = (cap <= 0 || rows.size() < cap);
                        if (keepForTable || collected != null) {
                            full = new ArrayList<String>(nc);
                            for (int c = 1; c <= nc; c++) full.add(SqlReportSupport.cell(rs.getObject(c), trim));
                        }
                        // the table is capped, the COUNT never is: a truncated table hiding the real
                        // number would be misleading in a document meant as evidence
                        if (keepForTable) rows.add(full);
                        if (collected != null && !collectOverflow) {
                            if (collectMaxRows > 0 && collected.get(0).size() >= collectMaxRows) {
                                collectOverflow = true;   // a partially collected list is a trap: publish nothing
                                continue;
                            }
                            for (int k = 0; k < plan.indexes.size(); k++) {
                                String v = full.get(plan.indexes.get(k).intValue());
                                if (SqlReportSupport.needsSanitizing(v)) sanitized++;
                                collected.get(k).add(SqlReportSupport.sanitizeListValue(v));
                            }
                            if (collectedKeys != null) {
                                String kv = full.get(plan.keyIndex);
                                if (SqlReportSupport.needsSanitizing(kv)) sanitized++;
                                collectedKeys.add(SqlReportSupport.sanitizeListValue(kv));
                            }
                        }
                    }
                } finally {
                    if (control != null) control.statement = null;
                    if (st != null) try { st.close(); } catch (Exception ignored) { }
                }
                long ms = System.currentTimeMillis() - t0;
                executed++;
                rowsTotal += count;
                if (count == 0) emptyQueries++;

                md.append("Executed ").append(startedAt).append(", ")
                  .append(String.format(java.util.Locale.ROOT, "%.2f", ms / 1000.0)).append(" s, ")
                  .append(count).append(count == 1 ? " row" : " rows");
                if (cap > 0 && count > rows.size()) md.append(" (table truncated to the first ").append(rows.size()).append(")");
                md.append("\n").append("\n");
                md.append("```sql").append("\n").append(stmt.trim()).append("\n").append("```").append("\n").append("\n");
                md.append(SqlReportSupport.markdownTable(cols, rows)).append("\n");

                // ---- publish the collected variables (batch 2) ----
                if (collectOverflow) {
                    String msg = "query " + (i + 1) + " (" + titles.get(i) + "): more than " + collectMaxRows
                            + " rows to collect - nothing was published for it. Narrow the query or raise 'Collect max rows'.";
                    collectErrors.add(msg);
                    md.append("Collected: NOTHING - the result exceeds the collect limit of ").append(collectMaxRows)
                      .append(" rows, so no variable was published (a partially collected list would be misleading).").append("\n").append("\n");
                } else if (plan != null && plan.active()) {
                    List<String> shown = new ArrayList<String>();
                    for (int k = 0; k < plan.names.size(); k++) {
                        String name = plan.names.get(k);
                        List<String> vals = collected.get(k);
                        if (plan.keyed()) {
                            res.outVars.put(name, SqlReportSupport.joinList(vals));
                            res.outVars.put(name + ".keys", SqlReportSupport.joinList(collectedKeys));
                            shown.add(name + " (" + vals.size() + (vals.size() == 1 ? " value" : " values") + " keyed by " + plan.keyName + ")");
                        } else if (vals.size() == 1) {
                            res.outVars.put(name, vals.get(0));            // the scalar case
                            shown.add(name + " = " + vals.get(0));
                        } else {
                            res.outVars.put(name, SqlReportSupport.joinList(vals));
                            shown.add(name + " (" + vals.size() + (vals.size() == 1 ? " value" : " values") + ")");
                        }
                    }
                    if (plan.keyed()) {
                        res.outVars.put(plan.keyName, SqlReportSupport.joinList(collectedKeys));
                        int dup = SqlReportSupport.duplicateKeys(collectedKeys);
                        if (dup > 0) {
                            String warn = "query " + (i + 1) + ": the key column " + plan.keyName + " has " + dup
                                    + " duplicate key(s) - ${COL@key} returns the FIRST match for those";
                            line.accept("sqlreport: WARNING - " + warn);
                            md.append("Warning: ").append(warn).append(".").append("\n").append("\n");
                        }
                    }
                    md.append("Collected: ").append(String.join(", ", shown)).append("\n").append("\n");
                    if (sanitized > 0) {
                        md.append("Note: ").append(sanitized).append(" collected value(s) contained a ';' or a line break and had it replaced")
                          .append(" by a space, because run variable lists are ';'-separated.").append("\n").append("\n");
                        line.accept("sqlreport: query " + (i + 1) + ": " + sanitized + " collected value(s) sanitized (';' or line break -> space)");
                    }
                    // names and counts only in the step log - never the values (PII rule)
                    line.accept("sqlreport: query " + (i + 1) + " collected " + plan.names.size() + " variable(s): " + String.join(", ", plan.names)
                            + (plan.keyed() ? (" keyed by " + plan.keyName + " (+ .keys companion lists)") : ""));
                }

                line.accept("sqlreport: query " + (i + 1) + " \"" + titles.get(i) + "\" -> " + count + " row(s) in " + ms + " ms");
            }
        } finally {
            if (conn != null) try { conn.close(); } catch (Exception ignored) { }
        }

        // reportFormat: md (default) | docx | both. The .docx is a rendering of the SAME Markdown, so
        // the two files can never disagree about what the queries returned.
        String fmt = params.get("reportFormat");
        fmt = (fmt == null || fmt.trim().isEmpty()) ? "md" : fmt.trim().toLowerCase(java.util.Locale.ROOT);
        boolean wantMd = !"docx".equals(fmt);
        boolean wantDocx = "docx".equals(fmt) || "both".equals(fmt);

        java.io.File out = new java.io.File(reportFile);
        if (out.getParentFile() != null) out.getParentFile().mkdirs();
        if (wantMd) Files.write(out.toPath(), md.toString().getBytes(StandardCharsets.UTF_8));
        java.io.File outDocx = null;
        if (wantDocx) {
            outDocx = new java.io.File(DocxWriter.docxPathFor(out.getAbsolutePath()));
            Files.write(outDocx.toPath(), DocxWriter.fromMarkdown(md.toString()));
        }

        if (wantMd) res.outVars.put("reportFile", out.getAbsolutePath());
        if (outDocx != null) res.outVars.put("reportDocxFile", outDocx.getAbsolutePath());
        if (!wantMd) res.outVars.put("reportFile", outDocx.getAbsolutePath());   // always points at what was written
        res.outVars.put("queriesExecuted", String.valueOf(executed));
        res.outVars.put("rowsTotal", String.valueOf(rowsTotal));
        // Only the step's own outputs are echoed with their value. A COLLECTED variable holds query
        // data, which may be PII, so the log names it and counts it but never prints it. (Note the
        // engine still audits every out var with its value: collect counts, sums, statuses and keys,
        // not personal data - the docs say so.)
        for (Map.Entry<String, String> e : res.outVars.entrySet()) {
            if (STEP_OUTPUT_VARS.contains(e.getKey())) line.accept("##VAR " + e.getKey() + "=" + e.getValue());
            else line.accept("##VAR " + e.getKey() + " (collected, value not logged)");
        }
        if (wantMd) line.accept("sqlreport: report written to " + out.getAbsolutePath());
        if (outDocx != null) line.accept("sqlreport: report written to " + outDocx.getAbsolutePath());

        if (!collectErrors.isEmpty()) {
            for (String ce : collectErrors) line.accept("sqlreport: COLLECT FAILED - " + ce);
            res.lastLines = collectErrors.get(0);
            res.exitCode = 1;
            return;
        }

        if (failOnEmpty && emptyQueries > 0) {
            line.accept("sqlreport: " + emptyQueries + " query(ies) returned no rows and 'fail on empty' is on - the report was written anyway");
            res.lastLines = emptyQueries + " query(ies) returned no rows";
            res.exitCode = 1;
            return;
        }
        res.exitCode = 0;
    }

    // ------------------------------------------------------------- ifscopy
    /**
     * IFS copy, in one of two shapes chosen by the {@code listSource} param:
     * <ul>
     *   <li>{@code pattern} (default, and what the param's absence means): list a directory on the
     *       IFS and copy what matches the glob - the behaviour this executor has always had, byte
     *       for byte, including which variables it publishes;</li>
     *   <li>{@code csv}: copy exactly the files named in one column of a CSV, typically the output
     *       of an earlier step in the same workflow.</li>
     * </ul>
     * An unrecognised value FAILS rather than falling back to the pattern shape: a typo would
     * otherwise be answered by a directory copy with no pattern, i.e. everything.
     */
    private void runIfsCopy(StepDef step, Map<String, String> params, Map<String, String> vars,
                            StepExecutor.Result res, java.util.function.Consumer<String> line) throws Exception {
        publishDataSource(step, res);
        DataSourceDef d = dataSources.get(step.datasource);
        if (d == null) { line.accept("datasource not found: " + step.datasource); res.exitCode = 2; return; }

        String listSource = xStr(VarResolver.resolve(params.get("listSource"), vars), "pattern");
        if ("csv".equalsIgnoreCase(listSource)) {
            runIfsCopyList(step, params, vars, d, res, line);
            return;
        }
        if (!"pattern".equalsIgnoreCase(listSource)) {
            line.accept("ifscopy: listSource must be 'pattern' or 'csv', not '" + listSource + "'");
            res.lastLines = "invalid listSource";
            res.exitCode = 2;
            return;
        }

        String ifsPath = VarResolver.resolve(step.ifsPath, vars);
        String dest = VarResolver.resolve(step.dest, vars);
        String glob = VarResolver.resolve(step.pattern, vars);
        line.accept("IFS copy " + ifsPath + "  ->  " + dest + "  (pattern " + (glob == null ? "*" : glob) + ")");
        IfsSupport.CopyResult cr = ifs.copyToLocal(d, ifsPath, dest, glob, step.overwrite, line);
        res.outVars.put("filesCopied", String.valueOf(cr.filesCopied));
        res.outVars.put("bytesCopied", String.valueOf(cr.bytesCopied));
        res.outVars.put("matchedFiles", String.join(step.delimiter == null ? ";" : step.delimiter, cr.names));
        for (Map.Entry<String, String> e : res.outVars.entrySet()) line.accept("##VAR " + e.getKey() + "=" + e.getValue());
        res.exitCode = 0;
    }

    /**
     * Copy the files named in one column of a CSV.
     *
     * <p>The list is read whole and reported BEFORE anything is transferred - how many rows, how many
     * names, how many duplicates, how many blanks, which column, which base path - so an operator
     * reading the log knows what the step was about to do even when it then fails. Duplicates are
     * collapsed rather than copied twice; a blank cell is counted and its line named rather than
     * being an invisible short delivery, which is the failure mode this project keeps meeting.</p>
     *
     * <p>Two listed files whose names differ but whose LOCAL name is the same would land on top of
     * each other in the destination. That is refused by default ({@code onNameCollision=fail}),
     * because the loss would be silent and the step would report a success with fewer files than it
     * copied.</p>
     */
    private void runIfsCopyList(StepDef step, Map<String, String> params, Map<String, String> vars,
                                DataSourceDef d, StepExecutor.Result res,
                                java.util.function.Consumer<String> line) throws Exception {
        String listFile = blankToNull(VarResolver.resolve(params.get("listFile"), vars));
        String column = blankToNull(VarResolver.resolve(params.get("listColumn"), vars));
        String dest = blankToNull(VarResolver.resolve(step.dest, vars));
        List<String> missingCfg = new ArrayList<String>();
        if (listFile == null) missingCfg.add("listFile");
        if (column == null) missingCfg.add("listColumn");
        if (dest == null) missingCfg.add("dest");
        if (!missingCfg.isEmpty()) {
            line.accept("ifscopy: missing required parameter(s) for a CSV file list: " + String.join(", ", missingCfg));
            res.lastLines = "missing parameter(s): " + String.join(", ", missingCfg);
            res.exitCode = 2;
            return;
        }

        // the explicit prefix wins; without one the existing "IFS source path" field is the base, so a
        // list of bare file names needs no second copy of the directory it came from
        String prefix = blankToNull(VarResolver.resolve(params.get("listPathPrefix"), vars));
        String base = prefix != null ? prefix : blankToNull(VarResolver.resolve(step.ifsPath, vars));
        String baseFrom = prefix != null ? "listPathPrefix" : "the IFS source path";

        java.io.File csv = new java.io.File(rebaseRel(listFile, vars));
        if (!csv.isFile()) {
            line.accept("ifscopy: file list not found: " + csv.getPath());
            res.lastLines = "file list not found: " + csv.getPath();
            res.exitCode = 2;
            return;
        }

        String charset = xStr(VarResolver.resolve(params.get("listCharset"), vars), "UTF-8");
        String dl = VarResolver.resolve(params.get("listDelimiter"), vars);
        char delim = (dl != null && !dl.isEmpty()) ? dl.charAt(0) : detectDelim(csv, ';');
        boolean hasHeader = !"false".equalsIgnoreCase(xStr(params.get("hasHeader"), "true"));
        boolean failOnMissing = !"skip".equalsIgnoreCase(xStr(params.get("onMissingFile"), "fail"));
        boolean failOnCollision = !"overwrite".equalsIgnoreCase(xStr(params.get("onNameCollision"), "fail"));

        String glob = blankToNull(VarResolver.resolve(step.pattern, vars));
        if (glob != null) line.accept("ifscopy: the pattern '" + glob + "' is IGNORED when the list comes from a CSV");

        IfsListSupport.ListResult lr =
                IfsListSupport.read(csv, charset, delim, column, hasHeader, base);
        if (lr.error != null) {
            line.accept("ifscopy: " + lr.error);
            res.lastLines = lr.error;
            res.exitCode = 2;
            return;
        }

        line.accept("IFS copy from the list " + csv.getPath() + "  ->  " + dest);
        line.accept("ifscopy: delimiter '" + delim + "', " + (hasHeader ? "with" : "without")
                + " header, charset " + charset + ", file name in " + lr.columnLabel);
        line.accept("ifscopy: " + lr.dataRows + " row(s) read, " + lr.paths.size() + " file(s) to copy, "
                + lr.duplicates + " duplicate(s) collapsed, " + lr.blankRows + " row(s) with no file name");
        if (!lr.blankLines.isEmpty()) {
            line.accept("ifscopy: no file name at line(s) " + String.join(", ", lr.blankLines)
                    + (lr.blankRows > lr.blankLines.size() ? " ... (" + lr.blankRows + " in total)" : ""));
        }
        line.accept("ifscopy: names that are not absolute are resolved under "
                + (base == null ? "(nothing - set listPathPrefix or the IFS source path if the names are not full paths)" : base + " (from " + baseFrom + ")"));
        for (String c : lr.collisions) line.accept("ifscopy: local name collision - " + c);
        if (!lr.collisions.isEmpty() && failOnCollision) {
            String msg = lr.collisions.size() + " listed file(s) would overwrite each other in " + dest
                    + "; rename them, copy them in separate steps, or set onNameCollision=overwrite";
            line.accept("ifscopy: " + msg);
            res.lastLines = msg;
            res.exitCode = 1;
            return;
        }

        IfsSupport.CopyResult cr = ifs.copyListToLocal(d, lr.paths, dest, step.overwrite, failOnMissing, line);
        String sep = step.delimiter == null ? ";" : step.delimiter;
        res.outVars.put("filesCopied", String.valueOf(cr.filesCopied));
        res.outVars.put("bytesCopied", String.valueOf(cr.bytesCopied));
        res.outVars.put("matchedFiles", String.join(sep, cr.names));
        res.outVars.put("listRows", String.valueOf(lr.dataRows));
        res.outVars.put("listedFiles", String.valueOf(lr.paths.size()));
        res.outVars.put("duplicatesInList", String.valueOf(lr.duplicates));
        res.outVars.put("blankNames", String.valueOf(lr.blankRows));
        res.outVars.put("missingFiles", String.valueOf(cr.missing));
        res.outVars.put("skippedExisting", String.valueOf(cr.skippedExisting));
        for (Map.Entry<String, String> e : res.outVars.entrySet()) line.accept("##VAR " + e.getKey() + "=" + e.getValue());

        if (cr.failure != null) {
            line.accept("ifscopy: " + cr.failure);
            res.lastLines = cr.failure;
            res.exitCode = 1;
            return;
        }
        if (cr.missing > 0) {
            line.accept("ifscopy: " + cr.missing + " listed file(s) were not on the IFS and were skipped"
                    + " (onMissingFile=skip): " + String.join(", ", cr.missingNames)
                    + (cr.missing > cr.missingNames.size() ? " ..." : ""));
        }
        res.exitCode = 0;
    }

    // ------------------------------------------------------------ filecopy
    private void runFileCopy(StepDef step, Map<String, String> vars,
                             StepExecutor.Result res, java.util.function.Consumer<String> line) throws Exception {
        String source = VarResolver.resolve(step.source, vars);
        String dest = VarResolver.resolve(step.dest, vars);
        String glob = VarResolver.resolve(step.pattern, vars);
        String mode = step.mode == null ? "copy" : step.mode.toLowerCase(); // copy | move | list
        if (glob == null || glob.trim().isEmpty()) glob = "*";

        Path src = Paths.get(source);
        if (!Files.isDirectory(src)) { line.accept("source directory not found: " + source); res.exitCode = 2; return; }
        line.accept(mode + "  " + source + "  pattern " + glob + (("list".equals(mode)) ? "" : ("  -> " + dest)));

        List<String> names = new ArrayList<String>();
        long bytes = 0;
        Path outDir = null;
        if (!"list".equals(mode)) {
            outDir = Paths.get(dest);
            Files.createDirectories(outDir);
        }
        DirectoryStream<Path> ds = Files.newDirectoryStream(src, glob);
        try {
            for (Path f : ds) {
                if (Files.isDirectory(f)) continue;
                names.add(f.getFileName().toString());
                if ("list".equals(mode)) continue;
                Path target = outDir.resolve(f.getFileName());
                if ("move".equals(mode)) {
                    Files.move(f, target, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.copy(f, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
                bytes += Files.size(target);
                line.accept(mode + " " + f.getFileName());
            }
        } finally {
            ds.close();
        }
        res.outVars.put("matchedCount", String.valueOf(names.size()));
        res.outVars.put("matchedFiles", String.join(step.delimiter == null ? ";" : step.delimiter, names));
        if (!"list".equals(mode)) res.outVars.put("bytesCopied", String.valueOf(bytes));
        for (Map.Entry<String, String> e : res.outVars.entrySet()) line.accept("##VAR " + e.getKey() + "=" + e.getValue());
        res.exitCode = 0;
    }

    // -------------------------------------------------------------- safecopy
    /**
     * SAFE COPY: copy files matching a wildcard from an input directory to an output
     * directory, writing each file first as {@code <name>.on_fly_} and renaming it to the
     * final name only once the copy is complete (atomic move when possible). This prevents
     * downstream automation watching the landing zone from ever picking up a partial file.
     * The temporary suffix is configurable via the {@code tmpSuffix} param (default .on_fly_).
     */
    private void runSafeCopy(StepDef step, Map<String, String> params, Map<String, String> vars,
                             StepExecutor.Result res, java.util.function.Consumer<String> line) throws Exception {
        String source = VarResolver.resolve(step.source, vars);
        String dest = VarResolver.resolve(step.dest, vars);
        String glob = VarResolver.resolve(step.pattern, vars);
        if (glob == null || glob.trim().isEmpty()) glob = "*";
        // allow several patterns separated by comma or semicolon, e.g. "*.md5, *.tar"
        java.util.List<String> globs = new ArrayList<String>();
        for (String g : glob.split("[,;]")) { String t = g.trim(); if (!t.isEmpty()) globs.add(t); }
        if (globs.isEmpty()) globs.add("*");
        String tmpSuffix = params.get("tmpSuffix");
        if (tmpSuffix == null || tmpSuffix.trim().isEmpty()) tmpSuffix = ".on_fly_";

        if (source == null || source.trim().isEmpty()) { line.accept("safecopy: missing source directory"); res.exitCode = 2; return; }
        if (dest == null || dest.trim().isEmpty()) { line.accept("safecopy: missing dest directory"); res.exitCode = 2; return; }
        Path src = Paths.get(source);
        if (!Files.isDirectory(src)) { line.accept("safecopy: source directory not found: " + source); res.exitCode = 2; return; }
        Path outDir = Paths.get(dest);
        Files.createDirectories(outDir);
        line.accept("safecopy  " + source + "  patterns " + globs + "  -> " + dest + "  (temp suffix " + tmpSuffix + ")");

        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<String>();   // dedup across patterns
        List<String> names = new ArrayList<String>();
        long bytes = 0;
        for (String oneGlob : globs) {
            DirectoryStream<Path> ds = Files.newDirectoryStream(src, oneGlob);
            try {
                for (Path f : ds) {
                    if (Files.isDirectory(f)) continue;
                    String name = f.getFileName().toString();
                    if (name.endsWith(tmpSuffix)) continue;   // never copy someone else's in-flight temp
                    if (!seen.add(name)) continue;            // already copied via another pattern
                    Path tmp = outDir.resolve(name + tmpSuffix);
                    Path target = outDir.resolve(name);
                    Files.copy(f, tmp, StandardCopyOption.REPLACE_EXISTING);
                    try {
                        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
                    } catch (Exception atomicUnsupported) {
                        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    bytes += Files.size(target);
                    names.add(name);
                    line.accept("safecopy " + name);
                }
            } finally {
                ds.close();
            }
        }
        res.outVars.put("matchedCount", String.valueOf(names.size()));
        res.outVars.put("matchedFiles", String.join(step.delimiter == null ? ";" : step.delimiter, names));
        res.outVars.put("bytesCopied", String.valueOf(bytes));
        for (Map.Entry<String, String> e : res.outVars.entrySet()) line.accept("##VAR " + e.getKey() + "=" + e.getValue());
        res.exitCode = 0;
    }

    // -------------------------------------------------------------- dequote
    /**
     * DEQUOTE: read an input CSV and write an output CSV with double quotes removed from the
     * targeted text columns. Wrapping CSV quotes and escaped {@code ""} are first parsed away
     * (RFC-4180), then any remaining literal {@code "} characters are stripped from the chosen
     * columns. Output fields are re-quoted only when structurally required (they contain the
     * delimiter or a newline); set quoteIfNeeded=false to never quote.
     * Params: source (in), outFile (out, default &lt;name&gt;_dequoted), delimiter (empty=sniff),
     * hasHeader (default true), columns (comma-separated names or 1-based indexes; empty=all),
     * bom (default false), quoteIfNeeded (default true).
     */
    /**
     * The ELAR INDX/PULL builder. Everything of substance lives in
     * {@code com.legalarchive.orchestrator.elar}, free of Spring and of the orchestrator's own types,
     * so the whole run is exercised in tests against real files rather than only on deploy. This
     * method does two things and nothing else: translate step parameters into options, and translate
     * counters back into run variables.
     */
    /**
     * The ELAR INDX checker. Read-only: everything it inspects it only reads, and the findings file is
     * written into the STEP directory, never into the inspected one.
     */
    private void runElarCheck(StepDef step, Map<String, String> params, Map<String, String> vars,
                              StepExecutor.Result res, java.util.function.Consumer<String> line) throws Exception {
        com.legalarchive.orchestrator.elarcheck.ElarCheckRun.Options o =
                new com.legalarchive.orchestrator.elarcheck.ElarCheckRun.Options();
        String inputDir = blankToNull(VarResolver.resolve(params.get("inputDir"), vars));
        if (inputDir == null) {
            line.accept("elarcheck: missing required parameter: inputDir");
            res.exitCode = 2;
            return;
        }
        o.inputDir = new java.io.File(inputDir);
        o.filePattern = xStr(params.get("filePattern"), "*INDX*");
        o.inputCharset = xStr(params.get("inputCharset"), "windows-1252");
        o.maxLineLength = intParam(params.get("maxLineLength"), 25000);
        o.receiverLineLimit = intParam(params.get("receiverLineLimit"), 30000);
        o.contentElement = xStr(params.get("contentElement"), "Content");
        o.hashElement = xStr(params.get("hashElement"), "HashValue");
        o.docElement = xStr(params.get("docElement"), "Doc");
        String tags = blankToNull(VarResolver.resolve(params.get("mandatoryTags"), vars));
        if (tags != null) {
            String[] parts = tags.split(",", -1);
            for (int i = 0; i < parts.length; i++) {
                String t = parts[i].trim();
                if (!t.isEmpty()) o.mandatoryTags.add(t);
            }
        }
        o.checkPull = !"false".equalsIgnoreCase(params.get("checkPull"));
        String dd = blankToNull(VarResolver.resolve(params.get("deliveredDir"), vars));
        if (dd != null) o.deliveredDir = new java.io.File(dd);
        o.verifyHash = "true".equalsIgnoreCase(params.get("verifyHash"));
        o.maxFindingsPerFile = intParam(params.get("maxFindingsPerFile"), 100);

        try {
            com.legalarchive.orchestrator.elarcheck.ElarCheckReport rep =
                    com.legalarchive.orchestrator.elarcheck.ElarCheckRun.run(o, line);
            for (Map.Entry<String, String> e : rep.asVars().entrySet()) res.outVars.put(e.getKey(), e.getValue());

            // the findings file goes to the STEP directory. Never to inputDir: writing there would
            // break the property that makes this safe to run on a live delivery folder.
            String sd = VarResolver.resolve("${stepDir}", vars);
            if (sd != null && !sd.trim().isEmpty()) {
                java.io.File out = new java.io.File(sd.trim(), "elarcheck_findings.tsv");
                java.nio.file.Files.write(out.toPath(),
                        rep.toTsv().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                line.accept("elarcheck: findings written to " + out.getName());
                res.outVars.put("findingsFile", out.getAbsolutePath());
            } else {
                line.accept("elarcheck: no step directory available, so the findings file was not"
                        + " written; the counters and the per-file verdicts below carry the same"
                        + " information");
            }

            for (int i = 0; i < rep.files.size(); i++) {
                com.legalarchive.orchestrator.elarcheck.ElarCheckReport.FileReport f = rep.files.get(i);
                line.accept("elarcheck: " + f.name + " = " + f.verdict());
            }
            boolean any = rep.totalFindings() > 0;
            // failing is opt-in: the natural workflow is check, then repair only if the counters say
            // so, and a step that always failed could not drive that
            res.exitCode = (any && "true".equalsIgnoreCase(params.get("failOnFindings"))) ? 2 : 0;
        } catch (Exception ex) {
            line.accept("elarcheck: " + ex.getMessage());
            res.exitCode = 2;
        }
    }

    private void runElarXml(StepDef step, Map<String, String> params, Map<String, String> vars,
                            StepExecutor.Result res, java.util.function.Consumer<String> line) throws Exception {
        com.legalarchive.orchestrator.elar.ElarRun.Options o =
                new com.legalarchive.orchestrator.elar.ElarRun.Options();

        String inputDir = blankToNull(VarResolver.resolve(params.get("inputDir"), vars));
        String outputDir = blankToNull(VarResolver.resolve(params.get("outputDir"), vars));
        String propsPath = blankToNull(VarResolver.resolve(params.get("propertiesPath"), vars));
        String family = blankToNull(VarResolver.resolve(params.get("familyType"), vars));
        String indexTpl = blankToNull(VarResolver.resolve(params.get("indexTemplatePath"), vars));
        String pullTpl = blankToNull(VarResolver.resolve(params.get("pullTemplatePath"), vars));
        // every required parameter is reported in ONE message: an operator configuring a new feed
        // should not have to run the step six times to be told six things
        java.util.List<String> missing = new java.util.ArrayList<String>();
        if (inputDir == null) missing.add("inputDir");
        if (outputDir == null) missing.add("outputDir");
        if (propsPath == null) missing.add("propertiesPath");
        if (family == null) missing.add("familyType");
        if (indexTpl == null) missing.add("indexTemplatePath");
        if (pullTpl == null) missing.add("pullTemplatePath");
        if (!missing.isEmpty()) {
            line.accept("elarxml: missing required parameter(s): " + String.join(", ", missing));
            res.exitCode = 2;
            return;
        }
        o.inputDir = new java.io.File(inputDir);
        o.outputDir = new java.io.File(outputDir);
        o.propertiesFile = new java.io.File(propsPath);
        o.familyType = family;
        o.indexTemplate = new java.io.File(indexTpl);
        o.pullTemplate = new java.io.File(pullTpl);

        o.inputCharset = xStr(params.get("inputCharset"), "UTF-8");
        o.outputCharset = xStr(params.get("outputCharset"), "UTF-8");
        o.failOnMalformedInput = !"REPLACE".equalsIgnoreCase(xStr(params.get("onMalformedInput"), "FAIL"));
        String sep = params.get("separator");
        if (sep != null && !sep.isEmpty()) o.separator = sep.charAt(0);
        String q = params.get("quoteChar");
        if (q != null && !q.isEmpty()) o.quoteChar = q.charAt(0);
        o.listSeparator = xStr(params.get("listSeparator"), ",");
        o.skipPrefix = xStr(params.get("skipPrefix"), "out_");
        o.maxLineLength = intParam(params.get("maxLineLength"), 0);
        o.batchBy = "BYTES".equalsIgnoreCase(xStr(params.get("batchBy"), "DOCUMENTS"))
                ? com.legalarchive.orchestrator.elar.BatchPolicy.By.BYTES
                : com.legalarchive.orchestrator.elar.BatchPolicy.By.DOCUMENTS;
        o.maxBytesPerBatch = longParam(params.get("maxBytesPerBatch"), 200L * 1024 * 1024);
        o.oversize = "FAIL".equalsIgnoreCase(xStr(params.get("oversizeDocumentPolicy"), "WRITE_ALONE"))
                ? com.legalarchive.orchestrator.elar.BatchPolicy.Oversize.FAIL
                : com.legalarchive.orchestrator.elar.BatchPolicy.Oversize.WRITE_ALONE;
        o.onMalformedRowFail = !"SKIP".equalsIgnoreCase(xStr(params.get("onMalformedRow"), "FAIL"));
        // SKIP by default, unlike onMalformedRow. A malformed row means the input is broken and
        // re-running will not help; a missing content file usually means staging has not
        // finished, and the rows that DO have their files are still deliverable.
        o.onMissingFileFail = "FAIL".equalsIgnoreCase(xStr(params.get("onMissingFile"), "SKIP"));
        o.writeSkippedRows = !"false".equalsIgnoreCase(xStr(params.get("writeSkippedRows"), "true"));
        o.formatOutput = !"false".equalsIgnoreCase(xStr(params.get("formatOutput"), "true"));
        o.checkFreeDisk = !"false".equalsIgnoreCase(xStr(params.get("checkFreeDisk"), "true"));
        o.logDocuments = !"false".equalsIgnoreCase(xStr(params.get("logDocuments"), "true"));
        o.validate = "true".equalsIgnoreCase(params.get("validate"));
        o.renameProcessed = !"false".equalsIgnoreCase(params.get("renameProcessed"));
        o.overwriteExisting = "true".equalsIgnoreCase(params.get("overwriteExisting"));
        o.descriptorsElement = xStr(params.get("descriptorsElement"), "DocumentDescriptors");

        // Where the payload is read from. LOCAL keeps every existing workflow exactly as it is.
        String contentSource = xStr(VarResolver.resolve(params.get("contentSource"), vars), "LOCAL");
        if ("IFS".equalsIgnoreCase(contentSource)) {
            publishDataSource(step, res);
            DataSourceDef ds = dataSources.get(step.datasource);
            if (ds == null) {
                line.accept("elarxml: contentSource=IFS needs a datasource; '" + step.datasource
                        + "' is not one of the configured ones");
                res.exitCode = 2;
                return;
            }
            String ifsBase = blankToNull(VarResolver.resolve(params.get("contentIfsPath"), vars));
            int maxListing = intParam(params.get("contentIfsMaxListing"), 500000);
            // the step directory: one document at a time lands here and is deleted as the run goes on
            String stepDir = blankToNull(vars.get("stepDir"));
            java.io.File staging = stepDir != null ? new java.io.File(stepDir) : o.outputDir;
            String lk = xStr(VarResolver.resolve(params.get("contentIfsLookup"), vars), "STAT");
            IfsContentStore.Lookup lookup = "LISTING".equalsIgnoreCase(lk)
                    ? IfsContentStore.Lookup.LISTING : IfsContentStore.Lookup.STAT;
            o.contentStore = new IfsContentStore(new Jt400Ifs(ds), ifsBase, staging, maxListing, lookup, line);
            line.accept("elarxml: content read from IFS via datasource " + step.datasource
                    + (ifsBase == null ? " (column values must be absolute paths)"
                                       : ", base " + ifsBase + " for values that are not absolute")
                    + ", lookup " + lookup
                    + "; the family documentPath is NOT read");
        } else if (!"LOCAL".equalsIgnoreCase(contentSource)) {
            line.accept("elarxml: contentSource must be LOCAL or IFS, not '" + contentSource + "'");
            res.exitCode = 2;
            return;
        } else if (blankToNull(params.get("contentIfsPath")) != null) {
            // a setting that can be read but has no effect is worse than one that is absent
            line.accept("elarxml: contentIfsPath is set to '" + params.get("contentIfsPath")
                    + "' but contentSource is LOCAL, so it is NOT read");
        }

        try {
            com.legalarchive.orchestrator.elar.ElarCounters c =
                    com.legalarchive.orchestrator.elar.ElarRun.run(o, line);
            for (Map.Entry<String, String> e : c.asVars().entrySet()) res.outVars.put(e.getKey(), e.getValue());
            // a run that wrote nothing is not a failure, but it is not a success worth being quiet
            // about either: the counters say so and the step log carries the reason
            res.exitCode = 0;
        } catch (Exception ex) {
            line.accept("elarxml: " + ex.getMessage());
            res.exitCode = 2;
        } finally {
            // ElarRun closes the store on every path it reaches, but it validates the configuration
            // BEFORE that try/finally is entered - a missing idms.namespace, say - and an exception
            // from there would return without ever releasing the connection. Whoever constructs it
            // closes it too; close is idempotent so the two do not fight.
            if (o.contentStore != null) {
                try { o.contentStore.close(); } catch (Exception ignored) { }
            }
        }
    }

    private static int intParam(String v, int def) {
        if (v == null || v.trim().isEmpty()) return def;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return def; }
    }
    private static long longParam(String v, long def) {
        if (v == null || v.trim().isEmpty()) return def;
        try { return Long.parseLong(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    private void runDequote(StepDef step, Map<String, String> params, Map<String, String> vars,
                            StepExecutor.Result res, java.util.function.Consumer<String> line) throws Exception {
        String inPath = VarResolver.resolve(step.source, vars);
        if (inPath == null || inPath.trim().isEmpty()) { line.accept("dequote: missing input file (source)"); res.exitCode = 2; return; }
        java.io.File in = new java.io.File(inPath);
        if (!in.isFile()) { line.accept("dequote: input file not found: " + inPath); res.exitCode = 2; return; }

        char delim = (step.delimiter != null && !step.delimiter.isEmpty()) ? step.delimiter.charAt(0) : sniffDelimiter(in);
        String hh = params.get("hasHeader");      boolean hasHeader = (hh == null) || !hh.equalsIgnoreCase("false");
        String bm = params.get("bom");            boolean bom = (bm != null) && bm.equalsIgnoreCase("true");
        String qn = params.get("quoteIfNeeded");  boolean quoteIfNeeded = (qn == null) || !qn.equalsIgnoreCase("false");

        String outParam = blankToNull(VarResolver.resolve(params.get("outFile"), vars));
        java.io.File out;
        if (outParam != null) out = new java.io.File(outParam);
        else {
            String nm = in.getName();
            String ext = nm.lastIndexOf('.') > 0 ? nm.substring(nm.lastIndexOf('.')) : ".csv";
            out = new java.io.File(in.getParentFile(), stripExt(nm) + "_dequoted" + ext);
        }
        if (out.getParentFile() != null) out.getParentFile().mkdirs();

        java.util.Set<String> targets = new java.util.HashSet<String>();
        String colsParam = blankToNull(VarResolver.resolve(params.get("columns"), vars));
        if (colsParam != null) for (String t : colsParam.split(",")) { String x = t.trim(); if (!x.isEmpty()) targets.add(x); }

        // embedded line breaks inside quoted fields: keep (DEFAULT = legacy, the record stays split)
        // | space | strip. Conservative on purpose: existing feeds keep their exact behaviour.
        String nlMode = blankToNull(VarResolver.resolve(params.get("embeddedNewlines"), vars));
        boolean joinWrapped = "space".equalsIgnoreCase(nlMode) || "strip".equalsIgnoreCase(nlMode);
        String nlRepl = "strip".equalsIgnoreCase(nlMode) ? "" : " ";
        // blank lines are dropped only on request (legacy: they were written out as empty records)
        String dropBlankParam = blankToNull(VarResolver.resolve(params.get("dropBlankLines"), vars));
        boolean dropBlank = "yes".equalsIgnoreCase(dropBlankParam) || "true".equalsIgnoreCase(dropBlankParam);
        long[] joined = new long[1];

        long dataRows = 0, cells = 0, removed = 0, blankLines = 0; int columns = 0;
        boolean[] targeted = null;
        java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(in), "UTF-8"));
        java.io.BufferedWriter w = new java.io.BufferedWriter(new java.io.OutputStreamWriter(new java.io.FileOutputStream(out), "UTF-8"));
        try {
            if (bom) w.write('\uFEFF');
            String first = joinWrapped ? readCsvRecord(r, nlRepl, joined) : r.readLine();
            if (first == null) { line.accept("dequote: empty input"); res.exitCode = 2; return; }
            if (first.length() > 0 && first.charAt(0) == '\uFEFF') first = first.substring(1);

            if (hasHeader) {
                java.util.List<String> hf = parseCsv(first, delim);
                columns = hf.size();
                targeted = new boolean[columns];
                StringBuilder hb = new StringBuilder();
                for (int i = 0; i < columns; i++) {
                    String nm = hf.get(i);
                    targeted[i] = targets.isEmpty() || targets.contains(nm) || targets.contains(String.valueOf(i + 1));
                    if (i > 0) hb.append(delim);
                    String v = nm.replace("\"", "");                 // header names always cleaned
                    hb.append(quoteIfNeeded ? rfcField(v, delim) : v);
                }
                w.write(hb.toString()); w.write("\r\n");
            }

            String ln = hasHeader ? (joinWrapped ? readCsvRecord(r, nlRepl, joined) : r.readLine()) : first;
            while (ln != null) {
                // stray blank lines (the extra line breaks left at the end of a file, or empty lines in
                // the middle) are not valid CSV records, but they are dropped only when asked to
                if (dropBlank && ln.trim().isEmpty()) { blankLines++; ln = r.readLine(); continue; }
                java.util.List<String> f = parseCsv(ln, delim);
                if (columns == 0) columns = f.size();
                if (targeted == null || targeted.length < f.size()) {
                    boolean[] nt = new boolean[f.size()];
                    for (int i = 0; i < nt.length; i++) nt[i] = (targeted != null && i < targeted.length) ? targeted[i]
                            : (targets.isEmpty() || targets.contains(String.valueOf(i + 1)));
                    targeted = nt;
                }
                StringBuilder lb = new StringBuilder();
                for (int i = 0; i < f.size(); i++) {
                    if (i > 0) lb.append(delim);
                    String v = f.get(i);
                    boolean tgt = i < targeted.length ? targeted[i] : targets.isEmpty();
                    if (tgt && v.indexOf('"') >= 0) {
                        int before = v.length();
                        v = v.replace("\"", "");
                        removed += (before - v.length());
                    }
                    if (joinWrapped && (v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0)) {
                        v = v.replace("\r\n", nlRepl).replace("\n", nlRepl).replace("\r", nlRepl);
                    }
                    cells++;
                    lb.append(quoteIfNeeded ? rfcField(v, delim) : v);
                }
                w.write(lb.toString()); w.write("\r\n");
                dataRows++;
                ln = joinWrapped ? readCsvRecord(r, nlRepl, joined) : r.readLine();
            }
        } finally {
            r.close();
            w.close();
        }
        res.outVars.put("outputFile", out.getAbsolutePath());
        res.outVars.put("dataRows", String.valueOf(dataRows));
        res.outVars.put("columns", String.valueOf(columns));
        res.outVars.put("cells", String.valueOf(cells));
        res.outVars.put("quotesRemoved", String.valueOf(removed));
        res.outVars.put("blankLinesRemoved", String.valueOf(blankLines));
        res.outVars.put("embeddedNewlinesRemoved", String.valueOf(joined[0]));
        line.accept("dequote " + in.getName() + " -> " + out.getName() + "  rows=" + dataRows
                + " cols=" + columns + " quotesRemoved=" + removed + " blankLinesRemoved=" + blankLines
                + " embeddedNewlinesRemoved=" + joined[0] + " delim='" + delim + "'");
        for (Map.Entry<String, String> e : res.outVars.entrySet()) line.accept("##VAR " + e.getKey() + "=" + e.getValue());
        res.exitCode = 0;
    }

    // -------------------------------------------------------------- setvar
    /** params are name -> expression; expressions support ${vars} and simple a + b / a - b integer math. */
    private void runSetVar(Map<String, String> params, Map<String, String> vars,
                           StepExecutor.Result res, java.util.function.Consumer<String> line) {
        for (Map.Entry<String, String> e : params.entrySet()) {
            String value = evalArithmetic(e.getValue());
            res.outVars.put(e.getKey(), value);
            line.accept("##VAR " + e.getKey() + "=" + value);
        }
        res.exitCode = 0;
    }

    /** Join per-part row counts with the same separator used for csvFiles, so the two lists stay aligned. */
    private static String joinCounts(java.util.List<Long> xs, String sep) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < xs.size(); i++) { if (i > 0) b.append(sep); b.append(xs.get(i)); }
        return b.toString();
    }

    /**
     * Evaluate a chain of integer additions and subtractions, left to right: {@code "A + B - C + D"}.
     * Anything that is not exactly that shape is returned unchanged, which is the normal case - most
     * setvar values are paths, names and dates, not sums.
     *
     * The space around each operator is REQUIRED, and that is a guard rather than an inconvenience:
     * without it a literal like {@code 2026-08-05} would be read as arithmetic and silently become
     * 2013. Only whitespace-separated terms are considered, so any value with no spaces - a path, a
     * date, a ';'-separated list - passes straight through untouched.
     *
     * Left to right with no precedence: only + and - are supported, so there is nothing to disagree
     * about. Overflow returns the input unchanged rather than a wrapped number.
     */
    static String evalArithmetic(String expr) {
        if (expr == null) return "";
        String s = expr.trim();
        if (s.isEmpty()) return s;
        String[] t = s.split("\\s+");
        if (t.length < 3 || (t.length % 2) == 0) return s;   // must be term (op term)+
        long acc;
        try {
            acc = Long.parseLong(t[0]);
            for (int i = 1; i < t.length; i += 2) {
                String op = t[i];
                long v = Long.parseLong(t[i + 1]);
                if ("+".equals(op)) acc = Math.addExact(acc, v);
                else if ("-".equals(op)) acc = Math.subtractExact(acc, v);
                else return s;                                // not an operator we evaluate
            }
        } catch (NumberFormatException e) {
            return s;                                        // not all terms are integers
        } catch (ArithmeticException e) {
            return s;                                        // overflow: better the input than a wrong number
        }
        return String.valueOf(acc);
    }

    // -------------------------------------------------------------- csvreplace
    // -------------------------------------------------------------- anonymize (ARX) — Batch 1 skeleton
    /**
     * BATCH 1 scaffold for CSV anonymisation with ARX. It performs preflight, a conservative
     * resource fail-fast guard, date-column passthrough detection (with explicit overrides) and
     * writes the output — but the actual ARX transformation is NOT wired yet: every column is
     * passed through verbatim (placeholder), as agreed for Batch 1. Sub-steps are surfaced as a
     * live checklist (like the validate step).
     */
    private void runAnonymize(StepDef step, Map<String, String> params, Map<String, String> vars,
                              StepExecutor.Result res, java.util.function.Consumer<String> line,
                              com.legalarchive.orchestrator.model.run.StepExec se, Runnable onProgress) throws Exception {
        if (passthroughRequested(params, vars)) {
            String inP = VarResolver.resolve(step.source, vars);
            java.io.File inF = (inP == null || inP.trim().isEmpty()) ? null : new java.io.File(inP);
            String outP = blankToNull(VarResolver.resolve(params.get("outFile"), vars));
            java.io.File outF = outP != null ? new java.io.File(outP)
                    : (inF != null && inF.getParentFile() != null ? new java.io.File(inF.getParentFile(), stripExt(inF.getName()) + "_anon" + ext(inF.getName())) : null);
            maskPassthrough(inF, outF, res, line, "true".equalsIgnoreCase(vars.get("__prod")) ? "PROD environment" : "step flag");
            return;
        }
        final java.util.List<com.legalarchive.orchestrator.model.run.CheckResult> checks = new ArrayList<com.legalarchive.orchestrator.model.run.CheckResult>();
        String[][] subs = {
                {"preflight", "Preflight (rows/columns/cells/delimiter/encoding)"},
                {"guard", "Resource guard (estimate vs heap) — fail-fast"},
                {"dates", "Date column detection + overrides"},
                {"config", "Build anonymisation configuration"},
                {"anonymize", "Run anonymisation"},
                {"output", "Write output"}
        };
        for (String[] sb : subs) checks.add(new com.legalarchive.orchestrator.model.run.CheckResult(sb[0], sb[1]));
        if (se != null) { se.checks = checks; if (onProgress != null) onProgress.run(); }
        final java.util.Map<String, com.legalarchive.orchestrator.model.run.CheckResult> byId = new java.util.HashMap<String, com.legalarchive.orchestrator.model.run.CheckResult>();
        for (com.legalarchive.orchestrator.model.run.CheckResult c : checks) byId.put(c.id, c);
        java.util.function.BiConsumer<String, String[]> set = new java.util.function.BiConsumer<String, String[]>() {
            public void accept(String id, String[] sd) {
                com.legalarchive.orchestrator.model.run.CheckResult c = byId.get(id);
                if (c == null) return;
                c.status = sd[0]; c.detail = sd[1];
                line.accept("[" + id + "] " + sd[0] + (sd[1] != null ? ("  " + sd[1]) : ""));
                if (onProgress != null) onProgress.run();
            }
        };
        java.util.function.Consumer<String> running = new java.util.function.Consumer<String>() {
            public void accept(String id) { com.legalarchive.orchestrator.model.run.CheckResult c = byId.get(id); if (c != null) { c.status = "RUNNING"; if (onProgress != null) onProgress.run(); } }
        };

        long maxRows = pLong(params.get("maxRows"), props != null ? props.getAnonymizeMaxRows() : 5_000_000L);
        long maxCells = pLong(params.get("maxCells"), props != null ? props.getAnonymizeMaxCells() : 200_000_000L);
        int bytesPerCell = (int) pLong(params.get("bytesPerCell"), props != null ? props.getAnonymizeBytesPerCell() : 64);
        int heapHeadroomMb = (int) pLong(params.get("heapHeadroomMb"), props != null ? props.getAnonymizeHeapHeadroomMb() : 256);
        int sampleSize = (int) pLong(params.get("dateSampleSize"), props != null ? props.getDateSampleSize() : 200);
        double dateThreshold = pDouble(params.get("datePassthroughThreshold"), props != null ? props.getDatePassthroughThreshold() : 0.95);
        int minYear = (int) pLong(params.get("dateMinYear"), props != null ? props.getDateMinYear() : 1900);
        int maxYear = (int) pLong(params.get("dateMaxYear"), props != null ? props.getDateMaxYear() : 2099);

        String inPath = VarResolver.resolve(step.source, vars);
        java.io.File in = new java.io.File(inPath);
        if (!in.isFile()) { set.accept("preflight", new String[]{"FAIL", "input file not found: " + inPath}); res.exitCode = 2; return; }

        // 1) preflight: quote-aware single streaming pass (bounded memory)
        running.accept("preflight");
        char delim = (step.delimiter != null && !step.delimiter.isEmpty()) ? step.delimiter.charAt(0) : sniffDelimiter(in);
        long fileBytes = in.length();
        java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(in), StandardCharsets.UTF_8), 1 << 16);
        long dataRows = 0; int columns = 0;
        java.util.List<String> headerCols = null;
        boolean bom = false;
        try {
            r.mark(4); if (r.read() != 0xFEFF) r.reset(); else bom = true;
            String header = r.readLine();
            if (header != null) { headerCols = parseCsv(header, delim); columns = headerCols.size(); }
            boolean inQ = false, anyChar = false; int ch;
            while ((ch = r.read()) != -1) {
                char c = (char) ch;
                if (c == '"') { inQ = !inQ; anyChar = true; continue; }
                if (!inQ && c == '\n') { dataRows++; anyChar = false; continue; }
                if (c != '\r') anyChar = true;
            }
            if (anyChar) dataRows++;
        } finally { r.close(); }
        long cells = dataRows * (long) columns;
        set.accept("preflight", new String[]{"PASS", dataRows + " rows x " + columns + " cols = " + cells + " cells; delimiter '" + delim + "'; " + fileBytes + " bytes; UTF-8" + (bom ? " (BOM)" : "")});
        res.outVars.put("dataRows", String.valueOf(dataRows));
        res.outVars.put("columns", String.valueOf(columns));
        res.outVars.put("cells", String.valueOf(cells));

        // 2) resource guard (conservative fail-fast)
        running.accept("guard");
        long maxHeap = Runtime.getRuntime().maxMemory();
        long estimate = cells * (long) bytesPerCell;
        long headroom = (long) heapHeadroomMb * 1024L * 1024L;
        java.util.List<String> guardFail = new ArrayList<String>();
        if (maxRows > 0 && dataRows > maxRows) guardFail.add("rows " + dataRows + " > limit " + maxRows);
        if (maxCells > 0 && cells > maxCells) guardFail.add("cells " + cells + " > limit " + maxCells);
        if (estimate + headroom > maxHeap) guardFail.add("estimate " + mb(estimate) + "MB + headroom " + heapHeadroomMb + "MB > maxHeap " + mb(maxHeap) + "MB");
        if (!guardFail.isEmpty()) {
            set.accept("guard", new String[]{"FAIL", String.join("; ", guardFail) + " — aborting before ARX to avoid OutOfMemory"});
            for (String id : new String[]{"dates", "config", "anonymize", "output"}) set.accept(id, new String[]{"SKIP", "blocked by resource guard"});
            res.exitCode = 3;
            return;
        }
        set.accept("guard", new String[]{"PASS", "estimate ~" + mb(estimate) + "MB, maxHeap " + mb(maxHeap) + "MB, headroom " + heapHeadroomMb + "MB (conservative proxy)"});

        // 3) date column detection + overrides
        running.accept("dates");
        java.util.Set<String> forcePass = csvSet(params.get("forcePassthroughColumns"));
        java.util.Set<String> forceAnon = csvSet(params.get("forceAnonymizeColumns"));
        java.util.List<String> dateCols = new ArrayList<String>();
        boolean[] dateAuto = new boolean[Math.max(columns, 0)];
        int[] colMaxLen = new int[Math.max(columns, 0)];
        if (headerCols != null && columns > 0) {
            int[] sampled = new int[columns];
            int[] matched = new int[columns];
            int[] maxLen = new int[columns];
            java.io.BufferedReader r2 = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(in), StandardCharsets.UTF_8), 1 << 16);
            try {
                r2.mark(4); if (r2.read() != 0xFEFF) r2.reset();
                r2.readLine();
                String dl; boolean more = true;
                while (more && (dl = r2.readLine()) != null) {
                    java.util.List<String> f = parseCsv(dl, delim);
                    more = false;
                    for (int ci = 0; ci < columns; ci++) {
                        if (sampled[ci] >= sampleSize) continue;
                        more = true;
                        String raw = ci < f.size() ? f.get(ci) : "";
                        if (raw.length() > maxLen[ci]) maxLen[ci] = raw.length();
                        String v = raw.trim();
                        if (v.isEmpty()) continue;
                        sampled[ci]++;
                        if (looksLikeDate(v, minYear, maxYear)) matched[ci]++;
                    }
                }
            } finally { r2.close(); }
            colMaxLen = maxLen;
            for (int ci = 0; ci < columns; ci++) {
                String name = headerCols.get(ci).trim();
                boolean pass = sampled[ci] > 0 && (matched[ci] / (double) sampled[ci]) >= dateThreshold;
                if (forceAnon.contains(name)) pass = false;
                if (forcePass.contains(name)) pass = true;
                if (pass) { dateCols.add(name); dateAuto[ci] = true; }
            }
        }
        set.accept("dates", new String[]{"PASS", dateCols.isEmpty() ? "no date-passthrough columns" : (dateCols.size() + " passthrough: " + join(dateCols, 8))});
        res.outVars.put("dateColumns", String.join(",", dateCols));

        // 4) build configuration — resolve per-column roles (Batch 2a)
        running.accept("config");
        java.util.Set<String> identSet = csvSet(params.get("identifyingColumns"));
        java.util.Set<String> quasiSet = csvSet(params.get("quasiColumns"));
        java.util.Set<String> sensSet = csvSet(params.get("sensitiveColumns"));
        java.util.Set<String> freeSet = csvSet(params.get("freeTextColumns"));
        int freeTextThreshold = (int) pLong(params.get("freeTextThreshold"), 50);
        String strategy = blankToNull(params.get("freeTextStrategy"));
        if (strategy == null) strategy = "redact";
        String maskParam = params.get("maskChar");
        char maskChar = (maskParam != null && !maskParam.isEmpty()) ? maskParam.charAt(0) : '\u2588';
        int kAnon = (int) pLong(params.get("k"), 5);   // recorded; enforced in Batch 2b (ARX)

        // role per column: IDENTIFYING / QUASI / SENSITIVE / FREETEXT / INSENSITIVE
        String[] role = new String[columns];
        int nIdent = 0, nQuasi = 0, nSens = 0, nFree = 0, nIns = 0;
        java.util.List<String> freeColNames = new ArrayList<String>();
        for (int ci = 0; ci < columns; ci++) {
            String name = headerCols != null ? headerCols.get(ci).trim() : ("col" + (ci + 1));
            String rr;
            if (identSet.contains(name)) rr = "IDENTIFYING";
            else if (quasiSet.contains(name)) rr = "QUASI";
            else if (sensSet.contains(name)) rr = "SENSITIVE";
            else if (freeSet.contains(name)) rr = "FREETEXT";
            else if (dateAuto[ci]) rr = "INSENSITIVE";                          // date passthrough (Batch 1)
            else if (colMaxLen[ci] > freeTextThreshold) rr = "FREETEXT";        // auto free-text
            else rr = "INSENSITIVE";
            role[ci] = rr;
            if ("IDENTIFYING".equals(rr)) nIdent++;
            else if ("QUASI".equals(rr)) nQuasi++;
            else if ("SENSITIVE".equals(rr)) nSens++;
            else if ("FREETEXT".equals(rr)) { nFree++; freeColNames.add(name); }
            else nIns++;
        }
        set.accept("config", new String[]{"PASS", "roles — identifying:" + nIdent + " quasi:" + nQuasi
                + " sensitive:" + nSens + " free-text:" + nFree + " insensitive:" + nIns
                + " | k=" + kAnon + " | free-text strategy=" + strategy
                + (freeColNames.isEmpty() ? "" : " " + join(freeColNames, 8))});

        // 5) anonymise — Batch 2a applies the DETERMINISTIC transforms (free-text editing +
        //    identifying suppression). Quasi/sensitive generalisation (k-anonymity) is ARX = Batch 2b.
        running.accept("anonymize");
        String outParam = blankToNull(VarResolver.resolve(params.get("outFile"), vars));
        java.io.File out = outParam != null ? new java.io.File(outParam)
                : new java.io.File(in.getParentFile(), stripExt(in.getName()) + "_anon" + ext(in.getName()));
        if (out.getParentFile() != null) out.getParentFile().mkdirs();
        java.io.File tmp = new java.io.File(out.getParentFile(), out.getName() + ".tmp");

        long editedFields = 0, suppressedFields = 0, recordsOut = 0;
        java.io.BufferedReader rr = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(in), StandardCharsets.UTF_8), 1 << 16);
        java.io.BufferedWriter w = new java.io.BufferedWriter(new java.io.OutputStreamWriter(new java.io.FileOutputStream(tmp), StandardCharsets.UTF_8), 1 << 16);
        String CRLF = String.valueOf((char) 13) + String.valueOf((char) 10);
        try {
            rr.mark(4); if (rr.read() != 0xFEFF) rr.reset();
            // header: emit verbatim (first quote-aware record)
            String headerRec = readRecord(rr);
            if (headerRec != null) { w.write(headerRec); w.write(CRLF); }
            String rec;
            while ((rec = readRecord(rr)) != null) {
                if (rec.isEmpty()) continue;
                java.util.List<String> vals = new ArrayList<String>();
                java.util.List<Boolean> quoted = new ArrayList<Boolean>();
                parseCells(rec, delim, vals, quoted);
                StringBuilder ob = new StringBuilder(rec.length() + 16);
                int n = Math.max(vals.size(), columns);
                for (int ci = 0; ci < vals.size(); ci++) {
                    String v = vals.get(ci);
                    String roleCi = ci < columns ? role[ci] : "INSENSITIVE";
                    if ("IDENTIFYING".equals(roleCi)) { if (!v.isEmpty()) suppressedFields++; v = ""; }
                    else if ("FREETEXT".equals(roleCi)) { String e = editFreeText(v, strategy, maskChar); if (!e.equals(v)) editedFields++; v = e; }
                    // QUASI / SENSITIVE / INSENSITIVE -> passthrough in Batch 2a
                    if (ci > 0) ob.append(delim);
                    appendCell(ob, v, quoted.get(ci), delim);
                }
                w.write(ob.toString()); w.write(CRLF); recordsOut++;
            }
        } finally { rr.close(); w.close(); }
        set.accept("anonymize", new String[]{"PASS", "free-text edited: " + editedFields + " field(s) in " + nFree
                + " col(s) (" + strategy + "); identifying suppressed: " + suppressedFields + " field(s) in " + nIdent + " col(s)"
                + (nQuasi + nSens > 0 ? "; quasi/sensitive (" + (nQuasi + nSens) + " col) generalisation deferred to ARX/Batch 2b" : "")});

        // 6) finalise output
        running.accept("output");
        java.nio.file.Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
        set.accept("output", new String[]{"PASS", "written " + out.getName() + " (" + recordsOut + " rows)"});
        res.outVars.put("outputFile", out.getAbsolutePath());
        res.outVars.put("freeTextColumns", String.join(",", freeColNames));
        line.accept("##VAR outputFile=" + out.getAbsolutePath());
        line.accept("##VAR dataRows=" + dataRows);
        line.accept("##VAR dateColumns=" + String.join(",", dateCols));
        res.exitCode = 0;
        line.accept("anonymize (Batch 2a): free-text editing + identifying suppression applied; k-anonymity (ARX) pending Batch 2b");
    }

    // ---- free-text editing (preserve exact character length) ----
    private static final String LOREM = "lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod tempor incididunt ut labore et dolore magna aliqua ut enim ad minim veniam quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat ";
    private static final java.util.regex.Pattern[] PII = {
            java.util.regex.Pattern.compile("[A-Z]{2}\\d{2}[A-Z0-9]{11,30}"),                         // IBAN
            java.util.regex.Pattern.compile("[A-Z]{6}\\d{2}[A-Z]\\d{2}[A-Z]\\d{3}[A-Z]"),             // codice fiscale
            java.util.regex.Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+"),                            // email
            java.util.regex.Pattern.compile("\\d{4}[-/]\\d{2}[-/]\\d{2}|\\d{2}[-/]\\d{2}[-/]\\d{4}"),  // date
            java.util.regex.Pattern.compile("\\+?\\d[\\d\\-/ ]{6,}\\d"),                                // phone
            java.util.regex.Pattern.compile("\\d{1,3}(?:[.,]\\d{3})*[.,]\\d{2}")                        // amount
    };
    private static String editFreeText(String v, String strategy, char mask) {
        if (v == null || v.isEmpty()) return v;
        int len = v.length();
        if ("lorem".equalsIgnoreCase(strategy)) {
            StringBuilder sb = new StringBuilder(len);
            while (sb.length() < len) sb.append(LOREM);
            return sb.substring(0, len);
        }
        // redact: replace each PII match with `mask` repeated for the match's length (length preserved)
        String out = v;
        for (java.util.regex.Pattern p : PII) {
            java.util.regex.Matcher m = p.matcher(out);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                int mlen = m.end() - m.start();
                StringBuilder rep = new StringBuilder(mlen);
                for (int i = 0; i < mlen; i++) rep.append(mask);
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(rep.toString()));
            }
            m.appendTail(sb);
            out = sb.toString();
        }
        return out;
    }

    // ---- quote-aware record reader: returns one CSV record (may span newlines inside quotes) ----
    private static String readRecord(java.io.BufferedReader r) throws java.io.IOException {
        StringBuilder sb = new StringBuilder();
        boolean inQ = false; int ch; boolean any = false;
        while ((ch = r.read()) != -1) {
            any = true;
            char c = (char) ch;
            if (c == '"') { inQ = !inQ; sb.append(c); continue; }
            if (!inQ && c == '\n') { break; }
            if (!inQ && c == '\r') { continue; }   // drop CR (we re-emit CRLF)
            sb.append(c);
        }
        if (!any && sb.length() == 0) return null;
        return sb.toString();
    }

    // ---- parse a record into cells, recording whether each was originally quoted ----
    private static void parseCells(String rec, char delim, java.util.List<String> values, java.util.List<Boolean> quoted) {
        StringBuilder cur = new StringBuilder();
        boolean inQ = false, wasQ = false;
        for (int i = 0; i < rec.length(); i++) {
            char c = rec.charAt(i);
            if (inQ) {
                if (c == '"') { if (i + 1 < rec.length() && rec.charAt(i + 1) == '"') { cur.append('"'); i++; } else inQ = false; }
                else cur.append(c);
            } else {
                if (c == '"') { inQ = true; wasQ = true; }
                else if (c == delim) { values.add(cur.toString()); quoted.add(wasQ); cur.setLength(0); wasQ = false; }
                else cur.append(c);
            }
        }
        values.add(cur.toString()); quoted.add(wasQ);
    }

    // ---- emit a cell, preserving original quoting style (and quoting when content requires it) ----
    private static void appendCell(StringBuilder ob, String v, boolean wasQuoted, char delim) {
        boolean need = wasQuoted || v.indexOf('"') >= 0 || v.indexOf(delim) >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0;
        if (!need) { ob.append(v); return; }
        ob.append('"');
        for (int i = 0; i < v.length(); i++) { char c = v.charAt(i); if (c == '"') ob.append('"'); ob.append(c); }
        ob.append('"');
    }

    // ----------------------------------------------------------------- split
    /**
     * SPLIT executor: split an existing file into parts by row count and/or byte size,
     * reusing the SAME rotation semantics as the SQL CSV export (header repeated per part,
     * parts named stem_001.ext, optional BOM, CRLF). Lines are passed through verbatim
     * (no re-quoting), so already-masked/validated content is preserved. Exposes the same
     * variables as the SQL split: rowCount, csvParts, csvFile (first), csvFiles (joined),
     * so a LOOP can iterate ${csvFiles} regardless of where the split happened.
     */
    private void runSplit(StepDef step, Map<String, String> params, Map<String, String> vars,
                          StepExecutor.Result res, java.util.function.Consumer<String> line) throws Exception {
        String inPath = VarResolver.resolve(step.source, vars);
        if (inPath == null || inPath.trim().isEmpty()) { line.accept("split: missing input file (set 'source')"); res.exitCode = 2; return; }
        java.io.File in = new java.io.File(inPath.trim());
        if (!in.isFile()) { line.accept("split: input file not found: " + in.getAbsolutePath()); res.exitCode = 2; return; }

        String outBase = VarResolver.resolve(step.csvFile, vars);
        java.io.File base = (outBase != null && !outBase.trim().isEmpty()) ? new java.io.File(outBase.trim()) : in;
        if (base.getParentFile() != null) base.getParentFile().mkdirs();

        long maxRows = step.csvSplitRows > 0 ? step.csvSplitRows : 0;
        long maxBytes = step.csvSplitMb > 0 ? (long) step.csvSplitMb * 1024L * 1024L : 0;
        boolean split = maxRows > 0 || maxBytes > 0;
        boolean hasHeader = !"false".equalsIgnoreCase(params.get("hasHeader"));
        boolean bom = !"false".equalsIgnoreCase(params.get("bom"));
        String sep = (step.delimiter == null || step.delimiter.isEmpty()) ? ";" : step.delimiter;

        line.accept("split: input " + in.getAbsolutePath());
        if (split) line.accept("split: " + (maxRows > 0 ? (maxRows + " rows/part") : "")
                + (maxBytes > 0 ? ((maxRows > 0 ? " or " : "") + step.csvSplitMb + " MB/part") : ""));
        else line.accept("split: no row/byte limit set -> single output file");

        java.util.List<String> files = new java.util.ArrayList<String>();
        java.util.List<Long> partRows = new java.util.ArrayList<Long>();
        long dataRows = 0;
        java.io.BufferedReader r = null;
        java.io.Writer w = null;
        try {
            r = new java.io.BufferedReader(new java.io.InputStreamReader(
                    new java.io.FileInputStream(in), StandardCharsets.UTF_8), 1 << 16);
            String headerLine = null;
            if (hasHeader) {
                headerLine = r.readLine();
                if (headerLine != null && !headerLine.isEmpty() && headerLine.charAt(0) == '\uFEFF') headerLine = headerLine.substring(1);
            }
            long headerBytes = headerLine == null ? 0 : (utf8Len(headerLine) + 2);

            int part = 0;
            long rowsInPart = 0, bytesInPart = 0;
            String ln;
            while ((ln = r.readLine()) != null) {
                if (dataRows == 0 && !hasHeader && !ln.isEmpty() && ln.charAt(0) == '\uFEFF') ln = ln.substring(1);
                long rb = utf8Len(ln) + 2;
                boolean rollover = (w == null) || (split && rowsInPart > 0 && (
                        (maxRows > 0 && rowsInPart >= maxRows) ||
                        (maxBytes > 0 && bytesInPart + rb > maxBytes)));
                if (rollover) {
                    if (w != null) { partRows.add(rowsInPart); w.close(); w = null; }
                    part++;
                    java.io.File f = split ? partName(base, part) : base;
                    if (f.getParentFile() != null) f.getParentFile().mkdirs();
                    w = new java.io.BufferedWriter(new java.io.OutputStreamWriter(
                            new java.io.FileOutputStream(f), StandardCharsets.UTF_8), 1 << 16);
                    if (bom) w.write('\uFEFF');
                    if (headerLine != null) { w.write(headerLine); w.write("\r\n"); }
                    files.add(f.getAbsolutePath());
                    rowsInPart = 0;
                    bytesInPart = (headerLine != null ? headerBytes : 0) + (bom ? 1 : 0);
                }
                w.write(ln); w.write("\r\n");
                rowsInPart++; bytesInPart += rb; dataRows++;
            }
            if (w == null) {   // empty input (no data rows): still emit a first file with header
                java.io.File f = split ? partName(base, 1) : base;
                if (f.getParentFile() != null) f.getParentFile().mkdirs();
                w = new java.io.BufferedWriter(new java.io.OutputStreamWriter(
                        new java.io.FileOutputStream(f), StandardCharsets.UTF_8), 1 << 16);
                if (bom) w.write('\uFEFF');
                if (headerLine != null) { w.write(headerLine); w.write("\r\n"); }
                files.add(f.getAbsolutePath());
            }
            partRows.add(rowsInPart);
            w.flush();
        } finally {
            if (w != null) try { w.close(); } catch (Exception ignore) {}
            if (r != null) try { r.close(); } catch (Exception ignore) {}
        }

        res.outVars.put("rowCount", String.valueOf(dataRows));
        res.outVars.put("csvParts", String.valueOf(files.size()));
        res.outVars.put("csvFile", files.isEmpty() ? base.getAbsolutePath() : files.get(0));
        res.outVars.put("csvFiles", String.join(sep, files));
        res.outVars.put("csvRowCounts", joinCounts(partRows, sep));
        if (files.size() > 1) line.accept("split " + dataRows + " row(s) into " + files.size() + " part(s)");
        else line.accept("split: " + dataRows + " row(s) -> " + res.outVars.get("csvFile"));
        for (String f : files) line.accept("  " + f);
        for (Map.Entry<String, String> e : res.outVars.entrySet()) line.accept("##VAR " + e.getKey() + "=" + e.getValue());
        res.exitCode = 0;
    }

    private static long utf8Len(String s) { return s.getBytes(StandardCharsets.UTF_8).length; }

    private static java.io.File partName(java.io.File base, int n) {
        String name = base.getName();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        return new java.io.File(base.getParentFile(), stem + "_" + String.format("%03d", n) + ext);
    }

    private static long pLong(String v, long def) { try { return v == null || v.trim().isEmpty() ? def : Long.parseLong(v.trim()); } catch (Exception e) { return def; } }

    /** Pool file-name selector: returns the chosen file name, or the default if blank.
     *  Hardened to a bare file name (strips any path) to avoid traversal. */
    private static String poolFile(String v, String def) {
        if (v == null) return def;
        String t = v.trim();
        if (t.isEmpty()) return def;
        t = t.replace('\\', '/');
        int slash = t.lastIndexOf('/');
        if (slash >= 0) t = t.substring(slash + 1);
        return t.isEmpty() ? def : t;
    }
    private static double pDouble(String v, double def) { try { return v == null || v.trim().isEmpty() ? def : Double.parseDouble(v.trim()); } catch (Exception e) { return def; } }
    private static long mb(long bytes) { return bytes / (1024L * 1024L); }
    private static java.util.Set<String> csvSet(String v) {
        java.util.Set<String> out = new java.util.HashSet<String>();
        if (v != null) for (String t : v.split(",")) { String tt = t.trim(); if (!tt.isEmpty()) out.add(tt); }
        return out;
    }
    private static char sniffDelimiter(java.io.File f) {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(f), StandardCharsets.UTF_8));
            try {
                r.mark(4); if (r.read() != 0xFEFF) r.reset();
                String h = r.readLine();
                if (h == null) return ';';
                int sc = 0, cc = 0; for (int i = 0; i < h.length(); i++) { char c = h.charAt(i); if (c == ';') sc++; else if (c == ',') cc++; }
                return (cc > sc) ? ',' : ';';
            } finally { r.close(); }
        } catch (Exception e) { return ';'; }
    }
    private static final java.util.regex.Pattern DATE_SLASH = java.util.regex.Pattern.compile("^\\d{4}/(0[1-9]|1[0-2])/(0[1-9]|[12]\\d|3[01])$");
    private static final java.util.regex.Pattern DATE_DASH = java.util.regex.Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\\d|3[01])$");
    private static final java.util.regex.Pattern DATE_COMPACT = java.util.regex.Pattern.compile("^\\d{4}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])$");
    private static boolean looksLikeDate(String v, int minYear, int maxYear) {
        if (!(DATE_SLASH.matcher(v).matches() || DATE_DASH.matcher(v).matches() || DATE_COMPACT.matcher(v).matches())) return false;
        try { int y = Integer.parseInt(v.substring(0, 4)); return y >= minYear && y <= maxYear; } catch (Exception e) { return false; }
    }

    // -------------------------------------------------------------- mask (deterministic streaming) — Batch 1
    /**
     * Deterministic, key-driven CSV masking in a single streaming pass (constant memory; handles ~1GB).
     * Driven by a displayschema JSON whose columns carry an `anonType`. Batch 1 strategies:
     * date (passthrough), numericFixed, alphanumericFixed, cid — all format-preserving, exact length,
     * empty stays empty. Pool-based and free-text strategies are later batches.
     *
     * Params: displayschema (path, required), delimiter (sniffed if absent), outFile (default
     * <name>_masked.<ext>), unmappedColumnPolicy (fail|redact|passthrough, default fail),
     * numericPreserveSeparators (default true). Secret + normalisation come from application.properties
     * (orchestrator.masking-secret, orchestrator.mask-normalize).
     */
    private void runMask(StepDef step, Map<String, String> params, Map<String, String> vars,
                         StepExecutor.Result res, java.util.function.Consumer<String> line,
                         com.legalarchive.orchestrator.model.run.StepExec se, Runnable onProgress) throws Exception {
        if (passthroughRequested(params, vars)) {
            String inP = VarResolver.resolve(step.source, vars);
            java.io.File inF = (inP == null || inP.trim().isEmpty()) ? null : new java.io.File(inP);
            String outP = blankToNull(VarResolver.resolve(params.get("outFile"), vars));
            java.io.File outF = outP != null ? new java.io.File(outP)
                    : (inF != null && inF.getParentFile() != null ? new java.io.File(inF.getParentFile(), stripExt(inF.getName()) + "_masked" + ext(inF.getName())) : null);
            maskPassthrough(inF, outF, res, line, "true".equalsIgnoreCase(vars.get("__prod")) ? "PROD environment" : "step flag");
            return;
        }
        final java.util.List<com.legalarchive.orchestrator.model.run.CheckResult> checks = new ArrayList<com.legalarchive.orchestrator.model.run.CheckResult>();
        String[][] subs = {
                {"schema", "Load & validate displayschema (every column classified)"},
                {"preflight", "Preflight (rows/columns/delimiter/encoding)"},
                {"init", "Init secret + deterministic RNG"},
                {"mask", "Masking (streaming)"},
                {"output", "Write output + verify row count"}
        };
        for (String[] sb : subs) checks.add(new com.legalarchive.orchestrator.model.run.CheckResult(sb[0], sb[1]));
        if (se != null) { se.checks = checks; if (onProgress != null) onProgress.run(); }
        final java.util.Map<String, com.legalarchive.orchestrator.model.run.CheckResult> byId = new java.util.HashMap<String, com.legalarchive.orchestrator.model.run.CheckResult>();
        for (com.legalarchive.orchestrator.model.run.CheckResult c : checks) byId.put(c.id, c);
        java.util.function.BiConsumer<String, String[]> set = new java.util.function.BiConsumer<String, String[]>() {
            public void accept(String id, String[] sd) {
                com.legalarchive.orchestrator.model.run.CheckResult c = byId.get(id);
                if (c == null) return;
                c.status = sd[0]; c.detail = sd[1];
                line.accept("[" + id + "] " + sd[0] + (sd[1] != null ? ("  " + sd[1]) : ""));
                if (onProgress != null) onProgress.run();
            }
        };
        java.util.function.Consumer<String> running = new java.util.function.Consumer<String>() {
            public void accept(String id) { com.legalarchive.orchestrator.model.run.CheckResult c = byId.get(id); if (c != null) { c.status = "RUNNING"; if (onProgress != null) onProgress.run(); } }
        };

        String inPath = VarResolver.resolve(step.source, vars);
        java.io.File in = new java.io.File(inPath);
        if (!in.isFile()) { set.accept("schema", new String[]{"FAIL", "input file not found: " + inPath}); res.exitCode = 2; return; }

        // ---- 1) schema / column mapping ----
        running.accept("schema");
        String schemaPath = blankToNull(VarResolver.resolve(params.get("displayschema"), vars));
        java.util.Map<String, String> anonByName = new java.util.LinkedHashMap<String, String>();
        java.util.Map<String, String> typeByName = new java.util.LinkedHashMap<String, String>();

        // (A) column-list mode: one comma-separated param per anonType (the displayschema cannot carry
        //     anonType because it is fixed by the destination system). Columns not in any list are ignored.
        java.util.List<String> listConflicts = new ArrayList<String>();
        boolean listMode = collectColumnLists(params, anonByName, listConflicts);

        // (B) displayschema (optional in list mode): used for DataType=date passthrough priority.
        if (schemaPath != null) {
            try {
                java.util.Map<String, String> anonFromSchema = new java.util.LinkedHashMap<String, String>();
                parseDisplaySchema(new java.io.File(schemaPath), anonFromSchema, typeByName);
                if (!listMode) anonByName.putAll(anonFromSchema);   // legacy mode: anonType lives in the JSON
            } catch (Exception e) {
                set.accept("schema", new String[]{"FAIL", "cannot read displayschema: " + e.getMessage()});
                res.exitCode = 2; return;
            }
        }
        if (!listMode && anonByName.isEmpty() && typeByName.isEmpty()) {
            set.accept("schema", new String[]{"FAIL", "provide per-anonType column lists (e.g. cidColumns=CID,...) or a displayschema with anonType"});
            res.exitCode = 2; return;
        }

        String unmappedPolicy = blankToNull(params.get("unmappedColumnPolicy"));
        if (unmappedPolicy == null) unmappedPolicy = listMode ? "passthrough" : "fail";   // list mode: ignore unmapped by default
        boolean numSep = !"false".equalsIgnoreCase(params.get("numericPreserveSeparators"));
        String normMode = props != null && props.getMaskNormalize() != null ? props.getMaskNormalize() : "trimUpper";

        char delim = (step.delimiter != null && !step.delimiter.isEmpty()) ? step.delimiter.charAt(0) : sniffDelimiter(in);
        // detect EOL to preserve it on output
        String eol = detectEol(in);

        // read header (quote-aware) to map columns
        java.util.List<String> headerCols;
        boolean bom;
        {
            java.io.BufferedReader hr = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(in), StandardCharsets.UTF_8), 1 << 16);
            try {
                hr.mark(4); bom = hr.read() == 0xFEFF; if (!bom) hr.reset();
                String headerRec = readRecord(hr);
                java.util.List<String> hv = new ArrayList<String>(); java.util.List<Boolean> hq = new ArrayList<Boolean>();
                parseCells(headerRec == null ? "" : headerRec, delim, hv, hq);
                headerCols = hv;
            } finally { hr.close(); }
        }
        int columns = headerCols.size();

        // resolve per-column anonType/dataType + classify; enforce unmapped policy + unsupported types
        String[] anonByCol = new String[columns];
        String[] typeByCol = new String[columns];
        java.util.List<String> unmapped = new ArrayList<String>();
        java.util.List<String> unsupported = new ArrayList<String>();
        java.util.Set<String> SUPPORTED = new java.util.HashSet<String>(java.util.Arrays.asList(
                "date", "numericFixed", "alphanumericFixed", "cid",
                "firstName", "lastName", "fullName", "city", "address", "company", "customerDescription", "freeText"));
        java.util.Set<String> KNOWN_LATER = new java.util.HashSet<String>();
        for (int ci = 0; ci < columns; ci++) {
            String name = headerCols.get(ci).trim();
            String at = anonByName.get(name);
            String dt = typeByName.get(name);
            anonByCol[ci] = at; typeByCol[ci] = dt;
            boolean isDate = dt != null && dt.trim().equalsIgnoreCase("date");
            if (isDate) continue;                                  // date DataType handled (passthrough), counts as mapped
            if (at == null || at.trim().isEmpty()) { unmapped.add(name); continue; }
            String ata = at.trim();
            if (!SUPPORTED.contains(ata)) {
                if (KNOWN_LATER.contains(ata)) unsupported.add(name + " (" + ata + ")");
                else unsupported.add(name + " (unknown anonType '" + ata + "')");
            }
        }
        if (!unsupported.isEmpty()) {
            set.accept("schema", new String[]{"FAIL", "anonType not available in this build (Batch 2/3): " + join(unsupported, 12)});
            res.exitCode = 2; return;
        }
        if (!unmapped.isEmpty() && "fail".equalsIgnoreCase(unmappedPolicy)) {
            set.accept("schema", new String[]{"FAIL", unmapped.size() + " unmapped column(s) [policy=fail]: " + join(unmapped, 12)});
            res.exitCode = 2; return;
        }
        // warn about names listed in the column-lists that are not present in the CSV header (typos)
        java.util.List<String> unknownListed = new ArrayList<String>();
        if (listMode) {
            java.util.Set<String> headerSet = new java.util.HashSet<String>();
            for (String hc : headerCols) headerSet.add(hc.trim());
            for (String k : anonByName.keySet()) if (!headerSet.contains(k)) unknownListed.add(k);
        }
        String mode = listMode ? "column-list mode" : "displayschema anonType mode";
        StringBuilder sd = new StringBuilder();
        sd.append(columns).append(" columns; ").append(mode).append("; ");
        sd.append(unmapped.isEmpty() ? "all classified" : (unmapped.size() + " ignored/unmapped -> " + unmappedPolicy + " (" + join(unmapped, 8) + ")"));
        if (!listConflicts.isEmpty()) sd.append("; conflicts: ").append(join(listConflicts, 6));
        if (!unknownListed.isEmpty()) sd.append("; listed-but-absent: ").append(join(unknownListed, 6));
        set.accept("schema", new String[]{"PASS", sd.toString()});

        // ---- 2) preflight (quote-aware row count) ----
        running.accept("preflight");
        long dataRows = countDataRowsQuoteAware(in);
        set.accept("preflight", new String[]{"PASS", dataRows + " rows x " + columns + " cols; delimiter '" + delim + "'; UTF-8" + (bom ? " (BOM)" : "") + "; EOL " + ("\r\n".equals(eol) ? "CRLF" : "LF")});
        res.outVars.put("dataRows", String.valueOf(dataRows));
        res.outVars.put("columns", String.valueOf(columns));

        // ---- 3) init secret + engine ----
        running.accept("init");
        String secret = props != null ? props.getMaskingSecret() : null;
        if (secret == null || secret.isEmpty()) {
            set.accept("init", new String[]{"FAIL", "masking secret not configured — set 'orchestrator.masking-secret' in application.properties"});
            for (String id : new String[]{"mask", "output"}) set.accept(id, new String[]{"SKIP", "no secret"});
            res.exitCode = 3; return;
        }
        com.legalarchive.orchestrator.mask.MaskEngine engine = new com.legalarchive.orchestrator.mask.MaskEngine(secret);
        com.legalarchive.orchestrator.mask.MaskPools pools = new com.legalarchive.orchestrator.mask.MaskPools(props != null ? props.getMaskPoolsDir() : null);
        com.legalarchive.orchestrator.mask.MaskGenerators gen = new com.legalarchive.orchestrator.mask.MaskGenerators(engine, pools);
        gen.normMode = normMode;
        gen.numericPreserveSeparators = numSep;
        gen.localePercentIt = (int) pLong(params.get("localePercent"), 100);
        gen.cidMode = blankToNull(params.get("cidMode")) != null ? params.get("cidMode").trim() : "formatPreserving";
        gen.cidMaskPercent = (int) pLong(params.get("cidMaskPercent"), 60);
        gen.cidHashLen = (int) pLong(params.get("cidHashLen"), 12);
        gen.personVsCompanyPercent = (int) pLong(params.get("personVsCompanyPercent"), 70);
        // Per-pool file selection (mix freely, e.g. IT animals + intl colors). Blank = default.
        gen.firstNameFile      = poolFile(params.get("firstNameFile"),      gen.firstNameFile);
        gen.lastNameFile       = poolFile(params.get("lastNameFile"),       gen.lastNameFile);
        gen.cityFile           = poolFile(params.get("cityFile"),           gen.cityFile);
        gen.streetFile         = poolFile(params.get("streetFile"),         gen.streetFile);
        gen.companyAnimalsFile = poolFile(params.get("companyAnimalsFile"), gen.companyAnimalsFile);
        gen.companyColorsFile  = poolFile(params.get("companyColorsFile"),  gen.companyColorsFile);
        gen.companyActionsFile = poolFile(params.get("companyActionsFile"), gen.companyActionsFile);
        gen.companySuffixesFile = poolFile(params.get("companySuffixesFile"), gen.companySuffixesFile);
        set.accept("init", new String[]{"PASS", "deterministic RNG + pools ready (HMAC-SHA256); normalize=" + normMode
                + "; localePercentIt=" + gen.localePercentIt + "; cidMode=" + gen.cidMode});

        // ---- 4) masking (streaming) ----
        running.accept("mask");
        String outParam = blankToNull(VarResolver.resolve(params.get("outFile"), vars));
        java.io.File out = outParam != null ? new java.io.File(outParam)
                : new java.io.File(in.getParentFile(), stripExt(in.getName()) + "_masked" + ext(in.getName()));
        if (out.getParentFile() != null) out.getParentFile().mkdirs();
        java.io.File tmp = new java.io.File(out.getParentFile(), out.getName() + ".tmp");

        long rowsOut = 0;
        long[] perType = new long[1];
        java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(in), StandardCharsets.UTF_8), 1 << 16);
        java.io.BufferedWriter w = new java.io.BufferedWriter(new java.io.OutputStreamWriter(new java.io.FileOutputStream(tmp), StandardCharsets.UTF_8), 1 << 16);
        try {
            r.mark(4); if (r.read() != 0xFEFF) r.reset();
            String headerRec = readRecord(r);
            if (headerRec != null) { w.write(headerRec); w.write(eol); }    // header verbatim
            String rec; long processed = 0; long nextTick = Math.max(1, dataRows / 20);
            while ((rec = readRecord(r)) != null) {
                if (rec.isEmpty()) continue;
                java.util.List<String> vals = new ArrayList<String>();
                java.util.List<Boolean> quoted = new ArrayList<Boolean>();
                parseCells(rec, delim, vals, quoted);
                StringBuilder ob = new StringBuilder(rec.length() + 16);
                for (int ci = 0; ci < vals.size(); ci++) {
                    String v = vals.get(ci);
                    if (ci < columns) {
                        String at = anonByCol[ci];
                        String dt = typeByCol[ci];
                        boolean isDate = dt != null && dt.trim().equalsIgnoreCase("date");
                        if (v == null || v.isEmpty()) { /* empty stays empty for every strategy */ }
                        else if (isDate) { /* passthrough */ }
                        else if (at == null || at.trim().isEmpty()) {
                            if ("redact".equalsIgnoreCase(unmappedPolicy)) v = engine.alphanumericFixed(v, "__redact__", maskNormalize(v, normMode));
                            // passthrough policy: leave v
                        } else {
                            String g = gen.apply(at.trim(), v);          // pool / free-text / cid modes
                            v = (g != null) ? g : maskField(at.trim(), dt, v, engine, numSep, normMode);  // else format-preserving
                        }
                    }
                    if (ci > 0) ob.append(delim);
                    appendCell(ob, v, quoted.get(ci), delim);
                }
                w.write(ob.toString()); w.write(eol); rowsOut++;
                processed++;
                if (onProgress != null && processed % nextTick == 0) {
                    int pct = dataRows > 0 ? (int) Math.min(99, processed * 100 / dataRows) : 0;
                    byId.get("mask").detail = "masking… " + pct + "% (" + processed + "/" + dataRows + ")";
                    onProgress.run();
                }
            }
        } finally { r.close(); w.close(); }
        set.accept("mask", new String[]{"PASS", "masked " + rowsOut + " rows (streaming, constant memory)"});

        // ---- 5) output + row-count verify ----
        running.accept("output");
        if (rowsOut != dataRows) {
            set.accept("output", new String[]{"FAIL", "row count mismatch: in=" + dataRows + " out=" + rowsOut});
            try { tmp.delete(); } catch (Exception ignore) {}
            res.exitCode = 4; return;
        }
        java.nio.file.Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
        set.accept("output", new String[]{"PASS", "written " + out.getName() + " (" + rowsOut + " rows, in==out)"});
        res.outVars.put("outputFile", out.getAbsolutePath());
        line.accept("##VAR outputFile=" + out.getAbsolutePath());
        line.accept("##VAR dataRows=" + dataRows);
        res.exitCode = 0;
        line.accept("mask (Batch 1): deterministic format-preserving masking complete");
    }

    /** apply a Batch-1 anonType to a single value (testable without JSON). DataType=date -> passthrough. */
    static String maskField(String anonType, String dataType, String value, com.legalarchive.orchestrator.mask.MaskEngine eng,
                            boolean numericPreserveSeparators, String normMode) {
        if (value == null) return null;
        if (dataType != null && dataType.trim().equalsIgnoreCase("date")) return value;   // priority passthrough
        if (value.isEmpty()) return value;                                                // empty stays empty
        if (anonType == null) return value;
        String at = anonType.trim();
        String group = at;                          // consistencyGroup default = anonType
        String norm = maskNormalize(value, normMode);
        if ("date".equalsIgnoreCase(at)) return value;
        if ("numericFixed".equals(at)) return eng.numericFixed(value, group, norm, numericPreserveSeparators);
        if ("alphanumericFixed".equals(at) || "cid".equals(at)) return eng.alphanumericFixed(value, group, norm);
        throw new IllegalArgumentException("anonType not supported in Batch 1: " + at);
    }

    static String maskNormalize(String v, String mode) {
        if (v == null) return "";
        if ("none".equalsIgnoreCase(mode)) return v;
        String t = v.trim();
        if ("trim".equalsIgnoreCase(mode)) return t;
        return t.toUpperCase();   // trimUpper (default)
    }

    /** Detect the first line ending in the file: CRLF or LF (default CRLF). */
    private static String detectEol(java.io.File f) {
        try {
            java.io.InputStream is = new java.io.FileInputStream(f);
            try {
                int prev = -1, c;
                int guard = 0;
                while ((c = is.read()) != -1 && guard++ < (1 << 20)) {
                    if (c == '\n') return prev == '\r' ? "\r\n" : "\n";
                    prev = c;
                }
            } finally { is.close(); }
        } catch (Exception ignore) {}
        return "\r\n";
    }

    private long countDataRowsQuoteAware(java.io.File in) throws java.io.IOException {
        java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(in), StandardCharsets.UTF_8), 1 << 16);
        long rows = 0;
        try {
            r.mark(4); if (r.read() != 0xFEFF) r.reset();
            readRecord(r);  // header
            while (readRecord(r) != null) rows++;
        } finally { r.close(); }
        return rows;
    }

    /** Reads per-anonType column-name lists from step params into name->anonType.
        Returns true if at least one list param was provided (list mode). Conflicts (a column in
        more than one list) are resolved by the precedence below and reported. */
    private boolean collectColumnLists(Map<String, String> params, java.util.Map<String, String> anonByName,
                                       java.util.List<String> conflicts) {
        String[][] lists = {
                {"dateColumns", "date"},
                {"cidColumns", "cid"},
                {"numericFixedColumns", "numericFixed"},
                {"alphanumericFixedColumns", "alphanumericFixed"},
                {"firstNameColumns", "firstName"},
                {"lastNameColumns", "lastName"},
                {"fullNameColumns", "fullName"},
                {"cityColumns", "city"},
                {"addressColumns", "address"},
                {"companyColumns", "company"},
                {"customerDescriptionColumns", "customerDescription"},
                {"freeTextColumns", "freeText"}
        };
        boolean any = false;
        for (String[] pair : lists) {
            String raw = params.get(pair[0]);
            if (raw == null || raw.trim().isEmpty()) continue;
            any = true;
            for (String tok : raw.split(",")) {
                String name = tok.trim();
                if (name.isEmpty()) continue;
                if (anonByName.containsKey(name)) {
                    conflicts.add(name + " (" + anonByName.get(name) + " wins over " + pair[1] + ")");
                } else {
                    anonByName.put(name, pair[1]);
                }
            }
        }
        return any;
    }

    /** Parse displayschema JSON into name->anonType and name->DataType. Accepts a top-level array
        or an object with a "columns" array; per entry accepts name|ColumnName, DataType, anonType. */
    @SuppressWarnings("unchecked")
    private void parseDisplaySchema(java.io.File f, java.util.Map<String, String> anonByName,
                                    java.util.Map<String, String> typeByName) throws Exception {
        Object root = jsonMapper.readValue(f, Object.class);
        java.util.List<Object> cols = null;
        if (root instanceof java.util.List) cols = (java.util.List<Object>) root;
        else if (root instanceof java.util.Map) {
            Object c = ((java.util.Map<String, Object>) root).get("columns");
            if (c instanceof java.util.List) cols = (java.util.List<Object>) c;
        }
        if (cols == null) return;
        for (Object o : cols) {
            if (!(o instanceof java.util.Map)) continue;
            java.util.Map<String, Object> m = (java.util.Map<String, Object>) o;
            Object nm = m.get("name"); if (nm == null) nm = m.get("ColumnName"); if (nm == null) nm = m.get("DisplayName");
            if (nm == null) continue;
            String name = String.valueOf(nm).trim();
            Object dt = m.get("DataType"); if (dt == null) dt = m.get("dataType");
            Object at = m.get("anonType"); if (at == null) at = m.get("AnonType");
            if (dt != null) typeByName.put(name, String.valueOf(dt));
            if (at != null) anonByName.put(name, String.valueOf(at));
        }
    }

    // -------------------------------------------------------------- encoding
    /**
     * Normalises a text file's character encoding to UTF-8.
     *  - If the file is already valid UTF-8 (optionally with BOM), only the BOM is adjusted.
     *  - Otherwise it is decoded from the declared source charset (param "from", default
     *    windows-1252) — with an optional best-effort guess — and re-encoded as UTF-8.
     *  - The BOM in the output is controlled by param "bom" (default false = UTF-8 without BOM).
     *
     * IMPORTANT (honest limitation): detecting the charset of a non-UTF-8 file is heuristic
     * and never 100% reliable (e.g. ISO-8859-1 vs Windows-1252 are practically
     * indistinguishable from bytes alone). Declare "from" when you know it.
     *
     * Params: from (source charset if not UTF-8, default windows-1252), bom (true/false,
     * default false), outFile (default: overwrite input). source = input file.
     */
    private void runEncoding(StepDef step, Map<String, String> params, Map<String, String> vars,
                             StepExecutor.Result res, java.util.function.Consumer<String> line) throws Exception {
        String inPath = VarResolver.resolve(step.source, vars);
        // directory (batch) mode: explicit inputDir param, or source pointing to a folder
        String inputDirParam = blankToNull(VarResolver.resolve(params.get("inputDir"), vars));
        java.io.File srcProbe = inPath != null ? new java.io.File(inPath) : null;
        if (inputDirParam != null || (srcProbe != null && srcProbe.isDirectory())) {
            java.io.File inDir = new java.io.File(inputDirParam != null ? inputDirParam : inPath);
            runEncodingBatch(inDir, params, vars, res, line);
            return;
        }
        java.io.File in = new java.io.File(inPath);
        if (!in.isFile()) { res.exitCode = 2; line.accept("encoding: input file not found: " + inPath); return; }

        boolean outBom = "true".equalsIgnoreCase(params.get("bom"));
        String fromName = blankToNull(params.get("from"));
        String outParam = blankToNull(VarResolver.resolve(params.get("outFile"), vars));
        java.io.File out = outParam != null ? new java.io.File(outParam) : in;
        boolean inPlace = out.getAbsolutePath().equals(in.getAbsolutePath());

        byte[] bytes = java.nio.file.Files.readAllBytes(in.toPath());
        // detect/strip an existing BOM
        boolean hadBom = bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF;
        int off = hadBom ? 3 : 0;
        boolean hadUtf16 = bytes.length >= 2 &&
                (((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) || ((bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF));

        String text;
        String detectedFrom;
        if (hadUtf16) {
            // UTF-16 with BOM: decode via the BOM-aware charset
            boolean le = (bytes[0] & 0xFF) == 0xFF;
            text = new String(bytes, 2, bytes.length - 2, le ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_16BE);
            detectedFrom = le ? "UTF-16LE (BOM)" : "UTF-16BE (BOM)";
        } else if (isValidUtf8(bytes, off, bytes.length - off)) {
            text = new String(bytes, off, bytes.length - off, StandardCharsets.UTF_8);
            detectedFrom = "UTF-8" + (hadBom ? " (BOM)" : "");
        } else {
            // not UTF-8: use the declared source charset, else a best-effort guess, else windows-1252
            String srcName = fromName != null ? fromName : guessLegacyCharset(bytes);
            java.nio.charset.Charset src;
            try { src = java.nio.charset.Charset.forName(srcName); }
            catch (Exception e) { src = java.nio.charset.Charset.forName("windows-1252"); srcName = "windows-1252"; }
            text = new String(bytes, off, bytes.length - off, src);
            detectedFrom = srcName + (fromName == null ? " (assumed)" : " (declared)");
        }

        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(text.length() + 8);
        if (outBom) bos.write(0xEF); if (outBom) bos.write(0xBB); if (outBom) bos.write(0xBF);
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        bos.write(body, 0, body.length);
        byte[] result = bos.toByteArray();

        if (inPlace) {
            java.io.File tmp = new java.io.File(out.getParentFile(), out.getName() + ".tmp");
            java.nio.file.Files.write(tmp.toPath(), result);
            java.nio.file.Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } else {
            if (out.getParentFile() != null) out.getParentFile().mkdirs();
            java.nio.file.Files.write(out.toPath(), result);
        }

        line.accept("encoding: source=" + detectedFrom + " -> UTF-8" + (outBom ? " with BOM" : " without BOM")
                + (hadBom && !outBom ? " (BOM removed)" : "") + (!hadBom && outBom ? " (BOM added)" : ""));
        line.accept("encoding: wrote " + result.length + " bytes to " + out.getAbsolutePath());
        res.outVars.put("outputFile", out.getAbsolutePath());
        res.outVars.put("sourceEncoding", detectedFrom);
        line.accept("##VAR outputFile=" + out.getAbsolutePath());
        line.accept("##VAR sourceEncoding=" + detectedFrom);
        res.exitCode = 0;
    }

    /** Directory mode: convert every matching file from inputDir into outputDir (UTF-8), keeping names. */
    private void runEncodingBatch(java.io.File inDir, Map<String, String> params, Map<String, String> vars,
                                  StepExecutor.Result res, java.util.function.Consumer<String> line) throws Exception {
        if (!inDir.isDirectory()) { res.exitCode = 2; line.accept("encoding: input directory not found: " + inDir.getAbsolutePath()); return; }
        String outDirP = blankToNull(VarResolver.resolve(params.get("outputDir"), vars));
        if (outDirP == null) { res.exitCode = 2; line.accept("encoding: 'outputDir' is required in directory mode"); return; }
        java.io.File outDir = new java.io.File(outDirP);
        boolean recursive = "true".equalsIgnoreCase(params.get("recursive"));
        String filter = blankToNull(params.get("filter"));            // e.g. "*.csv,*.txt" or "csv,txt" (empty = all)
        boolean outBom = "true".equalsIgnoreCase(params.get("bom"));
        String fromName = blankToNull(params.get("from"));

        java.util.List<java.io.File> files = new ArrayList<java.io.File>();
        collectFiles(inDir, recursive, files);
        int converted = 0, skipped = 0, failed = 0;
        for (java.io.File f : files) {
            if (!matchesFilter(f.getName(), filter)) { skipped++; continue; }
            String rel = inDir.toPath().relativize(f.toPath()).toString().replace('\\', '/');
            java.io.File outF = new java.io.File(outDir, rel);
            if (outF.getParentFile() != null) outF.getParentFile().mkdirs();
            try {
                String enc = convertFileTo(f, outF, fromName, outBom);
                converted++;
                line.accept("encoding: " + rel + "  [" + enc + "] -> " + outF.getAbsolutePath());
            } catch (Exception e) {
                failed++;
                line.accept("encoding: FAILED " + rel + " : " + e.getMessage());
            }
        }
        line.accept("encoding (batch): converted " + converted + ", skipped " + skipped + ", failed " + failed
                + " — output dir " + outDir.getAbsolutePath());
        res.outVars.put("outputDir", outDir.getAbsolutePath());
        res.outVars.put("filesConverted", String.valueOf(converted));
        res.outVars.put("filesFailed", String.valueOf(failed));
        line.accept("##VAR outputDir=" + outDir.getAbsolutePath());
        line.accept("##VAR filesConverted=" + converted);
        res.exitCode = failed > 0 ? 1 : 0;
    }

    private static void collectFiles(java.io.File dir, boolean recursive, java.util.List<java.io.File> acc) {
        java.io.File[] kids = dir.listFiles();
        if (kids == null) return;
        for (java.io.File f : kids) {
            if (f.isDirectory()) { if (recursive) collectFiles(f, true, acc); }
            else if (!f.getName().endsWith(".tmp")) acc.add(f);
        }
    }

    /** filter: comma list of globs (*.csv) or bare extensions (csv); null/empty = match all. */
    private static boolean matchesFilter(String name, String filter) {
        if (filter == null) return true;
        for (String pat : filter.split(",")) {
            String p = pat.trim();
            if (p.isEmpty()) continue;
            if (p.indexOf('*') >= 0 || p.indexOf('?') >= 0) {
                String rx = "(?i)" + p.replace(".", "\\.").replace("*", ".*").replace("?", ".");
                if (name.matches(rx)) return true;
            } else {
                String ext = p.startsWith(".") ? p.substring(1) : p;
                if (name.toLowerCase().endsWith("." + ext.toLowerCase())) return true;
            }
        }
        return false;
    }

    /** Convert one file to UTF-8 (same detection rules as single mode) writing to `out` via tmp+move. Returns source encoding. */
    private String convertFileTo(java.io.File in, java.io.File out, String fromName, boolean outBom) throws java.io.IOException {
        byte[] bytes = java.nio.file.Files.readAllBytes(in.toPath());
        boolean hadBom = bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF;
        int off = hadBom ? 3 : 0;
        boolean hadUtf16 = bytes.length >= 2 &&
                (((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) || ((bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF));
        String text, detectedFrom;
        if (hadUtf16) {
            boolean le = (bytes[0] & 0xFF) == 0xFF;
            text = new String(bytes, 2, bytes.length - 2, le ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_16BE);
            detectedFrom = le ? "UTF-16LE (BOM)" : "UTF-16BE (BOM)";
        } else if (isValidUtf8(bytes, off, bytes.length - off)) {
            text = new String(bytes, off, bytes.length - off, StandardCharsets.UTF_8);
            detectedFrom = "UTF-8" + (hadBom ? " (BOM)" : "");
        } else {
            String srcName = fromName != null ? fromName : guessLegacyCharset(bytes);
            java.nio.charset.Charset src;
            try { src = java.nio.charset.Charset.forName(srcName); }
            catch (Exception e) { src = java.nio.charset.Charset.forName("windows-1252"); srcName = "windows-1252"; }
            text = new String(bytes, off, bytes.length - off, src);
            detectedFrom = srcName + (fromName == null ? " (assumed)" : " (declared)");
        }
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(text.length() + 8);
        if (outBom) { bos.write(0xEF); bos.write(0xBB); bos.write(0xBF); }
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        bos.write(body, 0, body.length);
        java.io.File tmp = new java.io.File(out.getParentFile(), out.getName() + ".tmp");
        java.nio.file.Files.write(tmp.toPath(), bos.toByteArray());
        java.nio.file.Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return detectedFrom;
    }

    /** Strict UTF-8 validity check over bytes[off..end). */
    private static boolean isValidUtf8(byte[] b, int off, int len) {
        int i = off, end = off + len;
        while (i < end) {
            int c = b[i] & 0xFF;
            if (c < 0x80) { i++; continue; }
            int n;            // number of continuation bytes
            if ((c & 0xE0) == 0xC0) { n = 1; if (c < 0xC2) return false; }      // reject overlong
            else if ((c & 0xF0) == 0xE0) { n = 2; }
            else if ((c & 0xF8) == 0xF0) { n = 3; if (c > 0xF4) return false; } // > U+10FFFF
            else return false;
            if (i + n >= end) return false;
            for (int k = 1; k <= n; k++) if ((b[i + k] & 0xC0) != 0x80) return false;
            i += n + 1;
        }
        return true;
    }

    /** Very small heuristic for the legacy single-byte charset; defaults to windows-1252. */
    private static String guessLegacyCharset(byte[] b) {
        // windows-1252 defines almost all 0x80-0x9F; ISO-8859-1 leaves them as controls.
        // If we see bytes in 0x80-0x9F (other than none), 1252 is the safer default for Windows feeds.
        return "windows-1252";
    }

    private void runReplace(StepDef step, Map<String, String> params, Map<String, String> vars,
                            StepExecutor.Result res, java.util.function.Consumer<String> line) throws Exception {
        String inPath = VarResolver.resolve(step.source, vars);
        char delim = (step.delimiter != null && !step.delimiter.isEmpty()) ? step.delimiter.charAt(0) : ';';
        boolean hasHeader = !"false".equalsIgnoreCase(params.get("hasHeader"));
        String outParam = blankToNull(VarResolver.resolve(params.get("outFile"), vars));

        java.io.File in = new java.io.File(inPath);
        if (!in.isFile()) { line.accept("csvreplace: input not found: " + inPath); res.exitCode = 2; return; }
        if (step.replacements == null || step.replacements.isEmpty()) { line.accept("csvreplace: no replacements configured"); res.exitCode = 0; res.outVars.put("outputFile", in.getAbsolutePath()); return; }

        // output: a new file (or overwrite). Default = <name>_replaced.<ext> next to the input.
        java.io.File out = outParam != null ? new java.io.File(outParam)
                : new java.io.File(in.getParentFile(), stripExt(in.getName()) + "_replaced" + ext(in.getName()));
        boolean inPlace = out.getAbsolutePath().equals(in.getAbsolutePath());
        java.io.File target = inPlace ? new java.io.File(in.getParentFile(), in.getName() + ".tmp") : out;
        if (target.getParentFile() != null) target.getParentFile().mkdirs();

        line.accept("csvreplace " + in.getAbsolutePath() + " -> " + out.getAbsolutePath() + "  (" + step.replacements.size() + " rule(s))");

        java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(in), StandardCharsets.UTF_8), 1 << 16);
        java.io.Writer w = new java.io.BufferedWriter(new java.io.OutputStreamWriter(new java.io.FileOutputStream(target), StandardCharsets.UTF_8), 1 << 16);
        long rows = 0, hits = 0;
        long[] perRule = new long[step.replacements.size()];
        try {
            r.mark(2); int first = r.read(); boolean hadBom = first == 0xFEFF; if (!hadBom) r.reset();
            if (hadBom) w.write('\uFEFF');

            String[] headerCols = null;
            String header = hasHeader ? r.readLine() : null;
            if (header != null) { headerCols = splitSimple(header, delim).toArray(new String[0]); w.write(header); w.write("\r\n"); }

            // resolve target column indices per rule (empty = all fields => null)
            java.util.List<int[]> ruleCols = new ArrayList<int[]>();
            for (Replacement rp : step.replacements) {
                if (rp.columns == null || rp.columns.isEmpty()) { ruleCols.add(null); continue; }
                java.util.List<Integer> idxs = new ArrayList<Integer>();
                for (String cn : rp.columns) {
                    int found = -1;
                    if (headerCols != null) for (int i = 0; i < headerCols.length; i++) if (headerCols[i].trim().equals(cn.trim())) { found = i; break; }
                    if (found >= 0) idxs.add(found);
                    else line.accept("  warning: column '" + cn + "' not found in header (rule skipped for it)");
                }
                int[] arr = new int[idxs.size()]; for (int i = 0; i < arr.length; i++) arr[i] = idxs.get(i);
                ruleCols.add(arr);
            }

            String ln;
            StringBuilder sb = new StringBuilder();
            while ((ln = r.readLine()) != null) {
                rows++;
                java.util.List<String> f = splitSimple(ln, delim);
                for (int ri = 0; ri < step.replacements.size(); ri++) {
                    Replacement rp = step.replacements.get(ri);
                    if (rp.from == null || rp.from.isEmpty()) continue;
                    int[] cols = ruleCols.get(ri);
                    if (cols == null) {
                        for (int ci = 0; ci < f.size(); ci++) {
                            String v = f.get(ci);
                            if (v.indexOf(rp.from) >= 0) { f.set(ci, v.replace(rp.from, rp.to == null ? "" : rp.to)); hits++; perRule[ri]++; }
                        }
                    } else {
                        for (int ci : cols) {
                            if (ci >= f.size()) continue;
                            String v = f.get(ci);
                            if (v.indexOf(rp.from) >= 0) { f.set(ci, v.replace(rp.from, rp.to == null ? "" : rp.to)); hits++; perRule[ri]++; }
                        }
                    }
                }
                sb.setLength(0);
                for (int ci = 0; ci < f.size(); ci++) { if (ci > 0) sb.append(delim); sb.append(f.get(ci)); }
                w.write(sb.toString()); w.write("\r\n");
            }
        } finally {
            try { r.close(); } catch (Exception ignored) {}
            try { w.flush(); w.close(); } catch (Exception ignored) {}
        }

        if (inPlace) {
            java.nio.file.Files.move(target.toPath(), in.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            out = in;
        }
        for (int ri = 0; ri < perRule.length; ri++) {
            Replacement rp = step.replacements.get(ri);
            line.accept("  rule '" + rp.from + "' -> '" + (rp.to == null ? "" : rp.to) + "'" +
                    (rp.columns == null || rp.columns.isEmpty() ? " [all]" : " " + rp.columns) + ": " + perRule[ri] + " replacement(s)");
        }
        res.outVars.put("outputFile", out.getAbsolutePath());
        res.outVars.put("rowCount", String.valueOf(rows));
        res.outVars.put("replacements", String.valueOf(hits));
        line.accept("##VAR outputFile=" + out.getAbsolutePath());
        line.accept("##VAR rowCount=" + rows);
        line.accept("##VAR replacements=" + hits);
        line.accept("csvreplace finished: " + hits + " replacement(s) over " + rows + " row(s)");
        res.exitCode = 0;
    }

    private static String ext(String name) { int d = name.lastIndexOf('.'); return d > 0 ? name.substring(d) : ".csv"; }

    // -------------------------------------------------------------- validate
    private final com.fasterxml.jackson.databind.ObjectMapper jsonMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    private static String checkLabel(String id) {
        if ("rowCount".equals(id)) return "Row count matches expected";
        if ("colCount".equals(id)) return "Column count consistent (dataschema)";
        if ("jsonSchema".equals(id)) return "Schema JSON well-formed";
        if ("noQuotes".equals(id)) return "No double quotes inside fields";
        if ("colNames".equals(id)) return "Column names match dataschema";
        if ("notNull".equals(id)) return "Non-nullable fields are valued";
        if ("businessDate".equals(id)) return "Business date valued and well-formatted";
        if ("businessDateNotBefore".equals(id)) return "Business date not before the minimum date";
        if ("businessDateNotFuture".equals(id)) return "Business date not in the future";
        if ("displayDates".equals(id)) return "Display-schema date columns well-formatted";
        return id;
    }

    @SuppressWarnings("unchecked")
    private void runValidate(StepDef step, Map<String, String> params, Map<String, String> vars,
                             StepExecutor.Result res, java.util.function.Consumer<String> line,
                             com.legalarchive.orchestrator.model.run.StepExec se, Runnable onProgress) throws Exception {
        java.util.List<String> selected = (step.validateChecks == null || step.validateChecks.isEmpty())
                ? java.util.Arrays.asList("rowCount", "colCount", "jsonSchema", "noQuotes", "colNames", "notNull", "businessDate", "businessDateNotBefore", "displayDates")
                : step.validateChecks;

        // seed checklist (PENDING) so the UI shows the sub-steps immediately
        final java.util.List<com.legalarchive.orchestrator.model.run.CheckResult> checks = new ArrayList<com.legalarchive.orchestrator.model.run.CheckResult>();
        for (String id : selected) checks.add(new com.legalarchive.orchestrator.model.run.CheckResult(id, checkLabel(id)));
        if (se != null) { se.checks = checks; if (onProgress != null) onProgress.run(); }

        final java.util.Map<String, com.legalarchive.orchestrator.model.run.CheckResult> byId =
                new java.util.HashMap<String, com.legalarchive.orchestrator.model.run.CheckResult>();
        for (com.legalarchive.orchestrator.model.run.CheckResult c : checks) byId.put(c.id, c);

        // inputs
        String csvPath = VarResolver.resolve(step.source, vars);
        char delim = (step.delimiter != null && !step.delimiter.isEmpty()) ? step.delimiter.charAt(0) : ';';
        boolean hasHeader = !"false".equalsIgnoreCase(params.get("hasHeader"));
        String dataschemaPath = blankToNull(params.get("dataschema"));
        String displayschemaPath = blankToNull(params.get("displayschema"));
        String bizCol = blankToNull(params.get("businessDateColumn"));
        if (bizCol == null) bizCol = blankToNull(vars.get("recordBusinessDate"));   // default: per-feed business-date column
        String dateFormat = blankToNull(params.get("dateFormat"));
        Long expected = null;
        try { if (params.get("expectedRows") != null) expected = Long.parseLong(params.get("expectedRows").trim()); } catch (Exception ignore) {}

        line.accept("validate " + csvPath + "  (delimiter '" + delim + "', header=" + hasHeader + ")");

        java.util.function.BiConsumer<String, String[]> set = new java.util.function.BiConsumer<String, String[]>() {
            public void accept(String id, String[] sd) {
                com.legalarchive.orchestrator.model.run.CheckResult c = byId.get(id);
                if (c == null) return;
                c.status = sd[0]; c.detail = sd[1];
                line.accept("[check] " + id + " -> " + sd[0] + (sd[1] != null ? ("  " + sd[1]) : ""));
                if (onProgress != null) onProgress.run();
            }
        };
        boolean sel_rowCount = byId.containsKey("rowCount");
        boolean sel_colCount = byId.containsKey("colCount");
        boolean sel_json = byId.containsKey("jsonSchema");
        boolean sel_noQuotes = byId.containsKey("noQuotes");
        boolean sel_colNames = byId.containsKey("colNames");
        boolean sel_notNull = byId.containsKey("notNull");
        boolean sel_biz = byId.containsKey("businessDate");
        boolean sel_bizMin = byId.containsKey("businessDateNotBefore");
        // ON FOR EVERY FEED, including ones written and run before this check existed. The other
        // checks are a positive list, so a feed whose XML already carries checks="..." would never
        // pick up a new id - and rewriting 144 definitions to add one is not a deploy anybody should
        // do. This one is therefore on unless the step explicitly turns it OFF with the param
        // businessDateNotFuture=false, which is what the designer's checkbox writes when unticked.
        // Ticking the box removes the param rather than adding the id, so the two spellings cannot
        // disagree; a step that still lists the id in checks="..." is simply already consistent.
        boolean sel_bizFut = !"false".equalsIgnoreCase(nz(params.get("businessDateNotFuture")).trim());
        boolean sel_disp = byId.containsKey("displayDates");

        // --- parse schemas (also serves the jsonSchema well-formed check) ---
        java.util.List<Map<String, Object>> dataschema = null;
        java.util.List<Map<String, Object>> displayschema = null;
        String jsonErr = null;
        if (dataschemaPath != null) {
            try { dataschema = (java.util.List<Map<String, Object>>) (java.util.List<?>) jsonMapper.readValue(new java.io.File(dataschemaPath), java.util.List.class); }
            catch (Exception e) { jsonErr = "dataschema: " + e.getMessage(); }
        }
        if (displayschemaPath != null) {
            try { displayschema = (java.util.List<Map<String, Object>>) (java.util.List<?>) jsonMapper.readValue(new java.io.File(displayschemaPath), java.util.List.class); }
            catch (Exception e) { jsonErr = (jsonErr == null ? "" : jsonErr + "; ") + "displayschema: " + e.getMessage(); }
        }
        if (sel_json) {
            if (dataschemaPath == null && displayschemaPath == null) set.accept("jsonSchema", new String[]{"SKIP", "no schema provided"});
            else if (jsonErr != null) set.accept("jsonSchema", new String[]{"FAIL", jsonErr});
            else set.accept("jsonSchema", new String[]{"PASS", "schema(s) parsed"});
        }

        // dataschema-derived metadata
        java.util.List<String> schemaNames = new ArrayList<String>();
        java.util.List<Boolean> schemaNotNull = new ArrayList<Boolean>();
        if (dataschema != null) for (Map<String, Object> c : dataschema) {
            schemaNames.add(String.valueOf(c.get("name")));
            Object nu = c.get("nullable");
            schemaNotNull.add(Boolean.FALSE.equals(nu) || "false".equalsIgnoreCase(String.valueOf(nu)));
        }

        // open file
        java.io.File csv = new java.io.File(csvPath);
        if (!csv.isFile()) {
            String[] miss = new String[]{"FAIL", "CSV file not found: " + csvPath};
            for (String id : selected) if (!"jsonSchema".equals(id)) set.accept(id, miss);
            res.exitCode = 2;
            return;
        }

        // violation report: one CSV row per violation (CHECK;LINE;COLUMN;DETAIL), streamed with a cap
        final java.io.File reportFile = new java.io.File(csv.getParentFile(),
                stripExt(csv.getName()) + "_validation_report.csv");
        try { java.nio.file.Files.deleteIfExists(reportFile.toPath()); } catch (Exception ignored) {}
        class Reporter {
            static final long CAP = 100000;
            java.io.Writer w; long rows = 0; boolean trunc = false;
            void add(String check, long ln, String col, String det) {
                if (trunc) return;
                try {
                    if (rows >= CAP) { trunc = true; return; }
                    if (w == null) {
                        w = new java.io.BufferedWriter(new java.io.OutputStreamWriter(
                                new java.io.FileOutputStream(reportFile), StandardCharsets.UTF_8), 1 << 16);
                        w.write('\uFEFF');
                        w.write("CHECK;LINE;COLUMN;DETAIL\r\n");
                    }
                    w.write(check + ";" + ln + ";" + repField(col) + ";" + repField(det) + "\r\n");
                    rows++;
                } catch (Exception ignored) {}
            }
            void close() { if (w != null) try { w.flush(); w.close(); } catch (Exception ignored) {} }
        }
        final Reporter rep = new Reporter();

        java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(csv), StandardCharsets.UTF_8), 1 << 16);
        try {
            r.mark(4); if (r.read() != 0xFEFF) r.reset();
            String header = hasHeader ? r.readLine() : null;
            java.util.List<String> headerCols = header != null ? parseCsv(header, delim) : null;

            // colNames check (header vs dataschema)
            if (sel_colNames) {
                if (dataschema == null) set.accept("colNames", new String[]{"SKIP", "dataschema not provided"});
                else if (!hasHeader || headerCols == null) set.accept("colNames", new String[]{"SKIP", "file has no header"});
                else {
                    java.util.List<String> diff = new ArrayList<String>();
                    int n = Math.max(headerCols.size(), schemaNames.size());
                    for (int i = 0; i < n; i++) {
                        String h = i < headerCols.size() ? headerCols.get(i).trim() : "(missing)";
                        String sName = i < schemaNames.size() ? schemaNames.get(i) : "(missing)";
                        if (!h.equals(sName)) diff.add("col " + (i + 1) + ": '" + h + "' != '" + sName + "'");
                    }
                    if (diff.isEmpty()) set.accept("colNames", new String[]{"PASS", schemaNames.size() + " columns match"});
                    else set.accept("colNames", new String[]{"FAIL", join(diff, 5)});
                }
            }

            // column index map for value checks
            java.util.Map<String, Integer> idx = new java.util.HashMap<String, Integer>();
            java.util.List<String> idxNames = (headerCols != null) ? headerCols : schemaNames;
            for (int i = 0; i < idxNames.size(); i++) idx.put(idxNames.get(i).trim(), i);

            int expectedCols = dataschema != null ? schemaNames.size() : -1;   // colCount is defined vs dataschema; SKIP without it

            // non-nullable column indices
            java.util.List<Integer> nnIdx = new ArrayList<Integer>();
            if (sel_notNull && dataschema != null) for (int i = 0; i < schemaNames.size(); i++) if (schemaNotNull.get(i)) {
                Integer ci = idx.get(schemaNames.get(i)); if (ci != null) nnIdx.add(ci);
            }
            // business date col index + regex
            Integer bizIdx = ((sel_biz || sel_bizMin || sel_bizFut) && bizCol != null) ? idx.get(bizCol) : null;
            java.util.regex.Pattern datePat = dateFormat != null ? java.util.regex.Pattern.compile(fmtToRegex(dateFormat)) : null;
            // minimum business date: only when businessDateMin is provided (otherwise the check is skipped)
            java.time.format.DateTimeFormatter bizFmt = null;
            java.time.LocalDate bizMinDate = null;
            String bizMinSpec = null, bizMinErr = null;
            boolean bizMinSet = false;
            if (sel_bizMin) {
                String minRaw = blankToNull(params.get("businessDateMin"));
                bizMinSet = (minRaw != null);
                if (bizMinSet && bizIdx != null) {
                    if (dateFormat == null) {
                        bizMinErr = "dateFormat not provided";
                    } else {
                        bizFmt = maskFormatter(dateFormat);
                        if (bizFmt == null) bizMinErr = maskProblem(dateFormat);
                        if (bizFmt != null) {
                            try { bizMinDate = java.time.LocalDate.parse(minRaw.trim(), bizFmt); bizMinSpec = minRaw.trim(); }
                            catch (Exception e) { bizMinErr = "invalid businessDateMin '" + minRaw.trim() + "' for format " + dateFormat; }
                        }
                    }
                }
            }
            // upper bound: a business date must not be in the future. Unlike the minimum this needs no
            // configuration - the bound defaults to TODAY - so the check is usable on any feed that
            // declares a business date column and a date format. businessDateMax overrides it when a
            // feed legitimately carries forward-dated records.
            java.time.format.DateTimeFormatter bizFutFmt = null;
            java.time.LocalDate bizMaxDate = null;
            String bizMaxSpec = null, bizFutErr = null;
            if (sel_bizFut && bizIdx != null) {
                if (dateFormat == null) {
                    bizFutErr = "dateFormat not provided";
                } else {
                    bizFutFmt = maskFormatter(dateFormat);
                    if (bizFutFmt == null) bizFutErr = maskProblem(dateFormat);
                    if (bizFutFmt != null) {
                        String maxRaw = blankToNull(params.get("businessDateMax"));
                        if (maxRaw == null) {
                            bizMaxDate = java.time.LocalDate.now();
                            // the label is cosmetic: rendering it must never be able to fail the step
                            bizMaxSpec = "today (" + maskFormat(bizMaxDate, bizFutFmt) + ")";
                        } else {
                            try { bizMaxDate = java.time.LocalDate.parse(maxRaw.trim(), bizFutFmt); bizMaxSpec = maxRaw.trim(); }
                            catch (Exception e) { bizFutErr = "invalid businessDateMax '" + maxRaw.trim() + "' for format " + dateFormat; }
                        }
                    }
                }
            }
            // display-schema date columns
            java.util.List<Integer> dateIdx = new ArrayList<Integer>();
            java.util.List<String> dateColNames = new ArrayList<String>();
            if (sel_disp && displayschema != null) for (Map<String, Object> c : displayschema) {
                if ("date".equalsIgnoreCase(String.valueOf(c.get("DataType")))) {
                    String cn = String.valueOf(c.get("ColumnName"));
                    Integer ci = idx.get(cn);
                    if (ci != null) { dateIdx.add(ci); dateColNames.add(cn); }
                }
            }

            // single streaming pass
            long rows = 0, colViol = 0, quoteViol = 0, bizViol = 0, bizMinViol = 0, bizFutViol = 0;
            java.util.List<String> colViolLines = new ArrayList<String>();
            java.util.List<String> quoteViolLines = new ArrayList<String>();
            java.util.List<String> bizViolLines = new ArrayList<String>();
            java.util.List<String> bizMinViolLines = new ArrayList<String>();
            java.util.List<String> bizFutViolLines = new ArrayList<String>();
            long[] nnViol = new long[nnIdx.size()];
            long[] dateViol = new long[dateIdx.size()];
            String line2;
            long lineNo = hasHeader ? 1 : 0;
            while ((line2 = r.readLine()) != null) {
                lineNo++; rows++;
                java.util.List<String> f = parseCsv(line2, delim);
                if (sel_noQuotes) {
                    String firstCol = null;
                    for (int ci = 0; ci < f.size(); ci++) {
                        if (f.get(ci).indexOf('"') >= 0) {     // a quote left AFTER RFC parsing = embedded quote inside the field
                            String cn = ci < idxNames.size() ? idxNames.get(ci).trim() : ("col" + (ci + 1));
                            if (firstCol == null) firstCol = cn;
                            rep.add("noQuotes", lineNo, cn, snippet60(f.get(ci)));
                        }
                    }
                    if (firstCol != null) {
                        quoteViol++;
                        if (quoteViolLines.size() < 5) quoteViolLines.add("line " + lineNo + " [" + firstCol + "]");
                    }
                }
                if (sel_colCount && expectedCols >= 0 && f.size() != expectedCols) {
                    colViol++;
                    rep.add("colCount", lineNo, "", "found " + f.size() + ", expected " + expectedCols);
                    if (colViolLines.size() < 5) colViolLines.add("line " + lineNo + " has " + f.size());
                }
                if (sel_notNull) for (int k = 0; k < nnIdx.size(); k++) {
                    int ci = nnIdx.get(k);
                    if (ci >= f.size() || f.get(ci).trim().isEmpty()) {
                        nnViol[k]++;
                        rep.add("notNull", lineNo, ci < idxNames.size() ? idxNames.get(ci).trim() : ("col" + (ci + 1)), "empty");
                    }
                }
                if (bizIdx != null) {
                    String v = bizIdx < f.size() ? f.get(bizIdx).trim() : "";
                    boolean bad = v.isEmpty() || (datePat != null && !datePat.matcher(v).matches());
                    if (bad) {
                        bizViol++;
                        rep.add("businessDate", lineNo, bizCol, v.isEmpty() ? "empty" : ("malformed: " + snippet60(v)));
                        if (bizViolLines.size() < 5) bizViolLines.add("line " + lineNo + " ='" + v + "'");
                    } else {
                        if (bizMinDate != null) {
                            try {
                                java.time.LocalDate d = java.time.LocalDate.parse(v, bizFmt);
                                if (!d.isAfter(bizMinDate)) {   // violation when value <= min; only strictly greater passes
                                    bizMinViol++;
                                    rep.add("businessDateNotBefore", lineNo, bizCol, "on or before " + bizMinSpec + ": " + snippet60(v));
                                    if (bizMinViolLines.size() < 5) bizMinViolLines.add("line " + lineNo + " ='" + v + "'");
                                }
                            } catch (Exception ignore) { /* unparseable despite regex: handled by businessDate check */ }
                        }
                        if (bizMaxDate != null) {
                            try {
                                java.time.LocalDate d = java.time.LocalDate.parse(v, bizFutFmt);
                                if (d.isAfter(bizMaxDate)) {    // the bound itself is allowed: only strictly later fails
                                    bizFutViol++;
                                    rep.add("businessDateNotFuture", lineNo, bizCol, "after " + bizMaxSpec + ": " + snippet60(v));
                                    if (bizFutViolLines.size() < 5) bizFutViolLines.add("line " + lineNo + " ='" + v + "'");
                                }
                            } catch (Exception ignore) { /* unparseable despite regex: handled by businessDate check */ }
                        }
                    }
                }
                if (!dateIdx.isEmpty() && datePat != null) for (int k = 0; k < dateIdx.size(); k++) {
                    int ci = dateIdx.get(k); String v = ci < f.size() ? f.get(ci).trim() : "";
                    if (!v.isEmpty() && !datePat.matcher(v).matches()) {
                        dateViol[k]++;
                        rep.add("displayDates", lineNo, dateColNames.get(k), "malformed: " + snippet60(v));
                    }
                }
            }

            rep.close();
            String repNote = rep.rows > 0
                    ? (" — full report: " + reportFile.getName() + (rep.trunc ? " (truncated at " + Reporter.CAP + " rows)" : ""))
                    : "";
            if (rep.rows > 0) {
                line.accept("violation report (" + rep.rows + " row(s)" + (rep.trunc ? ", truncated" : "") + "): " + reportFile.getAbsolutePath());
                res.outVars.put("validationReport", reportFile.getAbsolutePath());
            }

            if (sel_rowCount) {
                if (expected == null) set.accept("rowCount", new String[]{"SKIP", "expectedRows not provided (actual " + rows + ")"});
                else if (rows == expected) set.accept("rowCount", new String[]{"PASS", rows + " data rows"});
                else set.accept("rowCount", new String[]{"FAIL", "expected " + expected + ", found " + rows});
            }
            if (sel_colCount) {
                if (expectedCols < 0) set.accept("colCount", new String[]{"SKIP", "dataschema not provided"});
                else if (colViol == 0) set.accept("colCount", new String[]{"PASS", "all rows have " + expectedCols + " columns"});
                else set.accept("colCount", new String[]{"FAIL", colViol + " row(s) with wrong column count (" + join(colViolLines, 5) + ")" + repNote});
            }
            if (sel_noQuotes) {
                if (quoteViol == 0) set.accept("noQuotes", new String[]{"PASS", "no embedded double quotes inside fields"});
                else set.accept("noQuotes", new String[]{"FAIL", quoteViol + " row(s) have a double quote inside a field (" + join(quoteViolLines, 5) + ")" + repNote});
            }
            if (sel_notNull) {
                if (dataschema == null) set.accept("notNull", new String[]{"SKIP", "dataschema not provided"});
                else {
                    java.util.List<String> bad = new ArrayList<String>();
                    for (int k = 0; k < nnIdx.size(); k++) if (nnViol[k] > 0) bad.add(idxNames.get(nnIdx.get(k)) + ": " + nnViol[k] + " empty");
                    if (bad.isEmpty()) set.accept("notNull", new String[]{"PASS", nnIdx.size() + " non-nullable column(s) ok"});
                    else set.accept("notNull", new String[]{"FAIL", join(bad, 6) + repNote});
                }
            }
            if (sel_biz) {
                if (bizCol == null) set.accept("businessDate", new String[]{"SKIP", "businessDateColumn not provided"});
                else if (bizIdx == null) set.accept("businessDate", new String[]{"FAIL", "column '" + bizCol + "' not found"});
                else if (bizViol == 0) set.accept("businessDate", new String[]{"PASS", "all rows valued" + (dateFormat != null ? (" and matching " + dateFormat) : "")});
                else set.accept("businessDate", new String[]{"FAIL", bizViol + " row(s) empty or malformed (" + join(bizViolLines, 5) + ")" + repNote});
            }
            if (sel_bizMin) {
                if (!bizMinSet) set.accept("businessDateNotBefore", new String[]{"SKIP", "businessDateMin not set"});
                else if (bizCol == null) set.accept("businessDateNotBefore", new String[]{"SKIP", "businessDateColumn not provided"});
                else if (bizIdx == null) set.accept("businessDateNotBefore", new String[]{"FAIL", "column '" + bizCol + "' not found"});
                else if (bizMinErr != null) set.accept("businessDateNotBefore", new String[]{"FAIL", bizMinErr});
                else if (bizMinViol == 0) set.accept("businessDateNotBefore", new String[]{"PASS", "all rows after " + bizMinSpec});
                else set.accept("businessDateNotBefore", new String[]{"FAIL", bizMinViol + " row(s) on or before " + bizMinSpec + " (" + join(bizMinViolLines, 5) + ")" + repNote});
            }
            if (sel_bizFut) {
                if (bizCol == null) set.accept("businessDateNotFuture", new String[]{"SKIP", "businessDateColumn not provided"});
                else if (bizIdx == null) set.accept("businessDateNotFuture", new String[]{"FAIL", "column '" + bizCol + "' not found"});
                else if (bizFutErr != null) set.accept("businessDateNotFuture", new String[]{"SKIP", bizFutErr});
                else if (bizFutViol == 0) set.accept("businessDateNotFuture", new String[]{"PASS", "no row after " + bizMaxSpec});
                else set.accept("businessDateNotFuture", new String[]{"FAIL", bizFutViol + " row(s) after " + bizMaxSpec + " (" + join(bizFutViolLines, 5) + ")" + repNote});
            }
            if (sel_disp) {
                if (displayschema == null) set.accept("displayDates", new String[]{"SKIP", "displayschema not provided"});
                else if (dateFormat == null) set.accept("displayDates", new String[]{"SKIP", "dateFormat not provided"});
                else if (dateIdx.isEmpty()) set.accept("displayDates", new String[]{"PASS", "no date columns in displayschema"});
                else {
                    java.util.List<String> bad = new ArrayList<String>();
                    for (int k = 0; k < dateIdx.size(); k++) if (dateViol[k] > 0) bad.add(dateColNames.get(k) + ": " + dateViol[k] + " malformed");
                    if (bad.isEmpty()) set.accept("displayDates", new String[]{"PASS", dateIdx.size() + " date column(s) ok"});
                    else set.accept("displayDates", new String[]{"FAIL", join(bad, 6) + repNote});
                }
            }

            int failed = 0, passed = 0;
            for (com.legalarchive.orchestrator.model.run.CheckResult c : checks) {
                if ("FAIL".equals(c.status)) failed++; else if ("PASS".equals(c.status)) passed++;
            }
            res.outVars.put("checksTotal", String.valueOf(checks.size()));
            res.outVars.put("checksPassed", String.valueOf(passed));
            res.outVars.put("checksFailed", String.valueOf(failed));
            for (Map.Entry<String, String> e : res.outVars.entrySet()) line.accept("##VAR " + e.getKey() + "=" + e.getValue());
            res.exitCode = failed > 0 ? 1 : 0;
            line.accept("validation finished: " + passed + " passed, " + failed + " failed" + (failed > 0 ? " — STEP FAILED" : ""));
        } finally {
            r.close();
        }
    }

    private static String nz(String v) { return v == null ? "" : v; }

    private static String blankToNull(String s) { return (s == null || s.trim().isEmpty()) ? null : s.trim(); }

    /** Build a SELECT column list from dataschema column names. quote=double wraps in "..."; default plain. */
    static String buildColumnList(java.util.List<String> names, String quote) {
        boolean dbl = "double".equalsIgnoreCase(quote);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            String n = names.get(i) == null ? "" : names.get(i).trim();
            if (n.isEmpty()) continue;
            if (sb.length() > 0) sb.append(", ");
            if (dbl) sb.append('"').append(n.replace("\"", "\"\"")).append('"');
            else sb.append(n);
        }
        return sb.toString();
    }

    /** Read column names from a dataschema JSON: array of {name|ColumnName} or {columns:[...]} or ["A","B"]. */
    private java.util.List<String> readSchemaColumnNames(java.io.File f) throws Exception {
        java.util.List<String> out = new java.util.ArrayList<String>();
        Object root = jsonMapper.readValue(f, Object.class);
        java.util.List<?> cols = null;
        if (root instanceof java.util.List) cols = (java.util.List<?>) root;
        else if (root instanceof java.util.Map) {
            Object c = ((java.util.Map<?, ?>) root).get("columns");
            if (c instanceof java.util.List) cols = (java.util.List<?>) c;
        }
        if (cols == null) return out;
        for (Object o : cols) {
            if (o instanceof java.util.Map) {
                java.util.Map<?, ?> m = (java.util.Map<?, ?>) o;
                Object nm = m.get("name"); if (nm == null) nm = m.get("ColumnName"); if (nm == null) nm = m.get("COLUMN_NAME");
                if (nm != null) out.add(String.valueOf(nm).trim());
            } else if (o instanceof String) {
                out.add(((String) o).trim());
            }
        }
        return out;
    }
    private static String stripExt(String name) { int d = name.lastIndexOf('.'); return d > 0 ? name.substring(0, d) : name; }
    private static String snippet60(String v) { v = v == null ? "" : v; return v.length() > 60 ? (v.substring(0, 60) + "…") : v; }
    /** RFC-quote a field for the violation report (it may itself contain quotes/semicolons). */
    private static String repField(String v) {
        if (v == null) return "";
        if (v.indexOf(';') >= 0 || v.indexOf('"') >= 0) return '"' + v.replace("\"", "\"\"") + '"';
        return v;
    }
    /** RFC-4180 output quoting: quote only when the value contains the delimiter, a quote or a newline. */
    private static String rfcField(String v, char delim) {
        if (v == null) return "";
        if (v.indexOf('"') >= 0 || v.indexOf(delim) >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0)
            return '"' + v.replace("\"", "\"\"") + '"';
        return v;
    }

    /** True when an anonymize/mask step must NOT transform data: explicit step param passthrough=true,
     *  or the whole workflow runs in the PROD environment (run var __prod=true). */
    private static boolean passthroughRequested(Map<String, String> params, Map<String, String> vars) {
        String p = params.get("passthrough");
        boolean stepFlag = p != null && ("true".equalsIgnoreCase(p) || "passthrough".equalsIgnoreCase(p) || "1".equals(p.trim()));
        boolean prod = "true".equalsIgnoreCase(vars.get("__prod"));
        return stepFlag || prod;
    }

    /** Passthrough copy: the input file is copied verbatim to the step's output path so the next
     *  step receives it unchanged. Returns true (the caller must then return). */
    private boolean maskPassthrough(java.io.File in, java.io.File out, StepExecutor.Result res,
                                    java.util.function.Consumer<String> line, String why) throws Exception {
        if (out == null) { line.accept("passthrough: cannot determine output file (set the output file / outFile)"); res.exitCode = 2; return true; }
        if (in == null || !in.isFile()) { line.accept("passthrough: input file not found: " + (in == null ? "" : in.getPath())); res.exitCode = 2; return true; }
        if (out.getParentFile() != null) out.getParentFile().mkdirs();
        Files.copy(in.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
        res.outVars.put("outputFile", out.getAbsolutePath());
        res.outVars.put("passthrough", "true");
        line.accept("PASSTHROUGH (" + why + "): copied " + in.getName() + " -> " + out.getAbsolutePath() + " unchanged (no anonymisation/masking applied)");
        line.accept("##VAR outputFile=" + out.getAbsolutePath());
        res.exitCode = 0;
        return true;
    }
    private static String join(java.util.List<String> l, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size() && i < max; i++) { if (i > 0) sb.append("; "); sb.append(l.get(i)); }
        if (l.size() > max) sb.append("; …");
        return sb.toString();
    }
    /** RFC-style quote-aware split: wrapping double quotes are removed and "" -> " inside fields.
        So header names and values are compared without their surrounding quotes, and only
        EMBEDDED quotes survive in a field (used by the noQuotes check). */
    private static boolean oddQuotes(CharSequence s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '"') n++;
        return (n & 1) == 1;                       // RFC "" escapes keep the parity even
    }

    /** Reads one LOGICAL csv record. While the double quotes on the record are unbalanced the record
     *  continues on the next physical line, so that line break is INSIDE a quoted field: the lines are
     *  joined and the break replaced by nlRepl. Returns null at EOF. */
    private static String readCsvRecord(java.io.BufferedReader r, String nlRepl, long[] joined) throws java.io.IOException {
        String ln = r.readLine();
        if (ln == null) return null;
        StringBuilder sb = new StringBuilder(ln);
        int guard = 0;
        while (oddQuotes(sb) && guard++ < 5000) {
            String more = r.readLine();
            if (more == null) break;               // unterminated quote at EOF: keep what we have
            sb.append(nlRepl).append(more);
            joined[0]++;
        }
        return sb.toString();
    }

    private static java.util.List<String> parseCsv(String line, char delim) {
        java.util.List<String> out = new ArrayList<String>();
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

    /** naive split by delimiter. */
    private static java.util.List<String> splitSimple(String line, char delim) {
        java.util.List<String> out = new ArrayList<String>();
        int start = 0;
        for (int i = 0; i < line.length(); i++) if (line.charAt(i) == delim) { out.add(line.substring(start, i)); start = i + 1; }
        out.add(line.substring(start));
        return out;
    }
    /** Convert a token date format (YYYY/MM/DD, YYYYMMDD, DD-MM-YYYY, HH:mm:ss…) to an anchored regex. */
    /** java.time pattern letters that mean the same thing in both dialects: passed through untouched. */
    private static final String JT_PASSTHROUGH = "GyuMLdQqEecwWFaHhKkmsSAnNVzOXxZpP";

    /**
     * Translates a date MASK into a java.time pattern, tolerating BOTH dialects, because the mask is
     * a per-feed value - often a workflow variable such as ${recordBusinessDateFormat} - and nothing
     * guarantees every feed writes it the same way.
     *
     * Only two letters are rewritten, and only because the two dialects genuinely disagree on them:
     * {@code Y} (this product: the year; java.time: the WEEK-BASED year) and {@code D} (this product:
     * the day of the month; java.time: the day of the YEAR). Feeding a mask straight to ofPattern is
     * what produced "Field DayOfYear cannot be printed as the value 222 exceeds the maximum print
     * width of 2" in the field. Nobody writes a mask meaning to ask for a week-based year, so
     * rewriting those two is safe.
     *
     * Everything else that is already a valid java.time letter passes through unchanged, so a feed
     * that writes {@code yyyy-MM-dd} keeps working exactly as it did - it must, since that form used
     * to reach ofPattern intact. Letters are consumed in RUNS, so {@code MMM} stays {@code MMM}
     * rather than being split into {@code MM} plus a stray literal. Quoted sections are preserved
     * verbatim, and any other letter is quoted so it cannot be read as a pattern letter.
     */
    static String fmtToJavaPattern(String fmt) {
        if (fmt == null) return null;
        StringBuilder sb = new StringBuilder();
        int i = 0, n = fmt.length();
        while (i < n) {
            char c = fmt.charAt(i);
            if (c == '\'') {                                   // an already-quoted literal: copy as is
                int j = i + 1;
                sb.append('\'');
                while (j < n) {
                    sb.append(fmt.charAt(j));
                    if (fmt.charAt(j) == '\'') { j++; break; }
                    j++;
                }
                if (j > n) sb.append('\'');
                i = j;
                continue;
            }
            if (!Character.isLetter(c)) { sb.append(c); i++; continue; }
            int j = i;
            while (j < n && fmt.charAt(j) == c) j++;            // one run of the same letter
            int len = j - i;
            char out = c;
            if (c == 'Y') out = 'u';
            else if (c == 'D') out = 'd';
            else if (JT_PASSTHROUGH.indexOf(c) < 0) {           // not a pattern letter at all: literal
                sb.append('\'');
                for (int k = 0; k < len; k++) sb.append(c);
                sb.append('\'');
                i = j;
                continue;
            }
            for (int k = 0; k < len; k++) sb.append(out);
            i = j;
        }
        return sb.toString();
    }

    /** The mask compiled for java.time, or null when it cannot be. Never throws. */
    private static java.time.format.DateTimeFormatter maskFormatter(String mask) {
        try { return java.time.format.DateTimeFormatter.ofPattern(fmtToJavaPattern(mask)); }
        catch (Exception e) { return null; }
    }

    /** Why a mask could not be compiled, in terms the author can act on. */
    static String maskProblem(String mask) {
        String m = mask == null ? "" : mask;
        if (m.indexOf("${") >= 0) {
            return "dateFormat is still '" + m + "': the variable it refers to is not defined for this feed";
        }
        return "dateFormat '" + m + "' cannot be read as a date mask";
    }

    /** Renders a date with the feed's mask; falls back to ISO rather than letting a LABEL fail a step. */
    static String maskFormat(java.time.LocalDate d, java.time.format.DateTimeFormatter fmt) {
        if (d == null) return "";
        if (fmt != null) { try { return d.format(fmt); } catch (Exception ignored) { } }
        return d.toString();
    }

    private static String fmtToRegex(String fmt) {
        StringBuilder sb = new StringBuilder("^");
        int i = 0;
        while (i < fmt.length()) {
            if (fmt.startsWith("YYYY", i)) { sb.append("\\d{4}"); i += 4; }
            else if (fmt.startsWith("YY", i)) { sb.append("\\d{2}"); i += 2; }
            else if (fmt.startsWith("MM", i)) { sb.append("\\d{2}"); i += 2; }
            else if (fmt.startsWith("DD", i)) { sb.append("\\d{2}"); i += 2; }
            else if (fmt.startsWith("HH", i)) { sb.append("\\d{2}"); i += 2; }
            else if (fmt.startsWith("mm", i)) { sb.append("\\d{2}"); i += 2; }
            else if (fmt.startsWith("ss", i)) { sb.append("\\d{2}"); i += 2; }
            else { char c = fmt.charAt(i); if ("\\.[]{}()*+-?^$|".indexOf(c) >= 0) sb.append('\\'); sb.append(c); i++; }
        }
        sb.append("$");
        return sb.toString();
    }

    private void safeLine(BufferedWriter log, String s) {
        if (log == null) return;
        try { log.write(LocalDateTime.now().format(TS) + "  " + s); log.newLine(); log.flush(); } catch (Exception ignored) {}
    }
}
