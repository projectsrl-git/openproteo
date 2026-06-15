package com.legalarchive.orchestrator.model.run;

/** Stato di esecuzione di un singolo step all'interno di un run. */
public class StepExec {
    public String stepId;
    public String name;
    public StepStatus status = StepStatus.PENDING;
    public String startTs;
    public String endTs;
    public Integer exitCode;
    public int attempts = 0;
    public String logFile;
    public String message;
    /** validate step: esito dei controlli (sotto-step). */
    public java.util.List<CheckResult> checks;
}
