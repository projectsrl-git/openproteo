package com.legalarchive.orchestrator.engine;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import com.legalarchive.orchestrator.platform.PlatformProbe;

/**
 * Starts an external step and, on timeout or Stop, kills everything it started - not only the
 * interpreter. Spec: {@code .claude/LINUX_AND_GUI_CONFIG.md} section 8.7.
 *
 * <h3>Why it exists</h3>
 * {@code Process.destroyForcibly()} kills one process. Measured on a bash script with six kinds of
 * child: all six survive, the foreground one included, and because they hold the step's stdout the
 * caller returns five seconds late. A step that "was killed by the orchestrator" kept working.
 *
 * <h3>How, where it can</h3>
 * On a host with {@code /proc} that is not Windows, the step is launched under {@code setsid} when
 * that is on the PATH, which makes it the leader of a new session without changing its pid, exit
 * code or streams. To kill it: every descendant by parent pid, plus every process whose session is
 * the step's - that second set is what catches a child whose parent has already exited. All are
 * stopped first, so nothing forks between the scan and the kill, then killed.
 *
 * <h3>Degraded modes - reported, never silent</h3>
 * <ul>
 *   <li>{@link Mode#DESCENDANTS}: no {@code setsid}. Children orphaned before the kill survive.</li>
 *   <li>{@link Mode#NONE}: Windows (Java 8 holds a HANDLE there, not a pid, and turning one into
 *       the other needs native code), or no {@code /proc}. Behaviour is what it always was.</li>
 * </ul>
 * A step whose pid cannot be read is killed as in NONE, whatever the host's mode.
 *
 * <h3>What still escapes</h3>
 * A process that leaves BOTH the tree and the session, e.g. {@code ( setsid daemon & )}.
 *
 * <p>JDK only. No signal is sent by anything but {@code /bin/sh -c "kill ..."} with numeric pids:
 * {@code kill} is a shell builtin, so no extra binary is assumed.
 */
public final class ProcessTree {

    public enum Mode { SESSION, DESCENDANTS, TASKKILL, NONE }

    /**
     * {@code orchestrator.windows-tree-kill}. OFF unless set: the Windows tree kill was written
     * without a Windows machine to run it on (see {@link WindowsTreeKill}). Read at each launch
     * and each kill, so a change applies from the next step.
     */
    private static volatile boolean windowsTreeKill = false;

    public static void setWindowsTreeKill(boolean on) { windowsTreeKill = on; }

    /** One started step. */
    public static final class Handle {
        public final Process process;
        /** -1 when it could not be read. */
        public final long pid;
        /** True when the step was launched under setsid. */
        public final boolean session;
        /** For the Windows tree kill: when the process was started, and the argument to know it by. */
        volatile long startedBefore, startedAfter;
        volatile String token = "";
        /** What the last {@link ProcessTree#kill} did, for the step log; null if never killed. */
        public volatile String killReport;
        /** Set the moment a kill begins, before any signal: whoever sees the process die can wait for the report. */
        volatile boolean killing;
        private final java.util.concurrent.CountDownLatch killed = new java.util.concurrent.CountDownLatch(1);

        /**
         * If a kill is under way (Stop, from another thread), waits for it to finish so that
         * {@link #killReport} is there to be logged. Returns at once when nobody is killing.
         */
        public void awaitKill(long millis) {
            if (!killing) return;
            try {
                killed.await(millis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        Handle(Process process, long pid, boolean session) {
            this.process = process;
            this.pid = pid;
            this.session = session;
        }
    }

    private static volatile ProcessTree host;

    /** The instance for this JVM's host, detected once. */
    public static ProcessTree host() {
        ProcessTree t = host;
        if (t == null) {
            synchronized (ProcessTree.class) {
                t = host;
                if (t == null) host = t = new ProcessTree(new PlatformProbe(), Paths.get("/proc"), "/bin/sh");
            }
        }
        return t;
    }

    private final PlatformProbe probe;
    private final Path proc;
    private final String sh;
    private final boolean windows;
    private final Mode mode;
    private final String reason;
    private final String setsid;

    /** For tests: a probe with an injected {@code os.name} / PATH, another proc root, another shell. */
    public ProcessTree(PlatformProbe probe, Path proc, String sh) {
        this.probe = probe;
        this.proc = proc;
        this.sh = sh;
        this.windows = probe.windowsRules();
        String found = null;
        Mode m;
        String why;
        if (windows) {
            m = Mode.NONE;
            why = "Windows: the process id of a step is not available to Java 8, and orchestrator.windows-tree-kill is off";
        } else if (!Files.isReadable(proc.resolve("self").resolve("stat"))) {
            m = Mode.NONE;
            why = "no " + proc + " on this host";
        } else if (!Files.isRegularFile(Paths.get(sh))) {
            m = Mode.NONE;
            why = sh + " not found, so no signal can be sent";
        } else {
            Map<String, Object> r = probe.interpreter("setsid", "setsid");
            if ("FOUND".equals(r.get("status"))) {
                found = String.valueOf(r.get("resolved"));
                m = Mode.SESSION;
                why = "steps are started under " + found;
            } else {
                m = Mode.DESCENDANTS;
                why = "setsid is not on the PATH: a child whose parent has already exited is not found";
            }
        }
        this.mode = m;
        this.reason = why;
        this.setsid = found;
    }

    public Mode mode() { return windows && windowsTreeKill ? Mode.TASKKILL : mode; }
    public String reason() {
        return windows && windowsTreeKill
                ? "Windows: taskkill /T (experimental, orchestrator.windows-tree-kill=true); a program whose parent has already ended is not reached"
                : reason;
    }
    public boolean windows() { return windows; }

    // ------------------------------------------------------------------ launch

    /**
     * Starts the command, under setsid when the host allows. The command list is not modified.
     * stderr is NOT merged into stdout: the step log colours it separately.
     */
    public Handle launch(List<String> command, File workingDir, Map<String, String> extraEnv) throws IOException {
        List<String> cmd = new ArrayList<String>();
        // An interpreter that is not there must fail as it always did - "Cannot run program ..."
        // from the JVM - and not as exit 127 from setsid. So setsid is used only when the
        // program can be found (or cannot be judged: a relative path is resolved per step).
        boolean session = mode == Mode.SESSION && !command.isEmpty()
                && !"NOT_FOUND".equals(probe.interpreter("step", command.get(0)).get("status"));
        if (session) cmd.add(setsid);
        cmd.addAll(command);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        if (workingDir != null && workingDir.isDirectory()) pb.directory(workingDir);
        if (extraEnv != null) pb.environment().putAll(extraEnv);
        long before = System.currentTimeMillis();
        Process p = pb.start();
        long after = System.currentTimeMillis();
        boolean wantPid = mode != Mode.NONE || (windows && windowsTreeKill);
        Handle h = new Handle(p, wantPid ? pidOf(p) : -1L, session);
        h.startedBefore = before;
        h.startedAfter = after;
        h.token = WindowsTreeKill.token(command);
        return h;
    }

    /** The pid: {@code Process.pid()} where it exists (Java 9+), else the private field of Java 8's UNIXProcess. */
    static long pidOf(Process p) {
        try {
            Method m = Process.class.getMethod("pid");
            return ((Number) m.invoke(p)).longValue();
        } catch (Exception notJava9) {
            // fall through
        }
        try {
            Field f = p.getClass().getDeclaredField("pid");
            f.setAccessible(true);
            return ((Number) f.get(p)).longValue();
        } catch (Exception | Error refused) {
            return -1L;
        }
    }

    // ------------------------------------------------------------------ kill

    /** Kills the step and whatever it started. Always ends with {@code destroyForcibly()}. Never throws. */
    public String kill(Handle h) {
        h.killing = true;
        String report;
        try {
            report = killTree(h);
        } catch (Exception | Error e) {
            report = "process tree not killed (" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()) + ") - interpreter only";
        }
        try {
            h.process.destroyForcibly();
        } catch (Exception ignored) {
            // nothing more can be done
        }
        h.killReport = report;
        h.killed.countDown();
        return report;
    }

    private String killTree(Handle h) throws Exception {
        if (windows && windowsTreeKill) {
            return windowsKiller().kill(h.pid, h.startedBefore, h.startedAfter, h.token);
        }
        if (mode == Mode.NONE) return "interpreter only - " + reason;
        if (h.pid <= 1) return "interpreter only - the process id of the step could not be read";
        long self = selfPid();
        Map<Long, long[]> first = snapshot();
        // Session kill only when the step really is the leader of its own session.
        boolean bySession = h.session && first.containsKey(Long.valueOf(h.pid))
                && first.get(Long.valueOf(h.pid))[2] == h.pid;
        Set<Long> victims = victims(first, h.pid, bySession, self);
        if (victims.isEmpty()) return "nothing left to kill";
        signal("STOP", victims);
        // A fork that raced the first scan: its parent is now stopped, so this one is complete.
        Map<Long, long[]> second = snapshot();
        Set<Long> all = victims(second, h.pid, bySession, self);
        Set<Long> kill = confirmed(first, second, all);
        int reused = 0;
        for (Long pid : all) if (second.containsKey(pid) && !kill.contains(pid)) reused++;
        signal("KILL", kill);
        // Anything stopped that will NOT be killed must not be left frozen.
        Set<Long> thaw = new TreeSet<Long>(victims);
        thaw.removeAll(kill);
        if (!thaw.isEmpty()) signal("CONT", thaw);
        return kill.size() + " process(es) killed, " + (bySession ? "by session and descent" : "by descent only"
                + (h.session ? "" : " (" + reason + ")")) + (reused > 0 ? ", " + reused + " skipped: pid reused" : "");
    }

    /**
     * The victims that may really be killed. A pid seen in the first scan must still be the SAME
     * process in the second: the start time is compared, and a pid that now belongs to another
     * process is left alone. One that has gone is dropped; one that appeared between the scans
     * (a fork that raced the first) is kept.
     */
    static Set<Long> confirmed(Map<Long, long[]> first, Map<Long, long[]> second, Set<Long> all) {
        Set<Long> kill = new TreeSet<Long>();
        for (Long pid : all) {
            long[] before = first.get(pid);
            long[] now = second.get(pid);
            if (now == null) continue;
            if (before != null && before[3] != now[3]) continue;
            kill.add(pid);
        }
        return kill;
    }

    /** pid -> { parent, process group, session, start time }. */
    Map<Long, long[]> snapshot() throws IOException {
        Map<Long, long[]> m = new HashMap<Long, long[]>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(proc)) {
            for (Path d : ds) {
                String n = d.getFileName().toString();
                if (n.isEmpty() || n.charAt(0) < '0' || n.charAt(0) > '9') continue;
                long[] s = stat(d.resolve("stat"));
                if (s != null) {
                    try {
                        m.put(Long.valueOf(n), s);
                    } catch (NumberFormatException notAPid) {
                        // not a process directory
                    }
                }
            }
        }
        return m;
    }

    /**
     * Fields of {@code /proc/<pid>/stat}. The command name sits in parentheses and may itself
     * contain spaces and parentheses, so everything is read after the LAST ')'.
     */
    static long[] stat(Path file) {
        try {
            String s = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
            int close = s.lastIndexOf(')');
            if (close < 0) return null;
            String[] f = s.substring(close + 1).trim().split(" ");
            // f[0]=state f[1]=ppid f[2]=pgrp f[3]=session ... f[19]=starttime (field 22 of the file)
            if (f.length < 20) return null;
            return new long[] { Long.parseLong(f[1]), Long.parseLong(f[2]), Long.parseLong(f[3]), Long.parseLong(f[19]) };
        } catch (Exception gone) {
            return null;   // the process ended between the listing and the read
        }
    }

    private long selfPid() {
        try {
            String s = new String(Files.readAllBytes(proc.resolve("self").resolve("stat")), StandardCharsets.ISO_8859_1);
            return Long.parseLong(s.substring(0, s.indexOf(' ')));
        } catch (Exception e) {
            return -1L;
        }
    }

    static Set<Long> victims(Map<Long, long[]> snap, long root, boolean bySession, long self) {
        Set<Long> v = new TreeSet<Long>();
        if (snap.containsKey(Long.valueOf(root))) v.add(Long.valueOf(root));
        Set<Long> tree = new TreeSet<Long>();
        tree.add(Long.valueOf(root));
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Map.Entry<Long, long[]> e : snap.entrySet()) {
                if (!tree.contains(e.getKey()) && tree.contains(Long.valueOf(e.getValue()[0]))) {
                    tree.add(e.getKey());
                    grew = true;
                }
            }
        }
        tree.retainAll(snap.keySet());
        v.addAll(tree);
        if (bySession) {
            for (Map.Entry<Long, long[]> e : snap.entrySet()) {
                if (e.getValue()[2] == root) v.add(e.getKey());
            }
        }
        // Never init, never this JVM, whatever a scan says.
        v.remove(Long.valueOf(0L));
        v.remove(Long.valueOf(1L));
        v.remove(Long.valueOf(self));
        return v;
    }

    private void signal(String name, Set<Long> pids) throws Exception {
        if (pids.isEmpty()) return;
        StringBuilder sb = new StringBuilder("kill -s ").append(name);
        for (Long p : pids) sb.append(' ').append(p.longValue());   // numbers only: nothing to inject
        sb.append(" 2>/dev/null");
        Process k = new ProcessBuilder(sh, "-c", sb.toString()).redirectErrorStream(true).start();
        k.getOutputStream().close();
        if (!k.waitFor(5, TimeUnit.SECONDS)) k.destroyForcibly();
    }

    private volatile WindowsTreeKill windowsKiller;
    /** For tests: the Windows side with a fake command runner. */
    void setWindowsKiller(WindowsTreeKill k) { this.windowsKiller = k; }

    private WindowsTreeKill windowsKiller() {
        WindowsTreeKill k = windowsKiller;
        if (k == null) windowsKiller = k = WindowsTreeKill.forThisJvm("powershell.exe");
        return k;
    }

    /** One line for the Platform page. */
    public String describe() {
        return mode().name().toLowerCase(Locale.ROOT) + " - " + reason();
    }
}
