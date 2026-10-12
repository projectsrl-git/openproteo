package com.legalarchive.orchestrator.diag;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.MonitorInfo;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A diagnostic snapshot of the running instance, as plain text: what the engine is doing, how
 * busy the Java runtime and the host are, how fast the disk answers, and the stack of every
 * thread. Spec: {@code .claude/STALL_DIAGNOSTICS.md}.
 *
 * <p>Written for one question - a run that is RUNNING and does not move: is its thread blocked
 * on a lock, waiting for a file system, starved by garbage collection, or gone? Each section
 * answers part of it, and the text says what each number means.
 *
 * <p><b>JDK only, no Spring.</b> Everything about the engine arrives as plain values
 * ({@link EngineState}), so the class compiles with {@code --release 8} and runs in a test.
 *
 * <p><b>What it never prints</b>, because the page that serves it has no login: system
 * properties other than the five named literally below, environment variables, the JVM's
 * command-line arguments (they can carry passwords), run variables, file contents. It writes
 * one temporary file per probed directory, and deletes it.
 */
public final class StallSnapshot {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx");
    private static final String NL = "\n";
    private static final long MB = 1024L * 1024L;

    // ------------------------------------------------------------------ engine state, as values

    /** One step of a run, as the engine holds it. */
    public static final class StepView {
        public String id;
        public String status;
        public String startTs;
        public String endTs;
        public int attempts;
    }

    /** One live run. */
    public static final class RunView {
        public String feedId;
        public String runId;
        public String status;
        public String trigger;
        public String startTs;
        public int currentIndex;
        public String currentNodeId;
        public String currentNodeKind;
        public String onHoldStepId;
        public String waitingGateId;
        public String lastGateTs;
        public String feedDir;
        public List<StepView> steps = new ArrayList<StepView>();
    }

    /** What the engine reports about itself. {@code error} is set when it could not be read. */
    public static final class EngineState {
        public List<RunView> runs = new ArrayList<RunView>();
        public int admitted = -1;
        public int pending = -1;
        public int maxParallel = -1;
        public String error;
    }

    /** Measures one directory. Replaceable in tests, to simulate a file system that does not answer. */
    public interface DirProbe {
        String probe(File dir) throws Exception;
    }

    // ------------------------------------------------------------------ settings

    /** How long the runtime is watched to measure garbage collection and CPU. */
    public long sampleMs = 10_000L;
    /** How long a directory probe may take before the snapshot gives up on it. */
    public long probeTimeoutMs = 15_000L;
    /** Frames printed per thread. */
    public int maxFrames = 80;
    /** The directory probe. */
    public DirProbe dirProbe = new DirProbe() {
        @Override public String probe(File dir) throws Exception { return probeDirectory(dir); }
    };

    /**
     * Directories whose probe has not returned yet. A file system that does not answer keeps the
     * probe's thread for as long as it does not answer; a second probe of the same directory
     * would only add a second stuck thread, so it is not started.
     */
    private static final Map<String, Long> STUCK = new ConcurrentHashMap<String, Long>();
    private static final ExecutorService PROBES = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "op-diag-probe");
        t.setDaemon(true);
        return t;
    });

    // ------------------------------------------------------------------ the snapshot

    /** Builds the snapshot. Takes about {@link #sampleMs} plus the directory probes. Never throws. */
    public String take(String reason, EngineState engine, List<File> dirs) {
        StringBuilder b = new StringBuilder(64 * 1024);
        try {
            header(b, reason);
            Sample s0 = sample();
            engine(b, engine);
            runnerThreads(b, "Run threads now (the full dump at the end is taken " + duration(sampleMs) + " later: a frame that has not changed between the two is where the run waits)");
            slowWrites(b);
            probes(b, dirs);
            long left = sampleMs - (System.currentTimeMillis() - s0.wallMs);
            if (left > 0) sleep(left);
            Sample s1 = sample();
            runtime(b, s0, s1);
            memory(b);
            host(b);
            threads(b);
        } catch (Throwable t) {
            b.append(NL).append("The snapshot stopped early: ").append(t).append(NL);
        }
        b.append(NL).append("== end of snapshot").append(NL);
        return b.toString();
    }

    private static void header(StringBuilder b, String reason) {
        RuntimeMXBean rt = ManagementFactory.getRuntimeMXBean();
        b.append("OpenProteo diagnostic snapshot").append(NL);
        b.append("taken:    ").append(OffsetDateTime.now().format(TS)).append(NL);
        b.append("reason:   ").append(reason == null ? "" : reason).append(NL);
        b.append("java:     ").append(System.getProperty("java.version")).append(" (").append(System.getProperty("java.vendor"))
                .append("), ").append(rt.getVmName()).append(' ').append(rt.getVmVersion()).append(NL);
        b.append("host:     ").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.arch"))
                .append(", ").append(Runtime.getRuntime().availableProcessors()).append(" processor(s) seen by the JVM").append(NL);
        b.append("up for:   ").append(duration(rt.getUptime())).append(NL);
        b.append("cwd:      ").append(System.getProperty("user.dir")).append(NL);
    }

    // ------------------------------------------------------------------ engine

    private static void engine(StringBuilder b, EngineState e) {
        section(b, "Engine");
        if (e == null) { b.append("not available").append(NL); return; }
        if (e.error != null) b.append("could not be read completely: ").append(e.error).append(NL);
        b.append("runs admitted: ").append(e.admitted).append(" of at most ").append(e.maxParallel)
                .append("; waiting for admission: ").append(e.pending).append(NL);
        if (e.runs.isEmpty()) { b.append("no live run").append(NL); return; }
        for (RunView r : e.runs) {
            b.append(NL).append("run ").append(r.runId).append(" (feed ").append(r.feedId).append(")").append(NL);
            b.append("  status ").append(r.status).append(", trigger ").append(r.trigger).append(", started ").append(r.startTs).append(NL);
            b.append("  at node #").append(r.currentIndex).append(' ').append(nz(r.currentNodeId))
                    .append(r.currentNodeKind == null ? "" : " (" + r.currentNodeKind + ")").append(NL);
            if (r.onHoldStepId != null) b.append("  on hold at ").append(r.onHoldStepId).append(NL);
            if (r.waitingGateId != null) b.append("  waiting at gate ").append(r.waitingGateId).append(NL);
            StepView running = null, last = null;
            int done = 0;
            for (StepView s : r.steps) {
                if ("RUNNING".equals(s.status)) running = s;
                if (s.endTs != null) {
                    done++;
                    if (last == null || s.endTs.compareTo(last.endTs) > 0) last = s;
                }
            }
            if (running != null) b.append("  step running: ").append(running.id).append(" since ").append(running.startTs)
                    .append(", attempt ").append(running.attempts).append(NL);
            else b.append("  no step running").append(NL);
            if (last != null) b.append("  last step ended: ").append(last.id).append(" (").append(last.status).append(") at ").append(last.endTs).append(NL);
            if (r.lastGateTs != null) b.append("  last gate evaluated at ").append(r.lastGateTs).append(NL);
            b.append("  steps ended so far: ").append(done).append(" of ").append(r.steps.size()).append(NL);
        }
    }

    private static void slowWrites(StringBuilder b) {
        section(b, "Bookkeeping writes (run JSON, audit line)");
        b.append("Each is done between two steps, under a lock. 'wait' is the time spent waiting for the lock,"
                + " 'work' the time spent writing.").append(NL);
        Map<String, IoTimings.Totals> totals = IoTimings.totals();
        if (totals.isEmpty()) b.append("none since the start").append(NL);
        for (Map.Entry<String, IoTimings.Totals> t : totals.entrySet()) {
            IoTimings.Totals v = t.getValue();
            b.append(t.getKey()).append(": ").append(v.calls).append(" call(s), ").append(v.slowCalls).append(" of ")
                    .append(IoTimings.SLOW_MS / 1000).append(" s or more; longest wait ").append(v.maxWaitMs)
                    .append(" ms, longest write ").append(v.maxWorkMs).append(" ms").append(NL);
        }
        List<IoTimings.Slow> slow = IoTimings.slowCalls();
        if (!slow.isEmpty()) {
            b.append("the slow ones, oldest first:").append(NL);
            for (IoTimings.Slow s : slow) {
                b.append("  ").append(s.at).append("  ").append(s.kind).append("  wait ").append(s.waitMs).append(" ms, work ")
                        .append(s.workMs).append(" ms  ").append(s.detail).append(NL);
            }
        }
    }

    // ------------------------------------------------------------------ disk

    private void probes(StringBuilder b, List<File> dirs) {
        section(b, "Disk: one 4 KB file written, synced and deleted in each directory");
        if (dirs == null || dirs.isEmpty()) { b.append("no directory to probe").append(NL); return; }
        Set<String> seen = new LinkedHashSet<String>();
        for (File d : dirs) {
            if (d == null) continue;
            String key = d.getAbsolutePath();
            if (!seen.add(key)) continue;
            b.append(key).append(NL).append("  ");
            Long since = STUCK.get(key);
            if (since != null) {
                b.append("NOT PROBED AGAIN: the probe started ").append(duration(System.currentTimeMillis() - since))
                        .append(" ago has still not returned. The file system is not answering.").append(NL);
                continue;
            }
            STUCK.put(key, System.currentTimeMillis());
            final File dir = d;
            Future<String> f = PROBES.submit(() -> {
                try { return dirProbe.probe(dir); }
                finally { STUCK.remove(dir.getAbsolutePath()); }
            });
            try {
                b.append(f.get(probeTimeoutMs, TimeUnit.MILLISECONDS)).append(NL);
            } catch (TimeoutException te) {
                b.append("DID NOT COMPLETE in ").append(probeTimeoutMs / 1000)
                        .append(" s. The file system is not answering; the probe is left waiting.").append(NL);
            } catch (Exception ex) {
                Throwable c = ex.getCause() == null ? ex : ex.getCause();
                b.append("failed: ").append(c).append(NL);
            }
        }
    }

    /** The default probe: create, write 4 KB, sync, delete; then count the directory's entries. */
    static String probeDirectory(File dir) throws Exception {
        if (!dir.isDirectory()) return "not a directory (or not reachable)";
        long t0 = System.nanoTime();
        File f = File.createTempFile(".op-diag-", ".tmp", dir);
        long t1 = System.nanoTime();
        byte[] block = new byte[4096];
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(block);
            out.getChannel().force(true);
        }
        long t2 = System.nanoTime();
        boolean deleted = f.delete();
        long t3 = System.nanoTime();
        String[] names = dir.list();
        long t4 = System.nanoTime();
        long free = dir.getUsableSpace();
        return "create " + ms(t1 - t0) + " ms, write+sync " + ms(t2 - t1) + " ms, delete " + ms(t3 - t2) + " ms"
                + (deleted ? "" : " (NOT deleted: " + f.getName() + ")")
                + "; list " + (names == null ? "failed" : names.length + " entries") + " in " + ms(t4 - t3) + " ms"
                + "; free " + (free / MB) + " MB";
    }

    // ------------------------------------------------------------------ runtime: GC and CPU over the sample

    private static final class Sample {
        long wallMs;
        long gcCount;
        long gcMs;
        long cpuNs = -1;
    }

    private static Sample sample() {
        Sample s = new Sample();
        s.wallMs = System.currentTimeMillis();
        for (GarbageCollectorMXBean g : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (g.getCollectionCount() > 0) s.gcCount += g.getCollectionCount();
            if (g.getCollectionTime() > 0) s.gcMs += g.getCollectionTime();
        }
        s.cpuNs = processCpuNs();
        return s;
    }

    private static void runtime(StringBuilder b, Sample s0, Sample s1) {
        long wall = Math.max(1L, s1.wallMs - s0.wallMs);
        section(b, "Java runtime over the last " + duration(wall));
        long gcMs = s1.gcMs - s0.gcMs;
        b.append("garbage collection: ").append(s1.gcCount - s0.gcCount).append(" collection(s), ").append(gcMs)
                .append(" ms = ").append(pct(gcMs, wall)).append(" of the time").append(NL);
        b.append("  (a heap that is too small shows here: well above 20% means the runtime spends its time freeing memory"
                + " instead of working)").append(NL);
        if (s0.cpuNs >= 0 && s1.cpuNs >= 0) {
            int cpus = Math.max(1, Runtime.getRuntime().availableProcessors());
            long used = s1.cpuNs - s0.cpuNs;
            b.append("process CPU: ").append(pct(used / 1_000_000L, wall)).append(" of one processor (")
                    .append(pct(used / 1_000_000L, wall * cpus)).append(" of all ").append(cpus).append(")").append(NL);
        } else {
            b.append("process CPU: not reported by this Java runtime").append(NL);
        }
        for (GarbageCollectorMXBean g : ManagementFactory.getGarbageCollectorMXBeans()) {
            b.append("  collector ").append(g.getName()).append(": ").append(g.getCollectionCount()).append(" collections, ")
                    .append(g.getCollectionTime()).append(" ms since the start").append(NL);
        }
    }

    // ------------------------------------------------------------------ memory

    private static void memory(StringBuilder b) {
        section(b, "Memory");
        Runtime rt = Runtime.getRuntime();
        long max = rt.maxMemory(), total = rt.totalMemory(), free = rt.freeMemory();
        b.append("heap: ").append((total - free) / MB).append(" MB in use, ").append(total / MB).append(" MB reserved, ")
                .append(max == Long.MAX_VALUE ? "no limit" : (max / MB) + " MB maximum (-Xmx)").append(NL);
        MemoryUsage nh = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
        b.append("non-heap: ").append(nh.getUsed() / MB).append(" MB in use").append(NL);
        b.append("pools (in use / reserved / maximum; after the last collection):").append(NL);
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
            MemoryUsage u = p.getUsage();
            MemoryUsage c = null;
            try { c = p.getCollectionUsage(); } catch (Throwable ignored) { }
            b.append("  ").append(p.getName()).append(" [").append(p.getType()).append("]: ")
                    .append(u == null ? "?" : mb(u.getUsed()) + " / " + mb(u.getCommitted()) + " / " + mbMax(u.getMax()))
                    .append(c == null ? "" : "; after last collection " + mb(c.getUsed()))
                    .append(NL);
        }
        b.append("  (a pool whose use AFTER the last collection is close to its maximum is full of live data)").append(NL);
    }

    // ------------------------------------------------------------------ host

    private static void host(StringBuilder b) {
        section(b, "Host, as the Java runtime sees it");
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        double load = os.getSystemLoadAverage();
        b.append("load average (1 min): ").append(load < 0 ? "not reported" : String.format(java.util.Locale.ROOT, "%.2f", load))
                .append(" for ").append(os.getAvailableProcessors()).append(" processor(s)").append(NL);
        hostValue(b, "physical memory, total", true, "getTotalPhysicalMemorySize", "getTotalPhysicalMemory");
        hostValue(b, "physical memory, free", true, "getFreePhysicalMemorySize");
        hostValue(b, "swap / paging space, total", true, "getTotalSwapSpaceSize");
        hostValue(b, "swap / paging space, free", true, "getFreeSwapSpaceSize");
        hostValue(b, "virtual memory committed to this process", true, "getCommittedVirtualMemorySize");
        hostValue(b, "system CPU load (0-1)", false, "getSystemCpuLoad");
        hostValue(b, "process CPU load (0-1)", false, "getProcessCpuLoad");
    }

    private static void hostValue(StringBuilder b, String label, boolean bytes, String... names) {
        Object v = osValue(names);
        if (v == null) return;
        b.append(label).append(": ");
        if (bytes && v instanceof Number) {
            long n = ((Number) v).longValue();
            b.append(n < 0 ? "not reported" : (n / MB) + " MB");
        } else if (v instanceof Double) {
            double d = (Double) v;
            b.append(d < 0 ? "not reported" : String.format(java.util.Locale.ROOT, "%.2f", d));
        } else {
            b.append(v);
        }
        b.append(NL);
    }

    /**
     * A value of the operating-system bean that is not in the standard interface: asked by name,
     * through whichever PUBLIC interface of the bean declares it (com.sun.management on HotSpot,
     * com.ibm.lang.management on the IBM runtime). Null when no interface has it.
     */
    static Object osValue(String... names) {
        Object bean = ManagementFactory.getOperatingSystemMXBean();
        Method m = osMethod(bean, names);
        if (m == null) return null;
        try { return m.invoke(bean); } catch (Throwable t) { return null; }
    }

    private static Method osMethod(Object bean, String... names) {
        List<Class<?>> ifaces = new ArrayList<Class<?>>();
        for (Class<?> c = bean.getClass(); c != null; c = c.getSuperclass()) collectInterfaces(c, ifaces);
        for (String n : names) {
            for (Class<?> i : ifaces) {
                if (!Modifier.isPublic(i.getModifiers())) continue;
                try { return i.getMethod(n); } catch (NoSuchMethodException ignored) { } catch (Throwable t) { return null; }
            }
        }
        return null;
    }

    private static void collectInterfaces(Class<?> c, List<Class<?>> out) {
        for (Class<?> i : c.getInterfaces()) {
            if (!out.contains(i)) out.add(i);
            collectInterfaces(i, out);
        }
    }

    /**
     * Process CPU time in nanoseconds, or -1. The IBM runtime's own {@code getProcessCpuTime}
     * counts in units of 100 ns; its {@code getProcessCpuTimeByNS} and the com.sun.management
     * method count in nanoseconds.
     */
    static long processCpuNs() {
        Object bean = ManagementFactory.getOperatingSystemMXBean();
        Method byNs = osMethod(bean, "getProcessCpuTimeByNS");
        try {
            if (byNs != null) return ((Number) byNs.invoke(bean)).longValue();
            Method m = osMethod(bean, "getProcessCpuTime");
            if (m == null) return -1L;
            long v = ((Number) m.invoke(bean)).longValue();
            if (v < 0) return -1L;
            return m.getDeclaringClass().getName().startsWith("com.ibm.") ? v * 100L : v;
        } catch (Throwable t) {
            return -1L;
        }
    }

    // ------------------------------------------------------------------ threads

    private void threads(StringBuilder b) {
        ThreadMXBean tm = ManagementFactory.getThreadMXBean();
        section(b, "Threads (" + tm.getThreadCount() + " live, peak " + tm.getPeakThreadCount() + ")");
        long[] dead = null;
        try {
            dead = tm.isSynchronizerUsageSupported() ? tm.findDeadlockedThreads() : tm.findMonitorDeadlockedThreads();
        } catch (Throwable ignored) { }
        b.append(dead == null || dead.length == 0 ? "no deadlock found" : "DEADLOCK between " + dead.length + " thread(s)").append(NL);
        b.append("The engine runs each run on a thread named wf-runner; they come first. A run that is RUNNING with no"
                + " wf-runner thread below has lost its thread.").append(NL);
        ThreadInfo[] all;
        try {
            all = tm.dumpAllThreads(tm.isObjectMonitorUsageSupported(), tm.isSynchronizerUsageSupported());
        } catch (Throwable t) {
            all = tm.dumpAllThreads(false, false);
        }
        List<ThreadInfo> list = new ArrayList<ThreadInfo>();
        for (ThreadInfo ti : all) if (ti != null) list.add(ti);
        Collections.sort(list, new Comparator<ThreadInfo>() {
            @Override public int compare(ThreadInfo a, ThreadInfo c) {
                int ra = rank(a), rc = rank(c);
                if (ra != rc) return ra - rc;
                return a.getThreadName().compareTo(c.getThreadName());
            }
        });
        boolean cpu = false;
        try { cpu = tm.isThreadCpuTimeSupported() && tm.isThreadCpuTimeEnabled(); } catch (Throwable ignored) { }
        for (ThreadInfo ti : list) {
            b.append(NL).append('"').append(ti.getThreadName()).append("\" id=").append(ti.getThreadId()).append(' ').append(ti.getThreadState());
            if (cpu) {
                long ns = -1;
                try { ns = tm.getThreadCpuTime(ti.getThreadId()); } catch (Throwable ignored) { }
                if (ns >= 0) b.append(" cpu=").append(ns / 1_000_000L).append("ms");
            }
            if (ti.getLockName() != null) {
                b.append(" on ").append(ti.getLockName());
                if (ti.getLockOwnerName() != null) b.append(" held by \"").append(ti.getLockOwnerName()).append("\" id=").append(ti.getLockOwnerId());
            }
            b.append(NL);
            StackTraceElement[] st = ti.getStackTrace();
            MonitorInfo[] mons = ti.getLockedMonitors();
            int n = Math.min(st.length, maxFrames);
            for (int i = 0; i < n; i++) {
                b.append("    at ").append(st[i]).append(NL);
                if (mons != null) for (MonitorInfo mi : mons) {
                    if (mi.getLockedStackDepth() == i) b.append("      - holds ").append(mi).append(NL);
                }
            }
            if (st.length > n) b.append("    ... ").append(st.length - n).append(" more frame(s)").append(NL);
            LockInfo[] syncs = ti.getLockedSynchronizers();
            if (syncs != null) for (LockInfo li : syncs) b.append("      - holds synchronizer ").append(li).append(NL);
        }
    }

    /** The wf-runner threads only, top frames: the first of the two looks at them. */
    private static void runnerThreads(StringBuilder b, String title) {
        section(b, title);
        ThreadMXBean tm = ManagementFactory.getThreadMXBean();
        int found = 0;
        for (ThreadInfo ti : tm.dumpAllThreads(false, false)) {
            if (ti == null || !ti.getThreadName().startsWith("wf-runner")) continue;
            found++;
            b.append('"').append(ti.getThreadName()).append("\" id=").append(ti.getThreadId()).append(' ').append(ti.getThreadState());
            if (ti.getLockName() != null) {
                b.append(" on ").append(ti.getLockName());
                if (ti.getLockOwnerName() != null) b.append(" held by \"").append(ti.getLockOwnerName()).append("\" id=").append(ti.getLockOwnerId());
            }
            b.append(NL);
            StackTraceElement[] st = ti.getStackTrace();
            for (int i = 0; i < Math.min(st.length, 25); i++) b.append("    at ").append(st[i]).append(NL);
        }
        if (found == 0) b.append("no wf-runner thread is alive").append(NL);
    }

    private static int rank(ThreadInfo t) {
        String n = t.getThreadName();
        if (n.startsWith("wf-runner")) return 0;
        if (n.startsWith("wf-")) return 1;
        if (t.getThreadState() == Thread.State.BLOCKED) return 2;
        if (n.startsWith("op-diag")) return 9;
        return 5;
    }

    // ------------------------------------------------------------------ helpers

    private static void section(StringBuilder b, String title) {
        b.append(NL).append("== ").append(title).append(NL);
    }

    private static String nz(String s) { return s == null ? "?" : s; }
    private static long ms(long nanos) { return nanos / 1_000_000L; }
    private static String mb(long bytes) { return bytes < 0 ? "?" : (bytes / MB) + " MB"; }
    private static String mbMax(long bytes) { return bytes < 0 ? "no limit" : (bytes / MB) + " MB"; }

    static String pct(long part, long whole) {
        if (whole <= 0) return "?";
        return String.format(java.util.Locale.ROOT, "%.0f%%", 100.0 * part / whole);
    }

    static String duration(long ms) {
        long s = ms / 1000;
        if (s < 120) return s + " s";
        long m = s / 60;
        if (m < 120) return m + " min " + (s % 60) + " s";
        return (m / 60) + " h " + (m % 60) + " min";
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
