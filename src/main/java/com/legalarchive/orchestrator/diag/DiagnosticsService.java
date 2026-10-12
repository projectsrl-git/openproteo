package com.legalarchive.orchestrator.diag;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.legalarchive.orchestrator.config.AppProperties;
import com.legalarchive.orchestrator.diag.StallSnapshot.EngineState;
import com.legalarchive.orchestrator.diag.StallSnapshot.RunView;
import com.legalarchive.orchestrator.diag.StallSnapshot.StepView;
import com.legalarchive.orchestrator.engine.WorkflowEngine;
import com.legalarchive.orchestrator.model.def.GateDef;
import com.legalarchive.orchestrator.model.def.LoopDef;
import com.legalarchive.orchestrator.model.def.LoopEndDef;
import com.legalarchive.orchestrator.model.def.NodeDef;
import com.legalarchive.orchestrator.model.def.WorkflowDef;
import com.legalarchive.orchestrator.model.run.GateExec;
import com.legalarchive.orchestrator.model.run.StepExec;
import com.legalarchive.orchestrator.model.run.WorkflowRun;
import com.legalarchive.orchestrator.registry.WorkflowRegistry;
import com.legalarchive.orchestrator.store.FeedLayout;

/**
 * Diagnostic snapshots: taken automatically when a run stops moving between two steps
 * ({@link StallWatchdog}), or on request from the Platform page. Spec:
 * {@code .claude/STALL_DIAGNOSTICS.md}.
 *
 * <p>The last {@link #KEEP_IN_MEMORY} snapshots are kept in memory, so that they can be
 * downloaded even when the disk is the thing that does not answer; each is also written, by a
 * thread of its own, under the application log directory in {@code diag/}, where the last
 * {@link #KEEP_ON_DISK} are kept.
 *
 * <p>It only looks. It never stops, resumes or changes a run, and it has no setting: the
 * thresholds are constants, named in the spec and in USAGE.
 */
@Component
public class DiagnosticsService {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticsService.class);

    public static final int KEEP_IN_MEMORY = 12;
    public static final int KEEP_ON_DISK = 50;
    public static final long CHECK_EVERY_SEC = 30L;
    /** A snapshot on request is refused when the last one on request is younger than this. */
    public static final long MANUAL_FLOOR_MS = 30_000L;

    private static final DateTimeFormatter ID = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** One snapshot. */
    public static final class Entry {
        public final String id;
        public final String takenAt;
        public final String reason;
        public final String feedId;
        public final String runId;
        public final boolean automatic;
        public final String text;
        public volatile String savedAs;
        public volatile String saveError;
        Entry(String id, String takenAt, String reason, String feedId, String runId, boolean automatic, String text) {
            this.id = id; this.takenAt = takenAt; this.reason = reason; this.feedId = feedId; this.runId = runId;
            this.automatic = automatic; this.text = text;
        }
    }

    private final WorkflowEngine engine;
    private final WorkflowRegistry registry;
    private final AppProperties props;
    private final Environment environment;

    private final StallWatchdog watchdog = new StallWatchdog();
    private final Deque<Entry> entries = new ArrayDeque<Entry>();
    private final AtomicInteger seq = new AtomicInteger();
    private final AtomicBoolean saving = new AtomicBoolean(false);
    private volatile long lastManual;

    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "op-diag-watchdog"));
    private final ExecutorService taker = Executors.newSingleThreadExecutor(r -> daemon(r, "op-diag-snapshot"));
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> daemon(r, "op-diag-writer"));
    private final ExecutorService lookups = Executors.newCachedThreadPool(r -> daemon(r, "op-diag-lookup"));
    static final long REGISTRY_WAIT_MS = 5_000L;

    public DiagnosticsService(WorkflowEngine engine, WorkflowRegistry registry, AppProperties props, Environment environment) {
        this.engine = engine;
        this.registry = registry;
        this.props = props;
        this.environment = environment;
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    @PostConstruct
    public void start() {
        timer.scheduleWithFixedDelay(this::tick, CHECK_EVERY_SEC * 2, CHECK_EVERY_SEC, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void stop() {
        timer.shutdownNow();
        taker.shutdownNow();
        writer.shutdownNow();
        lookups.shutdownNow();
    }

    /** One look at the engine; a snapshot for each run found stuck. */
    void tick() {
        try {
            EngineState state = engineState();
            for (final StallWatchdog.Due d : watchdog.check(state, System.currentTimeMillis())) {
                log.warn("[{}] {} - taking a diagnostic snapshot (Platform page)", d.run.feedId, d.reason);
                taker.submit(() -> {
                    try { record(d.reason, d.run.feedId, d.run.runId, true, probeDirs(d.run)); }
                    catch (Throwable t) { log.error("diagnostic snapshot failed: {}", t.toString()); }
                });
            }
        } catch (Throwable t) {
            log.error("stall watchdog: {}", t.toString());
        }
    }

    /**
     * A snapshot on request, taken on the caller's thread (about ten seconds). Returns null when
     * the last one on request is younger than {@link #MANUAL_FLOOR_MS}.
     */
    public Entry takeNow(String who) {
        long now = System.currentTimeMillis();
        synchronized (this) {
            if (now - lastManual < MANUAL_FLOOR_MS) return null;
            lastManual = now;
        }
        return record("taken on request from the Platform page" + (who == null || who.isEmpty() ? "" : " by " + who),
                null, null, false, probeDirs(null));
    }

    private Entry record(String reason, String feedId, String runId, boolean automatic, List<File> dirs) {
        StallSnapshot snap = new StallSnapshot();
        String text = snap.take(reason, engineState(), dirs);
        LocalDateTime now = LocalDateTime.now();
        Entry e = new Entry(now.format(ID) + "-" + String.format("%03d", seq.incrementAndGet() % 1000), now.format(TS),
                reason, feedId, runId, automatic, text);
        synchronized (entries) {
            entries.addFirst(e);
            while (entries.size() > KEEP_IN_MEMORY) entries.removeLast();
        }
        save(e);
        return e;
    }

    /** Written by a thread of its own: a disk that does not answer must not hold the watchdog. */
    private void save(final Entry e) {
        if (!saving.compareAndSet(false, true)) {
            e.saveError = "not saved: the previous snapshot is still being written (the disk is not answering?)";
            return;
        }
        writer.submit(() -> {
            try {
                File dir = new File(logDir(), "diag");
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("cannot create " + dir.getAbsolutePath());
                File f = new File(dir, "snapshot-" + e.id + ".txt");
                Files.write(f.toPath(), e.text.getBytes(StandardCharsets.UTF_8));
                prune(dir);
                e.savedAs = f.getAbsolutePath();
            } catch (Throwable t) {
                e.saveError = "not saved: " + t;
            } finally {
                saving.set(false);
            }
        });
    }

    private static void prune(File dir) {
        File[] fs = dir.listFiles((d, n) -> n.startsWith("snapshot-") && n.endsWith(".txt"));
        if (fs == null || fs.length <= KEEP_ON_DISK) return;
        Arrays.sort(fs, (a, b) -> a.getName().compareTo(b.getName()));
        for (int i = 0; i < fs.length - KEEP_ON_DISK; i++) {
            if (!fs[i].delete()) log.warn("diagnostics: could not remove {}", fs[i].getAbsolutePath());
        }
    }

    /** Newest first. */
    public List<Entry> entries() {
        synchronized (entries) { return new ArrayList<Entry>(entries); }
    }

    public Entry entry(String id) {
        synchronized (entries) {
            for (Entry e : entries) if (e.id.equals(id)) return e;
        }
        return null;
    }

    /** The application log directory, as the Platform page computes it; "logs" when not configured. */
    File logDir() {
        String name = environment.getProperty("logging.file.name");
        if (name != null && !name.trim().isEmpty()) {
            File parent = new File(name.trim()).getParentFile();
            return parent == null ? new File(".") : parent;
        }
        String path = environment.getProperty("logging.file.path");
        return path == null || path.trim().isEmpty() ? new File("logs") : new File(path.trim());
    }

    /** The stuck run's feed folder, the feeds base directory, the temporary directory. */
    private List<File> probeDirs(RunView run) {
        List<File> out = new ArrayList<File>();
        if (run != null && run.feedDir != null) out.add(new File(run.feedDir));
        String base = props.getDefaultBaseDir();
        if (base != null && !base.trim().isEmpty()) out.add(new File(base.trim()));
        String tmp = System.getProperty("java.io.tmpdir");
        if (tmp != null) out.add(new File(tmp));
        return out;
    }

    /** The engine's live runs as plain values. Never throws: what cannot be read is said. */
    EngineState engineState() {
        EngineState st = new EngineState();
        try {
            st.admitted = engine.activeRunCount();
            st.pending = engine.pendingRunCount();
            st.maxParallel = engine.maxParallelRuns();
            for (WorkflowRun r : engine.liveRuns()) {
                RunView v = new RunView();
                v.feedId = r.feedId;
                v.runId = r.runId;
                v.status = r.status == null ? null : r.status.name();
                v.trigger = r.trigger;
                v.startTs = r.startTs;
                v.currentIndex = r.currentIndex;
                v.onHoldStepId = r.onHoldStepId;
                v.waitingGateId = r.waitingGateId;
                for (StepExec s : new ArrayList<StepExec>(r.steps)) {
                    StepView sv = new StepView();
                    sv.id = s.stepId;
                    sv.status = s.status == null ? null : s.status.name();
                    sv.startTs = s.startTs;
                    sv.endTs = s.endTs;
                    sv.attempts = s.attempts;
                    v.steps.add(sv);
                }
                for (GateExec g : new ArrayList<GateExec>(r.gates)) {
                    if (g.ts != null && (v.lastGateTs == null || g.ts.compareTo(v.lastGateTs) > 0)) v.lastGateTs = g.ts;
                }
                st.runs.add(v);
            }
        } catch (Throwable t) {
            st.error = t.toString();
        }
        names(st);
        return st;
    }

    /**
     * The name of each run's current node and its feed folder, from the workflow registry. The
     * registry's methods are synchronized; if another thread holds it and does not let go, the
     * snapshot must still be taken, so the lookup is given {@link #REGISTRY_WAIT_MS} and is
     * otherwise reported as not answering.
     */
    private void names(final EngineState st) {
        if (st.runs.isEmpty()) return;
        final List<String[]> found = new ArrayList<String[]>();
        java.util.concurrent.Future<?> f = lookups.submit(() -> {
            for (RunView v : st.runs) {
                String id = null, kind = null, dir = null;
                WorkflowDef def = registry.get(v.feedId);
                if (def != null && v.currentIndex >= 0 && v.currentIndex < def.nodes.size()) {
                    NodeDef n = def.nodes.get(v.currentIndex);
                    id = n.id;
                    kind = n instanceof GateDef ? "gate" : n instanceof LoopDef ? "loop" : n instanceof LoopEndDef ? "end of loop" : "step";
                }
                FeedLayout layout = registry.layout(v.feedId);
                if (layout != null) dir = layout.feedDir.toString();
                synchronized (found) { found.add(new String[] { v.runId, id, kind, dir }); }
            }
        });
        try {
            f.get(REGISTRY_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            st.error = (st.error == null ? "" : st.error + "; ") + "the workflow registry did not answer in "
                    + (REGISTRY_WAIT_MS / 1000) + " s (another thread holds it): node names are missing";
        } catch (Throwable t) {
            st.error = (st.error == null ? "" : st.error + "; ") + "workflow registry: " + t;
        }
        synchronized (found) {
            for (String[] x : found) for (RunView v : st.runs) {
                if (!v.runId.equals(x[0])) continue;
                v.currentNodeId = x[1];
                v.currentNodeKind = x[2];
                v.feedDir = x[3];
            }
        }
    }
}
