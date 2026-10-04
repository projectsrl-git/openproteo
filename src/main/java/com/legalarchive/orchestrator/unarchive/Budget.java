package com.legalarchive.orchestrator.unarchive;

/**
 * The archive-bomb limits of spec section 7, counted on bytes WRITTEN, never on sizes an archive
 * declares.
 *
 * <p>One budget per archive. {@link #startEntry} before each entry, {@link #written} after each
 * chunk, with the compressed bytes consumed so far. The ratio is checked only once the entry (or
 * the stream) has written {@link #RATIO_GRACE} bytes - the one figure here that is chosen rather
 * than derived: below it a tiny, very compressible file would trip for no benefit.
 *
 * <p><b>∩ I21, revised in batch 2</b>: the per-entry ratio applies where an entry's compressed size
 * is KNOWN - zip, from the central directory. For a gzip stream only the whole-stream ratio applies:
 * a per-entry figure would be the compressed bytes consumed during the entry, and the decompressor's
 * read-ahead makes that meaningless (measured: 30 MB of zeros compress to ~30 KB, all of it consumed
 * before the first entry starts, so every entry read as an infinite ratio).
 */
public final class Budget {

    public static final long RATIO_GRACE = 10L * 1024 * 1024;

    private final long maxEntries;
    private final long maxEntryBytes;
    private final long maxArchiveBytes;
    private final long maxRatio;

    private long entries;
    private long archiveBytes;
    /** Bytes written since the current gzip stream started (section 22.5, ∩ I55). */
    private long streamBytes;
    private long entryBytes;
    private String entryName;

    public Budget(long maxEntries, long maxEntryBytes, long maxArchiveBytes, long maxRatio) {
        this.maxEntries = maxEntries;
        this.maxEntryBytes = maxEntryBytes;
        this.maxArchiveBytes = maxArchiveBytes;
        this.maxRatio = maxRatio;
    }

    /** Counts an entry; every entry counts, directories and skipped ones included. */
    public void startEntry(String name) throws UnarchiveException {
        entries++;
        entryName = name;
        entryBytes = 0;
        if (entries > maxEntries) {
            throw new UnarchiveException(UnarchiveException.Rule.LIMIT_ENTRIES,
                    "more than maxEntries=" + maxEntries + " entries (at '" + EntryName.printable(name) + "')");
        }
    }

    /**
     * @param n              bytes just written for the current entry
     * @param entryCompressed compressed bytes of THIS entry when known (zip: from the directory), else -1
     * @param streamCompressed compressed bytes consumed from the whole stream so far (gzip), else -1;
     *                         never used per entry (see the class comment)
     */
    public void written(long n, long entryCompressed, long streamCompressed) throws UnarchiveException {
        entryBytes += n;
        archiveBytes += n;
        streamBytes += n;
        if (entryBytes > maxEntryBytes) {
            throw new UnarchiveException(UnarchiveException.Rule.LIMIT_ENTRY_SIZE, "'" + EntryName.printable(entryName)
                    + "' passed maxEntryMb=" + (maxEntryBytes / (1024 * 1024)) + " (" + entryBytes + " bytes written)");
        }
        if (archiveBytes > maxArchiveBytes) {
            throw new UnarchiveException(UnarchiveException.Rule.LIMIT_ARCHIVE_SIZE, "the archive passed maxArchiveMb="
                    + (maxArchiveBytes / (1024 * 1024)) + " at '" + EntryName.printable(entryName) + "' (" + archiveBytes
                    + " bytes written)");
        }
        if (entryBytes > RATIO_GRACE && entryCompressed >= 0) {
            long c = entryCompressed;
            if (entryBytes > maxRatio * Math.max(c, 1)) {
                throw new UnarchiveException(UnarchiveException.Rule.LIMIT_RATIO, "'" + EntryName.printable(entryName)
                        + "' expands more than maxRatio=" + maxRatio + ":1 (" + entryBytes + " bytes from about "
                        + c + " compressed, per entry)");
            }
        }
        if (streamCompressed >= 0 && streamBytes > RATIO_GRACE && streamBytes > maxRatio * Math.max(streamCompressed, 1)) {
            throw new UnarchiveException(UnarchiveException.Rule.LIMIT_RATIO, "the stream expands more than maxRatio="
                    + maxRatio + ":1 (" + streamBytes + " bytes from " + streamCompressed + " compressed, whole stream)");
        }
    }

    /**
     * A new compressed stream begins (a nested gzip). <b>∩ I55</b>: the size limits stay cumulative
     * over the whole tree, but a stream's ratio counts only what THAT stream produced - otherwise a
     * 1 KB nested gzip met after 100 MB of legitimate data would read as 100 000:1.
     */
    public void startStream() {
        streamBytes = 0;
    }

    public long entries() {
        return entries;
    }

    public long archiveBytes() {
        return archiveBytes;
    }
}
