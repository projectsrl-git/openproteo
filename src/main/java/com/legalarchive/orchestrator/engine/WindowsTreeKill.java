package com.legalarchive.orchestrator.engine;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Killing a step's process tree on WINDOWS. <b>Experimental, written blind, and off unless
 * {@code orchestrator.windows-tree-kill=true}.</b>
 *
 * <p>Nothing in this class has ever been executed on Windows by its author. What HAS been run, on
 * Linux, is the part that decides: given what Windows reports about the JVM's children, which one
 * (if any) is the step. The part that talks to Windows - one PowerShell query, one
 * {@code taskkill} - is two command lines, and they are the same two the measurement kit in
 * {@code tools/windows-proctree-kit} runs. Spec: {@code .claude/LINUX_AND_GUI_CONFIG.md} 9.6.
 *
 * <h3>The problem</h3>
 * On Java 8 a Windows {@code Process} holds a HANDLE, not a process id, and no pure-Java call
 * turns one into the other. So the step has to be FOUND: among the processes whose parent is this
 * JVM, the one created inside the moment the step was started and whose command line contains
 * the step's own distinctive argument. On Java 9+ {@code Process.pid()} answers directly and none
 * of this is used.
 *
 * <h3>The one rule</h3>
 * <b>A kill that could hit the wrong process is not attempted.</b> Exactly one candidate, or
 * nothing: two steps with the same command line started in the same instant cannot be told apart,
 * and then only the interpreter is killed, as it always was, and the log says why.
 *
 * <h3>What it reaches</h3>
 * {@code taskkill /T} follows parent links. A child whose parent has already exited is not found:
 * Windows keeps no session to find it by. So this is the equivalent of the "descendants only"
 * mode on Linux, not of the session kill.
 */
public final class WindowsTreeKill {

    /** How far a process's creation time may be from the launch window, either side. */
    static final long SLACK_MS = 2000L;

    /** One child of the JVM, as Windows reports it. */
    static final class Candidate {
        final long pid;
        final long parent;
        final long createdMs;
        final String commandLine;

        Candidate(long pid, long parent, long createdMs, String commandLine) {
            this.pid = pid;
            this.parent = parent;
            this.createdMs = createdMs;
            this.commandLine = commandLine;
        }
    }

    /** Runs a command and returns its output lines. Replaced in tests. */
    interface Runner {
        List<String> run(List<String> command) throws Exception;
    }

    private final Runner runner;
    private final long jvmPid;
    private final String powershell;

    WindowsTreeKill(Runner runner, long jvmPid, String powershell) {
        this.runner = runner;
        this.jvmPid = jvmPid;
        this.powershell = powershell;
    }

    /** The instance for this JVM. {@code powershell} is the executable used for the one query. */
    static WindowsTreeKill forThisJvm(String powershell) {
        return new WindowsTreeKill(new Runner() {
            @Override public List<String> run(List<String> command) throws Exception {
                Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
                p.getOutputStream().close();
                List<String> out = new ArrayList<String>();
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String l;
                while ((l = r.readLine()) != null) out.add(l);
                if (!p.waitFor(20, TimeUnit.SECONDS)) p.destroyForcibly();
                return out;
            }
        }, thisJvmPid(), powershell);
    }

    /** The JVM's own pid on Java 8: the part before '@' of the runtime name. -1 if it is not a number. */
    static long thisJvmPid() {
        try {
            String n = ManagementFactory.getRuntimeMXBean().getName();
            int at = n.indexOf('@');
            return Long.parseLong(at > 0 ? n.substring(0, at) : n);
        } catch (RuntimeException e) {
            return -1L;
        }
    }

    /**
     * The argument that tells one step's command line from another's: the longest one. For a
     * PowerShell step that is the Base64 of its bootstrap, for cmd and jar the script path.
     */
    static String token(List<String> command) {
        String best = "";
        for (int i = 1; i < command.size(); i++) {
            String a = command.get(i);
            if (a != null && a.length() > best.length()) best = a;
        }
        return best;
    }

    /**
     * Kills the tree of a step. Returns what it did, for the step log. Never throws; never kills
     * anything it could not identify as the step.
     *
     * @param knownPid the pid if Java could give it (9+), else a value below 1
     */
    String kill(long knownPid, long startedBefore, long startedAfter, String token) {
        try {
            long pid = knownPid;
            String how = "pid from Java";
            if (pid < 1) {
                if (jvmPid < 1) return "interpreter only - the JVM's own process id could not be read";
                if (token == null || token.length() < 8) return "interpreter only - the step's command line has nothing to recognise it by";
                List<Candidate> children = parse(runner.run(query()));
                String refusal = whyNot(children, startedBefore, startedAfter, token);
                if (refusal != null) return "interpreter only - " + refusal;
                pid = choose(children, startedBefore, startedAfter, token).pid;
                how = "found among the JVM's children";
            }
            if (pid <= 4 || pid == jvmPid) return "interpreter only - refused to kill process " + pid;
            List<String> out = runner.run(Arrays.asList("taskkill", "/PID", String.valueOf(pid), "/T", "/F"));
            int killed = 0;
            for (String l : out) if (l.toUpperCase(java.util.Locale.ROOT).contains("PID")) killed++;
            return "taskkill /T on process " + pid + " (" + how + "): " + killed + " line(s) of result"
                    + (out.isEmpty() ? "" : ", first: " + out.get(0).trim());
        } catch (Exception | Error e) {
            return "interpreter only - the tree kill failed (" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()) + ")";
        }
    }

    /** One PowerShell call: every child of this JVM as {@code pid|parent|createdMs|commandLine}. */
    List<String> query() {
        String ps = "Get-CimInstance Win32_Process -Filter 'ParentProcessId=" + jvmPid + "' | ForEach-Object { "
                + "$_.ProcessId.ToString() + '|' + $_.ParentProcessId.ToString() + '|' + "
                + "($_.CreationDate.ToUniversalTime() - [datetime]'1970-01-01').TotalMilliseconds.ToString('F0', [cultureinfo]::InvariantCulture)"
                + " + '|' + $_.CommandLine }";
        return Arrays.asList(powershell, "-NoProfile", "-NonInteractive", "-Command", ps);
    }

    /** Lines that are not {@code number|number|number|text} are ignored: a banner, an error, a blank. */
    static List<Candidate> parse(List<String> lines) {
        List<Candidate> out = new ArrayList<Candidate>();
        if (lines == null) return out;
        for (String l : lines) {
            if (l == null) continue;
            String[] f = l.split("\\|", 4);
            if (f.length < 4) continue;
            try {
                out.add(new Candidate(Long.parseLong(f[0].trim()), Long.parseLong(f[1].trim()),
                        Long.parseLong(f[2].trim()), f[3]));
            } catch (NumberFormatException notARow) {
                // ignored
            }
        }
        return out;
    }

    private List<Candidate> matching(List<Candidate> children, long before, long after, String token) {
        List<Candidate> m = new ArrayList<Candidate>();
        for (Candidate c : children) {
            if (c.parent != jvmPid) continue;                                   // asked for, checked again
            if (c.createdMs < before - SLACK_MS || c.createdMs > after + SLACK_MS) continue;
            if (c.commandLine == null || !c.commandLine.contains(token)) continue;
            m.add(c);
        }
        return m;
    }

    /** Null when exactly one child is the step; otherwise the reason nothing may be killed. */
    String whyNot(List<Candidate> children, long before, long after, String token) {
        int n = matching(children, before, after, token).size();
        if (n == 1) return null;
        if (n == 0) return "the step was not found among the " + children.size() + " child process(es) of the server";
        return n + " running steps have the same command line and start time, and cannot be told apart";
    }

    Candidate choose(List<Candidate> children, long before, long after, String token) {
        return matching(children, before, after, token).get(0);
    }
}
