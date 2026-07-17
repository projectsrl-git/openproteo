package com.legalarchive.orchestrator.model.def;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Definizione di un workflow caricata da XML.
 * L'identita del workflow e' il feedId del Legal Archive: e' la chiave
 * di directory, log, run id e variabile ${feedId} disponibile in tutti gli step.
 */
public class WorkflowDef {

    /** Feed ID del Legal Archive (es. LA-EOR-001). Chiave univoca. */
    public String feedId;
    public String sourceId;
    public String targetId;
    public String sourceDescription;
    public String targetDescription;
    public boolean production;   // PROD environment flag: forces anonymize/mask to passthrough
    public boolean locked;       // maintenance lock: blocks manual/scheduled execution (testing still allowed)
    /** Free-form tags shown/searchable on the home dashboard. Values may contain ${var} placeholders. */
    public java.util.List<String> tags = new java.util.ArrayList<String>();

    /** Nome friendly del feed (es. "EORFULL verso Legal Archive"). */
    public String name;

    /** Espressione cron Spring (6 campi). Null/vuota = solo esecuzione manuale. */
    public String cron;

    /** Directory base sotto cui viene creata la struttura del feed. */
    public String baseDir;

    /** Descrizione opzionale. */
    public String description;

    /** Variabili statiche definite nel workflow (sovrascrivibili a runtime). */
    public Map<String, String> variables = new LinkedHashMap<String, String>();

    /** Workflow-level output data definitions: variable name -> description (surfaced in Operations). */
    public Map<String, String> outputData = new LinkedHashMap<String, String>();

    /** Sequenza ordinata di nodi: StepDef e GateDef. */
    public List<NodeDef> nodes = new ArrayList<NodeDef>();

    /** File XML di provenienza (per diagnostica/reload). */
    public String sourceFile;

    public List<StepDef> steps() {
        List<StepDef> out = new ArrayList<StepDef>();
        for (NodeDef n : nodes) {
            if (n instanceof StepDef) out.add((StepDef) n);
        }
        return out;
    }

    public int indexOfNode(String id) {
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).id.equals(id)) return i;
        }
        return -1;
    }
}
