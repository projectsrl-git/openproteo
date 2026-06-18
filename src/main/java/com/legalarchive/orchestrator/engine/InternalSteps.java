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
                runSql(step, resolvedParams, vars, res, line);
            } else if ("ifscopy".equals(kind)) {
                runIfsCopy(step, vars, res, line);
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
            } else {
                line.accept("unknown internal step kind: " + kind);
                res.exitCode = -996;
            }
        } catch (Exception e) {
            res.exitCode = res.exitCode == 0 ? 1 : res.exitCode;
            res.lastLines = e.getMessage();
            safeLine(log, "ERROR: " + e.getMessage());
        } finally {
            if (log != null) try { log.close(); } catch (Exception ignored) {}
        }
        return res;
    }

    // ----------------------------------------------------------------- sql
    private void runSql(StepDef step, Map<String, String> params, Map<String, String> vars,
                        StepExecutor.Result res, java.util.function.Consumer<String> line) throws Exception {
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
            SqlSupport.ExportResult er = sql.exportCsv(d, query, out, delim, true, maxRows, maxBytes, trim);
            res.outVars.put("rowCount", String.valueOf(er.rows));
            res.outVars.put("csvParts", String.valueOf(er.parts));
            res.outVars.put("csvFile", er.files.isEmpty() ? out.getAbsolutePath() : er.files.get(0));
            res.outVars.put("csvFiles", String.join(step.delimiter == null ? ";" : step.delimiter, er.files));
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

    // ------------------------------------------------------------- ifscopy
    private void runIfsCopy(StepDef step, Map<String, String> vars,
                            StepExecutor.Result res, java.util.function.Consumer<String> line) throws Exception {
        DataSourceDef d = dataSources.get(step.datasource);
        if (d == null) { line.accept("datasource not found: " + step.datasource); res.exitCode = 2; return; }
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

        long dataRows = 0, cells = 0, removed = 0; int columns = 0;
        boolean[] targeted = null;
        java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(in), "UTF-8"));
        java.io.BufferedWriter w = new java.io.BufferedWriter(new java.io.OutputStreamWriter(new java.io.FileOutputStream(out), "UTF-8"));
        try {
            if (bom) w.write('\uFEFF');
            String first = r.readLine();
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

            String ln = hasHeader ? r.readLine() : first;
            while (ln != null) {
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
                    cells++;
                    lb.append(quoteIfNeeded ? rfcField(v, delim) : v);
                }
                w.write(lb.toString()); w.write("\r\n");
                dataRows++;
                ln = r.readLine();
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
        line.accept("dequote " + in.getName() + " -> " + out.getName() + "  rows=" + dataRows
                + " cols=" + columns + " quotesRemoved=" + removed + " delim='" + delim + "'");
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

    /** Evaluate "A + B" or "A - B" as integers when both sides are integers; otherwise return as-is. */
    private String evalArithmetic(String expr) {
        if (expr == null) return "";
        String s = expr.trim();
        for (String op : new String[]{"+", "-"}) {
            int idx = s.indexOf(' ' + op + ' ');
            if (idx > 0) {
                String a = s.substring(0, idx).trim();
                String b = s.substring(idx + 3).trim();
                try {
                    long la = Long.parseLong(a), lb = Long.parseLong(b);
                    return String.valueOf("+".equals(op) ? la + lb : la - lb);
                } catch (NumberFormatException ignored) {
                    return s;
                }
            }
        }
        return s;
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
                    if (w != null) { w.close(); w = null; }
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
            w.flush();
        } finally {
            if (w != null) try { w.close(); } catch (Exception ignore) {}
            if (r != null) try { r.close(); } catch (Exception ignore) {}
        }

        res.outVars.put("rowCount", String.valueOf(dataRows));
        res.outVars.put("csvParts", String.valueOf(files.size()));
        res.outVars.put("csvFile", files.isEmpty() ? base.getAbsolutePath() : files.get(0));
        res.outVars.put("csvFiles", String.join(sep, files));
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
        if ("displayDates".equals(id)) return "Display-schema date columns well-formatted";
        return id;
    }

    @SuppressWarnings("unchecked")
    private void runValidate(StepDef step, Map<String, String> params, Map<String, String> vars,
                             StepExecutor.Result res, java.util.function.Consumer<String> line,
                             com.legalarchive.orchestrator.model.run.StepExec se, Runnable onProgress) throws Exception {
        java.util.List<String> selected = (step.validateChecks == null || step.validateChecks.isEmpty())
                ? java.util.Arrays.asList("rowCount", "colCount", "jsonSchema", "noQuotes", "colNames", "notNull", "businessDate", "displayDates")
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
            Integer bizIdx = (sel_biz && bizCol != null) ? idx.get(bizCol) : null;
            java.util.regex.Pattern datePat = dateFormat != null ? java.util.regex.Pattern.compile(fmtToRegex(dateFormat)) : null;
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
            long rows = 0, colViol = 0, quoteViol = 0, bizViol = 0;
            java.util.List<String> colViolLines = new ArrayList<String>();
            java.util.List<String> quoteViolLines = new ArrayList<String>();
            java.util.List<String> bizViolLines = new ArrayList<String>();
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
    private static String join(java.util.List<String> l, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size() && i < max; i++) { if (i > 0) sb.append("; "); sb.append(l.get(i)); }
        if (l.size() > max) sb.append("; …");
        return sb.toString();
    }
    /** RFC-style quote-aware split: wrapping double quotes are removed and "" -> " inside fields.
        So header names and values are compared without their surrounding quotes, and only
        EMBEDDED quotes survive in a field (used by the noQuotes check). */
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
