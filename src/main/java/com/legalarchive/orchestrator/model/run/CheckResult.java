package com.legalarchive.orchestrator.model.run;

/** Esito di un singolo controllo di validazione (sotto-step) all'interno di uno step validate. */
public class CheckResult {
    public String id;
    public String label;
    public String status = "PENDING";   // PENDING | RUNNING | PASS | FAIL | SKIP
    public String detail;

    public CheckResult() {}
    public CheckResult(String id, String label) { this.id = id; this.label = label; }
}
