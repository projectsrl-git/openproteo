package com.legalarchive.orchestrator.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.legalarchive.orchestrator.model.run.CheckResult;
import com.legalarchive.orchestrator.model.run.GateExec;
import com.legalarchive.orchestrator.model.run.StepExec;
import com.legalarchive.orchestrator.model.run.WorkflowRun;

/**
 * Renders the per-run evidence report written to
 * {@code {feedDir}/_logs/runs/{runId}/audit_report.md}.
 *
 * Free of Spring and of any IO on purpose: everything it needs is passed in, so the rendering can be
 * compiled and exercised against synthetic runs without a filesystem.
 *
 * Two sources of output variables, deliberately kept apart because they are different sets:
 * <ul>
 *   <li><b>Output data</b> - the list DECLARED in the workflow definition ({@code outputData.<var>}
 *       step params plus the workflow-level ones), which is exactly what the Operations grid shows
 *       in its "output data" column. Resolved by the caller so the two cannot disagree;</li>
 *   <li><b>per-step variables</b> - what each step actually published, recovered from the namespaced
 *       {@code <stepId>.<var>} keys the engine writes into {@code run.vars} alongside the plain ones.</li>
 * </ul>
 */
public final class RunAuditReport {

    private RunAuditReport() { }

    private static final String LF = String.valueOf((char) 10);
    private static final java.time.format.DateTimeFormatter TS =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /** One rendered step log: the text plus whether it was shortened. */
    public static class StepLog {
        public final String text;
        public final boolean truncated;
        public StepLog(String text, boolean truncated) { this.text = text; this.truncated = truncated; }
    }

    /**
     * @param run              the run, as persisted
     * @param declared         the declared output-data list, {varName, label} in display order
     * @param logs             stepId -> its standard output, already formatted as the run page shows it
     * @param generatedBy      user recorded in the header
     * @param parentId         the feed's parent id, or null/equal to feedId when not a version
     */
    public static String render(WorkflowRun run, List<String[]> declared,
                                Map<String, StepLog> logs, String generatedBy, String parentId) {
        return render(run, declared, logs, null, generatedBy, parentId);
    }

    /**
     * @param reports stepId -> the Markdown a step produced as its own report (sqlreport). Embedded
     *                under that step, so the evidence a query returned sits next to the step that ran
     *                it instead of in a file somewhere else that has to be found and matched by hand.
     */
    public static String render(WorkflowRun run, List<String[]> declared,
                                Map<String, StepLog> logs, Map<String, String> reports,
                                String generatedBy, String parentId) {
        StringBuilder md = new StringBuilder();
        String feedId = nz(run.feedId);

        md.append("# Audit report - ").append(feedId).append(LF).append(LF);
        md.append("Run `").append(nz(run.runId)).append("`").append(LF).append(LF);

        md.append("| | |").append(LF).append("|---|---|").append(LF);
        row(md, "Feed", feedId);
        if (parentId != null && !parentId.isEmpty() && !parentId.equals(feedId)) {
            row(md, "Version of", parentId);
        }
        row(md, "Workflow", nz(run.workflowName));
        row(md, "Run id", nz(run.runId));
        row(md, "Status", run.status == null ? "" : run.status.name());
        row(md, "Trigger", nz(run.trigger));
        row(md, "Started by", nz(run.triggeredBy));
        row(md, "Started", nz(run.startTs));
        row(md, "Ended", nz(run.endTs));
        String dur = duration(run.startTs, run.endTs);
        if (dur != null) row(md, "Duration", dur);
        if (nz(run.message).length() > 0) row(md, "Message", run.message);
        row(md, "Report generated", java.time.LocalDateTime.now().format(TS)
                + (generatedBy == null || generatedBy.isEmpty() ? "" : (" by " + generatedBy)));
        md.append(LF);

        // ---- output data, as shown in Operations ----
        md.append("## Output data").append(LF).append(LF);
        md.append("The variables the workflow declares as its output - the same list, in the same order,")
          .append(" that the Operations grid shows in its \"output data\" column for this run.").append(LF).append(LF);
        if (declared == null || declared.isEmpty()) {
            md.append("This workflow declares no output data.").append(LF).append(LF);
        } else {
            md.append("| Output | Value |").append(LF).append("|---|---|").append(LF);
            for (String[] d : declared) {
                String v = run.vars == null ? null : run.vars.get(d[0]);
                md.append("| ").append(cell(d[1])).append(" | ").append(cell(v == null ? "" : v)).append(" |").append(LF);
            }
            md.append(LF);
        }

        // ---- steps and gates, in execution order ----
        List<Object> timeline = timeline(run);
        md.append("## Steps").append(LF).append(LF);
        if (timeline.isEmpty()) {
            md.append("This run recorded no steps.").append(LF).append(LF);
        }
        boolean anyNamespaced = hasNamespacedVars(run);
        int n = 0;
        for (Object o : timeline) {
            if (o instanceof StepExec) {
                n++;
                renderStep(md, (StepExec) o, n, run, logs, reports, anyNamespaced);
            } else {
                renderGate(md, (GateExec) o);
            }
        }

        if (!anyNamespaced && !run.steps.isEmpty()) {
            md.append("---").append(LF).append(LF);
            md.append("Note: this run predates the per-step recording of output variables, so the")
              .append(" \"variables produced\" section of each step is not available for it. The run's variables as a")
              .append(" whole are still complete, and the Output data table above is unaffected.").append(LF).append(LF);
        }
        return md.toString();
    }

    private static void renderStep(StringBuilder md, StepExec se, int n, WorkflowRun run,
                                   Map<String, StepLog> logs, Map<String, String> reports, boolean anyNamespaced) {
        String name = nz(se.name);
        md.append("### ").append(n).append(". ").append(nz(se.stepId));
        if (!name.isEmpty() && !name.equals(se.stepId)) md.append(" - ").append(name);
        md.append(LF).append(LF);

        md.append("| | |").append(LF).append("|---|---|").append(LF);
        row(md, "Status", se.status == null ? "" : se.status.name());
        row(md, "Exit code", se.exitCode == null ? "" : String.valueOf(se.exitCode));
        if (se.attempts > 1) row(md, "Attempts", String.valueOf(se.attempts));
        row(md, "Started", nz(se.startTs));
        row(md, "Ended", nz(se.endTs));
        String dur = duration(se.startTs, se.endTs);
        if (dur != null) row(md, "Duration", dur);
        if (nz(se.message).length() > 0) row(md, "Message", se.message);
        md.append(LF);

        if (se.checks != null && !se.checks.isEmpty()) {
            md.append("**Checks**").append(LF).append(LF);
            md.append("| Check | Result | Detail |").append(LF).append("|---|---|---|").append(LF);
            for (CheckResult c : se.checks) {
                if (c == null) continue;
                String label = nz(c.label).isEmpty() ? nz(c.id) : nz(c.label);
                md.append("| ").append(cell(label))
                  .append(" | ").append(cell(nz(c.status)))
                  .append(" | ").append(cell(nz(c.detail))).append(" |").append(LF);
            }
            md.append(LF);
        }

        if (anyNamespaced) {
            List<String[]> produced = stepVars(run, se.stepId);
            md.append("**Variables produced by this step**").append(LF).append(LF);
            if (produced.isEmpty()) {
                md.append("This step published no output variable.").append(LF).append(LF);
            } else {
                md.append("| Variable | Value |").append(LF).append("|---|---|").append(LF);
                for (String[] p : produced) {
                    md.append("| `").append(cell(p[0])).append("` | ").append(cell(p[1])).append(" |").append(LF);
                }
                md.append(LF);
            }
        }

        String own = (reports == null || se.stepId == null) ? null : reports.get(se.stepId);
        if (own != null && !own.trim().isEmpty()) {
            md.append("**Report produced by this step**").append(LF).append(LF);
            // Headings are pushed down three levels so the embedded document nests UNDER this step
            // instead of competing with the audit report's own "#", "##" and "###". Nothing else is
            // rewritten: the tables, the SQL and the numbers are the file as it was written.
            md.append(demoteHeadings(own.trim(), 3)).append(LF).append(LF);
        }

        StepLog log = logs == null ? null : logs.get(se.stepId);
        md.append("**Standard output**");
        if (nz(se.logFile).length() > 0) md.append("  (`").append(nz(se.logFile)).append("`)");
        md.append(LF).append(LF);
        if (log == null || log.text == null || log.text.trim().isEmpty()) {
            md.append("No log recorded for this step.").append(LF).append(LF);
        } else {
            md.append("```text").append(LF).append(stripFence(log.text));
            if (!log.text.endsWith(LF)) md.append(LF);
            md.append("```").append(LF).append(LF);
        }
    }

    private static void renderGate(StringBuilder md, GateExec g) {
        md.append("### Gate: ").append(nz(g.gateId));
        if (nz(g.name).length() > 0 && !g.name.equals(g.gateId)) md.append(" - ").append(nz(g.name));
        md.append(LF).append(LF);
        md.append("| | |").append(LF).append("|---|---|").append(LF);
        row(md, "Type", nz(g.type));
        row(md, "Condition", nz(g.condition));
        row(md, "Result", g.result == null ? "not decided" : (g.result.booleanValue() ? "passed" : "did not pass"));
        if (nz(g.decidedBy).length() > 0) row(md, "Decided by", g.decidedBy);
        row(md, "Timestamp", nz(g.ts));
        md.append(LF);
    }

    /**
     * Pushes every ATX heading down by {@code by} levels, so an embedded document nests under the
     * section that hosts it. Lines inside fenced code blocks are left completely alone - a '#' there
     * is a comment or a column name, not a heading - and a heading that would go past level 6 is
     * capped, because Markdown has no h7 and a bare run of hashes would render as literal text.
     */
    public static String demoteHeadings(String md, int by) {
        if (md == null) return "";
        String[] lines = md.replace(String.valueOf((char) 13), "").split(String.valueOf((char) 10), -1);
        StringBuilder out = new StringBuilder(md.length() + 64);
        boolean inFence = false;
        for (int i = 0; i < lines.length; i++) {
            String ln = lines[i];
            if (ln.trim().startsWith("```")) inFence = !inFence;
            if (!inFence) {
                int h = 0;
                while (h < ln.length() && ln.charAt(h) == '#') h++;
                if (h > 0 && h < ln.length() && ln.charAt(h) == ' ') {
                    int lvl = Math.min(6, h + by);
                    StringBuilder hashes = new StringBuilder();
                    for (int k = 0; k < lvl; k++) hashes.append('#');
                    ln = hashes + ln.substring(h);
                }
            }
            out.append(ln);
            if (i < lines.length - 1) out.append(LF);
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ helpers

    /** Steps and gates merged in chronological order; entries with no timestamp keep list order. */
    public static List<Object> timeline(WorkflowRun run) {
        List<Object> out = new ArrayList<Object>();
        if (run == null) return out;
        if (run.steps != null) for (StepExec s : run.steps) if (s != null) out.add(s);
        if (run.gates != null) for (GateExec g : run.gates) if (g != null) out.add(g);
        final List<Object> original = new ArrayList<Object>(out);
        java.util.Collections.sort(out, new java.util.Comparator<Object>() {
            public int compare(Object a, Object b) {
                String ta = tsOf(a), tb = tsOf(b);
                if (ta.isEmpty() || tb.isEmpty()) return original.indexOf(a) - original.indexOf(b);
                int c = ta.compareTo(tb);
                return c != 0 ? c : (original.indexOf(a) - original.indexOf(b));
            }
        });
        return out;
    }

    private static String tsOf(Object o) {
        if (o instanceof StepExec) return nz(((StepExec) o).startTs);
        if (o instanceof GateExec) return nz(((GateExec) o).ts);
        return "";
    }

    /** True when the run carries the namespaced {@code stepId.var} keys the per-step section needs. */
    public static boolean hasNamespacedVars(WorkflowRun run) {
        if (run == null || run.vars == null || run.steps == null) return false;
        for (StepExec s : run.steps) {
            if (s == null || s.stepId == null) continue;
            String pfx = s.stepId + ".";
            for (String k : run.vars.keySet()) if (k != null && k.startsWith(pfx)) return true;
        }
        return false;
    }

    /** Variables published by one step: the {@code stepId.name} keys, with the prefix stripped. */
    public static List<String[]> stepVars(WorkflowRun run, String stepId) {
        List<String[]> out = new ArrayList<String[]>();
        if (run == null || run.vars == null || stepId == null) return out;
        String pfx = stepId + ".";
        for (Map.Entry<String, String> e : run.vars.entrySet()) {
            String k = e.getKey();
            if (k == null || !k.startsWith(pfx)) continue;
            String name = k.substring(pfx.length());
            if (name.isEmpty() || name.indexOf('.') >= 0) continue;   // only direct children
            out.add(new String[]{ name, e.getValue() == null ? "" : e.getValue() });
        }
        return out;
    }

    /** Human duration between two {@code yyyy-MM-dd HH:mm:ss.SSS} stamps, or null when not computable. */
    public static String duration(String from, String to) {
        if (from == null || to == null || from.trim().isEmpty() || to.trim().isEmpty()) return null;
        try {
            java.time.LocalDateTime a = java.time.LocalDateTime.parse(from.trim(), TS);
            java.time.LocalDateTime b = java.time.LocalDateTime.parse(to.trim(), TS);
            long ms = java.time.Duration.between(a, b).toMillis();
            if (ms < 0) return null;
            return humanMillis(ms);
        } catch (Exception e) {
            return null;
        }
    }

    public static String humanMillis(long ms) {
        if (ms < 1000) return ms + " ms";
        long s = ms / 1000, m = s / 60, h = m / 60;
        if (h > 0) return h + "h " + (m % 60) + "m " + (s % 60) + "s";
        if (m > 0) return m + "m " + (s % 60) + "s";
        return String.format(java.util.Locale.ROOT, "%.1f s", ms / 1000.0);
    }

    private static void row(StringBuilder md, String k, String v) {
        md.append("| ").append(k).append(" | ").append(cell(v)).append(" |").append(LF);
    }

    /** Table cell: escape the pipe and fold line breaks, so one value cannot break the table. */
    static String cell(String v) {
        if (v == null) return "";
        StringBuilder sb = new StringBuilder(v.length() + 8);
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '|') sb.append('\\').append('|');
            else if (c == '\r') { if (i + 1 < v.length() && v.charAt(i + 1) == '\n') i++; sb.append(' '); }
            else if (c == '\n') sb.append(' ');
            else sb.append(c);
        }
        return sb.toString();
    }

    /**
     * A log line that is itself a fence would close the block early and turn the rest of the log into
     * Markdown. Neutralised rather than dropped: the content stays readable and the report stays valid.
     */
    static String stripFence(String log) {
        if (log == null) return "";
        StringBuilder sb = new StringBuilder(log.length());
        int i = 0, n = log.length();
        boolean lineStart = true;
        while (i < n) {
            char c = log.charAt(i);
            if (lineStart && c == '`' && i + 2 < n && log.charAt(i + 1) == '`' && log.charAt(i + 2) == '`') {
                sb.append("'''");
                i += 3;
                lineStart = false;
                continue;
            }
            sb.append(c);
            lineStart = (c == '\n');
            i++;
        }
        return sb.toString();
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
