package com.legalarchive.orchestrator.elarcheck;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Inspects delivered ELAR INDX files. READ-ONLY BY CONSTRUCTION.
 *
 * No write API appears anywhere in this package - no FileOutputStream, no Files.write or delete, no
 * renameTo, no FileWriter - and a build scan asserts their absence. That is what makes it safe to run
 * against a live delivery folder at any moment, including while something else is writing into it.
 * The findings file is produced as a String and written by the caller into the STEP directory, never
 * into the inspected one.
 *
 * <p>Two physical passes per file, deliberately. The textual checks need physical lines and must keep
 * going after the document stops being well-formed - which is the whole point of reporting every
 * malformed tag opener when the parser stops at the first - while the structural checks need the
 * parser. Sharing one Reader fails on both counts: StAX consumes and closes it, and a fatal error
 * would end the scan for both. The cost is I/O rather than memory.
 */
public final class ElarCheckRun {

    public static final class Options {
        public File inputDir;
        public String filePattern = "*INDX*";
        public String inputCharset = "windows-1252";
        public int maxLineLength = 25000;
        public int receiverLineLimit = 30000;
        public String contentElement = "Content";
        public String hashElement = "HashValue";
        public String docElement = "Doc";
        public List<String> mandatoryTags = new ArrayList<String>();
        public boolean checkPull = true;
        public File deliveredDir;              // null disables the name-reuse check
        public boolean verifyHash = false;
        public int maxFindingsPerFile = 100;
    }

    private ElarCheckRun() { }

    public static ElarCheckReport run(Options o, Consumer<String> log) throws Exception {
        ElarCheckReport rep = new ElarCheckReport(o.maxFindingsPerFile);
        List<File> files = list(o.inputDir, o.filePattern);
        if (files.isEmpty()) {
            log.accept("elarcheck: no file matching '" + o.filePattern + "' in "
                    + (o.inputDir == null ? "(null)" : o.inputDir.getAbsolutePath()));
            return rep;
        }
        for (int i = 0; i < files.size(); i++) {
            File f = files.get(i);
            ElarCheckReport.FileReport fr = rep.startFile(f.getName());
            log.accept("elarcheck: " + f.getName() + " (" + f.length() + " bytes)");
            textualPass(f, o, rep, fr, log);
            structuralPass(f, o, rep, fr, log);
            if (o.checkPull) pairCheck(f, o, rep, fr);
            if (o.deliveredDir != null) nameReuse(f, o, rep, fr);
            log.accept("elarcheck: " + f.getName() + " -> " + fr.verdict()
                    + " (" + fr.documents + " document(s), longest line " + fr.longestLine + ")");
        }
        return rep;
    }

    // ------------------------------------------------------------------ pass 1: textual

    /**
     * Physical lines: malformed tag openers and over-long lines.
     *
     * Line numbering follows the reference script exactly, because the reported numbers have to match
     * both it and what ELAR reported. Lines are 1-based and the declaration line counts like any
     * other, but WHICH line a finding names differs by kind: a line break is reported at the line
     * where it STARTS, which is the one to repair, while a malformed opener is reported at the line it
     * is on. Reporting both at the current line would put every break one line late.
     */
    static void textualPass(File f, Options o, ElarCheckReport rep, ElarCheckReport.FileReport fr,
                            Consumer<String> log) throws IOException {
        BufferedReader r = reader(f, o.inputCharset);
        long lineNo = 0, record = 0, nextProgress = System.currentTimeMillis() + 5000;
        long bytesApprox = 0;
        String prev = null;
        boolean prevInTag = false, prevInPayload = false;
        try {
            String line;
            while ((line = r.readLine()) != null) {
                lineNo++;
                bytesApprox += line.length() + 1;
                fr.lines = lineNo;
                if (line.length() > fr.longestLine) fr.longestLine = line.length();

                // a break is only visible as the JOIN between two lines, so it is decided here, on the
                // previous line, and reported at the previous line's number
                if (prev != null && !prevInPayload) {
                    rep.add(fr, lineNo - 1, record,
                            prevInTag ? ElarCheckReport.Kind.MarkupLineBreak
                                      : ElarCheckReport.Kind.TextLineBreak,
                            "", prevInTag ? "line ends inside a tag" : "line ends inside a value");
                }

                record += countStarts(line, o.docElement);

                int bad = badOpener(line);
                if (bad >= 0) {
                    rep.add(fr, lineNo, record, ElarCheckReport.Kind.InvalidSpaceAfterAngle,
                            "", "whitespace after a tag opener at column " + (bad + 1));
                }

                if (line.length() > o.receiverLineLimit) {
                    rep.add(fr, lineNo, record, ElarCheckReport.Kind.LineOverReceiver, "",
                            line.length() + " characters; the receiver truncates beyond "
                            + o.receiverLineLimit + ", so this line loses its closing tag");
                } else if (line.length() > o.maxLineLength) {
                    rep.add(fr, lineNo, record, ElarCheckReport.Kind.LineOverTarget, "",
                            line.length() + " characters, over the target of " + o.maxLineLength
                            + " but still under what the receiver accepts");
                }

                State st = endState(line, prevInTag, prevInPayload, o.contentElement);
                // a line that ends mid-element is only a finding if the element carries a value; the
                // payload is wrapped on purpose and its breaks are whitespace to any decoder
                prevInTag = st.inTag;
                prevInPayload = st.inPayload;
                prev = st.endsInsideSomething ? line : null;

                if (System.currentTimeMillis() > nextProgress) {
                    nextProgress = System.currentTimeMillis() + 5000;
                    long pct = f.length() > 0 ? (bytesApprox * 100 / f.length()) : 0;
                    log.accept("elarcheck: " + f.getName() + " textual pass ~" + pct + "%, line " + lineNo);
                }
            }
        } finally {
            r.close();
        }
    }

    /** Where a line leaves the scanner: inside a tag, inside the payload, inside a value. */
    static final class State {
        boolean inTag, inPayload, endsInsideSomething;
    }

    /**
     * Walks a line to decide what it ends inside. Cheap and single-pass: this runs on every line of a
     * half-gigabyte file.
     */
    static State endState(String line, boolean inTag, boolean inPayload, String contentLocal) {
        State s = new State();
        boolean tag = inTag, payload = inPayload;
        boolean afterClosingTag = false;
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (tag) {
                if (c == '>') {
                    tag = false;
                    afterClosingTag = true;
                }
                i++;
                continue;
            }
            if (c == '<') {
                // read the element name to track the payload element by LOCAL name, prefix ignored
                int j = i + 1;
                boolean closing = j < line.length() && line.charAt(j) == '/';
                if (closing) j++;
                int start = j;
                while (j < line.length() && !isNameEnd(line.charAt(j))) j++;
                String qname = line.substring(Math.min(start, line.length()), Math.min(j, line.length()));
                String local = local(qname);
                if (local.equals(contentLocal)) payload = !closing;
                boolean selfClosed = false;
                int k = j;
                while (k < line.length() && line.charAt(k) != '>') {
                    if (line.charAt(k) == '/' && k + 1 < line.length() && line.charAt(k + 1) == '>') selfClosed = true;
                    k++;
                }
                if (k >= line.length()) { tag = true; i = k; }
                else {
                    if (selfClosed && local.equals(contentLocal)) payload = false;
                    i = k + 1;
                    afterClosingTag = true;
                }
                continue;
            }
            afterClosingTag = false;
            i++;
        }
        s.inTag = tag;
        s.inPayload = payload;
        // ends inside a tag, or inside character content (not straight after a '>')
        s.endsInsideSomething = tag || (!afterClosingTag && line.length() > 0);
        return s;
    }

    static boolean isNameEnd(char c) { return c == ' ' || c == '>' || c == '/' || c == '\t'; }
    static String local(String qname) {
        int i = qname.indexOf(':');
        return i >= 0 ? qname.substring(i + 1) : qname;
    }

    /** Column of a malformed opener - {@code < Name}, {@code < /Name}, {@code </ Name} - or -1. */
    static int badOpener(String line) {
        for (int i = 0; i + 1 < line.length(); i++) {
            if (line.charAt(i) != '<') continue;
            char n = line.charAt(i + 1);
            if (n == ' ' || n == '\t') return i;
            if (n == '/' && i + 2 < line.length()) {
                char m = line.charAt(i + 2);
                if (m == ' ' || m == '\t') return i;
            }
        }
        return -1;
    }

    static int countStarts(String line, String docLocal) {
        int n = 0, i = 0;
        while ((i = line.indexOf('<', i)) >= 0) {
            int j = i + 1;
            if (j < line.length() && line.charAt(j) == '/') { i = j; continue; }
            int start = j;
            while (j < line.length() && !isNameEnd(line.charAt(j))) j++;
            if (local(line.substring(start, Math.min(j, line.length()))).equals(docLocal)) n++;
            i = j;
        }
        return n;
    }

    // ------------------------------------------------------------------ pass 2: structural

    /**
     * StAX: well-formedness, and per-record element occurrence.
     *
     * COALESCING IS OFF, and it matters. With it on, each element's text would arrive as one String -
     * convenient, and fatal here: a payload of tens of megabytes would be materialised whole, which is
     * the exact accumulation that killed the legacy generator and what a 256 MB heap forbids. So an
     * element's text may arrive as several fragments, and nothing here ever assembles it.
     */
    static void structuralPass(File f, Options o, ElarCheckReport rep, ElarCheckReport.FileReport fr,
                               Consumer<String> log) throws IOException {
        XMLInputFactory xf = XMLInputFactory.newInstance();
        xf.setProperty(XMLInputFactory.IS_COALESCING, Boolean.FALSE);
        xf.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        xf.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);

        Set<String> mandatory = new LinkedHashSet<String>(o.mandatoryTags);
        BufferedReader r = reader(f, o.inputCharset);
        XMLStreamReader x = null;
        long record = 0;
        try {
            x = xf.createXMLStreamReader(r);
            Map<String, Integer> seen = new LinkedHashMap<String, Integer>();
            Map<String, Boolean> nonEmpty = new LinkedHashMap<String, Boolean>();
            String current = null;
            boolean inPayload = false, inDoc = false;
            long docLine = 0;
            MessageDigest md = null;
            Base64Streamer b64 = null;
            String declaredHash = null;

            while (x.hasNext()) {
                int e = x.next();
                if (e == XMLStreamConstants.START_ELEMENT) {
                    String local = x.getLocalName();
                    current = local;
                    if (local.equals(o.docElement)) {
                        inDoc = true;
                        record++;
                        fr.documents++;
                        docLine = x.getLocation().getLineNumber();
                        seen.clear();
                        nonEmpty.clear();
                        declaredHash = null;
                    } else if (inDoc) {
                        Integer c = seen.get(local);
                        seen.put(local, Integer.valueOf(c == null ? 1 : c.intValue() + 1));
                        if (!nonEmpty.containsKey(local)) nonEmpty.put(local, Boolean.FALSE);
                    }
                    if (local.equals(o.contentElement)) {
                        inPayload = true;
                        if (o.verifyHash) {
                            md = MessageDigest.getInstance("SHA-256");
                            b64 = new Base64Streamer(md);
                        }
                    }
                } else if (e == XMLStreamConstants.CHARACTERS || e == XMLStreamConstants.CDATA) {
                    // fragment-wise, never assembled: a break inside a value shows up in whichever
                    // fragment carries it, so this is exactly as sensitive and costs no memory
                    char[] buf = x.getTextCharacters();
                    int start = x.getTextStart(), len = x.getTextLength();
                    if (inPayload) {
                        if (o.verifyHash && b64 != null) b64.accept(buf, start, len);
                    } else {
                        boolean any = false;
                        for (int i = 0; i < len; i++) {
                            char c = buf[start + i];
                            if (!Character.isWhitespace(c)) any = true;
                        }
                        if (any && current != null && inDoc) nonEmpty.put(current, Boolean.TRUE);
                    }
                    if (inPayload || current == null) continue;
                    if (o.verifyHash && current.equals(o.hashElement)) {
                        declaredHash = (declaredHash == null ? "" : declaredHash)
                                + new String(buf, start, len).trim();
                    }
                } else if (e == XMLStreamConstants.END_ELEMENT) {
                    String local = x.getLocalName();
                    if (local.equals(o.contentElement)) {
                        inPayload = false;
                        if (o.verifyHash && b64 != null) {
                            String got = hex(b64.finish());
                            if (declaredHash != null && !declaredHash.isEmpty()
                                    && !declaredHash.equalsIgnoreCase(got)) {
                                rep.add(fr, x.getLocation().getLineNumber(), record,
                                        ElarCheckReport.Kind.HashMismatch, o.hashElement,
                                        "the payload does not match its declared digest");
                            }
                            b64 = null; md = null;
                        }
                    }
                    if (local.equals(o.docElement)) {
                        inDoc = false;
                        checkMandatory(mandatory, seen, nonEmpty, rep, fr, docLine, record);
                    }
                    current = null;
                }
            }
        } catch (XMLStreamException ex) {
            fr.wellFormed = false;
            int line = ex.getLocation() != null ? ex.getLocation().getLineNumber() : 0;
            int col = ex.getLocation() != null ? ex.getLocation().getColumnNumber() : 0;
            rep.add(fr, line, record, ElarCheckReport.Kind.NotWellFormed, "",
                    "parse stopped at column " + col + "; every structural finding after this point is"
                    + " unknown, but the textual checks covered the whole file");
        } catch (Exception ex) {
            fr.wellFormed = false;
            rep.add(fr, 0, record, ElarCheckReport.Kind.NotWellFormed, "", String.valueOf(ex.getMessage()));
        } finally {
            if (x != null) { try { x.close(); } catch (Exception ignored) { } }
            r.close();
        }
    }

    /**
     * Missing, duplicate and empty are three findings, not one.
     *
     * They have three different causes: a tag missing on nearly every record is a mapping problem -
     * the column is absent from tagNameMapping and the element is never emitted - while one missing on
     * three records is a data problem. An empty element serialises as a self-closing tag and the
     * receiver treats it as absent, which is why it cannot be folded into "present".
     */
    static void checkMandatory(Set<String> mandatory, Map<String, Integer> seen,
                               Map<String, Boolean> nonEmpty, ElarCheckReport rep,
                               ElarCheckReport.FileReport fr, long line, long record) {
        for (String tag : mandatory) {
            Integer c = seen.get(tag);
            if (c == null) {
                rep.add(fr, line, record, ElarCheckReport.Kind.TagMissing, tag, "not present in this record");
            } else if (c.intValue() > 1) {
                rep.add(fr, line, record, ElarCheckReport.Kind.TagDuplicate, tag,
                        "occurs " + c + " times; exactly one is required");
            } else if (!Boolean.TRUE.equals(nonEmpty.get(tag))) {
                rep.add(fr, line, record, ElarCheckReport.Kind.TagEmpty, tag,
                        "present but empty; the receiver treats it as absent");
            }
        }
    }

    // ------------------------------------------------------------------ pair and name

    static void pairCheck(File indx, Options o, ElarCheckReport rep, ElarCheckReport.FileReport fr)
            throws IOException {
        String pullName = indx.getName().replace("INDX", "PULL");
        if (pullName.equals(indx.getName())) return;      // not an INDX-named file: nothing to pair
        File pull = new File(indx.getParentFile(), pullName);
        if (!pull.isFile()) {
            rep.add(fr, 0, 0, ElarCheckReport.Kind.PullMissing, "", pullName + " does not exist");
            return;
        }
        String indexName = stripExtension(indx.getName());
        BufferedReader r = reader(pull, o.inputCharset);
        boolean found = false;
        try {
            String line;
            while (!found && (line = r.readLine()) != null) {
                if (line.contains(indexName)) found = true;
            }
        } finally {
            r.close();
        }
        if (!found) {
            rep.add(fr, 0, 0, ElarCheckReport.Kind.PullUnreferenced, "",
                    pullName + " never names " + indexName + ", so the pair is broken");
        }
    }

    /**
     * ELAR refuses a resend that reuses a name it has already seen, so a file that must be regenerated
     * cannot go back out under the same name. Reported, never acted on: this executor renames nothing.
     */
    static void nameReuse(File f, Options o, ElarCheckReport rep, ElarCheckReport.FileReport fr) {
        File already = new File(o.deliveredDir, f.getName());
        if (!already.isFile()) return;
        rep.add(fr, 0, 0, ElarCheckReport.Kind.NameAlreadyDelivered, "",
                "already delivered under this name; a resend needs " + nextName(f.getName()));
    }

    /**
     * The next name for a resubmission.
     *
     * The trailing counter is a SYNTHETIC CLOCK, so it advances by real time arithmetic and not by a
     * numeric increment: C113859 becomes C113900, never C113860. A numeric increment produces a name
     * that is not a valid time, which is a second rejection for a new reason.
     *
     * It advances by ONE SECOND rather than by the generator's sixty. Sixty would land exactly on the
     * next batch's name, which is the one name in the directory guaranteed to be taken; one second is
     * the smallest change that makes the name unique and cannot collide with a sibling batch.
     *
     * It also scans for the counter from the RIGHT. A real name is
     * RZ2.ELA.FTP.CLICT@DT.D26229.INDX.C152100.xml, so the first '.C' is inside the family and a
     * forward search returns the name unchanged - silently, which is the worst way to be wrong here.
     */
    static String nextName(String fileName) {
        // scan from the RIGHT for '.C' followed by exactly six digits. The first '.C' in a real name
        // is inside the family - RZ2.ELA.FTP.CLICT@DT.D26229.INDX.C152100.xml - so searching forwards
        // finds '.CLICT' and leaves the name unchanged, silently.
        int i = -1;
        for (int k = fileName.length() - 8; k >= 0; k--) {
            if (fileName.charAt(k) != '.' || fileName.charAt(k + 1) != 'C') continue;
            boolean six = true;
            for (int d = 0; d < 6; d++) if (!Character.isDigit(fileName.charAt(k + 2 + d))) { six = false; break; }
            if (six) { i = k; break; }
        }
        if (i < 0) return fileName;
        String seg = fileName.substring(i + 2, i + 8);
        int h = Integer.parseInt(seg.substring(0, 2));
        int m = Integer.parseInt(seg.substring(2, 4));
        int s = Integer.parseInt(seg.substring(4, 6));
        int total = ((h * 3600 + m * 60 + s) + 1) % 86400;
        String next = String.format("%02d%02d%02d", total / 3600, (total % 3600) / 60, total % 60);
        return fileName.substring(0, i + 2) + next + fileName.substring(i + 8);
    }

    static String stripExtension(String n) {
        int d = n.lastIndexOf('.');
        return d > 0 ? n.substring(0, d) : n;
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * Read with an EXPLICIT charset, never the declaration.
     *
     * Delivered files declare ISO-8859-1 while the legacy writer emitted the JVM platform default,
     * which on the target server is windows-1252. Trusting the declaration would surface an encoding
     * mismatch as a spurious structural error - the most misleading thing a checker can do. REPLACE
     * rather than REPORT, because a byte the charset cannot map is a finding for the generator to fix,
     * not a reason for the checker to stop reading.
     */
    static BufferedReader reader(File f, String charsetName) throws IOException {
        Charset cs = Charset.forName(charsetName);
        CharsetDecoder dec = cs.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        InputStream in = new FileInputStream(f);
        return new BufferedReader(new InputStreamReader(in, dec), 1 << 16);
    }

    static List<File> list(File dir, String pattern) {
        List<File> out = new ArrayList<File>();
        if (dir == null || !dir.isDirectory()) return out;
        File[] all = dir.listFiles();
        if (all == null) return out;
        String p = pattern == null ? "*" : pattern;
        for (int i = 0; i < all.length; i++) {
            if (all[i].isFile() && matches(all[i].getName(), p)) out.add(all[i]);
        }
        java.util.Collections.sort(out, new java.util.Comparator<File>() {
            public int compare(File a, File b) { return a.getName().compareTo(b.getName()); }
        });
        return out;
    }

    /** Glob with {@code *} only, which is all the pattern needs. */
    static boolean matches(String name, String pattern) {
        String[] parts = pattern.split("\\*", -1);
        int at = 0;
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) continue;
            int k = name.indexOf(parts[i], at);
            if (k < 0) return false;
            if (i == 0 && !pattern.startsWith("*") && k != 0) return false;
            at = k + parts[i].length();
        }
        if (!pattern.endsWith("*") && parts.length > 0) {
            String last = parts[parts.length - 1];
            if (!last.isEmpty() && !name.endsWith(last)) return false;
        }
        return true;
    }

    static String hex(byte[] b) {
        char[] d = "0123456789abcdef".toCharArray();
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (int i = 0; i < b.length; i++) sb.append(d[(b[i] >> 4) & 0xF]).append(d[b[i] & 0xF]);
        return sb.toString();
    }

    /** Decodes Base64 in flight, feeding a digest. Never holds the payload. */
    static final class Base64Streamer {
        private final MessageDigest md;
        private final StringBuilder pending = new StringBuilder(4);
        Base64Streamer(MessageDigest md) { this.md = md; }
        void accept(char[] buf, int off, int len) {
            for (int i = 0; i < len; i++) {
                char c = buf[off + i];
                if (Character.isWhitespace(c)) continue;   // the wrapping, ignored as a decoder would
                pending.append(c);
                if (pending.length() == 4) {
                    md.update(Base64.getDecoder().decode(pending.toString()));
                    pending.setLength(0);
                }
            }
        }
        byte[] finish() {
            if (pending.length() > 0) {
                md.update(Base64.getDecoder().decode(pending.toString()));
                pending.setLength(0);
            }
            return md.digest();
        }
    }
}
