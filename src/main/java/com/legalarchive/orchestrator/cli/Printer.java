package com.legalarchive.orchestrator.cli;

import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import com.legalarchive.orchestrator.control.ControlProtocol;

/** Human-readable output of the CLI. {@code --json} bypasses it. */
final class Printer {

    private Printer() { }

    static void print(String cmd, ControlProtocol.Response r, PrintStream out) {
        Map<String, Object> d = r.data;
        switch (cmd) {
            case "feeds": feeds(d, out); break;
            case "runs": runs(d, out); break;
            case "status": status(d, out); break;
            case "log": log(d, out); break;
            case "reload":
                out.println(d.get("workflows") + " workflow(s) loaded");
                for (Object e : list(d.get("errors"))) out.println("error: " + e);
                break;
            case "audit-verify": out.println(s(d.get("message"))); break;
            default:
                out.println(cmd + " accepted: run " + s(r.runId) + " is now " + s(d.get("status")));
        }
    }

    private static void feeds(Map<String, Object> d, PrintStream out) {
        List<String[]> rows = new ArrayList<String[]>();
        rows.add(new String[] { "FEED", "LAST RUN", "STATUS", "CRON", "FLAGS" });
        for (Object o : list(d.get("feeds"))) {
            Map<?, ?> f = (Map<?, ?>) o;
            Map<?, ?> last = f.get("lastRun") instanceof Map ? (Map<?, ?>) f.get("lastRun") : null;
            String flags = (Boolean.TRUE.equals(f.get("production")) ? "PROD " : "")
                    + (Boolean.TRUE.equals(f.get("locked")) ? "LOCKED " : "")
                    + (f.get("activeRunId") != null ? "ACTIVE " : "")
                    + (f.get("cronError") != null ? "CRON-ERROR" : "");
            rows.add(new String[] { s(f.get("feedId")), last == null ? "-" : s(last.get("runId")),
                    last == null ? "-" : s(last.get("status")), s(f.get("cron")), flags.trim() });
        }
        table(rows, out);
        for (Object e : list(d.get("loadErrors"))) out.println("load error: " + e);
    }

    private static void runs(Map<String, Object> d, PrintStream out) {
        List<String[]> rows = new ArrayList<String[]>();
        rows.add(new String[] { "RUN", "STATUS", "TRIGGER", "BY", "START", "END" });
        for (Object o : list(d.get("runs"))) {
            Map<?, ?> r = (Map<?, ?>) o;
            rows.add(new String[] { s(r.get("runId")), s(r.get("status")), s(r.get("trigger")),
                    s(r.get("triggeredBy")), s(r.get("startTs")), s(r.get("endTs")) });
        }
        table(rows, out);
    }

    private static void status(Map<String, Object> d, PrintStream out) {
        out.println("run        " + s(d.get("runId")) + "  (" + s(d.get("workflowName")) + ")");
        out.println("status     " + s(d.get("status")) + (d.get("message") == null ? "" : "  " + s(d.get("message"))));
        out.println("started    " + s(d.get("startTs")) + " by " + s(d.get("triggeredBy")) + " (" + s(d.get("trigger")) + ")");
        if (d.get("endTs") != null) out.println("ended      " + s(d.get("endTs")));
        if (d.get("waitingGateId") != null) out.println("waiting    gate " + s(d.get("waitingGateId")) + ": approve or reject");
        if (d.get("onHoldStepId") != null) out.println("on hold    before step " + s(d.get("onHoldStepId")) + ": resume to go on");
        List<String[]> rows = new ArrayList<String[]>();
        rows.add(new String[] { "STEP", "STATUS", "EXIT", "START", "END", "MESSAGE" });
        for (Object o : list(d.get("steps"))) {
            Map<?, ?> st = (Map<?, ?>) o;
            rows.add(new String[] { s(st.get("stepId")), s(st.get("status")), s(st.get("exitCode")),
                    s(st.get("startTs")), s(st.get("endTs")), s(st.get("message")) });
        }
        out.println();
        table(rows, out);
    }

    private static void log(Map<String, Object> d, PrintStream out) {
        if (d.containsKey("steps")) {
            List<String[]> rows = new ArrayList<String[]>();
            rows.add(new String[] { "STEP", "STATUS", "LOG" });
            for (Object o : list(d.get("steps"))) {
                Map<?, ?> st = (Map<?, ?>) o;
                rows.add(new String[] { s(st.get("stepId")), s(st.get("status")), Boolean.TRUE.equals(st.get("hasLog")) ? "yes" : "-" });
            }
            table(rows, out);
            return;
        }
        if (Boolean.FALSE.equals(d.get("exists"))) {
            out.println("(no log available)");
            return;
        }
        Object total = d.get("totalLines");
        List<Object> lines = list(d.get("lines"));
        if (total instanceof Number && ((Number) total).longValue() > lines.size()) {
            out.println("... (showing last " + lines.size() + " of " + total + " lines)");
        }
        for (Object l : lines) out.println(s(l));
    }

    static void printLines(ControlProtocol.Response r, PrintStream out) {
        for (Object l : list(r.data.get("lines"))) out.println(s(l));
    }

    /** "  step <id>" for the step that is running, "" otherwise. */
    static String currentStep(Map<String, Object> d) {
        for (Object o : list(d.get("steps"))) {
            Map<?, ?> st = (Map<?, ?>) o;
            if ("RUNNING".equals(st.get("status"))) return "  step " + s(st.get("stepId"));
        }
        return "";
    }

    static String now() {
        return new SimpleDateFormat("HH:mm:ss").format(new Date());
    }

    private static void table(List<String[]> rows, PrintStream out) {
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

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) {
        return o instanceof List ? (List<Object>) o : new ArrayList<Object>();
    }

    private static String s(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
