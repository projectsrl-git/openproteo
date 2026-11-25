package com.legalarchive.orchestrator.cli;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.legalarchive.orchestrator.control.ControlProtocol;
import com.legalarchive.orchestrator.control.ExitCodes;

/**
 * {@code openproteo start} (spec {@code .claude/HEADLESS.md} section 15.5): starts the headless
 * instance in the background from {@code OPENPROTEO_HOME}, detached from the session, and waits
 * until its heartbeat says it serves.
 *
 * <p>Refuses at once when an instance already serves the control directory. The engine lock in the
 * new instance is the final word: a start that loses the race exits, and its refusal is printed.
 *
 * <p>Linux: the instance runs under {@code setsid}, so the end of the SSH session (SIGHUP) does not
 * reach it. A server whose logind has {@code KillUserProcesses=yes} still ends it at logout: there
 * the systemd unit is the way ({@code tools/openproteo.service}). Windows: a child process with no
 * console, which a remote session that closes its processes may still end (section 15.5).
 */
final class Starter {

    private static final long WAIT_MS = 90000L;

    private Starter() { }

    static int start(Path home, Path control, Map<String, String> env, PrintStream out, PrintStream err) {
        File war = home.resolve("openproteo-headless.war").toFile();
        if (!war.isFile()) {
            err.println("openproteo: " + war + " not found: OPENPROTEO_HOME must be the installation folder");
            return ExitCodes.LOCAL;
        }
        ControlClient client = new ControlClient(control, err);
        long previousPid = -1;
        try {
            ControlProtocol.Instance i = client.instance();
            err.println("openproteo: already running (pid " + i.pid + " on " + i.host + ", started " + i.startedAt
                    + "). Stop it first with \"shutdown\" from the console.");
            return ExitCodes.CONFLICT;
        } catch (ControlClient.Failure f) {
            if (f.code != ExitCodes.NO_INSTANCE) {
                err.println("openproteo: " + f.getMessage());
                return f.code;
            }
        }
        try {
            previousPid = new ControlClient(control, err).lastPid();
        } catch (Exception ignored) { }

        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        List<String> cmd = new ArrayList<String>();
        if (!windows) {
            String setsid = which("setsid");
            if (setsid == null) {
                err.println("openproteo: setsid not found: the instance would end with your session. Use the systemd unit (tools/openproteo.service).");
                return ExitCodes.LOCAL;
            }
            cmd.add(setsid);
        }
        cmd.add(new File(new File(System.getProperty("java.home"), "bin"), windows ? "java.exe" : "java").getPath());
        String opts = env.get("OPENPROTEO_JAVA_OPTS");
        if (opts != null) for (String o : opts.trim().split("\\s+")) if (!o.isEmpty()) cmd.add(o);
        cmd.add("-jar");
        cmd.add(war.getPath());
        // a file: URI with forward slashes, on Windows too; "optional:" so a missing config/ is not fatal
        cmd.add("--spring.config.additional-location=optional:" + home.resolve("config").toUri());
        cmd.add("--orchestrator.control-dir=" + control);

        File logs = home.resolve("logs").toFile();
        if (!logs.isDirectory() && !logs.mkdirs()) {
            err.println("openproteo: cannot create " + logs);
            return ExitCodes.LOCAL;
        }
        File console = new File(logs, "console.out");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(home.toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.to(console));
        pb.redirectInput(ProcessBuilder.Redirect.from(new File(windows ? "NUL" : "/dev/null")));
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            err.println("openproteo: the instance could not be started: " + e.getMessage());
            return ExitCodes.LOCAL;
        }
        out.println("OpenProteo starting in the background...");
        out.println("  home     " + home);
        out.println("  control  " + control);
        out.println("  output   " + console);

        long until = System.currentTimeMillis() + WAIT_MS;
        while (System.currentTimeMillis() < until) {
            try {
                ControlProtocol.Instance i = client.instance();
                if (i.pid != previousPid && "headless".equals(i.mode)) {
                    out.println("Started: pid " + i.pid + " on " + i.host + ". Connect with: openproteo connect");
                    return ExitCodes.OK;
                }
            } catch (ControlClient.Failure ignored) {
                // not up yet
            }
            if (!p.isAlive()) {
                err.println("openproteo: the instance did not start:");
                for (String l : tail(console)) err.println("  " + l);
                return 1;
            }
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        err.println("openproteo: the instance does not answer after " + (WAIT_MS / 1000) + " s; see " + console);
        return ExitCodes.NO_INSTANCE;
    }

    /** The lines of the console output that say why a start was refused, or its last lines. */
    private static List<String> tail(File f) {
        List<String> out = new ArrayList<String>();
        try {
            List<String> all = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            for (String l : all) if (l.contains("not started") || l.contains("ERROR")) out.add(l);
            if (out.isEmpty()) out.addAll(all.subList(Math.max(0, all.size() - 10), all.size()));
        } catch (Exception e) {
            out.add("(" + f + " unreadable: " + e.getMessage() + ")");
        }
        return out.size() > 15 ? out.subList(out.size() - 15, out.size()) : out;
    }

    private static String which(String name) {
        for (String d : new String[] { "/usr/bin", "/bin", "/usr/sbin", "/sbin" }) {
            File f = new File(d, name);
            if (f.canExecute()) return f.getPath();
        }
        String path = System.getenv("PATH");
        if (path != null) for (String d : path.split(File.pathSeparator)) {
            File f = new File(d, name);
            if (f.canExecute()) return f.getPath();
        }
        return null;
    }
}
