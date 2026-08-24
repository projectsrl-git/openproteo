package com.legalarchive.orchestrator.json2csv;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Checks the mapping <b>before a single file is opened</b>.
 *
 * <p>Everything here is knowable from the step definition alone, so all of it fires when the step
 * starts rather than on the first row of a delivery. That is the cheap version of the elarxml
 * pre-scan, and it costs nothing because the mapping is known before the data is.
 *
 * <p>Every problem is collected and reported together. A mapping with four mistakes in it should be
 * corrected once, not discovered one failed run at a time.
 */
public final class MappingValidator {

    private MappingValidator() { }

    /**
     * @param columns the mapping, in header order
     * @param dataschemaColumns the dataschema column names, or null when no dataschema is configured
     * @return the problems found, in reading order; empty when the mapping is good
     */
    public static List<String> problems(List<ColumnMapping> columns, List<String> dataschemaColumns) {
        List<String> out = new ArrayList<String>();
        if (columns == null || columns.isEmpty()) {
            out.add("no columns are mapped");
            return out;
        }

        Set<String> seen = new HashSet<String>();
        Set<String> schema = dataschemaColumns == null ? null : new HashSet<String>(dataschemaColumns);

        for (int i = 0; i < columns.size(); i++) {
            ColumnMapping c = columns.get(i);

            if (!seen.add(c.as)) {
                out.add("column '" + c.as + "' is mapped more than once");
            }
            if (schema != null && !schema.contains(c.as)) {
                out.add("column '" + c.as + "' is not in the dataschema");
            }

            // The refusal of [] — the one this whole design turns on. Never read as [0], never
            // ignored: reading it as [0] would deliver a feed short by every element after the first
            // with nothing saying so, and ignoring the column would deliver it empty. Both are found
            // in ELAR months later. This is found when the step is saved.
            if (c.path != null && c.path.hasAnyIndex()) {
                out.add("column '" + c.as + "' maps '" + c.src + "': '[]' asks for one row per array"
                        + " element, and multi-row flattening is not implemented."
                        + " Use an explicit index such as '" + withFirstIndex(c.src) + "' to take one element.");
            }

            switch (c.type) {
                case SERIAL:
                case OBJECT_NAME:
                    if (c.src != null) {
                        out.add("column '" + c.as + "' is a " + c.type.attributeValue()
                                + " and takes no path, but maps '" + c.src + "'");
                    }
                    break;
                case MIMETYPE:
                    if (c.src != null) {
                        out.add("column '" + c.as + "' is a MIMEType and takes no path, but maps '" + c.src + "'");
                    }
                    if (c.mode == MimeMode.FIXED && (c.value == null || c.value.isEmpty())) {
                        out.add("column '" + c.as + "' is a FIXED MIMEType with no value to write");
                    }
                    if (c.mode == MimeMode.SOURCE_EXTENSION && c.value != null && !c.value.isEmpty()) {
                        // A setting that cannot take effect is refused, not ignored - the same rule
                        // that made contentIfsPath under LOCAL an error in elarxml.
                        out.add("column '" + c.as + "' takes its MIMEType from the file extension,"
                                + " so the fixed value '" + c.value + "' can never be written");
                    }
                    break;
                case DATE:
                    if (c.src == null) {
                        out.add("column '" + c.as + "' is a Date but maps no path");
                    }
                    if (c.value != null && !c.value.isEmpty()) {
                        out.add("column '" + c.as + "' is a Date and cannot also carry a fixed value");
                    }
                    break;
                case NUMBER:
                    if (c.src == null) {
                        out.add("column '" + c.as + "' is a Number but maps no path");
                    }
                    if (c.value != null && !c.value.isEmpty()) {
                        out.add("column '" + c.as + "' is a Number and cannot also carry a fixed value");
                    }
                    break;
                default:
                    if (c.src != null && c.value != null && !c.value.isEmpty()) {
                        out.add("column '" + c.as + "' has both a path and a fixed value; it can have one");
                    }
                    break;
            }

            if (c.type != ColumnType.DATE && c.from != null) {
                out.add("column '" + c.as + "' is a " + c.type.attributeValue()
                        + " and has no use for the input date mask '" + c.from + "'");
            }
        }
        return out;
    }

    /** True when any column needs a date format, and therefore when recordBusinessDateFormat matters. */
    public static boolean needsDates(List<ColumnMapping> columns) {
        if (columns == null) return false;
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).type == ColumnType.DATE) return true;
        }
        return false;
    }

    /** Every per-column {@code from} mask, for {@link DateCoercion#create}. */
    public static List<String> inputMasks(List<ColumnMapping> columns) {
        List<String> out = new ArrayList<String>();
        if (columns == null) return out;
        for (int i = 0; i < columns.size(); i++) {
            ColumnMapping c = columns.get(i);
            if (c.type == ColumnType.DATE && c.from != null) out.add(c.from);
        }
        return out;
    }

    /** Validates and throws with everything at once, which is how a mapping gets corrected in one pass. */
    public static void check(List<ColumnMapping> columns, List<String> dataschemaColumns) {
        List<String> p = problems(columns, dataschemaColumns);
        if (p.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        sb.append(p.size() == 1 ? "the column mapping has a problem: " : "the column mapping has ")
          .append(p.size() == 1 ? "" : (p.size() + " problems: "));
        for (int i = 0; i < p.size(); i++) {
            if (i > 0) sb.append("; ");
            sb.append(p.get(i));
        }
        throw new Json2CsvException(sb.toString());
    }

    /** Turns the first {@code []} of a path into {@code [0]}, for the suggestion in the message. */
    static String withFirstIndex(String src) {
        int i = src == null ? -1 : src.indexOf("[]");
        if (i < 0) return src;
        return src.substring(0, i) + "[0]" + src.substring(i + 2);
    }
}
