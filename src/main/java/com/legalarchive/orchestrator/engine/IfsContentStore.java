package com.legalarchive.orchestrator.engine;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.legalarchive.orchestrator.elar.ContentStore;

/**
 * The {@code <ELAR:Content>} payload read from an AS/400 IFS directory instead of the local disk.
 *
 * This lives in the engine layer, not in {@code elar}, because that package is free of Spring and
 * compiles standalone under {@code --release 8} - which is what lets the whole executor be executed
 * against real files in a test rather than only inspected. JTOpen and the datasource registry are here.
 *
 * <b>The seam inside the seam.</b> Everything that touches JTOpen is behind {@link Ifs}, about thirty
 * lines of it, so the parts with decisions in them - resolution, when to list, the staging, the size
 * check, the cap - are testable with a fake. A fake cannot prove JTOpen; it can prove everything built
 * on top of it, which is where the mistakes would otherwise hide.
 */
public final class IfsContentStore implements ContentStore {

    /**
     * How a document's existence, size and modification time are found.
     *
     * <b>STAT</b> asks for each document, one round trip each, and holds one entry per document the feed
     * actually references. <b>LISTING</b> lists each parent directory once and holds every entry in it.
     *
     * Which is right is decided by the shape of the store, not by preference, and the first real feed
     * settled it: the documents live in two directories holding <b>three million files</b> between them,
     * of which a run references a few thousand. Listing them would transfer three million entries to use
     * a fraction of them and hold roughly 300 MB to do it - and would trip the entry cap on the first
     * run. A stat per document is bounded by the feed instead of by the store.
     *
     * LISTING is still right for the opposite shape: a directory of a few thousand files that a feed
     * references densely, where one round trip replaces thousands. It is no longer the default, because
     * a default that fails on the only real feed we have is the wrong way round.
     */
    public enum Lookup { STAT, LISTING }

    /** The whole of the AS/400 dependency, so the rest of this class can be tested without one. */
    public interface Ifs extends java.io.Closeable {
        /** Every file in a directory. Empty when the directory does not exist - absence is not an error. */
        List<Entry> list(String dir) throws IOException;
        /** One file, or null when it is absent or is a directory. */
        Entry stat(String path) throws IOException;
        InputStream open(String path) throws IOException;
    }

    public static final class Entry {
        public final String path;
        public final long length;
        public final long modified;
        public Entry(String path, long length, long modified) {
            this.path = path; this.length = length; this.modified = modified;
        }
    }

    private final Ifs ifs;
    private final String basePath;          // joined to a value that is not absolute; may be null
    private final File stagingDir;
    private final int maxListing;
    private final Lookup lookup;
    private final Consumer<String> log;

    private final Map<String, Entry> known = new HashMap<String, Entry>();
    private final Set<String> listedDirs = new HashSet<String>();
    private int listings = 0;
    private long staged = 0, stagedBytes = 0;

    private static final String PART_SUFFIX = ".part";
    private static final String PART_PREFIX = "elarxml-content-";
    private static final int CREATE_ATTEMPTS = 3;

    private String stagedPath;              // the document currently on local disk
    private File stagedFile;
    private long stagedSeq = 0;
    private boolean sweptStagingDir = false;
    private boolean closed;

    public IfsContentStore(Ifs ifs, String basePath, File stagingDir, int maxListing, Consumer<String> log) {
        this(ifs, basePath, stagingDir, maxListing, Lookup.STAT, log);
    }

    public IfsContentStore(Ifs ifs, String basePath, File stagingDir, int maxListing, Lookup lookup,
                           Consumer<String> log) {
        if (ifs == null) throw new IllegalArgumentException("an IFS accessor is required");
        if (stagingDir == null) throw new IllegalArgumentException("a staging directory is required");
        this.ifs = ifs;
        this.basePath = basePath == null || basePath.trim().isEmpty() ? null : trimSlash(basePath.trim());
        this.stagingDir = stagingDir;
        this.maxListing = maxListing > 0 ? maxListing : Integer.MAX_VALUE;
        this.lookup = lookup == null ? Lookup.STAT : lookup;
        this.log = log;
    }

    // ------------------------------------------------------------------ resolution

    /**
     * The column carries a full IFS path, so it is taken as it stands. A value that is not absolute is
     * joined to the configured base, which is the only thing that parameter is for.
     *
     * Deliberately NOT the local rule of taking the last segment. Locally an {@code ifscopy} step has
     * already flattened the tree into one directory, so only the file name can still be meaningful;
     * here the tree is still there and the path is the only thing that finds the file. Reducing a full
     * path to its name here would look for every document under one directory it is not in, and send
     * every row to the discards file with the documents sitting untouched on the IFS.
     */
    public String resolve(String csvValue) {
        String v = csvValue.trim().replace('\\', '/');
        if (v.startsWith("/")) return collapse(v);
        return collapse(basePath == null ? "/" + v : basePath + "/" + v);
    }

    // ------------------------------------------------------------------ metadata

    public boolean exists(String resolved) throws IOException {
        return entry(resolved) != null;
    }

    public long length(String resolved) throws IOException {
        Entry e = entry(resolved);
        return e == null ? 0L : e.length;
    }

    /**
     * From the directory listing, so it is stable for the whole run.
     *
     * That makes the embedder's modification-time half of its change check inert here, and saying so is
     * better than implying a guard that is not guarding. What DOES guard is the size: the staging step
     * compares the bytes it actually downloaded against the size the listing reported and fails if they
     * differ, and the embedder independently compares the bytes it encoded against the same figure. A
     * document rewritten on the IFS after the listing is therefore caught by its length, in two places,
     * before anything reaches a deliverable name.
     */
    public long lastModified(String resolved) throws IOException {
        Entry e = entry(resolved);
        return e == null ? 0L : e.modified;
    }

    public String fileName(String resolved) {
        int i = resolved.lastIndexOf('/');
        return i >= 0 ? resolved.substring(i + 1) : resolved;
    }

    public String describe(String resolved) { return "ifs:" + resolved; }

    /**
     * One listing per parent directory, on first demand, cached for the run.
     *
     * A stat per row would be a round trip each - thousands for one feed. Listing the parent gets every
     * document in it for the same single round trip, and the pre-scan then costs nothing. The
     * degenerate case needs no special handling: documents scattered one per directory make each listing
     * return one entry, which is exactly the cost of the stat it replaces. That is why this is lazy and
     * per-parent rather than a set of directories collected up front - the shape of the feed decides,
     * and no guard has to guess.
     */
    private Entry entry(String path) throws IOException {
        Entry cached = known.get(path);
        if (cached != null) return cached;

        if (lookup == Lookup.STAT) {
            // one round trip, and the map grows with the FEED rather than with the store behind it
            Entry direct = ifs.stat(path);
            if (direct != null) {
                if (known.size() >= maxListing) throw tooMany(parentOf(path));
                known.put(path, direct);
            }
            return direct;
        }

        String dir = parentOf(path);
        if (!listedDirs.contains(dir)) {
            listedDirs.add(dir);
            List<Entry> entries = ifs.list(dir);
            listings++;
            for (int i = 0; i < entries.size(); i++) {
                if (known.size() >= maxListing) throw tooMany(dir);
                Entry e = entries.get(i);
                known.put(e.path, e);
            }
            Entry found = known.get(path);
            if (found != null) return found;
        }
        // listed and not there: ask directly, because a file created since the listing is a real
        // possibility on a live share and one extra round trip for a miss is cheap
        Entry direct = ifs.stat(path);
        if (direct != null) known.put(path, direct);
        return direct;
    }

    private IOException tooMany(String dir) {
        return new IOException("the IFS lookup has passed " + maxListing + " cached entr(y/ies) after "
                + listings + " directory listing(s), most recently under " + dir + ". Holding more would"
                + " risk running the JVM out of memory in the middle of a delivery, which is the failure"
                + " this executor exists to avoid."
                + (lookup == Lookup.LISTING
                    ? " Under LISTING the count is every file in the directories, not the ones this feed"
                      + " uses: a store of millions listed to reach thousands will always trip this."
                      + " contentIfsLookup=STAT asks per document instead and is bounded by the feed."
                    : " Under STAT the count is the documents this feed references, so this means the"
                      + " feed itself is larger than expected.")
                + " Raise contentIfsMaxListing deliberately if the figure is right.");
    }

    // ------------------------------------------------------------------ the payload

    /**
     * Staged to local disk once, then read from there.
     *
     * The template puts the hash element before the content element, so the digest has to be known
     * before the payload is written and the document is read twice. Over a network that would be two
     * transits; buffering it to make one pass is the {@code ByteArrayOutputStream} that caused the
     * OutOfMemoryError this rewrite removed. So: one transit to a temp file, and both passes read local
     * disk. Peak local disk is ONE document, because the temp is replaced as soon as another is asked
     * for and deleted when the run ends.
     *
     * The call pattern is two consecutive opens of the same path, which this exploits. If it ever
     * became interleaved the store would re-download rather than return wrong bytes: slower, still
     * correct.
     */
    public InputStream open(String resolved) throws IOException {
        if (!resolved.equals(stagedPath)) stage(resolved);
        return new FileInputStream(stagedFile);
    }

    private void stage(String path) throws IOException {
        discardStaged();
        Entry e = entry(path);
        if (e == null) throw new IOException("the document is no longer on the IFS: " + path);

        if (!stagingDir.isDirectory() && !stagingDir.mkdirs()) {
            throw new IOException("the staging directory cannot be created: " + stagingDir.getAbsolutePath());
        }
        // Unique names never overwrite one another, so anything a killed run left behind would simply
        // accumulate. Swept once per run rather than per document.
        if (!sweptStagingDir) {
            sweptStagingDir = true;
            File[] old = stagingDir.listFiles();
            if (old != null) {
                for (int i = 0; i < old.length; i++) {
                    String n = old[i].getName();
                    if (n.startsWith(PART_PREFIX) && n.endsWith(PART_SUFFIX) && !old[i].delete()) {
                        old[i].deleteOnExit();
                    }
                }
            }
        }
        // A UNIQUE name per document, not one reused for every one of them.
        //
        // The first run on volume failed with "Access is denied" after 27 668 documents and 9.3 GB, on a
        // disk with plenty of space. Reusing a single path means delete-then-create tens of thousands of
        // times at the same place, and on Windows that is the shape that collides with a virus scanner or
        // the indexer still holding the previous incarnation. A fresh name cannot collide with the file
        // before it; a short retry covers the rest, because such a lock lasts fractions of a second.
        File tmp = new File(stagingDir, "elarxml-content-" + (++stagedSeq) + PART_SUFFIX);
        IOException last = null;
        for (int attempt = 1; attempt <= CREATE_ATTEMPTS; attempt++) {
            try {
                new FileOutputStream(tmp).close();
                last = null;
                break;
            } catch (IOException ex) {
                last = ex;
                if (attempt < CREATE_ATTEMPTS) {
                    try { Thread.sleep(200L * attempt); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        if (last != null) {
            throw new IOException("the staging file " + tmp.getAbsolutePath() + " could not be created"
                    + " after " + CREATE_ATTEMPTS + " attempts: " + last.getMessage()
                    + ". The name is unique per document, so this is not the previous one still being"
                    + " held; check the free space and whether something is scanning that directory.",
                    last);
        }

        long total = 0;
        InputStream in = ifs.open(path);
        OutputStream out = new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16);
        try {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); total += n; }
            out.flush();
        } finally {
            try { in.close(); } catch (IOException ignored) { }
            try { out.close(); } catch (IOException ignored) { }
        }

        // the length check that the modification time cannot do here: what came down must be what the
        // listing said was there, or the document changed underneath the run
        if (total != e.length) {
            if (!tmp.delete()) tmp.deleteOnExit();
            throw new IOException("the IFS document " + path + " was " + e.length + " bytes when the"
                    + " directory was listed and " + total + " bytes when it was read. It changed while"
                    + " this run was in progress, so nothing is delivered from it. Re-run once the"
                    + " source is stable.");
        }

        stagedPath = path;
        stagedFile = tmp;
        staged++;
        stagedBytes += total;
    }

    private void discardStaged() {
        if (stagedFile != null && stagedFile.exists() && !stagedFile.delete()) stagedFile.deleteOnExit();
        stagedFile = null;
        stagedPath = null;
    }

    // ------------------------------------------------------------------ lifetime

    /**
     * Releases the connection and the staged document. Called by the executor on every path, including
     * the failing ones, so a run that throws does not leak either.
     */
    public void close() throws IOException {
        // idempotent: both the executor and whoever constructed the store may close it, and the second
        // call must not disconnect twice or log the summary twice
        if (closed) return;
        closed = true;
        discardStaged();
        if (log != null) {
            log.accept("elarxml: IFS content via " + lookup + " - " + listings + " directory listing(s), " + known.size()
                    + " entr(y/ies) known, " + staged + " document(s) staged, " + stagedBytes
                    + " byte(s) transferred");
        }
        ifs.close();
    }

    // ------------------------------------------------------------------ paths

    static String parentOf(String path) {
        int i = path.lastIndexOf('/');
        if (i < 0) return "/";
        return i == 0 ? "/" : path.substring(0, i);
    }

    /** Collapses repeated slashes and resolves {@code .} and {@code ..} textually. */
    static String collapse(String path) {
        String[] parts = path.split("/");
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty() || ".".equals(p)) continue;
            if ("..".equals(p)) {
                if (!out.isEmpty()) out.remove(out.size() - 1);
                continue;
            }
            out.add(p);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < out.size(); i++) sb.append('/').append(out.get(i));
        return sb.length() == 0 ? "/" : sb.toString();
    }

    private static String trimSlash(String s) {
        return s.endsWith("/") && s.length() > 1 ? s.substring(0, s.length() - 1) : s;
    }
}
