package com.legalarchive.orchestrator.elar;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The pre-scan: every input file is checked BEFORE a single byte of output is written.
 *
 * Two things are checked, and both stop the run under the default policy: a row whose field count
 * differs from the header's, and a referenced content file that does not exist.
 *
 * Running these mid-file would leave the mess nothing can tidy. By the time such a row is reached,
 * some batches have already been closed and renamed to their final deliverable names, so the output
 * directory holds a partial set with no marker saying so, while the failing input has no {@code .done}
 * and its neighbours do - and a re-run would then re-deliver what already went out. Scanning first
 * makes the refusal atomic at the STEP level: nothing written, nothing renamed, and a re-run after
 * the source is corrected processes the complete set.
 *
 * It also reports every offending line across every file in one message, so the correction is made
 * once instead of being discovered one failure at a time. The cost is a second read of the CSVs and
 * one {@code exists()} per document - nothing beside the hundreds of megabytes of Base64 the run is
 * about to write, and it touches no content file.
 */
public final class ElarPreScan {

    /** How many offending lines are listed per category before the report stops enumerating. */
    static final int MAX_LISTED = 50;

    public static final class Problem {
        public final String file;
        public final long lineNo;
        public final String detail;
        Problem(String file, long lineNo, String detail) { this.file = file; this.lineNo = lineNo; this.detail = detail; }
        public String toString() { return file + " line " + lineNo + ": " + detail; }
    }

    public static final class Report {
        public final List<Problem> malformedRows = new ArrayList<Problem>();
        public final List<Problem> missingFiles = new ArrayList<Problem>();
        public long malformedRowCount = 0;
        public long missingFileCount = 0;
        public long rowsScanned = 0;
        public long filesScanned = 0;

        public boolean clean() { return malformedRowCount == 0 && missingFileCount == 0; }

        /** One message naming every problem, capped so a wholly broken file cannot produce a wall of text. */
        public String message() {
            StringBuilder sb = new StringBuilder();
            sb.append("pre-scan of ").append(filesScanned).append(" file(s), ")
              .append(rowsScanned).append(" row(s): ");
            if (clean()) { sb.append("no field-count mismatch, every referenced content file exists."); return sb.toString(); }
            sb.append(malformedRowCount).append(" row(s) with a field count differing from the header, ")
              .append(missingFileCount).append(" referenced content file(s) missing.")
              .append(" Nothing was written and no input was renamed.");
            append(sb, "field count", malformedRows, malformedRowCount);
            append(sb, "missing content file", missingFiles, missingFileCount);
            return sb.toString();
        }
        private void append(StringBuilder sb, String what, List<Problem> list, long total) {
            if (list.isEmpty()) return;
            sb.append(String.valueOf((char) 10)).append("  ").append(what).append(':');
            for (int i = 0; i < list.size(); i++) {
                sb.append(String.valueOf((char) 10)).append("    ").append(list.get(i));
            }
            if (total > list.size()) {
                sb.append(String.valueOf((char) 10)).append("    ... and ").append(total - list.size()).append(" more");
            }
        }
    }

    private ElarPreScan() { }

    /**
     * @param inputs        the CSV files that would be processed, in the order they would be
     * @param cfg           the family configuration, for the mapping and the document directory
     * @param charsetName   same charset as the reading pass
     * @param failOnMalformed same decoder policy as the reading pass
     * @param separator     same separator
     * @param quoteChar     same quote character (0 = disabled)
     *
     * The scan MUST agree with the pass that follows it: it uses the same reader, the same charset and
     * the same parse, so a file that passes the scan cannot fail the read. A pre-scan that could
     * disagree with the reader after it would be worse than no pre-scan at all.
     */
    public static Report scan(List<File> inputs, ElarConfig cfg, String charsetName, boolean failOnMalformed,
                              char separator, char quoteChar, ContentStore store) throws Exception {
        Report rep = new Report();
        Map<String, String> mapping = cfg.tagNameMapping();
        String contentTag = cfg.contentTag();

        // the column whose value is the content path: the one mapped to the content tag
        String contentColumn = null;
        for (Map.Entry<String, String> e : mapping.entrySet()) {
            if (contentTag.equals(e.getValue())) { contentColumn = e.getKey(); break; }
        }

        for (int fi = 0; fi < inputs.size(); fi++) {
            File f = inputs.get(fi);
            rep.filesScanned++;
            FlatCsvReader r = new FlatCsvReader(f, charsetName, failOnMalformed, separator, quoteChar);
            try {
                int expect = r.headerSize();
                int contentIdx = -1;
                if (contentColumn != null) {
                    String[] h = r.header();
                    for (int i = 0; i < h.length; i++) if (contentColumn.equals(h[i])) { contentIdx = i; break; }
                }
                FlatCsvReader.Row row;
                while ((row = r.next()) != null) {
                    rep.rowsScanned++;
                    if (row.fields.length != expect) {
                        rep.malformedRowCount++;
                        if (rep.malformedRows.size() < MAX_LISTED) {
                            // the COUNTS only - never the row's content, which carries customer data
                            rep.malformedRows.add(new Problem(f.getName(), row.lineNo,
                                    row.fields.length + " field(s), header declares " + expect));
                        }
                        continue;   // its content path cannot be trusted either, so do not check it
                    }
                    if (contentIdx < 0) continue;
                    String raw = contentIdx < row.fields.length ? row.fields[contentIdx] : "";
                    if (raw == null || raw.trim().isEmpty()) continue;   // counted as a skip at write time, not here
                    String target = store.resolve(raw.trim());
                    if (!store.exists(target)) {
                        rep.missingFileCount++;
                        if (rep.missingFiles.size() < MAX_LISTED) {
                            // the file NAME is a document identifier, not a customer identifier, and
                            // without it the operator cannot act; the directory is already known
                            rep.missingFiles.add(new Problem(f.getName(), row.lineNo, store.fileName(target)));
                        }
                    }
                }
            } finally {
                r.close();
            }
        }
        return rep;
    }

}
