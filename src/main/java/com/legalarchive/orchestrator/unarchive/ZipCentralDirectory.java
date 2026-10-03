package com.legalarchive.orchestrator.unarchive;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a zip's central directory ourselves, for the two things Java 8 does not give
 * (spec section 4.2, Gate 0 Q5):
 * <ul>
 * <li>the RAW name bytes, so the charset of an unflagged name is decided here and not by the JDK;</li>
 * <li>the external attributes, which is where a {@code zip -y} symlink says it is one (measured:
 *     mode {@code 120777}); {@code ZipEntry} in Java 8 has no getter for them.</li>
 * </ul>
 * Everything else - decompression, ZIP64 bookkeeping for the streams - stays with {@code ZipFile},
 * and {@link ZipArchiveReader} refuses the archive if the two readings disagree on the count or on
 * any name. Two independent parsers of one directory agreeing is the point; neither is trusted alone.
 */
public final class ZipCentralDirectory {

    /** One central directory record, as stored. */
    public static final class Record {
        public final int versionMadeBy;
        public final int flags;
        public final int method;
        public final long crc;
        public final long compressedSize;
        public final long size;
        public final byte[] rawName;
        public final byte[] extra;
        public final long externalAttributes;

        Record(int versionMadeBy, int flags, int method, long crc, long compressedSize, long size,
               byte[] rawName, byte[] extra, long externalAttributes) {
            this.versionMadeBy = versionMadeBy;
            this.flags = flags;
            this.method = method;
            this.crc = crc;
            this.compressedSize = compressedSize;
            this.size = size;
            this.rawName = rawName;
            this.extra = extra;
            this.externalAttributes = externalAttributes;
        }

        /** Host system in the high byte of "version made by"; 3 = Unix, whose mode sits in the attributes. */
        public boolean madeOnUnix() {
            return (versionMadeBy >>> 8) == 3;
        }

        /** The Unix file type bits, or -1 when the record was not made on Unix. */
        public int unixType() {
            return madeOnUnix() ? (int) ((externalAttributes >>> 16) & 0170000) : -1;
        }

        public boolean utf8Flag() {
            return (flags & 0x800) != 0;
        }

        public boolean encrypted() {
            return (flags & 0x1) != 0 || (flags & 0x40) != 0;
        }

        /** The payload of extra field {@code id}, or null. */
        public byte[] extraField(int id) {
            int p = 0;
            while (p + 4 <= extra.length) {
                int hid = u16(extra, p);
                int len = u16(extra, p + 2);
                if (p + 4 + len > extra.length) return null;
                if (hid == id) {
                    byte[] out = new byte[len];
                    System.arraycopy(extra, p + 4, out, 0, len);
                    return out;
                }
                p += 4 + len;
            }
            return null;
        }
    }

    public static final int S_IFMT = 0170000;
    public static final int S_IFLNK = 0120000;
    public static final int S_IFREG = 0100000;
    public static final int S_IFDIR = 0040000;

    private static final int EOCD = 0x06054b50;
    private static final int ZIP64_LOCATOR = 0x07064b50;
    private static final int ZIP64_EOCD = 0x06064b50;
    private static final int CEN = 0x02014b50;

    private ZipCentralDirectory() {
    }

    /** Reads every record in directory order. */
    public static List<Record> read(File f) throws IOException {
        RandomAccessFile r = new RandomAccessFile(f, "r");
        try {
            long len = r.length();
            int tail = (int) Math.min(len, 22 + 65535);
            byte[] t = new byte[tail];
            r.seek(len - tail);
            r.readFully(t);
            int e = -1;
            for (int i = tail - 22; i >= 0; i--) {
                if (u32(t, i) == EOCD && i + 22 + u16(t, i + 20) <= tail) {
                    e = i;
                    break;
                }
            }
            if (e < 0) throw structure(f, "no end-of-central-directory record");
            long count = u16(t, e + 10);
            long cdSize = u32(t, e + 12);
            long cdOffset = u32(t, e + 16);
            long eocdAt = len - tail + e;
            if (count == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
                if (eocdAt < 20) throw structure(f, "ZIP64 values without a ZIP64 locator");
                byte[] loc = new byte[20];
                r.seek(eocdAt - 20);
                r.readFully(loc);
                if (u32(loc, 0) != ZIP64_LOCATOR) throw structure(f, "ZIP64 values without a ZIP64 locator");
                long z64 = u64(loc, 8);
                byte[] z = new byte[56];
                r.seek(z64);
                r.readFully(z);
                if (u32(z, 0) != ZIP64_EOCD) throw structure(f, "the ZIP64 locator does not point at a ZIP64 record");
                count = u64(z, 32);
                cdSize = u64(z, 40);
                cdOffset = u64(z, 48);
            }
            if (cdOffset + cdSize > len || cdSize > Integer.MAX_VALUE) {
                throw structure(f, "the central directory lies outside the file");
            }
            byte[] cd = new byte[(int) cdSize];
            r.seek(cdOffset);
            r.readFully(cd);
            List<Record> out = new ArrayList<Record>();
            int p = 0;
            for (long i = 0; i < count; i++) {
                if (p + 46 > cd.length || u32(cd, p) != CEN) throw structure(f, "central directory record " + i + " is malformed");
                int nameLen = u16(cd, p + 28);
                int extraLen = u16(cd, p + 30);
                int commentLen = u16(cd, p + 32);
                if (p + 46 + nameLen + extraLen + commentLen > cd.length) {
                    throw structure(f, "central directory record " + i + " runs past the directory");
                }
                byte[] name = new byte[nameLen];
                System.arraycopy(cd, p + 46, name, 0, nameLen);
                byte[] extra = new byte[extraLen];
                System.arraycopy(cd, p + 46 + nameLen, extra, 0, extraLen);
                long csize = u32(cd, p + 20);
                long size = u32(cd, p + 24);
                Record rec = new Record(u16(cd, p + 4), u16(cd, p + 8), u16(cd, p + 10), u32(cd, p + 16),
                        csize, size, name, extra, u32(cd, p + 38));
                if (size == 0xFFFFFFFFL || csize == 0xFFFFFFFFL) {
                    rec = zip64Sizes(rec, f);
                }
                out.add(rec);
                p += 46 + nameLen + extraLen + commentLen;
            }
            return out;
        } finally {
            r.close();
        }
    }

    /** Sizes stored as 0xFFFFFFFF are in extra field 0x0001, in that order, only those that overflowed. */
    private static Record zip64Sizes(Record r, File f) throws UnarchiveException {
        byte[] z = r.extraField(0x0001);
        if (z == null) throw structure(f, "a ZIP64 size without its ZIP64 extra field");
        int p = 0;
        long size = r.size;
        long csize = r.compressedSize;
        if (size == 0xFFFFFFFFL) {
            if (p + 8 > z.length) throw structure(f, "a truncated ZIP64 extra field");
            size = u64(z, p);
            p += 8;
        }
        if (csize == 0xFFFFFFFFL) {
            if (p + 8 > z.length) throw structure(f, "a truncated ZIP64 extra field");
            csize = u64(z, p);
        }
        return new Record(r.versionMadeBy, r.flags, r.method, r.crc, csize, size, r.rawName, r.extra,
                r.externalAttributes);
    }

    private static UnarchiveException structure(File f, String why) {
        return new UnarchiveException(UnarchiveException.Rule.ZIP_STRUCTURE, f.getName() + ": " + why);
    }

    static int u16(byte[] b, int p) {
        return (b[p] & 0xFF) | (b[p + 1] & 0xFF) << 8;
    }

    static long u32(byte[] b, int p) {
        return (u16(b, p) | (long) u16(b, p + 2) << 16) & 0xFFFFFFFFL;
    }

    static long u64(byte[] b, int p) {
        return u32(b, p) | u32(b, p + 4) << 32;
    }
}
