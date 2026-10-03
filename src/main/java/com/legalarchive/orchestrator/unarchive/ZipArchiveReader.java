package com.legalarchive.orchestrator.unarchive;

import java.io.Closeable;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Reads a zip through {@link ZipFile} - the central directory, as unzip, 7-Zip and Explorer do -
 * with three things added that the JDK does not do (spec section 4.2):
 * <ol>
 * <li><b>Names decided here</b>, from raw bytes, by four rules in order: flag bit 11 → UTF-8;
 *     a {@code 0x7075} Unicode-path extra whose CRC matches the raw name → its UTF-8; raw bytes that
 *     are strictly valid UTF-8 → UTF-8 (Info-ZIP on Linux writes UTF-8 with no flag, measured);
 *     otherwise the legacy charset, {@code IBM850} by default. A forced charset replaces rules 2-4
 *     for unflagged names; <b>∩ I20</b>: it never overrides bit 11.</li>
 * <li><b>External attributes</b> from {@link ZipCentralDirectory}: a Unix symlink is reported as
 *     one instead of reading as a small file holding its target (measured with {@code zip -y}).</li>
 * <li><b>CRC-32 checked</b> while the entry streams: {@code ZipFile} does not (measured - a flipped
 *     payload byte is read silently).</li>
 * </ol>
 * The JDK's entry list and ours must agree on the count and, entry by entry, on the name - else the
 * archive is refused. Only after that are they paired by position.
 *
 * <p><b>∩ I28</b>: an entry is a directory if and only if its name ends in a separator. Attributes
 * decide only the Unix special types; a DOS directory bit on a name without a trailing separator does
 * not make it a directory.
 */
public final class ZipArchiveReader implements Closeable {

    /** How an entry's name was decoded; counted per archive for the step log. */
    public enum NameRule { UTF8_FLAG, UNICODE_PATH_EXTRA, VALID_UTF8, LEGACY_CHARSET, FORCED_CHARSET }

    /** What an entry is. */
    public enum Type { FILE, DIRECTORY, SYMLINK, SPECIAL }

    /** One entry, decided. */
    public static final class Entry {
        public final String name;
        public final Type type;
        public final NameRule nameRule;
        public final long size;
        public final long compressedSize;
        public final long mtimeMillis;
        final ZipEntry jdk;
        final ZipCentralDirectory.Record record;

        Entry(String name, Type type, NameRule nameRule, ZipEntry jdk, ZipCentralDirectory.Record record) {
            this.name = name;
            this.type = type;
            this.nameRule = nameRule;
            this.size = record.size;
            this.compressedSize = record.compressedSize;
            this.mtimeMillis = jdk.getLastModifiedTime() == null ? -1 : jdk.getLastModifiedTime().toMillis();
            this.jdk = jdk;
            this.record = record;
        }
    }

    private final File file;
    private final ZipFile zip;
    private final List<Entry> entries;
    private final int[] ruleCounts = new int[NameRule.values().length];

    /**
     * @param forced a charset for every unflagged name, or null for the four-rule decision
     * @param legacy the charset of rule 4 (IBM850 by default in the executor)
     */
    public ZipArchiveReader(File file, Charset forced, Charset legacy) throws IOException {
        this.file = file;
        List<ZipCentralDirectory.Record> recs = ZipCentralDirectory.read(file);
        // Checked on OUR records, before ZipFile is opened: JDK 21 refuses such an archive already in
        // the constructor ("invalid CEN header (encrypted entry)", measured) while Java 8's native zip
        // code does not - so the same file would otherwise fail under a different rule on each JDK.
        for (int i = 0; i < recs.size(); i++) {
            ZipCentralDirectory.Record r = recs.get(i);
            if (r.encrypted()) {
                throw new UnarchiveException(UnarchiveException.Rule.ZIP_ENCRYPTED, file.getName()
                        + ": entry " + i + " is encrypted");
            }
            if (r.method != 0 && r.method != 8) {
                throw new UnarchiveException(UnarchiveException.Rule.ZIP_METHOD, file.getName() + ": entry "
                        + i + " uses compression method " + r.method + methodName(r.method)
                        + "; Java 8 reads only STORED (0) and DEFLATED (8) - recompress with deflate");
            }
        }
        ZipFile z;
        try {
            // ISO-8859-1 makes the JDK's names of unflagged entries a bijection of their raw bytes,
            // so they can be compared with ours; flagged names it decodes as UTF-8 regardless.
            z = new ZipFile(file, StandardCharsets.ISO_8859_1);
        } catch (ZipException e) {
            throw new UnarchiveException(UnarchiveException.Rule.ZIP_STRUCTURE, file.getName() + ": " + e.getMessage());
        }
        this.zip = z;
        try {
            List<ZipEntry> jdk = new ArrayList<ZipEntry>();
            for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) jdk.add(en.nextElement());
            if (jdk.size() != recs.size()) {
                throw new UnarchiveException(UnarchiveException.Rule.ZIP_STRUCTURE, file.getName()
                        + ": the JDK reads " + jdk.size() + " entries and the central directory holds " + recs.size());
            }
            List<Entry> out = new ArrayList<Entry>(recs.size());
            for (int i = 0; i < recs.size(); i++) {
                ZipCentralDirectory.Record r = recs.get(i);
                ZipEntry je = jdk.get(i);
                String jdkView = r.utf8Flag() ? decodeLenient(r.rawName, StandardCharsets.UTF_8)
                        : new String(r.rawName, StandardCharsets.ISO_8859_1);
                if (!jdkView.equals(je.getName())) {
                    throw new UnarchiveException(UnarchiveException.Rule.ZIP_STRUCTURE, file.getName()
                            + ": entry " + i + " is named differently by the JDK and by the central directory");
                }
                NameRule rule;
                String name;
                if (r.utf8Flag()) {
                    rule = NameRule.UTF8_FLAG;
                    name = strict(r.rawName, StandardCharsets.UTF_8, i);
                } else if (forced != null) {
                    rule = NameRule.FORCED_CHARSET;
                    name = new String(r.rawName, forced);
                } else {
                    String up = unicodePath(r);
                    if (up != null) {
                        rule = NameRule.UNICODE_PATH_EXTRA;
                        name = up;
                    } else {
                        String u = tryUtf8(r.rawName);
                        if (u != null) {
                            rule = NameRule.VALID_UTF8;
                            name = u;
                        } else {
                            rule = NameRule.LEGACY_CHARSET;
                            name = new String(r.rawName, legacy);
                        }
                    }
                }
                ruleCounts[rule.ordinal()]++;
                out.add(new Entry(name, typeOf(name, r), rule, je, r));
            }
            this.entries = Collections.unmodifiableList(out);
        } catch (IOException e) {
            z.close();
            throw e;
        }
    }

    public List<Entry> entries() {
        return entries;
    }

    /** How many names each rule decided, for one log line per archive. */
    public int count(NameRule r) {
        return ruleCounts[r.ordinal()];
    }

    /** Sum of the declared uncompressed sizes: used ONLY to refuse early, never as a limit. */
    public long declaredTotal() {
        long t = 0;
        for (Entry e : entries) t += e.size;
        return t;
    }

    /**
     * The entry's bytes; the stream throws {@code BAD_CRC} at its end if the CRC-32 of what it
     * delivered differs from the central directory's, and {@code TRUNCATED} if it delivered a
     * different number of bytes than declared.
     */
    public InputStream open(Entry e) throws IOException {
        final InputStream in = zip.getInputStream(e.jdk);
        final long expectCrc = e.record.crc;
        final long expectSize = e.record.size;
        final String name = e.name;
        return new FilterInputStream(in) {
            final CRC32 crc = new CRC32();
            long n;
            boolean checked;

            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                int r = read(one, 0, 1);
                return r < 0 ? -1 : one[0] & 0xFF;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int r = super.read(b, off, len);
                if (r > 0) {
                    crc.update(b, off, r);
                    n += r;
                } else if (r < 0 && !checked) {
                    checked = true;
                    if (n != expectSize) {
                        throw new UnarchiveException(UnarchiveException.Rule.TRUNCATED, file.getName() + ": '"
                                + name + "' delivered " + n + " bytes, the directory declares " + expectSize);
                    }
                    if (crc.getValue() != expectCrc) {
                        throw new UnarchiveException(UnarchiveException.Rule.BAD_CRC, file.getName() + ": '"
                                + name + "' fails its CRC-32 (corrupted)");
                    }
                }
                return r;
            }

            @Override
            public long skip(long k) throws IOException {
                throw new IOException("skip is not supported: the CRC must see every byte");
            }
        };
    }

    @Override
    public void close() throws IOException {
        zip.close();
    }

    private static Type typeOf(String name, ZipCentralDirectory.Record r) {
        if (name.endsWith("/") || name.endsWith("\\")) return Type.DIRECTORY;
        int t = r.unixType();
        if (t == ZipCentralDirectory.S_IFLNK) return Type.SYMLINK;
        if (t > 0 && t != ZipCentralDirectory.S_IFREG && t != ZipCentralDirectory.S_IFDIR) return Type.SPECIAL;
        return Type.FILE;
    }

    /** Rule 2: extra 0x7075 = version 1, CRC-32 of the raw name, UTF-8 name. Stale CRC → ignored. */
    static String unicodePath(ZipCentralDirectory.Record r) {
        byte[] x = r.extraField(0x7075);
        if (x == null || x.length < 5 || x[0] != 1) return null;
        long stored = ZipCentralDirectory.u32(x, 1);
        CRC32 c = new CRC32();
        c.update(r.rawName);
        if (c.getValue() != stored) return null;
        byte[] u = new byte[x.length - 5];
        System.arraycopy(x, 5, u, 0, u.length);
        return tryUtf8(u);
    }

    static String tryUtf8(byte[] b) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private String strict(byte[] b, Charset cs, int i) throws UnarchiveException {
        String s = tryUtf8(b);
        if (s == null) {
            throw new UnarchiveException(UnarchiveException.Rule.BAD_NAME_ENCODING, file.getName() + ": entry " + i
                    + " declares a UTF-8 name (flag bit 11) that is not valid UTF-8");
        }
        return s;
    }

    private static String decodeLenient(byte[] b, Charset cs) {
        return new String(b, cs);
    }

    private static String methodName(int m) {
        switch (m) {
            case 9: return " (DEFLATE64, written by Windows tooling for large files)";
            case 12: return " (bzip2)";
            case 14: return " (LZMA)";
            case 93: return " (zstd)";
            case 95: return " (xz)";
            case 99: return " (AES encryption)";
            default: return "";
        }
    }
}
