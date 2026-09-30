package com.legalarchive.orchestrator.rename;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Renames the files of a directory according to a mapping held in a CSV: the name a file has on
 * disk is built from a template and a progressive id, the name it must take is a column value.
 *
 * <p>This is the {@code filerename} executor, and it does what {@code Rename-FilesFromCsvMap.ps1}
 * does - the same parameters with the same defaults, the same checks in the same order, the same
 * log lines, the same exit codes (0 completed, 2 completed with rilievi, 1 error or aborted). The
 * reference is the script, and every place where this class does something else is listed in
 * {@code .claude/FILE_RENAME_EXECUTOR.md} with the reason; there are four and none changes which
 * file gets which name.
 *
 * <p>The whole mapping is built and checked before the first rename: two rows producing the same
 * target, and a target already taken by a file that is not itself being renamed away, abort the
 * run unless {@link #force} is set, because discovering a collision at row 4000 of 5000 leaves
 * the directory in a state nobody can describe. The directory is listed once and every existence
 * question is answered from that index, which is what makes it fast on a network share.
 *
 * <p>Spring-free and JDK-only, so it runs against real files outside the container.
 */
public final class CsvRenameMap {

    // ------------------------------------------------------------------ parameters (script defaults)
    public File csvPath;
    public File directory;
    public String nameColumn = "original_object_name";
    public String idColumn = "object_id";
    public String extColumn = "mime_type";
    public String prefix = "";
    public String sourceTemplate = "{PREFIX}.OID{ID}{EXT}";
    public int idPadding = 5;
    public String paddingBasis = "MaxId";
    public boolean reverse;
    public boolean force;
    public char delimiter = ';';
    public String charset = "windows-1252";
    /** Also write every line here; an existing directory gets a timestamped file inside it. */
    public String logFile;
    public boolean summaryOnly;
    public int maxReport = 30;
    /** Dry run: build, check and report everything, rename nothing (the script's -WhatIf). */
    public boolean whatIf;

    /** Where the lines go (the step log). */
    public Consumer<String> out;
    /** Polled between renames; true stops the loop cleanly (the Stop button). */
    public BooleanSupplier stopRequested;

    // ------------------------------------------------------------------ results
    public int exitCode = 1;
    public String error;
    public boolean aborted;
    public boolean stopped;
    public int rows;
    public int mappingRows;
    public int unusableRows;
    public int duplicateTargets;
    public int targetsOnDisk;
    public int renamed;
    public int wouldRename;
    public int sourceNotFound;
    public int skippedTargetExists;
    public int failed;
    public int effectivePadding;
    public int directoryFiles;
    public String firstLookedFor = "";
    public String logPath = "";

    private Writer logWriter;

    static final String MAX_ID = "MaxId";
    static final String ROW_COUNT = "RowCount";
    static final String DIRECTORY_COUNT = "DirectoryCount";

    private static final class Plan {
        final int row; final String from; final String to;
        Plan(int row, String from, String to) { this.row = row; this.from = from; this.to = to; }
    }

    private static final class Refusal extends Exception {
        private static final long serialVersionUID = 1L;
        Refusal(String m) { super(m); }
    }

    public int run() {
        try {
            exitCode = body();
        } catch (Refusal e) {
            fatal(e.getMessage());
        } catch (IOException e) {
            fatal(e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (RuntimeException e) {
            fatal(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            closeLog();
        }
        return exitCode;
    }

    private void fatal(String msg) {
        error = msg;
        log("ERROR " + msg);
        log("END exit=1");
        exitCode = 1;
    }

    private int body() throws Refusal, IOException {
        // Parameter-binding failures of the script come first there too: they stop it before
        // the log file is opened, before any path is looked at.
        String basis = canonicalBasis(paddingBasis);
        if (basis == null) {
            throw new Refusal("paddingBasis must be MaxId, RowCount or DirectoryCount, not '" + paddingBasis + "'");
        }
        Charset cs;
        try {
            cs = Charset.forName(charset);
        } catch (Exception e) {
            throw new Refusal("unknown charset '" + charset + "'");
        }

        openLog();

        if (csvPath == null || !csvPath.isFile()) throw new Refusal("CSV not found: " + (csvPath == null ? "" : csvPath.getPath()));
        if (directory == null || !directory.isDirectory()) throw new Refusal("Directory not found: " + (directory == null ? "" : directory.getPath()));
        if (idPadding < 0) throw new Refusal("idPadding cannot be negative (0 means automatic).");

        Path dir = directory.toPath().toAbsolutePath().normalize();

        PsCsvReader.Table t;
        try {
            t = PsCsvReader.read(csvPath.toPath(), cs, delimiter);
        } catch (IllegalArgumentException e) {
            throw new Refusal(e.getMessage());
        }
        if (t.unspecifiedNames) {
            log("WARNING One or more headers were not specified. Default names starting with \"H\" have been used in place of any missing headers.");
        }
        if (t.rows.isEmpty()) throw new Refusal("CSV has no data rows.");
        rows = t.rows.size();

        Map<String, Integer> col = new HashMap<String, Integer>();
        for (int i = 0; i < t.header.size(); i++) col.put(CaseInsensitive.key(t.header.get(i)), Integer.valueOf(i));
        String headerList = String.join(", ", t.header);
        int nameIx = column(col, nameColumn, headerList);
        int idIx = column(col, idColumn, headerList);
        // DEVIATION 1 (see the spec): the script requires the {EXT} column even when the template has
        // no {EXT}, where its value is thrown away. Here it is read whenever the header has it - so
        // every run the script could do behaves the same - and REQUIRED only when the template uses it.
        boolean usesExt = sourceTemplate.contains("{EXT}");
        Integer extFound = col.get(CaseInsensitive.key(extColumn));
        int extIx = extFound != null ? extFound.intValue() : (usesExt ? column(col, extColumn, headerList) : -1);

        log("START csv=" + csvPath.getName() + " rows=" + rows);
        log("  directory=" + dir);
        log("  template='" + sourceTemplate + "' prefix='" + prefix + "' idPadding="
                + (idPadding == 0 ? "auto (" + basis + ")" : String.valueOf(idPadding)));
        if (extIx >= 0) log("  {EXT} <- column '" + extColumn + "'");
        else log("  {EXT} is not in the template and column '" + extColumn + "' is absent: not needed");
        log("  direction=" + (reverse ? nameColumn + " -> template" : "template -> " + nameColumn));
        if (whatIf) log("  whatIf=yes: nothing will be renamed");
        if (logWriter != null) log("  log=" + logPath);

        // --- index the directory once ------------------------------------------------
        long t0 = System.nanoTime();
        Set<String> index = new HashSet<String>();
        DirectoryStream<Path> ds = Files.newDirectoryStream(dir);
        try {
            for (Path p : ds) {
                if (Files.isDirectory(p)) continue;
                index.add(CaseInsensitive.key(p.getFileName().toString()));
            }
        } finally {
            ds.close();
        }
        double idxSec = (System.nanoTime() - t0) / 1e9;
        directoryFiles = index.size();
        log("  directoryIndexed files=" + index.size() + " in " + sec(idxSec) + "s");

        // --- effective padding -----------------------------------------------------------
        int pad = idPadding;
        if (pad == 0) {
            long basisValue = 0;
            if (ROW_COUNT.equals(basis)) {
                basisValue = rows;
            } else if (DIRECTORY_COUNT.equals(basis)) {
                basisValue = index.size();
            } else {
                // Largest numeric id; with no numeric id at all, the longest id as written.
                long maxId = 0; int maxLen = 0; boolean anyNumeric = false;
                for (String[] r : t.rows) {
                    String v = DotNet.trim(str(r[idIx]));
                    if (v.length() > maxLen) maxLen = v.length();
                    Integer n = DotNet.tryParseInt(v);
                    if (n != null) {
                        anyNumeric = true;
                        if (n.intValue() > maxId) maxId = n.intValue();
                    }
                }
                if (anyNumeric) basisValue = maxId; else pad = maxLen;
            }
            if (pad == 0) {
                pad = Math.max(1, String.valueOf(Math.max(1L, basisValue)).length());
                log("  idPadding=auto -> " + pad + "  (basis " + basis + " = " + basisValue + ")");
            } else {
                log("  idPadding=auto -> " + pad + "  (basis: longest id as written, ids are not numeric)");
            }
        }
        effectivePadding = pad;

        // --- build and check the mapping before touching anything -----------------------
        List<Plan> plan = new ArrayList<Plan>();
        List<String> badRows = new ArrayList<String>();
        List<String> dupTargets = new ArrayList<String>();
        Set<String> targetSeen = new HashSet<String>();
        Set<String> sourceSeen = new HashSet<String>();
        String pfx = prefix == null ? "" : prefix;
        int rowNo = 1;
        for (String[] r : t.rows) {
            rowNo++;
            String idRaw = str(r[idIx]);
            String name = DotNet.trim(str(r[nameIx]));
            String ext = extIx >= 0 ? DotNet.trim(str(r[extIx])) : "";

            if (DotNet.isNullOrWhiteSpace(idRaw) || DotNet.isNullOrWhiteSpace(name)) {
                unusableRows++;
                if (badRows.size() < maxReport) badRows.add("row " + rowNo + ": empty id or name");
                continue;
            }

            // The id is padded numerically when it is a number, verbatim otherwise.
            String idOut = DotNet.trim(idRaw);
            Integer asInt = DotNet.tryParseInt(idOut);
            if (asInt != null) idOut = DotNet.padLeftZeros(asInt.toString(), pad);

            // Sequential literal replacement, as .NET String.Replace does it: a value substituted
            // early is itself exposed to the replacements that follow.
            String built = sourceTemplate;
            built = built.replace("{PREFIX}", pfx);
            built = built.replace("{ID}", idOut);
            built = built.replace("{EXT}", ext);
            for (int c = 0; c < t.header.size(); c++) {
                built = built.replace("{" + t.header.get(c) + "}", str(r[c]));
            }

            String from = reverse ? name : built;
            String to = reverse ? built : name;

            if (!isBareName(from) || !isBareName(to)) {
                unusableRows++;
                if (badRows.size() < maxReport) badRows.add("row " + rowNo + ": not a bare file name");
                continue;
            }
            if (!targetSeen.add(CaseInsensitive.key(to))) {
                duplicateTargets++;
                if (dupTargets.size() < maxReport) dupTargets.add("row " + rowNo + ": target already produced by an earlier row");
                continue;
            }
            sourceSeen.add(CaseInsensitive.key(from));
            plan.add(new Plan(rowNo, from, to));
        }
        mappingRows = plan.size();
        if (!plan.isEmpty()) firstLookedFor = plan.get(0).from;

        log("  mappingRows=" + plan.size() + " unusable=" + unusableRows + " duplicateTargets=" + duplicateTargets);

        // A target already on disk that is not itself being renamed away would be overwritten or
        // make the rename fail.
        List<String> blocked = new ArrayList<String>();
        for (Plan p : plan) {
            String tk = CaseInsensitive.key(p.to);
            if (index.contains(tk) && !sourceSeen.contains(tk)) {
                targetsOnDisk++;
                if (blocked.size() < maxReport) blocked.add("row " + p.row + ": target name already exists on disk");
            }
        }
        if (targetsOnDisk > 0) log("  targetsAlreadyOnDisk=" + targetsOnDisk);

        listCategory("unusable rows", badRows, unusableRows);
        listCategory("duplicate targets", dupTargets, duplicateTargets);
        listCategory("targets already on disk", blocked, targetsOnDisk);

        if ((duplicateTargets > 0 || targetsOnDisk > 0) && !force) {
            aborted = true;
            log("  ABORTED nothing was renamed: resolve the collisions or re-run with force=yes");
            log("END exit=1");
            return 1;
        }

        // --- rename ------------------------------------------------------------------
        List<String> listMissing = new ArrayList<String>();
        long t1 = System.nanoTime();
        for (Plan p : plan) {
            if (stopRequested != null && stopRequested.getAsBoolean()) {
                stopped = true;
                log("  STOPPED by user: " + renamed + " renamed, the rest of the mapping was not applied");
                break;
            }
            if (!index.contains(CaseInsensitive.key(p.from))) {
                sourceNotFound++;
                if (listMissing.size() < maxReport) listMissing.add(p.from);
                if (!summaryOnly) log("  SKIP  " + p.from + "  (source not found)");
                continue;
            }
            String tk = CaseInsensitive.key(p.to);
            if (index.contains(tk) && !sourceSeen.contains(tk)) {
                skippedTargetExists++;
                if (!summaryOnly) log("  SKIP  " + p.to + "  (target exists)");
                continue;
            }
            Path src = dir.resolve(p.from);
            Path dst = dir.resolve(p.to);
            if (whatIf) {
                // What -WhatIf prints, which is host output: it does not reach the script's log file.
                wouldRename++;
                emit("What if: Performing the operation \"Rename to " + p.to + "\" on target \"" + src + "\".");
                continue;
            }
            try {
                move(src, dst);
                index.remove(CaseInsensitive.key(p.from));
                index.add(tk);
                renamed++;
                if (!summaryOnly) log("  REN   " + p.from + "  ->  " + p.to);
            } catch (IOException e) {
                failed++;
                log("  FAIL  " + p.from + ": " + e.getClass().getSimpleName() + " " + e.getMessage());
            }
        }
        double renSec = (System.nanoTime() - t1) / 1e9;

        log("SUMMARY");
        log("  renamed=" + renamed);
        if (whatIf) log("  wouldRename=" + wouldRename + "  (whatIf: nothing was renamed)");
        log("  sourceNotFound=" + sourceNotFound);
        log("  skippedTargetExists=" + skippedTargetExists);
        log("  unusableRows=" + unusableRows);
        log("  duplicateTargets=" + duplicateTargets);
        log("  failed=" + failed);
        log("  elapsed index=" + sec(idxSec) + "s rename=" + sec(renSec) + "s");

        if (!listMissing.isEmpty()) {
            log("  source not found:");
            for (String x : listMissing) log("    " + x);
            if (sourceNotFound > listMissing.size()) log("    ... +" + (sourceNotFound - listMissing.size()) + " more");
            if (!plan.isEmpty()) log("  NOTE the first name looked for was: " + plan.get(0).from);
            log("  NOTE compare it with a real file name and adjust prefix, sourceTemplate or idPadding");
        }

        int code;
        if (failed > 0 || stopped) code = 1;
        else if (sourceNotFound > 0 || skippedTargetExists > 0 || unusableRows > 0 || duplicateTargets > 0) code = 2;
        else code = 0;
        log("END exit=" + code);
        return code;
    }

    /**
     * {@code File.Move} semantics. A case-only rename ({@code a.pdf} to {@code A.PDF}) needs care:
     * on Windows both names are the same file, and JDK 8's {@code Files.move} checks that FIRST and
     * returns without doing anything (WindowsFileCopy.move, "if both files are the same then
     * nothing to do"), where .NET's File.Move renames it. The atomic path goes straight to
     * {@code MoveFileEx}, which performs it; the on-disk name is then read back, so a rename that
     * did not take is a FAIL line rather than a REN line that lies.
     */
    static void move(Path src, Path dst) throws IOException {
        String a = src.getFileName().toString();
        String b = dst.getFileName().toString();
        if (!a.equals(b) && a.equalsIgnoreCase(b)
                && Files.exists(dst, LinkOption.NOFOLLOW_LINKS) && Files.isSameFile(src, dst)) {
            Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE);
            String real = dst.toRealPath().getFileName().toString();
            if (!real.equals(b)) throw new IOException("case-only rename did not take effect, the file is still named " + real);
            return;
        }
        Files.move(src, dst);
    }

    /** Test-BareName: a name, not a path; anything else is refused rather than interpreted. */
    static boolean isBareName(String name) {
        if (DotNet.isNullOrWhiteSpace(name)) return false;
        if (name.indexOf('\\') >= 0 || name.indexOf('/') >= 0) return false;
        if (name.contains("..") || name.contains(":")) return false;
        for (int i = 0; i < name.length(); i++) if (DotNet.isInvalidFileNameChar(name.charAt(i))) return false;
        return true;
    }

    static String canonicalBasis(String v) {
        if (v == null) return MAX_ID;
        if (MAX_ID.equalsIgnoreCase(v)) return MAX_ID;
        if (ROW_COUNT.equalsIgnoreCase(v)) return ROW_COUNT;
        if (DIRECTORY_COUNT.equalsIgnoreCase(v)) return DIRECTORY_COUNT;
        return null;
    }

    private int column(Map<String, Integer> col, String name, String headerList) throws Refusal {
        Integer ix = col.get(CaseInsensitive.key(name));
        if (ix == null) throw new Refusal("Column '" + name + "' not found. Header is: " + headerList);
        return ix.intValue();
    }

    /**
     * DEVIATION 2 (see the spec): the script's counters are the sizes of its report LISTS, which
     * stop growing at maxReport, so 100 unusable rows were reported as 30. Here the count is the
     * real one and the list is what is capped, with the overflow said.
     */
    private void listCategory(String title, List<String> items, int total) {
        if (total <= 0) return;
        log("  " + title + ":");
        for (String x : items) log("    " + x);
        if (total > items.size()) log("    ... +" + (total - items.size()) + " more");
    }

    private static String str(String v) { return v == null ? "" : v; }

    private static String sec(double s) { return String.format(Locale.ROOT, "%.1f", s); }

    // ------------------------------------------------------------------ output

    private void emit(String line) {
        if (out != null) out.accept(line);
    }

    private void log(String line) {
        emit(line);
        if (logWriter != null) {
            try {
                logWriter.write(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + " " + line + "\r\n");
                logWriter.flush();
            } catch (IOException e) {
                emit("WARNING could not write to log file " + logPath + ": " + e.getMessage());
                closeLog();
            }
        }
    }

    private void openLog() {
        if (logFile == null || logFile.trim().isEmpty()) return;
        File f = new File(logFile);
        try {
            if (f.isDirectory()) {
                f = new File(f, "rename-map-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".log");
            } else {
                File d = f.getAbsoluteFile().getParentFile();
                if (d != null && !d.exists()) Files.createDirectories(d.toPath());
            }
            logWriter = new OutputStreamWriter(Files.newOutputStream(f.toPath()), StandardCharsets.UTF_8);
            logPath = f.getAbsolutePath();
        } catch (Exception e) {
            emit("WARNING could not open log file " + logFile + ": " + e.getMessage());
            logWriter = null;
        }
    }

    private void closeLog() {
        if (logWriter != null) {
            try { logWriter.close(); } catch (IOException ignored) { }
            logWriter = null;
        }
    }
}
