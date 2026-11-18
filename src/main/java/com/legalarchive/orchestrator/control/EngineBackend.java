package com.legalarchive.orchestrator.control;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.legalarchive.orchestrator.audit.AuditLogger;
import com.legalarchive.orchestrator.engine.WorkflowEngine;
import com.legalarchive.orchestrator.engine.WorkflowScheduler;
import com.legalarchive.orchestrator.model.def.WorkflowDef;
import com.legalarchive.orchestrator.model.run.GateExec;
import com.legalarchive.orchestrator.model.run.StepExec;
import com.legalarchive.orchestrator.model.run.WorkflowRun;
import com.legalarchive.orchestrator.registry.WorkflowRegistry;
import com.legalarchive.orchestrator.store.FeedLayout;
import com.legalarchive.orchestrator.store.RunStore;

/**
 * Executes control requests against the real engine (spec {@code .claude/HEADLESS.md} section 6.2).
 *
 * <p>Every command calls what the corresponding GUI button calls, with the same refusals, so a run
 * started here is the run the Run button would have started (section 8). The differences are the
 * user - the OS owner of the request file - and the extra audit details, which say the request came
 * from the CLI. Nothing here changes a definition or a setting.
 */
@Component
public class EngineBackend implements ControlBackend {

    private final WorkflowEngine engine;
    private final WorkflowRegistry registry;
    private final RunStore store;
    private final AuditLogger audit;
    private final WorkflowScheduler scheduler;

    public EngineBackend(WorkflowEngine engine, WorkflowRegistry registry, RunStore store,
                         AuditLogger audit, WorkflowScheduler scheduler) {
        this.engine = engine;
        this.registry = registry;
        this.store = store;
        this.audit = audit;
        this.scheduler = scheduler;
    }

    @Override
    public ControlProtocol.Response execute(ControlProtocol.Request r, String user, Map<String, String> extra) {
        String c = r.command == null ? "" : r.command;
        switch (c) {
            case "ping": return ping();
            case "feeds": return feeds();
            case "runs": return runs(r);
            case "status": return status(r);
            case "log": return log(r);
            case "run": return run(r, user, extra);
            case "stop": return stop(r, user, extra);
            case "approve": return decide(r, true, user, extra);
            case "reject": return decide(r, false, user, extra);
            case "resume": return resume(r, user, extra);
            case "reload": return reload();
            case "audit-verify": return auditVerify(r);
            default: return ControlProtocol.Response.refused(ExitCodes.USAGE, "unknown command '" + c + "'");
        }
    }

    @Override
    public void auditExpired(ControlProtocol.Request r, String user, Map<String, String> extra) {
        if (r.feedId == null) return;
        FeedLayout layout = registry.layout(r.feedId);
        if (layout == null) return;
        Map<String, String> d = new LinkedHashMap<String, String>();
        d.put("command", r.command == null ? "" : r.command);
        d.putAll(extra);
        audit.log(layout.auditFile(), r.feedId, validRunId(r.runId) ? r.runId : null, null, "CLI_REQUEST_EXPIRED", user, d);
    }

    // ------------------------------------------------------------------ reads

    private ControlProtocol.Response ping() {
        Map<String, Object> d = new LinkedHashMap<String, Object>();
        d.put("workflows", registry.all().size());
        d.put("activeRuns", engine.activeRunCount());
        d.put("queuedRuns", engine.pendingRunCount());
        return ControlProtocol.Response.ok(d);
    }

    private ControlProtocol.Response feeds() {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        Map<String, String> cronErrors = scheduler.errors();
        for (WorkflowDef def : registry.all()) {
            Map<String, Object> f = new LinkedHashMap<String, Object>();
            f.put("feedId", def.feedId);
            f.put("name", def.name);
            f.put("production", def.production);
            f.put("locked", def.locked);
            f.put("cron", def.cron == null ? "" : def.cron);
            f.put("scheduled", scheduler.isScheduled(def.feedId));
            if (cronErrors.containsKey(def.feedId)) f.put("cronError", cronErrors.get(def.feedId));
            f.put("activeRunId", engine.activeRunId(def.feedId));
            FeedLayout layout = registry.layout(def.feedId);
            WorkflowRun last = null;
            if (layout != null) {
                for (WorkflowRun run : store.list(layout, 10)) {
                    if (run.runId != null && !run.runId.contains("_test_")) { last = run; break; }
                }
            }
            f.put("lastRun", last == null ? null : summary(fresh(last)));
            rows.add(f);
        }
        Map<String, Object> d = new LinkedHashMap<String, Object>();
        d.put("feeds", rows);
        d.put("loadErrors", registry.errors());
        return ControlProtocol.Response.ok(d);
    }

    private ControlProtocol.Response runs(ControlProtocol.Request r) {
        FeedLayout layout = layoutOf(r.feedId);
        if (layout == null) return unknownFeed(r.feedId);
        int limit = r.limit == null || r.limit <= 0 ? 20 : Math.min(r.limit, 500);
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (WorkflowRun run : store.list(layout, limit)) rows.add(summary(fresh(run)));
        Map<String, Object> d = new LinkedHashMap<String, Object>();
        d.put("feedId", r.feedId);
        d.put("runs", rows);
        return ControlProtocol.Response.ok(d);
    }

    private ControlProtocol.Response status(ControlProtocol.Request r) {
        WorkflowRun run = runOf(r);
        if (run == null) return unknownRun(r);
        ControlProtocol.Response resp = ControlProtocol.Response.ok(detail(run));
        resp.runId = run.runId;
        return resp;
    }

    /**
     * A step log, as the GUI's plain log shows it. Without {@code offset}: the last {@code tail}
     * lines. With it: the lines after that line number, for {@code --follow}. Per-item logs of a
     * forEach step are addressed as {@code <stepId>__<n>}.
     */
    private ControlProtocol.Response log(ControlProtocol.Request r) {
        WorkflowRun run = runOf(r);
        if (run == null) return unknownRun(r);
        FeedLayout layout = registry.layout(r.feedId);
        Map<String, Object> d = new LinkedHashMap<String, Object>();
        d.put("runStatus", run.status == null ? "" : run.status.name());
        if (r.stepId == null || r.stepId.isEmpty()) {
            List<Map<String, Object>> steps = new ArrayList<Map<String, Object>>();
            for (StepExec s : run.steps) {
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("stepId", s.stepId);
                m.put("name", s.name);
                m.put("status", s.status == null ? "" : s.status.name());
                m.put("hasLog", Files.exists(layout.stepLog(run.runId, s.stepId)));
                steps.add(m);
            }
            d.put("steps", steps);
            return ControlProtocol.Response.ok(d);
        }
        if (!ControlProtocol.SIMPLE_ID.matcher(r.stepId).matches()) {
            return ControlProtocol.Response.refused(ExitCodes.USAGE, "invalid step id");
        }
        Path file = layout.stepLog(run.runId, r.stepId);
        d.put("stepId", r.stepId);
        if (!Files.exists(file)) {
            d.put("exists", false);
            d.put("lines", new ArrayList<String>());
            d.put("nextOffset", r.offset == null ? 0L : r.offset);
            return ControlProtocol.Response.ok(d);
        }
        long from = r.offset == null ? -1L : Math.max(0L, r.offset);
        int cap = r.tail == null || r.tail <= 0 ? 200 : Math.min(r.tail, 20000);
        ArrayDeque<String> out = new ArrayDeque<String>();
        long total = 0;
        try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String ln;
            while ((ln = br.readLine()) != null) {
                total++;
                if (from >= 0) {
                    if (total > from && out.size() < 20000) out.addLast(format(ln));
                } else {
                    if (out.size() >= cap) out.pollFirst();
                    out.addLast(format(ln));
                }
            }
        } catch (Exception e) {
            return ControlProtocol.Response.refused(ExitCodes.INSTANCE_ERROR, "the log could not be read: " + e.getMessage());
        }
        d.put("exists", true);
        d.put("totalLines", total);
        d.put("lines", new ArrayList<String>(out));
        d.put("nextOffset", from >= 0 ? Math.min(total, from + out.size()) : total);
        return ControlProtocol.Response.ok(d);
    }

    /** "STREAM\tTS\tcontent" as the GUI's plain log shows it: time, [ERR]/[SYS] tag, content. */
    static String format(String raw) {
        int t1 = raw.indexOf('\t');
        if (t1 == 1 && (raw.charAt(0) == 'O' || raw.charAt(0) == 'E' || raw.charAt(0) == 'S')) {
            int t2 = raw.indexOf('\t', t1 + 1);
            if (t2 > t1) {
                String ts = raw.substring(t1 + 1, t2);
                String tag = raw.charAt(0) == 'E' ? "[ERR] " : (raw.charAt(0) == 'S' ? "[SYS] " : "");
                return (ts.isEmpty() ? "" : ts + "  ") + tag + raw.substring(t2 + 1);
            }
        }
        return raw;
    }

    private ControlProtocol.Response auditVerify(ControlProtocol.Request r) {
        FeedLayout layout = layoutOf(r.feedId);
        if (layout == null) return unknownFeed(r.feedId);
        AuditLogger.VerifyResult v = audit.verify(layout.auditFile());
        Map<String, Object> d = new LinkedHashMap<String, Object>();
        d.put("valid", v.valid);
        d.put("entries", v.entries);
        d.put("brokenAtSeq", v.brokenAtSeq);
        d.put("message", v.message);
        ControlProtocol.Response resp = ControlProtocol.Response.ok(d);
        if (!v.valid) { resp.ok = false; resp.code = ExitCodes.RUN_FAILED; resp.error = v.message; }
        return resp;
    }

    // ------------------------------------------------------------------ actions

    private ControlProtocol.Response run(ControlProtocol.Request r, String user, Map<String, String> extra) {
        WorkflowDef def = r.feedId == null ? null : registry.get(r.feedId);
        if (def == null) return unknownFeed(r.feedId);
        // as POST /api/workflows/{feedId}/run: a locked feed is refused before the engine, unaudited
        if (def.locked) {
            return ControlProtocol.Response.refused(ExitCodes.LOCKED, "This feed is locked for maintenance. Unlock it to run.");
        }
        Map<String, String> vars = r.vars == null ? new LinkedHashMap<String, String>() : r.vars;
        Map<String, String> details = new LinkedHashMap<String, String>(extra);
        for (Map.Entry<String, String> e : vars.entrySet()) details.put("var." + e.getKey(), e.getValue());   // Q5: name and value
        WorkflowRun run;
        try {
            run = engine.start(r.feedId, "MANUAL", user, vars, details);
        } catch (WorkflowEngine.InvalidVariablesException e) {
            return ControlProtocol.Response.refused(ExitCodes.INVALID_VARIABLE, e.getMessage());
        } catch (IllegalArgumentException e) {
            return unknownFeed(r.feedId);
        }
        if (run == null) return ControlProtocol.Response.refused(ExitCodes.CONFLICT, "A run is already active for this feed");
        ControlProtocol.Response resp = ControlProtocol.Response.ok(summary(run));
        resp.runId = run.runId;
        return resp;
    }

    private ControlProtocol.Response stop(ControlProtocol.Request r, String user, Map<String, String> extra) {
        WorkflowRun run = runOf(r);
        if (run == null) return unknownRun(r);
        if (WorkflowEngine.isTerminalStatus(run.status)) {
            return ControlProtocol.Response.refused(ExitCodes.CONFLICT, "The run has already ended: " + run.status);
        }
        if (!engine.stop(r.feedId, r.runId, user, extra)) {
            return ControlProtocol.Response.refused(ExitCodes.CONFLICT, "The run cannot be stopped");
        }
        return done(r);
    }

    private ControlProtocol.Response decide(ControlProtocol.Request r, boolean approve, String user, Map<String, String> extra) {
        WorkflowRun run = runOf(r);
        if (run == null) return unknownRun(r);
        if (!engine.decide(r.feedId, r.runId, approve, user, r.note, extra)) {
            return ControlProtocol.Response.refused(ExitCodes.CONFLICT,
                    "The run is not waiting for a decision (status " + run.status + ")");
        }
        return done(r);
    }

    private ControlProtocol.Response resume(ControlProtocol.Request r, String user, Map<String, String> extra) {
        WorkflowRun run = runOf(r);
        if (run == null) return unknownRun(r);
        if (!engine.resumeHold(r.feedId, r.runId, user, extra)) {
            return ControlProtocol.Response.refused(ExitCodes.CONFLICT, "The run is not on hold (status " + run.status + ")");
        }
        return done(r);
    }

    /** As the Reload button: reread every workflow file and replan the cron. */
    private ControlProtocol.Response reload() {
        registry.reload();
        scheduler.reschedule();
        Map<String, Object> d = new LinkedHashMap<String, Object>();
        d.put("workflows", registry.all().size());
        d.put("errors", registry.errors());
        return ControlProtocol.Response.ok(d);
    }

    // ------------------------------------------------------------------ helpers

    private ControlProtocol.Response done(ControlProtocol.Request r) {
        WorkflowRun after = runOf(r);
        ControlProtocol.Response resp = ControlProtocol.Response.ok(after == null ? null : summary(after));
        resp.runId = r.runId;
        return resp;
    }

    private FeedLayout layoutOf(String feedId) {
        return feedId == null ? null : registry.layout(feedId);
    }

    private static boolean validRunId(String runId) {
        return runId != null && ControlProtocol.SIMPLE_ID.matcher(runId).matches();
    }

    /** The run as the engine holds it when it is live, else as its file says. Null when unknown. */
    private WorkflowRun runOf(ControlProtocol.Request r) {
        FeedLayout layout = layoutOf(r.feedId);
        if (layout == null || !validRunId(r.runId)) return null;
        WorkflowRun run = engine.activeRun(r.runId);
        if (run != null && r.feedId.equals(run.feedId)) return run;
        return store.load(layout, r.runId);
    }

    private WorkflowRun fresh(WorkflowRun fromFile) {
        WorkflowRun live = fromFile.runId == null ? null : engine.activeRun(fromFile.runId);
        return live != null ? live : fromFile;
    }

    private ControlProtocol.Response unknownFeed(String feedId) {
        return ControlProtocol.Response.refused(ExitCodes.UNKNOWN, "Unknown workflow: " + feedId);
    }

    private ControlProtocol.Response unknownRun(ControlProtocol.Request r) {
        if (layoutOf(r.feedId) == null) return unknownFeed(r.feedId);
        return ControlProtocol.Response.refused(ExitCodes.UNKNOWN, "Unknown run: " + r.runId);
    }

    private static Map<String, Object> summary(WorkflowRun run) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("runId", run.runId);
        m.put("feedId", run.feedId);
        m.put("status", run.status == null ? "" : run.status.name());
        m.put("trigger", run.trigger);
        m.put("triggeredBy", run.triggeredBy);
        m.put("startTs", run.startTs);
        m.put("endTs", run.endTs);
        m.put("message", run.message);
        return m;
    }

    private static Map<String, Object> detail(WorkflowRun run) {
        Map<String, Object> m = summary(run);
        m.put("workflowName", run.workflowName);
        m.put("waitingGateId", run.waitingGateId);
        m.put("pausedNextStep", run.pausedNextStep);
        m.put("onHoldStepId", run.onHoldStepId);
        List<Map<String, Object>> steps = new ArrayList<Map<String, Object>>();
        for (StepExec s : run.steps) {
            Map<String, Object> x = new LinkedHashMap<String, Object>();
            x.put("stepId", s.stepId);
            x.put("name", s.name);
            x.put("status", s.status == null ? "" : s.status.name());
            x.put("startTs", s.startTs);
            x.put("endTs", s.endTs);
            x.put("exitCode", s.exitCode);
            x.put("attempts", s.attempts);
            x.put("message", s.message);
            steps.add(x);
        }
        m.put("steps", steps);
        List<Map<String, Object>> gates = new ArrayList<Map<String, Object>>();
        for (GateExec g : run.gates) {
            Map<String, Object> x = new LinkedHashMap<String, Object>();
            x.put("gateId", g.gateId);
            x.put("name", g.name);
            x.put("type", g.type);
            x.put("result", g.result);
            x.put("decidedBy", g.decidedBy);
            x.put("ts", g.ts);
            gates.add(x);
        }
        m.put("gates", gates);
        return m;
    }
}
