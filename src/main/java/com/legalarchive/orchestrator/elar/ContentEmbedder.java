package com.legalarchive.orchestrator.elar;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Embeds one content file: SHA-256 first, then Base64, both streamed.
 *
 * The template places the hash element before the content element, so the digest has to be known
 * before the payload is written. That means two sequential passes over the file - which is the same
 * number the legacy tool already performed, since {@code readFileBytes} read it once for the Base64
 * and {@code FileHashSHA256} read it again for the digest. What changes is that neither pass holds
 * the file: the legacy code buffered the whole thing in a {@code ByteArrayOutputStream}, encoded it
 * into a {@code String}, and hung that String on a DOM node, which is where the 147 MB batches and
 * the OutOfMemoryError came from.
 *
 * The digest is SHA-256 of the <b>raw file bytes</b>. That is the only convention ever executed:
 * {@code IndxBuilder.computeSHA256(String base64Content)} exists but is never called, and the
 * prototypes in the other package computed it over the Base64 text. Every INDX ELAR has accepted
 * carries the raw-bytes digest, so reproducing it is the safe move and changing it would be the risky
 * one.
 */
public final class ContentEmbedder {

    /**
     * A whole number of 3-byte groups, so every full buffer encodes to complete quads with no padding
     * and the encoder never has to carry state between chunks. Padding appears once, on the last
     * partial group, which is exactly where Base64 puts it.
     */
    private static final int CHUNK = 3 * 16384;

    /** The file changed under us between the two passes. The batch is already partly written. */
    public static final class ContentChangedException extends IOException {
        public ContentChangedException(String m) { super(m); }
    }

    /** What was captured before the digest pass, and must still hold after the encode pass. */
    public static final class Stamp {
        public final long length;
        public final long modified;
        Stamp(long l, long m) { this.length = l; this.modified = m; }
    }

    private ContentEmbedder() { }

    public static Stamp stamp(ContentStore store, String resolved) throws IOException {
        return new Stamp(store.length(resolved), store.lastModified(resolved));
    }

    /**
     * Pass one: the digest, in hex lower case, as {@code FileHashSHA256} produced it.
     * Reads in blocks and never holds the file.
     */
    public static String sha256Hex(ContentStore store, String resolved) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IOException("SHA-256 is not available in this JVM", e);
        }
        InputStream in = store.open(resolved);
        try {
            byte[] buf = new byte[CHUNK];
            int r;
            while ((r = in.read(buf)) > 0) md.update(buf, 0, r);
        } finally {
            in.close();
        }
        return hex(md.digest());
    }

    /**
     * Pass two: Base64 straight to the writer, which breaks lines at quad boundaries.
     *
     * @return the number of source bytes encoded
     * @throws ContentChangedException when the file no longer matches the stamp taken before the
     *         digest pass. By this point the hash element and most of the payload are already in the
     *         output stream and cannot be retracted, so the caller must discard the whole batch - an
     *         INDX carrying a digest that does not match its own embedded content is worse than a
     *         failed one. Detected during the pass rather than after it, so the failure is immediate.
     */
    public static long encodeBase64(WrappingXmlOut out, String qname, ContentStore store,
                                    String resolved, Stamp before) throws IOException {
        Base64.Encoder enc = Base64.getEncoder();
        InputStream in = store.open(resolved);
        long total = 0;
        try {
            byte[] buf = new byte[CHUNK];
            while (true) {
                int filled = fill(in, buf);
                if (filled <= 0) break;
                total += filled;
                if (before != null && total > before.length) {
                    throw new ContentChangedException(changed(store, resolved, before,
                            "it has grown past " + before.length + " bytes while being read"));
                }
                if (filled == buf.length) {
                    // a whole number of 3-byte groups: complete quads, no padding, no carried state
                    out.base64Chunk(enc.encodeToString(buf));
                } else {
                    byte[] tail = new byte[filled];
                    System.arraycopy(buf, 0, tail, 0, filled);
                    out.base64Chunk(enc.encodeToString(tail));   // the only chunk that may be padded
                    break;
                }
            }
        } finally {
            in.close();
        }
        if (before != null && (total != before.length || store.lastModified(resolved) != before.modified)) {
            throw new ContentChangedException(changed(store, resolved, before,
                    "it was " + before.length + " bytes when the digest was taken and " + total
                    + " bytes when it was encoded"));
        }
        return total;
    }

    private static String changed(ContentStore store, String resolved, Stamp before, String what) {
        return "the content file " + store.fileName(resolved) + " changed while the batch was being"
                + " written: " + what
                + ". The hash was already written, so this batch cannot be completed and is discarded;"
                + " nothing is left under a deliverable name. Re-run once the source is stable.";
    }

    /**
     * Fills the buffer completely, or as far as the file goes.
     *
     * {@code InputStream.read} may return fewer bytes than asked for, and the prototype encoder in the
     * legacy estate called it exactly once on a buffer sized to the whole file, discarding the count -
     * so any short read left the tail as zeros and Base64-encoded them silently. Looping is the fix,
     * and it also guarantees every full buffer is a whole number of 3-byte groups.
     */
    private static int fill(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int r = in.read(buf, off, buf.length - off);
            if (r < 0) break;
            off += r;
        }
        return off;
    }

    /** Base64 characters a file of this size will produce, for the byte-budget estimate. */
    public static long encodedLength(long bytes) { return ((bytes + 2) / 3) * 4; }

    static String hex(byte[] b) {
        char[] d = "0123456789abcdef".toCharArray();
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (int i = 0; i < b.length; i++) {
            sb.append(d[(b[i] >> 4) & 0xF]).append(d[b[i] & 0xF]);
        }
        return sb.toString();
    }
}
