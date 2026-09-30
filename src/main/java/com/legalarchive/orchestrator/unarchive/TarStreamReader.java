package com.legalarchive.orchestrator.unarchive;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * Reads a tar sequentially, one pass, payloads streamed and never buffered (spec section 4.1).
 *
 * <p>Written for extraction, and deliberately separate from {@code objpack.UstarReader}, which
 * reads only what objpack's own writer produces and was measured unfit here: it reports GNU
 * {@code @LongLink}, pax headers and git's global header as members, ignores the ustar prefix and
 * has no typeflags. objpack's verification stays independent of this class.
 *
 * <p>This class REPORTS; it does not apply policy. A symlink, a hardlink, a device or a FIFO comes
 * back as an entry of that type, and a missing end-of-archive marker comes back as a flag - what
 * to do about them is decided by the caller (sections 6 and 4.1). It refuses only what it cannot
 * read correctly: a bad checksum, a truncated stream, sparse/multivolume/dumpdir members, malformed
 * extended headers, names that are not valid UTF-8.
 *
 * <p>Decided intersections:
 * <ul>
 * <li><b>pax {@code path} wins over the header name</b> (and over the ustar prefix), as the pax
 *     standard says. When it is present the header name bytes are NOT decoded, so a producer that
 *     puts a transliterated legacy name there and the real one in pax is read correctly.</li>
 * <li><b>pax {@code path} and a GNU {@code L} name on the same entry</b>: refused as ambiguous.</li>
 * <li><b>pax {@code size} wins over the header size</b> (a member over 8 GiB may carry 0 there).</li>
 * <li><b>The prefix field is used only under the POSIX magic</b> {@code ustar\0}: in a GNU header
 *     those bytes hold other fields, and joining them would corrupt the name.</li>
 * <li><b>pax global headers ({@code g}) never change an entry</b>: parsed, their keys reported once.</li>
 * <li><b>End of archive</b>: the stream ending exactly on a header boundary with no zero block is
 *     reported as {@link #endMarkerMissing()} (GNU tar accepts it with exit 0, measured); ending
 *     anywhere else - inside a header, inside a payload or its padding - is refused as truncated.</li>
 * </ul>
 */
public final class TarStreamReader {

    /** What a member is. The reader refuses the types it cannot read at all. */
    public enum Type { FILE, DIRECTORY, HARDLINK, SYMLINK, CHAR_DEVICE, BLOCK_DEVICE, FIFO }

    /** One member, as declared by the archive after extended headers are applied. */
    public static final class Entry {
        public final String name;
        public final String linkName;
        public final Type type;
        public final char typeflag;
        public final long size;
        /** Seconds since the epoch; pax fractions dropped. */
        public final long mtime;
        /** Byte offset of this member's own header in the (decompressed) tar stream. */
        public final long headerOffset;

        Entry(String name, String linkName, Type type, char typeflag, long size, long mtime, long headerOffset) {
            this.name = name;
            this.linkName = linkName;
            this.type = type;
            this.typeflag = typeflag;
            this.size = size;
            this.mtime = mtime;
            this.headerOffset = headerOffset;
        }
    }

    /** Cap on one GNU long-name or pax record block: a name is not a megabyte. */
    static final int MAX_EXTENDED = 1024 * 1024;

    private final InputStream in;
    private final byte[] header = new byte[512];
    private long offset;
    private long remaining;
    private long padding;
    private boolean finished;
    private boolean endMarkerSeen;
    private boolean endMarkerMissing;
    private boolean loneZeroBlock;
    private final TreeSet<String> globalKeys = new TreeSet<String>();
    private final TreeSet<String> ignoredPaxKeys = new TreeSet<String>();
    private int volumeLabels;
    private final InputStream payload = new Payload();

    public TarStreamReader(InputStream in) {
        this.in = in;
    }

    /** The next member, or null at the end of the archive. Unread payload of the previous one is skipped. */
    public Entry next() throws IOException {
        if (finished) return null;
        drain();
        String longName = null;
        String longLink = null;
        Map<String, String> pax = null;
        long paxHeaderAt = -1;
        while (true) {
            long at = offset;
            int got = readBlock(header);
            if (got == 0) {
                if (longName != null || longLink != null || pax != null) {
                    throw truncated(at, "an extended header is not followed by a member header");
                }
                endMarkerMissing = true;
                finished = true;
                return null;
            }
            if (got < 512) {
                throw truncated(at, "the stream ends inside a header (" + got + " of 512 bytes)");
            }
            if (isZero(header)) {
                if (longName != null || longLink != null || pax != null) {
                    throw truncated(at, "an extended header is followed by the end of the archive");
                }
                int second = readBlock(header);
                if (second == 512 && isZero(header)) {
                    endMarkerSeen = true;
                } else {
                    loneZeroBlock = true;       // GNU tar stops here too; anything after is ignored
                }
                finished = true;
                return null;
            }
            if (!checksumValid(header)) {
                throw new UnarchiveException(UnarchiveException.Rule.BAD_CHECKSUM,
                        "tar header at offset " + at + " fails its checksum (not a tar, or corrupted)");
            }
            boolean posix = isPosixMagic(header);
            char flag = (char) (header[156] & 0xFF);
            long size = number(header, 124, 12, at, "size");
            if (size < 0) {
                throw new UnarchiveException(UnarchiveException.Rule.NEGATIVE_SIZE,
                        "tar header at offset " + at + " declares a negative size");
            }
            if (flag == 'L' || flag == 'K') {
                String v = cString(readExtended(size, at), at, flag == 'L' ? "GNU long name" : "GNU long link name");
                if (flag == 'L') longName = v; else longLink = v;
                continue;
            }
            if (flag == 'x') {
                pax = parsePax(readExtended(size, at), at);
                paxHeaderAt = at;
                continue;
            }
            if (flag == 'g') {
                globalKeys.addAll(parsePax(readExtended(size, at), at).keySet());
                continue;
            }
            if (flag == 'V') {
                volumeLabels++;
                skipExactly(padded(size), at);
                continue;
            }
            Type type = typeOf(flag, at);

            String name;
            String paxPath = pax == null ? null : pax.get("path");
            if (paxPath != null && longName != null) {
                throw new UnarchiveException(UnarchiveException.Rule.AMBIGUOUS_NAME,
                        "tar member at offset " + at + " has both a pax path (header at " + paxHeaderAt
                                + ") and a GNU long name; which is its name cannot be decided");
            }
            if (paxPath != null) {
                name = paxPath;
            } else if (longName != null) {
                name = longName;
            } else {
                String base = field(header, 0, 100, at, "name");
                String prefix = posix ? field(header, 345, 155, at, "prefix") : "";
                name = prefix.isEmpty() ? base : prefix + "/" + base;
            }
            String link;
            String paxLink = pax == null ? null : pax.get("linkpath");
            if (paxLink != null) link = paxLink;
            else if (longLink != null) link = longLink;
            else link = field(header, 157, 100, at, "link name");

            if (pax != null && pax.containsKey("size")) {
                size = paxNumber(pax.get("size"), at, "size");
            }
            long mtime = pax != null && pax.containsKey("mtime")
                    ? paxSeconds(pax.get("mtime"), at)
                    : number(header, 136, 12, at, "mtime");
            if (pax != null) {
                for (String k : pax.keySet()) {
                    if (!k.equals("path") && !k.equals("linkpath") && !k.equals("size") && !k.equals("mtime")) {
                        ignoredPaxKeys.add(k);
                    }
                }
            }
            remaining = size;
            padding = padded(size) - size;
            return new Entry(name, link.isEmpty() ? null : link, type, flag, size, mtime, at);
        }
    }

    /**
     * The payload of the member last returned by {@link #next()}: exactly its declared size, and a
     * stream ending early is refused as truncated. Closing it does nothing.
     */
    public InputStream payload() {
        return payload;
    }

    /** True when the archive ended with the two zero blocks. */
    public boolean endMarkerSeen() {
        return endMarkerSeen;
    }

    /** True when the stream ended on a header boundary with no zero block at all. */
    public boolean endMarkerMissing() {
        return endMarkerMissing;
    }

    /** True when one zero block was followed by the end of the stream or by non-zero data. */
    public boolean loneZeroBlock() {
        return loneZeroBlock;
    }

    /** Keys seen in pax global headers, for one log line per archive. */
    public TreeSet<String> globalKeys() {
        return globalKeys;
    }

    /** pax keys present on some entry and not applied (uid, uname, atime, ...). */
    public TreeSet<String> ignoredPaxKeys() {
        return ignoredPaxKeys;
    }

    public int volumeLabels() {
        return volumeLabels;
    }

    // ------------------------------------------------------------------ header helpers, shared

    /** The unsigned sum as POSIX defines it; the signed sum is accepted too, as GNU tar does. */
    static boolean checksumValid(byte[] h) {
        long stored;
        try {
            String s = rawField(h, 148, 8).trim();
            if (s.isEmpty()) return false;
            stored = Long.parseLong(s, 8);
        } catch (NumberFormatException e) {
            return false;
        }
        long unsigned = 0;
        long signed = 0;
        for (int i = 0; i < 512; i++) {
            if (i >= 148 && i < 156) {
                unsigned += ' ';
                signed += ' ';
            } else {
                unsigned += h[i] & 0xFF;
                signed += h[i];
            }
        }
        return stored == unsigned || stored == signed;
    }

    /** POSIX: {@code ustar\0} followed by version {@code 00}. */
    static boolean isPosixMagic(byte[] h) {
        return h[257] == 'u' && h[258] == 's' && h[259] == 't' && h[260] == 'a' && h[261] == 'r'
                && h[262] == 0 && h[263] == '0' && h[264] == '0';
    }

    /** GNU: {@code ustar␠} followed by {@code ␠\0} (measured on GNU tar 1.35's default output). */
    static boolean isGnuMagic(byte[] h) {
        return h[257] == 'u' && h[258] == 's' && h[259] == 't' && h[260] == 'a' && h[261] == 'r'
                && h[262] == ' ' && h[263] == ' ' && h[264] == 0;
    }

    // ------------------------------------------------------------------ internals

    private Type typeOf(char flag, long at) throws UnarchiveException {
        switch (flag) {
            case '0': case 0: case '7': return Type.FILE;
            case '5': return Type.DIRECTORY;
            case '1': return Type.HARDLINK;
            case '2': return Type.SYMLINK;
            case '3': return Type.CHAR_DEVICE;
            case '4': return Type.BLOCK_DEVICE;
            case '6': return Type.FIFO;
            case 'S':
                throw unsupported(flag, at, "a GNU sparse file: its payload is a sparse map, not the file");
            case 'M':
                throw unsupported(flag, at, "the continuation of a multi-volume archive");
            case 'D':
                throw unsupported(flag, at, "a GNU incremental dump directory");
            default:
                throw unsupported(flag, at, "a type this step does not know");
        }
    }

    private static UnarchiveException unsupported(char flag, long at, String what) {
        return new UnarchiveException(UnarchiveException.Rule.UNSUPPORTED_ENTRY_TYPE,
                "tar member at offset " + at + " has typeflag '" + (flag == 0 ? "\\0" : String.valueOf(flag))
                        + "', " + what);
    }

    /** Octal (NUL/space terminated) or GNU base-256 (high bit of the first byte set). */
    static long number(byte[] h, int off, int len, long at, String what) throws UnarchiveException {
        if ((h[off] & 0x80) != 0) {
            if ((h[off] & 0x40) != 0) {
                return -1;                                   // negative base-256
            }
            long v = h[off] & 0x3F;
            for (int i = 1; i < len; i++) {
                if ((v >>> 55) != 0) {
                    throw new UnarchiveException(UnarchiveException.Rule.BAD_NUMBER,
                            "tar header at offset " + at + ": " + what + " does not fit in 63 bits");
                }
                v = (v << 8) | (h[off + i] & 0xFF);
            }
            return v;
        }
        String s = rawField(h, off, len).trim();
        if (s.isEmpty()) return 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '7') {
                throw new UnarchiveException(UnarchiveException.Rule.BAD_NUMBER,
                        "tar header at offset " + at + ": " + what + " is not octal: '" + EntryName.printable(s) + "'");
            }
        }
        if (s.length() > 21) {
            throw new UnarchiveException(UnarchiveException.Rule.BAD_NUMBER,
                    "tar header at offset " + at + ": " + what + " has too many digits");
        }
        return Long.parseLong(s, 8);
    }

    private static String rawField(byte[] h, int off, int len) {
        int n = 0;
        while (n < len && h[off + n] != 0) n++;
        return new String(h, off, n, StandardCharsets.ISO_8859_1);
    }

    /** A name field, decoded as strict UTF-8 up to the first NUL. */
    private static String field(byte[] h, int off, int len, long at, String what) throws UnarchiveException {
        int n = 0;
        while (n < len && h[off + n] != 0) n++;
        return strictUtf8(h, off, n, at, what);
    }

    private static String cString(byte[] b, long at, String what) throws UnarchiveException {
        int n = 0;
        while (n < b.length && b[n] != 0) n++;
        return strictUtf8(b, 0, n, at, what);
    }

    static String strictUtf8(byte[] b, int off, int n, long at, String what) throws UnarchiveException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b, off, n)).toString();
        } catch (CharacterCodingException e) {
            throw new UnarchiveException(UnarchiveException.Rule.BAD_NAME_ENCODING,
                    "tar header at offset " + at + ": the " + what + " is not valid UTF-8");
        }
    }

    /** pax records: {@code "<len> <key>=<value>\n"}, len counting the whole record in bytes. */
    static Map<String, String> parsePax(byte[] b, long at) throws UnarchiveException {
        Map<String, String> out = new LinkedHashMap<String, String>();
        int p = 0;
        while (p < b.length) {
            if (b[p] == 0) break;                          // tolerated trailing padding
            int sp = p;
            while (sp < b.length && b[sp] >= '0' && b[sp] <= '9') sp++;
            if (sp == p || sp >= b.length || b[sp] != ' ' || sp - p > 9) {
                throw badPax(at, "a record does not start with '<length> '");
            }
            int len = Integer.parseInt(new String(b, p, sp - p, StandardCharsets.US_ASCII));
            int end = p + len;
            if (len <= sp - p + 1 || end > b.length || b[end - 1] != '\n') {
                throw badPax(at, "a record's length does not match its content");
            }
            int eq = -1;
            for (int i = sp + 1; i < end - 1; i++) {
                if (b[i] == '=') {
                    eq = i;
                    break;
                }
            }
            if (eq < 0 || eq == sp + 1) {
                throw badPax(at, "a record has no 'key='");
            }
            String key = strictUtf8(b, sp + 1, eq - sp - 1, at, "pax key");
            String value = strictUtf8(b, eq + 1, end - 1 - (eq + 1), at, "pax " + key + " value");
            out.put(key, value);
            p = end;
        }
        return out;
    }

    private static UnarchiveException badPax(long at, String why) {
        return new UnarchiveException(UnarchiveException.Rule.BAD_EXTENDED_HEADER,
                "pax header at offset " + at + ": " + why);
    }

    private static long paxNumber(String v, long at, String what) throws UnarchiveException {
        try {
            long n = Long.parseLong(v.trim());
            if (n < 0) {
                throw new UnarchiveException(UnarchiveException.Rule.NEGATIVE_SIZE,
                        "pax header before offset " + at + " declares a negative " + what);
            }
            return n;
        } catch (NumberFormatException e) {
            throw badPax(at, what + " is not a decimal number: '" + EntryName.printable(v) + "'");
        }
    }

    /** pax mtime may be fractional ({@code 1727600000.123456789}); the fraction is dropped. */
    private static long paxSeconds(String v, long at) throws UnarchiveException {
        String s = v.trim();
        int dot = s.indexOf('.');
        String whole = dot < 0 ? s : s.substring(0, dot);
        try {
            return Long.parseLong(whole);
        } catch (NumberFormatException e) {
            throw badPax(at, "mtime is not a number: '" + EntryName.printable(v) + "'");
        }
    }

    private byte[] readExtended(long size, long at) throws IOException {
        if (size > MAX_EXTENDED) {
            throw new UnarchiveException(UnarchiveException.Rule.OVERSIZED_EXTENDED_HEADER,
                    "extended header at offset " + at + " declares " + size + " bytes (limit " + MAX_EXTENDED + ")");
        }
        byte[] b = new byte[(int) size];
        int got = readFully(b, 0, b.length);
        if (got < b.length) throw truncated(at, "the stream ends inside an extended header");
        skipExactly(padded(size) - size, at);
        return b;
    }

    private void drain() throws IOException {
        if (remaining > 0 || padding > 0) {
            long at = offset;
            skipExactly(remaining + padding, at);
            remaining = 0;
            padding = 0;
        }
    }

    private void skipExactly(long n, long at) throws IOException {
        byte[] sink = new byte[8192];
        long left = n;
        while (left > 0) {
            int r = in.read(sink, 0, (int) Math.min(left, sink.length));   // never trust skip()
            if (r < 0) throw truncated(at, "the stream ends inside a member's data or padding");
            left -= r;
            offset += r;
        }
    }

    /** Reads up to 512 bytes; returns how many (0 = end of stream at a block boundary). */
    private int readBlock(byte[] b) throws IOException {
        return readFully(b, 0, 512);
    }

    private int readFully(byte[] b, int off, int len) throws IOException {
        int n = 0;
        while (n < len) {
            int r = in.read(b, off + n, len - n);
            if (r < 0) break;
            n += r;
        }
        offset += n;
        return n;
    }

    private static long padded(long size) {
        return ((size + 511) / 512) * 512;
    }

    private static boolean isZero(byte[] b) {
        for (byte x : b) {
            if (x != 0) return false;
        }
        return true;
    }

    private static UnarchiveException truncated(long at, String why) {
        return new UnarchiveException(UnarchiveException.Rule.TRUNCATED, "tar at offset " + at + ": " + why);
    }

    /** Exactly the declared bytes of the current member, then EOF. */
    private final class Payload extends InputStream {
        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int r = read(one, 0, 1);
            return r < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) return -1;
            int want = (int) Math.min(len, remaining);
            int r = in.read(b, off, want);
            if (r < 0) throw truncated(offset, "the stream ends inside a member's data");
            remaining -= r;
            offset += r;
            return r;
        }

        @Override
        public void close() {
        }
    }
}
