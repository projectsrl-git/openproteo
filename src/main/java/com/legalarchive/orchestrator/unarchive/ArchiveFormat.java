package com.legalarchive.orchestrator.unarchive;

import java.util.Locale;

/**
 * Decides what an archive is: magic bytes first, extension as a hint (spec section 3).
 *
 * <p>More formats are RECOGNISED than are supported, on purpose: "this is a bzip2 archive and
 * bzip2 is not in Java 8" is something an operator can act on, "unknown format" sends someone to a
 * hex editor.
 *
 * <p>Pure function of the file name, the first bytes of the file and the requested format; no IO.
 */
public final class ArchiveFormat {

    /** What the first bytes say. */
    public enum Kind {
        ZIP, GZIP, TAR_POSIX, TAR_GNU,
        /** A header with a valid checksum and no magic: a pre-POSIX tar. Never claimed by magic alone. */
        TAR_V7,
        /** The first 512 bytes are all zero: GNU tar's empty archive (measured: 10240 zero bytes). */
        TAR_EMPTY,
        BZIP2, XZ, SEVEN_ZIP, RAR, ZSTD, ZIP_SPANNED,
        /** No bytes at all. */
        EMPTY,
        UNKNOWN
    }

    /** What the author asked for; {@link #AUTO} lets the magic decide. */
    public enum Requested {
        AUTO, ZIP, TAR, TAR_GZ, GZ;

        /** Parses the {@code format} parameter; null for an unknown value. */
        public static Requested parse(String v) {
            if (v == null || v.trim().isEmpty()) return AUTO;
            String s = v.trim().toLowerCase(Locale.ROOT);
            if (s.equals("auto")) return AUTO;
            if (s.equals("zip")) return ZIP;
            if (s.equals("tar")) return TAR;
            if (s.equals("tar.gz") || s.equals("tgz")) return TAR_GZ;
            if (s.equals("gz") || s.equals("gzip")) return GZ;
            return null;
        }
    }

    /** How the executor will read the file. */
    public enum Handling { ZIP, TAR, GZIP_AUTO, GZIP_TAR, GZIP_SINGLE }

    /** The outcome for one file. */
    public static final class Decision {
        public final Kind kind;
        public final Handling handling;
        /** Non-null when the extension disagrees with the magic under {@code auto}: logged, never fatal. */
        public final String warning;

        Decision(Kind kind, Handling handling, String warning) {
            this.kind = kind;
            this.handling = handling;
            this.warning = warning;
        }
    }

    /** Bytes needed to see every magic, tar's included (offset 257 + 8, inside the first block). */
    public static final int HEAD_BYTES = 512;

    private ArchiveFormat() {
    }

    /** Classifies the first bytes of a file ({@code len} of them are valid). */
    public static Kind sniff(byte[] b, int len) {
        if (len <= 0) return Kind.EMPTY;
        if (starts(b, len, 0x50, 0x4B, 0x03, 0x04) || starts(b, len, 0x50, 0x4B, 0x05, 0x06)) return Kind.ZIP;
        if (starts(b, len, 0x50, 0x4B, 0x07, 0x08)) return Kind.ZIP_SPANNED;
        if (starts(b, len, 0x1F, 0x8B)) return Kind.GZIP;
        if (len >= 4 && starts(b, len, 0x42, 0x5A, 0x68) && b[3] >= '1' && b[3] <= '9') return Kind.BZIP2;
        if (starts(b, len, 0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00)) return Kind.XZ;
        if (starts(b, len, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C)) return Kind.SEVEN_ZIP;
        if (starts(b, len, 0x52, 0x61, 0x72, 0x21, 0x1A, 0x07)) return Kind.RAR;
        if (starts(b, len, 0x28, 0xB5, 0x2F, 0xFD)) return Kind.ZSTD;
        if (len >= 512) {
            if (allZero(b, 512)) return Kind.TAR_EMPTY;
            if (TarStreamReader.checksumValid(b)) {
                if (TarStreamReader.isPosixMagic(b)) return Kind.TAR_POSIX;
                if (TarStreamReader.isGnuMagic(b)) return Kind.TAR_GNU;
                return Kind.TAR_V7;
            }
        }
        return Kind.UNKNOWN;
    }

    /**
     * Decides how to read a file. Throws when the file is refused.
     *
     * <p>Intersections, decided here and in section 3.2 of the spec:
     * <ul>
     * <li>Under {@code auto} the MAGIC wins over the extension; a disagreement is a warning.
     *     {@code report.tar} that is gzip: read as gzip, warning logged.</li>
     * <li>An EXPLICIT format loses to nothing: a file contradicting it is refused, even when
     *     {@code auto} would have read it. {@code format=tar} on a gzip: refused.</li>
     * <li>{@link Kind#TAR_V7} and {@link Kind#TAR_EMPTY} carry no magic, so they are accepted only
     *     when the author said tar or the name ends {@code .tar}; otherwise they are unrecognised.
     *     A zero-filled {@code data.bin} is not an empty tar.</li>
     * <li>A 0-byte file is refused whatever its name, as GNU tar and python refuse it (measured).</li>
     * </ul>
     */
    public static Decision decide(String fileName, byte[] head, int len, Requested req) throws UnarchiveException {
        Kind k = sniff(head, len);
        String name = fileName == null ? "" : fileName;
        Kind byExt = kindFromExtension(name);
        if (k == Kind.EMPTY) {
            throw new UnarchiveException(UnarchiveException.Rule.EMPTY_FILE, name + " is empty (0 bytes)");
        }
        String refusedReason = unsupportedReason(k);
        if (refusedReason != null) {
            throw new UnarchiveException(UnarchiveException.Rule.UNSUPPORTED_FORMAT, name + " is " + refusedReason);
        }
        boolean tarByMagic = k == Kind.TAR_POSIX || k == Kind.TAR_GNU;
        boolean tarWithoutMagic = k == Kind.TAR_V7 || k == Kind.TAR_EMPTY;
        switch (req) {
            case ZIP:
                if (k == Kind.ZIP) return new Decision(k, Handling.ZIP, null);
                throw mismatch(name, "zip", k);
            case TAR:
                if (tarByMagic || tarWithoutMagic) return new Decision(k, Handling.TAR, null);
                throw mismatch(name, "tar", k);
            case TAR_GZ:
                if (k == Kind.GZIP) return new Decision(k, Handling.GZIP_TAR, null);
                throw mismatch(name, "tar.gz", k);
            case GZ:
                if (k == Kind.GZIP) return new Decision(k, Handling.GZIP_SINGLE, null);
                throw mismatch(name, "gz", k);
            default:
                break;
        }
        // auto
        if (k == Kind.ZIP) return new Decision(k, Handling.ZIP, warn(name, byExt, k));
        if (k == Kind.GZIP) return new Decision(k, Handling.GZIP_AUTO, warn(name, byExt, k));
        if (tarByMagic) return new Decision(k, Handling.TAR, warn(name, byExt, k));
        if (tarWithoutMagic && byExt == Kind.TAR_POSIX) return new Decision(k, Handling.TAR, null);
        throw new UnarchiveException(UnarchiveException.Rule.UNRECOGNISED,
                name + " is not an archive this step recognises (zip, tar, tar.gz, gz)"
                        + (tarWithoutMagic ? "; its first block could be a tar without magic, which is accepted"
                        + " only when the name ends .tar or format=tar" : ""));
    }

    /**
     * Whether the decompressed start of a gzip is a tar, for {@link Handling#GZIP_AUTO}.
     * {@code innerName} is the archive name with {@code .gz}/{@code .tgz} handled by
     * {@link #innerNameOfGzip}. Intersection: a POSIX or GNU magic inside is a tar whatever the
     * name; a magic-less or all-zero first block is a tar ONLY when the archive is named
     * {@code .tgz}/{@code .tar.gz} - otherwise a gzipped block of zeros would unpack as an empty tar.
     */
    public static boolean innerIsTar(String archiveName, byte[] head, int len) {
        Kind k = sniff(head, len);
        if (k == Kind.TAR_POSIX || k == Kind.TAR_GNU) return true;
        if (k == Kind.TAR_V7 || k == Kind.TAR_EMPTY) return namedTarGz(archiveName);
        return false;
    }

    /**
     * The name of the single file inside a plain gzip. From OUR side, never from the gzip FNAME
     * header, which the archive controls (measured: gzip -dN honours a FNAME of ../../evil.txt by
     * stripping the directory). Strips {@code .gz} or {@code .gzip} case-insensitively; with no such
     * suffix, the archive's own name - safe because it lands in the archive's own directory.
     */
    public static String innerNameOfGzip(String archiveName) {
        String lower = archiveName.toLowerCase(Locale.ROOT);
        String out = archiveName;
        if (lower.endsWith(".gzip")) out = archiveName.substring(0, archiveName.length() - 5);
        else if (lower.endsWith(".gz")) out = archiveName.substring(0, archiveName.length() - 3);
        return out.isEmpty() ? archiveName : out;
    }

    /**
     * The per-archive subdirectory name: the file name without its RECOGNISED archive extension
     * ({@code .tar.gz}, {@code .tgz}, {@code .tar}, {@code .zip}, {@code .gz}, case-insensitive).
     * An unrecognised extension is kept: {@code report.dat} that is a zip becomes {@code report.dat}.
     */
    public static String baseName(String archiveName) {
        String lower = archiveName.toLowerCase(Locale.ROOT);
        String[] exts = {".tar.gz", ".tgz", ".tar", ".zip", ".gzip", ".gz"};
        for (String e : exts) {
            if (lower.endsWith(e) && archiveName.length() > e.length()) {
                return archiveName.substring(0, archiveName.length() - e.length());
            }
        }
        return archiveName;
    }

    static Kind kindFromExtension(String name) {
        String l = name.toLowerCase(Locale.ROOT);
        if (l.endsWith(".zip")) return Kind.ZIP;
        if (l.endsWith(".tar")) return Kind.TAR_POSIX;
        if (l.endsWith(".tgz") || l.endsWith(".gz") || l.endsWith(".gzip")) return Kind.GZIP;
        return Kind.UNKNOWN;
    }

    private static boolean namedTarGz(String name) {
        String l = name.toLowerCase(Locale.ROOT);
        return l.endsWith(".tgz") || l.endsWith(".tar.gz");
    }

    private static String warn(String name, Kind byExt, Kind k) {
        if (byExt == Kind.UNKNOWN) return null;
        boolean tar = k == Kind.TAR_POSIX || k == Kind.TAR_GNU;
        boolean agrees = byExt == k || (byExt == Kind.TAR_POSIX && tar);
        if (agrees) return null;
        return name + " is named like " + label(byExt) + " but its content is " + label(k)
                + "; read as " + label(k);
    }

    private static UnarchiveException mismatch(String name, String asked, Kind k) {
        return new UnarchiveException(UnarchiveException.Rule.FORMAT_MISMATCH,
                "format=" + asked + " but " + name + " is " + label(k));
    }

    static String label(Kind k) {
        switch (k) {
            case ZIP: return "zip";
            case GZIP: return "gzip";
            case TAR_POSIX: return "tar";
            case TAR_GNU: return "tar (GNU)";
            case TAR_V7: return "possibly a tar without magic";
            case TAR_EMPTY: return "all zeros (an empty tar, if it is a tar)";
            case BZIP2: return "bzip2";
            case XZ: return "xz";
            case SEVEN_ZIP: return "7z";
            case RAR: return "rar";
            case ZSTD: return "zstd";
            case ZIP_SPANNED: return "a spanned (multi-volume) zip";
            case EMPTY: return "empty";
            default: return "not a recognised archive";
        }
    }

    private static String unsupportedReason(Kind k) {
        switch (k) {
            case BZIP2: case XZ: case ZSTD:
                return label(k) + " compressed: not in the Java 8 platform (it needs commons-compress, not"
                        + " available); recompress as gzip or zip";
            case SEVEN_ZIP: case RAR:
                return "a " + label(k) + " archive: not in the Java 8 platform; repack as zip or tar";
            case ZIP_SPANNED:
                return label(k) + ": join the volumes into one zip first";
            default:
                return null;
        }
    }

    private static boolean starts(byte[] b, int len, int... m) {
        if (len < m.length) return false;
        for (int i = 0; i < m.length; i++) {
            if ((b[i] & 0xFF) != m[i]) return false;
        }
        return true;
    }

    private static boolean allZero(byte[] b, int n) {
        for (int i = 0; i < n; i++) {
            if (b[i] != 0) return false;
        }
        return true;
    }
}
