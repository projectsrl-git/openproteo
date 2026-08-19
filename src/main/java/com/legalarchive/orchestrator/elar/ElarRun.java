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
        public String outputCharset = "ISO-8859-1";
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
        public boolean validate = false;
        public boolean renameProcessed = true;
        public boolean overwriteExisting = false;
        public String descriptorsElement = "DocumentDescriptors";
        public LocalDateTime now = LocalDateTime.now();
    }

    private ElarRun() { }

    /** Overhead per document beyond the Base64 itself: tags, attributes and line separators. */
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
        String docIdTag = cfg.docIdTag(mapping);
        File docDir = new File(cfg.documentPath());

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
                o.separator, o.quoteChar);
        log.accept("elarxml: " + scan.message());
        if (!scan.clean() && o.onMalformedRowFail) {
            throw new IOException(scan.message());
        }
        counters.rowsMalformed = scan.malformedRowCount;

        ElarValidator validator = o.validate ? new ElarValidator(docIdTag, cfg.notDuplicatedTags(o.listSeparator)) : null;

        Batch batch = null;
        List<String> written = new ArrayList<String>();
        try {
            for (int fi = 0; fi < inputs.size(); fi++) {
                File in = inputs.get(fi);
                counters.filesProcessed++;
                FlatCsvReader r = new FlatCsvReader(in, o.inputCharset, o.failOnMalformedInput, o.separator, o.quoteChar);
                try {
                    int expect = r.headerSize();
                    FlatCsvReader.Row row;
                    while ((row = r.next()) != null) {
                        if (row.fields.length != expect) { counters.rowsMalformed++; continue; }   // SKIP mode only
                        Map<String, String> tags = r.asTagMap(row, mapping);
                        if (validator != null) validator.check(row.lineNo, tags);

                        String raw = tags.get(contentTag);
                        if (raw == null || raw.trim().isEmpty()) {
                            counters.skipped(ElarCounters.Skip.NO_PATH);
                            continue;
                        }
                        File content = ElarPreScan.resolveContentFile(docDir, raw.trim());
                        if (!content.isFile()) {
                            counters.skipped(ElarCounters.Skip.FILE_MISSING);
                            continue;
                        }

                        long estimate = ContentEmbedder.encodedLength(content.length()) + PER_DOCUMENT_OVERHEAD;
                        BatchPolicy.Decision dec = policy.decide(estimate);
                        if (dec.oversizeDocument) counters.documentsOversize++;
                        if (dec.action != BatchPolicy.Action.APPEND && batch != null) {
                            batch.close(indx, pull, counters, written, log);
                            batch = null;
                            policy.rolled();
                        }
                        if (batch == null) batch = Batch.open(naming, o, indx, log);

                        long actual = batch.writeDocument(indx, tags, content, contentTag, dsakTag);
                        counters.wrote(tags.size(), actual);
                        policy.appended(estimate);
                        if (BatchPolicy.estimateDrifted(estimate, actual + PER_DOCUMENT_OVERHEAD)) {
                            log.accept("elarxml: the size estimate for " + content.getName() + " was " + estimate
                                    + " and it wrote " + (actual + PER_DOCUMENT_OVERHEAD)
                                    + "; the byte budget is rolling at the wrong point");
                        }
                        if (dec.action == BatchPolicy.Action.ROLL_THEN_ALONE) {
                            batch.close(indx, pull, counters, written, log);
                            batch = null;
                            policy.rolled();
                        }
                    }
                } finally {
                    r.close();
                }
            }
            if (batch != null) { batch.close(indx, pull, counters, written, log); batch = null; }
        } finally {
            // an exception anywhere leaves the open batch unpublished rather than half-delivered
            if (batch != null) batch.abort();
        }

        if (validator != null) log.accept("elarxml: " + validator.message());

        // the .done rename happens only once every batch has reached its final name
        if (o.renameProcessed) {
            for (int i = 0; i < inputs.size(); i++) {
                File in = inputs.get(i);
                File done = new File(in.getParentFile(), in.getName() + ".done");
                if (!in.renameTo(done)) {
                    log.accept("elarxml: could not rename " + in.getName() + " to .done; the output is complete"
                            + " and delivered, but this file will be picked up again unless it is moved by hand");
                }
            }
        }

        log.accept("elarxml: " + counters.summary());
        for (int i = 0; i < written.size(); i++) log.accept("elarxml: wrote " + written.get(i));
        return counters;
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
        private final BatchNaming.Pair names;
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
            AtomicOutput a = new AtomicOutput(new File(o.outputDir, n.indexFileName), o.overwriteExisting);
            int maxLine = o.maxLineLength > 0 ? o.maxLineLength : 20000;
            WrappingXmlOut x = new WrappingXmlOut(a.stream(), o.outputCharset, maxLine);
            return new Batch(n, a, x, o);
        }

        long writeDocument(IndxTemplate indx, Map<String, String> tags, File content,
                           String contentTag, String dsakTag) throws Exception {
            if (!prologueWritten) { indx.writePrologue(xml); prologueWritten = true; }
            long[] bytes = new long[1];
            indx.writeDocument(xml, source(tags, content, contentTag, dsakTag, bytes));
            docs++;
            return bytes[0];
        }

        private IndxTemplate.DocSource source(final Map<String, String> tags, final File content,
                                              final String contentTag, final String dsakTag,
                                              final long[] bytesOut) throws Exception {
            final String hash = ContentEmbedder.sha256Hex(content);
            final ContentEmbedder.Stamp stamp = ContentEmbedder.stamp(content);
            final Map<String, String> values = new LinkedHashMap<String, String>(tags);
            values.put(dsakTag, extensionUpper(content.getName()));
            final String hashTag = hashTagOf(values);
            return new IndxTemplate.DocSource() {
                public String value(String qname) {
                    if (hashTag != null && hashTag.equals(qname)) return hash;
                    return values.containsKey(qname) ? values.get(qname) : null;
                }
                public boolean isContentTag(String qname) { return contentTag.equals(qname); }
                public void writeContent(WrappingXmlOut out, String qname) throws IOException {
                    bytesOut[0] = ContentEmbedder.encodeBase64(out, qname, content, stamp);
                }
            };
        }

        /** The hash tag is whatever the template calls it; only its position is fixed by the template. */
        private String hashTagOf(Map<String, String> values) { return HASH_TAG; }

        void close(IndxTemplate indx, PullTemplate pull, ElarCounters counters,
                   List<String> written, Consumer<String> log) throws Exception {
            if (prologueWritten) indx.writeEpilogue(xml);
            xml.close();
            indexOut.commit();
            written.add(names.indexFileName + " (" + docs + " document(s))");

            AtomicOutput pullOut = new AtomicOutput(new File(opts.outputDir, names.pullFileName), opts.overwriteExisting);
            try {
                int maxLine = opts.maxLineLength > 0 ? opts.maxLineLength : 20000;
                WrappingXmlOut px = new WrappingXmlOut(pullOut.stream(), opts.outputCharset, maxLine);
                pull.write(px, names.indexName);
                px.close();
                pullOut.commit();
            } finally {
                pullOut.abort();
            }
            written.add(names.pullFileName);
            counters.batchesWritten++;
        }

        void abort() {
            try { xml.close(); } catch (Exception ignored) { }
            indexOut.abort();
        }
    }

    /** The tag carrying the digest. Configurable for the same reason the content tag is. */
    static String HASH_TAG = "ELAR:HashValue";

    static String extensionUpper(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 && dot < fileName.length() - 1
                ? fileName.substring(dot + 1).toUpperCase(java.util.Locale.ROOT) : "";
    }
}
