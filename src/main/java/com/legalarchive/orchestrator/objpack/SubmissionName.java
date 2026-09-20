package com.legalarchive.orchestrator.objpack;

import java.util.Locale;

/**
 * The Transarch submission naming rules, in one place because every artifact of a submission shares
 * them and a disagreement between any two of them is a rejected submission.
 *
 * <p>Base name: {@code <tf#>.<transmission date>.S<sequence>.V<version>}, e.g.
 * {@code tf0000001.20250101.S001.V001}. Sequence and version are left padded with zeros to three
 * digits, per the specification's §2.
 *
 * <p>§3.5 of the specification writes {@code V1} rather than {@code V001} in its own tar and md5
 * examples, while §2 requires three digits and every other example in the document uses them. Three
 * digits is what this class produces, because §2 states the rule and also states that the base name
 * is identical for all files in a submission — so the §3.5 examples cannot be right without making
 * the tar disagree with the audit file it contains. Recorded as Gate 0 question 9.2.
 */
public final class SubmissionName {

    private final String tfId;
    private final String transmissionDate;
    private final int sequenceNr;
    private final int versionNr;
    private final String base;

    public SubmissionName(String tfId, String transmissionDate, int sequenceNr, int versionNr) {
        this.tfId = requireFeedId(tfId);
        this.transmissionDate = requireDate(transmissionDate);
        this.sequenceNr = requireRange(sequenceNr, "sequenceNr");
        this.versionNr = requireRange(versionNr, "versionNr");
        this.base = this.tfId + "." + this.transmissionDate
                + ".S" + pad3(this.sequenceNr) + ".V" + pad3(this.versionNr);
    }

    /** {@code tf0000001.20250101.S001.V001} — the stem every artifact of the submission shares. */
    public String base() {
        return base;
    }

    public String auditJson()  { return base + ".audit.json"; }
    public String metadataCsv(){ return base + ".metadata.csv"; }
    public String control()    { return base + ".control"; }
    public String tar()        { return base + ".tar"; }

    /**
     * The name of the delivered archive for a given compression.
     *
     * <p>The specification states this outright and there is nothing to infer: §3.5 says gzip
     * results in a {@code .tar.gz} file, bzip2 in {@code .tar.bz2}, xz in {@code .tar.xz}. §2's
     * pattern ends in {@code .*}, a wildcard, so those names satisfy it without strain — its
     * {@code .tar} examples are the uncompressed case, not a rule against the others.
     *
     * <p>Naming a compressed archive {@code .tar} would misdeclare its type to the receiver.
     * Whether it would still be unpacked depends entirely on the reader: GNU tar and bsdtar sniff
     * the magic bytes and cope, while anything parsing ustar headers directly — commons-compress,
     * a strict system tar, {@link UstarReader} itself — sees only garbage at offset 257. That is a
     * wager on someone else's implementation, and the wrong outcome of it fails late and quietly
     * rather than at the naming validation.
     */
    public String archive(String compression) {
        String c = compression == null ? "none" : compression.trim().toLowerCase(Locale.ROOT);
        if (c.isEmpty() || "none".equals(c)) {
            return base + ".tar";
        }
        if ("gzip".equals(c) || "gz".equals(c)) {
            return base + ".tar.gz";
        }
        if ("bzip2".equals(c) || "bz2".equals(c)) {
            return base + ".tar.bz2";
        }
        if ("xz".equals(c)) {
            return base + ".tar.xz";
        }
        throw new ObjPackException("compression must be none, gzip, bzip2 or xz; got '" + compression + "'");
    }
    public String md5()        { return base + ".md5"; }

    /**
     * The width the OID must be padded to, derived from the number of objects in the submission.
     *
     * <p>§3.3: "the padding on the OID must be assigned based on the number of files in the
     * submission". 5 objects give OID1..OID5, 62 give OID01..OID62, 137 give OID001..OID137 — that
     * is, the width of the largest id. Confirmed as the rule to follow for every feed (Gate 0 9.1),
     * including feeds the PowerShell script previously built with a fixed width of 6.
     */
    public static int oidWidth(int objectCount) {
        if (objectCount < 1) {
            throw new IllegalArgumentException("a submission needs at least one object, got " + objectCount);
        }
        return Integer.toString(objectCount).length();
    }

    /**
     * The leaf name of one object file.
     *
     * <p>§3.3 requires it to start with the submission base name and end with
     * {@code object_id.file_format}; anything in between is free, which is what {@code label}
     * carries — the specification's own example is {@code ….V001.monthly_report.OID2.pdf}.
     *
     * @param extension the object's extension WITH its leading dot, or empty for a file that has none
     */
    public String objectFile(int objectId, int oidWidth, String label, String extension) {
        if (objectId < 1) {
            throw new IllegalArgumentException("object_id starts at 1, got " + objectId);
        }
        StringBuilder sb = new StringBuilder(base);
        String lab = label == null ? "" : label.trim();
        if (!lab.isEmpty()) {
            sb.append('.').append(sanitiseLabel(lab));
        }
        sb.append(".OID").append(padTo(objectId, oidWidth));
        String ext = extension == null ? "" : extension.trim();
        if (!ext.isEmpty()) {
            if (ext.charAt(0) != '.') {
                sb.append('.');
            }
            sb.append(ext);
        }
        return sb.toString();
    }

    /**
     * Keeps a free-text label inside the characters §2 allows in a submission file name. Anything
     * else becomes '_' rather than being dropped, so two labels that differ only in punctuation do
     * not collapse onto the same name.
     */
    public static String sanitiseLabel(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            sb.append(allowed ? c : '_');
        }
        return sb.toString();
    }

    /** The extension of a file name, with its leading dot, or "" when it has none. */
    public static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int slash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        String leaf = slash >= 0 ? fileName.substring(slash + 1) : fileName;
        int dot = leaf.lastIndexOf('.');
        // A leading dot is the whole name of a hidden file, not an extension.
        return (dot <= 0 || dot == leaf.length() - 1) ? "" : leaf.substring(dot);
    }

    public String tfId() { return tfId; }
    public String transmissionDate() { return transmissionDate; }
    public int sequenceNr() { return sequenceNr; }
    public int versionNr() { return versionNr; }

    // ---------------------------------------------------------------- validation

    private static String requireFeedId(String s) {
        String v = s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
        if (!v.matches("^tf[0-9]{7}$")) {
            throw new ObjPackException("tfId must be 'tf' followed by 7 digits, e.g. tf0000001; got '" + s + "'");
        }
        return v;
    }

    private static String requireDate(String s) {
        String v = s == null ? "" : s.trim();
        if (!v.matches("^[0-9]{8}$")) {
            throw new ObjPackException("transmissionDate must be yyyyMMdd; got '" + s + "'");
        }
        int y = Integer.parseInt(v.substring(0, 4));
        int m = Integer.parseInt(v.substring(4, 6));
        int d = Integer.parseInt(v.substring(6, 8));
        if (m < 1 || m > 12 || d < 1 || d > daysIn(y, m)) {
            throw new ObjPackException("transmissionDate is not a real date: '" + v + "'");
        }
        return v;
    }

    static int daysIn(int year, int month) {
        switch (month) {
            case 1: case 3: case 5: case 7: case 8: case 10: case 12: return 31;
            case 4: case 6: case 9: case 11: return 30;
            case 2:
                boolean leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0;
                return leap ? 29 : 28;
            default: return 0;
        }
    }

    private static int requireRange(int v, String what) {
        if (v < 1 || v > 999) {
            throw new ObjPackException(what + " must be between 1 and 999 to fit three digits; got " + v);
        }
        return v;
    }

    private static String pad3(int v) {
        return padTo(v, 3);
    }

    private static String padTo(int v, int width) {
        String s = Integer.toString(v);
        if (s.length() >= width) {
            return s;
        }
        StringBuilder sb = new StringBuilder(width);
        for (int i = s.length(); i < width; i++) {
            sb.append('0');
        }
        return sb.append(s).toString();
    }
}
