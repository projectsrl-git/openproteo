package com.legalarchive.orchestrator.model.def;

/**
 * Inizio di un blocco LOOP sequenziale. Itera la lista risolta da {@code over}
 * (split per {@code delimiter}, default ';') eseguendo i nodi tra questo LOOP e il
 * relativo ENDLOOP una volta per elemento, in ordine. A ogni giro espone:
 *   ${itemVar} (default "item"), ${indexVar} (default "loopIndex", 0-based),
 *   ${countVar} (default "loopCount").
 * Se la lista e' vuota il blocco viene saltato. Supporta annidamento.
 */
public class LoopDef extends NodeDef {
    public String over;                  // es. ${csvFiles}
    public String delimiter;             // default ";"
    public String itemVar = "item";
    public String indexVar = "loopIndex";
    public String countVar = "loopCount";

    @Override
    public String getKind() { return "LOOP"; }
}
