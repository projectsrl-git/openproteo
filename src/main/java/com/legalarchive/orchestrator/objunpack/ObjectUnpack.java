package com.legalarchive.orchestrator.objunpack;

import com.legalarchive.orchestrator.objpack.AuditJson;
import com.legalarchive.orchestrator.objpack.Md5;
import com.legalarchive.orchestrator.objpack.ObjPackException;
import com.legalarchive.orchestrator.objpack.SubmissionName;
import com.legalarchive.orchestrator.objunpack.ObjUnpackException.Reason;
import com.legalarchive.orchestrator.platform.HostFiles;
import com.legalarchive.orchestrator.rename.PsCsvReader;
import com.legalarchive.orchestrator.unarchive.ArchiveFormat;
import com.legalarchive.orchestrator.unarchive.Budget;
import com.legalarchive.orchestrator.unarchive.EntryName;
import com.legalarchive.orchestrator.unarchive.FileMask;
import com.legalarchive.orchestrator.unarchive.GzipSupport;
import com.legalarchive.orchestrator.unarchive.HostRules;
import com.legalarchive.orchestrator.unarchive.NameIndex;
import com.legalarchive.orchestrator.unarchive.TarStreamReader;
import com.legalarchive.orchestrator.unarchive.UnarchiveException;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The inverse of {@code ObjectPack}: one Transarch TAR-packaged object submission back to its
 * objects under their original names and its metadata CSV, byte for byte. Spring-free, JDK only.
 * Specification: {@code .claude/OBJECT_UNPACK_EXECUTOR.md}.
 *
 * <p><b>The mapping is a join, never a name template.</b> {@code audit.json} gives
 * {@code file_name <-> object_id}; {@code metadata.csv} gives {@code object_id <->
 * original_object_name}. Nothing in a member's own name is relied on: the padding, the optional
 * label and the extension all differ between producers (measured on both).
 *
 * <p><b>One pass.</b> The legacy producer writes the objects BEFORE the audit and the metadata, so
 * a member cannot be given its final name while it streams past. Every member is staged under a
 * number, the two index files are read from the staging folder, the package is checked, and only
 * then are the objects renamed and the result committed. Nothing appears under a final name until
 * every check has passed.
 *
 * <p>Nothing here is a second implementation: format detection, gzip, the tar reader, name
 * validation, collision keys and the size limits are {@code unarchive}'s; the checksum and the
 * name grammar are {@code objpack}'s; the CSV reader is {@code rename}'s.
 */
public final class ObjectUnpack {

    // ---- inputs ------------------------------------------------------------
    /** A file, or a path whose last segment holds {@code *} / {@code ?} and matches exactly one file. */
    public String archive;
    public File outputDir;
    public String md5Check = "require";          // require | ifPresent | off
    public String onInconsistency = "fail";      // fail | warn
    public String onExisting = "fail";           // fail | replace
    public String metadataDelimiter = "auto";    // auto | one character
    /** Objects, not members: a package also holds audit, metadata and control (spec, U5). */
    public int maxObjects = 100000;
    public long maxObjectBytes = 2L * 1024 * 1024 * 1024;
    public long maxArchiveBytes = 20L * 1024 * 1024 * 1024;
    public long maxRatio = 200;
    /** 0 = the host's own limit (259 UTF-16 units on Windows, 4096 UTF-8 bytes on Linux). */
    public int maxPathLength = 0;
    public boolean preserveMtime = true;

    /** {@code os.name}; a field so the Windows rules can be exercised on a Linux box. */
    public String hostOs = System.getProperty("os.name");
    /**
     * The charset the JVM encodes file names with off Windows ({@code sun.jnu.encoding}, from the
     * service's locale). Under an ASCII locale a name such as {@code perch\u00e9.txt} cannot be
     * created at all (measured on Java 8 and 21); it is refused by name instead of failing at the
     * rename with nothing to say why.
     */
    public String fileNameEncoding = System.getProperty("sun.jnu.encoding");
    /** Names the staging folder; unique per run. */
    public String runId = Long.toHexString(System.nanoTime());
    public Consumer<String> progress;
    public BooleanSupplier aborted = new BooleanSupplier() {
        @Override public boolean getAsBoolean() { return false; }
    };

    // ---- results -----------------------------------------------------------
    public File archiveFile;
    public String tfId;
    public String transmissionDate;
    public int sequenceNr;
    public int versionNr;
    /** {@code versionNr + 1}, or empty at 999 where no next version exists. */
    public String nextVersionNr = "";
    public String submissionBaseName;
    /** Null when the audit file has none; empty when it has an empty one (the legacy default). */
    public String targetDestination;
    public int objectCount;
    public int metadataRows;
    public File metadataCsv;
    public String metadataDelimiterUsed = "";
    public File objectsDir;
    public File packageDir;
    public String compression = "none";
    public String md5 = "";
    public boolean md5Checked;
    public String hostRules = "";
    public final List<String> inconsistencies = new ArrayList<String>();
    public final List<String> warnings = new ArrayList<String>();

    /** Largest metadata file read into memory. A guard, not a format rule. */
    static final long MAX_METADATA_BYTES = 512L * 1024 * 1024;
    private static final int COPY_BUFFER = 64 * 1024;
    private static final long ABORT_CHECK_BYTES = 8L * 1024 * 1024;
    private static final String STAGING_PREFIX = ".objunpack-";
    private static final String STAGING_SUFFIX = ".part";

    /** One tar member, staged under a number. */
    private static final class Member {
        String name;
        File staged;
        long size;
        long mtime;
        /** The validated original name, for an object; null for audit, metadata, control, extras. */
        String restoreAs;
    }

    private HostRules rules;

    public void run() throws IOException {
        String md5Mode = oneOf("md5Check", md5Check, "require", "ifpresent", "off");
        String incMode = oneOf("onInconsistency", onInconsistency, "fail", "warn");
        String existMode = oneOf("onExisting", onExisting, "fail", "replace");
        rules = HostRules.detect(hostOs);
        if (rules == null) {
            throw new ObjUnpackException(Reason.CONFIGURATION, "this host is neither Windows nor Linux (os.name='"
                    + hostOs + "'); no file-name rules are defined for it");
        }
        hostRules = rules.label();
        if (maxObjects < 1) {
            throw new ObjUnpackException(Reason.CONFIGURATION, "maxObjects must be at least 1; got " + maxObjects);
        }

        archiveFile = resolveArchive();
        SubmissionName.Parsed parsed;
        try {
            parsed = SubmissionName.parse(archiveFile.getName());
        } catch (ObjPackException e) {
            throw new ObjUnpackException(Reason.CONFIGURATION, e.getMessage()
                    + ". The submission's identity is read from this name, so a renamed archive is refused");
        }
        tfId = parsed.name.tfId();
        transmissionDate = parsed.name.transmissionDate();
        sequenceNr = parsed.name.sequenceNr();
        versionNr = parsed.name.versionNr();
        nextVersionNr = versionNr < 999 ? Integer.toString(versionNr + 1) : "";
        submissionBaseName = parsed.baseAsWritten;
        say("objunpack: " + archiveFile.getName() + " (" + archiveFile.length() + " bytes); file-name rules: "
                + hostRules);
        if (!parsed.canonical()) {
            warn("the archive spells its base '" + parsed.baseAsWritten + "'; three-digit padding would be '"
                    + parsed.name.base() + "'");
        }
        if (versionNr == 999) {
            warn("versionNr is 999: there is no next version, nextVersionNr is empty");
        }

        if (outputDir == null) {
            throw new ObjUnpackException(Reason.CONFIGURATION, "outputDir is required");
        }
        if (!outputDir.isDirectory() && !outputDir.mkdirs()) {
            throw new ObjUnpackException(Reason.CONFIGURATION, "cannot create outputDir: " + outputDir.getAbsolutePath());
        }
        objectsDir = new File(outputDir, "objects");
        packageDir = new File(outputDir, "package");
        sweepLeftovers();
        if ("fail".equals(existMode)) {
            refuseExisting();
        }

        checkMd5(parsed, md5Mode);

        File staging = new File(outputDir, STAGING_PREFIX + runId + STAGING_SUFFIX);
        if (staging.exists() || !staging.mkdir()) {
            throw new ObjUnpackException(Reason.CONFIGURATION, "cannot create the staging folder " + staging.getAbsolutePath());
        }
        try {
            List<Member> members = extract(staging);
            List<Member> objects = join(members, parsed);
            if (!inconsistencies.isEmpty()) {
                for (String s : inconsistencies) {
                    say("INCONSISTENT: " + s);
                }
                if ("fail".equals(incMode)) {
                    throw new ObjUnpackException(Reason.INCONSISTENT, inconsistencies.size()
                            + " conformance check(s) failed; first: " + inconsistencies.get(0)
                            + ". Nothing was restored. onInconsistency=warn restores the package as it is");
                }
            }
            arrange(staging, members, objects);
            commit(staging, existMode);
            objectCount = objects.size();
            say("restored " + objectCount + " object(s) to " + objectsDir.getPath() + "; metadata: "
                    + metadataCsv.getName() + " (" + metadataRows + " row(s), delimiter '" + metadataDelimiterUsed + "')");
        } finally {
            int left = HostFiles.deleteTreeNoFollow(staging.toPath());
            if (left > 0) {
                warn(left + " staging entr" + (left == 1 ? "y" : "ies") + " could not be removed under " + staging.getPath());
            }
        }
    }

    // ------------------------------------------------------------------ locating

    private File resolveArchive() throws IOException {
        String a = archive == null ? "" : archive.trim();
        if (a.isEmpty()) {
            throw new ObjUnpackException(Reason.CONFIGURATION, "archive is required");
        }
        File f = new File(a);
        String leaf = f.getName();
        if (leaf.indexOf('*') < 0 && leaf.indexOf('?') < 0) {
            if (!f.isFile()) {
                throw new ObjUnpackException(Reason.CONFIGURATION, "archive not found: " + f.getAbsolutePath());
            }
            return f;
        }
        File dir = f.getAbsoluteFile().getParentFile();
        String[] names = dir == null ? null : dir.list();
        if (names == null) {
            throw new ObjUnpackException(Reason.CONFIGURATION, "the folder of archive cannot be listed: "
                    + (dir == null ? a : dir.getAbsolutePath()));
        }
        List<String> hits = new ArrayList<String>();
        for (String n : names) {
            if (FileMask.matches(n, leaf) && new File(dir, n).isFile()) {
                hits.add(n);
            }
        }
        Collections.sort(hits);
        if (hits.size() != 1) {
            throw new ObjUnpackException(Reason.CONFIGURATION, "archive '" + leaf + "' must match exactly one file in "
                    + dir.getAbsolutePath() + "; it matches " + hits.size()
                    + (hits.isEmpty() ? " (names are matched case-sensitively on every host)" : ": " + sample(hits, 10)));
        }
        return new File(dir, hits.get(0));
    }

    private void sweepLeftovers() {
        String[] names = outputDir.list();
        if (names == null) {
            return;
        }
        Arrays.sort(names);
        for (String n : names) {
            if (n.startsWith(STAGING_PREFIX) && n.endsWith(STAGING_SUFFIX)) {
                HostFiles.deleteTreeNoFollow(new File(outputDir, n).toPath());
                say("removed the staging folder of an interrupted run: " + n);
            }
        }
    }

    private void refuseExisting() throws IOException {
        for (File d : new File[] { objectsDir, packageDir }) {
            if (Files.exists(d.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new ObjUnpackException(Reason.EXISTS, d.getAbsolutePath() + " already exists. Point outputDir"
                        + " at an empty folder, or set onExisting=replace to delete it once the new content is complete");
            }
        }
    }

    // ------------------------------------------------------------------ checksum

    private void checkMd5(SubmissionName.Parsed parsed, String mode) throws IOException {
        if ("off".equals(mode)) {
            say("md5Check=off: the checksum file is not read");
            return;
        }
        File side = new File(archiveFile.getAbsoluteFile().getParentFile(), parsed.baseAsWritten + ".md5");
        if (!side.isFile()) {
            if ("require".equals(mode)) {
                throw new ObjUnpackException(Reason.MD5_MISSING, "no checksum file " + side.getAbsolutePath()
                        + ". A package is the archive and its .md5; md5Check=ifPresent or off unpacks without one");
            }
            warn("no checksum file " + side.getName() + ": the archive was NOT verified");
            return;
        }
        if (side.length() > 1024) {
            throw new ObjUnpackException(Reason.MD5_FORMAT, side.getName() + " is " + side.length()
                    + " bytes; a checksum file holds 32 hex characters");
        }
        String declared = new String(Files.readAllBytes(side.toPath()), StandardCharsets.ISO_8859_1).trim();
        if (!declared.matches("[0-9a-fA-F]{32}")) {
            throw new ObjUnpackException(Reason.MD5_FORMAT, side.getName() + " does not hold a bare MD5 (32 hex"
                    + " characters, no file name): '" + EntryName.printable(declared.length() > 80
                    ? declared.substring(0, 80) + "..." : declared) + "'");
        }
        if (!declared.equals(declared.toLowerCase(Locale.ROOT))) {
            warn(side.getName() + " is not in lower case, which the Transarch specification requires (3.5)");
        }
        long t0 = System.nanoTime();
        String actual = Md5.ofFile(archiveFile);
        if (!actual.equals(declared.toLowerCase(Locale.ROOT))) {
            throw new ObjUnpackException(Reason.MD5_MISMATCH, archiveFile.getName() + " has MD5 " + actual + " but "
                    + side.getName() + " says " + declared.toLowerCase(Locale.ROOT) + ". Nothing was read from the archive");
        }
        md5 = actual;
        md5Checked = true;
        say("checksum verified in " + secs(t0) + ": " + actual);
    }

    // ------------------------------------------------------------------ extraction

    private List<Member> extract(File staging) throws IOException {
        File mdir = new File(staging, "m");
        if (!mdir.mkdir()) {
            throw new IOException("cannot create " + mdir.getAbsolutePath());
        }
        byte[] head = new byte[ArchiveFormat.HEAD_BYTES];
        int n = 0;
        InputStream probe = new FileInputStream(archiveFile);
        try {
            while (n < head.length) {
                int r = probe.read(head, n, head.length - n);
                if (r < 0) break;
                n += r;
            }
        } finally {
            probe.close();
        }
        ArchiveFormat.Decision d = ArchiveFormat.decide(archiveFile.getName(), head, n, ArchiveFormat.Requested.parse("auto"));
        if (d.warning != null) {
            warn(d.warning);
        }
        if (d.handling == ArchiveFormat.Handling.ZIP) {
            throw new ObjUnpackException(Reason.CONFIGURATION, archiveFile.getName()
                    + " is a zip; a Transarch object package is a tar or a tar.gz");
        }
        List<Member> members = new ArrayList<Member>();
        NameIndex index = new NameIndex(rules);
        Budget budget = new Budget((long) maxObjects + 3, maxObjectBytes, maxArchiveBytes, maxRatio);
        long t0 = System.nanoTime();
        InputStream raw = new BufferedInputStream(new FileInputStream(archiveFile), COPY_BUFFER);
        try {
            InputStream tarIn = raw;
            GzipSupport.Counting counter = null;
            if (d.handling != ArchiveFormat.Handling.TAR) {
                GzipSupport.Opened o = GzipSupport.open(raw, archiveFile.getName(), d.handling);
                if (!o.innerIsTar) {
                    throw new ObjUnpackException(Reason.CONFIGURATION, archiveFile.getName()
                            + " is a gzip that does not contain a tar");
                }
                tarIn = o.decompressed;
                counter = o.compressedCounter;
                compression = "gzip";
            }
            TarStreamReader reader = new TarStreamReader(tarIn);
            byte[] buf = new byte[COPY_BUFFER];
            TarStreamReader.Entry e;
            while ((e = reader.next()) != null) {
                stopIfAborted();
                budget.startEntry(e.name);
                if (e.type != TarStreamReader.Type.FILE) {
                    throw new ObjUnpackException(Reason.MEMBER, "'" + EntryName.printable(e.name) + "' is a "
                            + e.type.name().toLowerCase(Locale.ROOT)
                            + "; a package holds regular files only, so it is refused, not extracted");
                }
                EntryName.Name nm = EntryName.validate(e.name, false, rules, false);
                if (nm.segments.size() != 1) {
                    throw new ObjUnpackException(Reason.MEMBER, "'" + EntryName.printable(e.name)
                            + "' is not a bare file name; a package is flat");
                }
                index.add(nm);
                Member m = new Member();
                m.name = nm.path;
                m.staged = new File(mdir, Integer.toString(members.size() + 1));
                m.mtime = e.mtime;
                InputStream payload = reader.payload();
                OutputStream out = new BufferedOutputStream(new FileOutputStream(m.staged), COPY_BUFFER);
                try {
                    long sinceCheck = 0;
                    int r;
                    while ((r = payload.read(buf)) > 0) {
                        out.write(buf, 0, r);
                        m.size += r;
                        budget.written(r, -1, counter == null ? -1 : counter.count());
                        sinceCheck += r;
                        if (sinceCheck >= ABORT_CHECK_BYTES) {
                            sinceCheck = 0;
                            stopIfAborted();
                        }
                    }
                } finally {
                    out.close();
                }
                members.add(m);
            }
            if (reader.endMarkerMissing()) {
                warn("the tar has no end-of-archive marker; every member was read to its declared size");
            }
        } finally {
            raw.close();
        }
        say("read " + members.size() + " member(s), " + budget.archiveBytes() + " bytes, in " + secs(t0));
        return members;
    }

    // ------------------------------------------------------------------ the join and the checks

    /** Returns the objects in audit order, each with {@code restoreAs} set. */
    private List<Member> join(List<Member> members, SubmissionName.Parsed parsed) throws IOException {
        Map<String, Member> byName = new HashMap<String, Member>();
        List<Member> audits = new ArrayList<Member>();
        List<Member> metas = new ArrayList<Member>();
        List<Member> controls = new ArrayList<Member>();
        for (Member m : members) {
            byName.put(m.name, m);
            if (m.name.endsWith(".audit.json")) audits.add(m);
            else if (m.name.endsWith(".metadata.csv")) metas.add(m);
            else if (m.name.endsWith(".control")) controls.add(m);
        }
        if (audits.size() != 1) {
            throw new ObjUnpackException(Reason.AUDIT, "the package has " + audits.size() + " *.audit.json members"
                    + (audits.isEmpty() ? "" : " (" + names(audits) + ")")
                    + "; exactly one is needed to know which member is which object");
        }
        Member auditM = audits.get(0);
        AuditJson.Document doc;
        try {
            doc = AuditJson.read(auditM.staged, auditM.name);
        } catch (ObjPackException e) {
            throw new ObjUnpackException(Reason.AUDIT, e.getMessage());
        }
        if (doc.files == null || doc.files.isEmpty()) {
            throw new ObjUnpackException(Reason.AUDIT, auditM.name + " lists no submission_object_files");
        }
        targetDestination = doc.targetDestination;

        // ---- the metadata member
        Member metaM = doc.metadataFileName == null ? null : byName.get(doc.metadataFileName);
        if (metaM == null) {
            if (metas.size() != 1) {
                throw new ObjUnpackException(Reason.METADATA, "the audit names the metadata file '"
                        + doc.metadataFileName + "', which is not in the package, and the package has " + metas.size()
                        + " *.metadata.csv members" + (metas.isEmpty() ? "" : " (" + names(metas) + ")"));
            }
            metaM = metas.get(0);
            inconsistencies.add("metadata_file_name in the audit is '" + doc.metadataFileName
                    + "' but the package's metadata file is '" + metaM.name + "'");
        }
        if (metaM.size > MAX_METADATA_BYTES) {
            throw new ObjUnpackException(Reason.METADATA, metaM.name + " is " + metaM.size + " bytes, over the "
                    + MAX_METADATA_BYTES + " this step reads into memory");
        }
        String text = decodeUtf8(Files.readAllBytes(metaM.staged.toPath()), metaM.name);
        char delim = delimiterOf(text, metaM.name);
        metadataDelimiterUsed = String.valueOf(delim);
        PsCsvReader.Table table;
        try {
            table = PsCsvReader.parse(text, delim);
        } catch (IllegalArgumentException e) {
            throw new ObjUnpackException(Reason.METADATA, metaM.name + ": " + e.getMessage());
        }
        int cId = column(table, "object_id");
        int cName = column(table, "original_object_name");
        int cMime = column(table, "mime_type");
        if (cId < 0) {
            throw new ObjUnpackException(Reason.METADATA, metaM.name + " has no object_id column (read with delimiter '"
                    + delim + "'; header: " + sample(table.header, 8) + ")");
        }
        if (cName < 0) {
            throw new ObjUnpackException(Reason.METADATA, metaM.name + " has no original_object_name column, so the"
                    + " package does not say what its objects were called (the legacy script in MapMode=Order does not"
                    + " require the column). Header: " + sample(table.header, 8));
        }
        metadataRows = table.rows.size();
        Map<String, Integer> rowOfId = new HashMap<String, Integer>();
        int rowsWithoutId = 0;
        for (int i = 0; i < table.rows.size(); i++) {
            String id = canonicalId(table.rows.get(i)[cId]);
            if (id.isEmpty()) {
                rowsWithoutId++;
                continue;
            }
            Integer prev = rowOfId.put(id, Integer.valueOf(i));
            if (prev != null) {
                throw new ObjUnpackException(Reason.MAPPING, "object_id " + id + " is on two metadata rows ("
                        + (prev.intValue() + 1) + " and " + (i + 1) + "); which one describes the object cannot be decided");
            }
        }
        if (rowsWithoutId > 0) {
            inconsistencies.add(rowsWithoutId + " metadata row(s) have an empty object_id");
        }

        // ---- every audit item to its member and its row
        List<Member> objects = new ArrayList<Member>();
        Map<String, String> idOfFile = new HashMap<String, String>();
        Map<String, String> fileOfId = new HashMap<String, String>();
        Map<String, List<String>> idsByKey = new LinkedHashMap<String, List<String>>();
        Map<String, List<String>> spellingsByKey = new HashMap<String, List<String>>();
        String objectsBase = objectsDir.getAbsolutePath();
        int maxPath = maxPathLength > 0 ? maxPathLength : rules.defaultMaxPath();
        int mimeDiffers = 0;
        String mimeExample = null;
        int item = 0;
        for (AuditJson.FileItem f : doc.files) {
            item++;
            if (f.fileName == null || f.fileName.isEmpty() || f.objectId == null || f.objectId.trim().isEmpty()) {
                throw new ObjUnpackException(Reason.AUDIT, "item " + item + " of submission_object_files in " + auditM.name
                        + " has no " + (f.fileName == null || f.fileName.isEmpty() ? "file_name" : "object_id"));
            }
            String id = canonicalId(f.objectId);
            if (idOfFile.put(f.fileName, id) != null) {
                throw new ObjUnpackException(Reason.MAPPING, "the audit lists '" + f.fileName + "' twice");
            }
            String other = fileOfId.put(id, f.fileName);
            if (other != null) {
                throw new ObjUnpackException(Reason.MAPPING, "the audit gives object_id " + id + " to two files: '"
                        + other + "' and '" + f.fileName + "'");
            }
            Member m = byName.get(f.fileName);
            if (m == null) {
                throw new ObjUnpackException(Reason.MAPPING, "the audit lists '" + f.fileName + "' (object_id " + id
                        + "), which is not in the archive");
            }
            if (m == auditM || m == metaM || controls.contains(m)) {
                throw new ObjUnpackException(Reason.MAPPING, "the audit lists '" + f.fileName
                        + "' as an object, but it is the package's own audit, metadata or control file");
            }
            Integer row = rowOfId.get(id);
            if (row == null) {
                throw new ObjUnpackException(Reason.MAPPING, "object_id " + id + " ('" + f.fileName
                        + "') has no row in " + metaM.name + ", so its original name is unknown");
            }
            String[] cells = table.rows.get(row.intValue());
            String original = cells[cName] == null ? "" : cells[cName].trim();
            m.restoreAs = hostName(original, id, row.intValue() + 1);
            EntryName.checkLength(objectsBase, EntryName.validate(m.restoreAs, false, rules, false), maxPath, rules);
            String key = rules == HostRules.LINUX ? m.restoreAs : EntryName.collisionKey(m.restoreAs);
            List<String> ids = idsByKey.get(key);
            if (ids == null) {
                ids = new ArrayList<String>();
                idsByKey.put(key, ids);
                spellingsByKey.put(key, new ArrayList<String>());
            }
            ids.add(id);
            if (!spellingsByKey.get(key).contains(m.restoreAs)) {
                spellingsByKey.get(key).add(m.restoreAs);
            }
            if (cMime >= 0 && f.mimeType != null) {
                String mm = cells[cMime] == null ? "" : cells[cMime].trim();
                if (!mm.equals(f.mimeType.trim())) {
                    mimeDiffers++;
                    if (mimeExample == null) {
                        mimeExample = "object_id " + id + ": audit '" + f.mimeType + "', metadata '" + mm + "'";
                    }
                }
            }
            objects.add(m);
        }
        refuseDuplicates(idsByKey, spellingsByKey);

        // ---- conformance: governed by onInconsistency
        long declared = doc.recordCount == null ? -1 : doc.recordCount.longValue();
        if (declared != objects.size() || metadataRows != objects.size()) {
            inconsistencies.add("record_count is " + (doc.recordCount == null ? "absent" : String.valueOf(declared))
                    + ", the audit lists " + objects.size() + " object(s), the metadata has " + metadataRows + " row(s)");
        }
        int rowsWithoutObject = 0;
        for (String id : rowOfId.keySet()) {
            if (!fileOfId.containsKey(id)) rowsWithoutObject++;
        }
        if (rowsWithoutObject > 0) {
            inconsistencies.add(rowsWithoutObject + " metadata row(s) describe an object the audit does not list");
        }
        List<String> extras = new ArrayList<String>();
        List<String> foreign = new ArrayList<String>();
        String stem = parsed.baseAsWritten + ".";
        for (Member m : members) {
            if (m.restoreAs == null && m != auditM && m != metaM && !controls.contains(m)) {
                extras.add(m.name);
            }
            if (!m.name.startsWith(stem)) {
                foreign.add(m.name);
            }
        }
        if (!extras.isEmpty()) {
            inconsistencies.add(extras.size() + " member(s) are not listed in the audit: " + sample(extras, 5)
                    + " (under onInconsistency=warn they are left in package/, never in objects/)");
        }
        if (!foreign.isEmpty()) {
            inconsistencies.add(foreign.size() + " member(s) do not start with the archive's base name '"
                    + parsed.baseAsWritten + "': " + sample(foreign, 5));
        }
        if (controls.size() != 1) {
            inconsistencies.add("the package has " + controls.size() + " *.control members; exactly one is expected");
        } else if (controls.get(0).size != 0) {
            inconsistencies.add(controls.get(0).name + " is " + controls.get(0).size + " bytes; a control file is empty");
        }
        identity("transmission_date", doc.transmissionDate, transmissionDate, false);
        identity("sequence_number", doc.sequenceNumber, Integer.toString(sequenceNr), true);
        identity("version_number", doc.versionNumber, Integer.toString(versionNr), true);
        if (mimeDiffers > 0) {
            inconsistencies.add("mime_type differs between audit and metadata for " + mimeDiffers
                    + " object(s); first: " + mimeExample);
        }
        if (targetDestination == null || targetDestination.trim().isEmpty()) {
            say("the audit has no TargetDestination (the legacy script writes it empty when not given);"
                    + " ${targetDestination} is empty");
        }
        metadataCsv = new File(packageDir, metaM.name);
        return objects;
    }

    /** The audit's own date / sequence / version against the archive's name, which wins. */
    private void identity(String key, String inAudit, String fromName, boolean numeric) {
        String a = inAudit == null ? null : inAudit.trim();
        boolean same;
        if (a == null) {
            same = false;
        } else if (numeric) {
            same = a.matches("[0-9]{1,9}") && Integer.parseInt(a) == Integer.parseInt(fromName);
        } else {
            same = a.equals(fromName);
        }
        if (!same) {
            inconsistencies.add(key + " in the audit is " + (a == null ? "absent" : "'" + a + "'")
                    + " but the archive's name says " + fromName + " (the name is used)");
        }
    }

    /**
     * The original name as ONE file name of this host, or a refusal naming the row and the rule.
     * Refused, never normalised: a name the validator would have to change is not restored under
     * something else.
     */
    private String hostName(String original, String id, int row) throws IOException {
        String where = "object_id " + id + " (metadata row " + row + ")";
        if (original.isEmpty()) {
            throw new ObjUnpackException(Reason.NAME, where + " has an empty original_object_name");
        }
        EntryName.Name nm;
        try {
            nm = EntryName.validate(original, false, rules, false);
        } catch (UnarchiveException e) {
            throw new ObjUnpackException(Reason.NAME, where + ": the original name cannot be a file name on this host ("
                    + hostRules + " rules) - " + e.getMessage());
        }
        if (nm.segments.size() != 1 || !nm.path.equals(original)) {
            throw new ObjUnpackException(Reason.NAME, where + ": the original name '" + EntryName.printable(original)
                    + "' is a path, not a file name (" + hostRules + " rules); the folder structure of a package"
                    + " built from sub-folders cannot be rebuilt");
        }
        if (rules == HostRules.LINUX && !encodable(nm.path)) {
            throw new ObjUnpackException(Reason.NAME, where + ": the original name '" + EntryName.printable(original)
                    + "' cannot be written by this JVM, whose file-name encoding is " + fileNameEncoding
                    + " (sun.jnu.encoding). Start the service under a UTF-8 locale (for example LANG=C.UTF-8);"
                    + " the Platform page shows the encoding in use");
        }
        return nm.path;
    }

    private boolean encodable(String name) {
        if (fileNameEncoding == null || fileNameEncoding.trim().isEmpty()) {
            return true;
        }
        try {
            return java.nio.charset.Charset.forName(fileNameEncoding.trim()).newEncoder().canEncode(name);
        } catch (RuntimeException e) {
            return true;   // an encoding this JVM cannot name is not a reason to refuse a package
        }
    }

    /** Every clash is reported, not only the first: the names are the feed's, and the author needs the list. */
    private void refuseDuplicates(Map<String, List<String>> idsByKey, Map<String, List<String>> spellings)
            throws IOException {
        int names = 0;
        int objects = 0;
        List<String> examples = new ArrayList<String>();
        for (Map.Entry<String, List<String>> e : idsByKey.entrySet()) {
            if (e.getValue().size() < 2) continue;
            names++;
            objects += e.getValue().size();
            if (examples.size() < 10) {
                List<String> sp = spellings.get(e.getKey());
                examples.add((sp.size() == 1 ? "'" + EntryName.printable(sp.get(0)) + "'"
                        : "names differing only in case " + printable(sp))
                        + " for object_id " + sample(e.getValue(), 10));
            }
        }
        if (names > 0) {
            throw new ObjUnpackException(Reason.DUPLICATE_NAME, names + " original name(s) are shared by " + objects
                    + " objects, which would be restored to the same file (" + hostRules + " rules): "
                    + sample(examples, 10) + ". Nothing was restored: renaming them would break the link between"
                    + " the metadata and the files");
        }
    }

    // ------------------------------------------------------------------ arranging and committing

    private void arrange(File staging, List<Member> members, List<Member> objects) throws IOException {
        File so = new File(staging, "objects");
        File sp = new File(staging, "package");
        if (!so.mkdir() || !sp.mkdir()) {
            throw new IOException("cannot create the staging layout under " + staging.getAbsolutePath());
        }
        int maxPath = maxPathLength > 0 ? maxPathLength : rules.defaultMaxPath();
        String packageBase = packageDir.getAbsolutePath();
        for (Member m : members) {
            stopIfAborted();
            File to;
            if (m.restoreAs != null) {
                to = new File(so, m.restoreAs);
            } else {
                EntryName.checkLength(packageBase, EntryName.validate(m.name, false, rules, false), maxPath, rules);
                to = new File(sp, m.name);
            }
            if (!HostFiles.renameNoReplace(m.staged, to)) {
                throw new ObjUnpackException(Reason.COMMIT, "cannot give '" + EntryName.printable(to.getName())
                        + "' its name in the staging folder (the name is already taken, or the host refused it)");
            }
            if (preserveMtime && m.mtime > 0 && !to.setLastModified(m.mtime * 1000L)) {
                warn("could not set the time of " + to.getName());
            }
        }
    }

    private void commit(File staging, String existMode) throws IOException {
        stopIfAborted();
        File[][] moves = { { new File(staging, "objects"), objectsDir }, { new File(staging, "package"), packageDir } };
        for (File[] mv : moves) {
            if (Files.exists(mv[1].toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                if (!"replace".equals(existMode)) {
                    throw new ObjUnpackException(Reason.EXISTS, mv[1].getAbsolutePath() + " appeared while the step ran");
                }
                int left = HostFiles.deleteTreeNoFollow(mv[1].toPath());
                if (left > 0) {
                    throw new ObjUnpackException(Reason.COMMIT, "onExisting=replace could not remove " + left
                            + " entr" + (left == 1 ? "y" : "ies") + " under " + mv[1].getAbsolutePath());
                }
                say("onExisting=replace: removed the previous " + mv[1].getName() + "/");
            }
        }
        for (int k = 0; k < moves.length; k++) {
            boolean done = false;
            for (int attempt = 0; attempt < 3 && !done; attempt++) {
                done = HostFiles.renameNoReplace(moves[k][0], moves[k][1]);
                if (!done) {
                    try {
                        Thread.sleep(200L * (attempt + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            if (!done) {
                throw new ObjUnpackException(Reason.COMMIT, "cannot move the result to " + moves[k][1].getAbsolutePath()
                        + (k == 1 ? ". " + objectsDir.getAbsolutePath() + " IS already in place: the result is incomplete" : ""));
            }
        }
    }

    // ------------------------------------------------------------------ small things

    private String decodeUtf8(byte[] b, String name) throws IOException {
        int skip = b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b, skip, b.length - skip)).toString();
        } catch (CharacterCodingException e) {
            throw new ObjUnpackException(Reason.METADATA, name + " is not valid UTF-8, which the Transarch specification"
                    + " requires (3.2); its names cannot be read without guessing");
        }
    }

    /** {@code auto}: the character that follows the first header, which the format fixes as object_id. */
    private char delimiterOf(String text, String name) throws IOException {
        String p = metadataDelimiter == null ? "auto" : metadataDelimiter;
        if (!"auto".equalsIgnoreCase(p.trim())) {
            String v = "tab".equalsIgnoreCase(p.trim()) ? "\t" : p;
            if (v.length() != 1) {
                v = v.trim();
            }
            if (v.length() != 1 || v.charAt(0) == '"' || v.charAt(0) == '\r' || v.charAt(0) == '\n') {
                throw new ObjUnpackException(Reason.CONFIGURATION, "metadataDelimiter must be auto, tab or one character; got '"
                        + metadataDelimiter + "'");
            }
            return v.charAt(0);
        }
        String head = "object_id";
        int at = -1;
        if (text.regionMatches(true, 0, head, 0, head.length())) {
            at = head.length();
        } else if (text.startsWith("\"") && text.regionMatches(true, 1, head, 0, head.length())
                && text.startsWith("\"", 1 + head.length())) {
            at = head.length() + 2;
        }
        char c = at >= 0 && at < text.length() ? text.charAt(at) : 0;
        if (at < 0 || c == 0 || c == '\r' || c == '\n' || c == '"' || Character.isLetterOrDigit(c) || c == '_') {
            throw new ObjUnpackException(Reason.METADATA, name + " does not start with an object_id column followed by"
                    + " a delimiter, so the delimiter cannot be read from it. Set metadataDelimiter");
        }
        return c;
    }

    private static int column(PsCsvReader.Table t, String name) {
        for (int i = 0; i < t.header.size(); i++) {
            if (name.equalsIgnoreCase(t.header.get(i).trim())) {
                return i;
            }
        }
        return -1;
    }

    /** {@code 000001} and {@code 1} are one id; anything that is not digits is compared as written. */
    static String canonicalId(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (!s.isEmpty() && s.matches("[0-9]+")) {
            int i = 0;
            while (i < s.length() - 1 && s.charAt(i) == '0') i++;
            return s.substring(i);
        }
        return s;
    }

    private String oneOf(String param, String value, String... allowed) throws IOException {
        String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        for (String a : allowed) {
            if (a.equals(v)) return v;
        }
        throw new ObjUnpackException(Reason.CONFIGURATION, param + " must be one of " + Arrays.toString(allowed)
                + " (any letter case); got '" + value + "'");
    }

    private void stopIfAborted() throws IOException {
        if (aborted != null && aborted.getAsBoolean()) {
            throw new ObjUnpackException(Reason.STOPPED, "stopped; nothing was restored");
        }
    }

    private static String names(List<Member> l) {
        List<String> n = new ArrayList<String>();
        for (Member m : l) n.add(m.name);
        return sample(n, 5);
    }

    private static String printable(List<String> l) {
        List<String> n = new ArrayList<String>();
        for (String s : l) n.add("'" + EntryName.printable(s) + "'");
        return sample(n, 5);
    }

    private static String sample(List<String> l, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size() && i < max; i++) {
            if (i > 0) sb.append(", ");
            sb.append(l.get(i));
        }
        if (l.size() > max) sb.append(", ... (").append(l.size()).append(" in all)");
        return sb.toString();
    }

    private static String secs(long t0) {
        return String.format(Locale.ROOT, "%.1f s", Double.valueOf((System.nanoTime() - t0) / 1e9));
    }

    private void warn(String s) {
        warnings.add(s);
        say("WARNING: " + s);
    }

    private void say(String s) {
        if (progress != null) {
            progress.accept(s);
        }
    }
}
