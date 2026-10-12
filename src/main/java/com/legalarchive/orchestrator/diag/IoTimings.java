package com.legalarchive.orchestrator.diag;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How long the engine's two bookkeeping writes take: saving a run's JSON and appending an audit
 * line. Spec: {@code .claude/STALL_DIAGNOSTICS.md}.
 *
 * <p>Both are done between two steps, both hold a lock while they write, and a run that stops
 * between two steps stops in one of them - or in what is between. Each call reports two numbers:
 * the time spent waiting for the lock and the time spent inside it. A call that took
 * {@link #SLOW_MS} or more in total is kept, with the moment and what it was writing, in a short
 * ring that a diagnostic snapshot prints. Every call also feeds a count and a maximum per kind.
 *
 * <p>JDK only, static, and cheap on the fast path: two additions and a comparison under one
 * monitor. It never throws and never writes anything itself.
 */
public final class IoTimings {

    /** A call this slow or slower is kept in the ring. */
    public static final long SLOW_MS = 2000L;
    /** How many slow calls are kept. */
    public static final int RING = 40;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** One slow call. */
    public static final class Slow {
        public final String at;
        public final String kind;
        public final String detail;
        public final long waitMs;
        public final long workMs;
        Slow(String at, String kind, String detail, long waitMs, long workMs) {
            this.at = at; this.kind = kind; this.detail = detail; this.waitMs = waitMs; this.workMs = workMs;
        }
    }

    /** Count and maxima of one kind of call since the start. */
    public static final class Totals {
        public long calls;
        public long slowCalls;
        public long maxWaitMs;
        public long maxWorkMs;
        public long totalMs;
    }

    private static final Object LOCK = new Object();
    private static final Deque<Slow> SLOW = new ArrayDeque<Slow>();
    private static final Map<String, Totals> TOTALS = new LinkedHashMap<String, Totals>();

    private IoTimings() { }

    /**
     * Records one call. {@code waitNanos} is the time before the lock was taken, {@code workNanos}
     * the time inside it.
     */
    public static void record(String kind, String detail, long waitNanos, long workNanos) {
        try {
            long waitMs = Math.max(0L, waitNanos / 1_000_000L);
            long workMs = Math.max(0L, workNanos / 1_000_000L);
            boolean slow = waitMs + workMs >= SLOW_MS;
            String at = slow ? LocalDateTime.now().format(TS) : null;
            synchronized (LOCK) {
                Totals t = TOTALS.get(kind);
                if (t == null) { t = new Totals(); TOTALS.put(kind, t); }
                t.calls++;
                t.totalMs += waitMs + workMs;
                if (waitMs > t.maxWaitMs) t.maxWaitMs = waitMs;
                if (workMs > t.maxWorkMs) t.maxWorkMs = workMs;
                if (slow) {
                    t.slowCalls++;
                    SLOW.addLast(new Slow(at, kind, detail == null ? "" : detail, waitMs, workMs));
                    while (SLOW.size() > RING) SLOW.removeFirst();
                }
            }
        } catch (Throwable ignored) {
            // a measurement must never be the thing that fails a write
        }
    }

    /** The slow calls kept, oldest first. */
    public static List<Slow> slowCalls() {
        synchronized (LOCK) { return new ArrayList<Slow>(SLOW); }
    }

    /** A copy of the totals per kind. */
    public static Map<String, Totals> totals() {
        Map<String, Totals> out = new LinkedHashMap<String, Totals>();
        synchronized (LOCK) {
            for (Map.Entry<String, Totals> e : TOTALS.entrySet()) {
                Totals c = new Totals();
                Totals t = e.getValue();
                c.calls = t.calls; c.slowCalls = t.slowCalls; c.maxWaitMs = t.maxWaitMs; c.maxWorkMs = t.maxWorkMs; c.totalMs = t.totalMs;
                out.put(e.getKey(), c);
            }
        }
        return out;
    }

    /** Tests only. */
    static void reset() {
        synchronized (LOCK) { SLOW.clear(); TOTALS.clear(); }
    }
}
