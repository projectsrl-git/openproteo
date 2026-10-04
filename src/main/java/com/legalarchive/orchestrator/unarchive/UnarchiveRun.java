package com.legalarchive.orchestrator.unarchive;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.LinkOption;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.Set;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.ToLongFunction;
import java.util.stream.Stream;

/**
 * The whole executor, Spring-free, so it runs end to end against real files outside the container
 * (spec sections 6-9). {@code InternalSteps.runUnarchive} (batch 3) only translates parameters in
 * and counters out.
 *
 * <p>Order of work, and why:
 * <ol>
 * <li><b>Configuration</b> refused before anything is read; settings that cannot take effect are
 *     refused, not ignored ({@code layout=flat}, {@code afterExtract=delete} - Gate 0 Q10, Q8).</li>
 * <li><b>Sweep</b> of this executor's own leftovers in {@code outputDir}: directories named
 *     {@code .unarchive-*.part} or {@code .unarchive-*.old}, direct children only.</li>
 * <li><b>Selection</b>: {@code pattern}, case-sensitive; <b>∩ I22</b> names ending {@code .done} are
 *     never selected, whatever the pattern. Sorted by relative path, so the order is the same on
 *     every file system (directory enumeration order is not - measured in tiffcompress).</li>
 * <li><b>Checks that need no extraction, over EVERY archive, before the first is extracted</b>:
 *     subdirectory name valid, no two archives sharing one (<b>∩</b> {@code a.zip} + {@code a.tar}),
 *     target already there under {@code onExisting=fail}, {@code .done} already there under
 *     {@code afterExtract=rename}. A refusal that could have been known up front must not arrive
 *     after three archives were extracted.</li>
 * <li><b>Each archive</b> into {@code .unarchive-<runId>-<n>.part}, then ONE rename onto its
 *     subdirectory. Any failure deletes the staging; archives committed before stay committed.</li>
 * </ol>
 */
public final class UnarchiveRun {

    // ------------------------------------------------------------------ configuration
    public File sourceDir;
    public File outputDir;
    /** Where the manifest goes (the step directory in production); null writes none. */
    public File manifestFile;
    public String pattern = "*.zip;*.tar;*.tgz;*.tar.gz;*.gz";
    public boolean recursive;
    public String format = "auto";
    public String layout = "subdir";
    public String onExisting = "fail";
    public String onUnsupportedEntry = "fail";
    public String zipNameCharset = "auto";
    public String zipLegacyCharset = "IBM850";
    public long maxEntries = 100000;
    public long maxEntryMb = 2048;
    public long maxArchiveMb = 20480;
    public long maxRatio = 200;
    /** 0 = auto: the host's limit, 259 UTF-16 units on Windows, 4096 UTF-8 bytes on Linux (section 21). */
    public int maxPathLength = 0;
    public boolean checkFreeDisk = true;
    public String afterExtract = "keep";
    public boolean preserveMtime = true;
    public boolean manifestHash = true;
    public boolean failOnEmpty;
    public String runId = String.valueOf(System.currentTimeMillis());
    public Consumer<String> log = s -> { };
    public BooleanSupplier aborted = () -> false;
    /** Free space on a directory; injected by tests, since a disk cannot be filled on demand. */
    public ToLongFunction<File> usableSpace = File::getUsableSpace;

    /** The OS the name rules follow; {@code os.name} in production, set by tests to exercise both. */
    public String hostOs = System.getProperty("os.name");

    /** A rename; injected by tests, since a rename cannot be made to fail on demand. */
    public interface Mover {
        void move(Path from, Path to) throws IOException;
    }

    /** The commit's rename, with the Windows back-off. Tests replace it to fail a chosen move. */
    public Mover mover = UnarchiveRun::atomicMove;

    // ------------------------------------------------------------------ results
    public int archivesFound;
    public int archivesExtracted;
    public int archivesSkipped;
    public long entriesExtracted;
    public long entriesSkipped;
    public long bytesExtracted;
    public int warnings;
    public int mtimeFailures;
    public boolean wasAborted;
    /** The rule set applied, "windows" or "linux" - published so a run says which it used. */
    public String hostRules = "";
    public final List<String> extractDirs = new ArrayList<String>();

    static final String STAGING_PREFIX = ".unarchive-";
    private static final int COMMIT_ATTEMPTS = 3;
    private static final long ABORT_CHECK_BYTES = 8L * 1024 * 1024;
    private static final int MAX_NAMES_LOGGED = 20;

    private Charset forcedCharset;
    private Charset legacyCharset;
    private ArchiveFormat.Requested requested;
    private Path out;
    private BufferedWriter manifest;
    private HostRules rules;
    private int pathLimit;
    /** The process umask, read from the staging folder Java just created (0777 & ~umask); -1 = no POSIX view. */
    private int umask = -1;

    /** Thrown internally when Stop is seen; the run records it and stops cleanly. */
    static final class Aborted extends IOException {
        private static final long serialVersionUID = 1L;
    }

    /** One selected archive. */
    private static final class Candidate {
        final Path file;
        final String rel;
        final String subdir;

        Candidate(Path file, String rel, String subdir) {
            this.file = file;
            this.rel = rel;
            this.subdir = subdir;
        }
    }

    /** One manifest row, kept until its archive commits. */
    private static final class Row {
        final String entry, target, sha;
        final long bytes, mtime;

        Row(String entry, String target, long bytes, String sha, long mtime) {
            this.entry = entry;
            this.target = target;
            this.bytes = bytes;
            this.sha = sha;
            this.mtime = mtime;
        }
    }

    public void run() throws IOException {
        configure();
        log.accept("unarchive: host " + hostOs + ": " + rules.label() + " name rules, path limit " + pathLimit
                + (rules == HostRules.LINUX ? " UTF-8 bytes" : " characters"));
        sweep();
        List<Candidate> found = select();
        archivesFound = found.size();
        log.accept("unarchive: " + archivesFound + " archive(s) match '" + pattern + "' in " + sourceDir
                + (recursive ? " (recursive)" : ""));
        if (found.isEmpty()) {
            if (failOnEmpty) {
                throw new UnarchiveException(UnarchiveException.Rule.CONFIGURATION,
                        "no archive matched and failOnEmpty is set");
            }
            return;
        }
        precheck(found);
        if (manifestFile != null) openManifest();
        try {
            int n = 0;
            for (Candidate c : found) {
                n++;
                if (aborted.getAsBoolean()) {
                    wasAborted = true;
                    log.accept("unarchive: stopped before " + c.rel);
                    return;
                }
                try {
                    one(c, n);
                } catch (Aborted a) {
                    wasAborted = true;
                    log.accept("unarchive: stopped while extracting " + c.rel + "; its staging was removed");
                    return;
                }
            }
        } finally {
            if (manifest != null) manifest.close();
        }
        log.accept("unarchive: " + archivesExtracted + " extracted, " + archivesSkipped + " skipped, "
                + entriesExtracted + " entries, " + bytesExtracted + " bytes, " + entriesSkipped
                + " entries skipped, " + warnings + " warning(s)");
    }

    // ------------------------------------------------------------------ steps

    private void configure() throws IOException {
        rules = HostRules.detect(hostOs);
        if (rules == null) {
            throw config("the host OS '" + hostOs + "' is neither Windows nor Linux; unarchive has name rules for those two only");
        }
        hostRules = rules.label();
        if (sourceDir == null || !sourceDir.isDirectory()) {
            throw config("sourceDir is not a directory: " + sourceDir);
        }
        if (outputDir == null) throw config("outputDir is required");
        Files.createDirectories(outputDir.toPath());
        Path src = sourceDir.toPath().toRealPath();
        out = outputDir.toPath().toRealPath();
        if (out.equals(src)) {
            throw config("outputDir is sourceDir: the next run would find the extracted archives and open them");
        }
        if (recursive && out.startsWith(src)) {
            throw config("outputDir is inside sourceDir and recursive=true: a recursive scan would re-open what was extracted");
        }
        String lay = low(layout, "subdir");
        if (lay.equals("flat")) {
            throw config("layout=flat is not available (Gate 0 Q10): only subdir, the atomic layout, is implemented");
        }
        if (!lay.equals("subdir")) throw config("layout must be subdir, not '" + layout + "'");
        if (!oneOf(low(onExisting, "fail"), "fail", "skip", "replace")) throw config("onExisting must be fail, skip or replace");
        if (!oneOf(low(onUnsupportedEntry, "fail"), "fail", "skip")) throw config("onUnsupportedEntry must be fail or skip");
        String ae = low(afterExtract, "keep");
        if (ae.equals("delete")) {
            throw config("afterExtract=delete is not offered (Gate 0 Q8): use keep or rename");
        }
        if (!oneOf(ae, "keep", "rename")) throw config("afterExtract must be keep or rename");
        requested = ArchiveFormat.Requested.parse(format);
        if (requested == null) throw config("format must be auto, zip, tar, tar.gz or gz, not '" + format + "'");
        String zc = zipNameCharset == null ? "auto" : zipNameCharset.trim();
        if (!zc.isEmpty() && !zc.equalsIgnoreCase("auto")) {
            if (!Charset.isSupported(zc)) throw config("zipNameCharset '" + zc + "' is not a charset this Java knows");
            forcedCharset = Charset.forName(zc);
        }
        if (!Charset.isSupported(zipLegacyCharset)) {
            throw config("the legacy zip charset " + zipLegacyCharset + " is not available in this Java"
                    + " (on a Java 8 JRE it lives in lib/charsets.jar)");
        }
        legacyCharset = Charset.forName(zipLegacyCharset);
        if (maxEntries <= 0 || maxEntryMb <= 0 || maxArchiveMb <= 0 || maxRatio <= 0 || maxPathLength < 0) {
            throw config("limits must be positive");
        }
        pathLimit = maxPathLength == 0 ? rules.defaultMaxPath() : maxPathLength;
        if (new FileMask(pattern).isEmpty()) throw config("pattern selects nothing");
    }

    private void sweep() throws IOException {
        try (Stream<Path> s = Files.list(out)) {
            List<Path> left = new ArrayList<Path>();
            s.forEach(p -> {
                String n = p.getFileName().toString();
                if (n.startsWith(STAGING_PREFIX) && (n.endsWith(".part") || n.endsWith(".old")) && Files.isDirectory(p)) {
                    left.add(p);
                }
            });
            for (Path p : left) {
                deleteTree(p);
                log.accept("unarchive: removed a leftover from an interrupted run: " + p.getFileName());
            }
        }
    }

    private List<Candidate> select() throws IOException {
        FileMask mask = new FileMask(pattern);
        Path src = sourceDir.toPath().toRealPath();
        List<Candidate> list = new ArrayList<Candidate>();
        try (Stream<Path> s = Files.walk(src, recursive ? Integer.MAX_VALUE : 1)) {
            s.filter(Files::isRegularFile).forEach(p -> {
                String name = p.getFileName().toString();
                if (name.endsWith(".done")) return;                      // ∩ I22
                if (!mask.matchesAny(name)) return;
                String rel = src.relativize(p).toString().replace('\\', '/');
                list.add(new Candidate(p, rel, ArchiveFormat.baseName(name)));
            });
        }
        list.sort((a, b) -> a.rel.compareTo(b.rel));
        return list;
    }

    private void precheck(List<Candidate> found) throws UnarchiveException {
        Map<String, Candidate> bySubdir = new HashMap<String, Candidate>();
        String exist = low(onExisting, "fail");
        boolean rename = low(afterExtract, "keep").equals("rename");
        for (Candidate c : found) {
            EntryName.Name n = EntryName.validate(c.subdir, true, rules, false);
            if (n.segments.size() != 1) {
                throw new UnarchiveException(UnarchiveException.Rule.CONFIGURATION,
                        c.rel + " would extract into '" + c.subdir + "', which is not a single directory name");
            }
            EntryName.checkLength(out.toString(), n, pathLimit, rules);
            Candidate prev = bySubdir.put(rules == HostRules.LINUX ? c.subdir : EntryName.collisionKey(c.subdir), c);
            if (prev != null) {
                throw new UnarchiveException(UnarchiveException.Rule.SUBDIR_COLLISION, prev.rel + " and " + c.rel
                        + " would both extract into '" + c.subdir + "'" + (rules == HostRules.LINUX ? "" : " (names compared as Windows does)"));
            }
            if (exist.equals("fail") && Files.exists(out.resolve(c.subdir))) {
                throw new UnarchiveException(UnarchiveException.Rule.TARGET_EXISTS, out.resolve(c.subdir)
                        + " already exists (onExisting=fail); nothing was extracted");
            }
            if (rename && Files.exists(doneOf(c.file))) {
                throw new UnarchiveException(UnarchiveException.Rule.DONE_EXISTS, doneOf(c.file).getFileName()
                        + " already exists, so " + c.rel + " could not be renamed after extraction; nothing was extracted");
            }
        }
    }

    private void one(Candidate c, int n) throws IOException {
        Path target = out.resolve(c.subdir);
        String exist = low(onExisting, "fail");
        if (Files.exists(target) && exist.equals("skip")) {
            archivesSkipped++;
            log.accept("unarchive: " + c.rel + " skipped, " + c.subdir + " already exists (onExisting=skip)");
            return;
        }
        byte[] head = new byte[ArchiveFormat.HEAD_BYTES];
        int len;
        try (InputStream in = Files.newInputStream(c.file)) {
            len = readUpTo(in, head);
        }
        ArchiveFormat.Decision d;
        try {
            d = ArchiveFormat.decide(c.file.getFileName().toString(), head, len, requested);
        } catch (UnarchiveException e) {
            throw prefixed(c, e);
        }
        log.accept("unarchive: " + c.rel + " -> " + c.subdir + "/ (" + ArchiveFormat.label(d.kind) + ")");
        if (d.warning != null) warn(d.warning);

        Path staging = out.resolve(STAGING_PREFIX + runId + "-" + n + ".part");
        Files.createDirectory(staging);
        umask = rules == HostRules.LINUX ? umaskOf(staging) : -1;
        List<Row> rows = new ArrayList<Row>();
        boolean committed = false;
        try {
            Budget budget = new Budget(maxEntries, maxEntryMb * 1024 * 1024, maxArchiveMb * 1024 * 1024, maxRatio);
            Ctx x = new Ctx(c, staging, target, budget, rows);
            switch (d.handling) {
                case ZIP:
                    extractZip(x);
                    break;
                case TAR:
                    try (InputStream in = new BufferedInputStream(Files.newInputStream(c.file), 65536)) {
                        extractTar(x, in, null);
                    }
                    break;
                default:
                    try (InputStream raw = new BufferedInputStream(Files.newInputStream(c.file), 65536)) {
                        GzipSupport.Opened g = GzipSupport.open(raw, c.file.getFileName().toString(), d.handling);
                        if (g.innerIsTar) extractTar(x, g.decompressed, g.compressedCounter);
                        else extractSingle(x, g);
                    }
            }
            if (rules == HostRules.LINUX) linuxFinish(x);
            commit(staging, target, n);
            committed = true;
        } catch (UnarchiveException e) {
            throw prefixed(c, e);
        } finally {
            if (!committed && Files.exists(staging)) deleteTree(staging);
        }
        archivesExtracted++;
        extractDirs.add(target.toString());
        for (Row r : rows) writeRow(c.rel, r);
        if (manifest != null) manifest.flush();
        if (low(afterExtract, "keep").equals("rename")) {
            try {
                Files.move(c.file, doneOf(c.file));
            } catch (IOException e) {
                throw new UnarchiveException(UnarchiveException.Rule.COMMIT_FAILED, c.rel + " was extracted and committed to "
                        + target + ", but renaming it to .done failed: " + e.getMessage());
            }
        }
    }

    /** Per-archive state shared by the three extraction paths. */
    private final class Ctx {
        final Candidate c;
        final Path staging, target;
        final Budget budget;
        final List<Row> rows;
        final NameIndex index;
        final List<String> skippedNames = new ArrayList<String>();
        /** Linux: regular files written so far, by path, for hardlink targets. */
        final Map<String, Row> files = new HashMap<String, Row>();
        /** Linux: symlinks created, verified together before the commit. */
        final List<Path> symlinks = new ArrayList<Path>();
        /** Linux: directory entries' mode and time, applied deepest first after everything else. */
        final List<Object[]> dirs = new ArrayList<Object[]>();
        /** ∩ I46: a zip's modes are applied as stored (unzip, measured); a tar's minus the umask (GNU tar). */
        boolean zip;
        int backslashNames, notNfcNames;

        Ctx(Candidate c, Path staging, Path target, Budget budget, List<Row> rows) {
            this.c = c;
            this.staging = staging;
            this.target = target;
            this.budget = budget;
            this.rows = rows;
            this.index = new NameIndex(rules);
        }
    }

    private void extractTar(Ctx x, InputStream in, GzipSupport.Counting counter) throws IOException {
        TarStreamReader r = new TarStreamReader(in);
        TarStreamReader.Entry e;
        while ((e = r.next()) != null) {
            checkAbort();
            x.budget.startEntry(e.name);
            EntryName.Name n = EntryName.validate(e.name, e.type == TarStreamReader.Type.DIRECTORY, rules, false);
            note(x, n);
            if (rules == HostRules.LINUX && e.type == TarStreamReader.Type.SYMLINK) {
                symlink(x, n, e.name, e.linkName == null ? "" : e.linkName);
                continue;
            }
            if (rules == HostRules.LINUX && e.type == TarStreamReader.Type.HARDLINK) {
                hardlink(x, n, e.name, e.linkName == null ? "" : e.linkName, e.mtime >= 0 ? e.mtime * 1000 : -1);
                continue;
            }
            if (e.type != TarStreamReader.Type.FILE && e.type != TarStreamReader.Type.DIRECTORY) {
                unsupported(x, e.name, e.type.name().toLowerCase(Locale.ROOT)
                        + (e.linkName != null ? " -> " + e.linkName : ""));
                continue;
            }
            place(x, n, e.name, r.payload(), -1, counter, e.mtime >= 0 ? e.mtime * 1000 : -1, e.mode);
        }
        if (r.endMarkerMissing()) warn(x.c.rel + ": the tar has no end-of-archive blocks (accepted, as GNU tar does)");
        if (r.loneZeroBlock()) warn(x.c.rel + ": the tar ends with a single zero block (accepted, as GNU tar does)");
        if (!r.globalKeys().isEmpty()) log.accept("unarchive: " + x.c.rel + ": pax global header keys ignored " + r.globalKeys());
        if (!r.ignoredPaxKeys().isEmpty()) log.accept("unarchive: " + x.c.rel + ": pax keys not applied " + r.ignoredPaxKeys());
        if (r.volumeLabels() > 0) log.accept("unarchive: " + x.c.rel + ": " + r.volumeLabels() + " volume label(s) ignored");
        finish(x);
    }

    private void extractZip(Ctx x) throws IOException {
        x.zip = true;
        try (ZipArchiveReader z = new ZipArchiveReader(x.c.file.toFile(), forcedCharset, legacyCharset)) {
            long declared = z.declaredTotal();
            if (declared > maxArchiveMb * 1024 * 1024) {
                throw new UnarchiveException(UnarchiveException.Rule.LIMIT_ARCHIVE_SIZE, "the directory declares "
                        + declared + " bytes, over maxArchiveMb=" + maxArchiveMb + "; refused before extracting");
            }
            if (checkFreeDisk) {
                long free = usableSpace.applyAsLong(out.toFile());
                if (free <= 0) {
                    log.accept("unarchive: the file system reports no free-space figure; free-disk check skipped");
                } else if (free < declared + declared / 10) {
                    throw new UnarchiveException(UnarchiveException.Rule.DISK_SPACE, "the directory declares " + declared
                            + " bytes and " + free + " are free on the output volume (10% margin required)");
                }
            }
            StringBuilder decided = new StringBuilder();
            for (ZipArchiveReader.NameRule nr : ZipArchiveReader.NameRule.values()) {
                if (z.count(nr) > 0) decided.append(decided.length() == 0 ? "" : ", ").append(nr).append('=').append(z.count(nr));
            }
            log.accept("unarchive: " + x.c.rel + ": names decided by " + (decided.length() == 0 ? "(no entries)" : decided));
            for (ZipArchiveReader.Entry e : z.entries()) {
                checkAbort();
                x.budget.startEntry(e.name);
                EntryName.Name n = EntryName.validate(e.name, e.type == ZipArchiveReader.Type.DIRECTORY, rules, e.backslashSeparates);
                note(x, n);
                if (rules == HostRules.LINUX && e.type == ZipArchiveReader.Type.SYMLINK) {
                    // a zip stores a symlink as an entry whose CONTENT is the target (zip -y, measured)
                    byte[] t;
                    try (InputStream in = z.open(e)) {
                        t = readTarget(in, e.name);
                    }
                    String target = ZipArchiveReader.tryUtf8(t);
                    if (target == null) {
                        throw new UnarchiveException(UnarchiveException.Rule.BAD_LINK_TARGET, "link '" + EntryName.printable(e.name)
                                + "' has a target that is not valid UTF-8");
                    }
                    symlink(x, n, e.name, target);
                    continue;
                }
                if (e.type == ZipArchiveReader.Type.SYMLINK || e.type == ZipArchiveReader.Type.SPECIAL) {
                    unsupported(x, e.name, e.type == ZipArchiveReader.Type.SYMLINK ? "symlink" : "special file");
                    continue;
                }
                if (n.directory) {
                    place(x, n, e.name, null, -1, null, e.mtimeMillis, e.unixMode);
                } else {
                    try (InputStream in = z.open(e)) {
                        place(x, n, e.name, in, e.compressedSize, null, e.mtimeMillis, e.unixMode);
                    }
                }
            }
        }
        finish(x);
    }

    private void extractSingle(Ctx x, GzipSupport.Opened g) throws IOException {
        String name = ArchiveFormat.innerNameOfGzip(x.c.file.getFileName().toString());
        x.budget.startEntry(name);
        EntryName.Name n = EntryName.validate(name, false, rules, false);
        place(x, n, name, g.decompressed, -1, g.compressedCounter, -1, -1);
        finish(x);
    }

    /**
     * Registers and writes one validated entry. <b>∩</b> the name is validated for EVERY entry,
     * skipped ones included (a hostile name refuses the archive even on a link that would have been
     * skipped); only entries that are written are registered, since a skipped one creates nothing.
     */
    private void place(Ctx x, EntryName.Name n, String raw, InputStream in, long entryCompressed,
                       GzipSupport.Counting counter, long mtimeMillis, int mode) throws IOException {
        x.index.add(n);
        EntryName.checkLength(x.target.toString(), n, pathLimit, rules);
        if (n.isRoot()) {
            if (rules == HostRules.LINUX) x.dirs.add(new Object[]{x.staging, mode, mtimeMillis});
            return;
        }
        Path p = x.staging.resolve(n.path);
        if (!p.normalize().startsWith(x.staging)) {                    // the net, never the rule
            throw new UnarchiveException(UnarchiveException.Rule.TRAVERSAL, "'" + raw + "' resolves outside the staging directory");
        }
        if (n.directory) {
            Files.createDirectories(p);
            if (rules == HostRules.LINUX) x.dirs.add(new Object[]{p, mode, mtimeMillis});
            return;
        }
        Files.createDirectories(p.getParent());
        MessageDigest md = manifestHash ? sha256() : null;
        long bytes = 0;
        long sinceCheck = 0;
        byte[] buf = new byte[65536];
        try (OutputStream o = Files.newOutputStream(p, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            int r;
            while ((r = in.read(buf)) > 0) {
                o.write(buf, 0, r);
                if (md != null) md.update(buf, 0, r);
                bytes += r;
                sinceCheck += r;
                x.budget.written(r, entryCompressed, counter == null ? -1 : counter.count());
                if (sinceCheck >= ABORT_CHECK_BYTES) {
                    sinceCheck = 0;
                    checkAbort();
                }
            }
        }
        if (preserveMtime && mtimeMillis >= 0) {
            try {
                Files.setLastModifiedTime(p, FileTime.fromMillis(mtimeMillis));
            } catch (IOException e) {
                mtimeFailures++;
            }
        }
        if (rules == HostRules.LINUX && mode >= 0) setMode(p, mode, !x.zip);
        entriesExtracted++;
        bytesExtracted += bytes;
        Row row = new Row(raw, x.c.subdir + "/" + n.path, bytes, md == null ? "" : hex(md.digest()), mtimeMillis);
        x.rows.add(row);
        if (rules == HostRules.LINUX) x.files.put(n.path, row);
    }

    // ------------------------------------------------------------------ Linux: links, modes, directories

    /**
     * A symbolic link, Linux hosts only. Registered in the index as a FILE, so nothing is ever written
     * through it ({@code link/x} after {@code link} is a file/directory conflict). Its target must stay
     * inside the archive's folder: checked as text now, and through the other links before the commit
     * ({@link LinkGuard}). <b>∩ I45</b>: GNU tar extracts {@code ../outside} (measured); this step refuses it.
     */
    private void symlink(Ctx x, EntryName.Name n, String raw, String target) throws IOException {
        if (n.isRoot() || n.directory) {
            throw new UnarchiveException(UnarchiveException.Rule.BAD_LINK_TARGET, "link '" + EntryName.printable(raw)
                    + "' is named as a folder");
        }
        x.index.add(n);
        EntryName.checkLength(x.target.toString(), n, pathLimit, rules);
        LinkGuard.lexical(raw, n.segments.subList(0, n.segments.size() - 1), target);
        Path p = x.staging.resolve(n.path);
        if (!p.normalize().startsWith(x.staging)) {
            throw new UnarchiveException(UnarchiveException.Rule.TRAVERSAL, "'" + raw + "' resolves outside the staging directory");
        }
        Files.createDirectories(p.getParent());
        Files.createSymbolicLink(p, Paths.get(target));
        x.symlinks.add(p);
        entriesExtracted++;
        x.rows.add(new Row(raw, x.c.subdir + "/" + n.path, 0, "", -1));
    }

    /**
     * A hard link, Linux hosts only: allowed only to a REGULAR file already extracted from the same
     * archive (GNU tar stores the second name of a multiply-linked file this way, measured), so both
     * names share one inode inside the staging folder and nothing outside can be reached.
     */
    private void hardlink(Ctx x, EntryName.Name n, String raw, String linkName, long mtimeMillis) throws IOException {
        if (n.isRoot() || n.directory) {
            throw new UnarchiveException(UnarchiveException.Rule.BAD_LINK_TARGET, "hard link '" + EntryName.printable(raw)
                    + "' is named as a folder");
        }
        EntryName.Name tn = EntryName.validate(linkName, false, rules, false);
        Row t = x.files.get(tn.path);
        if (t == null) {
            throw new UnarchiveException(UnarchiveException.Rule.LINK_TARGET_MISSING, "hard link '" + EntryName.printable(raw)
                    + "' -> '" + EntryName.printable(linkName) + "': the target must be a regular file extracted earlier from the same archive");
        }
        x.index.add(n);
        EntryName.checkLength(x.target.toString(), n, pathLimit, rules);
        Path p = x.staging.resolve(n.path);
        if (!p.normalize().startsWith(x.staging)) {
            throw new UnarchiveException(UnarchiveException.Rule.TRAVERSAL, "'" + raw + "' resolves outside the staging directory");
        }
        Files.createDirectories(p.getParent());
        Files.createLink(p, x.staging.resolve(tn.path));
        entriesExtracted++;
        Row row = new Row(raw, x.c.subdir + "/" + n.path, t.bytes, t.sha, mtimeMillis);
        x.rows.add(row);
        x.files.put(n.path, row);
    }

    /** Before the commit: links verified through each other, then folders' modes and times, deepest first. */
    private void linuxFinish(Ctx x) throws IOException {
        LinkGuard.verify(x.staging, x.symlinks);
        List<Object[]> ds = new ArrayList<Object[]>(x.dirs);
        ds.sort((a, b) -> ((Path) b[0]).getNameCount() - ((Path) a[0]).getNameCount());
        for (Object[] d : ds) {
            Path p = (Path) d[0];
            int mode = (Integer) d[1];
            long mt = (Long) d[2];
            if (mode >= 0) setMode(p, mode, !x.zip);
            if (preserveMtime && mt >= 0) {
                try {
                    Files.setLastModifiedTime(p, FileTime.fromMillis(mt));
                } catch (IOException e) {
                    mtimeFailures++;
                }
            }
        }
        if (!x.symlinks.isEmpty()) log.accept("unarchive: " + x.c.rel + ": " + x.symlinks.size() + " symbolic link(s) created, all inside the archive's folder");
    }

    /**
     * The archive's permission bits, as the reference tool applies them when not run as root
     * (measured): a tar's {@code & ~umask} (GNU tar), a zip's as stored (Info-ZIP unzip ignores the
     * umask: 777 stays 777) - ∩ I46. Setuid, setgid and sticky never; owner never; on both. No POSIX
     * view: nothing is set.
     */
    private void setMode(Path p, int mode, boolean applyUmask) {
        if (umask < 0) return;
        int bits = mode & 0777 & (applyUmask ? ~umask : 0777);
        try {
            Files.setPosixFilePermissions(p, perms(bits));
        } catch (IOException | UnsupportedOperationException e) {
            mtimeFailures++;
        }
    }

    static int umaskOf(Path freshDir) {
        PosixFileAttributeView v = Files.getFileAttributeView(freshDir, PosixFileAttributeView.class);
        if (v == null) return -1;
        try {
            return 0777 & ~bits(v.readAttributes().permissions());
        } catch (IOException e) {
            return -1;
        }
    }

    private static final PosixFilePermission[] ORDER = {
            PosixFilePermission.OTHERS_EXECUTE, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_READ,
            PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_READ,
            PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_READ };

    static Set<PosixFilePermission> perms(int bits) {
        Set<PosixFilePermission> s = EnumSet.noneOf(PosixFilePermission.class);
        for (int i = 0; i < ORDER.length; i++) if ((bits & (1 << i)) != 0) s.add(ORDER[i]);
        return s;
    }

    static int bits(Set<PosixFilePermission> s) {
        int b = 0;
        for (int i = 0; i < ORDER.length; i++) if (s.contains(ORDER[i])) b |= 1 << i;
        return b;
    }

    /** A zip symlink's content, at most PATH_MAX bytes; read to the end so the CRC is checked. */
    private static byte[] readTarget(InputStream in, String name) throws IOException {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[1024];
        int r;
        while ((r = in.read(b)) > 0) {
            o.write(b, 0, r);
            if (o.size() > 4096) {
                throw new UnarchiveException(UnarchiveException.Rule.BAD_LINK_TARGET, "link '" + EntryName.printable(name)
                        + "' has a target longer than 4096 bytes");
            }
        }
        return o.toByteArray();
    }

    private void unsupported(Ctx x, String name, String what) throws UnarchiveException {
        if (low(onUnsupportedEntry, "fail").equals("fail")) {
            throw new UnarchiveException(UnarchiveException.Rule.LINK_OR_SPECIAL, "'" + EntryName.printable(name) + "' is a "
                    + what + "; only files and directories are extracted (onUnsupportedEntry=skip leaves it out)");
        }
        entriesSkipped++;
        if (x.skippedNames.size() < MAX_NAMES_LOGGED) x.skippedNames.add(EntryName.printable(name) + " (" + what + ")");
        else if (x.skippedNames.size() == MAX_NAMES_LOGGED) x.skippedNames.add("...");
    }

    private void note(Ctx x, EntryName.Name n) {
        if (n.usedBackslash) x.backslashNames++;
        if (n.notNfc) x.notNfcNames++;
    }

    private void finish(Ctx x) {
        if (!x.skippedNames.isEmpty()) log.accept("unarchive: " + x.c.rel + ": skipped " + x.skippedNames);
        if (x.backslashNames > 0) log.accept("unarchive: " + x.c.rel + ": " + x.backslashNames + " name(s) used '\\' as separator");
        if (x.notNfcNames > 0) warn(x.c.rel + ": " + x.notNfcNames + " name(s) are not Unicode NFC; kept as they are");
        if (x.budget.entries() == 0) warn(x.c.rel + ": the archive has no entries");
    }

    /**
     * One rename onto the final name. Under {@code replace}, the old directory is moved aside FIRST
     * and deleted only after the new one is in place; if the new one cannot be put in place, the old
     * one is moved back. Retried with back-off: on Windows a scanner holding a handle inside makes a
     * rename fail transiently (elarxml's "Access is denied").
     */
    private void commit(Path staging, Path target, int n) throws IOException {
        boolean replace = low(onExisting, "fail").equals("replace");
        Path old = null;
        if (Files.exists(target)) {
            if (!replace) {
                throw new UnarchiveException(UnarchiveException.Rule.TARGET_EXISTS, target + " appeared during the run");
            }
            old = out.resolve(STAGING_PREFIX + runId + "-" + n + ".old");
            mover.move(target, old);
        }
        try {
            mover.move(staging, target);
        } catch (IOException e) {
            if (old != null) mover.move(old, target);
            throw new UnarchiveException(UnarchiveException.Rule.COMMIT_FAILED, "could not rename the staging directory to "
                    + target + ": " + e.getMessage());
        }
        if (old != null) {
            try {
                deleteTree(old);
            } catch (IOException e) {
                warn("the replaced " + target.getFileName() + " could not be deleted (" + e.getMessage()
                        + "); the next run removes " + old.getFileName());
            }
        }
    }

    static void atomicMove(Path a, Path b) throws IOException {
        IOException last = null;
        for (int i = 0; i < COMMIT_ATTEMPTS; i++) {
            try {
                Files.move(a, b, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (AtomicMoveNotSupportedException e) {
                throw e;
            } catch (IOException e) {
                last = e;
                try {
                    Thread.sleep(100L << (2 * i));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw last;
    }

    // ------------------------------------------------------------------ manifest

    private void openManifest() throws IOException {
        Files.createDirectories(manifestFile.getAbsoluteFile().getParentFile().toPath());
        manifest = new BufferedWriter(new OutputStreamWriter(Files.newOutputStream(manifestFile.toPath()), StandardCharsets.UTF_8));
        manifest.write("archive;entry;target;bytes;sha256;mtime\r\n");
    }

    private void writeRow(String archive, Row r) throws IOException {
        if (manifest == null) return;
        manifest.write(csv(archive) + ";" + csv(r.entry) + ";" + csv(r.target) + ";" + r.bytes + ";" + r.sha + ";"
                + (r.mtime >= 0 ? java.time.Instant.ofEpochMilli(r.mtime).toString() : "") + "\r\n");
    }

    /** RFC 4180 quoting, only where needed. */
    static String csv(String v) {
        if (v.indexOf(';') < 0 && v.indexOf('"') < 0 && v.indexOf('\r') < 0 && v.indexOf('\n') < 0) return v;
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }

    // ------------------------------------------------------------------ helpers

    private void checkAbort() throws Aborted {
        if (aborted.getAsBoolean()) throw new Aborted();
    }

    private void warn(String s) {
        warnings++;
        log.accept("unarchive: WARNING " + s);
    }

    private static Path doneOf(Path p) {
        return p.resolveSibling(p.getFileName().toString() + ".done");
    }

    private static UnarchiveException prefixed(Candidate c, UnarchiveException e) {
        String m = e.getMessage();
        String body = m.startsWith(e.rule() + ": ") ? m.substring(e.rule().toString().length() + 2) : m;
        return new UnarchiveException(e.rule(), c.rel + ": " + body);
    }

    private static UnarchiveException config(String why) {
        return new UnarchiveException(UnarchiveException.Rule.CONFIGURATION, why);
    }

    private static String low(String v, String def) {
        return v == null || v.trim().isEmpty() ? def : v.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean oneOf(String v, String... ok) {
        for (String o : ok) if (o.equals(v)) return true;
        return false;
    }

    private static int readUpTo(InputStream in, byte[] b) throws IOException {
        int n = 0;
        while (n < b.length) {
            int r = in.read(b, n, b.length - n);
            if (r < 0) break;
            n += r;
        }
        return n;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] d) {
        StringBuilder s = new StringBuilder(d.length * 2);
        for (byte b : d) s.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return s.toString();
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            /**
             * A folder made read-only by its archive (0555, applied after its content as GNU tar does)
             * cannot be emptied as it is - measured, rm -rf fails on it as a non-root user - so the owner
             * gets rwx back before the folder is entered. Links are never followed.
             */
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) {
                PosixFileAttributeView v = Files.getFileAttributeView(d, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                if (v != null) {
                    try {
                        Set<PosixFilePermission> s = v.readAttributes().permissions();
                        if (s.add(PosixFilePermission.OWNER_READ) | s.add(PosixFilePermission.OWNER_WRITE) | s.add(PosixFilePermission.OWNER_EXECUTE)) {
                            v.setPermissions(s);
                        }
                    } catch (IOException ignored) {
                        // the delete below reports it
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                Files.delete(f);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                if (e != null) throw e;
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
