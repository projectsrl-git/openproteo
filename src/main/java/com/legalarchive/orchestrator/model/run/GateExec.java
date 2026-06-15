package com.legalarchive.orchestrator.model.run;

/** Esito di un gate all'interno di un run (per la UI e l'audit). */
public class GateExec {
    public String gateId;
    public String name;
    public String type;
    public String condition;       // condizione gia' risolta (per gate auto)
    public Boolean result;         // true/false, null = in attesa
    public String decidedBy;       // utente per gate manuali
    public String ts;
}
