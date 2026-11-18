package com.legalarchive.orchestrator.control;

/**
 * Exit codes of the CLI (spec {@code .claude/HEADLESS.md} section 6.3). The ranges mean something:
 * 0-9 the outcome of a run, 10-19 a refusal by the instance, 20-29 the channel, 30+ local.
 */
public final class ExitCodes {

    private ExitCodes() { }

    public static final int OK = 0;
    public static final int RUN_FAILED = 1;
    public static final int USAGE = 2;
    public static final int RUN_WAITING_APPROVAL = 3;
    public static final int RUN_ON_HOLD = 4;
    public static final int RUN_REJECTED = 5;
    public static final int RUN_ABORTED = 6;
    public static final int RUN_SKIPPED = 7;

    public static final int UNKNOWN = 10;
    public static final int LOCKED = 11;
    public static final int CONFLICT = 12;
    public static final int INVALID_VARIABLE = 13;

    public static final int NO_INSTANCE = 20;
    public static final int EXPIRED = 21;
    public static final int PROTOCOL = 22;
    /** Added in H1: the instance claimed the request and failed while executing it. */
    public static final int INSTANCE_ERROR = 23;

    public static final int LOCAL = 30;

    /**
     * The exit code for a run status, by name so the CLI needs no model class. -1 for a status
     * that is neither final nor paused (QUEUED, RUNNING): the run is still going.
     */
    public static int forRunStatus(String status) {
        if (status == null) return -1;
        switch (status) {
            case "SUCCESS": return OK;
            case "FAILED": return RUN_FAILED;
            case "WAITING_APPROVAL": return RUN_WAITING_APPROVAL;
            case "ON_HOLD": return RUN_ON_HOLD;
            case "REJECTED": return RUN_REJECTED;
            case "ABORTED": return RUN_ABORTED;
            case "SKIPPED": return RUN_SKIPPED;
            default: return -1;
        }
    }
}
