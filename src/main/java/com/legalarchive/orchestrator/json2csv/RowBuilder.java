package com.legalarchive.orchestrator.json2csv;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Turns one document tree into one CSV row, in dataschema order.
 *
 * <p>One file is one document is one row. Multi-row flattening — one row per element of an array,
 * with the outer values repeated — is specified in {@code .claude/JSON_TO_CSV_EXECUTOR.md} §6.3–§6.5
 * and not implemented; a path asking for it is refused by {@link MappingValidator} before a file is
 * opened, never read as {@code [0]} and never ignored.
 *
 * <p>Stateful in exactly one respect: {@link #nextSerial}, which is why the instance is per run and
 * not per file. Serial is a row number in the output, so it restarts neither per input file nor per
 * split part — the parts are one delivery, and a Serial that restarted would give two rows the same
 * number.
 */
public final class RowBuilder {

    private final List<ColumnMapping> columns;
    private final OnNonScalar onNonScalar;
    private final ObjectNameValue objectNameValue;
    private final DateCoercion dates;         // null when no Date column exists
    private final int serialPad;
    private final Json2CsvCounters counters;

    private long nextSerial;

    public RowBuilder(List<ColumnMapping> columns, OnNonScalar onNonScalar, ObjectNameValue objectNameValue,
                      DateCoercion dates, long serialStart, int serialPad, Json2CsvCounters counters) {
        if (columns == null || columns.isEmpty()) throw new Json2CsvException("no columns are mapped");
        this.columns = columns;
        this.onNonScalar = onNonScalar == null ? OnNonScalar.FAIL : onNonScalar;
        this.objectNameValue = objectNameValue == null ? ObjectNameValue.FILENAME : objectNameValue;
        this.dates = dates;
        this.nextSerial = serialStart;
        this.serialPad = serialPad;
        this.counters = counters == null ? new Json2CsvCounters() : counters;
    }

    /** The CSV header, in dataschema order — the same order every row is built in. */
    public String[] header() {
        String[] h = new String[columns.size()];
        for (int i = 0; i < columns.size(); i++) h[i] = columns.get(i).as;
        return h;
    }

    public long nextSerial() { return nextSerial; }
    public Json2CsvCounters counters() { return counters; }

    /**
     * @param document the parsed tree: Map / List / String / BigDecimal / Boolean / null
     * @throws Json2CsvException under {@link OnNonScalar#FAIL} when the document is not shaped the
     *         way a mapping assumes
     */
    public String[] build(Object document, DocumentContext ctx) {
        String[] row = new String[columns.size()];
        long serial = nextSerial++;
        for (int i = 0; i < columns.size(); i++) {
            row[i] = cell(columns.get(i), document, ctx, serial);
        }
        counters.rowsWritten++;
        return row;
    }

    // ------------------------------------------------------------------ cells

    private String cell(ColumnMapping c, Object document, DocumentContext ctx, long serial) {
        switch (c.type) {
            case SERIAL:      return serial(serial);
            case OBJECT_NAME: return objectName(ctx);
            case MIMETYPE:    return c.mode == MimeMode.SOURCE_EXTENSION
                                     ? ctx.extension()
                                     : (c.value == null ? "" : c.value);
            default:          break;
        }

        if (c.src == null) {
            // A constant column: String with a 'value' and no path. Anything else with no path is an
            // unmapped dataschema column, which is written empty so the CSV keeps the schema's shape.
            return c.value == null ? "" : c.value;
        }

        JsonPath.Resolution r = c.path.resolve(document);
        if (r.status == JsonPath.Resolution.ABSENT) {
            counters.valuesMissing++;
            return "";
        }
        if (r.status == JsonPath.Resolution.MISMATCH) {
            return refuse(c, ctx, "at '" + r.where + "' the document holds " + r.found
                    + " and the path expects to go through it", jsonNodeAt(c, document));
        }

        Object v = r.value;
        switch (c.type) {
            case NUMBER: return number(c, ctx, v);
            case DATE:   return date(c, ctx, v);
            default:     return text(v);
        }
    }

    private String serial(long n) {
        String s = Long.toString(n);
        if (serialPad <= 0 || s.length() >= serialPad) return s;
        StringBuilder sb = new StringBuilder(serialPad);
        for (int i = s.length(); i < serialPad; i++) sb.append('0');
        return sb.append(s).toString();
    }

    private String objectName(DocumentContext ctx) {
        switch (objectNameValue) {
            case FILENAME_NOEXT: return ctx.nameWithoutExtension();
            case RELATIVE_PATH:  return ctx.relativePath;
            case ABSOLUTE_PATH:  return ctx.absolutePath;
            default:             return ctx.fileName;
        }
    }

    /** A scalar as text. BigDecimal is written plain, so no CSV consumer ever meets {@code 1E+3}. */
    static String text(Object v) {
        if (v == null) return "";
        if (v instanceof BigDecimal) return ((BigDecimal) v).toPlainString();
        if (v instanceof Boolean) return ((Boolean) v).booleanValue() ? "true" : "false";
        return String.valueOf(v);
    }

    private String number(ColumnMapping c, DocumentContext ctx, Object v) {
        if (v instanceof BigDecimal) return ((BigDecimal) v).toPlainString();
        if (v instanceof Number) return new BigDecimal(v.toString()).toPlainString();
        if (v instanceof CharSequence) {
            String s = v.toString().trim();
            if (s.isEmpty()) { counters.valuesMissing++; return ""; }
            try { return new BigDecimal(s).toPlainString(); }
            catch (NumberFormatException e) {
                return refuse(c, ctx, "the value is not a number", null);
            }
        }
        return refuse(c, ctx, "the value is " + JsonPath.describe(v) + " and the column is a Number", null);
    }

    private String date(ColumnMapping c, DocumentContext ctx, Object v) {
        if (dates == null) throw new Json2CsvException("column '" + c.as
                + "' is a Date but no date format was configured for this run");
        String raw = text(v);
        if (raw.trim().isEmpty()) { counters.valuesMissing++; return ""; }
        String out = dates.convert(raw, c.from);
        if (out == null) {
            return refuse(c, ctx, "the value does not read as a date under " + dates.triedMasks(c.from), null);
        }
        return out;
    }

    /**
     * Applies {@link OnNonScalar}. The message names the column, the path and the file — and never the
     * value, which is the elarcheck rule and is not negotiable for a banking document.
     */
    private String refuse(ColumnMapping c, DocumentContext ctx, String why, Object node) {
        if (onNonScalar == OnNonScalar.JSON && node != null) {
            counters.valuesNonScalar++;
            return compactJson(node);
        }
        if (onNonScalar == OnNonScalar.FAIL) {
            throw new Json2CsvException("column '" + c.as + "' (path '" + c.src + "') in "
                    + ctx.fileName + ": " + why);
        }
        counters.valuesNonScalar++;
        return "";
    }

    /** Re-resolves for the JSON policy only, which is off the hot path and never taken under FAIL. */
    private Object jsonNodeAt(ColumnMapping c, Object document) {
        if (onNonScalar != OnNonScalar.JSON) return null;
        Object cur = document;
        List<JsonPath.Segment> segs = c.path.segments();
        for (int i = 0; i < segs.size(); i++) {
            JsonPath.Segment s = segs.get(i);
            if (s.kind == JsonPath.Segment.KEY) {
                if (!(cur instanceof Map)) return null;
                cur = ((Map<?, ?>) cur).get(s.key);
            } else if (s.kind == JsonPath.Segment.INDEX) {
                if (!(cur instanceof List)) return null;
                List<?> l = (List<?>) cur;
                if (s.index >= l.size()) return null;
                cur = l.get(s.index);
            } else {
                return null;
            }
        }
        return cur;
    }

    /**
     * Compact JSON for the {@code JSON} policy. Written here rather than delegated so that this
     * package stays free of Jackson and keeps compiling with the JDK alone.
     */
    static String compactJson(Object o) {
        StringBuilder sb = new StringBuilder();
        writeJson(sb, o);
        return sb.toString();
    }

    private static void writeJson(StringBuilder sb, Object o) {
        if (o == null) { sb.append("null"); return; }
        if (o instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeJsonString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeJson(sb, e.getValue());
            }
            sb.append('}');
            return;
        }
        if (o instanceof List) {
            sb.append('[');
            List<?> l = (List<?>) o;
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) sb.append(',');
                writeJson(sb, l.get(i));
            }
            sb.append(']');
            return;
        }
        if (o instanceof Boolean) { sb.append(((Boolean) o).booleanValue() ? "true" : "false"); return; }
        if (o instanceof BigDecimal) { sb.append(((BigDecimal) o).toPlainString()); return; }
        if (o instanceof Number) { sb.append(o.toString()); return; }
        writeJsonString(sb, String.valueOf(o));
    }

    private static void writeJsonString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0, n = s.length(); i < n; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append("\\u");
                        String hex = Integer.toHexString(c);
                        for (int k = hex.length(); k < 4; k++) sb.append('0');
                        sb.append(hex);
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }
}
