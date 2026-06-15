package com.legalarchive.orchestrator.engine;

/**
 * Per-run control handle shared between the engine worker thread and the
 * stop() request: lets an operator abort a running job and kill the
 * currently executing PowerShell process.
 */
public class RunControl {
    public volatile boolean aborted = false;
    public volatile Process process;
}
