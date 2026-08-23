package com.legalarchive.orchestrator.elarcheck;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the checker found, and the verdict that follows from it.
 *
 * Nothing in this class holds a field value. These files carry customer names, tax codes and account
 * identifiers, so a finding carries the file, the line, the record ordinal, the element's local name
 * and a fixed description - never the text that was wrong. That rule is what makes the findings file
 * safe to keep beside the run log.
 */
public final class ElarCheckReport {

    /** The kinds, each of which names a distinct repair. */
    public enum Kind {
        NotWellFormed,          // 5.1
        InvalidSpaceAfterAngle, // 5.2
        TextLineBreak,          // 5.3, inside a value: the value is wrong
        MarkupLineBreak,        // 5.3, inside a tag: different repair, see the note below
        LineOverTarget,         // 5.4, over maxLineLength but under what the receiver truncates at
        LineOverReceiver,       // 5.4, over ~30000: this one loses its closing tag
        TagMissing,             // 5.5
        TagDuplicate,           // 5.5
        TagEmpty,               // 5.5
        NameAlreadyDelivered,   // 5.6
        PullMissing,            // 5.7
        PullUnreferenced,       // 5.7
        HashMismatch            // 5.8
    }

    /** Verdicts, in increasing severity. */
    public enum Verdict { OK, CORRUPTED, MALFORMED }

    public static final class Finding {
        public final String file;
        public final long line;
        public final long record;
        public final Kind kind;
        public final String element;
        public final String detail;
        Finding(String file, long line, long record, Kind kind, String element, String detail) {
            this.file = file; this.line = line; this.record = record;
            this.kind = kind; this.element = element == null ? "" : element;
            this.detail = detail == null ? "" : detail;
        }
        public String toString() {
            return file + " line " + line + (record > 0 ? (" record " + record) : "")
                    + " " + kind + (element.isEmpty() ? "" : (" <" + element + ">"))
                    + (detail.isEmpty() ? "" : (" - " + detail));
        }
    }

    /** One inspected file. */
    public static final class FileReport {
        public final String name;
        public final List<Finding> findings = new ArrayList<Finding>();
        public final Map<Kind, Long> counts = new LinkedHashMap<Kind, Long>();
        public long documents = 0;
        public long lines = 0;
        public long longestLine = 0;
        public boolean wellFormed = true;
        public long suppressed = 0;          // findings past the cap: counted, not listed

        FileReport(String name) { this.name = name; }

        public long count(Kind k) {
            Long v = counts.get(k);
            return v == null ? 0 : v.longValue();
        }

        /**
         * The decision the operator needs: send, or regenerate.
         *
         * CORRUPTED is the one that matters. Such a file is accepted by ELAR and archived with a wrong
         * value inside it, and nothing downstream will ever flag it. MALFORMED is expensive but
         * self-announcing: the receiver rejects it and says so.
         *
         * A file that is both is reported MALFORMED, with the corruption findings still listed: it has
         * to be regenerated either way, and the operator needs the whole list in one report.
         */
        public Verdict verdict() {
            if (!wellFormed
                    || count(Kind.InvalidSpaceAfterAngle) > 0
                    || count(Kind.LineOverReceiver) > 0
                    || count(Kind.PullMissing) > 0
                    || count(Kind.PullUnreferenced) > 0) return Verdict.MALFORMED;
            if (count(Kind.TextLineBreak) > 0
                    || count(Kind.MarkupLineBreak) > 0
                    || count(Kind.TagMissing) > 0
                    || count(Kind.TagDuplicate) > 0
                    || count(Kind.TagEmpty) > 0
                    || count(Kind.HashMismatch) > 0
                    || count(Kind.NameAlreadyDelivered) > 0) return Verdict.CORRUPTED;
            return Verdict.OK;
        }
    }

    public final List<FileReport> files = new ArrayList<FileReport>();
    private final int maxPerFile;

    public ElarCheckReport(int maxFindingsPerFile) {
        this.maxPerFile = maxFindingsPerFile > 0 ? maxFindingsPerFile : Integer.MAX_VALUE;
    }

    public FileReport startFile(String name) {
        FileReport f = new FileReport(name);
        files.add(f);
        return f;
    }

    /**
     * Record a finding. The list is capped; the COUNT never is.
     * A capped list with an exact count tells the operator both what to fix and how big the problem
     * is. A capped count would quietly understate it, which is worse than not counting at all.
     */
    public void add(FileReport f, long line, long record, Kind kind, String element, String detail) {
        Long prev = f.counts.get(kind);
        f.counts.put(kind, Long.valueOf((prev == null ? 0 : prev.longValue()) + 1));
        if (f.findings.size() < maxPerFile) {
            f.findings.add(new Finding(f.name, line, record, kind, element, detail));
        } else {
            f.suppressed++;
        }
    }

    // ------------------------------------------------------------------ totals

    public long total(Kind k) {
        long n = 0;
        for (int i = 0; i < files.size(); i++) n += files.get(i).count(k);
        return n;
    }
    public long filesWith(Verdict v) {
        long n = 0;
        for (int i = 0; i < files.size(); i++) if (files.get(i).verdict() == v) n++;
        return n;
    }

    /** Counters for {@code run.vars}, so a following step can act on them. */
    public Map<String, String> asVars() {
        long docs = 0, longest = 0, wellFormed = 0;
        for (int i = 0; i < files.size(); i++) {
            FileReport f = files.get(i);
            docs += f.documents;
            if (f.longestLine > longest) longest = f.longestLine;
            if (f.wellFormed) wellFormed++;
        }
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("filesScanned", String.valueOf(files.size()));
        m.put("filesWellFormed", String.valueOf(wellFormed));
        m.put("filesRejectedLikely", String.valueOf(filesWith(Verdict.MALFORMED)));
        m.put("filesCorrupted", String.valueOf(filesWith(Verdict.CORRUPTED)));
        m.put("documentsTotal", String.valueOf(docs));
        m.put("whitespaceAfterAngle", String.valueOf(total(Kind.InvalidSpaceAfterAngle)));
        m.put("valueLineBreaks", String.valueOf(total(Kind.TextLineBreak)));
        m.put("markupLineBreaks", String.valueOf(total(Kind.MarkupLineBreak)));
        m.put("linesOverLimit", String.valueOf(total(Kind.LineOverTarget) + total(Kind.LineOverReceiver)));
        m.put("linesOverReceiverLimit", String.valueOf(total(Kind.LineOverReceiver)));
        m.put("longestLine", String.valueOf(longest));
        m.put("tagsMissing", String.valueOf(total(Kind.TagMissing)));
        m.put("tagsDuplicate", String.valueOf(total(Kind.TagDuplicate)));
        m.put("tagsEmpty", String.valueOf(total(Kind.TagEmpty)));
        m.put("nameAlreadyDelivered", String.valueOf(total(Kind.NameAlreadyDelivered)));
        m.put("pullMissing", String.valueOf(total(Kind.PullMissing)));
        m.put("pullUnreferenced", String.valueOf(total(Kind.PullUnreferenced)));
        m.put("hashMismatches", String.valueOf(total(Kind.HashMismatch)));
        m.put("findingsTotal", String.valueOf(totalFindings()));
        return m;
    }

    public long totalFindings() {
        long n = 0;
        for (int i = 0; i < files.size(); i++) {
            FileReport f = files.get(i);
            for (Long v : f.counts.values()) n += v.longValue();
        }
        return n;
    }

    /** The findings file: one record per line, tab separated, no field values. */
    public String toTsv() {
        StringBuilder sb = new StringBuilder();
        sb.append("file\tverdict\tline\trecord\tkind\telement\tdetail").append(nl());
        for (int i = 0; i < files.size(); i++) {
            FileReport f = files.get(i);
            String v = f.verdict().name();
            if (f.findings.isEmpty()) {
                sb.append(f.name).append('\t').append(v).append("\t\t\t\t\t").append(nl());
                continue;
            }
            for (int j = 0; j < f.findings.size(); j++) {
                Finding x = f.findings.get(j);
                sb.append(x.file).append('\t').append(v).append('\t').append(x.line).append('\t')
                  .append(x.record).append('\t').append(x.kind).append('\t')
                  .append(x.element).append('\t').append(x.detail).append(nl());
            }
            if (f.suppressed > 0) {
                sb.append(f.name).append('\t').append(v).append("\t\t\t(suppressed)\t\t")
                  .append(f.suppressed).append(" further finding(s) not listed").append(nl());
            }
        }
        return sb.toString();
    }

    static String nl() { return String.valueOf((char) 10); }
}
