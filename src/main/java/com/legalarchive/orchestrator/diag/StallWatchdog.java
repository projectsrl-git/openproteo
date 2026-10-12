package com.legalarchive.orchestrator.diag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.legalarchive.orchestrator.diag.StallSnapshot.EngineState;
import com.legalarchive.orchestrator.diag.StallSnapshot.RunView;
import com.legalarchive.orchestrator.diag.StallSnapshot.StepView;

/**
 * Decides when a run has stopped moving. Spec: {@code .claude/STALL_DIAGNOSTICS.md}.
 *
 * <p><b>The rule.</b> Between two steps the engine needs milliseconds: it saves the run, writes an
 * audit line, evaluates a gate. A run that is RUNNING, has no step RUNNING, and whose state - the
 * node it is at, the status and end of every step, the last gate - has not changed for
 * {@link #STALL_MS} is stuck between two steps. That is the case seen twice on the author's
 * server (2026-10-09 and 2026-10-12), and it is the only case judged: a step that runs for a
 * long time is not, because a long step can be legitimate (an unpack of 100 000 objects).
 *
 * <p>One snapshot when the rule first holds; while the run stays stuck in the same state, one
 * more every {@link #REPEAT_MS}, at most {@link #MAX_PER_STALL} in all. Test runs are not judged.
 *
 * <p>Pure logic: the caller passes the engine's state and the time, and takes the snapshots.
 */
public final class StallWatchdog {

    public static final long STALL_MS = 120_000L;
    public static final long REPEAT_MS = 15L * 60_000L;
    public static final int MAX_PER_STALL = 3;

    /** A run found stuck, and why. */
    public static final class Due {
        public final RunView run;
        public final String reason;
        public final long stuckMs;
        Due(RunView run, String reason, long stuckMs) { this.run = run; this.reason = reason; this.stuckMs = stuckMs; }
    }

    private static final class Track {
        final String fingerprint;
        final long since;
        int snapshots;
        long lastSnapshot;
        Track(String fingerprint, long since) { this.fingerprint = fingerprint; this.since = since; }
    }

    private final Map<String, Track> tracks = new HashMap<String, Track>();

    /** The runs that need a snapshot now. */
    public synchronized List<Due> check(EngineState state, long now) {
        List<Due> due = new ArrayList<Due>();
        Set<String> live = new HashSet<String>();
        if (state != null) for (RunView r : state.runs) {
            if (r == null || r.runId == null) continue;
            if (!judged(r)) continue;
            live.add(r.runId);
            String fp = fingerprint(r);
            Track t = tracks.get(r.runId);
            if (t == null || !t.fingerprint.equals(fp)) {
                tracks.put(r.runId, new Track(fp, now));
                continue;
            }
            long stuck = now - t.since;
            if (stuck < STALL_MS) continue;
            boolean first = t.snapshots == 0;
            boolean again = t.snapshots > 0 && t.snapshots < MAX_PER_STALL && now - t.lastSnapshot >= REPEAT_MS;
            if (!first && !again) continue;
            t.snapshots++;
            t.lastSnapshot = now;
            due.add(new Due(r, "run " + r.runId + " (feed " + r.feedId + ") is RUNNING with no step running and has not moved for "
                    + StallSnapshot.duration(stuck) + ", at node #" + r.currentIndex + " "
                    + (r.currentNodeId == null ? "?" : r.currentNodeId)
                    + (t.snapshots > 1 ? " (snapshot " + t.snapshots + " of this stop)" : ""), stuck));
        }
        for (Iterator<String> it = tracks.keySet().iterator(); it.hasNext(); ) {
            if (!live.contains(it.next())) it.remove();
        }
        return due;
    }

    /** RUNNING, not a test run, and no step running. */
    static boolean judged(RunView r) {
        if (!"RUNNING".equals(r.status)) return false;
        if (r.runId.contains("_test_")) return false;
        for (StepView s : r.steps) if ("RUNNING".equals(s.status)) return false;
        return true;
    }

    /** Everything that changes when the engine moves on. */
    static String fingerprint(RunView r) {
        StringBuilder b = new StringBuilder();
        b.append(r.currentIndex).append('|').append(r.lastGateTs).append('|');
        for (StepView s : r.steps) {
            b.append(s.id).append(':').append(s.status).append(':').append(s.endTs).append(':').append(s.attempts).append(';');
        }
        return b.toString();
    }
}
