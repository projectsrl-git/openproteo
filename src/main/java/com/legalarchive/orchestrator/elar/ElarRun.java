package com.legalarchive.orchestrator.elar;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The run: pre-scan every input, then stream each document into batches of INDX/PULL pairs.
 *
 * Deliberately free of Spring and of the orchestrator's own types, exactly as the rest of this
 * package, so the whole run can be executed against real files in a test rather than only on deploy.
 * {@code InternalSteps} does nothing but translate parameters into {@link Options} and counters back
 * into run variables.
 *
 * <p>Nothing accumulates. One row is read, mapped, written and discarded; the peak heap is
 * independent of the batch size and of the size of the embedded files.
 */
public final class ElarRun {

    /** Everything the run needs, already resolved. */
    public static final class Options {
        public File inputDir;
        public File outputDir;
        public File propertiesFile;
        public String familyType;
        public File indexTemplate;
        public File pullTemplate;

        public String inputCharset = "UTF-8";
        // UTF-8, and it is a DELIBERATE exception to the conservative-default rule: it changes the
        // bytes of every family that does not set the parameter, on the first run after deploy.
        // Measured, not assumed - the INDX ELAR receives today from the PowerShell scripts is
        // UTF-8 and declares UTF-8 (byte probe: 147 valid multibyte sequences, zero stray high
        // bytes). ISO-8859-1 was chosen from the legacy JAR's behaviour, which is a different
        // producer. The declaration is generated from this value, so the file stays
        // self-consistent whatever it is set to.
        public String outputCharset = "UTF-8";
        public boolean failOnMalformedInput = true;
        public char separator = ';';
        public char quoteChar = 0;
        public String listSeparator = ",";
        public String skipPrefix = "out_";
        public int maxLineLength = 0;                 // 0 = take max.line.length, else 20000
        public BatchPolicy.By batchBy = BatchPolicy.By.DOCUMENTS;
        public long maxBytesPerBatch = 200L * 1024 * 1024;
        public BatchPolicy.Oversize oversize = BatchPolicy.Oversize.WRITE_ALONE;
        public boolean onMalformedRowFail = true;
        /**
         * A referenced content file that is not on disk. SKIP by default, which is a DECLARED
         * exception to the conservative-default rule: until now a missing file failed the whole run,
         * through the same switch as a malformed row. The two are different problems - a malformed
         * row means the file is broken and re-running will not help, a missing content file usually
         * means staging has not finished - so they now have separate policies. FAIL restores the
         * previous behaviour for a family that wants it.
         */
        public boolean onMissingFileFail = false;
        /** Copy each skipped row into '<input>.skipped'. On by default: a skip with no record of it is a silent loss. */
        public boolean writeSkippedRows = true;
        /**
         * One element per line, indented. ON by default, decided explicitly: it costs space and
         * changes the bytes of every family on the first run after deploy, and buys a file that can
         * be checked by eye - which is what a feed being validated against a legacy one is for.
         * Whitespace between elements is insignificant in XML, values are written as unbreakable
         * units so none of them is touched, and the content payload stays attached to its own tags.
         */
        public boolean formatOutput = true;
        /**
         * Where the {@code <ELAR:Content>} payload is read from. Null means the local filesystem under
         * the family's {@code documentPath}, which is what every existing workflow gets.
         */
        public ContentStore contentStore;
        /**
         * Refuse to open a new INDX when the output disk could not hold two more of them.
         *
         * Only under {@code batchBy=BYTES}, because only there is there a figure to reason from. On by
         * default: running out of disk part-way through a delivery is not a hypothetical here, and the
         * manual repair afterwards is the thing this exists to remove.
         */
        public boolean checkFreeDisk = true;
        /**
         * One log line per document, naming the INDX it went into, the document id from
         * {@code input.doc_id_reference} and the content file name.
         *
         * On by default: without it, an INDX rejected by ELAR leaves no record on this side of WHICH
         * documents were in it, and the file has to be reopened and parsed to find out. Only the two
         * identifiers are logged - never a field value - which is the same line the pre-scan and the
         * findings file already draw.
         */
        public boolean logDocuments = true;
        /**
         * Where the free-space figure comes from. Null means ask the filesystem, which is what a real
         * run does. It exists because a disk cannot be filled on demand in a test, and a safeguard that
         * has never been seen to fire is not a safeguard - so the one thing that cannot be simulated is
         * the one thing that is injected.
         */
        public java.util.function.ToLongFunction<File> freeSpaceProbe;
        public boolean validate = false;
        public boolean renameProcessed = true;
        public boolean overwriteExisting = false;
        public String descriptorsElement = "DocumentDescriptors";
        public LocalDateTime now = LocalDateTime.now();
    }

    private ElarRun() { }

    /**
     * Overhead per document beyond the Base64 itself: tags, attributes and line separators.
     *
     * This figure is <b>chosen, not measured</b>. It only shifts where the byte budget rolls over by a
     * couple of kilobytes per document, and it is unused entirely under the default
     * {@code batchBy=DOCUMENTS}, so it cannot corrupt anything - but it is the one number in this
     * executor that is not derived from something. {@link BatchPolicy#estimateDrifted} exists to catch
     * it: it compares the estimate against the bytes actually written for every document and logs when
     * the two come apart, so a wrong figure reports itself on the first real run rather than silently
     * batching at the wrong point. Correct it from that log rather than from another guess.
     */
    static final long PER_DOCUMENT_OVERHEAD = 2048;

    /**
     * @param log receives the step log; never a row's content, only names, counts and line numbers
     * @return the counters, which the caller publishes as run variables
     */
    public static ElarCounters run(Options o, Consumer<String> log) throws Exception {
        ElarCounters counters = new ElarCounters();

        ElarConfig cfg = ElarConfig.load(o.propertiesFile, o.familyType);
        Map<String, String> mapping = cfg.tagNameMapping();
        String contentTag = cfg.contentTag();
        String dsakTag = cfg.dsakTag();
        String hashTag = cfg.hashTag();
        String docIdTag = cfg.docIdTag(mapping);
        // Where the payload comes from. LOCAL is the only implementation in this batch and reproduces
        // exactly what the executor has always done; an IFS store slots in here without this class
        // learning anything about JTOpen. Supplied by the caller when it is not local.
        ContentStore store = o.contentStore != null ? o.contentStore
                : new LocalContentStore(new File(cfg.documentPath()));

        String idms = cfg.opt("idms.namespace", null);
        if (idms == null) {
            throw new IllegalArgumentException("missing property '" + o.familyType + ".idms.namespace' (or the"
                    + " unprefixed 'idms.namespace') in " + cfg.sourcePath() + " - it is needed to find the"
                    + " document container in the INDX template");
        }
        IndxTemplate indx = IndxTemplate.parse(o.indexTemplate, idms, o.descriptorsElement);
        PullTemplate pull = PullTemplate.parse(o.pullTemplate);
        if (!pull.hasPlaceholder()) {
            log.accept("warning: the PULL template contains no " + PullTemplate.PLACEHOLDER
                    + ", so every PULL will reference the same index name");
        }
        List<String> unknown = indx.unknownMappedTags(mapping);
        if (!unknown.isEmpty()) {
            // a mapped tag the template does not contain writes nothing at all, silently
            log.accept("warning: tagNameMapping names " + unknown.size() + " tag(s) the INDX template does not"
                    + " contain, whose values will not appear anywhere: " + String.join(", ", unknown));
        }

        int maxLine = o.maxLineLength > 0 ? o.maxLineLength : cfg.optInt("max.line.length", 20000);
        BatchPolicy policy = new BatchPolicy(o.batchBy, cfg.optInt("output.max_index_docs", 0),
                o.maxBytesPerBatch, o.oversize);
        // The filenames come ENTIRELY from these two patterns - nothing here adds an extension or a
        // counter - so printing them turns "where did this name come from" into a log lookup instead
        // of a properties-file hunt. start_time is printed with them because the C-segment is a
        // synthetic clock seeded from it, not a timestamp.
        log.accept("elarxml: index pattern " + cfg.req("output.index_name_pattern")
                + ", pull pattern " + cfg.req("output.pull_name_pattern")
                + ", start_time " + (cfg.opt("output.start_time", null) == null
                        ? "(unset - the run's wall clock)" : cfg.opt("output.start_time", null))
                + ", output formatting " + (o.formatOutput ? "on" : "off"));
        BatchNaming naming = new BatchNaming(cfg.req("output.index_name_pattern"),
                cfg.req("output.pull_name_pattern"),
                cfg.optInt("output.files_per_julian_date", 0),
                cfg.optInt("output.julian_date_start", 0),
                cfg.opt("output.start_time", null),
                o.now);

        log.accept("elarxml: family " + cfg.family() + ", " + policy.describe());
        for (String s : naming.describe()) log.accept("elarxml: " + s);
        log.accept("elarxml: output charset " + o.outputCharset + ", max line " + maxLine
                + ", input charset " + o.inputCharset);

        counters.sameDayPairsFound = naming.countSameDayPairs(o.outputDir);
        if (counters.sameDayPairsFound > 0) {
            log.accept("elarxml: " + counters.sameDayPairsFound + " file(s) for today's julian date are already"
                    + " in the output directory. This run adds to them rather than replacing them; nothing is"
                    + " overwritten, but duplicates will accumulate if this is an unintended re-run.");
        }

        List<File> inputs = listInputs(o.inputDir, o.skipPrefix);
        if (inputs.isEmpty()) {
            log.accept("elarxml: no input file in " + o.inputDir.getAbsolutePath()
                    + " (files starting with '" + o.skipPrefix + "' are skipped)");
            return counters;
        }

        // ---- the blocking pre-scan, before a single byte of output exists ----
        ElarPreScan.Report scan = ElarPreScan.scan(inputs, cfg, o.inputCharset, o.failOnMalformedInput,
                o.separator, o.quoteChar, store);
        log.accept("elarxml: " + scan.message());
        // Two problems, two policies. A malformed row means the input is broken and re-running will
        // not help; a missing content file usually means staging has not finished, and the rows that
        // DO have their files are still deliverable. One switch for both made the second hostage to
        // the first.
        if (scan.malformedRowCount > 0 && o.onMalformedRowFail) {
            throw new IOException(scan.message());
        }
        if (scan.missingFileCount > 0 && o.onMissingFileFail) {
            throw new IOException(scan.message());
        }
        if (scan.missingFileCount > 0) {
            log.accept("elarxml: " + scan.missingFileCount + " row(s) reference a content file that is not"
                    + " on disk; they are skipped and"
                    + (o.writeSkippedRows
                        ? " copied to '<input>" + SkippedRows.SUFFIX + "' beside their input, header included,"
                          + " so the file can be fixed and fed back in"
                        : " NOT recorded anywhere, because writeSkippedRows is off"));
        }
        counters.rowsMalformed = scan.malformedRowCount;

        ElarValidator validator = o.validate ? new ElarValidator(docIdTag, cfg.notDuplicatedTags(o.listSeparator)) : null;

        Batch batch = null;
        List<String> written = new ArrayList<String>();
        // The .done rename is PER INPUT FILE, not per run. An input is renamed as soon as every batch
        // IT contributed to has reached its final name - not once every batch of every file has.
        // These two lists are what makes "its own batches" decidable:
        //   contributors    the inputs that put at least one document into the batch currently open;
        //   awaitingRename  the inputs fully read whose last documents are still in that open batch.
        // A batch closing flushes the intersection. See §5 of ELAR_XML_EXECUTOR.md.
        List<File> contributors = new ArrayList<File>();
        List<File> awaitingRename = new ArrayList<File>();
        // One discards file per input, keyed by input, finalised on the SAME event as the .done
        // rename: a '.skipped' file therefore means exactly what '.done' means.
        Map<File, SkippedRows> discards = new LinkedHashMap<File, SkippedRows>();
        try {
            for (int fi = 0; fi < inputs.size(); fi++) {
                File in = inputs.get(fi);
                counters.filesProcessed++;
                FlatCsvReader r = new FlatCsvReader(in, o.inputCharset, o.failOnMalformedInput, o.separator, o.quoteChar);
                SkippedRows skipped = new SkippedRows(in, r.headerLine(), o.inputCharset);
                discards.put(in, skipped);
                try {
                    int expect = r.headerSize();
                    FlatCsvReader.Row row;
                    while ((row = r.next()) != null) {
                        if (row.fields.length != expect) {                                        // SKIP mode only
                            // NOT counted here: the pre-scan already counted every malformed row in
                            // every file, and counting again made rowsMalformed report double under
                            // onMalformedRow=SKIP. The pre-scan's count is the authoritative one - it
                            // is taken before any output exists and it sees rows this loop may never
                            // reach.
                            if (o.writeSkippedRows) skipped.add(row.raw);
                            continue;
                        }
                        Map<String, String> tags = r.asTagMap(row, mapping);
                        if (validator != null) validator.check(row.lineNo, tags);

                        String raw = tags.get(contentTag);
                        if (raw == null || raw.trim().isEmpty()) {
                            counters.skipped(ElarCounters.Skip.NO_PATH);
                            // an empty path is a discard too. Re-running will not rescue it - the
                            // source has to be corrected - but a discards file that listed only SOME
                            // of the dropped rows would misrepresent what was archived, and this is
                            // an archive.
                            if (o.writeSkippedRows) skipped.add(row.raw);
                            continue;
                        }
                        String content = store.resolve(raw.trim());
                        if (!store.exists(content)) {
                            counters.skipped(ElarCounters.Skip.FILE_MISSING);
                            if (o.writeSkippedRows) skipped.add(row.raw);
                            continue;
                        }

                        long estimate = ContentEmbedder.encodedLength(store.length(content)) + PER_DOCUMENT_OVERHEAD;
                        BatchPolicy.Decision dec = policy.decide(estimate);
                        if (dec.oversizeDocument) counters.documentsOversize++;
                        if (dec.action != BatchPolicy.Action.APPEND && batch != null) {
                            closeBatch(batch, indx, pull, counters, written, log,
                                    contributors, awaitingRename, o.renameProcessed, discards);
                            batch = null;
                            policy.rolled();
                        }
                        if (batch == null) {
                            // The check belongs HERE and nowhere else. Between batches the question of
                            // which rows are done has one answer: every batch opened so far has reached
                            // its final name, so the rows before this one are delivered and this one is
                            // not. A check anywhere inside a batch would have to answer it with a
                            // half-written INDX in hand.
                            checkDisk(o, policy, in, r, row, log, counters, discards);
                            batch = Batch.open(naming, o, indx, log);
                        }
                        // recorded BEFORE the write, so a document that closes its own batch
                        // (ROLL_THEN_ALONE) still counts this input as one of that batch's producers
                        if (!contributors.contains(in)) contributors.add(in);

                        if (o.logDocuments) {
                            String id = tags.get(docIdTag);
                            log.accept("elarxml: " + batch.names.indexFileName + " <- id="
                                    + (id == null || id.isEmpty() ? "(none)" : id)
                                    + " file=" + store.fileName(content));
                        }
                        long actual = batch.writeDocument(indx, tags, store, content, contentTag, dsakTag, hashTag);
                        counters.wrote(tags.size(), actual);
                        policy.appended(estimate);
                        if (BatchPolicy.estimateDrifted(estimate, actual + PER_DOCUMENT_OVERHEAD)) {
                            log.accept("elarxml: the size estimate for " + store.fileName(content) + " was " + estimate
                                    + " and it wrote " + (actual + PER_DOCUMENT_OVERHEAD)
                                    + "; the byte budget is rolling at the wrong point");
                        }
                        if (dec.action == BatchPolicy.Action.ROLL_THEN_ALONE) {
                            closeBatch(batch, indx, pull, counters, written, log,
                                    contributors, awaitingRename, o.renameProcessed, discards);
                            batch = null;
                            policy.rolled();
                        }
                    }
                } finally {
                    r.close();
                }
                // This input has been read to the end. If none of its documents is in the open batch
                // - it produced none, or the last one closed its batch - there is nothing left to
                // wait for and it is renamed now. Otherwise it waits for that batch to be committed.
                if (contributors.contains(in)) awaitingRename.add(in);
                else finishInput(in, o.renameProcessed, log, counters, discards);
            }
            if (batch != null) {
                closeBatch(batch, indx, pull, counters, written, log,
                        contributors, awaitingRename, o.renameProcessed, discards);
                batch = null;
            }
        } finally {
            // an exception anywhere leaves the open batch unpublished rather than half-delivered,
            // and the discards of every input not yet finished are thrown away with it: a discards
            // file for an input that was never delivered would read as a complete account of what
            // was dropped, which is exactly what it would not be
            if (batch != null) batch.abort();
            for (SkippedRows s : discards.values()) s.abort();
            discards.clear();
            // The executor owns the store for the duration of the run and releases it here, on every
            // path including the failing ones. A store may hold a connection - an AS/400 one does - and
            // a run that throws must not leave it open; leaking one per failed run is the kind of
            // problem that only shows up after a week of retries.
            try { store.close(); } catch (IOException ignored) { }
        }

        if (validator != null) log.accept("elarxml: " + validator.message());

        // Invariant: an input only ever enters awaitingRename while a batch is open, and the close
        // above is unconditional, so on this path the list is empty. If it is not, something closed a
        // batch without going through closeBatch - say so rather than rename on a guess.
        if (!awaitingRename.isEmpty()) {
            log.accept("elarxml: " + awaitingRename.size() + " input file(s) finished the run still waiting"
                    + " for a batch to be committed and were NOT renamed to .done; they will be picked up"
                    + " again. This should not be reachable - report it.");
        }

        log.accept("elarxml: " + counters.summary());
        for (int i = 0; i < written.size(); i++) log.accept("elarxml: wrote " + written.get(i));
        return counters;
    }

    /** The headroom demanded before a new INDX is opened: two more of them, plus a tenth. */
    static long requiredFree(long maxBytesPerBatch) {
        return maxBytesPerBatch * 2 + maxBytesPerBatch / 10;
    }

    /**
     * Refuses to start an INDX the disk could not hold, and leaves the input in a state the next run can
     * simply pick up.
     *
     * Filling the disk mid-INDX is not a clean failure. The batch aborts, so nothing of it is delivered -
     * but the batches before it ARE delivered, and the input still carries its original name, so the next
     * run reprocesses it from the top and sends those documents a second time. Someone then splits the
     * CSV by hand. That is the operation this removes.
     *
     * So the run stops here, before the INDX exists, and the input is cut at exactly this row: what was
     * delivered goes to {@code .done_before_failure}, the rest to {@code .remaining.csv}, and the
     * original is kept as {@code .failed}. The discards file is published too, because the delivered part
     * really was delivered and its dropped rows are a real account of it - unlike an aborted run, where
     * it would be an account of nothing.
     */
    private static void checkDisk(Options o, BatchPolicy policy, File in, FlatCsvReader r,
                                  FlatCsvReader.Row row, Consumer<String> log, ElarCounters counters,
                                  Map<File, SkippedRows> discards) throws IOException {
        if (!o.checkFreeDisk || policy.by() != BatchPolicy.By.BYTES) return;

        long required = requiredFree(o.maxBytesPerBatch);
        long free = o.freeSpaceProbe != null ? o.freeSpaceProbe.applyAsLong(o.outputDir)
                                             : o.outputDir.getUsableSpace();
        // 0 means the filesystem would not say. Refusing on that would stop every run on a share that
        // does not report, which is worse than not checking.
        if (free <= 0 || free > required) return;

        log.accept("elarxml: " + free + " byte(s) free on " + o.outputDir.getAbsolutePath()
                + ", and a new INDX needs " + required + " free before it is safe to start"
                + " (twice maxBytesPerBatch plus a tenth). Stopping before the file exists.");

        // the delivered part is real, so its record of what it dropped is real too
        SkippedRows s = discards.remove(in);
        if (s != null) {
            File f = s.commit();
            if (f != null) {
                counters.skippedFilesWritten++;
                log.accept("elarxml: " + s.rows() + " skipped row(s) of the delivered part were written to "
                        + f.getName());
            }
        }
        try { r.close(); } catch (IOException ignored) { }   // the splitter re-reads the file

        InputSplitter.Result split = InputSplitter.split(in, o.inputCharset, row.lineNo);
        log.accept("elarxml: " + in.getName() + " was cut at line " + row.lineNo + " - "
                + split.rowsDone + " row(s) already delivered are in " + split.doneBefore.getName()
                + ", the remaining " + split.rowsRemaining + " are in " + split.remaining.getName()
                + " ready for the next run, and the original is kept as " + split.failed.getName());

        throw new IOException("the output disk is full: " + free + " byte(s) free where a new INDX needs "
                + required + ". Everything delivered so far is delivered and " + in.getName()
                + " has been split, so the next run continues from " + split.remaining.getName()
                + " instead of starting over. Free space before re-running.");
    }

    /**
     * Commits a batch and then renames every input that was only waiting for THAT batch.
     *
     * The rename is deliberately downstream of {@link Batch#close}: close renames the temp files to
     * their final deliverable names, so an input marked {@code .done} always corresponds to output
     * that has actually been delivered. If close throws, nothing is renamed and the whole set is
     * reprocessed - which is the correct outcome, because none of it was delivered.
     */
    private static void closeBatch(Batch batch, IndxTemplate indx, PullTemplate pull, ElarCounters counters,
                                   List<String> written, Consumer<String> log,
                                   List<File> contributors, List<File> awaitingRename,
                                   boolean renameProcessed, Map<File, SkippedRows> discards) throws Exception {
        batch.close(indx, pull, counters, written, log);
        for (int i = 0; i < contributors.size(); i++) {
            File c = contributors.get(i);
            if (awaitingRename.remove(c)) finishInput(c, renameProcessed, log, counters, discards);
        }
        contributors.clear();
    }

    /**
     * An input is finished: its discards are published and it is renamed to {@code .done}, in that
     * order and at the same moment. Both mean the same thing - every batch this input produced has
     * reached its final name - so neither may appear without the other.
     */
    private static void finishInput(File in, boolean renameProcessed, Consumer<String> log,
                                    ElarCounters counters, Map<File, SkippedRows> discards) throws IOException {
        SkippedRows s = discards.remove(in);
        if (s != null) {
            File f = s.commit();
            if (f != null) {
                counters.skippedFilesWritten++;
                log.accept("elarxml: " + s.rows() + " row(s) of " + in.getName() + " produced no document"
                        + " and were copied to " + f.getName() + ", header included; correct them and rename"
                        + " the file to end in .csv to feed them back in");
            }
        }
        renameDone(in, renameProcessed, log);
    }

    /**
     * Renames one processed input to {@code .done}. A failure is logged and non-fatal: the output is
     * already delivered, so failing the step here would be worse than the duplicate it warns about.
     */
    static void renameDone(File in, boolean renameProcessed, Consumer<String> log) {
        if (!renameProcessed) return;
        File done = new File(in.getParentFile(), in.getName() + ".done");
        if (!in.renameTo(done)) {
            log.accept("elarxml: could not rename " + in.getName() + " to .done; every batch it produced is"
                    + " delivered, but this file will be picked up again unless it is moved by hand");
        }
    }

    /** Input files, in a stable order, skipping the legacy intermediates and anything already done. */
    static List<File> listInputs(File dir, String skipPrefix) {
        List<File> out = new ArrayList<File>();
        if (dir == null || !dir.isDirectory()) return out;
        File[] all = dir.listFiles();
        if (all == null) return out;
        String pfx = skipPrefix == null ? "" : skipPrefix;
        for (int i = 0; i < all.length; i++) {
            File f = all[i];
            if (!f.isFile()) continue;
            String n = f.getName();
            if (n.endsWith(".done")) continue;
            if (!pfx.isEmpty() && n.startsWith(pfx)) continue;   // leftover legacy out_*.csv
            if (!n.toLowerCase().endsWith(".csv")) continue;
            out.add(f);
        }
        // a stable order matters: the synthetic clock assigns names in the order files are processed
        java.util.Collections.sort(out, new Comparator<File>() {
            public int compare(File a, File b) { return a.getName().compareTo(b.getName()); }
        });
        return out;
    }

    /** One INDX being written, with its PULL produced on close. */
    static final class Batch {
        final BatchNaming.Pair names;
        private final AtomicOutput indexOut;
        private final WrappingXmlOut xml;
        private final Options opts;
        private int docs = 0;
        private boolean prologueWritten = false;

        private Batch(BatchNaming.Pair n, AtomicOutput a, WrappingXmlOut x, Options o) {
            names = n; indexOut = a; xml = x; opts = o;
        }

        static Batch open(BatchNaming naming, Options o, IndxTemplate indx, Consumer<String> log) throws Exception {
            BatchNaming.Pair n = naming.next();
            AtomicOutput a = new AtomicOutput(new File(o.outputDir, n.indexFileName), o.overwriteExisting,
                    BatchNaming.partName(n.indexFileName, BatchNaming.INDX_TOKEN, BatchNaming.INDX_PART));
            int maxLine = o.maxLineLength > 0 ? o.maxLineLength : 20000;
            WrappingXmlOut x = new WrappingXmlOut(a.stream(), o.outputCharset, maxLine, o.formatOutput);
            return new Batch(n, a, x, o);
        }

        long writeDocument(IndxTemplate indx, Map<String, String> tags, ContentStore store, String content,
                           String contentTag, String dsakTag, String hashTag) throws Exception {
            if (!prologueWritten) { indx.writePrologue(xml); prologueWritten = true; }
            long[] bytes = new long[1];
            indx.writeDocument(xml, source(tags, store, content, contentTag, dsakTag, hashTag, bytes));
            docs++;
            return bytes[0];
        }

        private IndxTemplate.DocSource source(final Map<String, String> tags, final ContentStore store,
                                              final String content, final String contentTag,
                                              final String dsakTag, final String hashTag,
                                              final long[] bytesOut) throws Exception {
            final String hash = ContentEmbedder.sha256Hex(store, content);
            final ContentEmbedder.Stamp stamp = ContentEmbedder.stamp(store, content);
            final Map<String, String> values = new LinkedHashMap<String, String>(tags);
            values.put(dsakTag, extensionUpper(store.fileName(content)));
            return new IndxTemplate.DocSource() {
                public String value(String qname) {
                    if (hashTag.equals(qname)) return hash;
                    return values.containsKey(qname) ? values.get(qname) : null;
                }
                public boolean isContentTag(String qname) { return contentTag.equals(qname); }
                public void writeContent(WrappingXmlOut out, String qname) throws IOException {
                    bytesOut[0] = ContentEmbedder.encodeBase64(out, qname, store, content, stamp);
                }
            };
        }

        void close(IndxTemplate indx, PullTemplate pull, ElarCounters counters,
                   List<String> written, Consumer<String> log) throws Exception {
            if (prologueWritten) indx.writeEpilogue(xml);
            xml.close();
            indexOut.commit();
            written.add(names.indexFileName + " (" + docs + " document(s))");

            AtomicOutput pullOut = new AtomicOutput(new File(opts.outputDir, names.pullFileName),
                    opts.overwriteExisting,
                    BatchNaming.partName(names.pullFileName, BatchNaming.PULL_TOKEN, BatchNaming.PULL_PART));
            try {
                int maxLine = opts.maxLineLength > 0 ? opts.maxLineLength : 20000;
                WrappingXmlOut px = new WrappingXmlOut(pullOut.stream(), opts.outputCharset, maxLine, opts.formatOutput);
                pull.write(px, names.indexName);
                px.close();
                pullOut.commit();
            } finally {
                pullOut.abort();
            }
            written.add(names.pullFileName);
            counters.batchesWritten++;
            // the closing line of the per-document trace: what went in, and how many
            if (opts.logDocuments) {
                log.accept("elarxml: " + names.indexFileName + " delivered with " + docs
                        + " document(s), paired with " + names.pullFileName);
            }
        }

        void abort() {
            try { xml.close(); } catch (Exception ignored) { }
            indexOut.abort();
        }
    }

    static String extensionUpper(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 && dot < fileName.length() - 1
                ? fileName.substring(dot + 1).toUpperCase(java.util.Locale.ROOT) : "";
    }
}
