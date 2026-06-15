package com.legalarchive.orchestrator.model.def;

/**
 * Gate decisionale.
 * type="auto":   valuta condition sulle variabili del run e instrada su onTrue/onFalse.
 * type="manual": sospende il run in attesa di approvazione umana dalla UI.
 * I target onTrue/onFalse sono l'id di un nodo, oppure "END:STATO"
 * (es. END:SKIPPED, END:REJECTED, END:SUCCESS) per terminare il run.
 */
public class GateDef extends NodeDef {

    public String type = "auto"; // auto | manual

    /** Condizione per gate auto, es: ${fileCount} > 0 && ${env} == PROD */
    public String condition;

    public String onTrue;
    public String onFalse;

    @Override
    public String getKind() { return "GATE"; }
}
