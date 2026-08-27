package com.legalarchive.orchestrator.tiff;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads what a TIFF says about itself: byte order, magic, and the tags of every page reached through
 * the IFD chain. <b>No pixel data is ever read</b>, which is what makes scanning a directory of three
 * million files viable - the cost per page is a couple of seeks over a few hundred bytes, not a read
 * of the image.
 *
 * <b>Read-only, and it has to stay that way.</b> This whole package carries no write API, and
 * {@code tools/scan_tiff_readonly.js} asserts it, so a scan can be aimed at a live directory without
 * anyone having to reason about whether it might touch something. Rendering the report is deliberately
 * somebody else's job for the same reason: {@link TiffScanReport} produces lines and the caller writes
 * them, exactly as {@code elarcheck} hands its findings to {@code InternalSteps}.
 *
 * Nothing here infers. A file whose pages disagree about their compression is reported as disagreeing
 * rather than collapsed to its first page; a BigTIFF is reported as a BigTIFF rather than skipped;
 * a file that cannot be parsed says which way it failed. Every file opened lands in exactly one
 * outcome, because the outcomes summing to the number of files opened is the cheapest assertion that
 * the scan did what it claims.
 */
public final class TiffInfo {

    /** How reading a single file turned out. Exactly one per file opened. */
    public enum Outcome {
        /** A classic TIFF whose IFD chain was followed to the end. */
        TIFF,
        /** Magic 43. Detected and named, deliberately not parsed: the IFD layout differs. */
        BIG_TIFF,
        /** Neither II nor MM, or the magic is neither 42 nor 43. */
        NOT_TIFF,
        /** An offset points past the end of the file, or a structure is cut short. */
        TRUNCATED,
        /** The IFD chain revisits an offset, or runs past {@link #MAX_PAGES}. */
        IFD_LOOP,
        /** The file could not be opened or read at all. */
        UNREADABLE
    }

    /** A chain longer than this is treated as a loop. No real scanned document comes close. */
    public static final int MAX_PAGES = 10000;

    // The tags collected. Everything needed to answer "is this already compressed" and "could G4
    // even apply", and nothing else - an unused tag is an unused seek.
    public static final int TAG_IMAGE_WIDTH       = 256;
    public static final int TAG_IMAGE_LENGTH      = 257;
    public static final int TAG_BITS_PER_SAMPLE   = 258;
    public static final int TAG_COMPRESSION       = 259;
    public static final int TAG_PHOTOMETRIC       = 262;
    public static final int TAG_SAMPLES_PER_PIXEL = 277;

    /** One page of one file. Absent tags stay -1 rather than being defaulted to something plausible. */
    public static final class Page {
        public int width = -1, height = -1, bitsPerSample = -1, compression = -1,
                   photometric = -1, samplesPerPixel = -1;

        /**
         * Whether CCITT T.6 could encode this page at all. G4 is a bilevel codec: one sample per
         * pixel, one bit per sample, and a photometric interpretation that means black-and-white.
         * A page missing any of those tags answers false - unknown is not eligible.
         */
        public boolean bilevel() {
            return bitsPerSample == 1 && samplesPerPixel == 1
                    && (photometric == 0 || photometric == 1);
        }
    }

    /** What one file turned out to be. */
    public static final class Result {
        public final Outcome outcome;
        public final List<Page> pages;
        public final boolean littleEndian;
        /** Why it was TRUNCATED, NOT_TIFF, IFD_LOOP or UNREADABLE. Never a path, never content. */
        public final String detail;

        Result(Outcome outcome, List<Page> pages, boolean littleEndian, String detail) {
            this.outcome = outcome;
            this.pages = pages;
            this.littleEndian = littleEndian;
            this.detail = detail;
        }

        /** The distinct compression codes across the pages, in encounter order. */
        public List<Integer> compressions() {
            List<Integer> out = new ArrayList<Integer>();
            for (int i = 0; i < pages.size(); i++) {
                Integer c = Integer.valueOf(pages.get(i).compression);
                if (!out.contains(c)) out.add(c);
            }
            return out;
        }

        /** A file whose pages do not agree. Reported as itself, never flattened to page one. */
        public boolean mixedCompression() { return compressions().size() > 1; }

        /** True when every page is bilevel. One colour page makes the file ineligible. */
        public boolean allPagesBilevel() {
            if (pages.isEmpty()) return false;
            for (int i = 0; i < pages.size(); i++) if (!pages.get(i).bilevel()) return false;
            return true;
        }

        /**
         * Whether re-encoding to CCITT T.6 could plausibly gain anything: every page bilevel, and not
         * already T.6. G3 (2 and 3) counts as eligible - it is bilevel and G4 beats it.
         */
        public boolean g4Eligible() {
            if (outcome != Outcome.TIFF || !allPagesBilevel()) return false;
            for (int i = 0; i < pages.size(); i++) if (pages.get(i).compression == 4) return false;
            return true;
        }
    }

    private TiffInfo() { }

    /** The human name of a compression code, for the report. Unknown codes keep their number. */
    public static String compressionName(int code) {
        switch (code) {
            case -1:    return "absent";
            case 1:     return "none";
            case 2:     return "CCITT RLE";
            case 3:     return "CCITT T.4 (G3)";
            case 4:     return "CCITT T.6 (G4)";
            case 5:     return "LZW";
            case 6:     return "JPEG (old-style)";
            case 7:     return "JPEG";
            case 8:     return "Deflate";
            case 32773: return "PackBits";
            case 32946: return "Deflate (Adobe)";
            default:    return "code " + code;
        }
    }

    public static Result read(File f) {
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "r");
            return readOpen(raf, raf.length());
        } catch (IOException e) {
            return new Result(Outcome.UNREADABLE, empty(), false, e.getClass().getSimpleName());
        } finally {
            if (raf != null) try { raf.close(); } catch (IOException ignored) { }
        }
    }

    private static List<Page> empty() { return new ArrayList<Page>(); }

    private static Result readOpen(RandomAccessFile raf, long length) throws IOException {
        if (length < 8) return new Result(Outcome.NOT_TIFF, empty(), false, "shorter than a TIFF header");

        byte[] head = new byte[8];
        raf.seek(0);
        raf.readFully(head);

        boolean le;
        if (head[0] == 'I' && head[1] == 'I')      le = true;
        else if (head[0] == 'M' && head[1] == 'M') le = false;
        else return new Result(Outcome.NOT_TIFF, empty(), false, "byte order is neither II nor MM");

        int magic = u16(head, 2, le);
        // 43 is BigTIFF: a different header length, 8-byte offsets and a different IFD entry layout.
        // Named as its own outcome rather than skipped, so it cannot silently leave the denominator.
        if (magic == 43) return new Result(Outcome.BIG_TIFF, empty(), le, "magic 43");
        if (magic != 42) return new Result(Outcome.NOT_TIFF, empty(), le, "magic " + magic);

        long next = u32(head, 4, le);
        List<Page> pages = new ArrayList<Page>();
        Set<Long> seen = new HashSet<Long>();

        while (next != 0) {
            if (next < 0 || next + 2 > length) {
                return new Result(Outcome.TRUNCATED, pages, le, "IFD offset past the end of the file");
            }
            if (!seen.add(Long.valueOf(next))) {
                return new Result(Outcome.IFD_LOOP, pages, le, "IFD chain revisits an offset");
            }
            if (pages.size() >= MAX_PAGES) {
                return new Result(Outcome.IFD_LOOP, pages, le, "more than " + MAX_PAGES + " pages");
            }

            raf.seek(next);
            byte[] cnt = new byte[2];
            raf.readFully(cnt);
            int entries = u16(cnt, 0, le);
            long block = 12L * entries + 4L;
            if (next + 2 + block > length) {
                return new Result(Outcome.TRUNCATED, pages, le, "IFD runs past the end of the file");
            }
            byte[] buf = new byte[(int) block];
            raf.readFully(buf);

            Page p = new Page();
            for (int i = 0; i < entries; i++) {
                int o = 12 * i;
                int tag = u16(buf, o, le);
                switch (tag) {
                    case TAG_IMAGE_WIDTH:       p.width           = (int) first(raf, buf, o, le, length); break;
                    case TAG_IMAGE_LENGTH:      p.height          = (int) first(raf, buf, o, le, length); break;
                    case TAG_BITS_PER_SAMPLE:   p.bitsPerSample   = (int) first(raf, buf, o, le, length); break;
                    case TAG_COMPRESSION:       p.compression     = (int) first(raf, buf, o, le, length); break;
                    case TAG_PHOTOMETRIC:       p.photometric     = (int) first(raf, buf, o, le, length); break;
                    case TAG_SAMPLES_PER_PIXEL: p.samplesPerPixel = (int) first(raf, buf, o, le, length); break;
                    default: break;   // every other tag is an unused seek
                }
            }
            pages.add(p);
            next = u32(buf, (int) (12L * entries), le);
        }

        if (pages.isEmpty()) return new Result(Outcome.TRUNCATED, pages, le, "no IFD at all");
        return new Result(Outcome.TIFF, pages, le, null);
    }

    /**
     * The first value of an entry.
     *
     * The value field is four bytes. When the values fit inside it they are stored there; when they do
     * not, the field holds an offset instead. Reading the field as a value in the second case - which
     * happens for BitsPerSample as soon as there is more than one sample per pixel - would return an
     * offset dressed up as a bit depth, and the page would be classified on it.
     */
    private static long first(RandomAccessFile raf, byte[] buf, int o, boolean le, long length)
            throws IOException {
        int type  = u16(buf, o + 2, le);
        long count = u32(buf, o + 4, le);
        int size = typeSize(type);
        if (size == 0 || count <= 0) return -1;

        if (size * count <= 4) return valueAt(buf, o + 8, type, le);

        long off = u32(buf, o + 8, le);
        if (off < 0 || off + size > length) return -1;      // an offset that cannot be honoured
        raf.seek(off);
        byte[] v = new byte[size];
        raf.readFully(v);
        return valueAt(v, 0, type, le);
    }

    private static int typeSize(int type) {
        switch (type) {
            case 1: case 2: case 6: case 7: return 1;   // BYTE, ASCII, SBYTE, UNDEFINED
            case 3: case 8:                 return 2;   // SHORT, SSHORT
            case 4: case 9:                 return 4;   // LONG, SLONG
            default:                        return 0;   // RATIONAL and the rest: not a tag we read
        }
    }

    private static long valueAt(byte[] b, int at, int type, boolean le) {
        switch (typeSize(type)) {
            case 1:  return b[at] & 0xFF;
            case 2:  return u16(b, at, le);
            case 4:  return u32(b, at, le);
            default: return -1;
        }
    }

    private static int u16(byte[] b, int at, boolean le) {
        int a = b[at] & 0xFF, c = b[at + 1] & 0xFF;
        return le ? (c << 8) | a : (a << 8) | c;
    }

    private static long u32(byte[] b, int at, boolean le) {
        long a = b[at] & 0xFFL, c = b[at + 1] & 0xFFL, d = b[at + 2] & 0xFFL, e = b[at + 3] & 0xFFL;
        return le ? (e << 24) | (d << 16) | (c << 8) | a
                  : (a << 24) | (c << 16) | (d << 8) | e;
    }
}
