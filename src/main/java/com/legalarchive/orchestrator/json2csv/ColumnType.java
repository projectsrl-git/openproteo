package com.legalarchive.orchestrator.json2csv;

/**
 * The type dropdown of requirement 6, plus the one that answers requirement 5.
 *
 * <p>{@link #SERIAL} and {@link #OBJECT_NAME} are the two whose value does not come from the JSON at
 * all. Putting ObjectName here rather than in a parameter naming a column is what keeps them in the
 * same dropdown: they are the same mechanism and they belong in the same place.
 */
public enum ColumnType {
    STRING,
    NUMBER,
    DATE,
    MIMETYPE,
    SERIAL,
    OBJECT_NAME;

    /** Parses the attribute value, case-insensitively, accepting the designer spelling. */
    public static ColumnType parse(String s) {
        String v = s == null ? "" : s.trim();
        if (v.isEmpty()) return STRING;
        String u = v.toUpperCase(java.util.Locale.ROOT).replace("_", "");
        if (u.equals("STRING") || u.equals("TEXT")) return STRING;
        if (u.equals("NUMBER") || u.equals("NUMERIC")) return NUMBER;
        if (u.equals("DATE")) return DATE;
        if (u.equals("MIMETYPE") || u.equals("MIME")) return MIMETYPE;
        if (u.equals("SERIAL")) return SERIAL;
        if (u.equals("OBJECTNAME")) return OBJECT_NAME;
        throw new Json2CsvException("type '" + s + "' is not one of String, Number, Date, MIMEType, Serial, ObjectName");
    }

    /** The spelling written back to the workflow XML. */
    public String attributeValue() {
        switch (this) {
            case STRING: return "String";
            case NUMBER: return "Number";
            case DATE: return "Date";
            case MIMETYPE: return "MIMEType";
            case SERIAL: return "Serial";
            default: return "ObjectName";
        }
    }
}
