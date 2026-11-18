package com.legalarchive.orchestrator.cli;

import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalarchive.orchestrator.control.ControlProtocol;
import com.legalarchive.orchestrator.control.ExitCodes;

/**
 * The OpenProteo command line (spec {@code .claude/HEADLESS.md} section 6):
 * {@code java -jar openproteo-headless.war cli <command> ...}.
 *
 * <p>Execution and observation only. It never changes a definition or a setting: setup stays in
 * the GUI. Data goes to stdout, messages to stderr; the exit code says what happened (see
 * {@link ExitCodes}), so a scheduler script can branch on it.
 */
public final class OpCli {

    static final String USAGE =
            "Usage: openproteo [--control <dir>] [--json] <command> [arguments]%n"
            + "%n"
            + "  ping                                      is an instance serving the control directory?%n"
            + "  feeds                                     workflows, last run, cron%n"
            + "  runs <feedId> [--limit N]                 runs of a workflow, newest first%n"
            + "  status <feedId> <runId>                   a run and its steps%n"
            + "  log <feedId> <runId> [<stepId>] [--tail N] [--follow]%n"
            + "                                            a step log; without a step, the run's steps%n"
            + "  run <feedId> [--var name=value]... [--wait]%n"
            + "                                            start a run, as the Run button does%n"
            + "  wait <feedId> <runId>                     wait until the run ends or pauses%n"
            + "  stop <feedId> <runId>                     stop a run (again: force-stop)%n"
            + "  approve|reject <feedId> <runId> [--note text]   decide a manual gate%n"
            + "  resume <feedId> <runId>                   release a run on hold%n"
            + "  reload                                    reread the workflow files%n"
            + "  audit-verify <feedId>                     verify the audit hash chain%n"
            + "%n"
            + "The control directory comes from --control, else from OPENPROTEO_CONTROL.%n"
            + "Exit codes: 0 done / run SUCCESS, 1 FAILED, 2 usage, 3 waiting approval, 4 on hold,%n"
            + "5 REJECTED, 6 ABORTED, 7 SKIPPED, 10 unknown, 11 locked, 12 conflict, 13 bad variable,%n"
            + "20 no instance, 21 expired, 22 protocol, 23 instance error, 30 local error.%n";

    private static final long WAIT_POLL_MS = 2000L;

    private final PrintStream out;
    private final PrintStream err;
    private final Map<String, String> env;
    private final ObjectMapper mapper = ControlProtocol.mapper();

    OpCli(PrintStream out, PrintStream err, Map<String, String> env) {
        this.out = out;
        this.err = err;
        this.env = env;
    }

    /** Runs one command and returns its exit code. Never calls System.exit. */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        return new OpCli(out, err, System.getenv()).execute(args);
    }

    // ------------------------------------------------------------------ arguments

    static final class Args {
        String control;
        boolean json, waitFlag, follow, help;
        Integer limit, tail;
        String note;
        final Map<String, String> vars = new LinkedHashMap<String, String>();
        final List<String> positional = new ArrayList<String>();
    }

    static Args parse(String[] a) throws ControlClient.Failure {
        Args r = new Args();
        for (int i = 0; i < a.length; i++) {
            String s = a[i];
            if ("--control".equals(s)) r.control = value(a, ++i, s);
            else if ("--json".equals(s)) r.json = true;
            else if ("--wait".equals(s)) r.waitFlag = true;
            else if ("--follow".equals(s)) r.follow = true;
            else if ("--help".equals(s) || "-h".equals(s)) r.help = true;
            else if ("--limit".equals(s)) r.limit = number(value(a, ++i, s), s);
            else if ("--tail".equals(s)) r.tail = number(value(a, ++i, s), s);
            else if ("--note".equals(s)) r.note = value(a, ++i, s);
            else if ("--var".equals(s)) {
                String v = value(a, ++i, s);
                int eq = v.indexOf('=');
                if (eq <= 0) throw new ControlClient.Failure(ExitCodes.USAGE, "--var takes name=value, got '" + v + "'");
                String name = v.substring(0, eq).trim();
                if (r.vars.containsKey(name)) throw new ControlClient.Failure(ExitCodes.USAGE, "--var " + name + " given twice");
                r.vars.put(name, v.substring(eq + 1));
            } else if (s.startsWith("--")) throw new ControlClient.Failure(ExitCodes.USAGE, "unknown option " + s);
            else r.positional.add(s);
        }
        return r;
    }

    private static String value(String[] a, int i, String opt) throws ControlClient.Failure {
        if (i >= a.length) throw new ControlClient.Failure(ExitCodes.USAGE, opt + " needs a value");
        return a[i];
    }

    private static Integer number(String v, String opt) throws ControlClient.Failure {
        try {
            int n = Integer.parseInt(v.trim());
            if (n <= 0) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException e) {
            throw new ControlClient.Failure(ExitCodes.USAGE, opt + " takes a positive number, got '" + v + "'");
        }
    }

    // ------------------------------------------------------------------ dispatch

    int execute(String[] argv) {
        try {
            Args a = parse(argv);
            if (a.help || a.positional.isEmpty()) {
                out.printf(USAGE);
                return a.help ? ExitCodes.OK : ExitCodes.USAGE;
            }
            String cmd = a.positional.get(0);
            List<String> p = a.positional.subList(1, a.positional.size());
            int expected = arity(cmd);
            if (expected < 0) throw new ControlClient.Failure(ExitCodes.USAGE, "unknown command '" + cmd + "'. Try --help");
            boolean optionalStep = "log".equals(cmd) && p.size() == 3;
            if (p.size() != expected && !optionalStep) {
                throw new ControlClient.Failure(ExitCodes.USAGE, cmd + " takes " + expected + " argument(s). Try --help");
            }
            if (!a.vars.isEmpty() && !"run".equals(cmd)) throw new ControlClient.Failure(ExitCodes.USAGE, "--var is for run only");

            String dir = a.control != null ? a.control : env.get("OPENPROTEO_CONTROL");
            if (dir == null || dir.trim().isEmpty()) {
                throw new ControlClient.Failure(ExitCodes.USAGE, "no control directory: pass --control <dir> or set OPENPROTEO_CONTROL");
            }
            ControlClient client = new ControlClient(Paths.get(dir.trim()).toAbsolutePath().normalize(), err);

            switch (cmd) {
                case "ping": return ping(client, a);
                case "run": return runCommand(client, a, p.get(0));
                case "wait": return waitFor(client, a, p.get(0), p.get(1));
                case "log": return log(client, a, p);
                default: return simple(client, a, cmd, p);
            }
        } catch (ControlClient.Failure f) {
            err.println("openproteo: " + f.getMessage());
            return f.code;
        } catch (RuntimeException e) {
            err.println("openproteo: unexpected error: " + e);
            return ExitCodes.LOCAL;
        }
    }

    /** Positional arguments each command takes after its name; -1 for an unknown command. */
    static int arity(String cmd) {
        switch (cmd) {
            case "ping": case "feeds": case "reload": return 0;
            case "runs": case "run": case "audit-verify": return 1;
            case "status": case "wait": case "stop": case "approve": case "reject": case "resume": case "log": return 2;
            default: return -1;
        }
    }

    private ControlProtocol.Request request(String cmd, List<String> p, Args a) {
        ControlProtocol.Request r = new ControlProtocol.Request();
        r.command = cmd;
        if (p.size() > 0) r.feedId = p.get(0);
        if (p.size() > 1) r.runId = p.get(1);
        if (p.size() > 2) r.stepId = p.get(2);
        r.note = a.note;
        r.limit = a.limit;
        r.tail = a.tail;
        r.vars.putAll(a.vars);
        return r;
    }

    /** One request, its answer printed. */
    private int simple(ControlClient client, Args a, String cmd, List<String> p) throws ControlClient.Failure {
        ControlProtocol.Response r = client.call(request(cmd, p, a));
        if (!r.ok && r.data.isEmpty()) return refused(r);
        if (a.json) printJson(r);
        else Printer.print(cmd, r, out);
        if (!r.ok) err.println("openproteo: " + r.error);
        return r.code;
    }

    private int ping(ControlClient client, Args a) throws ControlClient.Failure {
        ControlProtocol.Instance i = client.instance();
        ControlProtocol.Response r = client.call(request("ping", new ArrayList<String>(), a));
        if (!r.ok) return refused(r);
        if (a.json) {
            Map<String, Object> all = new LinkedHashMap<String, Object>();
            all.put("instance", i);
            all.put("recordedAs", r.user);
            all.putAll(r.data);
            out.println(json(all));
        } else {
            out.println("instance   pid " + i.pid + " on " + i.host + ", mode " + i.mode + ", build " + i.build);
            out.println("running as " + i.osUser + ", started " + i.startedAt + ", last seen " + i.lastSeen);
            out.println("workflows  " + r.data.get("workflows") + ", runs active " + r.data.get("activeRuns")
                    + ", queued " + r.data.get("queuedRuns"));
            out.println("you are recorded as " + r.user);
        }
        return ExitCodes.OK;
    }

    private int runCommand(ControlClient client, Args a, String feedId) throws ControlClient.Failure {
        ControlProtocol.Response r = client.call(request("run", java.util.Collections.singletonList(feedId), a));
        if (!r.ok) return refused(r);
        if (a.json && !a.waitFlag) printJson(r);
        else out.println(r.runId);
        err.println("queued " + r.runId + ", recorded as " + r.user);
        if (!a.waitFlag) return ExitCodes.OK;
        return waitFor(client, a, feedId, r.runId);
    }

    /** Polls the status until the run ends or pauses; the exit code is the run's (section 6.3). */
    private int waitFor(ControlClient client, Args a, String feedId, String runId) throws ControlClient.Failure {
        err.println("waiting for " + runId + " (Ctrl-C stops waiting, not the run)");
        String lastLine = null;
        List<String> ids = new ArrayList<String>();
        ids.add(feedId);
        ids.add(runId);
        while (true) {
            ControlProtocol.Response r = client.call(request("status", ids, a));
            if (!r.ok) return refused(r);
            String status = String.valueOf(r.data.get("status"));
            String line = status + Printer.currentStep(r.data);
            if (!line.equals(lastLine)) {
                err.println(Printer.now() + "  " + line);
                lastLine = line;
            }
            int code = ExitCodes.forRunStatus(status);
            if (code >= 0) {
                if (a.json) printJson(r);
                else Printer.print("status", r, out);
                return code;
            }
            sleep();
        }
    }

    private int log(ControlClient client, Args a, List<String> p) throws ControlClient.Failure {
        ControlProtocol.Request req = request("log", p, a);
        ControlProtocol.Response r = client.call(req);
        if (!r.ok) return refused(r);
        if (a.json && !a.follow) { printJson(r); return ExitCodes.OK; }
        Printer.print("log", r, out);
        if (!a.follow || p.size() < 3) return ExitCodes.OK;
        while (true) {
            Object next = r.data.get("nextOffset");
            long offset = next instanceof Number ? ((Number) next).longValue() : 0L;
            boolean ended = ExitCodes.forRunStatus(String.valueOf(r.data.get("runStatus"))) >= 0;
            if (ended) {
                // one last read: lines written between the previous read and the end of the run
                ControlProtocol.Request last = request("log", p, a);
                last.offset = offset;
                r = client.call(last);
                if (r.ok) Printer.printLines(r, out);
                return ExitCodes.OK;
            }
            sleep();
            ControlProtocol.Request more = request("log", p, a);
            more.offset = offset;
            r = client.call(more);
            if (!r.ok) return refused(r);
            Printer.printLines(r, out);
        }
    }

    // ------------------------------------------------------------------ output

    private int refused(ControlProtocol.Response r) {
        err.println("openproteo: " + (r.error == null ? "refused" : r.error));
        return r.code == 0 ? ExitCodes.INSTANCE_ERROR : r.code;
    }

    private void printJson(ControlProtocol.Response r) {
        Map<String, Object> m = new LinkedHashMap<String, Object>(r.data);
        if (r.runId != null && !m.containsKey("runId")) m.put("runId", r.runId);
        out.println(json(m));
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }

    private static void sleep() throws ControlClient.Failure {
        try {
            Thread.sleep(WAIT_POLL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ControlClient.Failure(ExitCodes.LOCAL, "interrupted");
        }
    }

    /** For a test or a script: the path the client would use. */
    static Path controlDir(String dir) {
        return Paths.get(dir).toAbsolutePath().normalize();
    }
}
