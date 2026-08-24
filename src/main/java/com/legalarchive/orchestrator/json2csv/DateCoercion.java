package com.legalarchive.orchestrator.json2csv;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a date out of a JSON value and writes it in the feed's format (requirement 6.3).
 *
 * <p>The output mask is always {@code recordBusinessDateFormat}. There is no per-column output
 * format: the feed has one date format, and a second place to set it would guarantee the two
 * disagree.
 *
 * <h3>Why trying three input masks in order is safe HERE</h3>
 *
 * The defaults are {@code YYYY/MM/DD}, {@code YYYYMMDD}, {@code YYYY-MM-DD}, and they are
 * <b>disjoint by shape</b>: eight digits, or ten with two slashes, or ten with two dashes. No value
 * can parse under two of them, so the order cannot change an answer — it only decides which attempt
 * succeeds first.
 *
 * <p>That is a property of these three masks and not of the technique. A list of formats tried in
 * order is a dangerous idea in general: {@code DD/MM/YYYY} followed by {@code MM/DD/YYYY} reads the
 * third of April as the fourth of March and never says so. <b>A fourth default added later is not
 * automatically safe and has to be checked for overlap against the other three.</b>
 *
 * <h3>Why parsing is STRICT</h3>
 *
 * {@link ResolverStyle#STRICT} on the {@code uuuu} that the translator produces refuses
 * {@code 20260230} instead of quietly resolving it to the 28th. A Date column exists to validate as
 * much as to reformat, and a resolver that repairs impossible dates gives the validation away.
 */
public final class DateCoercion {

    /** Gate 0 Q4. Order is safe because the shapes are disjoint — see the class doc before adding one. */
    public static final String[] DEFAULT_INPUT_MASKS = { "YYYY/MM/DD", "YYYYMMDD", "YYYY-MM-DD" };

    private final DateTimeFormatter output;
    private final String outputMask;
    private final DateTimeFormatter[] inputs;
    private final String[] inputMasks;

    private DateCoercion(String outputMask, DateTimeFormatter output,
                         String[] inputMasks, DateTimeFormatter[] inputs) {
        this.outputMask = outputMask; this.output = output;
        this.inputMasks = inputMasks; this.inputs = inputs;
    }

    /**
     * @param outputMask the resolved {@code recordBusinessDateFormat}
     * @param columnMasks per-column {@code from} masks already collected; null/empty entries ignored
     */
    public static DateCoercion create(MaskTranslator translator, String outputMask, List<String> columnMasks) {
        if (translator == null) throw new Json2CsvException("no mask translator was supplied");
        if (outputMask == null || outputMask.trim().isEmpty()) {
            throw new Json2CsvException("recordBusinessDateFormat is not set, and a Date column cannot be written without it");
        }
        if (outputMask.indexOf("${") >= 0) {
            // The same wording the validate step uses, and deliberately so: an unresolved variable is
            // a different problem from a bad mask and needs a different fix.
            throw new Json2CsvException("recordBusinessDateFormat is still '" + outputMask
                    + "': the variable it refers to is not defined for this feed");
        }
        DateTimeFormatter out = compile(translator, outputMask, "recordBusinessDateFormat", false);

        // A mask that cannot render a plain date - one carrying HH or mm, say - must be found now and
        // not on the first row of a delivery. Formatting a probe is the only way to know.
        try {
            LocalDate.of(2026, 8, 24).format(out);
        } catch (RuntimeException e) {
            throw new Json2CsvException("recordBusinessDateFormat '" + outputMask
                    + "' cannot render a date: " + e.getMessage());
        }

        List<String> masks = new ArrayList<String>();
        if (columnMasks != null) {
            for (int i = 0; i < columnMasks.size(); i++) {
                String m = columnMasks.get(i);
                if (m != null && !m.trim().isEmpty() && !masks.contains(m.trim())) masks.add(m.trim());
            }
        }
        for (int i = 0; i < DEFAULT_INPUT_MASKS.length; i++) masks.add(DEFAULT_INPUT_MASKS[i]);

        String[] im = masks.toArray(new String[masks.size()]);
        DateTimeFormatter[] ifs = new DateTimeFormatter[im.length];
        for (int i = 0; i < im.length; i++) ifs[i] = compile(translator, im[i], "date input mask", true);
        return new DateCoercion(outputMask.trim(), out, im, ifs);
    }

    private static DateTimeFormatter compile(MaskTranslator t, String mask, String what, boolean strict) {
        String pattern = t.toJavaPattern(mask);
        if (pattern == null || pattern.isEmpty()) {
            throw new Json2CsvException(what + " '" + mask + "' cannot be read as a date mask");
        }
        try {
            DateTimeFormatter f = DateTimeFormatter.ofPattern(pattern);
            return strict ? f.withResolverStyle(ResolverStyle.STRICT) : f;
        } catch (RuntimeException e) {
            throw new Json2CsvException(what + " '" + mask + "' cannot be read as a date mask: " + e.getMessage());
        }
    }

    public String outputMask() { return outputMask; }
    public String[] inputMasks() { return inputMasks.clone(); }

    /**
     * @param text the value as written, already flattened to text by the caller
     * @param columnMask the column's {@code from} mask, or null for the defaults
     * @return the reformatted date, or null when the text matches no mask. Empty in, empty out: an
     *         absent date is data, and only an unparseable one is a finding.
     */
    public String convert(String text, String columnMask) {
        if (text == null) return "";
        String v = text.trim();
        if (v.isEmpty()) return "";
        if (columnMask != null && !columnMask.trim().isEmpty()) {
            LocalDate d = tryParse(v, columnMask.trim());
            return d == null ? null : d.format(output);
        }
        for (int i = 0; i < inputs.length; i++) {
            LocalDate d = parseWith(v, inputs[i]);
            if (d != null) return d.format(output);
        }
        return null;
    }

    private LocalDate tryParse(String v, String mask) {
        for (int i = 0; i < inputMasks.length; i++) {
            if (inputMasks[i].equals(mask)) return parseWith(v, inputs[i]);
        }
        throw new Json2CsvException("date input mask '" + mask + "' was not registered before the run");
    }

    private static LocalDate parseWith(String v, DateTimeFormatter f) {
        try { return LocalDate.parse(v, f); }
        catch (RuntimeException e) { return null; }
    }

    /** For the message when nothing matched: the masks that were tried, never the value. */
    public String triedMasks(String columnMask) {
        if (columnMask != null && !columnMask.trim().isEmpty()) return columnMask.trim();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < DEFAULT_INPUT_MASKS.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(DEFAULT_INPUT_MASKS[i]);
        }
        return sb.toString();
    }
}
