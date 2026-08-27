package com.legalarchive.orchestrator.tiff;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

/**
 * Walks a directory of TIFFs and reports what compression they carry.
 *
 * <b>The sample is the deliverable, not a preview.</b> Whether recompression is worth building is a
 * proportion, so how the sample is drawn matters more than how large it is - see {@link Order}.
 *
 * <b>Enumeration is lazy.</b> {@code File.list()} materialises a String for every entry before the
 * first file is opened, which on the measured store - {@code /Proteo/DOC/PDF} and
 * {@code /Proteo/DOC/TIFF} hold three million files between them - is three million strings.
 * {@link DirectoryStream} iterates instead.
 *
 * Read-only, and asserted to be by {@code tools/scan_tiff_readonly.js}.
 */
public final class TiffScan {

    /** How the files to open are chosen out of what the walk enumerates. */
    public enum Order {
        /**
         * A reservoir sample over one full lazy enumeration. Unbiased, bounded memory, and it opens
         * exactly as many files as {@link #DIRECTORY} does. The default, because the number this scan
         * produces only means something as a proportion of the corpus.
         */
        RESERVOIR,
        /**
         * The first N entries the filesystem hands back, stopping the walk there.
         *
         * Faster, and biased in the one way that matters here: directory order tracks creation order,
         * so the first N are one feed, from one source system, scanned in one period by one generation
         * of hardware - the very things that decide which compression a file carries. Available
         * because on a slow share a truncated walk may be the only affordable one, but a proportion
         * drawn this way is a proportion of that corner and not of the store.
         */
        DIRECTORY
    }

    public static final class Options {
        public File directory;
        public boolean recursive = false;
        /** How many files are OPENED. 0 means no limit. */
        public long maxFilesScanned = 1000;
        public Order scanOrder = Order.RESERVOIR;
        /** 0 means pick one and report it, so a surprising result can be reproduced exactly. */
        public long sampleSeed = 0;
    }

    private TiffScan() { }

    public static TiffScanReport run(Options o, Consumer<String> log) throws IOException {
        if (o.directory == null || !o.directory.isDirectory()) {
            throw new IOException("tiffcompress: not a directory: "
                    + (o.directory == null ? "<null>" : o.directory.getName()));
        }

        TiffScanReport rep = new TiffScanReport();
        rep.directory = o.directory.getAbsolutePath();
        rep.recursive = o.recursive;
        rep.scanOrder = o.scanOrder.name();
        rep.maxFilesScanned = o.maxFilesScanned;
        rep.sampleSeed = o.sampleSeed != 0 ? o.sampleSeed : freshSeed();

        long limit = o.maxFilesScanned > 0 ? o.maxFilesScanned : Long.MAX_VALUE;
        List<File> chosen = o.scanOrder == Order.RESERVOIR
                ? reservoir(o, limit, rep)
                : firstN(o, limit, rep);

        log.accept("tiffcompress: enumerated " + rep.filesEnumerated + " entr(ies), opening "
                + chosen.size() + " by " + rep.scanOrder + " with seed " + rep.sampleSeed);

        for (int i = 0; i < chosen.size(); i++) inspect(chosen.get(i), rep);

        // Stated in the log rather than only computed: the invariant is worth nothing if nobody sees
        // it fail.
        if (!rep.outcomesSumToFilesOpened()) {
            throw new IOException("tiffcompress: the outcomes do not sum to the files opened ("
                    + rep.filesOpened + "). The scan cannot be trusted and is reported as failed.");
        }
        log.accept(rep.summary());
        return rep;
    }

    // ------------------------------------------------------------------ sampling

    /**
     * One lazy pass, keeping {@code limit} names. Every entry gets the same chance of ending up in the
     * reservoir regardless of where it sits in the enumeration, which is the entire point.
     */
    private static List<File> reservoir(Options o, long limit, TiffScanReport rep) throws IOException {
        List<File> keep = new ArrayList<File>();
        Random rnd = new Random(rep.sampleSeed);
        Walk w = new Walk(o.directory, o.recursive);
        File f;
        while ((f = w.next()) != null) {
            rep.filesEnumerated++;
            if (keep.size() < limit) {
                keep.add(f);
            } else {
                // index over everything seen so far, not over the reservoir: that is what keeps it
                // uniform rather than favouring the tail
                long j = (long) (rnd.nextDouble() * rep.filesEnumerated);
                if (j < limit) keep.set((int) j, f);
            }
        }
        return keep;
    }

    /** The first N, and the walk stops there - which is the only advantage this order has. */
    private static List<File> firstN(Options o, long limit, TiffScanReport rep) throws IOException {
        List<File> keep = new ArrayList<File>();
        Walk w = new Walk(o.directory, o.recursive);
        File f;
        while (keep.size() < limit && (f = w.next()) != null) {
            rep.filesEnumerated++;
            keep.add(f);
        }
        return keep;
    }

    /** A lazy walk. Directories are queued; a stream is held open for one directory at a time. */
    private static final class Walk {
        private final Deque<Path> dirs = new ArrayDeque<Path>();
        private final boolean recursive;
        private DirectoryStream<Path> stream;
        private java.util.Iterator<Path> it;

        Walk(File root, boolean recursive) {
            this.recursive = recursive;
            dirs.add(root.toPath());
        }

        File next() throws IOException {
            while (true) {
                if (it == null) {
                    if (dirs.isEmpty()) return null;
                    stream = Files.newDirectoryStream(dirs.poll());
                    it = stream.iterator();
                }
                if (!it.hasNext()) {
                    stream.close();
                    stream = null;
                    it = null;
                    continue;
                }
                Path p = it.next();
                File f = p.toFile();
                if (f.isDirectory()) {
                    if (recursive) dirs.add(p);
                    continue;
                }
                if (f.isFile()) return f;
            }
        }
    }

    // ------------------------------------------------------------------ one file

    private static void inspect(File f, TiffScanReport rep) {
        long len = f.length();
        rep.filesOpened++;
        rep.bytesScanned += len;

        TiffInfo.Result r = TiffInfo.read(f);
        switch (r.outcome) {
            case TIFF:       rep.filesTiff++;       break;
            case BIG_TIFF:   rep.filesBigTiff++;    rep.row("BigTIFF (not parsed)").files++;
                             rep.row("BigTIFF (not parsed)").bytes += len; return;
            case NOT_TIFF:   rep.filesNotTiff++;    rep.row("not a TIFF").files++;
                             rep.row("not a TIFF").bytes += len; return;
            case TRUNCATED:  rep.filesTruncated++;  rep.row("truncated").files++;
                             rep.row("truncated").bytes += len; return;
            case IFD_LOOP:   rep.filesIfdLoop++;    rep.row("IFD loop").files++;
                             rep.row("IFD loop").bytes += len; return;
            case UNREADABLE: rep.filesUnreadable++; rep.row("unreadable").files++;
                             rep.row("unreadable").bytes += len; return;
            default:         return;
        }

        // A file whose pages disagree is its own row. Charging it to its first page's codec would put
        // a number in the histogram that no single page justifies.
        String label;
        if (r.mixedCompression()) {
            rep.filesMixedCompression++;
            StringBuilder b = new StringBuilder("MIXED:");
            List<Integer> cs = r.compressions();
            for (int i = 0; i < cs.size(); i++) {
                if (i > 0) b.append('+');
                b.append(TiffInfo.compressionName(cs.get(i).intValue()));
            }
            label = b.toString();
        } else {
            label = TiffInfo.compressionName(r.pages.get(0).compression);
        }

        TiffScanReport.Row row = rep.row(label);
        row.files++;
        row.pages += r.pages.size();
        row.bytes += len;

        boolean alreadyG4 = !r.mixedCompression() && r.pages.get(0).compression == 4;
        if (alreadyG4) {
            rep.filesAlreadyG4++;
            rep.bytesAlreadyG4 += len;
        } else if (r.g4Eligible()) {
            rep.filesG4Eligible++;
            rep.bytesG4Eligible += len;
            row.g4EligibleFiles++;
            row.g4EligibleBytes += len;
        }
    }

    private static long freshSeed() {
        long s = System.nanoTime();
        return s == 0 ? 1 : s;
    }
}
