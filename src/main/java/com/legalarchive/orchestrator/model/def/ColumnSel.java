package com.legalarchive.orchestrator.model.def;

/**
 * One selected column.
 *
 * <p>{@code xlsx2csv}: source (header name or letter) and optional output rename — the original use,
 * unchanged.
 *
 * <p>{@code json2csv}: {@code src} is the JSON attribute path and {@code as} is the dataschema column
 * it fills, plus the four fields below. They are <b>optional and null for every xlsx2csv step</b>, and
 * the writer emits them only when non-empty, so an xlsx2csv definition round-trips exactly as before.
 */
public class ColumnSel {
    public String src;
    public String as;

    /** json2csv: String (default), Number, Date, MIMEType, Serial, ObjectName. */
    public String type;
    /** json2csv, Date only: the INPUT mask; empty means the three defaults are tried in order. */
    public String from;
    /** json2csv, MIMEType only: FIXED (default) or SOURCE_EXTENSION. */
    public String mode;
    /** json2csv: the FIXED MIMEType literal, or a constant for a String column with no path. */
    public String value;
}
