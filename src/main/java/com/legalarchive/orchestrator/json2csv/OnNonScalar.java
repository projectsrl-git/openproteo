package com.legalarchive.orchestrator.json2csv;

/**
 * What to do when a value cannot be used as the column's declared type.
 *
 * <p>Covers both halves of "the document is not what the mapping assumes": a path that lands on an
 * object or an array, and a value that will not read as a Number or a Date. They are one policy
 * because they are one mistake — the mapping and the data disagree — and because an operator who
 * wants to let one through wants to let the other through too.
 *
 * <p>{@link #FAIL} is the default. An absent value is NOT this: that writes empty and is counted
 * separately, because absent is data and this is configuration.
 */
public enum OnNonScalar {
    /** Stop the run, naming the column, the path and the file. */
    FAIL,
    /** Write nothing, count it, carry on. */
    EMPTY,
    /** Write the node as compact JSON text. Only meaningful for an object or an array. */
    JSON;

    public static OnNonScalar parse(String s) {
        String v = s == null ? "" : s.trim();
        if (v.isEmpty()) return FAIL;
        String u = v.toUpperCase(java.util.Locale.ROOT);
        if (u.equals("FAIL")) return FAIL;
        if (u.equals("EMPTY")) return EMPTY;
        if (u.equals("JSON")) return JSON;
        throw new Json2CsvException("onNonScalar '" + s + "' is not one of FAIL, EMPTY, JSON");
    }
}
