package com.legalarchive.orchestrator.model.def;

/** Nodo generico del workflow: step eseguibile o gate decisionale. */
public abstract class NodeDef {
    public String id;
    public String name;

    /** "STEP" o "GATE": usato dai template (l'accesso a .class e' bloccato in Thymeleaf). */
    public abstract String getKind();
}
