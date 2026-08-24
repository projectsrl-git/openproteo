package com.legalarchive.orchestrator.json2csv;

/**
 * Where a MIMEType column's value comes from (requirement 6.1).
 *
 * <p>For a {@code *.json} input the two produce the same string, which is a fair thing to ask of a
 * design. They are both kept because they fail differently: FIXED keeps writing what it was told if
 * the file mask is widened to {@code *.txt} one day, and SOURCE_EXTENSION follows it. Neither is more
 * correct — but the step should record which one it meant, and a single mode would let it say nothing.
 */
public enum MimeMode {
    FIXED,
    SOURCE_EXTENSION;

    public static MimeMode parse(String s) {
        String v = s == null ? "" : s.trim();
        if (v.isEmpty()) return FIXED;
        String u = v.toUpperCase(java.util.Locale.ROOT).replace("_", "");
        if (u.equals("FIXED")) return FIXED;
        if (u.equals("SOURCEEXTENSION")) return SOURCE_EXTENSION;
        throw new Json2CsvException("mode '" + s + "' is not one of FIXED, SOURCE_EXTENSION");
    }

    public String attributeValue() { return this == FIXED ? "FIXED" : "SOURCE_EXTENSION"; }
}
