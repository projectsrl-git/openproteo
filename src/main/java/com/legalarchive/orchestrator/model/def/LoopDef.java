package com.legalarchive.orchestrator.model.def;

/**
 * Inizio di un blocco LOOP sequenziale. Itera la lista risolta da {@code over}
 * (split per {@code delimiter}, default ';') eseguendo i nodi tra questo LOOP e il
 * relativo ENDLOOP una volta per elemento, in ordine. A ogni giro espone:
 *   ${itemVar} (default "item"), ${indexVar} (default "loopIndex", 1-based),
 *   ${indexStringVar} (default "loopIndexString", the 1-based index left-padded with '0'
 *   to {@code indexPad} chars, e.g. 001), ${countVar} (default "loopCount").
 * Se la lista e' vuota il blocco viene saltato. Supporta annidamento.
 */
public class LoopDef extends NodeDef {
    public String over;                  // es. ${csvFiles}
    public String delimiter;             // default ";"
    public String itemVar = "item";
    public String indexVar = "loopIndex";          // 1-based
    public String indexStringVar = "loopIndexString";
    public int indexPad = 3;                        // LPAD '0' width for indexStringVar
    public String countVar = "loopCount";

    @Override
    public String getKind() { return "LOOP"; }
}
