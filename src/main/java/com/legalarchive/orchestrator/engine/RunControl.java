package com.legalarchive.orchestrator.engine;

/**
 * Per-run control handle shared between the engine worker thread and the
 * stop() request: lets an operator abort a running job and kill the
 * currently executing PowerShell process.
 */
public class RunControl {
    public volatile boolean aborted = false;
    /** The most recently started process. Kept for compatibility; Stop does NOT rely on it. */
    public volatile Process process;
    /**
     * EVERY process this run has alive. A fan-out step runs several at once on the same control:
     * the single field above was overwritten by each, so Stop reached only the last one started
     * and the others ran to their end. Stop kills this whole set.
     */
    public final java.util.Set<ProcessTree.Handle> live =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<ProcessTree.Handle, Boolean>());
    /** Currently executing JDBC statement (csvsql), so an operator Stop can cancel a long query. */
    public volatile java.sql.Statement statement;
    /**
     * Forcible abort action for a blocked DB operation (set by the sql/DB2 extractor): cancels the
     * statement AND closes the statement+connection, which reliably unblocks a running query/fetch on
     * drivers (e.g. AS400 jt400) where Statement.cancel() alone is a no-op. Run by stop().
     */
    public volatile Runnable aborter;
}
