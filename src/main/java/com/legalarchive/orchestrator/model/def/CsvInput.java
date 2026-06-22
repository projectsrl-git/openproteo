package com.legalarchive.orchestrator.model.def;

/** csvsql: one input CSV staged into the temp H2 DB under the given table name. */
public class CsvInput {
    public String csv;
    public String table;
    /** Optional input field separator; blank/null = auto-detect from the header. */
    public String delimiter;
}
