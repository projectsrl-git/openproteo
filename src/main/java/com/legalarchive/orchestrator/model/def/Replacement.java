package com.legalarchive.orchestrator.model.def;

import java.util.ArrayList;
import java.util.List;

/** csvreplace: una sostituzione letterale "from" -> "to", su tutti i campi o su colonne specifiche. */
public class Replacement {
    public String from;
    public String to = "";
    /** nomi colonna su cui applicare; vuoto = tutti i campi. */
    public List<String> columns = new ArrayList<String>();
}
