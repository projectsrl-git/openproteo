package com.legalarchive.orchestrator.objpack;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Streaming POSIX ustar archive writer, JDK only.
 *
 * <p>Written by hand rather than taken from a library because Java 8 has no tar anywhere in the
 * platform and a new Maven dependency cannot be confirmed against the internal Nexus from the
 * development sandbox. The format is small enough to implement correctly and, more to the point,
 * small enough to verify against a real tar.
 *
 * <p>Entries are written as they are added and the source file is streamed, so the archive costs
 * one buffer of memory and no temporary copy regardless of how large the submission is.
 *
 * <p>Deliberate restrictions, each of which makes a property of the Transarch package structural
 * rather than a thing to remember:
 * <ul>
 *   <li>Member names are <b>flat</b>: a name containing '/' is refused. The package format has no
 *       directories, and refusing the separator is what guarantees no member can arrive as
 *       "./name" or "sub/name".</li>
 *   <li>A name longer than 100 bytes is <b>refused, not truncated</b> and not split into the ustar
 *       prefix field, because the prefix can only hold a directory part and these names have
 *       none.</li>
 *   <li>Names must be printable US-ASCII, which the Transarch naming convention already
 *       requires.</li>
 * </ul>
 *
 * <p>Ownership and permissions are fixed (mode 0644, uid/gid 0, empty uname/gname) so that the same
 * inputs produce the same archive on any machine and any account. The only per-entry variable is
 * the modification time.
 */
public final class UstarWriter implements Closeable {

    /** Tar block size. Everything in the format is a multiple of this. */
    public static final int BLOCK = 512;

    /** Blocks per record. GNU tar's default, and what the end of the archive is padded to. */
    public static final int DEFAULT_BLOCKING_FACTOR = 20;

    private static final int NAME_MAX = 100;
    private static final int DEFAULT_MODE = 0644;
    private static final byte[] MAGIC = { 'u', 's', 't', 'a', 'r', 0 };
    private static final byte[] VERSION = { '0', '0' };

    private final OutputStream out;
    private final int blockingFactor;
    private final byte[] buf = new byte[64 * 1024];

    private long bytesWritten;
    private int entryCount;
    private boolean closed;

    public UstarWriter(OutputStream out) {
        this(out, DEFAULT_BLOCKING_FACTOR);
    }

    public UstarWriter(OutputStream out, int blockingFactor) {
        if (out == null) {
            throw new IllegalArgumentException("output stream is null");
        }
        if (blockingFactor < 1) {
            throw new IllegalArgumentException("blocking factor must be at least 1, got " + blockingFactor);
        }
        this.out = out;
        this.blockingFactor = blockingFactor;
    }

    /** Number of entries written so far. */
    public int entryCount() {
        return entryCount;
    }

    /** Bytes written to the underlying stream so far, including headers and padding. */
    public long bytesWritten() {
        return bytesWritten;
    }

    /**
     * Appends a file under the given member name, streaming its content.
     *
     * @return the number of content bytes written, which is the file's length
     */
    public long addFile(String name, File source) throws IOException {
        if (source == null) {
            throw new IllegalArgumentException("source file is null for member '" + name + "'");
        }
        if (!source.isFile()) {
            throw new IOException("not a regular file: " + source.getPath());
        }
        long size = source.length();
        long mtime = source.lastModified() / 1000L;
        writeHeader(name, size, mtime);
        long copied = 0;
        InputStream in = new FileInputStream(source);
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                if (copied + n > size) {
                    // The file grew while it was being read. A short or long member would corrupt
                    // every following header, so this stops here rather than writing a bad archive.
                    throw new IOException("file grew while being archived: " + source.getPath());
                }
                write(buf, 0, n);
                copied += n;
            }
        } finally {
            in.close();
        }
        if (copied != size) {
            throw new IOException("file shrank while being archived: " + source.getPath()
                    + " (declared " + size + ", read " + copied + ")");
        }
        pad(size);
        entryCount++;
        return copied;
    }

    /** Appends an in-memory member. Used for the control file, which is empty by specification. */
    public void addBytes(String name, byte[] content, long mtimeSeconds) throws IOException {
        byte[] c = content == null ? new byte[0] : content;
        writeHeader(name, c.length, mtimeSeconds);
        write(c, 0, c.length);
        pad(c.length);
        entryCount++;
    }

    /**
     * Writes the two zero blocks that end the archive and pads to the blocking factor, then closes
     * the underlying stream.
     */
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            byte[] zero = new byte[BLOCK];
            write(zero, 0, BLOCK);
            write(zero, 0, BLOCK);
            long record = (long) BLOCK * blockingFactor;
            long rem = bytesWritten % record;
            if (rem != 0) {
                long need = record - rem;
                while (need > 0) {
                    int n = (int) Math.min(need, BLOCK);
                    write(zero, 0, n);
                    need -= n;
                }
            }
            out.flush();
        } finally {
            out.close();
        }
    }

    // ---------------------------------------------------------------- internals

    private void writeHeader(String name, long size, long mtime) throws IOException {
        if (closed) {
            throw new IOException("archive is already closed");
        }
        byte[] nb = validName(name);
        if (size < 0) {
            throw new IllegalArgumentException("negative size for member '" + name + "'");
        }
        // 8 octal digits plus terminator: the largest size a ustar header can carry.
        if (size > 077777777777L) {
            throw new IOException("member too large for ustar: '" + name + "' is " + size + " bytes");
        }

        byte[] h = new byte[BLOCK];
        System.arraycopy(nb, 0, h, 0, nb.length);
        octal(h, 100, 8, DEFAULT_MODE);      // mode
        octal(h, 108, 8, 0);                 // uid
        octal(h, 116, 8, 0);                 // gid
        octal(h, 124, 12, size);             // size
        octal(h, 136, 12, mtime < 0 ? 0 : mtime);
        for (int i = 148; i < 156; i++) {    // checksum field is spaces while it is computed
            h[i] = ' ';
        }
        h[156] = '0';                        // typeflag: regular file
        System.arraycopy(MAGIC, 0, h, 257, MAGIC.length);
        System.arraycopy(VERSION, 0, h, 263, VERSION.length);
        // uname, gname, devmajor, devminor and prefix stay zero on purpose.

        int sum = 0;
        for (int i = 0; i < BLOCK; i++) {
            sum += (h[i] & 0xFF);
        }
        // Six octal digits, NUL, space: the encoding every tar implementation reads.
        String s = Integer.toOctalString(sum);
        while (s.length() < 6) {
            s = "0" + s;
        }
        for (int i = 0; i < 6; i++) {
            h[148 + i] = (byte) s.charAt(i);
        }
        h[154] = 0;
        h[155] = ' ';

        write(h, 0, BLOCK);
    }

    private static byte[] validName(String name) {
        if (name == null || name.length() == 0) {
            throw new IllegalArgumentException("member name is empty");
        }
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
            throw new IllegalArgumentException(
                    "member name must be flat, with no path separator: '" + name + "'");
        }
        if (".".equals(name) || "..".equals(name)) {
            throw new IllegalArgumentException("member name must not be '" + name + "'");
        }
        byte[] nb = new byte[name.length()];
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x21 || c > 0x7E) {
                throw new IllegalArgumentException(
                        "member name must be printable US-ASCII without spaces: '" + name + "'");
            }
            nb[i] = (byte) c;
        }
        if (nb.length > NAME_MAX) {
            throw new IllegalArgumentException("member name is " + nb.length
                    + " bytes, the ustar limit is " + NAME_MAX + ": '" + name + "'");
        }
        return nb;
    }

    /** Writes {@code value} as right-aligned zero-padded octal in {@code len - 1} digits plus NUL. */
    private static void octal(byte[] h, int off, int len, long value) {
        int digits = len - 1;
        String s = Long.toOctalString(value);
        if (s.length() > digits) {
            throw new IllegalArgumentException(
                    "value " + value + " does not fit in " + digits + " octal digits");
        }
        int pad = digits - s.length();
        for (int i = 0; i < pad; i++) {
            h[off + i] = '0';
        }
        for (int i = 0; i < s.length(); i++) {
            h[off + pad + i] = (byte) s.charAt(i);
        }
        h[off + digits] = 0;
    }

    /** Pads the last member out to a whole number of blocks. */
    private void pad(long size) throws IOException {
        int rem = (int) (size % BLOCK);
        if (rem != 0) {
            write(new byte[BLOCK - rem], 0, BLOCK - rem);
        }
    }

    private void write(byte[] b, int off, int len) throws IOException {
        out.write(b, off, len);
        bytesWritten += len;
    }
}
