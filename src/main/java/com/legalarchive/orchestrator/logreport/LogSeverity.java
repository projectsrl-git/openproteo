package com.legalarchive.orchestrator.logreport;

/**
 * Severity of an audit event, classified once and reused everywhere (indexer column, grid badge,
 * chart colour, metric cards) instead of re-matching event strings in each place.
 *
 * <p>The rules mirror what {@code audit.html} already infers inline today, so the log report and the
 * per-feed audit view stay visually consistent.</p>
 */
public final class LogSeverity {

    public static final String OK = "OK";
    public static final String FAIL = "FAIL";
    public static final String WAIT = "WAIT";
    public static final String RUN = "RUN";
    public static final String SKIP = "SKIP";
    public static final String INFO = "INFO";

    private LogSeverity() { }

    /** Never returns null: an unknown or empty event is INFO. */
    public static String of(String event) {
        if (event == null) return INFO;
        String e = event.toUpperCase(java.util.Locale.ROOT);
        // failures first: an event naming both a failure and a completion is a failure
        if (e.contains("FAILED") || e.contains("ERROR") || e.contains("REJECTED") || e.contains("ABORTED")) return FAIL;
        if (e.contains("COMPLETED") || e.contains("SUCCESS") || e.contains("APPROVED")) return OK;
        if (e.contains("WAITING") || e.contains("DECISION") || e.contains("ON_HOLD") || e.contains("QUEUED")) return WAIT;
        if (e.contains("SKIPPED")) return SKIP;
        if (e.contains("STARTED") || e.contains("RUNNING") || e.contains("RESUMED") || e.contains("RETRY")) return RUN;
        return INFO;
    }
}
