package com.legalarchive.orchestrator.engine;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.legalarchive.orchestrator.audit.AuditLogger;
import com.legalarchive.orchestrator.config.AppProperties;
import com.legalarchive.orchestrator.model.def.GateDef;
import com.legalarchive.orchestrator.model.def.LoopDef;
import com.legalarchive.orchestrator.model.def.LoopEndDef;
import com.legalarchive.orchestrator.model.def.NodeDef;
import com.legalarchive.orchestrator.model.def.StepDef;
import com.legalarchive.orchestrator.model.def.WorkflowDef;
import com.legalarchive.orchestrator.model.run.GateExec;
import com.legalarchive.orchestrator.model.run.RunStatus;
import com.legalarchive.orchestrator.model.run.StepExec;
import com.legalarchive.orchestrator.model.run.StepStatus;
import com.legalarchive.orchestrator.model.run.WorkflowRun;
import com.legalarchive.orchestrator.registry.WorkflowRegistry;
import com.legalarchive.orchestrator.store.FeedLayout;
import com.legalarchive.orchestrator.store.AssetStore;
import com.legalarchive.orchestrator.store.RunStore;

/**
 * Motore di esecuzione: percorre i nodi del workflow in sequenza, esegue gli
 * step PowerShell, valuta i gate automatici e sospende il run sui gate manuali
 * (ripresi poi via approve/reject dalla UI). Ogni evento e' tracciato
 * nell'audit log del feed e lo stato del run e' persistito a ogni transizione.
 */
@Component
public class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter ID_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final AppProperties props;
    private final WorkflowRegistry registry;
    private final RunStore store;
    private final AuditLogger audit;
    private final InternalSteps internalSteps;
    private final AssetStore assets;
    private final com.legalarchive.orchestrator.store.GlobalVarsStore globalVars;
    // Coda FIFO globale a singolo worker: i run vengono serializzati - un workflow
    // parte solo quando il precedente e' terminato completamente. L'unica concorrenza
    // resta il fan-out forEach (esplicito) interno a uno step, che gira dentro al run
    // che occupa il worker, senza interleaving con altri run.
    // Admitted-run pool: concurrency is bounded by the admission logic (schedule()), not the pool size,
    // so a cached pool is fine. Each admitted task occupies one "active" slot until it returns
    // (finishes OR pauses at a manual gate, which frees the slot).
    private final ExecutorService runPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "wf-runner");
        t.setDaemon(true);
        return t;
    });
    // Periodic scheduler tick: re-checks resources and admits deferred queued runs ("decide next minute").
    private final java.util.concurrent.ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "wf-scheduler");
        t.setDaemon(true);
        return t;
    });
    // FIFO of runs waiting for an execution slot, and the count currently executing.
    private static final class Pending { final String runId; final Runnable task; Pending(String id, Runnable t) { runId = id; task = t; } }
    private final java.util.concurrent.ConcurrentLinkedQueue<Pending> pending = new java.util.concurrent.ConcurrentLinkedQueue<Pending>();
    private final java.util.concurrent.atomic.AtomicInteger active = new java.util.concurrent.atomic.AtomicInteger(0);

    // Pool dedicato ai TEST di singolo step lanciati dall'editor: gira FUORI dalla coda FIFO
    // principale, cosi' un test parte subito anche mentre altri feed sono in esecuzione.
    private final ExecutorService testExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "wf-test-runner");
        t.setDaemon(true);
        return t;
    });

    /** feedId -> runId attualmente in esecuzione (un run alla volta per feed). */
    private final Map<String, String> runningFeeds = new ConcurrentHashMap<String, String>();
    /** Run attivi in memoria per polling veloce della UI. */
    private final Map<String, WorkflowRun> activeRuns = new ConcurrentHashMap<String, WorkflowRun>();
    /** Handle di controllo per run (abort + processo corrente). */
    private final Map<String, RunControl> controls = new ConcurrentHashMap<String, RunControl>();
    private final AtomicLong seq = new AtomicLong(0);

    public WorkflowEngine(AppProperties props, WorkflowRegistry registry, RunStore store, AuditLogger audit,
                          InternalSteps internalSteps, AssetStore assets,
                          com.legalarchive.orchestrator.store.GlobalVarsStore globalVars) {
        this.props = props;
        this.registry = registry;
        this.store = store;
        this.audit = audit;
        this.internalSteps = internalSteps;
        this.assets = assets;
        this.globalVars = globalVars;
        int tick = props.getSchedulerTickSec() > 0 ? props.getSchedulerTickSec() : 20;
        scheduler.scheduleWithFixedDelay(this::schedule, tick, tick, java.util.concurrent.TimeUnit.SECONDS);
    }

    // ---- adaptive admission: bound parallel runs by config + JVM heap headroom ----

    /** Queue a unit of run work (initial start or gate-resume) for admission, then try to admit now. */
    private void enqueue(String runId, Runnable task) {
        pending.add(new Pending(runId, task));
        schedule();
    }

    /** Admit as many queued runs as the parallelism limit and current resources allow. Thread-safe. */
    private synchronized void schedule() {
        while (true) {
            Pending p = pending.peek();
            if (p == null) break;
            if (!canAdmit()) break;
            pending.poll();
            active.incrementAndGet();
            final Pending pp = p;
            runPool.submit(() -> {
                try { pp.task.run(); }
                catch (Throwable t) { log.error("run task {} crashed: {}", pp.runId, t.toString()); }
                finally { active.decrementAndGet(); schedule(); }
            });
        }
    }

    private boolean canAdmit() {
        int max = props.getMaxParallelRuns() > 0 ? props.getMaxParallelRuns() : 1;
        int cur = active.get();
        if (cur >= max) return false;
        if (cur == 0) return true;                       // always run at least one (guarantees progress)
        return availHeapMb() >= props.getRunAdmissionHeadroomMb();
    }

    /** JVM heap still allocatable, in MB. */
    private static long availHeapMb() {
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        return (rt.maxMemory() - used) / 1048576L;
    }

    /** Operations: number of runs currently executing (admitted, not yet finished/paused). */
    public int activeRunCount() { return active.get(); }
    /** Operations: number of runs queued waiting for an execution slot. */
    public int pendingRunCount() { return pending.size(); }
    public int maxParallelRuns() { return props.getMaxParallelRuns() > 0 ? props.getMaxParallelRuns() : 1; }

    /** Avvia un run. Ritorna il run creato, o null se il feed ha gia' un run attivo. */
    public WorkflowRun start(String feedId, String trigger, String user) {
        WorkflowDef def = registry.get(feedId);
        FeedLayout layout = registry.layout(feedId);
        if (def == null || layout == null) {
            throw new IllegalArgumentException("Unknown workflow: " + feedId);
        }
        synchronized (this) {
            if (def.locked) {
                log.warn("[{}] avvio rifiutato ({}): feed bloccato (manutenzione)", feedId, trigger);
                auditFeed(layout, feedId, null, null, "RUN_REJECTED", user,
                        kv("reason", "feed is locked for maintenance", "trigger", trigger));
                return null;
            }
            if (runningFeeds.containsKey(feedId)) {
                log.warn("[{}] avvio rifiutato ({}): run gia' attivo {}", feedId, trigger, runningFeeds.get(feedId));
                auditFeed(layout, feedId, null, null, "RUN_REJECTED", user,
                        kv("reason", "a run is already active: " + runningFeeds.get(feedId), "trigger", trigger));
                return null;
            }
            WorkflowRun run = buildRun(def, layout, trigger, user);
            run.status = RunStatus.QUEUED;          // in coda FIFO finche' il worker non lo preleva
            runningFeeds.put(feedId, run.runId);
            activeRuns.put(run.runId, run);
            controls.put(run.runId, new RunControl()); // handle per Stop fin da subito
            store.save(layout, run);
            log.info("[{}] RUN_QUEUED {} trigger={} user={}", feedId, run.runId, trigger, user);
            auditFeed(layout, feedId, run.runId, null, "RUN_QUEUED", user,
                    kv("trigger", trigger, "workflow", def.name));
            enqueue(run.runId, () -> loop(def, layout, run, 0));
            return run;
        }
    }

    /**
     * Test-run a single step on a dedicated executor, OUTSIDE the main FIFO queue, so it runs immediately
     * even while other feeds are executing. Builds an ephemeral TEST run (runId contains "_test_"),
     * executes only the chosen step (the rest are marked SKIPPED) and registers it in activeRuns +
     * controls so the normal run page shows live output and Stop works. Does NOT take the per-feed lock,
     * so it runs concurrently with other feeds. Refuses only if the SAME feed has a normal run active
     * (to avoid clobbering its working files).
     */
    public WorkflowRun testStep(String feedId, String stepId, String user) {
        WorkflowDef def = registry.get(feedId);
        FeedLayout layout = registry.layout(feedId);
        if (def == null || layout == null) throw new IllegalArgumentException("Unknown workflow: " + feedId);
        StepDef step = null;
        for (StepDef s : def.steps()) if (s.id.equals(stepId)) { step = s; break; }
        if (step == null) throw new IllegalArgumentException("Unknown step: " + stepId);
        if (runningFeeds.containsKey(feedId)) {
            throw new IllegalStateException("This feed has a run in progress (" + runningFeeds.get(feedId)
                    + "). Wait for it to finish before testing a step on the same feed.");
        }

        final WorkflowRun run = buildRun(def, layout, "TEST", user);
        LocalDateTime now = LocalDateTime.now();
        run.runId = def.feedId + "_test_" + now.format(ID_TS) + "_" + String.format("%03d", seq.incrementAndGet() % 1000);
        run.vars.put("runId", run.runId);
        run.status = RunStatus.RUNNING;
        for (StepExec se : run.steps) if (!se.stepId.equals(stepId)) se.status = StepStatus.SKIPPED;

        try { layout.provision(); } catch (Exception e) { throw new RuntimeException("provision failed: " + e.getMessage(), e); }
        activeRuns.put(run.runId, run);
        controls.put(run.runId, new RunControl());
        store.save(layout, run);
        log.info("[{}] TEST_STEP {} step={} user={}", feedId, run.runId, stepId, user);
        auditFeed(layout, feedId, run.runId, stepId, "TEST_STEP_STARTED", user, kv("step", stepId));

        final WorkflowDef fdef = def; final FeedLayout flay = layout; final StepDef fstep = step;
        testExecutor.submit(() -> {
            boolean ok = false;
            try { ok = executeStep(fdef, flay, run, fstep); }
            catch (Exception e) { log.error("[{}] test step {} error: {}", fdef.feedId, fstep.id, e.toString()); }
            RunControl c = controls.get(run.runId);
            boolean aborted = c != null && c.aborted;
            finish(fdef, flay, run, aborted ? RunStatus.ABORTED : (ok ? RunStatus.SUCCESS : RunStatus.FAILED),
                    aborted ? "Stopped by user" : (ok ? "Test step OK" : "Test step failed"));
        });
        return run;
    }

    // ---- step-by-step test sessions: run steps one at a time from a chosen start, pausing for confirm ----
    private static final class TestSession {
        final WorkflowDef def; final FeedLayout layout; final WorkflowRun run;
        final java.util.List<StepDef> steps; int cursor;
        TestSession(WorkflowDef d, FeedLayout l, WorkflowRun r, java.util.List<StepDef> s) { def = d; layout = l; run = r; steps = s; cursor = 0; }
    }
    private final Map<String, TestSession> testSessions = new ConcurrentHashMap<String, TestSession>();

    /**
     * Start a step-by-step TEST from a chosen step through the end of the workflow, on the test executor
     * (off the main queue). Runs the first step, then pauses (WAITING_APPROVAL) until {@link #testContinue}
     * or Stop. All steps share ONE run, so output vars and files accumulate (step N sees step N-1's
     * output). Steps before the start are SKIPPED; gates and loops are not evaluated. Does not take the
     * per-feed lock; refused only if the SAME feed has a normal run active.
     */
    public WorkflowRun testFrom(String feedId, String startStepId, String user) {
        WorkflowDef def = registry.get(feedId);
        FeedLayout layout = registry.layout(feedId);
        if (def == null || layout == null) throw new IllegalArgumentException("Unknown workflow: " + feedId);
        java.util.List<StepDef> all = def.steps();
        int start = -1;
        for (int i = 0; i < all.size(); i++) if (all.get(i).id.equals(startStepId)) { start = i; break; }
        if (start < 0) throw new IllegalArgumentException("Unknown step: " + startStepId);
        if (runningFeeds.containsKey(feedId)) {
            throw new IllegalStateException("This feed has a run in progress (" + runningFeeds.get(feedId)
                    + "). Wait for it to finish before testing on the same feed.");
        }
        java.util.List<StepDef> plan = new java.util.ArrayList<StepDef>(all.subList(start, all.size()));
        java.util.Set<String> planIds = new java.util.HashSet<String>();
        for (StepDef s : plan) planIds.add(s.id);

        final WorkflowRun run = buildRun(def, layout, "TEST", user);
        LocalDateTime now = LocalDateTime.now();
        run.runId = def.feedId + "_test_" + now.format(ID_TS) + "_" + String.format("%03d", seq.incrementAndGet() % 1000);
        run.vars.put("runId", run.runId);
        run.status = RunStatus.RUNNING;
        for (StepExec se : run.steps) if (!planIds.contains(se.stepId)) se.status = StepStatus.SKIPPED;

        try { layout.provision(); } catch (Exception e) { throw new RuntimeException("provision failed: " + e.getMessage(), e); }
        activeRuns.put(run.runId, run);
        controls.put(run.runId, new RunControl());
        testSessions.put(run.runId, new TestSession(def, layout, run, plan));
        store.save(layout, run);
        log.info("[{}] TEST_FROM {} start={} steps={} user={}", feedId, run.runId, startStepId, plan.size(), user);
        auditFeed(layout, feedId, run.runId, startStepId, "TEST_SESSION_STARTED", user,
                kv("from", startStepId, "steps", String.valueOf(plan.size())));
        testExecutor.submit(() -> runSessionStep(run.runId));
        return run;
    }

    /** Resume a paused step-by-step test: run the next step. */
    public boolean testContinue(String feedId, String runId, String user) {
        TestSession s = testSessions.get(runId);
        if (s == null || s.run.status != RunStatus.WAITING_APPROVAL) return false;
        s.run.status = RunStatus.RUNNING;
        s.run.pausedNextStep = null;
        store.save(s.layout, s.run);
        auditFeed(s.layout, feedId, runId, null, "TEST_CONTINUE", user, null);
        testExecutor.submit(() -> runSessionStep(runId));
        return true;
    }

    private void runSessionStep(String runId) {
        TestSession s = testSessions.get(runId);
        if (s == null) return;
        RunControl ctl = controls.get(runId);
        if (ctl != null && ctl.aborted) { finish(s.def, s.layout, s.run, RunStatus.ABORTED, "Stopped by user"); return; }
        if (s.cursor >= s.steps.size()) { finish(s.def, s.layout, s.run, RunStatus.SUCCESS, "Step-by-step test completed"); return; }
        StepDef step = s.steps.get(s.cursor);
        boolean ok;
        try { ok = executeStep(s.def, s.layout, s.run, step); }
        catch (Exception e) { log.error("[{}] test session step {} error: {}", s.def.feedId, step.id, e.toString()); ok = false; }
        RunControl c2 = controls.get(runId);
        if (c2 != null && c2.aborted) { finish(s.def, s.layout, s.run, RunStatus.ABORTED, "Stopped by user"); return; }
        if (!ok) { finish(s.def, s.layout, s.run, RunStatus.FAILED, "Step '" + step.id + "' failed"); return; }
        s.cursor++;
        if (s.cursor >= s.steps.size()) {
            finish(s.def, s.layout, s.run, RunStatus.SUCCESS, "Step-by-step test completed");
        } else {
            StepDef next = s.steps.get(s.cursor);
            s.run.status = RunStatus.WAITING_APPROVAL;
            s.run.pausedNextStep = (next.name == null || next.name.isEmpty() ? next.id : next.name) + " (" + next.id + ")";
            store.save(s.layout, s.run);
            auditFeed(s.layout, s.run.feedId, s.run.runId, step.id, "TEST_STEP_DONE_WAITING", "system",
                    kv("done", step.id, "next", next.id));
        }
    }

    /** Decisione su un gate manuale in attesa: riprende il run. */
    public boolean decide(String feedId, String runId, boolean approve, String user, String note) {
        WorkflowDef def = registry.get(feedId);
        FeedLayout layout = registry.layout(feedId);
        if (def == null || layout == null) return false;
        WorkflowRun run = activeRuns.get(runId);
        if (run == null) run = store.load(layout, runId);
        if (run == null || run.status != RunStatus.WAITING_APPROVAL || run.waitingGateId == null) return false;

        NodeDef node = def.nodes.get(run.currentIndex);
        if (!(node instanceof GateDef) || !node.id.equals(run.waitingGateId)) return false;
        GateDef gate = (GateDef) node;

        // un altro run dello stesso feed potrebbe essere partito durante l'attesa
        String active = runningFeeds.get(feedId);
        if (active != null && !active.equals(runId)) return false;

        GateExec ge = run.gate(gate.id);
        if (ge != null) {
            ge.result = approve;
            ge.decidedBy = user;
            ge.ts = now();
        }
        log.info("[{}] GATE_DECISION {} gate={} decision={} user={}", feedId, runId, gate.id, approve ? "APPROVE" : "REJECT", user);
        auditFeed(layout, feedId, runId, gate.id, "GATE_DECISION", user,
                kv("decision", approve ? "APPROVE" : "REJECT", "note", note == null ? "" : note));

        run.waitingGateId = null;
        run.status = RunStatus.QUEUED;          // rientra in coda FIFO
        runningFeeds.put(feedId, runId);
        activeRuns.put(runId, run);
        controls.putIfAbsent(runId, new RunControl());
        store.save(layout, run);

        final WorkflowRun r = run;
        String target = approve ? gate.onTrue : gate.onFalse;
        enqueue(r.runId, () -> continueFromTarget(def, layout, r, gate, target));
        return true;
    }

    /** runId del run attivo per il feed, o null se nessuno è in corso/in coda. */
    public String activeRunId(String feedId) { return runningFeeds.get(feedId); }

    /** Snapshot of the FIFO execution queue: RUNNING / WAITING_APPROVAL first, then QUEUED
        in submission order (by startTs, ms precision). Empty when nothing is active. */
    public java.util.List<WorkflowRun> queueSnapshot() {
        java.util.List<WorkflowRun> act = new java.util.ArrayList<WorkflowRun>();
        for (WorkflowRun r : activeRuns.values()) {
            if (r.status == RunStatus.RUNNING || r.status == RunStatus.QUEUED || r.status == RunStatus.WAITING_APPROVAL) {
                act.add(r);
            }
        }
        java.util.Collections.sort(act, new java.util.Comparator<WorkflowRun>() {
            public int compare(WorkflowRun a, WorkflowRun b) {
                int ra = rank(a.status), rb = rank(b.status);
                if (ra != rb) return ra - rb;
                String sa = a.startTs == null ? "" : a.startTs, sb = b.startTs == null ? "" : b.startTs;
                int c = sa.compareTo(sb);
                return c != 0 ? c : a.runId.compareTo(b.runId);
            }
            private int rank(RunStatus s) { return s == RunStatus.RUNNING ? 0 : (s == RunStatus.WAITING_APPROVAL ? 1 : 2); }
        });
        return act;
    }
    public WorkflowRun activeRun(String runId) {
        return activeRuns.get(runId);
    }

    /**
     * Stop di un run in corso o in attesa di approvazione.
     * Se sta eseguendo uno step, il processo PowerShell viene terminato e il
     * thread worker chiude il run come ABORTED; se e' su un gate manuale, il run
     * viene chiuso direttamente come ABORTED.
     */
    public boolean stop(String feedId, String runId, String user) {
        WorkflowDef def = registry.get(feedId);
        FeedLayout layout = registry.layout(feedId);
        if (def == null || layout == null) return false;
        WorkflowRun run = activeRuns.get(runId);
        if (run == null) run = store.load(layout, runId);
        if (run == null || isTerminal(run.status)) return false;

        RunControl c = controls.get(runId);
        boolean repeatStop = c != null && c.aborted;   // a Stop was already requested for this run
        if (c != null) c.aborted = true;
        log.info("[{}] STOP requested for {} by {}", feedId, runId, user);
        auditFeed(layout, feedId, runId, null, "RUN_STOP_REQUESTED", user, null);

        if (run.status == RunStatus.WAITING_APPROVAL) {
            run.waitingGateId = null;
            finish(def, layout, run, RunStatus.ABORTED, "Stopped by " + user);
            return true;
        }
        if (run.status == RunStatus.QUEUED) {
            finish(def, layout, run, RunStatus.ABORTED, "Stopped while queued by " + user);
            return true;
        }
        if (c != null && c.statement != null) {
            try { c.statement.cancel(); } catch (Exception ignored) {}   // csvsql (H2): cancel the running query
        }
        if (c != null && c.aborter != null) {
            // sql/DB2: cancel + close statement and connection, to unblock a query that ignores cancel()
            final Runnable ab = c.aborter;
            Thread t = new Thread(() -> { try { ab.run(); } catch (Exception ignored) {} }, "wf-db-abort");
            t.setDaemon(true);
            t.start();
        }
        if (c != null && c.process != null) {
            c.process.destroyForcibly();
        }
        // Last resort: if the operator presses Stop again and the run is still active (a worker thread
        // genuinely stuck, e.g. a blocked native/IO call that ignores cancel+close), finalise it ABORTED
        // now and abandon the worker, so the UI/Operations don't stay stuck on RUNNING forever.
        if (repeatStop && !isTerminal(run.status)) {
            log.warn("[{}] FORCE stop {} (repeat request): finalising ABORTED, worker thread abandoned", feedId, runId);
            auditFeed(layout, feedId, runId, null, "RUN_FORCE_STOPPED", user, null);
            finish(def, layout, run, RunStatus.ABORTED, "Force-stopped by " + user);
        }
        return true;
    }

    private static boolean isTerminal(RunStatus s) {
        return s == RunStatus.SUCCESS || s == RunStatus.FAILED || s == RunStatus.SKIPPED
                || s == RunStatus.REJECTED || s == RunStatus.ABORTED;
    }

    // ------------------------------------------------------------------ core

    private WorkflowRun buildRun(WorkflowDef def, FeedLayout layout, String trigger, String user) {
        WorkflowRun run = new WorkflowRun();
        LocalDateTime now = LocalDateTime.now();
        run.runId = def.feedId + "_" + now.format(ID_TS) + "_" + String.format("%03d", seq.incrementAndGet() % 1000);
        run.feedId = def.feedId;
        run.workflowName = def.name;
        run.trigger = trigger;
        run.triggeredBy = user;
        run.startTs = now.format(TS);

        // variabili builtin: l'identita' del feed e' disponibile ovunque
        run.vars.put("stepTimeoutMins", "5");   // standard default (minutes); overridable globally, per-workflow, or per-step via stepTimeoutMins.<stepId>
        run.vars.putAll(globalVars.all());   // common vars for all workflows (lowest precedence)
        run.vars.put("feedId", def.feedId);
        run.vars.put("sourceId", def.sourceId == null ? "" : def.sourceId);
        run.vars.put("targetId", def.targetId == null ? "" : def.targetId);
        run.vars.put("__prod", def.production ? "true" : "false");
        run.vars.put("feedName", def.name);
        run.vars.put("runId", run.runId);
        run.vars.put("runDate", now.format(DateTimeFormatter.ofPattern("yyyyMMdd")));
        run.vars.put("runTs", now.format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")));
        run.vars.putAll(layout.dirVars());
        run.vars.put("sharedDir", assets.sharedDir().toString());   // app-level shared files dir
        run.vars.putAll(def.variables);
        run.vars.putAll(assets.scriptVars(def.feedId)); // alias degli script caricati (app + feed)

        for (NodeDef n : def.nodes) {
            if (n instanceof StepDef) {
                StepExec se = new StepExec();
                se.stepId = n.id;
                se.name = n.name;
                run.steps.add(se);
            } else if (n instanceof GateDef) {
                GateExec ge = new GateExec();
                ge.gateId = n.id;
                ge.name = n.name;
                ge.type = ((GateDef) n).type;
                run.gates.add(ge);
            }
        }
        return run;
    }

    private void loop(WorkflowDef def, FeedLayout layout, WorkflowRun run, int startIndex) {
        int idx = startIndex;
        int transitions = 0;
        try {
            // il worker preleva il run dalla coda
            if (isTerminal(run.status)) return;   // gia' chiuso (es. stop in coda)
            RunControl ctl0 = controls.get(run.runId);
            if (ctl0 != null && ctl0.aborted) {
                finish(def, layout, run, RunStatus.ABORTED, "Stopped while queued");
                return;
            }
            if (run.status == RunStatus.QUEUED) {
                run.status = RunStatus.RUNNING;
                store.save(layout, run);
                log.info("[{}] RUN_RUNNING {} (dequeued)", run.feedId, run.runId);
                auditFeed(layout, run.feedId, run.runId, null, "RUN_RUNNING", "system", null);
            }
            while (idx < def.nodes.size()) {
                if (++transitions > props.getMaxTransitions()) {
                    finish(def, layout, run, RunStatus.FAILED, "Exceeded the limit of " + props.getMaxTransitions()
                            + " node transitions: possible gate loop");
                    return;
                }
                RunControl ctl = controls.get(run.runId);
                if (ctl != null && ctl.aborted) {
                    finish(def, layout, run, RunStatus.ABORTED, "Stopped by user");
                    return;
                }
                run.currentIndex = idx;
                NodeDef node = def.nodes.get(idx);

                if (node instanceof StepDef) {
                    boolean ok = executeStep(def, layout, run, (StepDef) node);
                    if (!ok) {
                        RunControl c2 = controls.get(run.runId);
                        if (c2 != null && c2.aborted) {
                            finish(def, layout, run, RunStatus.ABORTED, "Stopped by user");
                        } else {
                            finish(def, layout, run, RunStatus.FAILED, "Step '" + node.id + "' failed");
                        }
                        return;
                    }
                    idx++;
                } else if (node instanceof LoopDef) {
                    LoopDef lp = (LoopDef) node;
                    int endIdx = loopEndIndex(def, idx);
                    if (endIdx < 0) { finish(def, layout, run, RunStatus.FAILED, "Loop '" + lp.id + "' has no matching ENDLOOP"); return; }
                    String raw = VarResolver.resolve(lp.over, run.vars);
                    String delim = (lp.delimiter == null || lp.delimiter.isEmpty()) ? ";" : lp.delimiter;
                    java.util.List<String> items = splitList(raw, delim);
                    String key = "__loop." + lp.id;
                    if (items.isEmpty()) {
                        auditFeed(layout, run.feedId, run.runId, lp.id, "LOOP_SKIP", "system", kv("over", s(lp.over)));
                        idx = endIdx + 1;
                    } else {
                        run.vars.put(key + ".items", join(items, "\u0001"));
                        run.vars.put(key + ".n", String.valueOf(items.size()));
                        run.vars.put(key + ".i", "0");
                        run.vars.put(lp.itemVar, items.get(0));
                        if (lp.over2 != null && !lp.over2.trim().isEmpty()) {
                            java.util.List<String> items2 = splitList(VarResolver.resolve(lp.over2, run.vars), delim);
                            run.vars.put(key + ".items2", join(items2, "\u0001"));
                            run.vars.put(lp.itemVar2, items2.isEmpty() ? "" : items2.get(0));
                        }
                        setLoopIndexVars(run, lp, 0);
                        run.vars.put(lp.countVar, String.valueOf(items.size()));
                        store.save(layout, run);
                        log.info("[{}] LOOP '{}' su {} item(s)", run.feedId, lp.id, items.size());
                        auditFeed(layout, run.feedId, run.runId, lp.id, "LOOP_START", "system",
                                kv("count", String.valueOf(items.size())));
                        idx++;
                    }
                } else if (node instanceof LoopEndDef) {
                    int startIdx = loopStartIndex(def, idx);
                    if (startIdx < 0) { finish(def, layout, run, RunStatus.FAILED, "ENDLOOP '" + node.id + "' has no matching LOOP"); return; }
                    LoopDef lp = (LoopDef) def.nodes.get(startIdx);
                    String key = "__loop." + lp.id;
                    int n = parseIntSafe(run.vars.get(key + ".n"), 0);
                    int i = parseIntSafe(run.vars.get(key + ".i"), 0) + 1;
                    if (i < n) {
                        java.util.List<String> items = splitList(run.vars.get(key + ".items"), "\u0001");
                        run.vars.put(key + ".i", String.valueOf(i));
                        run.vars.put(lp.itemVar, i < items.size() ? items.get(i) : "");
                        if (lp.over2 != null && !lp.over2.trim().isEmpty()) {
                            java.util.List<String> items2 = splitList(run.vars.get(key + ".items2"), "\u0001");
                            run.vars.put(lp.itemVar2, i < items2.size() ? items2.get(i) : "");
                        }
                        setLoopIndexVars(run, lp, i);
                        store.save(layout, run);
                        idx = startIdx + 1;   // re-enter loop body
                    } else {
                        run.vars.remove(key + ".items"); run.vars.remove(key + ".n"); run.vars.remove(key + ".i"); run.vars.remove(key + ".items2");
                        auditFeed(layout, run.feedId, run.runId, lp.id, "LOOP_END", "system", kv("count", String.valueOf(n)));
                        idx++;
                    }
                } else if (node instanceof GateDef) {
                    GateDef gate = (GateDef) node;
                    if ("manual".equals(gate.type)) {
                        run.status = RunStatus.WAITING_APPROVAL;
                        run.waitingGateId = gate.id;
                        store.save(layout, run);
                        log.info("[{}] {} sospeso sul gate manuale '{}': in attesa di approvazione", run.feedId, run.runId, gate.id);
                        auditFeed(layout, run.feedId, run.runId, gate.id, "GATE_WAITING", "system",
                                kv("gate", gate.name));
                        runningFeeds.remove(run.feedId); // il feed non occupa il motore mentre attende
                        return; // sospeso: ripresa via decide()
                    }
                    String resolved = VarResolver.resolve(gate.condition, run.vars);
                    boolean result;
                    try {
                        result = VarResolver.evalCondition(resolved);
                    } catch (Exception e) {
                        auditFeed(layout, run.feedId, run.runId, gate.id, "GATE_ERROR", "system",
                                kv("condition", resolved, "error", e.getMessage()));
                        finish(def, layout, run, RunStatus.FAILED, "Gate '" + gate.id + "': condition could not be evaluated");
                        return;
                    }
                    GateExec ge = run.gate(gate.id);
                    if (ge != null) { ge.condition = resolved; ge.result = result; ge.ts = now(); }
                    log.debug("[{}] gate '{}' condizione [{}] -> {}", run.feedId, gate.id, resolved, result);
                    auditFeed(layout, run.feedId, run.runId, gate.id, "GATE_EVALUATED", "system",
                            kv("condition", resolved, "result", String.valueOf(result),
                               "target", result ? s(gate.onTrue) : s(gate.onFalse)));
                    store.save(layout, run);

                    Integer next = resolveTarget(def, layout, run, gate, result ? gate.onTrue : gate.onFalse);
                    if (next == null) return; // terminato da END:
                    idx = next;
                }
            }
            finish(def, layout, run, RunStatus.SUCCESS, null);
        } catch (Exception e) {
            finish(def, layout, run, RunStatus.FAILED, "Internal error: " + e.getMessage());
        }
    }

    private void continueFromTarget(WorkflowDef def, FeedLayout layout, WorkflowRun run, GateDef gate, String target) {
        try {
            Integer next = resolveTarget(def, layout, run, gate, target);
            if (next == null) return;
            loop(def, layout, run, next);
        } catch (Exception e) {
            finish(def, layout, run, RunStatus.FAILED, "Internal error: " + e.getMessage());
        }
    }

    /**
     * Risolve il target di un gate: indice del nodo, oppure null se il run e' stato
     * terminato (target END:STATO o assente = prosegui col nodo successivo).
     */
    private Integer resolveTarget(WorkflowDef def, FeedLayout layout, WorkflowRun run, GateDef gate, String target) {
        if (target == null) {
            int next = def.indexOfNode(gate.id) + 1;
            if (next >= def.nodes.size()) { finish(def, layout, run, RunStatus.SUCCESS, null); return null; }
            return next;
        }
        if (target.startsWith("END:")) {
            String st = target.substring(4).trim().toUpperCase();
            RunStatus rs;
            try { rs = RunStatus.valueOf(st); } catch (Exception e) { rs = RunStatus.ABORTED; }
            finish(def, layout, run, rs, "Ended by gate '" + gate.id + "' (" + target + ")");
            return null;
        }
        return def.indexOfNode(target);
    }

    /** Set the exposed loop index vars from the 0-based internal index:
     *  indexVar is 1-based; indexStringVar is the 1-based index left-padded with '0'
     *  to indexPad chars (e.g. 001). */
    private static void setLoopIndexVars(WorkflowRun run, LoopDef lp, int zeroBased) {
        int oneBased = zeroBased + 1;
        if (lp.indexVar != null && !lp.indexVar.isEmpty()) run.vars.put(lp.indexVar, String.valueOf(oneBased));
        if (lp.indexStringVar != null && !lp.indexStringVar.isEmpty()) {
            String s = lp.indexPad > 0 ? String.format("%0" + lp.indexPad + "d", oneBased) : String.valueOf(oneBased);
            run.vars.put(lp.indexStringVar, s);
        }
    }

    /** Index of the ENDLOOP matching the LOOP at loopIdx (supports nesting), or -1. */
    private static int loopEndIndex(WorkflowDef def, int loopIdx) {
        int depth = 1;
        for (int j = loopIdx + 1; j < def.nodes.size(); j++) {
            NodeDef n = def.nodes.get(j);
            if (n instanceof LoopDef) depth++;
            else if (n instanceof LoopEndDef) { depth--; if (depth == 0) return j; }
        }
        return -1;
    }

    /** Index of the LOOP matching the ENDLOOP at endIdx (supports nesting), or -1. */
    private static int loopStartIndex(WorkflowDef def, int endIdx) {
        int depth = 1;
        for (int j = endIdx - 1; j >= 0; j--) {
            NodeDef n = def.nodes.get(j);
            if (n instanceof LoopEndDef) depth++;
            else if (n instanceof LoopDef) { depth--; if (depth == 0) return j; }
        }
        return -1;
    }

    private static java.util.List<String> splitList(String raw, String delim) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (raw == null || raw.isEmpty()) return out;
        int from = 0, dl = delim.length();
        while (true) {
            int p = raw.indexOf(delim, from);
            String tok = (p < 0) ? raw.substring(from) : raw.substring(from, p);
            if (tok != null && !tok.trim().isEmpty()) out.add(tok.trim());
            if (p < 0) break;
            from = p + dl;
        }
        return out;
    }

    private static String join(java.util.List<String> items, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) { if (i > 0) sb.append(sep); sb.append(items.get(i)); }
        return sb.toString();
    }

    private static int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    /**
     * Optional per-step cleanup: when the step succeeds, delete the file/directory named by the
     * step param "deleteOnSuccess" (var-resolved; e.g. ${dir.previousStepId}). Lets a step free the
     * working directory of an earlier step it consumed. Safety: the resolved path must live under a
     * feeds base directory and be deeper than a feed root, so it can never remove an entire feed or
     * anything outside the feeds area. Best-effort: failures are audited, never abort the run.
     */
    private void deleteOnSuccess(FeedLayout layout, WorkflowRun run, StepDef step) {
        String raw = (step.params == null) ? null : step.params.get("deleteOnSuccess");
        if (raw == null || raw.trim().isEmpty()) return;
        String resolved = VarResolver.resolve(raw.trim(), run.vars);
        if (resolved == null || resolved.trim().isEmpty()) return;
        java.nio.file.Path target;
        try { target = java.nio.file.Paths.get(resolved.trim()).toAbsolutePath().normalize(); }
        catch (Exception e) { log.warn("[{}] deleteOnSuccess bad path '{}': {}", run.feedId, resolved, e.toString()); return; }
        java.nio.file.Path base1 = layout.feedDir.getParent();
        java.nio.file.Path base2 = null;
        try { base2 = java.nio.file.Paths.get(props.getDefaultBaseDir()).toAbsolutePath().normalize(); } catch (Exception ignore) {}
        boolean ok1 = base1 != null && target.startsWith(base1) && target.getNameCount() > base1.getNameCount() + 1;
        boolean ok2 = base2 != null && target.startsWith(base2) && target.getNameCount() > base2.getNameCount() + 1;
        if (!ok1 && !ok2) {
            log.warn("[{}] deleteOnSuccess REFUSED (outside feeds base or too shallow): {}", run.feedId, target);
            auditFeed(layout, run.feedId, run.runId, step.id, "DELETE_ON_SUCCESS_REFUSED", "system", kv("path", target.toString()));
            return;
        }
        try {
            if (!java.nio.file.Files.exists(target)) {
                auditFeed(layout, run.feedId, run.runId, step.id, "DELETE_ON_SUCCESS_SKIP", "system", kv("path", target.toString(), "reason", "not found"));
                return;
            }
            boolean isDir = java.nio.file.Files.isDirectory(target);
            deleteRecursively(target);
            log.info("[{}] deleteOnSuccess removed {} ({})", run.feedId, target, isDir ? "directory" : "file");
            auditFeed(layout, run.feedId, run.runId, step.id, "DELETE_ON_SUCCESS", "system", kv("path", target.toString(), "type", isDir ? "directory" : "file"));
        } catch (Exception e) {
            log.warn("[{}] deleteOnSuccess error on '{}': {}", run.feedId, target, e.toString());
            auditFeed(layout, run.feedId, run.runId, step.id, "DELETE_ON_SUCCESS_ERROR", "system", kv("path", target.toString(), "error", e.toString()));
        }
    }

    private static void deleteRecursively(java.nio.file.Path root) throws java.io.IOException {
        if (!java.nio.file.Files.exists(root)) return;
        java.nio.file.Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            public java.nio.file.FileVisitResult visitFile(java.nio.file.Path f, java.nio.file.attribute.BasicFileAttributes a) throws java.io.IOException {
                java.nio.file.Files.delete(f); return java.nio.file.FileVisitResult.CONTINUE;
            }
            public java.nio.file.FileVisitResult postVisitDirectory(java.nio.file.Path d, java.io.IOException e) throws java.io.IOException {
                if (e != null) throw e;
                java.nio.file.Files.delete(d); return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    private boolean executeStep(WorkflowDef def, FeedLayout layout, WorkflowRun run, StepDef step) {
        StepExec se = run.step(step.id);
        se.status = StepStatus.RUNNING;
        se.startTs = now();
        run.vars.put("stepDir", layout.stepDirs.get(step.id).toString());
        run.vars.put("stepId", step.id);                                  // current step id
        run.vars.put("stepName", step.name == null ? step.id : step.name); // current step name
        store.save(layout, run);

        String internalKind = internalKind(step.exec);
        String scriptPath = internalKind != null ? "<" + internalKind + ">" : resolveScript(step.script);
        StepExecutor.Kind extKind = internalKind != null ? null : StepExecutor.resolveKind(step.exec, scriptPath);
        String execLabel = internalKind != null ? internalKind : extKind.name();
        java.nio.file.Path logFile = layout.stepLog(run.runId, step.id);
        se.logFile = layout.logsDir.toAbsolutePath().relativize(logFile.toAbsolutePath()).toString();

        log.info("[{}] STEP_STARTED {} step={} exec={} target={}", run.feedId, run.runId, step.id, execLabel, scriptPath);
        Map<String, String> startDet = new LinkedHashMap<String, String>();
        startDet.put("target", scriptPath);
        startDet.put("exec", execLabel);
        if (step.forEach != null && !step.forEach.trim().isEmpty()) startDet.put("forEach", step.forEach);
        auditFeed(layout, run.feedId, run.runId, step.id, "STEP_STARTED", "system", startDet);

        int timeout = InternalSteps.stepTimeoutSec(step.id, step.timeoutSec, run.vars, props.getDefaultStepTimeoutSec());

        try {
            // ---- parallel fan-out: run once per item of the resolved list ----
            if (step.forEach != null && !step.forEach.trim().isEmpty()) {
                return runForEach(def, layout, run, step, internalKind, extKind, scriptPath, logFile, timeout, se);
            }

            // ---- single execution with retry ----
            int maxAttempts = 1 + Math.max(0, step.retry);
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                se.attempts = attempt;
                StepExecutor.Result res = runOnce(run, layout, step, internalKind, extKind, scriptPath, run.vars, logFile, timeout, true);
                se.exitCode = res.exitCode;
                log.debug("[{}] step '{}' attempt {} exit={} timedOut={}", run.feedId, step.id, attempt, res.exitCode, res.timedOut);

                for (Map.Entry<String, String> ov : res.outVars.entrySet()) {
                    run.vars.put(ov.getKey(), ov.getValue());
                    run.vars.put(step.id + "." + ov.getKey(), ov.getValue());   // namespaced: ${stepId.var}
                    auditFeed(layout, run.feedId, run.runId, step.id, "STEP_OUTPUT_VAR", "system",
                            kv("var", ov.getKey(), "value", ov.getValue()));
                }
                // canonical handle for the file produced by this step (sql export, csvreplace, ...)
                String produced = res.outVars.containsKey("outputFile") ? res.outVars.get("outputFile")
                        : res.outVars.get("csvFile");
                if (produced != null) run.vars.put(step.id + ".outputFile", produced);
                for (String expected : step.outputs) {
                    if (!res.outVars.containsKey(expected)) {
                        auditFeed(layout, run.feedId, run.runId, step.id, "STEP_OUTPUT_MISSING", "system", kv("var", expected));
                    }
                }

                RunControl ac = controls.get(run.runId);
                if (ac != null && ac.aborted) {
                    se.status = StepStatus.FAILED; se.endTs = now(); se.message = "Stopped by user";
                    auditFeed(layout, run.feedId, run.runId, step.id, "STEP_ABORTED", "system", null);
                    store.save(layout, run);
                    return false;
                }

                if (res.exitCode == 0) {
                    se.status = StepStatus.SUCCESS; se.endTs = now();
                    auditFeed(layout, run.feedId, run.runId, step.id, "STEP_COMPLETED", "system",
                            kv("exitCode", "0", "attempt", String.valueOf(attempt)));
                    deleteOnSuccess(layout, run, step);
                    store.save(layout, run);
                    return true;
                }

                String reason = res.timedOut ? "timeout " + timeout + "s" : "exit code " + res.exitCode;
                se.message = reason + (res.lastLines == null || res.lastLines.isEmpty() ? "" : " - " + res.lastLines);
                if (attempt < maxAttempts) {
                    log.warn("[{}] step '{}' failed ({}), retry {}/{} in {}s", run.feedId, step.id, reason, attempt, maxAttempts - 1, step.retryDelaySec);
                    auditFeed(layout, run.feedId, run.runId, step.id, "STEP_RETRY", "system",
                            kv("attempt", String.valueOf(attempt), "reason", reason, "nextInSec", String.valueOf(step.retryDelaySec)));
                    store.save(layout, run);
                    Thread.sleep(step.retryDelaySec * 1000L);
                } else {
                    se.status = StepStatus.FAILED; se.endTs = now();
                    log.error("[{}] STEP_FAILED {} step={} {} after {} attempts", run.feedId, run.runId, step.id, reason, attempt);
                    auditFeed(layout, run.feedId, run.runId, step.id, "STEP_FAILED", "system",
                            kv("exitCode", String.valueOf(res.exitCode), "attempts", String.valueOf(attempt), "reason", reason));
                    store.save(layout, run);
                    return false;
                }
            }
            return false;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            se.status = StepStatus.FAILED; se.endTs = now(); se.message = "Interrupted";
            store.save(layout, run);
            return false;
        } catch (Exception e) {
            se.status = StepStatus.FAILED; se.endTs = now(); se.message = e.getMessage();
            auditFeed(layout, run.feedId, run.runId, step.id, "STEP_FAILED", "system", kv("error", String.valueOf(e.getMessage())));
            store.save(layout, run);
            return false;
        }
    }

    /** Built-in (in-process) kind, or null for external process kinds. */
    private static String internalKind(String exec) {
        if (exec == null) return null;
        String e = exec.trim().toLowerCase();
        if (e.equals("sql") || e.equals("ifscopy") || e.equals("filecopy") || e.equals("setvar") || e.equals("validate") || e.equals("csvreplace") || e.equals("encoding") || e.equals("anonymize") || e.equals("mask") || e.equals("split") || e.equals("safecopy") || e.equals("dequote") || e.equals("csvsql") || e.equals("xlsx2csv")) return e;
        return null;
    }

    /** A single execution (internal or external) against a given variable snapshot. */
    private StepExecutor.Result runOnce(WorkflowRun run, FeedLayout layout, StepDef step, String internalKind,
                                        StepExecutor.Kind extKind, String scriptPath, Map<String, String> varsSnapshot,
                                        java.nio.file.Path logFile, int timeout, boolean attachChecks) throws Exception {
        Map<String, String> rp = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> p : step.params.entrySet()) {
            rp.put(p.getKey(), VarResolver.resolve(p.getValue(), varsSnapshot));
        }
        if (internalKind != null) {
            final WorkflowRun frun = run; final FeedLayout flayout = layout;
            // checks (sotto-step) solo per esecuzione singola: in forEach gli item condividerebbero
            // lo stesso StepExec da thread concorrenti
            StepExec se = attachChecks ? run.step(step.id) : null;
            Runnable onProgress = attachChecks ? new Runnable() { public void run() { store.save(flayout, frun); } } : null;
            return internalSteps.run(internalKind, step, rp, varsSnapshot, logFile, controls.get(run.runId), se, onProgress);
        }
        StepExecutor ps = new StepExecutor(props.getPowershellExe(), props.getJavaExe(), props.getCmdExe());
        return ps.execute(extKind, scriptPath, rp, logFile, timeout, layout.stepDirs.get(step.id).toFile(), controls.get(run.runId));
    }

    /** Fan-out: run the step once per item of the resolved list, concurrently, then join. */
    private boolean runForEach(WorkflowDef def, FeedLayout layout, final WorkflowRun run, final StepDef step,
                               final String internalKind, final StepExecutor.Kind extKind, final String scriptPath,
                               java.nio.file.Path logFile, final int timeout, StepExec se) throws Exception {
        String listRaw = VarResolver.resolve(step.forEach, run.vars);
        String delim = step.delimiter == null ? "[;,\\n]" : java.util.regex.Pattern.quote(step.delimiter);
        java.util.List<String> items = new java.util.ArrayList<String>();
        if (listRaw != null) {
            for (String it : listRaw.split(delim)) { if (it != null && !it.trim().isEmpty()) items.add(it.trim()); }
        }
        int concurrency = Math.max(1, step.concurrency);
        log.info("[{}] step '{}' fan-out: {} item(s), concurrency {}", run.feedId, step.id, items.size(), concurrency);
        auditFeed(layout, run.feedId, run.runId, step.id, "STEP_FANOUT", "system",
                kv("items", String.valueOf(items.size()), "concurrency", String.valueOf(concurrency)));

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(Math.min(concurrency, Math.max(1, items.size())));
        java.util.List<java.util.concurrent.Future<Integer>> futures = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
        final java.nio.file.Path baseLog = logFile;
        try {
            for (int i = 0; i < items.size(); i++) {
                final String item = items.get(i);
                final int idx = i;
                futures.add(pool.submit(new java.util.concurrent.Callable<Integer>() {
                    public Integer call() throws Exception {
                        RunControl c = controls.get(run.runId);
                        if (c != null && c.aborted) return -997;
                        Map<String, String> snap = new LinkedHashMap<String, String>(run.vars);
                        snap.put("item", item);
                        snap.put("itemIndex", String.valueOf(idx));
                        java.nio.file.Path itemLog = baseLog.resolveSibling(step.id + "__" + idx + ".log");
                        StepExecutor.Result r = runOnce(run, layout, step, internalKind, extKind, scriptPath, snap, itemLog, timeout, false);
                        return r.exitCode;
                    }
                }));
            }
            pool.shutdown();
            int ok = 0, failed = 0;
            for (java.util.concurrent.Future<Integer> f : futures) {
                Integer code;
                try { code = f.get(); } catch (Exception e) { code = 1; }
                if (code != null && code == 0) ok++; else failed++;
            }
            se.exitCode = failed == 0 ? 0 : 1;
            run.vars.put("itemsTotal", String.valueOf(items.size()));
            run.vars.put("itemsOk", String.valueOf(ok));
            run.vars.put("itemsFailed", String.valueOf(failed));
            auditFeed(layout, run.feedId, run.runId, step.id, "STEP_FANOUT_JOINED", "system",
                    kv("total", String.valueOf(items.size()), "ok", String.valueOf(ok), "failed", String.valueOf(failed)));

            RunControl ac = controls.get(run.runId);
            if (ac != null && ac.aborted) {
                se.status = StepStatus.FAILED; se.endTs = now(); se.message = "Stopped by user";
                store.save(layout, run);
                return false;
            }
            if (failed == 0) {
                se.status = StepStatus.SUCCESS; se.endTs = now();
                auditFeed(layout, run.feedId, run.runId, step.id, "STEP_COMPLETED", "system", kv("items", String.valueOf(items.size())));
                store.save(layout, run);
                return true;
            }
            se.status = StepStatus.FAILED; se.endTs = now();
            se.message = failed + " of " + items.size() + " parallel item(s) failed";
            auditFeed(layout, run.feedId, run.runId, step.id, "STEP_FAILED", "system", kv("failed", String.valueOf(failed)));
            store.save(layout, run);
            return false;
        } finally {
            pool.shutdownNow();
        }
    }

    private void finish(WorkflowDef def, FeedLayout layout, WorkflowRun run, RunStatus status, String message) {
        synchronized (this) {
            if (isTerminal(run.status)) return;   // idempotent: the first finalisation wins (force-stop vs worker)
            run.status = status;                  // claim the terminal status atomically
        }
        log.info("[{}] RUN terminato {} stato={}{}", run.feedId, run.runId, status, message == null ? "" : " - " + message);
        run.message = message;
        run.endTs = now();
        for (StepExec se : run.steps) {
            if (se.status == StepStatus.PENDING) se.status = StepStatus.SKIPPED;
        }
        store.save(layout, run);
        activeRuns.remove(run.runId);
        runningFeeds.remove(run.feedId, run.runId);   // only clear the lock if it belongs to THIS run (test runs never hold it)
        controls.remove(run.runId);
        testSessions.remove(run.runId);                // drop any step-by-step test session
        java.util.Map<String, String> finDet = new java.util.LinkedHashMap<String, String>();
        finDet.put("status", status.name());
        if (message != null) finDet.put("message", message);
        auditFeed(layout, run.feedId, run.runId, null,
                "RUN_" + (status == RunStatus.SUCCESS ? "COMPLETED" : status.name()), "system", finDet);
    }

    // --------------------------------------------------------------- helpers

    private String resolveScript(String script) {
        java.io.File f = new java.io.File(script);
        if (f.isAbsolute()) return f.getPath();
        return new java.io.File(props.getScriptsDir(), script).getAbsolutePath();
    }

    private void auditFeed(FeedLayout layout, String feedId, String runId, String node,
                           String event, String user, Map<String, String> details) {
        audit.log(layout.auditFile(), feedId, runId, node, event, user, details);
    }

    private static Map<String, String> kv(String... pairs) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (int i = 0; i + 1 < pairs.length; i += 2) m.put(pairs[i], pairs[i + 1]);
        return m;
    }

    private static Map<String, String> prefix(Map<String, String> params) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> e : params.entrySet()) m.put("param." + e.getKey(), e.getValue());
        return m;
    }

    private static String now() { return LocalDateTime.now().format(TS); }
    private static String s(String v) { return v == null ? "(next)" : v; }

    @PreDestroy
    public void shutdown() {
        runPool.shutdownNow();
        scheduler.shutdownNow();
    }
}
