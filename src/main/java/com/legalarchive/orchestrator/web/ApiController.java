package com.legalarchive.orchestrator.web;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.web.bind.annotation.RequestBody;

import com.legalarchive.orchestrator.audit.AuditLogger;
import com.legalarchive.orchestrator.ds.DataSourceDef;
import com.legalarchive.orchestrator.ds.DataSourceStore;
import com.legalarchive.orchestrator.ds.SqlSupport;
import com.legalarchive.orchestrator.config.AppProperties;
import com.legalarchive.orchestrator.engine.WorkflowEngine;
import com.legalarchive.orchestrator.engine.WorkflowScheduler;
import com.legalarchive.orchestrator.model.def.GateDef;
import com.legalarchive.orchestrator.model.def.NodeDef;
import com.legalarchive.orchestrator.model.def.StepDef;
import com.legalarchive.orchestrator.model.def.WorkflowDef;
import com.legalarchive.orchestrator.model.run.WorkflowRun;
import com.legalarchive.orchestrator.parser.WorkflowXmlParser;
import com.legalarchive.orchestrator.parser.WorkflowXmlWriter;
import com.legalarchive.orchestrator.registry.WorkflowRegistry;
import com.legalarchive.orchestrator.store.AssetStore;
import com.legalarchive.orchestrator.store.CsvService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import com.legalarchive.orchestrator.store.FeedLayout;
import com.legalarchive.orchestrator.store.RunStore;
import com.legalarchive.orchestrator.web.dto.WorkflowDto;

@RestController
public class ApiController {

    private final WorkflowRegistry registry;
    private final WorkflowEngine engine;
    private final RunStore store;
    private final AuditLogger audit;
    private final WorkflowScheduler scheduler;
    private final AppProperties props;
    private final com.legalarchive.orchestrator.store.GlobalVarsStore globalVars;
    private final DataSourceStore dataSources;
    private final SqlSupport sql;
    private final AssetStore assets;
    private final CsvService csv;
    private final ObjectMapper mapper = new ObjectMapper();
    private final WorkflowXmlWriter xmlWriter = new WorkflowXmlWriter();
    private final WorkflowXmlParser xmlParser = new WorkflowXmlParser();

    public ApiController(WorkflowRegistry registry, WorkflowEngine engine, RunStore store,
                         AuditLogger audit, WorkflowScheduler scheduler, AppProperties props,
                         DataSourceStore dataSources, SqlSupport sql, AssetStore assets, CsvService csv,
                         com.legalarchive.orchestrator.store.GlobalVarsStore globalVars) {
        this.registry = registry;
        this.engine = engine;
        this.store = store;
        this.audit = audit;
        this.scheduler = scheduler;
        this.props = props;
        this.dataSources = dataSources;
        this.sql = sql;
        this.assets = assets;
        this.csv = csv;
        this.globalVars = globalVars;
    }

    // ----------------------------------------------------------- datasources
    @GetMapping("/api/datasources")
    public java.util.List<DataSourceDef> listDataSources() {
        java.util.List<DataSourceDef> out = new java.util.ArrayList<DataSourceDef>();
        for (DataSourceDef d : dataSources.all()) out.add(mask(d));
        return out;
    }

    @PostMapping("/api/datasources")
    public ResponseEntity<Map<String, Object>> saveDataSource(@RequestBody DataSourceDef d) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (d.id == null || !d.id.matches("[A-Za-z0-9._-]+")) {
            return badRequest(out, "Invalid datasource id: only letters, digits, . _ - allowed");
        }
        // keep existing password if the UI sends the masked placeholder
        if ("********".equals(d.password)) {
            DataSourceDef ex = dataSources.get(d.id);
            d.password = ex != null ? ex.password : "";
        }
        dataSources.save(d);
        out.put("ok", true);
        return ResponseEntity.ok(out);
    }

    @PostMapping("/api/datasources/{id}/delete")
    public ResponseEntity<Map<String, Object>> deleteDataSource(@PathVariable String id) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", dataSources.delete(id));
        return ResponseEntity.ok(out);
    }

    /** Test connection. Body is a (possibly unsaved) datasource definition. */
    @PostMapping("/api/datasources/test")
    public Map<String, Object> testDataSource(@RequestBody DataSourceDef d) {
        if ("********".equals(d.password) && d.id != null) {
            DataSourceDef ex = dataSources.get(d.id);
            if (ex != null) d.password = ex.password;
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        String err = sql.test(d);
        out.put("ok", err == null);
        if (err != null) out.put("error", err);
        return out;
    }

    /** Query preview for the SQL step textbox: runs against a saved datasource. */
    @PostMapping("/api/datasources/{id}/query")
    public ResponseEntity<Map<String, Object>> previewQuery(@PathVariable String id,
                                                           @RequestParam(defaultValue = "50") int maxRows,
                                                           @RequestBody String query) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        DataSourceDef d = dataSources.get(id);
        if (d == null) return badRequest(out, "Unknown datasource: " + id);
        try {
            SqlSupport.QueryResult qr = sql.run(d, query, maxRows);
            out.put("ok", true);
            out.put("columns", qr.columns);
            out.put("rows", qr.rows);
            out.put("rowCount", qr.rowCount);
            out.put("truncated", qr.truncated);
            out.put("updateCount", qr.updateCount);
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return badRequest(out, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    public static class CsvSqlPreviewReq {
        public java.util.List<WorkflowDto.NodeDto.CsvInputDto> inputs;
        public String query;
        public String delimiter;
    }

    /**
     * Fast preview for the csvsql step: stages the first 1000 rows of each input into an in-memory H2
     * DB and runs the user query capped at 50 rows. Joins/aggregates are therefore approximate.
     */
    @PostMapping("/api/workflows/{feedId}/csvsql/preview")
    public ResponseEntity<Map<String, Object>> csvsqlPreview(@PathVariable String feedId,
                                                             @RequestBody CsvSqlPreviewReq body) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        WorkflowDef def = registry.get(feedId);
        if (def == null) return badRequest(out, "Unknown workflow: " + feedId);
        if (body == null || body.inputs == null || body.inputs.isEmpty()) return badRequest(out, "At least one input is required");
        if (body.query == null || body.query.trim().isEmpty()) return badRequest(out, "Query is required");

        // best-effort variable map for this feed (no step outputs at preview time)
        Map<String, String> vars = new LinkedHashMap<String, String>();
        vars.putAll(globalVars.all());
        vars.put("feedId", def.feedId);
        vars.put("sourceId", def.sourceId == null ? "" : def.sourceId);
        vars.put("targetId", def.targetId == null ? "" : def.targetId);
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        vars.put("runDate", now.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")));
        vars.put("runTs", now.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")));
        FeedLayout layout = registry.layout(feedId);
        if (layout != null) { try { layout.provision(); } catch (Exception ignore) {} vars.putAll(layout.dirVars()); }
        try { vars.put("sharedDir", assets.sharedDir().toString()); } catch (Exception ignore) {}
        if (def.variables != null) vars.putAll(def.variables);

        char delim = (body.delimiter != null && !body.delimiter.isEmpty()) ? body.delimiter.charAt(0) : ';';
        try { Class.forName("org.h2.Driver"); }
        catch (ClassNotFoundException e) { return badRequest(out, "H2 driver not on classpath — add com.h2database:h2 (see h2/README_H2.md)"); }

        java.sql.Connection conn = null;
        try {
            conn = java.sql.DriverManager.getConnection("jdbc:h2:mem:prev_" + System.nanoTime(), "sa", "");
            String csvOpts = "fieldSeparator=" + delim + " charset=UTF-8";
            java.util.Set<String> seen = new java.util.HashSet<String>();
            for (WorkflowDto.NodeDto.CsvInputDto ci : body.inputs) {
                String table = ci.table == null ? "" : ci.table.trim();
                String csv = com.legalarchive.orchestrator.engine.VarResolver.resolve(ci.csv, vars);
                if (table.isEmpty() || csv == null || csv.trim().isEmpty()) return badRequest(out, "Every input needs both csv and table");
                if (!table.matches("[A-Za-z_][A-Za-z0-9_]*")) return badRequest(out, "Invalid table name '" + table + "'");
                if (!seen.add(table.toUpperCase(java.util.Locale.ROOT))) return badRequest(out, "Duplicate table name '" + table + "'");
                if (!new java.io.File(csv).isFile()) return badRequest(out, "Input file not found: " + csv);
                java.sql.PreparedStatement ps = conn.prepareStatement(
                        "CREATE TABLE " + table + " AS SELECT * FROM CSVREAD(?, NULL, ?) FETCH FIRST 1000 ROWS ONLY");
                ps.setString(1, csv); ps.setString(2, csvOpts);
                ps.executeUpdate(); ps.close();
            }
            String q = com.legalarchive.orchestrator.engine.VarResolver.resolve(body.query, vars);
            java.sql.Statement st = conn.createStatement();
            java.sql.ResultSet rs = st.executeQuery("SELECT * FROM (" + q + ") LIMIT 50");
            java.sql.ResultSetMetaData md = rs.getMetaData();
            int cols = md.getColumnCount();
            java.util.List<String> columns = new java.util.ArrayList<String>();
            for (int i = 1; i <= cols; i++) columns.add(md.getColumnLabel(i));
            java.util.List<java.util.List<String>> rows = new java.util.ArrayList<java.util.List<String>>();
            while (rs.next()) {
                java.util.List<String> r = new java.util.ArrayList<String>();
                for (int i = 1; i <= cols; i++) { Object v = rs.getObject(i); r.add(v == null ? "" : v.toString()); }
                rows.add(r);
            }
            st.close();
            out.put("ok", true);
            out.put("columns", columns);
            out.put("rows", rows);
            out.put("note", "preview on the first 1000 rows of each input — joins/aggregates are approximate");
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return badRequest(out, e.getMessage() == null ? e.toString() : e.getMessage());
        } finally {
            if (conn != null) try { conn.close(); } catch (Exception ignore) {}
        }
    }

    /** Best-effort variable map for a feed (no step outputs), used by previews. */
    private Map<String, String> feedVars(WorkflowDef def, String feedId) {
        Map<String, String> vars = new LinkedHashMap<String, String>();
        vars.putAll(globalVars.all());
        vars.put("feedId", def.feedId);
        vars.put("sourceId", def.sourceId == null ? "" : def.sourceId);
        vars.put("targetId", def.targetId == null ? "" : def.targetId);
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        vars.put("runDate", now.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")));
        vars.put("runTs", now.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")));
        FeedLayout layout = registry.layout(feedId);
        if (layout != null) { try { layout.provision(); } catch (Exception ignore) {} vars.putAll(layout.dirVars()); }
        try { vars.put("sharedDir", assets.sharedDir().toString()); } catch (Exception ignore) {}
        if (def.variables != null) vars.putAll(def.variables);
        return vars;
    }

    /** List the sheet names of an .xlsx (for the xlsx2csv sheet dropdown). */
    @GetMapping("/api/workflows/{feedId}/xlsx/sheets")
    public ResponseEntity<Map<String, Object>> xlsxSheets(@PathVariable String feedId,
                                                          @RequestParam("path") String path) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        WorkflowDef def = registry.get(feedId);
        if (def == null) return badRequest(out, "Unknown workflow: " + feedId);
        String resolved = com.legalarchive.orchestrator.engine.VarResolver.resolve(path, feedVars(def, feedId));
        if (resolved == null || resolved.trim().isEmpty()) return badRequest(out, "path is required");
        java.io.File f = new java.io.File(resolved);
        if (!f.isFile()) return badRequest(out, "file not found: " + resolved);
        if (!resolved.toLowerCase(java.util.Locale.ROOT).endsWith(".xlsx")) return badRequest(out, "not an .xlsx file: " + resolved);
        try {
            java.util.List<String> names = com.legalarchive.orchestrator.xlsx.XlsxSheetReader.sheetNames(f);
            java.util.List<Map<String, Object>> sheets = new java.util.ArrayList<Map<String, Object>>();
            for (int i = 0; i < names.size(); i++) {
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("name", names.get(i)); m.put("index", i);
                sheets.add(m);
            }
            out.put("ok", true); out.put("sheets", sheets); out.put("path", resolved);
            return ResponseEntity.ok(out);
        } catch (Throwable t) {
            return badRequest(out, t.getMessage() == null ? t.toString() : t.getMessage());
        }
    }

    public static class XlsxPreviewReq {
        public String path;
        public String sheet;
        public Integer sheetIndex;
        public Integer headerRow;
        public Integer firstDataRow;
        public String selectBy;
        public String dateFormat;
        public String delimiter;
        public Boolean rawValues;
        public java.util.List<WorkflowDto.NodeDto.ColumnSelDto> columns;
    }

    /** Preview the xlsx2csv conversion: header row + up to 50 projected data rows. */
    @PostMapping("/api/workflows/{feedId}/xlsx/preview")
    public ResponseEntity<Map<String, Object>> xlsxPreview(@PathVariable String feedId,
                                                           @RequestBody XlsxPreviewReq body) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        WorkflowDef def = registry.get(feedId);
        if (def == null) return badRequest(out, "Unknown workflow: " + feedId);
        if (body == null || body.path == null || body.path.trim().isEmpty()) return badRequest(out, "path is required");
        String resolved = com.legalarchive.orchestrator.engine.VarResolver.resolve(body.path, feedVars(def, feedId));
        java.io.File f = new java.io.File(resolved);
        if (!f.isFile()) return badRequest(out, "file not found: " + resolved);
        if (!resolved.toLowerCase(java.util.Locale.ROOT).endsWith(".xlsx")) return badRequest(out, "not an .xlsx file: " + resolved);

        final int headerRow = body.headerRow != null ? body.headerRow : 1;
        final int firstDataRow = body.firstDataRow != null ? body.firstDataRow : 2;
        final String selectBy = (body.selectBy == null || body.selectBy.trim().isEmpty()) ? "header" : body.selectBy.trim();
        String dateFormat = (body.dateFormat == null || body.dateFormat.trim().isEmpty()) ? "yyyyMMdd" : body.dateFormat.trim();
        boolean rawValues = body.rawValues != null && body.rawValues.booleanValue();
        final java.util.List<String> srcs = new java.util.ArrayList<String>();
        final java.util.List<String> ases = new java.util.ArrayList<String>();
        if (body.columns != null) for (WorkflowDto.NodeDto.ColumnSelDto c : body.columns) { srcs.add(c.src); ases.add(c.as); }

        final java.util.List<String> headers = new java.util.ArrayList<String>();
        final java.util.List<java.util.List<String>> rows = new java.util.ArrayList<java.util.List<String>>();
        final int LIMIT = 50;
        try {
            try {
                com.legalarchive.orchestrator.xlsx.XlsxSheetReader.read(f, body.sheet,
                        body.sheetIndex != null ? body.sheetIndex : 0, dateFormat, rawValues,
                        new com.legalarchive.orchestrator.xlsx.XlsxSheetReader.RowSink() {
                            com.legalarchive.orchestrator.xlsx.XlsxSheetReader.Plan plan;
                            public void row(int rowNum, java.util.List<String> cells) throws Exception {
                                if (headerRow >= 1 && rowNum == headerRow) {
                                    plan = com.legalarchive.orchestrator.xlsx.XlsxSheetReader.plan(cells, srcs.isEmpty() ? null : srcs, ases, selectBy);
                                    for (String h : plan.out) headers.add(h);
                                    return;
                                }
                                if (rowNum >= firstDataRow) {
                                    if (plan == null) {
                                        plan = com.legalarchive.orchestrator.xlsx.XlsxSheetReader.plan(cells, srcs.isEmpty() ? null : srcs, ases, selectBy);
                                        for (String h : plan.out) headers.add(h);
                                    }
                                    java.util.List<String> r = new java.util.ArrayList<String>();
                                    for (int k = 0; k < plan.idx.length; k++) {
                                        int ci = plan.idx[k];
                                        String v = (ci >= 0 && ci < cells.size()) ? cells.get(ci) : "";
                                        r.add(v == null ? "" : v);
                                    }
                                    rows.add(r);
                                    if (rows.size() >= LIMIT) throw new StopPreview();
                                }
                            }
                        });
            } catch (StopPreview stop) { /* reached the row cap */ }
            out.put("ok", true);
            out.put("headers", headers);
            out.put("rows", rows);
            out.put("note", "preview of the first " + LIMIT + " data rows");
            return ResponseEntity.ok(out);
        } catch (Throwable t) {
            return badRequest(out, t.getMessage() == null ? t.toString() : t.getMessage());
        }
    }

    private static class StopPreview extends RuntimeException {}

    private DataSourceDef mask(DataSourceDef d) {
        DataSourceDef c = new DataSourceDef();
        c.id = d.id; c.name = d.name; c.type = d.type; c.host = d.host; c.user = d.user;
        c.password = (d.password == null || d.password.isEmpty()) ? "" : "********";
        c.properties = d.properties; c.jdbcUrl = d.jdbcUrl; c.driverClass = d.driverClass; c.testQuery = d.testQuery;
        return c;
    }

    /** Avvio manuale di un workflow. */
    @GetMapping("/api/workflows/{feedId}/active")
    public ResponseEntity<Map<String, Object>> activeRun(@PathVariable String feedId) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        String rid = engine.activeRunId(feedId);
        out.put("ok", true);
        out.put("running", rid != null);
        out.put("runId", rid);
        return ResponseEntity.ok(out);
    }

    /** FIFO execution queue snapshot for the dashboard (running + queued; may be empty). */
    @GetMapping("/api/queue")
    public ResponseEntity<Map<String, Object>> queue() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        java.util.List<Map<String, Object>> items = new java.util.ArrayList<Map<String, Object>>();
        for (com.legalarchive.orchestrator.model.run.WorkflowRun r : engine.queueSnapshot()) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("feedId", r.feedId);
            m.put("runId", r.runId);
            m.put("name", r.workflowName);
            m.put("status", r.status.name());
            m.put("startTs", r.startTs);
            items.add(m);
        }
        out.put("ok", true);
        out.put("items", items);
        return ResponseEntity.ok(out);
    }

    /** Bulk-create workflows from a template feed + CSV. Reserved CSV cols (feedId/name/sourceId/
        description) set workflow attributes; every other column becomes a workflow variable. */
    @PostMapping("/api/workflows/bulk")
    public ResponseEntity<Map<String, Object>> bulkCreate(
            @RequestParam("template") String templateFeedId,
            @RequestParam("csv") String csv,
            @RequestParam(value = "overwrite", defaultValue = "false") String overwriteParam,
            @RequestParam(value = "delimiter", defaultValue = ",") String delimParam,
            @RequestParam(value = "mapFeedId", defaultValue = "feedId") String mapFeedId,
            @RequestParam(value = "mapName", defaultValue = "name") String mapName,
            @RequestParam(value = "mapSourceId", defaultValue = "sourceId") String mapSourceId,
            @RequestParam(value = "mapTargetId", defaultValue = "targetId") String mapTargetId,
            @RequestParam(value = "mapDescription", defaultValue = "description") String mapDescription,
            @RequestParam(value = "mapRecordBusinessDate", defaultValue = "recordBusinessDate") String mapRecordBusinessDate,
            @RequestParam(value = "mapRecordBusinessDateFormat", defaultValue = "recordBusinessDateFormat") String mapRecordBusinessDateFormat,
            @RequestParam(value = "mapSourceDescription", defaultValue = "sourceDescription") String mapSourceDescription,
            @RequestParam(value = "mapTargetDescription", defaultValue = "targetDescription") String mapTargetDescription,
            @RequestParam(value = "mapDataschema", defaultValue = "dataschema") String mapDataschema,
            @RequestParam(value = "mapDisplayschema", defaultValue = "displayschema") String mapDisplayschema,
            @RequestParam(value = "csv2", defaultValue = "") String csv2,
            @RequestParam(value = "delimiter2", defaultValue = ",") String delim2Param,
            @RequestParam(value = "mapFeedId2", defaultValue = "feedId") String mapFeedId2,
            @RequestParam(value = "mapTableName", defaultValue = "tableName") String mapTableName,
            @RequestParam(value = "tableVar", defaultValue = "originTableName") String tableVar) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        try {
            boolean overwrite = "true".equalsIgnoreCase(overwriteParam);
            char delim = (delimParam != null && !delimParam.isEmpty()) ? delimParam.charAt(0) : ',';
            char delim2 = (delim2Param != null && !delim2Param.isEmpty()) ? delim2Param.charAt(0) : ',';

            if (templateFeedId == null || templateFeedId.trim().isEmpty()) {
                out.put("ok", false); out.put("error", "missing 'template' feedId");
                return ResponseEntity.badRequest().body(out);
            }
            if (csv == null || csv.trim().isEmpty()) {
                out.put("ok", false); out.put("error", "missing 'csv' content");
                return ResponseEntity.badRequest().body(out);
            }
            com.legalarchive.orchestrator.model.def.WorkflowDef tpl = registry.get(templateFeedId.trim());
            if (tpl == null || tpl.sourceFile == null) {
                out.put("ok", false); out.put("error", "template workflow not found: " + templateFeedId);
                return ResponseEntity.badRequest().body(out);
            }
            String templateXml = readWorkflowFile(tpl);

            com.legalarchive.orchestrator.parser.BulkWorkflowGenerator.Mapping map =
                    new com.legalarchive.orchestrator.parser.BulkWorkflowGenerator.Mapping();
            map.feedId = mapFeedId; map.name = mapName; map.sourceId = mapSourceId;
            map.targetId = mapTargetId;
            map.description = mapDescription; map.dataschema = mapDataschema; map.displayschema = mapDisplayschema;
            map.recordBusinessDate = mapRecordBusinessDate; map.recordBusinessDateFormat = mapRecordBusinessDateFormat;
            map.sourceDescription = mapSourceDescription; map.targetDescription = mapTargetDescription;

            Map<String, String> tableByFeed =
                    com.legalarchive.orchestrator.parser.BulkWorkflowGenerator.joinTable(csv2, delim2, mapFeedId2, mapTableName);

            java.io.File dir = new java.io.File(props.getWorkflowsDir());
            java.util.List<com.legalarchive.orchestrator.parser.BulkWorkflowGenerator.Item> items =
                    com.legalarchive.orchestrator.parser.BulkWorkflowGenerator.generate(templateXml, csv, delim, map, tableByFeed, tableVar);

            int created = 0, skipped = 0, failed = 0;
            java.util.List<Map<String, Object>> details = new java.util.ArrayList<Map<String, Object>>();
            // collect feeds that were created with schema JSON, to write after reload (need feedDir from layout)
            java.util.List<String[]> schemasToWrite = new java.util.ArrayList<String[]>(); // {feedId, dataschemaJson, displayschemaJson}

            for (com.legalarchive.orchestrator.parser.BulkWorkflowGenerator.Item it : items) {
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("feedId", it.feedId);
                if (it.tableName != null) row.put("table", it.tableName);
                if (it.error != null) {
                    row.put("status", "error"); row.put("detail", it.error); failed++;
                    details.add(row); continue;
                }
                if (it.feedId != null && it.feedId.equals(templateFeedId.trim())) {
                    row.put("status", "skipped"); row.put("detail", "same as template feedId"); skipped++;
                    details.add(row); continue;
                }
                java.io.File target = new java.io.File(dir, it.feedId + ".xml");
                if (target.exists() && !overwrite) {
                    row.put("status", "skipped"); row.put("detail", "file exists (enable overwrite)"); skipped++;
                    details.add(row); continue;
                }
                java.io.File tmp = new java.io.File(dir, it.feedId + ".xml.tmp");
                try {
                    java.nio.file.Files.write(tmp.toPath(), it.xml.getBytes(StandardCharsets.UTF_8));
                    xmlParser.parse(tmp);   // validate with the real parser
                    java.nio.file.Files.move(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    created++;
                    java.util.List<String> notes = new java.util.ArrayList<String>();
                    // validate JSON schemas now (well-formedness); defer file write until after reload
                    String dataJson = checkJson(it.dataschemaJson, "dataschema", notes);
                    String dispJson = checkJson(it.displayschemaJson, "displayschema", notes);
                    if (dataJson != null || dispJson != null) schemasToWrite.add(new String[]{it.feedId, dataJson, dispJson});
                    row.put("status", "created");
                    if (!notes.isEmpty()) row.put("detail", String.join("; ", notes));
                } catch (Exception pe) {
                    try { tmp.delete(); } catch (Exception ignore) {}
                    row.put("status", "error"); row.put("detail", "generated XML invalid: " + pe.getMessage()); failed++;
                }
                details.add(row);
            }
            registry.reload();

            // write schema files into each feed's directory (feedDir resolved from the reloaded layout)
            int schemasWritten = 0;
            for (String[] s : schemasToWrite) {
                String feedId = s[0], dataJson = s[1], dispJson = s[2];
                try {
                    com.legalarchive.orchestrator.store.FeedLayout layout = registry.layout(feedId);
                    if (layout == null) continue;
                    layout.provision();
                    if (dataJson != null) {
                        java.nio.file.Files.write(layout.feedDir.resolve("dataschema.json"), dataJson.getBytes(StandardCharsets.UTF_8));
                        schemasWritten++;
                    }
                    if (dispJson != null) {
                        java.nio.file.Files.write(layout.feedDir.resolve("displayschema.json"), dispJson.getBytes(StandardCharsets.UTF_8));
                        schemasWritten++;
                    }
                } catch (Exception ignore) {}
            }

            out.put("ok", true);
            out.put("created", created);
            out.put("skipped", skipped);
            out.put("failed", failed);
            out.put("schemasWritten", schemasWritten);
            out.put("items", details);
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            out.put("ok", false); out.put("error", e.getMessage());
            return ResponseEntity.status(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR).body(out);
        }
    }

    /** Returns the JSON string if well-formed, else null and appends a note. */
    private String checkJson(String json, String label, java.util.List<String> notes) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            mapper.readTree(json);
            return json;
        } catch (Exception e) {
            notes.add(label + " not written (invalid JSON)");
            return null;
        }
    }

    private String readWorkflowFile(com.legalarchive.orchestrator.model.def.WorkflowDef tpl) throws Exception {
        java.io.File f = new java.io.File(tpl.sourceFile);
        if (!f.isAbsolute() || !f.exists()) {
            f = new java.io.File(props.getWorkflowsDir(), new java.io.File(tpl.sourceFile).getName());
        }
        return new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @PostMapping("/api/workflows/{feedId}/run")
    public ResponseEntity<Map<String, Object>> run(@PathVariable String feedId, HttpServletRequest req) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        try {
            WorkflowRun run = engine.start(feedId, "MANUAL", user(req));
            if (run == null) {
                out.put("ok", false);
                out.put("error", "A run is already active for this feed");
                return ResponseEntity.status(HttpStatus.CONFLICT).body(out);
            }
            out.put("ok", true);
            out.put("runId", run.runId);
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            out.put("ok", false);
            out.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(out);
        }
    }

    /** Delete a workflow definition (its XML file) and reload the registry.
        Refuses if a run is currently active for the feed. Run history/data on disk are left intact. */
    @PostMapping("/api/workflows/{feedId}/delete")
    public ResponseEntity<Map<String, Object>> deleteWorkflow(@PathVariable String feedId) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        try {
            com.legalarchive.orchestrator.model.def.WorkflowDef wf = registry.get(feedId);
            if (wf == null) {
                out.put("ok", false); out.put("error", "Unknown feed");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(out);
            }
            if (engine.activeRunId(feedId) != null) {
                out.put("ok", false); out.put("error", "A run is currently active for this feed");
                return ResponseEntity.status(HttpStatus.CONFLICT).body(out);
            }
            java.io.File dir = new java.io.File(props.getWorkflowsDir());
            java.io.File f = new java.io.File(dir, new java.io.File(wf.sourceFile == null ? feedId + ".xml" : wf.sourceFile).getName());
            boolean removed = f.exists() && f.delete();
            registry.reload();
            out.put("ok", removed);
            if (!removed) out.put("error", "Workflow file not found or could not be deleted");
            return removed ? ResponseEntity.ok(out) : ResponseEntity.status(HttpStatus.NOT_FOUND).body(out);
        } catch (Exception e) {
            out.put("ok", false); out.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(out);
        }
    }

    /** Stato corrente di un run (polling UI). */
    @GetMapping("/api/runs/{feedId}/{runId}")
    public ResponseEntity<WorkflowRun> runState(@PathVariable String feedId, @PathVariable String runId) {
        WorkflowRun run = engine.activeRun(runId);
        if (run == null) {
            FeedLayout layout = registry.layout(feedId);
            if (layout != null) run = store.load(layout, runId);
        }
        return run == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(run);
    }

    /** Approvazione/rifiuto di un gate manuale in attesa. */
    @PostMapping("/api/runs/{feedId}/{runId}/decision")
    public ResponseEntity<Map<String, Object>> decision(@PathVariable String feedId, @PathVariable String runId,
                                                        @RequestParam boolean approve,
                                                        @RequestParam(required = false) String note,
                                                        HttpServletRequest req) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        boolean ok = engine.decide(feedId, runId, approve, user(req), note);
        out.put("ok", ok);
        if (!ok) out.put("error", "The run is not waiting for approval");
        return ok ? ResponseEntity.ok(out) : ResponseEntity.status(HttpStatus.CONFLICT).body(out);
    }

    /** Stop a running or waiting run. */
    @PostMapping("/api/runs/{feedId}/{runId}/stop")
    public ResponseEntity<Map<String, Object>> stop(@PathVariable String feedId, @PathVariable String runId,
                                                    HttpServletRequest req) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        boolean ok = engine.stop(feedId, runId, user(req));
        out.put("ok", ok);
        if (!ok) out.put("error", "Run not found or already finished");
        return ok ? ResponseEntity.ok(out) : ResponseEntity.status(HttpStatus.CONFLICT).body(out);
    }

    /** Log PowerShell di uno step (testo). */
    @PostMapping("/api/runs/{feedId}/{runId}/delete")
    public ResponseEntity<Map<String, Object>> deleteRun(@PathVariable String feedId, @PathVariable String runId,
                                                         HttpServletRequest req) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        WorkflowDef wf = registry.get(feedId);
        if (wf == null) { out.put("ok", false); out.put("error", "Unknown feed"); return ResponseEntity.status(HttpStatus.NOT_FOUND).body(out); }
        if (runId.equals(engine.activeRunId(feedId))) {
            out.put("ok", false); out.put("error", "Run is currently active — stop it before deleting");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(out);
        }
        FeedLayout layout = registry.layout(feedId);
        boolean removed = store.delete(layout, runId);
        audit.log(layout.auditFile(), feedId, runId, null, "RUN_DELETED", user(req), new java.util.LinkedHashMap<String, String>());
        out.put("ok", removed);
        if (!removed) out.put("error", "Run not found");
        return removed ? ResponseEntity.ok(out) : ResponseEntity.status(HttpStatus.NOT_FOUND).body(out);
    }

    @GetMapping(value = "/api/runs/{feedId}/{runId}/log/{stepId}", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> stepLog(@PathVariable String feedId, @PathVariable String runId,
                                          @PathVariable String stepId,
                                          @RequestParam(value = "tail", defaultValue = "0") int tail) {
        FeedLayout layout = registry.layout(feedId);
        if (layout == null) return ResponseEntity.notFound().build();
        // anti path-traversal: solo nomi semplici
        if (!runId.matches("[A-Za-z0-9._-]+") || !stepId.matches("[A-Za-z0-9._-]+")) {
            return ResponseEntity.badRequest().body("Invalid identifiers");
        }
        Path log = layout.stepLog(runId, stepId);
        if (!Files.exists(log)) return ResponseEntity.ok("(no log available)");
        int cap = tail > 0 ? Math.min(tail, 20000) : 5000;     // bounded memory: keep only the last N lines
        try {
            java.util.ArrayDeque<String> dq = new java.util.ArrayDeque<String>(Math.min(cap, 4096));
            long total = 0;
            java.io.BufferedReader r = Files.newBufferedReader(log, StandardCharsets.UTF_8);
            try {
                String ln;
                while ((ln = r.readLine()) != null) {
                    total++;
                    if (dq.size() >= cap) dq.pollFirst();
                    dq.addLast(ln);
                }
            } finally { r.close(); }
            StringBuilder sb = new StringBuilder();
            if (total > dq.size()) sb.append("… (showing last ").append(dq.size()).append(" of ").append(total).append(" lines)").append(System.lineSeparator());
            for (String raw : dq) {
                String[] p = parseLogLine(raw);
                String tag = "E".equals(p[0]) ? "[ERR] " : ("S".equals(p[0]) ? "[SYS] " : "");
                sb.append(p[1].isEmpty() ? "" : (p[1] + "  ")).append(tag).append(p[2]).append(System.lineSeparator());
            }
            return ResponseEntity.ok(sb.toString());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error reading log: " + e.getMessage());
        }
    }

    /** Parse a tagged log line "STREAM\tTS\tcontent"; falls back to stdout for untagged lines. */
    private String[] parseLogLine(String ln) {
        int t1 = ln.indexOf('\t');
        if (t1 == 1 && (ln.charAt(0) == 'O' || ln.charAt(0) == 'E' || ln.charAt(0) == 'S')) {
            int t2 = ln.indexOf('\t', t1 + 1);
            if (t2 > t1) return new String[]{ String.valueOf(ln.charAt(0)), ln.substring(t1 + 1, t2), ln.substring(t2 + 1) };
        }
        return new String[]{ "O", "", ln };
    }

    /** Live console feed: returns new lines for a step (incremental via offset), with stream tags.
     *  For a forEach step it merges the per-item logs (stepId__N.log) chronologically, tagging each
     *  line with its item index, and returns the full merged view (full=true). */
    @GetMapping("/api/runs/{feedId}/{runId}/log/{stepId}/tail")
    public ResponseEntity<Map<String, Object>> stepTail(@PathVariable String feedId, @PathVariable String runId,
            @PathVariable String stepId, @RequestParam(value = "offset", defaultValue = "0") int offset) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        FeedLayout layout = registry.layout(feedId);
        if (layout == null) return badRequest(out, "Unknown workflow");
        if (!runId.matches("[A-Za-z0-9._-]+") || !stepId.matches("[A-Za-z0-9._-]+")) return badRequest(out, "Invalid identifiers");
        try {
            Path main = layout.stepLog(runId, stepId);
            Path dir = main.getParent();
            java.util.List<Path> items = new java.util.ArrayList<Path>();
            if (dir != null && Files.isDirectory(dir)) {
                final String pfx = stepId + "__";
                java.io.File[] kids = dir.toFile().listFiles();
                if (kids != null) for (java.io.File k : kids) {
                    String n = k.getName();
                    if (n.startsWith(pfx) && n.endsWith(".log")) items.add(k.toPath());
                }
            }
            java.util.List<Map<String, Object>> lines = new java.util.ArrayList<Map<String, Object>>();

            if (!items.isEmpty()) {
                // forEach: merge all item logs, sort by timestamp then item, tag with [#N]
                // bounded memory: keep only the last lines per item and cap the merged view
                final int PER_ITEM_CAP = 2000, MERGED_CAP = 8000;
                java.util.List<Object[]> merged = new java.util.ArrayList<Object[]>(); // {ts, item, s, c, seq}
                long seq = 0;
                for (Path ip : items) {
                    String fn = ip.getFileName().toString();
                    int us = fn.lastIndexOf("__"); int dot = fn.lastIndexOf(".log");
                    String item = (us >= 0 && dot > us) ? fn.substring(us + 2, dot) : "?";
                    java.util.ArrayDeque<String> dq = new java.util.ArrayDeque<String>(256);
                    java.io.BufferedReader ir = Files.newBufferedReader(ip, StandardCharsets.UTF_8);
                    try {
                        String ln;
                        while ((ln = ir.readLine()) != null) { if (dq.size() >= PER_ITEM_CAP) dq.pollFirst(); dq.addLast(ln); }
                    } finally { ir.close(); }
                    for (String ln : dq) { String[] p = parseLogLine(ln); merged.add(new Object[]{ p[1], item, p[0], p[2], seq++ }); }
                }
                merged.sort(new java.util.Comparator<Object[]>() {
                    public int compare(Object[] a, Object[] b) {
                        int c = ((String) a[0]).compareTo((String) b[0]);
                        if (c != 0) return c;
                        return Long.compare((Long) a[4], (Long) b[4]);
                    }
                });
                int from = Math.max(0, merged.size() - MERGED_CAP);
                for (int mi = from; mi < merged.size(); mi++) {
                    Object[] m = merged.get(mi);
                    Map<String, Object> o = new LinkedHashMap<String, Object>();
                    o.put("s", m[2]); o.put("i", m[1]); o.put("c", m[3]);
                    lines.add(o);
                }
                out.put("full", true);
                out.put("next", lines.size());
            } else {
                if (!Files.exists(main)) { out.put("ok", true); out.put("lines", lines); out.put("next", 0); out.put("full", false); return ResponseEntity.ok(out); }
                // stream: skip already-delivered lines, return at most MAX_BATCH new ones (next poll continues)
                final int MAX_BATCH = 4000;
                long delivered = 0;
                java.io.BufferedReader br = Files.newBufferedReader(main, StandardCharsets.UTF_8);
                try {
                    String ln;
                    while (delivered < offset && br.readLine() != null) delivered++;
                    while (lines.size() < MAX_BATCH && (ln = br.readLine()) != null) {
                        String[] p = parseLogLine(ln);
                        Map<String, Object> o = new LinkedHashMap<String, Object>();
                        o.put("s", p[0]); o.put("c", p[2]);
                        lines.add(o);
                    }
                } finally { br.close(); }
                out.put("full", false);
                out.put("next", delivered + lines.size());
            }
            out.put("ok", true);
            out.put("lines", lines);
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return badRequest(out, e.getMessage());
        }
    }

    /** Verifica integrita' della catena di audit. */
    @GetMapping("/api/audit/{feedId}/verify")
    public ResponseEntity<AuditLogger.VerifyResult> verify(@PathVariable String feedId) {
        FeedLayout layout = registry.layout(feedId);
        if (layout == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(audit.verify(layout.auditFile()));
    }

    /** Ricarica le definizioni XML e ripianifica i cron. */
    @PostMapping("/api/reload")
    public Map<String, Object> reload() {
        registry.reload();
        scheduler.reschedule();
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.put("workflows", registry.all().size());
        out.put("errors", registry.errors());
        return out;
    }

    /**
     * DESTRUCTIVE: wipe all run history / data by deleting every entry under the feeds base
     * directory and recreating it empty. Refuses if any run is active. The UI guards this with a
     * very explicit irreversible confirmation and disables it for PROD workflows.
     */
    @PostMapping("/api/admin/clear-history")
    public ResponseEntity<Map<String, Object>> clearHistory() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        // safety: never wipe while something is running
        for (WorkflowDef def : registry.all()) {
            if (engine.activeRunId(def.feedId) != null) {
                return badRequest(out, "A run is currently active (" + def.feedId + "). Wait for it to finish before clearing history.");
            }
        }
        java.io.File base = new java.io.File(props.getDefaultBaseDir());
        int removed = 0;
        try {
            if (base.isDirectory()) {
                java.io.File[] children = base.listFiles();
                if (children != null) {
                    for (java.io.File c : children) { deleteRecursively(c); removed++; }
                }
            }
            if (!base.isDirectory() && !base.mkdirs()) {
                return badRequest(out, "Could not recreate the feeds base directory: " + base.getAbsolutePath());
            }
        } catch (Exception e) {
            return badRequest(out, "Clear history failed: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
        out.put("ok", true);
        out.put("base", base.getAbsolutePath());
        out.put("removed", removed);
        return ResponseEntity.ok(out);
    }

    private static void deleteRecursively(java.io.File f) throws java.io.IOException {
        if (f == null) return;
        java.nio.file.Path root = f.toPath();
        if (!java.nio.file.Files.exists(root)) return;
        java.nio.file.Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            public java.nio.file.FileVisitResult visitFile(java.nio.file.Path p, java.nio.file.attribute.BasicFileAttributes a) throws java.io.IOException {
                java.nio.file.Files.delete(p); return java.nio.file.FileVisitResult.CONTINUE;
            }
            public java.nio.file.FileVisitResult postVisitDirectory(java.nio.file.Path d, java.io.IOException e) throws java.io.IOException {
                java.nio.file.Files.delete(d); return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Save a workflow from raw XML pasted/edited in the designer. The XML is validated by parsing
     * it with the runtime parser; on success it is written and the registry/scheduler reloaded.
     */
    @PostMapping("/api/workflows/save-xml")
    public ResponseEntity<Map<String, Object>> saveXml(@RequestBody String xml,
                                                       @RequestParam(defaultValue = "false") boolean overwrite,
                                                       HttpServletRequest req) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (xml == null || xml.trim().isEmpty()) return badRequest(out, "Empty XML");
        java.io.File dir = new java.io.File(props.getWorkflowsDir());
        if (!dir.isDirectory() && !dir.mkdirs()) return badRequest(out, "Workflows directory cannot be created: " + dir.getAbsolutePath());
        String feedId;
        Path tmp = null;
        try {
            tmp = Files.createTempFile(dir.toPath(), "_validate_", ".tmp");
            Files.write(tmp, xml.getBytes(StandardCharsets.UTF_8));
            WorkflowDef parsed = xmlParser.parse(tmp.toFile());
            feedId = parsed.feedId;
            if (feedId == null || !feedId.matches("[A-Za-z0-9._-]+")) return badRequest(out, "Invalid or missing feedId in the XML");
        } catch (Exception e) {
            return badRequest(out, "Validation failed: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        } finally {
            if (tmp != null) try { Files.deleteIfExists(tmp); } catch (Exception ignore) {}
        }
        WorkflowDef existing = registry.get(feedId);
        String fileName = existing != null && existing.sourceFile != null ? existing.sourceFile : feedId + ".xml";
        Path target = dir.toPath().resolve(fileName);
        try {
            if ((existing != null || Files.exists(target)) && !overwrite) {
                out.put("ok", false); out.put("exists", true);
                out.put("error", "Workflow '" + feedId + "' already exists. Confirm overwrite.");
                return ResponseEntity.status(HttpStatus.CONFLICT).body(out);
            }
            Files.write(target, xml.getBytes(StandardCharsets.UTF_8));
            registry.reload();
            scheduler.reschedule();
        } catch (Exception e) {
            return badRequest(out, "Save failed: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
        out.put("ok", true); out.put("feedId", feedId); out.put("file", fileName);
        return ResponseEntity.ok(out);
    }

    /** Designer: current definition of a workflow as JSON. */
    @GetMapping("/api/workflows/{feedId}/definition")
    public ResponseEntity<WorkflowDto> definition(@PathVariable String feedId) {
        WorkflowDef def = registry.get(feedId);
        if (def == null) return ResponseEntity.notFound().build();
        WorkflowDto dto = toDto(def);
        return ResponseEntity.ok(dto);
    }

    /** Build the editor DTO from a workflow definition (used by the designer and the variables page). */
    WorkflowDto toDto(WorkflowDef def) {
        WorkflowDto dto = new WorkflowDto();
        dto.feedId = def.feedId;
        dto.sourceId = def.sourceId;
        dto.targetId = def.targetId;
        dto.sourceDescription = def.sourceDescription;
        dto.targetDescription = def.targetDescription;
        dto.production = def.production;
        dto.name = def.name;
        dto.cron = def.cron;
        dto.baseDir = def.baseDir;
        dto.description = def.description;
        for (Map.Entry<String, String> v : def.variables.entrySet()) {
            dto.variables.add(new WorkflowDto.KV(v.getKey(), v.getValue()));
        }
        for (NodeDef n : def.nodes) {
            WorkflowDto.NodeDto nd = new WorkflowDto.NodeDto();
            nd.kind = n.getKind();
            nd.id = n.id;
            nd.name = n.name;
            if (n instanceof StepDef) {
                StepDef st = (StepDef) n;
                nd.script = st.script;
                nd.exec = st.exec;
                nd.timeoutSec = st.timeoutSec > 0 ? st.timeoutSec : null;
                nd.retry = st.retry > 0 ? st.retry : null;
                nd.retryDelaySec = st.retry > 0 ? st.retryDelaySec : null;
                nd.datasource = st.datasource;
                nd.query = st.query;
                nd.ifsPath = st.ifsPath;
                nd.source = st.source;
                nd.dest = st.dest;
                nd.pattern = st.pattern;
                nd.mode = st.mode;
                nd.overwrite = st.overwrite ? Boolean.TRUE : null;
                nd.outputVar = st.outputVar;
                nd.csvFile = st.csvFile;
                nd.csvSplitRows = st.csvSplitRows > 0 ? st.csvSplitRows : null;
                nd.csvSplitMb = st.csvSplitMb > 0 ? st.csvSplitMb : null;
                nd.validateChecks = st.validateChecks;
                if (st.replacements != null && !st.replacements.isEmpty()) {
                    nd.replacements = new java.util.ArrayList<WorkflowDto.NodeDto.ReplacementDto>();
                    for (com.legalarchive.orchestrator.model.def.Replacement rp : st.replacements) {
                        WorkflowDto.NodeDto.ReplacementDto rd = new WorkflowDto.NodeDto.ReplacementDto();
                        rd.from = rp.from; rd.to = rp.to; rd.columns = rp.columns;
                        nd.replacements.add(rd);
                    }
                }
                if (st.inputs != null && !st.inputs.isEmpty()) {
                    nd.inputs = new java.util.ArrayList<WorkflowDto.NodeDto.CsvInputDto>();
                    for (com.legalarchive.orchestrator.model.def.CsvInput ci : st.inputs) {
                        WorkflowDto.NodeDto.CsvInputDto cd = new WorkflowDto.NodeDto.CsvInputDto();
                        cd.csv = ci.csv; cd.table = ci.table;
                        nd.inputs.add(cd);
                    }
                }
                if (st.columns != null && !st.columns.isEmpty()) {
                    nd.columns = new java.util.ArrayList<WorkflowDto.NodeDto.ColumnSelDto>();
                    for (com.legalarchive.orchestrator.model.def.ColumnSel cs : st.columns) {
                        WorkflowDto.NodeDto.ColumnSelDto cd = new WorkflowDto.NodeDto.ColumnSelDto();
                        cd.src = cs.src; cd.as = cs.as;
                        nd.columns.add(cd);
                    }
                }
                nd.delimiter = st.delimiter;
                nd.forEach = st.forEach;
                nd.concurrency = st.concurrency != 4 ? st.concurrency : null;
                for (Map.Entry<String, String> p : st.params.entrySet()) {
                    nd.params.add(new WorkflowDto.KV(p.getKey(), p.getValue()));
                }
                nd.outputs.addAll(st.outputs);
            } else if (n instanceof com.legalarchive.orchestrator.model.def.LoopDef) {
                com.legalarchive.orchestrator.model.def.LoopDef lp = (com.legalarchive.orchestrator.model.def.LoopDef) n;
                nd.over = lp.over;
                nd.loopDelimiter = lp.delimiter;
                nd.itemVar = lp.itemVar;
                nd.indexVar = lp.indexVar;
                nd.indexStringVar = lp.indexStringVar;
                nd.indexPad = String.valueOf(lp.indexPad);
                nd.countVar = lp.countVar;
            } else if (n instanceof com.legalarchive.orchestrator.model.def.LoopEndDef) {
                // marker only: kind/id/name already set
            } else {
                GateDef g = (GateDef) n;
                nd.type = g.type;
                nd.condition = g.condition;
                nd.onTrue = g.onTrue;
                nd.onFalse = g.onFalse;
            }
            dto.nodes.add(nd);
        }
        return dto;
    }

    // =========================== variables manager ===========================

    /** Everything the variables page needs: global vars (file + properties) and, per workflow,
     *  its selectors (source/target ids + descriptions), workflow variables and step params. */
    @GetMapping("/api/var-catalog")
    public ResponseEntity<Map<String, Object>> varCatalog() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        Map<String, Object> globals = new LinkedHashMap<String, Object>();
        globals.put("filePath", globalVars.file().getAbsolutePath());
        globals.put("file", globalVars.fileVars());
        globals.put("props", globalVars.propsVars());
        out.put("globals", globals);

        java.util.List<Map<String, Object>> wfs = new java.util.ArrayList<Map<String, Object>>();
        for (WorkflowDef def : registry.all()) {
            WorkflowDto dto = toDto(def);
            Map<String, Object> w = new LinkedHashMap<String, Object>();
            w.put("feedId", dto.feedId);
            w.put("name", dto.name == null ? "" : dto.name);
            w.put("sourceId", dto.sourceId == null ? "" : dto.sourceId);
            w.put("targetId", dto.targetId == null ? "" : dto.targetId);
            w.put("sourceDescription", dto.sourceDescription == null ? "" : dto.sourceDescription);
            w.put("targetDescription", dto.targetDescription == null ? "" : dto.targetDescription);
            java.util.List<Map<String, String>> vars = new java.util.ArrayList<Map<String, String>>();
            for (WorkflowDto.KV kv : dto.variables) {
                Map<String, String> m = new LinkedHashMap<String, String>();
                m.put("name", kv.name); m.put("value", kv.value == null ? "" : kv.value);
                vars.add(m);
            }
            w.put("vars", vars);
            java.util.List<Map<String, Object>> steps = new java.util.ArrayList<Map<String, Object>>();
            for (WorkflowDto.NodeDto nd : dto.nodes) {
                if (!"STEP".equals(nd.kind)) continue;
                Map<String, Object> sm = new LinkedHashMap<String, Object>();
                sm.put("id", nd.id); sm.put("name", nd.name == null ? "" : nd.name); sm.put("exec", nd.exec == null ? "" : nd.exec);
                java.util.List<Map<String, String>> ps = new java.util.ArrayList<Map<String, String>>();
                if (nd.params != null) for (WorkflowDto.KV kv : nd.params) {
                    Map<String, String> m = new LinkedHashMap<String, String>();
                    m.put("name", kv.name); m.put("value", kv.value == null ? "" : kv.value);
                    ps.add(m);
                }
                sm.put("params", ps);
                steps.add(sm);
            }
            w.put("steps", steps);
            wfs.add(w);
        }
        out.put("workflows", wfs);
        out.put("ok", true);
        return ResponseEntity.ok(out);
    }

    public static class GlobalsSaveReq { public java.util.List<WorkflowDto.KV> vars; }

    /** Save the file-based global variables (application.properties globals are read-only). */
    @PostMapping("/api/globals/save")
    public ResponseEntity<Map<String, Object>> saveGlobals(@RequestBody GlobalsSaveReq body) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        Map<String, String> m = new LinkedHashMap<String, String>();
        java.util.Set<String> seen = new java.util.HashSet<String>();
        if (body != null && body.vars != null) {
            for (WorkflowDto.KV kv : body.vars) {
                String k = kv.name == null ? "" : kv.name.trim();
                if (k.isEmpty()) continue;
                if (!k.matches("[A-Za-z_][A-Za-z0-9_.-]*")) return badRequest(out, "Invalid variable name: '" + k + "'");
                if (!seen.add(k)) return badRequest(out, "Duplicate variable name: '" + k + "'");
                m.put(k, kv.value == null ? "" : kv.value);
            }
        }
        try {
            globalVars.saveFile(m);
        } catch (Exception e) {
            return badRequest(out, "Could not save global variables: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
        out.put("ok", true);
        out.put("count", m.size());
        out.put("file", globalVars.file().getAbsolutePath());
        return ResponseEntity.ok(out);
    }

    public static class VarSaveReq {
        public java.util.List<FeedEdit> edits;
        public static class FeedEdit {
            public String feedId;
            public java.util.List<WorkflowDto.KV> vars;     // workflow-level variables to set
            public java.util.List<StepEdit> steps;          // optional per-step param edits
        }
        public static class StepEdit {
            public String stepId;
            public java.util.List<WorkflowDto.KV> params;
        }
    }

    /**
     * Apply variable (and optional step-param) edits to one or more workflows. Every modified XML
     * is regenerated and validated with the runtime parser BEFORE anything is written: if any
     * workflow fails validation, nothing is saved and the per-feed errors are returned.
     */
    @PostMapping("/api/variables/save")
    public ResponseEntity<Map<String, Object>> saveVariables(@RequestBody VarSaveReq body, HttpServletRequest req) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (body == null || body.edits == null || body.edits.isEmpty()) return badRequest(out, "No edits supplied");

        java.util.List<Map<String, Object>> results = new java.util.ArrayList<Map<String, Object>>();
        java.util.List<String[]> staged = new java.util.ArrayList<String[]>(); // {feedId, fileName, xml}
        boolean allOk = true;

        for (VarSaveReq.FeedEdit fe : body.edits) {
            Map<String, Object> r = new LinkedHashMap<String, Object>();
            r.put("feedId", fe.feedId);
            WorkflowDef def = fe.feedId == null ? null : registry.get(fe.feedId);
            if (def == null) { r.put("ok", false); r.put("error", "workflow not found"); results.add(r); allOk = false; continue; }
            WorkflowDto dto = toDto(def);
            // apply workflow-level variable edits
            if (fe.vars != null) {
                for (WorkflowDto.KV kv : fe.vars) {
                    if (kv.name == null || kv.name.trim().isEmpty()) continue;
                    boolean found = false;
                    for (WorkflowDto.KV ex : dto.variables) { if (kv.name.equals(ex.name)) { ex.value = kv.value; found = true; break; } }
                    if (!found) dto.variables.add(new WorkflowDto.KV(kv.name.trim(), kv.value == null ? "" : kv.value));
                }
            }
            // apply per-step param edits
            if (fe.steps != null) {
                for (VarSaveReq.StepEdit se : fe.steps) {
                    if (se.stepId == null || se.params == null) continue;
                    for (WorkflowDto.NodeDto nd : dto.nodes) {
                        if (!"STEP".equals(nd.kind) || !se.stepId.equals(nd.id) || nd.params == null) continue;
                        for (WorkflowDto.KV kv : se.params) {
                            for (WorkflowDto.KV ex : nd.params) { if (kv.name != null && kv.name.equals(ex.name)) { ex.value = kv.value; break; } }
                        }
                    }
                }
            }
            // regenerate + validate
            String fileName = def.sourceFile != null ? def.sourceFile : def.feedId + ".xml";
            Path tmp = null;
            try {
                String xml = xmlWriter.toXml(dto);
                java.io.File dir = new java.io.File(props.getWorkflowsDir());
                if (!dir.isDirectory()) dir.mkdirs();
                tmp = Files.createTempFile(dir.toPath(), "_validate_", ".tmp");
                Files.write(tmp, xml.getBytes(StandardCharsets.UTF_8));
                xmlParser.parse(tmp.toFile());          // throws if invalid
                staged.add(new String[]{def.feedId, fileName, xml});
                r.put("ok", true);
            } catch (Exception e) {
                r.put("ok", false);
                r.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
                allOk = false;
            } finally {
                if (tmp != null) try { Files.deleteIfExists(tmp); } catch (Exception ignore) {}
            }
            results.add(r);
        }

        if (!allOk) {
            out.put("ok", false);
            out.put("error", "No changes were saved: one or more workflows failed validation.");
            out.put("results", results);
            return ResponseEntity.badRequest().body(out);
        }

        // all valid: write every staged file, then reload once
        java.io.File dir = new java.io.File(props.getWorkflowsDir());
        try {
            for (String[] s : staged) Files.write(dir.toPath().resolve(s[1]), s[2].getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return badRequest(out, "Validation passed but writing failed: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
        registry.reload();
        scheduler.reschedule();
        out.put("ok", true);
        out.put("saved", staged.size());
        out.put("results", results);
        return ResponseEntity.ok(out);
    }

    /**
     * Designer: validate and save a workflow definition.
     * The XML is generated server-side, re-validated with the same parser used at
     * load time, written to the workflows directory, then registry and scheduler
     * are reloaded. The save is recorded in the feed audit log.
     */
    @PostMapping("/api/workflows/save")
    public ResponseEntity<Map<String, Object>> save(@RequestBody WorkflowDto dto,
                                                    @RequestParam(defaultValue = "false") boolean overwrite,
                                                    HttpServletRequest req) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        try {
            if (dto.feedId == null || !dto.feedId.matches("[A-Za-z0-9._-]+")) {
                return badRequest(out, "Invalid feedId: only letters, digits, . _ - are allowed");
            }
            String xml = xmlWriter.toXml(dto);

            java.io.File dir = new java.io.File(props.getWorkflowsDir());
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return badRequest(out, "Workflows directory cannot be created: " + dir.getAbsolutePath());
            }

            // validate by parsing the generated XML before touching the real file
            Path tmp = Files.createTempFile(dir.toPath(), "_validate_", ".tmp");
            try {
                Files.write(tmp, xml.getBytes(StandardCharsets.UTF_8));
                WorkflowDef parsed = xmlParser.parse(tmp.toFile());
                if (!parsed.feedId.equals(dto.feedId)) {
                    return badRequest(out, "feedId mismatch after generation");
                }
            } catch (Exception e) {
                return badRequest(out, "Validation failed: " + e.getMessage());
            } finally {
                Files.deleteIfExists(tmp);
            }

            WorkflowDef existing = registry.get(dto.feedId);
            String fileName = existing != null && existing.sourceFile != null
                    ? existing.sourceFile : dto.feedId + ".xml";
            Path target = dir.toPath().resolve(fileName);
            if ((existing != null || Files.exists(target)) && !overwrite) {
                out.put("ok", false);
                out.put("exists", true);
                out.put("error", "Workflow '" + dto.feedId + "' already exists. Confirm overwrite.");
                return ResponseEntity.status(HttpStatus.CONFLICT).body(out);
            }

            Files.write(target, xml.getBytes(StandardCharsets.UTF_8));
            registry.reload();
            scheduler.reschedule();

            FeedLayout layout = registry.layout(dto.feedId);
            if (layout != null) {
                Map<String, String> det = new LinkedHashMap<String, String>();
                det.put("file", fileName);
                det.put("action", existing != null ? "UPDATED" : "CREATED");
                det.put("nodes", String.valueOf(dto.nodes == null ? 0 : dto.nodes.size()));
                audit.log(layout.auditFile(), dto.feedId, null, null, "WORKFLOW_SAVED", user(req), det);
            }

            out.put("ok", true);
            out.put("feedId", dto.feedId);
            out.put("file", fileName);
            out.put("loadErrors", registry.errors());
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return badRequest(out, "Save failed: " + e.getMessage());
        }
    }

    // =================================================================== files
    // Feed scope: /api/workflows/{feedId}/files...   App scope: /api/shared/files...
    // feedId == null  -> application-wide shared files (usable by every workflow).

    @GetMapping("/api/workflows/{feedId}/files")
    public ResponseEntity<Map<String, Object>> listFiles(@PathVariable String feedId,
                                                         @RequestParam(value = "root", defaultValue = "false") boolean rootOnly) { return doList(feedId, rootOnly); }
    @GetMapping("/api/shared/files")
    public ResponseEntity<Map<String, Object>> listShared(@RequestParam(value = "root", defaultValue = "false") boolean rootOnly) { return doList(null, rootOnly); }

    @PostMapping("/api/workflows/{feedId}/files")
    public ResponseEntity<Map<String, Object>> uploadFile(@PathVariable String feedId,
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            @RequestParam(value = "kind", required = false) String kind,
            @RequestParam(value = "alias", required = false) String alias,
            HttpServletRequest req) { return doUpload(feedId, file, kind, alias, req); }
    @PostMapping("/api/shared/files")
    public ResponseEntity<Map<String, Object>> uploadShared(
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            @RequestParam(value = "kind", required = false) String kind,
            @RequestParam(value = "alias", required = false) String alias,
            HttpServletRequest req) { return doUpload(null, file, kind, alias, req); }

    @PostMapping("/api/workflows/{feedId}/files/delete")
    public ResponseEntity<Map<String, Object>> deleteFile(@PathVariable String feedId,
            @RequestParam("path") String path, HttpServletRequest req) { return doDelete(feedId, path, req); }
    @PostMapping("/api/shared/files/delete")
    public ResponseEntity<Map<String, Object>> deleteShared(
            @RequestParam("path") String path, HttpServletRequest req) { return doDelete(null, path, req); }

    @PostMapping("/api/workflows/{feedId}/files/create")
    public ResponseEntity<Map<String, Object>> createFile(@PathVariable String feedId,
            @RequestParam("name") String name, @RequestParam(value = "content", required = false) String content,
            @RequestParam(value = "kind", required = false) String kind,
            @RequestParam(value = "alias", required = false) String alias,
            HttpServletRequest req) { return doCreate(feedId, name, content, kind, alias, req); }
    @PostMapping("/api/shared/files/create")
    public ResponseEntity<Map<String, Object>> createShared(
            @RequestParam("name") String name, @RequestParam(value = "content", required = false) String content,
            @RequestParam(value = "kind", required = false) String kind,
            @RequestParam(value = "alias", required = false) String alias,
            HttpServletRequest req) { return doCreate(null, name, content, kind, alias, req); }

    @GetMapping("/api/workflows/{feedId}/download")
    public void download(@PathVariable String feedId, @RequestParam("path") String path,
                         javax.servlet.http.HttpServletResponse resp) throws java.io.IOException { serve(feedId, path, resp); }
    @GetMapping("/api/shared/download")
    public void downloadShared(@RequestParam("path") String path,
                               javax.servlet.http.HttpServletResponse resp) throws java.io.IOException { serve(null, path, resp); }

    /** Suggest a unique, sanitized alias derived from a file name. */
    @GetMapping("/api/workflows/{feedId}/alias-suggest")
    public Map<String, Object> aliasFeed(@PathVariable String feedId, @RequestParam("file") String file) { return aliasSuggest(feedId, file); }
    @GetMapping("/api/shared/alias-suggest")
    public Map<String, Object> aliasShared(@RequestParam("file") String file) { return aliasSuggest(null, file); }

    // ======================= MASK POOL FILES =======================
    // Catalog + view/replace for the masking value pools. Effective file =
    // external override (orchestrator.mask-pools-dir) if present, else bundled
    // in the WAR under /maskdata/. Endpoint shape mirrors the shared-files panel
    // so filespanel.js can be reused unchanged.

    private String poolDir() {
        String d = props.getMaskPoolsDir();
        if (d == null || d.trim().isEmpty()) return null;
        return d.trim();
    }

    @GetMapping("/api/mask/pools/files")
    public ResponseEntity<Map<String, Object>> poolList() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        com.legalarchive.orchestrator.mask.MaskPools pools =
                new com.legalarchive.orchestrator.mask.MaskPools(props.getMaskPoolsDir());
        String dir = poolDir();
        java.util.LinkedHashMap<String, Map<String, Object>> byName =
                new java.util.LinkedHashMap<String, Map<String, Object>>();
        for (String b : com.legalarchive.orchestrator.mask.MaskPools.BUNDLED) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            boolean ext = pools.hasExternal(b);
            m.put("path", b); m.put("name", b); m.put("kind", "document"); m.put("alias", null);
            m.put("size", (long) pools.get(b).length);   // entry count as a size hint
            m.put("modified", 0L);
            m.put("source", ext ? "external" : "bundled");
            m.put("managed", ext);
            byName.put(b, m);
        }
        if (dir != null) {
            java.io.File[] kids = new java.io.File(dir).listFiles();
            if (kids != null) for (java.io.File f : kids) {
                if (!f.isFile()) continue;
                String fn = f.getName();
                if (!fn.toLowerCase().endsWith(".txt")) continue;
                Map<String, Object> m = byName.get(fn);
                if (m == null) {
                    m = new LinkedHashMap<String, Object>();
                    m.put("path", fn); m.put("name", fn); m.put("kind", "document"); m.put("alias", null);
                    byName.put(fn, m);
                }
                m.put("size", f.length());
                m.put("modified", f.lastModified());
                m.put("source", "external");
                m.put("managed", Boolean.TRUE);
            }
        }
        java.util.List<Map<String, Object>> files =
                new java.util.ArrayList<Map<String, Object>>(byName.values());
        files.sort((a, b) -> String.valueOf(a.get("name")).compareTo(String.valueOf(b.get("name"))));
        out.put("ok", true);
        out.put("external", dir);
        out.put("dir", dir == null
                ? "(solo bundled nel WAR - imposta orchestrator.mask-pools-dir per sostituirli)"
                : dir);
        out.put("files", files);
        return ResponseEntity.ok(out);
    }

    @GetMapping("/api/mask/pools/download")
    public void poolDownload(@RequestParam("path") String path,
                             javax.servlet.http.HttpServletResponse resp) throws java.io.IOException {
        String name = safeName(path);
        if (name == null) { resp.setStatus(400); return; }
        com.legalarchive.orchestrator.mask.MaskPools pools =
                new com.legalarchive.orchestrator.mask.MaskPools(props.getMaskPoolsDir());
        String raw = pools.readRaw(name);
        if (raw == null) { resp.setStatus(404); return; }
        resp.setContentType("text/plain; charset=UTF-8");
        resp.setHeader("Content-Disposition", "inline; filename=\"" + name + "\"");
        resp.getOutputStream().write(raw.getBytes(StandardCharsets.UTF_8));
        resp.getOutputStream().flush();
    }

    @PostMapping("/api/mask/pools/files")
    public ResponseEntity<Map<String, Object>> poolUpload(
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        String dir = poolDir();
        if (dir == null) return badRequest(out,
                "Nessuna cartella esterna configurata: imposta orchestrator.mask-pools-dir nell'application.properties per poter sostituire i pool.");
        if (file == null || file.isEmpty()) return badRequest(out, "No file provided");
        String name = safeName(file.getOriginalFilename());
        if (name == null) return badRequest(out, "Invalid file name: " + file.getOriginalFilename());
        if (!name.toLowerCase().endsWith(".txt")) name = name + ".txt";
        try {
            java.io.File d = new java.io.File(dir);
            java.nio.file.Files.createDirectories(d.toPath());
            java.io.InputStream in = file.getInputStream();
            try {
                java.nio.file.Files.copy(in, new java.io.File(d, name).toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } finally { in.close(); }
            out.put("ok", true); out.put("name", name); out.put("dir", dir);
            return ResponseEntity.ok(out);
        } catch (Exception e) { return badRequest(out, e.getMessage()); }
    }

    @PostMapping("/api/mask/pools/files/create")
    public ResponseEntity<Map<String, Object>> poolCreate(@RequestParam("name") String name,
            @RequestParam(value = "content", required = false) String content) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        String dir = poolDir();
        if (dir == null) return badRequest(out, "Nessuna cartella esterna configurata: imposta orchestrator.mask-pools-dir.");
        String fn = safeName(name);
        if (fn == null) return badRequest(out, "Nome file non valido");
        if (!fn.toLowerCase().endsWith(".txt")) fn = fn + ".txt";
        try {
            java.io.File d = new java.io.File(dir);
            java.nio.file.Files.createDirectories(d.toPath());
            java.nio.file.Files.write(new java.io.File(d, fn).toPath(),
                    (content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            out.put("ok", true); out.put("name", fn);
            return ResponseEntity.ok(out);
        } catch (Exception e) { return badRequest(out, e.getMessage()); }
    }

    @PostMapping("/api/mask/pools/files/delete")
    public ResponseEntity<Map<String, Object>> poolDelete(@RequestParam("path") String path) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        String dir = poolDir();
        if (dir == null) return badRequest(out, "Nessuna cartella esterna configurata.");
        String name = safeName(path);
        if (name == null) return badRequest(out, "Nome non valido");
        java.io.File f = new java.io.File(dir, name);
        if (!f.isFile()) return badRequest(out,
                "Override esterno non presente per '" + name + "' (il file bundled nel WAR resta disponibile).");
        boolean ok = f.delete();
        out.put("ok", ok);
        if (!ok) out.put("error", "Impossibile eliminare il file");
        return ResponseEntity.ok(out);
    }

    @GetMapping("/api/mask/pools/alias-suggest")
    public Map<String, Object> poolAlias(@RequestParam("file") String file) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.put("alias", safeName(file));
        return out;
    }

    // ---- shared helpers (feedId == null => app scope) ----
    private java.nio.file.Path scope(String feedId) {
        if (feedId == null) return assets.sharedDir();
        FeedLayout l = registry.layout(feedId);
        return l == null ? null : l.feedDir;
    }

    private ResponseEntity<Map<String, Object>> doList(String feedId, boolean rootOnly) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        java.nio.file.Path base = scope(feedId);
        if (base == null) return badRequest(out, "Unknown scope");
        java.util.List<Map<String, Object>> files = new java.util.ArrayList<Map<String, Object>>();
        try {
            java.io.File dir = base.toFile();
            if (dir.isDirectory()) {
                if (rootOnly) collectRoot(base, dir, files, feedId);   // only files directly in the feed root (uploaded/created in design)
                else collect(base, dir, files, feedId);
            }
            files.sort((a, b) -> String.valueOf(a.get("path")).compareTo(String.valueOf(b.get("path"))));
            out.put("ok", true);
            out.put("dir", base.toString());
            out.put("files", files);
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return badRequest(out, e.getMessage());
        }
    }

    /** Only the files placed directly in the feed root (what the user uploads/creates in the designer),
        excluding run-generated subfolders (00_landing_in, _logs, _runs, …) and internal files. */
    private void collectRoot(java.nio.file.Path base, java.io.File dir,
                             java.util.List<Map<String, Object>> acc, String feedId) {
        java.io.File[] kids = dir.listFiles();
        if (kids == null) return;
        for (java.io.File f : kids) {
            if (f.isDirectory()) continue;
            String fn = f.getName();
            if (fn.equals("_assets.json") || fn.endsWith(".tmp")) continue;
            String rel = base.relativize(f.toPath()).toString().replace('\\', '/');
            AssetStore.Asset meta = assets.find(feedId, fn);
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("path", rel);
            m.put("name", fn);
            m.put("size", f.length());
            m.put("modified", f.lastModified());
            m.put("kind", meta != null ? meta.kind : "document");
            m.put("alias", meta != null ? meta.alias : null);
            m.put("managed", meta != null);
            acc.add(m);
        }
    }

    private void collect(java.nio.file.Path base, java.io.File dir,
                         java.util.List<Map<String, Object>> acc, String feedId) {
        java.io.File[] kids = dir.listFiles();
        if (kids == null) return;
        for (java.io.File f : kids) {
            String fn = f.getName();
            if (f.isDirectory()) {
                if (fn.equals("_logs") || fn.equals("_runs")) continue;  // internal
                collect(base, f, acc, feedId);
            } else {
                if (fn.equals("_assets.json") || fn.endsWith(".tmp")) continue;
                String rel = base.relativize(f.toPath()).toString().replace('\\', '/');
                AssetStore.Asset meta = rel.indexOf('/') < 0 ? assets.find(feedId, fn) : null;
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("path", rel);
                m.put("name", fn);
                m.put("size", f.length());
                m.put("modified", f.lastModified());
                m.put("kind", meta != null ? meta.kind : (rel.indexOf('/') < 0 ? null : "output"));
                m.put("alias", meta != null ? meta.alias : null);
                m.put("managed", meta != null);
                acc.add(m);
            }
        }
    }

    private ResponseEntity<Map<String, Object>> doUpload(String feedId,
            org.springframework.web.multipart.MultipartFile file, String kind, String alias, HttpServletRequest req) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        java.nio.file.Path base = scope(feedId);
        if (base == null) return badRequest(out, "Unknown scope");
        if (file == null || file.isEmpty()) return badRequest(out, "No file provided");

        String name = safeName(file.getOriginalFilename());
        if (name == null) return badRequest(out, "Invalid file name: " + file.getOriginalFilename());
        String k = "script".equalsIgnoreCase(kind) ? "script" : "document";

        String finalAlias = null;
        if ("script".equals(k)) {
            if (alias == null || alias.trim().isEmpty()) return badRequest(out, "Alias is required for an executable (script) file");
            finalAlias = AssetStore.sanitizeAlias(alias.trim());
            // uniqueness: ignore the alias currently held by this same file (re-upload)
            AssetStore.Asset existing = assets.find(feedId, name);
            boolean sameFileSameAlias = existing != null && finalAlias.equals(existing.alias);
            if (!sameFileSameAlias && assets.aliasTaken(finalAlias, feedId)) {
                return badRequest(out, "Alias '" + finalAlias + "' is already in use (app or this feed). Choose another.");
            }
        }

        try {
            java.nio.file.Files.createDirectories(base);
            java.io.InputStream in = file.getInputStream();
            try {
                java.nio.file.Files.copy(in, base.resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } finally { in.close(); }

            AssetStore.Asset a = new AssetStore.Asset();
            a.fileName = name; a.kind = k; a.alias = finalAlias;
            assets.put(feedId, a);

            auditUpload(feedId, "FILE_UPLOADED", user(req), name, k, finalAlias);
            out.put("ok", true);
            out.put("name", name);
            out.put("kind", k);
            out.put("alias", finalAlias);
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return badRequest(out, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    /** Create (or overwrite) a text file from pasted content. Same classification as upload. */
    private ResponseEntity<Map<String, Object>> doCreate(String feedId, String name, String content,
                                                         String kind, String alias, HttpServletRequest req) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        java.nio.file.Path base = scope(feedId);
        if (base == null) return badRequest(out, "Unknown scope");
        String safe = safeName(name);
        if (safe == null) return badRequest(out, "Invalid file name: " + name);
        String k = "script".equalsIgnoreCase(kind) ? "script" : "document";

        String finalAlias = null;
        if ("script".equals(k)) {
            if (alias == null || alias.trim().isEmpty()) return badRequest(out, "Alias is required for an executable (script) file");
            finalAlias = AssetStore.sanitizeAlias(alias.trim());
            AssetStore.Asset existing = assets.find(feedId, safe);
            boolean sameFileSameAlias = existing != null && finalAlias.equals(existing.alias);
            if (!sameFileSameAlias && assets.aliasTaken(finalAlias, feedId)) {
                return badRequest(out, "Alias '" + finalAlias + "' is already in use (app or this feed). Choose another.");
            }
        }
        try {
            java.nio.file.Files.createDirectories(base);
            byte[] bytes = (content == null ? "" : content).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            java.nio.file.Files.write(base.resolve(safe), bytes,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                    java.nio.file.StandardOpenOption.WRITE);

            AssetStore.Asset a = new AssetStore.Asset();
            a.fileName = safe; a.kind = k; a.alias = finalAlias;
            assets.put(feedId, a);

            auditUpload(feedId, "FILE_CREATED", user(req), safe, k, finalAlias);
            out.put("ok", true);
            out.put("name", safe);
            out.put("kind", k);
            out.put("alias", finalAlias);
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return badRequest(out, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private ResponseEntity<Map<String, Object>> doDelete(String feedId, String relPath, HttpServletRequest req) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        java.nio.file.Path base = scope(feedId);
        if (base == null) return badRequest(out, "Unknown scope");
        java.nio.file.Path target = safeResolve(base, relPath);
        if (target == null) return badRequest(out, "Invalid path");
        try {
            boolean deleted = java.nio.file.Files.deleteIfExists(target);
            if (deleted) {
                if (target.getParent().equals(base)) assets.remove(feedId, target.getFileName().toString());
                auditUpload(feedId, "FILE_DELETED", user(req), relPath, null, null);
            }
            out.put("ok", deleted);
            if (!deleted) out.put("error", "File not found");
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return badRequest(out, e.getMessage());
        }
    }

    private void serve(String feedId, String relPath, javax.servlet.http.HttpServletResponse resp) throws java.io.IOException {
        java.nio.file.Path base = scope(feedId);
        java.nio.file.Path target = base == null ? null : safeResolve(base, relPath);
        if (target == null || !java.nio.file.Files.isRegularFile(target)) {
            resp.setStatus(404);
            return;
        }
        resp.setContentType("application/octet-stream");
        resp.setHeader("Content-Disposition", "attachment; filename=\"" + target.getFileName().toString() + "\"");
        java.nio.file.Files.copy(target, resp.getOutputStream());
        resp.getOutputStream().flush();
    }

    private Map<String, Object> aliasSuggest(String feedId, String file) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("alias", assets.suggestAlias(file, feedId));
        return out;
    }

    private void auditUpload(String feedId, String event, String user, String name, String kind, String alias) {
        java.nio.file.Path auditFile;
        if (feedId == null) {
            auditFile = assets.sharedDir().resolve("_audit_shared.jsonl");
        } else {
            FeedLayout l = registry.layout(feedId);
            if (l == null) return;
            auditFile = l.auditFile();
        }
        Map<String, String> d = new LinkedHashMap<String, String>();
        d.put("name", name);
        if (kind != null) d.put("kind", kind);
        if (alias != null) d.put("alias", alias);
        try { audit.log(auditFile, feedId == null ? "_shared" : feedId, null, null, event, user, d); } catch (Exception ignored) {}
    }

    // ===================================================== server-side CSV/text
    @GetMapping("/api/workflows/{feedId}/csv/meta")
    public ResponseEntity<Map<String, Object>> csvMetaF(@PathVariable String feedId, @RequestParam("path") String path) { return csvMeta(feedId, path); }
    @GetMapping("/api/shared/csv/meta")
    public ResponseEntity<Map<String, Object>> csvMetaS(@RequestParam("path") String path) { return csvMeta(null, path); }

    @GetMapping("/api/workflows/{feedId}/csv/page")
    public ResponseEntity<Map<String, Object>> csvPageF(@PathVariable String feedId, @RequestParam("path") String path,
            @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "200") int limit,
            @RequestParam(required = false) String q) { return csvPage(feedId, path, offset, limit, q); }
    @GetMapping("/api/shared/csv/page")
    public ResponseEntity<Map<String, Object>> csvPageS(@RequestParam("path") String path,
            @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "200") int limit,
            @RequestParam(required = false) String q) { return csvPage(null, path, offset, limit, q); }

    @GetMapping("/api/workflows/{feedId}/csv/aggregate")
    public ResponseEntity<Map<String, Object>> csvAggF(@PathVariable String feedId, @RequestParam("path") String path,
            @RequestParam(required = false) String group, @RequestParam(required = false) String distinct,
            @RequestParam(required = false) String q) { return csvAgg(feedId, path, group, distinct, q); }
    @GetMapping("/api/shared/csv/aggregate")
    public ResponseEntity<Map<String, Object>> csvAggS(@RequestParam("path") String path,
            @RequestParam(required = false) String group, @RequestParam(required = false) String distinct,
            @RequestParam(required = false) String q) { return csvAgg(null, path, group, distinct, q); }

    @GetMapping("/api/workflows/{feedId}/text/meta")
    public ResponseEntity<Map<String, Object>> txtMetaF(@PathVariable String feedId, @RequestParam("path") String path) { return txtMeta(feedId, path); }
    @GetMapping("/api/shared/text/meta")
    public ResponseEntity<Map<String, Object>> txtMetaS(@RequestParam("path") String path) { return txtMeta(null, path); }

    @GetMapping("/api/workflows/{feedId}/text/page")
    public ResponseEntity<Map<String, Object>> txtPageF(@PathVariable String feedId, @RequestParam("path") String path,
            @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "500") int limit) { return txtPage(feedId, path, offset, limit); }
    @GetMapping("/api/shared/text/page")
    public ResponseEntity<Map<String, Object>> txtPageS(@RequestParam("path") String path,
            @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "500") int limit) { return txtPage(null, path, offset, limit); }

    @PostMapping("/api/workflows/{feedId}/files/save")
    public ResponseEntity<Map<String, Object>> saveF(@PathVariable String feedId, @RequestParam("path") String path,
            @RequestParam(value = "content", required = false) String content, HttpServletRequest req) { return doSave(feedId, path, content, req); }
    @PostMapping("/api/shared/files/save")
    public ResponseEntity<Map<String, Object>> saveS(@RequestParam("path") String path,
            @RequestParam(value = "content", required = false) String content, HttpServletRequest req) { return doSave(null, path, content, req); }

    @GetMapping("/api/audit/{feedId}/entries")
    public ResponseEntity<Map<String, Object>> auditEntries(@PathVariable String feedId) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        FeedLayout layout = registry.layout(feedId);
        if (layout == null) return badRequest(out, "Unknown workflow: " + feedId);
        java.util.List<Object> events = new java.util.ArrayList<Object>();
        try {
            java.io.File af = layout.auditFile().toFile();
            if (af.exists()) {
                // bounded memory: keep only the most recent events
                final int MAX_EVENTS = 5000;
                java.util.ArrayDeque<String> dq = new java.util.ArrayDeque<String>(1024);
                java.io.BufferedReader br = java.nio.file.Files.newBufferedReader(af.toPath(), java.nio.charset.StandardCharsets.UTF_8);
                try {
                    String ln;
                    while ((ln = br.readLine()) != null) {
                        if (ln.trim().isEmpty()) continue;
                        if (dq.size() >= MAX_EVENTS) dq.pollFirst();
                        dq.addLast(ln);
                    }
                } finally { br.close(); }
                for (String ln : dq) { try { events.add(mapper.readValue(ln, Map.class)); } catch (Exception ignore) {} }
            }
            out.put("ok", true);
            out.put("events", events);
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return badRequest(out, e.getMessage());
        }
    }

    private java.io.File csvFile(String feedId, String path, Map<String, Object> out) {
        java.nio.file.Path base = scope(feedId);
        if (base == null) { out.put("error", "Unknown scope"); return null; }
        java.nio.file.Path t = safeResolve(base, path);
        if (t == null || !java.nio.file.Files.isRegularFile(t)) { out.put("error", "File not found"); return null; }
        return t.toFile();
    }

    private ResponseEntity<Map<String, Object>> csvMeta(String feedId, String path) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        java.io.File f = csvFile(feedId, path, out);
        if (f == null) return badRequest(out, String.valueOf(out.get("error")));
        try {
            CsvService.Meta m = csv.meta(f);
            out.put("ok", true); out.put("columns", m.columns); out.put("totalRows", m.totalRows); out.put("delimiter", m.delimiter);
            return ResponseEntity.ok(out);
        } catch (Exception e) { return badRequest(out, e.getMessage()); }
    }

    private ResponseEntity<Map<String, Object>> csvPage(String feedId, String path, int offset, int limit, String q) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        java.io.File f = csvFile(feedId, path, out);
        if (f == null) return badRequest(out, String.valueOf(out.get("error")));
        try {
            CsvService.Page p = csv.page(f, offset, Math.min(limit, 1000), q);
            out.put("ok", true); out.put("rows", p.rows); out.put("total", p.total);
            return ResponseEntity.ok(out);
        } catch (Exception e) { return badRequest(out, e.getMessage()); }
    }

    private ResponseEntity<Map<String, Object>> csvAgg(String feedId, String path, String group, String distinct, String q) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        java.io.File f = csvFile(feedId, path, out);
        if (f == null) return badRequest(out, String.valueOf(out.get("error")));
        String[] g = parseSpecs(group), dd = parseSpecs(distinct);
        if (g.length == 0 && dd.length == 0) return badRequest(out, "Select at least one group or distinct column");
        try {
            CsvService.Agg a = csv.aggregate(f, g, dd, q);
            out.put("ok", true);
            out.put("groupColumns", a.groupColumns);
            out.put("distinctColumns", a.distinctColumns);
            out.put("groups", a.groups);
            out.put("scanned", a.scanned);
            out.put("truncated", a.truncated);
            out.put("truncatedDistinct", a.truncatedDistinct);
            out.put("totalCount", a.totalCount);
            out.put("totalDistinct", a.totalDistinct);
            out.put("distinctGroups", a.groups.size());
            return ResponseEntity.ok(out);
        } catch (Exception e) { return badRequest(out, e.getMessage()); }
    }

    private ResponseEntity<Map<String, Object>> txtMeta(String feedId, String path) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        java.io.File f = csvFile(feedId, path, out);
        if (f == null) return badRequest(out, String.valueOf(out.get("error")));
        try {
            CsvService.Meta m = csv.meta(f);
            out.put("ok", true); out.put("totalLines", m.totalLines); out.put("size", f.length());
            return ResponseEntity.ok(out);
        } catch (Exception e) { return badRequest(out, e.getMessage()); }
    }

    private ResponseEntity<Map<String, Object>> txtPage(String feedId, String path, int offset, int limit) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        java.io.File f = csvFile(feedId, path, out);
        if (f == null) return badRequest(out, String.valueOf(out.get("error")));
        try {
            CsvService.Page p = csv.lines(f, offset, Math.min(limit, 2000));
            java.util.List<String> ls = new java.util.ArrayList<String>();
            for (java.util.List<String> r : p.rows) ls.add(r.isEmpty() ? "" : r.get(0));
            out.put("ok", true); out.put("lines", ls); out.put("total", p.total);
            return ResponseEntity.ok(out);
        } catch (Exception e) { return badRequest(out, e.getMessage()); }
    }

    private ResponseEntity<Map<String, Object>> doSave(String feedId, String relPath, String content, HttpServletRequest req) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        java.nio.file.Path base = scope(feedId);
        if (base == null) return badRequest(out, "Unknown scope");
        java.nio.file.Path target = safeResolve(base, relPath);
        if (target == null) return badRequest(out, "Invalid path");
        try {
            if (target.getParent() != null) java.nio.file.Files.createDirectories(target.getParent());
            byte[] bytes = (content == null ? "" : content).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            java.nio.file.Files.write(target, bytes,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                    java.nio.file.StandardOpenOption.WRITE);
            auditUpload(feedId, "FILE_EDITED", user(req), relPath, null, null);
            out.put("ok", true);
            return ResponseEntity.ok(out);
        } catch (Exception e) { return badRequest(out, e.getMessage()); }
    }

    private String[] parseSpecs(String csvList) {
        if (csvList == null || csvList.trim().isEmpty()) return new String[0];
        String[] parts = csvList.split(",");
        java.util.List<String> l = new java.util.ArrayList<String>();
        for (String p : parts) { String t = p.trim(); if (!t.isEmpty()) l.add(t); }
        return l.toArray(new String[0]);
    }

    private int[] parseInts(String csvList) {
        if (csvList == null || csvList.trim().isEmpty()) return new int[0];
        String[] parts = csvList.split(",");
        java.util.List<Integer> l = new java.util.ArrayList<Integer>();
        for (String p : parts) { try { l.add(Integer.parseInt(p.trim())); } catch (NumberFormatException ignored) {} }
        int[] a = new int[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }

    /** Strip path components, reject traversal; returns null if not a safe single file name. */
    private String safeName(String n) {
        if (n == null) return null;
        String base = new java.io.File(n.trim().replace('\\', '/')).getName();
        if (base.isEmpty() || base.equals(".") || base.equals("..") || base.contains("/")) return null;
        return base;
    }

    /** Resolve a relative path inside base, guaranteeing it cannot escape base. */
    private java.nio.file.Path safeResolve(java.nio.file.Path base, String rel) {
        if (rel == null || rel.trim().isEmpty()) return null;
        java.nio.file.Path target = base.resolve(rel.replace('\\', '/')).normalize();
        return target.startsWith(base) ? target : null;
    }

    private Map<String, String> kvMap(String... kv) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> tooLarge(MaxUploadSizeExceededException e) {
        return badRequest(new LinkedHashMap<String, Object>(),
                "File too large. Raise spring.servlet.multipart.max-file-size / max-request-size.");
    }

    private ResponseEntity<Map<String, Object>> badRequest(Map<String, Object> out, String error) {
        out.put("ok", false);
        out.put("error", error);
        return ResponseEntity.badRequest().body(out);
    }

    private String user(HttpServletRequest req) {
        String u = req.getHeader("X-User");
        if (u == null || u.trim().isEmpty()) u = req.getRemoteUser();
        if (u == null || u.trim().isEmpty()) u = "operator@" + req.getRemoteAddr();
        return u;
    }
}
