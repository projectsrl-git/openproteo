package com.legalarchive.orchestrator.unarchive;

import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/**
 * gzip handling (spec section 4.3).
 *
 * <ul>
 * <li>Multi-member streams read as one, as {@code gzip -dc} does ({@code GZIPInputStream},
 *     measured). The trailer CRC and length are checked by the JDK.</li>
 * <li>The compressed bytes consumed are COUNTED underneath the decompressor, so the ratio limit
 *     (section 7) is computed on what was actually read, not on anything the file declares.</li>
 * <li>The first decompressed block is sniffed to tell a tar from a single file, under the
 *     intersection rule in {@link ArchiveFormat#innerIsTar}.</li>
 * <li>The FNAME header is never used for the output name ({@link ArchiveFormat#innerNameOfGzip}).</li>
 * </ul>
 */
public final class GzipSupport {

    /** Counts bytes read through it. */
    public static final class Counting extends FilterInputStream {
        private long count;

        public Counting(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int r = super.read();
            if (r >= 0) count++;
            return r;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int r = super.read(b, off, len);
            if (r > 0) count += r;
            return r;
        }

        /** Skips by reading, so the count stays exact and {@code skip()} is never trusted. */
        @Override
        public long skip(long n) throws IOException {
            byte[] sink = new byte[(int) Math.min(8192, Math.max(1, n))];
            long left = n;
            while (left > 0) {
                int r = read(sink, 0, (int) Math.min(left, sink.length));
                if (r < 0) break;
                left -= r;
            }
            return n - left;
        }

        @Override
        public boolean markSupported() {
            return false;
        }

        public long count() {
            return count;
        }
    }

    /** An opened gzip: the decompressed stream, the counter under it, and what it contains. */
    public static final class Opened {
        public final InputStream decompressed;
        public final Counting compressedCounter;
        public final boolean innerIsTar;

        Opened(InputStream decompressed, Counting compressedCounter, boolean innerIsTar) {
            this.decompressed = decompressed;
            this.compressedCounter = compressedCounter;
            this.innerIsTar = innerIsTar;
        }
    }

    private GzipSupport() {
    }

    /**
     * Opens {@code raw} (positioned at the gzip magic) and decides tar vs single file.
     * {@code handling} must be one of the three gzip handlings of {@link ArchiveFormat.Decision}.
     * Intersection with the explicit formats: {@code GZIP_TAR} ({@code format=tar.gz}) REQUIRES a tar
     * inside and refuses otherwise; {@code GZIP_SINGLE} ({@code format=gz}) never unpacks, even a tar
     * - the author asked for decompression only; {@code GZIP_AUTO} follows the sniff.
     */
    public static Opened open(InputStream raw, String archiveName, ArchiveFormat.Handling handling) throws IOException {
        Counting counter = new Counting(raw);
        BufferedInputStream d = new BufferedInputStream(new GZIPInputStream(counter, 65536), 65536);
        d.mark(ArchiveFormat.HEAD_BYTES);
        byte[] head = new byte[ArchiveFormat.HEAD_BYTES];
        int n = 0;
        while (n < head.length) {
            int r = d.read(head, n, head.length - n);
            if (r < 0) break;
            n += r;
        }
        d.reset();
        boolean tar;
        switch (handling) {
            case GZIP_TAR: {
                ArchiveFormat.Kind k = ArchiveFormat.sniff(head, n);
                tar = k == ArchiveFormat.Kind.TAR_POSIX || k == ArchiveFormat.Kind.TAR_GNU
                        || k == ArchiveFormat.Kind.TAR_V7 || k == ArchiveFormat.Kind.TAR_EMPTY;
                if (!tar) {
                    throw new UnarchiveException(UnarchiveException.Rule.FORMAT_MISMATCH,
                            "format=tar.gz but the content of " + archiveName + " is not a tar");
                }
                break;
            }
            case GZIP_SINGLE:
                tar = false;
                break;
            case GZIP_AUTO:
                tar = ArchiveFormat.innerIsTar(archiveName, head, n);
                break;
            default:
                throw new IllegalArgumentException("not a gzip handling: " + handling);
        }
        return new Opened(d, counter, tar);
    }
}
