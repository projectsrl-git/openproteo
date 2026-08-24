package com.legalarchive.orchestrator.json2csv;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * The run loop: list, read, build, write, and only then rename.
 *
 * <h3>Why this is here and not in InternalSteps</h3>
 *
 * Written first as a method on {@code InternalSteps}, it could not be exercised at all — that class
 * is Spring-coupled and does not compile outside the application. Everything interesting about this
 * loop is exactly the sort of thing that is wrong the first time: the order files are visited, what
 * happens to the counters when one fails, and whether the rename can run before the CSV is closed.
 *
 * <p>So reading and writing became seams. {@link DocumentReader} is Jackson in production and a map
 * of hand-built trees in the suite; {@link RowSink} is {@code CsvWriter} in production and a list in
 * the suite. What is left is Spring-free, Jackson-free, and testable — and {@code InternalSteps} is
 * reduced to assembling the parts, which is the part that cannot go subtly wrong in silence.
 */
public final class Json2CsvRun {

    /** Parses one file into a {@code Map}/{@code List}/scalar tree. Jackson lives behind this. */
    public interface DocumentReader {
        Object read(File f) throws Exception;
    }

    /** Where rows go. {@code CsvWriter} lives behind this, with its splitting and its quoting. */
    public interface RowSink {
        void header(String[] cols) throws Exception;
        void row(String[] cells) throws Exception;
    }

    public static final class Options {
        public File inputDir;
        public String filePattern = "*.json";
        /** FAIL (default) stops the run on a malformed file; SKIP counts it and carries on. */
        public boolean failOnBadFile = true;
        // No renameProcessed here on purpose: the rename is the CALLER's, after it closes the sink.
        // A flag on this object could not be honoured by this method without doing it too early, and
        // a setting that cannot take effect is worse than one that does not exist.
        public List<ColumnMapping> columns;          // in header order
        public OnNonScalar onNonScalar = OnNonScalar.FAIL;
        public ObjectNameValue objectNameValue = ObjectNameValue.FILENAME;
        public DateCoercion dates;                   // null when no Date column exists
        public long serialStart = 1;
        public int serialPad = 0;
    }

    private Json2CsvRun() { }

    /**
     * Everything matching the mask, <b>in file-name order</b>, one row each.
     *
     * @throws Json2CsvException on a refusal the operator has to act on: a malformed file under FAIL,
     *         or a document that is not shaped the way a mapping assumes
     */
    public static Json2CsvCounters run(Options o, DocumentReader reader, RowSink sink, Consumer<String> log)
            throws Exception {
        Json2CsvCounters c = new Json2CsvCounters();
        RowBuilder rb = new RowBuilder(o.columns, o.onNonScalar, o.objectNameValue, o.dates,
                o.serialStart, o.serialPad, c);
        sink.header(rb.header());

        List<File> files = list(o.inputDir, o.filePattern);
        say(log, "json2csv: " + files.size() + " file(s) matching '" + o.filePattern + "' in "
                + (o.inputDir == null ? "?" : o.inputDir.getPath()));

        // Files that produced a row. Only these are renamed, and only after the sink is closed by the
        // caller - see renameProcessed below.
        List<File> done = new ArrayList<File>();

        for (int i = 0; i < files.size(); i++) {
            File f = files.get(i);
            Object doc;
            try {
                doc = reader.read(f);
            } catch (Exception e) {
                c.filesFailed++;
                if (o.failOnBadFile) {
                    // The output is a single CSV about to be delivered, and a short delivery that
                    // looks complete is worse than a run that stops.
                    throw new Json2CsvException(f.getName() + ": " + e.getMessage(), e);
                }
                say(log, "json2csv: skipped " + f.getName() + ": " + e.getMessage());
                continue;
            }
            c.filesRead++;
            sink.row(rb.build(doc, new DocumentContext(f.getName(), relative(o.inputDir, f), f.getAbsolutePath())));
            done.add(f);
        }
        c.processed = done;
        return c;
    }

    /**
     * Renames the inputs that produced a row.
     *
     * <p>Separate from {@link #run} and called by the caller <b>after the sink is closed</b>, which is
     * the whole point. Unlike elarxml, where the rename is per file as soon as that file's batches
     * have reached their final names, here every input feeds one output: nothing is processed until
     * the CSV is complete. Renaming inside the loop would be the elarxml {@code .done} defect
     * reintroduced from the other side — inputs marked done for a delivery that never finished.
     *
     * @return how many were renamed
     */
    public static int renameProcessed(Json2CsvCounters c, Consumer<String> log) {
        int renamed = 0;
        if (c.processed == null) return 0;
        for (int i = 0; i < c.processed.size(); i++) {
            File f = c.processed.get(i);
            if (f.renameTo(new File(f.getParentFile(), f.getName() + ".done"))) renamed++;
            else say(log, "json2csv: could not rename " + f.getName() + " to .done");
        }
        return renamed;
    }

    /** Everything matching the mask, sorted by name so two runs produce byte-identical output. */
    public static List<File> list(File dir, String pattern) {
        List<File> out = new ArrayList<File>();
        if (dir == null || !dir.isDirectory()) return out;
        File[] all = dir.listFiles();
        if (all == null) return out;
        for (int i = 0; i < all.length; i++) {
            if (all[i].isFile() && FileMask.matches(all[i].getName(), pattern)) out.add(all[i]);
        }
        // String.compareTo and not a locale collator: the order must not depend on the server's
        // locale, or Serial would mean something different on two machines.
        Collections.sort(out, new Comparator<File>() {
            public int compare(File a, File b) { return a.getName().compareTo(b.getName()); }
        });
        return out;
    }

    static String relative(File dir, File f) {
        if (dir == null) return f.getName();
        String d = dir.getAbsolutePath();
        String a = f.getAbsolutePath();
        if (a.startsWith(d) && a.length() > d.length() + 1) return a.substring(d.length() + 1);
        return f.getName();
    }

    private static void say(Consumer<String> log, String s) {
        if (log != null) log.accept(s);
    }
}
