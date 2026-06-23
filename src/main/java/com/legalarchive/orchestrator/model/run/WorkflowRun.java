package com.legalarchive.orchestrator.model.run;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stato completo di un'esecuzione di workflow.
 * Persistito come JSON leggibile in {baseDir}/{feedId}/_runs/{runId}.json
 * dopo ogni cambiamento di stato.
 */
public class WorkflowRun {

    public String runId;          // {feedId}_{yyyyMMdd-HHmmss}_{seq}
    public String feedId;
    public String workflowName;
    public RunStatus status = RunStatus.RUNNING;
    public String trigger;        // CRON | MANUAL | RESUME
    public String triggeredBy;    // utente o "scheduler"
    public String startTs;
    public String endTs;
    public String message;

    /** Indice del nodo corrente nella definizione (per resume dei gate manuali). */
    public int currentIndex = 0;

    /** Id del gate manuale in attesa di approvazione (null se non in attesa). */
    public String waitingGateId;

    /** Step-by-step test: descrizione del prossimo step in attesa di conferma (null se non in pausa di test). */
    public String pausedNextStep;

    /** Variabili del run: builtin (${feedId}, ${runId}, dir...) + output degli step. */
    public Map<String, String> vars = new LinkedHashMap<String, String>();

    public List<StepExec> steps = new ArrayList<StepExec>();
    public List<GateExec> gates = new ArrayList<GateExec>();

    public StepExec step(String stepId) {
        for (StepExec s : steps) if (s.stepId.equals(stepId)) return s;
        return null;
    }

    public GateExec gate(String gateId) {
        for (GateExec g : gates) if (g.gateId.equals(gateId)) return g;
        return null;
    }
}
