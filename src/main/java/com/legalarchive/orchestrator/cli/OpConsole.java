package com.legalarchive.orchestrator.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.legalarchive.orchestrator.control.ControlProtocol;
import com.legalarchive.orchestrator.control.ExitCodes;

/**
 * The interactive console: {@code openproteo connect} (spec {@code .claude/HEADLESS.md} section 15.3).
 *
 * <p>Every command is one request on the control channel, exactly as a one-shot command is, so the
 * console adds no trust: each line typed is recorded under the person who typed it. It keeps only
 * conveniences: the current feed ({@code use}), the numbers of the last listings, and {@code last}.
 */
public final class OpConsole {

    private static final long FOLLOW_POLL_MS = 2000L;

    private final ControlClient client;
    private final BufferedReader in;
    private final PrintStream out;

    private String feed;
    private final List<String> feedList = new ArrayList<String>();
    private final List<String> runList = new ArrayList<String>();
    private volatile boolean interrupted;
    private volatile boolean following;

    OpConsole(ControlClient client, BufferedReader in, PrintStream out) {
        this.client = client;
        this.in = in;
        this.out = out;
    }

    static int run(ControlClient client, PrintStream out) {
        return new OpConsole(client, new BufferedReader(new InputStreamReader(System.in)), out).loop();
    }

    // ------------------------------------------------------------------ loop

    int loop() {
        try {
            ControlProtocol.Instance i = client.instance();
            ControlProtocol.Response p = client.call(req("ping"));
            out.println("Connected to OpenProteo on " + i.host + " (pid " + i.pid + ", " + i.mode + ", build " + i.build + ")");
            out.println("You are recorded as: " + p.user + "          Type \"help\" for the commands, \"exit\" to leave.");
            loadFeeds(false);
        } catch (ControlClient.Failure f) {
            out.println("openproteo: " + f.getMessage());
            return f.code;
        }
        installCtrlC();
        while (true) {
            out.print(feed == null ? "openproteo> " : "openproteo [" + feed + "]> ");
            out.flush();
            String line;
            try {
                line = in.readLine();
            } catch (IOException e) {
                return ExitCodes.LOCAL;
            }
            if (line == null) { out.println(); return ExitCodes.OK; }   // end of input
            line = line.trim();
            if (line.isEmpty()) continue;
            List<String> t;
            try {
                t = tokens(line);
            } catch (IllegalArgumentException e) {
                out.println(e.getMessage());
                continue;
            }
            String cmd = t.get(0).toLowerCase();
            List<String> a = t.subList(1, t.size());
            try {
                if ("exit".equals(cmd) || "quit".equals(cmd)) {
                    out.println("Disconnected. OpenProteo keeps running; the cron schedules go on.");
                    return ExitCodes.OK;
                }
                if ("shutdown".equals(cmd)) {
                    if (shutdown(a)) return ExitCodes.OK;
                    continue;
                }
                execute(cmd, a);
            } catch (ControlClient.Failure f) {
                out.println("openproteo: " + f.getMessage());
                if (f.code == ExitCodes.NO_INSTANCE) return f.code;
            } catch (Usage u) {
                out.println(u.getMessage());
            }
        }
    }

    /** A mistake in what was typed: printed, and the console goes on. */
    static final class Usage extends Exception {
        private static final long serialVersionUID = 1L;
        Usage(String m) { super(m); }
    }

    private void execute(String cmd, List<String> args) throws ControlClient.Failure, Usage {
        Opts o = new Opts(args);
        switch (cmd) {
            case "help": help(o.pos); return;
            case "ping": ping(); return;
            case "feeds": loadFeeds(true); return;
            case "use": use(o.pos); return;
            case "runs": runs(o); return;
            case "status": status(o); return;
            case "log": log(o); return;
            case "run": runCmd(o); return;
            case "follow": { String[] fr = feedRun(o.pos, "follow"); follow(fr[0], fr[1]); return; }
            case "approve": case "reject": decide(cmd, o); return;
            case "resume": action("resume", o, true); return;
            case "stop": action("stop", o, false); return;
            case "reload": simple("reload", null, null); loadFeeds(false); return;
            case "audit-verify": {
                String f = o.pos.isEmpty() ? currentFeed("audit-verify") : resolveFeed(o.pos.get(0));
                simple("audit-verify", f, null);
                return;
            }
            default: throw new Usage("Unknown command '" + cmd + "'. Type \"help\".");
        }
    }

    // ------------------------------------------------------------------ commands

    private void help(List<String> a) {
        out.println("  feeds                                 workflows, numbered; flags PROD LOCKED ACTIVE");
        out.println("  use <feed or number>                  set the current feed (use - clears it)");
        out.println("  runs [feed] [--limit N]               runs, newest first, numbered");
        out.println("  status [feed] <run|last|number>       a run and its steps");
        out.println("  log [feed] <run|last|number> [step] [--tail N]");
        out.println("  run [feed] [name=value]...            start a run and follow it");
        out.println("  follow [feed] <run|last|number>       follow a run (Ctrl-C: back to the prompt, the run goes on)");
        out.println("  approve|reject [feed] <run|last> [--note \"text\"]");
        out.println("  resume [feed] <run|last>              release a run on hold");
        out.println("  stop [feed] <run|last>                stop a run (again: force-stop)");
        out.println("  reload                                reread the workflow files");
        out.println("  audit-verify [feed]                   verify the audit hash chain");
        out.println("  ping                                  the instance, and who you are recorded as");
        out.println("  exit                                  leave the console; OpenProteo keeps running");
        out.println("  shutdown [--force]                    stop OpenProteo (asks for confirmation)");
        out.println("Double quotes group words; a quote inside a quoted value is not supported.");
    }

    private void ping() throws ControlClient.Failure {
        ControlProtocol.Instance i = client.instance();
        ControlProtocol.Response r = client.call(req("ping"));
        out.println("instance   pid " + i.pid + " on " + i.host + ", mode " + i.mode + ", build " + i.build);
        out.println("running as " + i.osUser + ", started " + i.startedAt);
        out.println("workflows  " + r.data.get("workflows") + ", runs active " + r.data.get("activeRuns") + ", queued " + r.data.get("queuedRuns"));
        out.println("you are recorded as " + r.user);
    }

    private void loadFeeds(boolean print) throws ControlClient.Failure {
        ControlProtocol.Response r = client.call(req("feeds"));
        feedList.clear();
        List<String[]> rows = new ArrayList<String[]>();
        rows.add(new String[] { "#", "FEED", "LAST RUN", "STATUS", "CRON", "FLAGS" });
        int n = 0;
        for (Object o : list(r.data.get("feeds"))) {
            Map<?, ?> f = (Map<?, ?>) o;
            feedList.add(s(f.get("feedId")));
            Map<?, ?> last = f.get("lastRun") instanceof Map ? (Map<?, ?>) f.get("lastRun") : null;
            String flags = (Boolean.TRUE.equals(f.get("production")) ? "PROD " : "")
                    + (Boolean.TRUE.equals(f.get("locked")) ? "LOCKED " : "")
                    + (f.get("activeRunId") != null ? "ACTIVE " : "")
                    + (f.get("cronError") != null ? "CRON-ERROR" : "");
            rows.add(new String[] { String.valueOf(++n), s(f.get("feedId")), last == null ? "-" : s(last.get("runId")),
                    last == null ? "-" : s(last.get("status")), s(f.get("cron")), flags.trim() });
        }
        if (print) {
            table(rows);
            for (Object e : list(r.data.get("loadErrors"))) out.println("load error: " + e);
        }
    }

    private void use(List<String> a) throws Usage, ControlClient.Failure {
        if (a.isEmpty()) { out.println(feed == null ? "No current feed." : "Current feed: " + feed); return; }
        if ("-".equals(a.get(0))) { feed = null; out.println("No current feed."); return; }
        if (feedList.isEmpty()) loadFeeds(false);
        feed = resolveFeed(a.get(0));
        out.println("Current feed: " + feed);
    }

    private void runs(Opts o) throws ControlClient.Failure, Usage {
        String f = o.pos.isEmpty() ? currentFeed("runs") : resolveFeed(o.pos.get(0));
        ControlProtocol.Request q = req("runs");
        q.feedId = f;
        q.limit = o.limit;
        ControlProtocol.Response r = client.call(q);
        if (!r.ok) { refused(r); return; }
        runList.clear();
        List<String[]> rows = new ArrayList<String[]>();
        rows.add(new String[] { "#", "RUN", "STATUS", "TRIGGER", "BY", "START", "END" });
        int n = 0;
        for (Object x : list(r.data.get("runs"))) {
            Map<?, ?> m = (Map<?, ?>) x;
            runList.add(s(m.get("runId")));
            rows.add(new String[] { String.valueOf(++n), s(m.get("runId")), s(m.get("status")), s(m.get("trigger")),
                    s(m.get("triggeredBy")), s(m.get("startTs")), s(m.get("endTs")) });
        }
        if (rows.size() == 1) out.println("No run yet.");
        else table(rows);
    }

    private void status(Opts o) throws ControlClient.Failure, Usage {
        String[] fr = feedRun(o.pos, "status");
        ControlProtocol.Response r = client.call(req("status", fr[0], fr[1]));
        if (!r.ok) { refused(r); return; }
        Printer.print("status", r, out);
    }

    private void log(Opts o) throws ControlClient.Failure, Usage {
        List<String> p = o.pos;
        String f, run, step = null;
        if (p.isEmpty()) throw new Usage("log [feed] <run|last|number> [step] [--tail N]");
        if (isFeed(p.get(0)) && p.size() >= 2) {
            f = p.get(0); run = p.get(1); if (p.size() > 2) step = p.get(2);
        } else {
            f = currentFeed("log"); run = p.get(0); if (p.size() > 1) step = p.get(1);
        }
        ControlProtocol.Request q = req("log", f, resolveRun(f, run));
        q.stepId = step;
        q.tail = o.tail;
        ControlProtocol.Response r = client.call(q);
        if (!r.ok) { refused(r); return; }
        Printer.print("log", r, out);
    }

    private void runCmd(Opts o) throws ControlClient.Failure, Usage {
        String f = null;
        Map<String, String> vars = new LinkedHashMap<String, String>();
        for (String t : o.pos) {
            int eq = t.indexOf('=');
            if (eq > 0) {
                vars.put(t.substring(0, eq), t.substring(eq + 1));
            } else if (eq == 0) {
                throw new Usage("A variable is written name=value");
            } else if (f == null && vars.isEmpty()) {
                f = resolveFeed(t);
            } else {
                throw new Usage("run [feed] [name=value]...");
            }
        }
        if (f == null) f = currentFeed("run");
        ControlProtocol.Request q = req("run", f, null);
        q.vars.putAll(vars);
        ControlProtocol.Response r = client.call(q);
        if (!r.ok) { refused(r); return; }
        out.println("Queued " + r.runId + " (recorded as " + r.user + "). Following it - Ctrl-C returns to the prompt, the run goes on.");
        follow(f, r.runId, new HashMap<String, String>());   // nothing ran yet: every step that runs is shown
    }

    private void decide(String cmd, Opts o) throws ControlClient.Failure, Usage {
        String[] fr = feedRun(o.pos, cmd);
        Map<String, String> before = stepStatuses(fr[0], fr[1]);
        ControlProtocol.Request q = req(cmd, fr[0], fr[1]);
        q.note = o.note;
        ControlProtocol.Response r = client.call(q);
        if (!r.ok) { refused(r); return; }
        out.println(("approve".equals(cmd) ? "Approved" : "Rejected") + " by " + r.user + ". Following it...");
        follow(fr[0], fr[1], before);
    }

    private void action(String cmd, Opts o, boolean thenFollow) throws ControlClient.Failure, Usage {
        String[] fr = feedRun(o.pos, cmd);
        Map<String, String> before = thenFollow ? stepStatuses(fr[0], fr[1]) : null;
        ControlProtocol.Response r = client.call(req(cmd, fr[0], fr[1]));
        if (!r.ok) { refused(r); return; }
        out.println(cmd + " accepted: run " + fr[1] + " is now " + s(r.data.get("status")));
        if (thenFollow) follow(fr[0], fr[1], before);
    }

    /** Step id -> status now: the baseline that tells the steps an action makes run from those that ran before it. */
    private Map<String, String> stepStatuses(String f, String run) throws ControlClient.Failure {
        Map<String, String> m = new HashMap<String, String>();
        ControlProtocol.Response r = client.call(req("status", f, run));
        for (Object o : list(r.data.get("steps"))) {
            Map<?, ?> step = (Map<?, ?>) o;
            m.put(s(step.get("stepId")), s(step.get("status")));
        }
        return m;
    }

    private void simple(String cmd, String f, String run) throws ControlClient.Failure {
        ControlProtocol.Response r = client.call(req(cmd, f, run));
        if (!r.ok && r.data.isEmpty()) { refused(r); return; }
        Printer.print(cmd, r, out);
        if (!r.ok) out.println("openproteo: " + r.error);
    }

    /** True when the instance accepted to stop: the console then ends. */
    private boolean shutdown(List<String> args) throws ControlClient.Failure {
        boolean force = args.contains("--force");
        ControlProtocol.Instance i = client.instance();
        out.print("Stop OpenProteo on " + i.host + " (pid " + i.pid + ")? Cron schedules will not fire until it is started again"
                + (force ? "; ACTIVE RUNS WILL BE INTERRUPTED" : "") + ". [yes/no] ");
        out.flush();
        String answer;
        try { answer = in.readLine(); } catch (IOException e) { answer = null; }
        if (answer == null || !"yes".equalsIgnoreCase(answer.trim())) {
            out.println("Not stopped.");
            return false;
        }
        ControlProtocol.Request q = req("shutdown");
        q.force = force;
        ControlProtocol.Response r = client.call(q);
        if (!r.ok) { refused(r); return false; }
        out.print("Stopping...");
        out.flush();
        long until = System.currentTimeMillis() + 60000L;
        while (System.currentTimeMillis() < until) {
            try {
                client.instance();
            } catch (ControlClient.Failure f) {
                out.println(" stopped (pid " + i.pid + ") at " + new SimpleDateFormat("HH:mm:ss").format(new Date()) + ".");
                return true;
            }
            sleep(500);
        }
        out.println(" the instance accepted, but still answers after 60 s: check its log.");
        return true;
    }

    // ------------------------------------------------------------------ following

    /** Follows a run until it ends or pauses, or until Ctrl-C (HEADLESS.md 15.4). */
    private void follow(String f, String runId) throws ControlClient.Failure {
        follow(f, runId, null);
    }

    /**
     * @param baseline step statuses before the action that started this follow; a step whose status
     *                 differs from it has its log shown. Null (the follow command): the first poll is the
     *                 baseline, so only the running step is shown at first.
     */
    private void follow(String f, String runId, Map<String, String> baseline) throws ControlClient.Failure {
        interrupted = false;
        following = true;
        try {
            String lastLine = null;
            Map<String, Long> offsets = new HashMap<String, Long>();
            Map<String, String> lastStepStatus = baseline == null ? new HashMap<String, String>() : new HashMap<String, String>(baseline);
            boolean firstPoll = true;
            while (true) {
                ControlProtocol.Response r = client.call(req("status", f, runId));
                if (!r.ok) { refused(r); return; }
                String st = s(r.data.get("status"));
                String running = null;
                List<String> touched = new ArrayList<String>();
                boolean noBaseline = firstPoll && baseline == null;
                firstPoll = false;
                for (Object o : list(r.data.get("steps"))) {
                    Map<?, ?> step = (Map<?, ?>) o;
                    String id = s(step.get("stepId")), ss = s(step.get("status"));
                    if ("RUNNING".equals(ss)) running = id;
                    String before = lastStepStatus.put(id, ss);
                    boolean active = !"PENDING".equals(ss) && !"SKIPPED".equals(ss) && !ss.isEmpty();
                    // the running step, and every step whose status changed since the baseline or the
                    // last poll (a fast step can start and end between two polls)
                    if (active && ("RUNNING".equals(ss) || (!noBaseline && !ss.equals(before)))) touched.add(id);
                }
                int code = ExitCodes.forRunStatus(st);
                boolean ended = code >= 0 && !"WAITING_APPROVAL".equals(st) && !"ON_HOLD".equals(st);
                String line = st + (running == null ? "" : "   step " + running);
                if (!ended && !line.equals(lastLine)) {
                    out.println(now() + "  " + line);
                    lastLine = line;
                }
                for (String id : touched) printNewLog(f, runId, id, offsets);
                if (code >= 0) {
                    end(r, st);
                    return;
                }
                if (pause()) {
                    out.println("Back to the prompt: the run goes on. \"follow " + runId + "\" to watch it again.");
                    return;
                }
            }
        } finally {
            following = false;
        }
    }

    private void printNewLog(String f, String runId, String stepId, Map<String, Long> offsets) throws ControlClient.Failure {
        ControlProtocol.Request q = req("log", f, runId);
        q.stepId = stepId;
        q.offset = offsets.containsKey(stepId) ? offsets.get(stepId) : 0L;
        ControlProtocol.Response r = client.call(q);
        if (!r.ok) return;
        for (Object l : list(r.data.get("lines"))) out.println(now() + "  | " + s(l));
        Object next = r.data.get("nextOffset");
        if (next instanceof Number) offsets.put(stepId, ((Number) next).longValue());
    }

    private void end(ControlProtocol.Response r, String st) {
        String msg = s(r.data.get("message"));
        switch (st) {
            case "SUCCESS": out.println(now() + "  SUCCESS" + duration(r.data)); break;
            case "WAITING_APPROVAL":
                out.println("The run waits for a decision on gate " + s(r.data.get("waitingGateId")) + ": approve or reject.");
                break;
            case "ON_HOLD":
                out.println("The run is on hold before step " + s(r.data.get("onHoldStepId")) + ": resume to go on.");
                break;
            default: out.println(now() + "  " + st + (msg.isEmpty() ? "" : ": " + msg));
        }
    }

    private static String duration(Map<String, Object> d) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            long ms = f.parse(s(d.get("endTs"))).getTime() - f.parse(s(d.get("startTs"))).getTime();
            long sec = ms / 1000;
            return " in " + (sec >= 3600 ? (sec / 3600) + "h " : "") + ((sec % 3600) / 60) + "m " + (sec % 60) + "s";
        } catch (Exception e) {
            return "";
        }
    }

    /** Sleeps one poll; true when Ctrl-C was pressed meanwhile. */
    private boolean pause() {
        long until = System.currentTimeMillis() + FOLLOW_POLL_MS;
        while (System.currentTimeMillis() < until) {
            if (interrupted) return true;
            sleep(100);
        }
        return interrupted;
    }

    /**
     * Ctrl-C while following returns to the prompt, through {@code sun.misc.Signal} (HotSpot),
     * used by reflection. Where it is missing, Ctrl-C ends the console; the run lives in the
     * instance either way.
     */
    private void installCtrlC() {
        try {
            Class<?> signal = Class.forName("sun.misc.Signal");
            Class<?> handler = Class.forName("sun.misc.SignalHandler");
            Object sig = signal.getConstructor(String.class).newInstance("INT");
            Object h = Proxy.newProxyInstance(handler.getClassLoader(), new Class<?>[] { handler }, new InvocationHandler() {
                @Override public Object invoke(Object proxy, Method m, Object[] args) {
                    if ("handle".equals(m.getName())) {
                        if (following) interrupted = true;
                        else { out.println(); out.println("(Ctrl-C) Type \"exit\" to leave the console."); out.flush(); }
                    }
                    return null;
                }
            });
            signal.getMethod("handle", signal, handler).invoke(null, sig, h);
        } catch (Throwable ignored) {
            // no handler: Ctrl-C ends the console, not the run
        }
    }

    // ------------------------------------------------------------------ arguments

    /** Options of a console line: --limit, --tail, --note; the rest are positional. */
    static final class Opts {
        final List<String> pos = new ArrayList<String>();
        Integer limit, tail;
        String note;

        Opts(List<String> a) throws Usage {
            for (int i = 0; i < a.size(); i++) {
                String t = a.get(i);
                if ("--limit".equals(t) || "--tail".equals(t)) {
                    if (i + 1 >= a.size()) throw new Usage(t + " needs a number");
                    try {
                        int n = Integer.parseInt(a.get(++i));
                        if (n <= 0) throw new NumberFormatException();
                        if ("--limit".equals(t)) limit = n; else tail = n;
                    } catch (NumberFormatException e) {
                        throw new Usage(t + " takes a positive number");
                    }
                } else if ("--note".equals(t)) {
                    if (i + 1 >= a.size()) throw new Usage("--note needs a text");
                    note = a.get(++i);
                } else if ("--force".equals(t)) {
                    // shutdown only, handled there
                } else if (t.startsWith("--")) {
                    throw new Usage("Unknown option " + t);
                } else {
                    pos.add(t);
                }
            }
        }
    }

    /** Splits a line on spaces; double quotes group words. No escapes. */
    static List<String> tokens(String line) {
        List<String> out = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false, any = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') { quoted = !quoted; any = true; continue; }
            if (!quoted && Character.isWhitespace(c)) {
                if (any) { out.add(cur.toString()); cur.setLength(0); any = false; }
                continue;
            }
            cur.append(c);
            any = true;
        }
        if (quoted) throw new IllegalArgumentException("A quote is not closed.");
        if (any) out.add(cur.toString());
        return out;
    }

    private boolean isFeed(String t) {
        return feedList.contains(t);
    }

    private String resolveFeed(String t) throws Usage {
        if (feedList.contains(t)) return t;
        if (t.matches("[0-9]+")) {
            int n = Integer.parseInt(t);
            if (n >= 1 && n <= feedList.size()) return feedList.get(n - 1);
            throw new Usage("No feed number " + n + ": \"feeds\" lists them.");
        }
        throw new Usage("Unknown feed '" + t + "': \"feeds\" lists them.");
    }

    private String currentFeed(String cmd) throws Usage {
        if (feed == null) throw new Usage("No current feed: \"" + cmd + " <feed> ...\", or \"use <feed>\" first.");
        return feed;
    }

    /** [feed] <run>: a known feed first, else the current feed. The run is resolved (last, number). */
    private String[] feedRun(List<String> p, String cmd) throws Usage, ControlClient.Failure {
        String f, run;
        if (p.size() >= 2 && isFeed(p.get(0))) { f = p.get(0); run = p.get(1); }
        else if (p.size() == 1) { f = currentFeed(cmd); run = p.get(0); }
        else throw new Usage(cmd + " [feed] <run|last|number>");
        return new String[] { f, resolveRun(f, run) };
    }

    private String resolveRun(String f, String run) throws Usage, ControlClient.Failure {
        if ("last".equalsIgnoreCase(run)) {
            ControlProtocol.Request q = req("runs");
            q.feedId = f;
            q.limit = 20;
            ControlProtocol.Response r = client.call(q);
            for (Object x : list(r.data.get("runs"))) {
                String id = s(((Map<?, ?>) x).get("runId"));
                if (!id.contains("_test_")) return id;
            }
            throw new Usage(f + " has no run yet.");
        }
        if (run.matches("[0-9]+")) {
            int n = Integer.parseInt(run);
            if (n >= 1 && n <= runList.size()) return runList.get(n - 1);
            throw new Usage("No run number " + n + ": \"runs\" lists them.");
        }
        return run;
    }

    // ------------------------------------------------------------------ helpers

    private static ControlProtocol.Request req(String cmd) {
        ControlProtocol.Request r = new ControlProtocol.Request();
        r.command = cmd;
        return r;
    }

    private static ControlProtocol.Request req(String cmd, String f, String run) {
        ControlProtocol.Request r = req(cmd);
        r.feedId = f;
        r.runId = run;
        return r;
    }

    private void refused(ControlProtocol.Response r) {
        out.println("openproteo: " + (r.error == null ? "refused" : r.error));
    }

    private void table(List<String[]> rows) {
        int cols = rows.get(0).length;
        int[] w = new int[cols];
        for (String[] r : rows) for (int i = 0; i < cols; i++) w[i] = Math.max(w[i], r[i].length());
        for (String[] r : rows) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < cols; i++) {
                sb.append(r[i]);
                if (i < cols - 1) for (int k = r[i].length(); k < w[i] + 2; k++) sb.append(' ');
            }
            out.println(sb.toString().replaceAll("\\s+$", ""));
        }
    }

    private static String now() {
        return new SimpleDateFormat("HH:mm:ss").format(new Date());
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) {
        return o instanceof List ? (List<Object>) o : new ArrayList<Object>();
    }

    private static String s(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
