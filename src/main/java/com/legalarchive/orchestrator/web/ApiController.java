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
    private final DataSourceStore dataSources;
    private final SqlSupport sql;
    private final AssetStore assets;
    private final CsvService csv;
    private final ObjectMapper mapper = new ObjectMapper();
    private final WorkflowXmlWriter xmlWriter = new WorkflowXmlWriter();
    private final WorkflowXmlParser xmlParser = new WorkflowXmlParser();

    public ApiController(WorkflowRegistry registry, WorkflowEngine engine, RunStore store,
                         AuditLogger audit, WorkflowScheduler scheduler, AppProperties props,
                         DataSourceStore dataSources, SqlSupport sql, AssetStore assets, CsvService csv) {
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

    /** Designer: current definition of a workflow as JSON. */
    @GetMapping("/api/workflows/{feedId}/definition")
    public ResponseEntity<WorkflowDto> definition(@PathVariable String feedId) {
        WorkflowDef def = registry.get(feedId);
        if (def == null) return ResponseEntity.notFound().build();
        WorkflowDto dto = new WorkflowDto();
        dto.feedId = def.feedId;
        dto.sourceId = def.sourceId;
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
                nd.delimiter = st.delimiter;
                nd.forEach = st.forEach;
                nd.concurrency = st.concurrency != 4 ? st.concurrency : null;
                for (Map.Entry<String, String> p : st.params.entrySet()) {
                    nd.params.add(new WorkflowDto.KV(p.getKey(), p.getValue()));
                }
                nd.outputs.addAll(st.outputs);
            } else {
                GateDef g = (GateDef) n;
                nd.type = g.type;
                nd.condition = g.condition;
                nd.onTrue = g.onTrue;
                nd.onFalse = g.onFalse;
            }
            dto.nodes.add(nd);
        }
        return ResponseEntity.ok(dto);
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
