package com.legalarchive.orchestrator.elar;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The checks the legacy {@code Validator} was supposed to perform and never did.
 *
 * <p><b>Why it never ran.</b> {@code CsvValidator.validate} began by requiring four columns and the
 * vertical intermediate it was handed has three, so it returned immediately, silently, before any
 * check executed. Every delivered INDX went out unvalidated.
 *
 * <p><b>Why one of the three checks could not have passed anyway.</b> The reference check compared the
 * document id against {@code map.get(doc_id_reference)}, but {@code doc_id_reference} names a CSV
 * COLUMN while the map is keyed by ELAR TAG. The lookup returned null for every document, so the
 * check would have thrown on the first row of any file. That asymmetry is real in the properties
 * file - {@code not_duplicated_tags_list} is already written in tag names and needs no translation,
 * {@code doc_id_reference} does - and is handled by {@link ElarConfig#docIdTag} rather than assumed
 * away.
 *
 * <p><b>Why that check is not reproduced here.</b> Once the translation is applied to a FLAT row, the
 * check asks whether the value of the doc-id column equals the value carried under the tag that
 * column maps to. In the flat model those are the same value by construction, so the check can never
 * fail. A green check that cannot go red is worse than no check: it reports confidence it does not
 * have. It is replaced by the invariant that survives the translation - the document id must be
 * present and non-empty - which can and does fail on real data.
 *
 * <p><b>Memory.</b> Two sets of small keys: the document ids, and one {@code value} set per tag named
 * in {@code not_duplicated_tags_list}. Bounded by the document count and by nothing about content
 * size. At a few thousand documents this is nothing; at tens of millions it is not, and that is the
 * point at which this needs revisiting rather than the point at which it silently exhausts the heap.
 */
public final class ElarValidator {

    /** How many offending line numbers are kept per finding before the report stops enumerating. */
    static final int MAX_LISTED = 20;

    public static final class Finding {
        public final String check;
        public final String detail;
        public final List<Long> lines = new ArrayList<Long>();
        public long count = 0;
        Finding(String check, String detail) { this.check = check; this.detail = detail; }
        void hit(long line) {
            count++;
            if (lines.size() < MAX_LISTED) lines.add(Long.valueOf(line));
        }
        public String toString() {
            StringBuilder sb = new StringBuilder(check).append(": ").append(detail)
                    .append(" \u2014 ").append(count).append(" row(s)");
            if (!lines.isEmpty()) {
                sb.append(" at line");
                for (int i = 0; i < lines.size(); i++) sb.append(i == 0 ? " " : ", ").append(lines.get(i));
                if (count > lines.size()) sb.append(" and ").append(count - lines.size()).append(" more");
            }
            return sb.toString();
        }
    }

    private final String docIdTag;
    private final List<String> uniqueTags;

    private final Set<String> seenDocIds = new HashSet<String>();
    private final Map<String, Set<String>> seenByTag = new LinkedHashMap<String, Set<String>>();

    private final Map<String, Finding> findings = new LinkedHashMap<String, Finding>();
    private long rowsChecked = 0;

    /**
     * @param docIdTag    the ELAR tag the document id maps to, from {@link ElarConfig#docIdTag}
     * @param uniqueTags  ELAR tag names whose values must not repeat, already in tag-name form
     */
    public ElarValidator(String docIdTag, List<String> uniqueTags) {
        this.docIdTag = docIdTag;
        this.uniqueTags = uniqueTags == null ? new ArrayList<String>() : new ArrayList<String>(new LinkedHashSet<String>(uniqueTags));
        for (int i = 0; i < this.uniqueTags.size(); i++) {
            seenByTag.put(this.uniqueTags.get(i), new HashSet<String>());
        }
    }

    /** Checks one row's tag map. Never throws: a finding is data, not an error. */
    public void check(long lineNo, Map<String, String> tagValues) {
        rowsChecked++;
        String id = tagValues == null ? null : tagValues.get(docIdTag);
        String trimmed = id == null ? "" : id.trim();

        if (trimmed.isEmpty()) {
            // replaces the reference check, which cannot fail on a flat row
            finding("docIdPresent", "the document id (" + docIdTag + ") is empty").hit(lineNo);
        } else if (!seenDocIds.add(trimmed)) {
            // the id IS a document identifier and is what an operator needs to find the row; it is
            // the same class of value as the missing-file name the pre-scan reports
            finding("docIdUnique", "duplicate document id '" + trimmed + "'").hit(lineNo);
        }

        for (int i = 0; i < uniqueTags.size(); i++) {
            String tag = uniqueTags.get(i);
            String v = tagValues == null ? null : tagValues.get(tag);
            if (v == null || v.trim().isEmpty()) continue;      // absent is not duplicated
            if (!seenByTag.get(tag).add(v.trim())) {
                // the VALUE is deliberately not reported here: unlike a document id, an arbitrary
                // tag can carry anything, and the tag name plus the line numbers is enough to act on
                finding("tagUnique:" + tag, "a value of <" + tag + "> repeats").hit(lineNo);
            }
        }
    }

    private Finding finding(String check, String detail) {
        Finding f = findings.get(check);
        if (f == null) { f = new Finding(check, detail); findings.put(check, f); }
        return f;
    }

    public long rowsChecked() { return rowsChecked; }
    public boolean clean() { return findings.isEmpty(); }
    public List<Finding> findings() { return new ArrayList<Finding>(findings.values()); }

    public long totalFindings() {
        long n = 0;
        for (Finding f : findings.values()) n += f.count;
        return n;
    }

    /** One message for the step log, whether or not anything was found. */
    public String message() {
        StringBuilder sb = new StringBuilder("validate: ").append(rowsChecked).append(" row(s) checked");
        if (uniqueTags.isEmpty()) sb.append(", no not_duplicated_tags_list configured");
        if (clean()) { sb.append(" \u2014 no findings."); return sb.toString(); }
        sb.append(" \u2014 ").append(totalFindings()).append(" finding(s):");
        for (Finding f : findings.values()) {
            sb.append(String.valueOf((char) 10)).append("    ").append(f.toString());
        }
        return sb.toString();
    }
}
