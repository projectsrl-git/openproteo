package com.legalarchive.orchestrator.json2csv;

/**
 * One CSV column and where its value comes from — the right-hand side of one row of the mapper.
 *
 * <p>Mirrors the {@code <column>} element: {@code as} is the CSV column (a dataschema name),
 * {@code src} is the JSON path, and the rest is type-specific. {@code src}/{@code as} keep their
 * xlsx2csv direction, so the element reads the same way in both executors.
 *
 * <p>Immutable once built, and {@link #path} is parsed once at construction: a path is resolved once
 * per document per column, and reparsing it every time would be the whole cost of the run.
 */
public final class ColumnMapping {

    public final String as;          // CSV column name (required)
    public final String src;         // JSON path, or null
    public final ColumnType type;
    public final String from;        // Date: input mask, or null for the defaults
    public final MimeMode mode;      // MIMEType only
    public final String value;       // MIMEType FIXED literal, or a constant for a String column
    public final JsonPath path;      // parsed src, or null

    public ColumnMapping(String as, String src, ColumnType type, String from, MimeMode mode, String value) {
        String a = as == null ? "" : as.trim();
        if (a.isEmpty()) throw new Json2CsvException("a column has no 'as' (the CSV column it fills)");
        this.as = a;
        String s = src == null ? null : src.trim();
        this.src = (s == null || s.isEmpty()) ? null : s;
        this.type = type == null ? ColumnType.STRING : type;
        String f = from == null ? null : from.trim();
        this.from = (f == null || f.isEmpty()) ? null : f;
        this.mode = mode == null ? MimeMode.FIXED : mode;
        this.value = value;
        if (this.src == null) {
            this.path = null;
        } else {
            try {
                this.path = JsonPath.parse(this.src);
            } catch (Json2CsvException e) {
                throw new Json2CsvException("column '" + this.as + "': " + e.getMessage(), e);
            }
        }
    }

    /** A column that reads nothing from the document: an unmapped dataschema column writes empty. */
    public static ColumnMapping unmapped(String as) {
        return new ColumnMapping(as, null, ColumnType.STRING, null, null, null);
    }

    @Override public String toString() {
        return as + " <- " + (src == null ? ("<" + type.attributeValue() + ">") : src);
    }
}
