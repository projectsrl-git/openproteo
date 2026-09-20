package com.legalarchive.orchestrator.objpack;

import com.legalarchive.orchestrator.ds.CsvWriter;
import com.legalarchive.orchestrator.elar.FlatCsvReader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds a Transarch TAR-packaged object submission from a source metadata CSV and a directory of
 * objects: the renamed objects, the metadata CSV, the audit JSON, the control file, the tar and the
 * md5. Spring-free on purpose, so the whole thing can be run against real files outside the
 * container.
 *
 * <p>Replaces {@code Object_CS_Archiving_v4_UK_PS5.ps1}. The one structural difference is that the
 * objects are <b>not staged</b>: a tar member's name is independent of where its content is read
 * from, so the Transarch rename is achieved by naming the member while streaming the bytes straight
 * out of the landing zone. The script copied every object into {@code %TEMP%} first, which at the
 * documented 20 GB ceiling is 20 GB written and read to produce nothing. Set {@code emitObjects} to
 * materialise the renamed copies anyway — useful for inspection and for the non-TAR path, but not
 * needed for a TAR submission, where only the tar and the md5 are delivered.
 *
 * <p>Order of work, which is also the order the failures are worth hitting in: resolve names, pair
 * rows with objects, pre-flight, then write. Nothing is written until every check that can be made
 * cheaply has been made, so a submission that is going to be rejected fails before it has produced
 * a 20 GB archive.
 */
public final class ObjectPack {

    // ---- identity ----------------------------------------------------------
    public String tfId;
    public String transmissionDate;          // yyyyMMdd; never defaulted to today (Gate 0 9.8)
    public int sequenceNr = 1;
    public int versionNr = 1;
    public String targetDestination;

    // ---- inputs ------------------------------------------------------------
    public File metadataCsv;
    public char inDelimiter = 0;             // 0 = detect from the header
    public char quoteChar = '"';
    public String inCharset = "UTF-8";
    public File objectsDir;
    public String objectSource;              // path | name | order; default depends on the mapping
    public boolean recurse = false;
    public String orderBy = "name";          // name | path, for objectSource=order
    public List<String> include = new ArrayList<String>(Arrays.asList("*"));
    public List<String> exclude = new ArrayList<String>();
    public File dataschema;                  // optional; pre-flight only, never packaged

    // ---- mapping: six fixed roles, so they are parameters, not a <column> list ----
    public String mapObjectId;
    public String mapRecordBusinessDate;
    public String mapMimeType;
    public String mapOriginalObjectName;
    public String mapObjectPath;
    public String mapNameLabel;
    public String businessDateFormat;        // optional source format, e.g. yyyy-MM-dd

    // ---- output ------------------------------------------------------------
    public File outputDir;
    public char outDelimiter = ';';
    public boolean emitObjects = false;
    public String compression = "none";

    // ---- limits (spec §1, CS submissions) ----------------------------------
    public long maxObjectBytes = 2L * 1024 * 1024 * 1024;
    public long maxSubmissionBytes = 20L * 1024 * 1024 * 1024;
    public int maxObjects = 100000;
    public boolean failOnOversize = true;
    public boolean failOnStaleBusinessDate = false;   // Gate 0 9.7 open: warn by default
    public int businessDateMonths = 10;
    public String onMissingObject = "fail";           // Gate 0 9.9 open: the script's behaviour

    // ---- results -----------------------------------------------------------
    public String submissionBaseName;
    public int objectCount;
    public int metadataRows;
    public int skippedRows;
    public File tarFile;
    public File md5File;
    public long tarBytes;
    public String md5;
    public final List<String> warnings = new ArrayList<String>();

    private static final String[] FALLBACK = {
            "object_id", "record_business_date", "mime_type", "original_object_name"
    };

    /** One metadata row paired with the object it describes. */
    private static final class Pair {
        Map<String, String> row;
        long lineNo;
        File object;
        int objectId;
        String mimeType;
        String businessDate;
        String originalName;
        String label;
        String memberName;
    }

    public void run() throws IOException {
        String comp = normaliseCompression(compression);
        SubmissionName name = new SubmissionName(tfId, transmissionDate, sequenceNr, versionNr);
        submissionBaseName = name.base();

        if (outputDir == null) {
            throw new ObjPackException("outputDir is required");
        }
        if (!outputDir.isDirectory() && !outputDir.mkdirs()) {
            throw new ObjPackException("cannot create outputDir: " + outputDir.getAbsolutePath());
        }
        if (objectsDir == null || !objectsDir.isDirectory()) {
            throw new ObjPackException("objectsDir not found: "
                    + (objectsDir == null ? "null" : objectsDir.getAbsolutePath()));
        }
        if (metadataCsv == null || !metadataCsv.isFile()) {
            throw new ObjPackException("metadataCsv not found: "
                    + (metadataCsv == null ? "null" : metadataCsv.getAbsolutePath()));
        }

        List<Pair> pairs = readAndPair();
        objectCount = pairs.size();
        assignObjectIds(pairs);

        int width = SubmissionName.oidWidth(objectCount);
        for (Pair p : pairs) {
            p.memberName = name.objectFile(p.objectId, width, p.label,
                    SubmissionName.extensionOf(p.object.getName()));
        }
        preflight(pairs);

        writeAll(name, pairs);
    }

    // ------------------------------------------------------------------ reading and pairing

    private List<Pair> readAndPair() throws IOException {
        char delim = inDelimiter != 0 ? inDelimiter : detectDelimiter(metadataCsv);
        FlatCsvReader r = new FlatCsvReader(metadataCsv, inCharset, true, delim, quoteChar);
        List<Pair> pairs = new ArrayList<Pair>();
        List<String> headers;
        try {
            headers = Arrays.asList(r.header());
            String cId    = resolve(mapObjectId, FALLBACK[0], headers, false);
            String cDate  = resolve(mapRecordBusinessDate, FALLBACK[1], headers, true);
            String cMime  = resolve(mapMimeType, FALLBACK[2], headers, true);
            String cName  = resolve(mapOriginalObjectName, FALLBACK[3], headers, true);
            String cPath  = resolve(mapObjectPath, null, headers, false);
            String cLabel = resolve(mapNameLabel, null, headers, false);

            String mode = trim(objectSource);
            if (mode == null || mode.isEmpty()) {
                mode = cPath != null ? "path" : "order";
            }
            mode = mode.toLowerCase(Locale.ROOT);
            if (!"path".equals(mode) && !"name".equals(mode) && !"order".equals(mode)) {
                throw new ObjPackException("objectSource must be path, name or order; got '" + objectSource + "'");
            }
            if ("path".equals(mode) && cPath == null) {
                throw new ObjPackException("objectSource=path needs map.objectPath to name a column of "
                        + metadataCsv.getName());
            }

            List<File> listing = "order".equals(mode) ? listObjects() : null;
            Map<String, List<File>> byName = "name".equals(mode) ? indexByName() : null;

            int headerSize = r.headerSize();
            FlatCsvReader.Row row;
            while ((row = r.next()) != null) {
                metadataRows++;
                if (row.fields.length != headerSize) {
                    // Almost always a record split by a bare newline. FlatCsvReader is line-based,
                    // so it cannot rejoin one; dequote or csvsql exist upstream for exactly that.
                    throw new ObjPackException("line " + row.lineNo + " of " + metadataCsv.getName()
                            + " has " + row.fields.length + " fields but the header has " + headerSize
                            + ". A record split across lines must be normalised upstream (dequote, csvsql).");
                }
                Map<String, String> m = r.asColumnMap(row);
                Pair p = new Pair();
                p.row = m;
                p.lineNo = row.lineNo;
                p.mimeType = trim(m.get(cMime));
                p.businessDate = businessDate(trim(m.get(cDate)), row.lineNo);
                p.originalName = trim(m.get(cName));
                p.label = cLabel == null ? null : trim(m.get(cLabel));
                if (cId != null) {
                    String v = trim(m.get(cId));
                    if (v == null || v.isEmpty()) {
                        throw new ObjPackException("line " + row.lineNo + ": mapped object_id column '"
                                + cId + "' is empty");
                    }
                    try {
                        p.objectId = Integer.parseInt(v);
                    } catch (NumberFormatException e) {
                        throw new ObjPackException("line " + row.lineNo + ": object_id '" + v
                                + "' is not an integer (spec 3.2 requires an integer starting at 1)");
                    }
                } else {
                    p.objectId = -1;                 // assigned after the rows are known
                }
                p.object = locate(mode, m, cPath, p, listing, pairs.size(), byName);
                if (p.object == null) {
                    skippedRows++;
                    continue;                        // only reachable with onMissingObject=skip
                }
                pairs.add(p);
            }
        } finally {
            r.close();
        }
        if (pairs.isEmpty()) {
            throw new ObjPackException("no objects were paired with " + metadataCsv.getName()
                    + "; a submission needs at least one object");
        }
        return pairs;
    }

    private File locate(String mode, Map<String, String> m, String cPath, Pair p,
                        List<File> listing, int index, Map<String, List<File>> byName) {
        File f;
        if ("path".equals(mode)) {
            String rel = trim(m.get(cPath));
            if (rel == null || rel.isEmpty()) {
                return missing("line " + p.lineNo + ": the object path column is empty");
            }
            f = resolveInside(rel, p.lineNo);
        } else if ("name".equals(mode)) {
            if (p.originalName == null || p.originalName.isEmpty()) {
                return missing("line " + p.lineNo + ": original object name is empty, so the object "
                        + "cannot be found by name");
            }
            List<File> hits = byName.get(p.originalName.toLowerCase(Locale.ROOT));
            if (hits == null || hits.isEmpty()) {
                return missing("line " + p.lineNo + ": no object named '" + p.originalName + "' under "
                        + objectsDir.getAbsolutePath());
            }
            if (hits.size() > 1) {
                // Picking one would archive a plausible wrong document under a right-looking name.
                StringBuilder sb = new StringBuilder();
                for (File h : hits) {
                    sb.append("\n  ").append(h.getAbsolutePath());
                }
                throw new ObjPackException("line " + p.lineNo + ": '" + p.originalName
                        + "' matches " + hits.size() + " files and the right one cannot be guessed:" + sb);
            }
            f = hits.get(0);
        } else {
            if (index >= listing.size()) {
                return missing("row " + (index + 1) + " has no object: the listing holds "
                        + listing.size() + " files");
            }
            f = listing.get(index);
        }
        if (f == null || !f.isFile()) {
            return missing("line " + p.lineNo + ": object not found: "
                    + (f == null ? "null" : f.getAbsolutePath()));
        }
        return f;
    }

    private File missing(String why) {
        if ("skip".equalsIgnoreCase(trim(onMissingObject))) {
            warnings.add("skipped: " + why);
            return null;
        }
        throw new ObjPackException(why);
    }

    /** Resolves a CSV-supplied path against objectsDir and refuses anything that escapes it. */
    private File resolveInside(String rel, long lineNo) {
        String norm = rel.replace('\\', '/').trim();
        if (norm.startsWith("/") || norm.matches("^[A-Za-z]:/.*")) {
            throw new ObjPackException("line " + lineNo + ": the object path must be relative to "
                    + "objectsDir, got the absolute path '" + rel + "'");
        }
        File f = new File(objectsDir, norm);
        String root, child;
        try {
            root = objectsDir.getCanonicalPath();
            child = f.getCanonicalPath();
        } catch (IOException e) {
            throw new ObjPackException("line " + lineNo + ": cannot resolve '" + rel + "'", e);
        }
        if (!child.equals(root) && !child.startsWith(root + File.separator)) {
            throw new ObjPackException("line " + lineNo + ": the object path '" + rel
                    + "' resolves outside objectsDir");
        }
        return f;
    }

    private List<File> listObjects() {
        List<File> out = new ArrayList<File>();
        collect(objectsDir, out);
        if ("path".equalsIgnoreCase(orderBy)) {
            Collections.sort(out, new java.util.Comparator<File>() {
                public int compare(File a, File b) {
                    return a.getAbsolutePath().compareTo(b.getAbsolutePath());
                }
            });
        } else {
            Collections.sort(out, new java.util.Comparator<File>() {
                public int compare(File a, File b) {
                    int c = a.getName().compareTo(b.getName());
                    return c != 0 ? c : a.getAbsolutePath().compareTo(b.getAbsolutePath());
                }
            });
        }
        return out;
    }

    private Map<String, List<File>> indexByName() {
        List<File> all = new ArrayList<File>();
        collect(objectsDir, all);
        Map<String, List<File>> m = new HashMap<String, List<File>>();
        for (File f : all) {
            String k = f.getName().toLowerCase(Locale.ROOT);
            List<File> l = m.get(k);
            if (l == null) {
                l = new ArrayList<File>();
                m.put(k, l);
            }
            l.add(f);
        }
        return m;
    }

    private void collect(File dir, List<File> out) {
        File[] kids = dir.listFiles();
        if (kids == null) {
            return;
        }
        Arrays.sort(kids);
        for (File k : kids) {
            if (k.isDirectory()) {
                if (recurse) {
                    collect(k, out);
                }
            } else if (k.isFile() && matches(k.getName())) {
                out.add(k);
            }
        }
    }

    private boolean matches(String fileName) {
        boolean in = include.isEmpty();
        for (String pat : include) {
            if (glob(pat, fileName)) {
                in = true;
                break;
            }
        }
        if (!in) {
            return false;
        }
        for (String pat : exclude) {
            if (glob(pat, fileName)) {
                return false;
            }
        }
        return true;
    }

    /** '*' and '?', case-insensitive, matching the whole name. */
    static boolean glob(String pattern, String s) {
        StringBuilder re = new StringBuilder("^");
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*') {
                re.append(".*");
            } else if (c == '?') {
                re.append('.');
            } else {
                re.append(java.util.regex.Pattern.quote(String.valueOf(c)));
            }
        }
        return s.toLowerCase(Locale.ROOT).matches(re.append('$').toString().toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------ ids, dates, pre-flight

    private void assignObjectIds(List<Pair> pairs) {
        if (pairs.get(0).objectId < 0) {
            for (int i = 0; i < pairs.size(); i++) {
                pairs.get(i).objectId = i + 1;
            }
            return;
        }
        // A mapped id is the feed's own; a value that is not an ascending run from 1 is refused
        // rather than renumbered, because silently replacing it breaks every downstream reference.
        for (int i = 0; i < pairs.size(); i++) {
            int want = i + 1;
            if (pairs.get(i).objectId != want) {
                throw new ObjPackException("the mapped object_id must ascend from 1 with no gaps "
                        + "(spec 3.2): row " + (i + 1) + " on line " + pairs.get(i).lineNo
                        + " has object_id " + pairs.get(i).objectId + ", expected " + want
                        + ". Leave map.objectId unset to have the packager assign 1..N.");
            }
        }
    }

    private String businessDate(String raw, long lineNo) {
        if (raw == null || raw.isEmpty()) {
            throw new ObjPackException("line " + lineNo + ": record_business_date is mandatory and not "
                    + "nullable (spec 3.2)");
        }
        String fmt = trim(businessDateFormat);
        if (fmt == null || fmt.isEmpty()) {
            if (!raw.matches("^[0-9]{8}$")) {
                throw new ObjPackException("line " + lineNo + ": record_business_date '" + raw
                        + "' is not yyyyMMdd. Set map.recordBusinessDate.format to convert it.");
            }
            return raw;
        }
        try {
            LocalDate d = LocalDate.parse(raw, DateTimeFormatter.ofPattern(fmt));
            return d.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        } catch (RuntimeException e) {
            throw new ObjPackException("line " + lineNo + ": record_business_date '" + raw
                    + "' does not parse with format '" + fmt + "'");
        }
    }

    private void preflight(List<Pair> pairs) throws IOException {
        if (pairs.size() > maxObjects) {
            throw new ObjPackException("the submission has " + pairs.size()
                    + " objects, above the limit of " + maxObjects + " (spec 1)");
        }
        if (dataschema != null) {
            checkAgainstSchema(pairs);
        } else {
            warnings.add("no dataschema was configured, so column order and nullability were not checked");
        }

        long total = 0;
        LocalDate today = LocalDate.now();
        for (Pair p : pairs) {
            if (p.mimeType == null || p.mimeType.isEmpty()) {
                throw new ObjPackException("line " + p.lineNo + ": mime_type is mandatory and not nullable "
                        + "(spec 3.2)");
            }
            if (p.originalName == null || p.originalName.isEmpty()) {
                throw new ObjPackException("line " + p.lineNo + ": original_object_name is mandatory and "
                        + "not nullable (spec 3.2)");
            }
            // Gate 0 9.3: mime_type carries the dotted extension, and §4 validates it against the
            // file name, so the check is a comparison and not a media-type lookup.
            String ext = SubmissionName.extensionOf(p.object.getName());
            String declared = p.mimeType.startsWith(".") ? p.mimeType : "." + p.mimeType;
            if (!ext.equalsIgnoreCase(declared)) {
                throw new ObjPackException("line " + p.lineNo + ": mime_type '" + p.mimeType
                        + "' does not match the object's extension '" + (ext.isEmpty() ? "(none)" : ext)
                        + "' for " + p.object.getName() + " (spec 4, submission names)");
            }
            long len = p.object.length();
            total += len;
            if (len > maxObjectBytes) {
                String msg = "line " + p.lineNo + ": " + p.object.getName() + " is " + len
                        + " bytes, above the per-object limit of " + maxObjectBytes + " (spec 1)";
                if (failOnOversize) {
                    throw new ObjPackException(msg);
                }
                warnings.add(msg);
            }
            checkBusinessDate(p, today);
        }
        if (total > maxSubmissionBytes) {
            String msg = "the submission totals " + total + " bytes, above the limit of "
                    + maxSubmissionBytes + " (spec 1)";
            if (failOnOversize) {
                throw new ObjPackException(msg);
            }
            warnings.add(msg);
        }
        // §3.3: each object file in a submission must have a unique name.
        Map<String, Long> seen = new HashMap<String, Long>();
        for (Pair p : pairs) {
            Long prev = seen.put(p.memberName, Long.valueOf(p.lineNo));
            if (prev != null) {
                throw new ObjPackException("two rows produce the same object file name '" + p.memberName
                        + "' (lines " + prev + " and " + p.lineNo + ")");
            }
        }
    }

    private void checkBusinessDate(Pair p, LocalDate today) {
        LocalDate d;
        try {
            d = LocalDate.of(Integer.parseInt(p.businessDate.substring(0, 4)),
                    Integer.parseInt(p.businessDate.substring(4, 6)),
                    Integer.parseInt(p.businessDate.substring(6, 8)));
        } catch (RuntimeException e) {
            throw new ObjPackException("line " + p.lineNo + ": record_business_date '" + p.businessDate
                    + "' is not a real date");
        }
        if (d.isBefore(today.minusMonths(businessDateMonths))) {
            String msg = "line " + p.lineNo + ": record_business_date " + p.businessDate
                    + " is older than " + businessDateMonths + " months (spec 4)";
            if (failOnStaleBusinessDate) {
                throw new ObjPackException(msg);
            }
            warnings.add(msg);
        }
    }

    private void checkAgainstSchema(List<Pair> pairs) throws IOException {
        Dataschema ds = Dataschema.read(dataschema);
        List<String> declared = ds.names();
        List<String> actual = new ArrayList<String>(pairs.get(0).row.keySet());
        int n = Math.min(declared.size(), actual.size());
        for (int i = 0; i < n; i++) {
            if (!declared.get(i).equalsIgnoreCase(actual.get(i))) {
                throw new ObjPackException("the source CSV's column " + (i + 1) + " is '" + actual.get(i)
                        + "' but the dataschema declares '" + declared.get(i)
                        + "'; the mandatory columns must appear in the declared order (spec 3.2)");
            }
        }
        if (declared.size() != actual.size()) {
            warnings.add("the dataschema declares " + declared.size() + " columns and the source CSV has "
                    + actual.size());
        }
        for (Dataschema.Column c : ds.columns()) {
            if (c.nullable) {
                continue;
            }
            for (Pair p : pairs) {
                String v = p.row.get(c.name);
                if (v == null || v.trim().isEmpty()) {
                    throw new ObjPackException("line " + p.lineNo + ": column '" + c.name
                            + "' is declared not nullable in the dataschema but is empty");
                }
            }
        }
    }

    // ------------------------------------------------------------------ writing

    private void writeAll(SubmissionName name, List<Pair> pairs) throws IOException {
        File meta    = new File(outputDir, name.metadataCsv());
        File audit   = new File(outputDir, name.auditJson());
        File control = new File(outputDir, name.control());
        String comp  = normaliseCompression(compression);
        tarFile      = new File(outputDir, name.archive(comp));
        md5File      = new File(outputDir, name.md5());

        writeMetadata(meta, pairs);

        List<AuditJson.Entry> entries = new ArrayList<AuditJson.Entry>(pairs.size());
        for (Pair p : pairs) {
            entries.add(new AuditJson.Entry(p.memberName, p.mimeType, p.objectId));
        }
        AuditJson.write(audit, name, targetDestination, entries);
        AuditJson.validate(audit, entries);

        OutputStream c = new FileOutputStream(control);
        c.close();                                   // §3.4: the control file must be 0 bytes
        if (control.length() != 0) {
            throw new ObjPackException("the control file must be 0 bytes, got " + control.length());
        }

        if (emitObjects) {
            for (Pair p : pairs) {
                copy(p.object, new File(outputDir, p.memberName));
            }
        }

        // Members in the order the specification lists them: audit, metadata, objects, control.
        OutputStream raw = new java.io.BufferedOutputStream(new FileOutputStream(tarFile), 1 << 16);
        // The compressor wraps the tar stream, so the archive is compressed as it is produced and
        // no intermediate uncompressed copy is ever written.
        UstarWriter w = new UstarWriter("gzip".equals(comp)
                ? new java.util.zip.GZIPOutputStream(raw, 1 << 16) : raw);
        boolean done = false;
        try {
            w.addFile(name.auditJson(), audit);
            w.addFile(name.metadataCsv(), meta);
            for (Pair p : pairs) {
                w.addFile(p.memberName, p.object);
            }
            w.addBytes(name.control(), new byte[0], System.currentTimeMillis() / 1000L);
            done = true;
        } finally {
            w.close();
            if (!done && tarFile.exists() && !tarFile.delete()) {
                warnings.add("a partial tar was left behind: " + tarFile.getAbsolutePath());
            }
        }
        tarBytes = tarFile.length();

        verifyArchive(name, pairs, audit, meta, comp);

        md5 = Md5.writeSidecar(tarFile, md5File);
    }

    /**
     * Reads the finished archive back with an independent parser and checks that every member that
     * was meant to be in it is in it, at the size it has on disk. This is the check that separates
     * "the archive was written" from "the archive contains what we meant".
     */
    private void verifyArchive(SubmissionName name, List<Pair> pairs, File audit, File meta,
                               String comp) throws IOException {
        Map<String, Long> actual;
        if ("gzip".equals(comp)) {
            // Read back through the decompressor, so what is checked is the file that will be
            // delivered and not an uncompressed intermediate that never existed.
            java.io.InputStream in = new java.util.zip.GZIPInputStream(
                    new java.io.BufferedInputStream(new java.io.FileInputStream(tarFile), 1 << 16), 1 << 16);
            try {
                actual = UstarReader.sizes(in);
            } finally {
                in.close();
            }
        } else {
            actual = UstarReader.sizes(tarFile);
        }
        Map<String, Long> want = new LinkedHashMap<String, Long>();
        want.put(name.auditJson(), Long.valueOf(audit.length()));
        want.put(name.metadataCsv(), Long.valueOf(meta.length()));
        for (Pair p : pairs) {
            want.put(p.memberName, Long.valueOf(p.object.length()));
        }
        want.put(name.control(), Long.valueOf(0L));

        for (Map.Entry<String, Long> e : want.entrySet()) {
            Long got = actual.get(e.getKey());
            if (got == null) {
                throw new ObjPackException("the tar is missing '" + e.getKey() + "': " + tarFile.getName());
            }
            if (!got.equals(e.getValue())) {
                throw new ObjPackException("the tar holds '" + e.getKey() + "' at " + got
                        + " bytes but the source is " + e.getValue() + " bytes");
            }
        }
        if (actual.size() != want.size()) {
            throw new ObjPackException("the tar holds " + actual.size() + " members but the submission has "
                    + want.size());
        }
        // §4: the object count must match the audit record count and the metadata row count.
        if (pairs.size() + 3 != actual.size()) {
            throw new ObjPackException("the tar holds " + actual.size() + " members, which is not "
                    + pairs.size() + " objects plus audit, metadata and control");
        }
    }

    private void writeMetadata(File out, List<Pair> pairs) throws IOException {
        List<String> passthrough = new ArrayList<String>();
        for (String h : pairs.get(0).row.keySet()) {
            if (!isMapped(h)) {
                passthrough.add(h);
            }
        }
        List<String> header = new ArrayList<String>(Arrays.asList(FALLBACK));
        header.addAll(passthrough);

        CsvWriter w = new CsvWriter(out, outDelimiter, false, 0, 0);
        try {
            w.header(header.toArray(new String[header.size()]));
            for (Pair p : pairs) {
                String[] cells = new String[header.size()];
                cells[0] = Integer.toString(p.objectId);
                cells[1] = p.businessDate;
                cells[2] = p.mimeType;
                cells[3] = p.originalName;
                for (int i = 0; i < passthrough.size(); i++) {
                    String v = p.row.get(passthrough.get(i));
                    cells[4 + i] = v == null ? "" : v;
                }
                w.row(cells);
            }
        } finally {
            w.close();
        }
    }

    private boolean isMapped(String header) {
        return eq(header, mapObjectId) || eq(header, mapRecordBusinessDate)
                || eq(header, mapMimeType) || eq(header, mapOriginalObjectName)
                || eq(header, mapObjectPath) || eq(header, mapNameLabel)
                || eq(header, FALLBACK[0]) || eq(header, FALLBACK[1])
                || eq(header, FALLBACK[2]) || eq(header, FALLBACK[3]);
    }

    private static boolean eq(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b.trim());
    }

    // ------------------------------------------------------------------ small helpers

    /** A mapped column must exist; an unmapped role falls back to the Transarch name if present. */
    private static String resolve(String mapped, String fallback, List<String> headers, boolean required) {
        String m = trim(mapped);
        if (m != null && !m.isEmpty()) {
            for (String h : headers) {
                if (h.equalsIgnoreCase(m)) {
                    return h;
                }
            }
            throw new ObjPackException("the mapped column '" + m + "' is not in the source CSV header "
                    + headers);
        }
        if (fallback != null) {
            for (String h : headers) {
                if (h.equalsIgnoreCase(fallback)) {
                    return h;
                }
            }
            if (required) {
                throw new ObjPackException("the source CSV has no '" + fallback
                        + "' column and no mapping was given for it; header is " + headers);
            }
        }
        return null;
    }

    private static char detectDelimiter(File f) throws IOException {
        java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                new java.io.FileInputStream(f), Charset.forName("UTF-8")));
        try {
            String line = r.readLine();
            if (line == null) {
                throw new ObjPackException("the metadata CSV is empty: " + f.getAbsolutePath());
            }
            char[] candidates = { ';', ',', '\t', '|' };
            char best = ';';
            int bestN = -1;
            for (char c : candidates) {
                int n = 0;
                for (int i = 0; i < line.length(); i++) {
                    if (line.charAt(i) == c) {
                        n++;
                    }
                }
                if (n > bestN) {
                    bestN = n;
                    best = c;
                }
            }
            if (bestN <= 0) {
                throw new ObjPackException("cannot detect a delimiter in the header of " + f.getName()
                        + "; set inDelimiter explicitly");
            }
            return best;
        } finally {
            r.close();
        }
    }

    private static void copy(File src, File dst) throws IOException {
        java.io.InputStream in = new java.io.FileInputStream(src);
        try {
            OutputStream out = new FileOutputStream(dst);
            try {
                byte[] b = new byte[64 * 1024];
                int n;
                while ((n = in.read(b)) > 0) {
                    out.write(b, 0, n);
                }
                out.flush();
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    /**
     * §3.5 permits gzip, bzip2 and xz for the archive. Only gzip is reachable from the Java 8
     * platform: {@code java.util.zip.GZIPOutputStream} is in the JDK, while bzip2 and xz are not
     * anywhere in it. Implementing those two would mean adding commons-compress (and, for xz, the
     * tukaani library), which cannot be confirmed against the internal Nexus from where this is
     * built — so they are refused with the reason rather than half-supported.
     */
    private static String normaliseCompression(String v) {
        String c = v == null ? "none" : v.trim().toLowerCase(Locale.ROOT);
        if (c.isEmpty() || "none".equals(c)) {
            return "none";
        }
        if ("gzip".equals(c) || "gz".equals(c)) {
            return "gzip";
        }
        if ("bzip2".equals(c) || "bz2".equals(c) || "xz".equals(c)) {
            throw new ObjPackException("compression '" + v + "' is permitted by the specification but "
                    + "is not available on Java 8: only gzip is in the platform. bzip2 and xz would "
                    + "need commons-compress on the internal Nexus. Use gzip or none.");
        }
        throw new ObjPackException("compression must be none, gzip, bzip2 or xz; got '" + v + "'");
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }
}
