package com.legalarchive.orchestrator.model.def;

/** Fine di un blocco LOOP (marker). Si appaia al LOOP precedente per annidamento. */
public class LoopEndDef extends NodeDef {
    @Override
    public String getKind() { return "ENDLOOP"; }
}
