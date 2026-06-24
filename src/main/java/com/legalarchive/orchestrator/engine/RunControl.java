package com.legalarchive.orchestrator.engine;

/**
 * Per-run control handle shared between the engine worker thread and the
 * stop() request: lets an operator abort a running job and kill the
 * currently executing PowerShell process.
 */
public class RunControl {
    public volatile boolean aborted = false;
    public volatile Process process;
    /** Currently executing JDBC statement (csvsql), so an operator Stop can cancel a long query. */
    public volatile java.sql.Statement statement;
    /**
     * Forcible abort action for a blocked DB operation (set by the sql/DB2 extractor): cancels the
     * statement AND closes the statement+connection, which reliably unblocks a running query/fetch on
     * drivers (e.g. AS400 jt400) where Statement.cancel() alone is a no-op. Run by stop().
     */
    public volatile Runnable aborter;
}
