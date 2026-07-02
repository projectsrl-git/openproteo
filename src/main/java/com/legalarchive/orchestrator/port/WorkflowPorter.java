package com.legalarchive.orchestrator.port;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.legalarchive.orchestrator.config.AppProperties;
import com.legalarchive.orchestrator.ds.DataSourceDef;
import com.legalarchive.orchestrator.ds.DataSourceStore;
import com.legalarchive.orchestrator.model.def.StepDef;
import com.legalarchive.orchestrator.model.def.WorkflowDef;
import com.legalarchive.orchestrator.registry.WorkflowRegistry;
import com.legalarchive.orchestrator.store.FeedLayout;
import com.legalarchive.orchestrator.store.GlobalVarsStore;

/**
 * Packs one or more workflows (XML + every file they need to run) into a single
 * ZIP, and unpacks such a ZIP on import. JDK + Jackson only.
 *
 * The ZIP layout:
 * <pre>
 *   manifest.json
 *   workflows/&lt;feedId&gt;.xml
 *   schemas/&lt;feedId&gt;/dataschema.json
 *   schemas/&lt;feedId&gt;/displayschema.json
 *   scripts/&lt;name&gt;                 (referenced .ps1/.bat/.cmd/.jar)
 *   datasources/datasources.json    (referenced defs, passwords blanked)
 *   globals/global-vars.properties  (referenced file-globals, secrets redacted)
 * </pre>
 *
 * Secrets are never exported: datasource passwords are blanked, the masking
 * secret and application.properties globals are omitted, and secret-looking
 * global keys are redacted.
 */
@Component
public class WorkflowPorter {

    private static final Logger log = LoggerFactory.getLogger(WorkflowPorter.class);

    /** Manifest / export format version. */
    public static final int FORMAT_VERSION = 1;

    /** ${name} placeholder scanner (names may contain letters, digits, _ . -). */
    private static final Pattern VAR_REF = Pattern.compile("\\$\\{([A-Za-z0-9_.\\-]+)\\}");
    /** Global keys whose value must not leave the instance. */
    private static final Pattern SECRET_KEY =
            Pattern.compile(".*(password|passwd|pwd|secret|token|apikey|api[_-]?key|credential).*", Pattern.CASE_INSENSITIVE);

    /** Staging older than this is swept on the next inspect. */
    private static final long STAGING_TTL_MS = 6L * 60 * 60 * 1000; // 6 hours

    private final AppProperties props;
    private final WorkflowRegistry registry;
    private final DataSourceStore dataSources;
    private final GlobalVarsStore globalVars;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public WorkflowPorter(AppProperties props, WorkflowRegistry registry,
                          DataSourceStore dataSources, GlobalVarsStore globalVars) {
        this.props = props;
        this.registry = registry;
        this.dataSources = dataSources;
        this.globalVars = globalVars;
    }

    // ============================================================ EXPORT

    /**
     * Write a ZIP with the given feeds and every file they reference to {@code out}.
     * Unknown feedIds are skipped. Returns the list of feedIds actually written.
     */
    public List<String> writeZip(List<String> feedIds, OutputStream out) throws IOException {
        List<WorkflowDef> defs = new ArrayList<WorkflowDef>();
        for (String id : feedIds) {
            WorkflowDef d = id == null ? null : registry.get(id.trim());
            if (d != null) defs.add(d);
        }

        Set<String> scriptNames = new LinkedHashSet<String>();      // base name -> collected once
        Map<String, File> scriptFiles = new LinkedHashMap<String, File>();
        Set<String> dsIds = new LinkedHashSet<String>();
        Set<String> globalKeys = new LinkedHashSet<String>();

        List<String> written = new ArrayList<String>();
        ZipOutputStream zip = new ZipOutputStream(out);
        zip.setComment("OpenProteo workflow export");
        try {
            List<Map<String, Object>> feedMeta = new ArrayList<Map<String, Object>>();
            for (WorkflowDef def : defs) {
                File xml = workflowFile(def);
                if (xml == null || !xml.isFile()) {
                    log.warn("[export] {} has no readable XML ({}), skipped", def.feedId, def.sourceFile);
                    continue;
                }
                byte[] xmlBytes = Files.readAllBytes(xml.toPath());
                putEntry(zip, "workflows/" + def.feedId + ".xml", xmlBytes);
                written.add(def.feedId);

                // schemas
                FeedLayout layout = registry.layout(def.feedId);
                if (layout != null) {
                    copyIfExists(zip, layout.feedDir.resolve("dataschema.json"),
                            "schemas/" + def.feedId + "/dataschema.json");
                    copyIfExists(zip, layout.feedDir.resolve("displayschema.json"),
                            "schemas/" + def.feedId + "/displayschema.json");
                }

                // referenced assets
                collectScripts(def, scriptFiles);
                dsIds.addAll(datasourceIds(def));
                globalKeys.addAll(globalKeysReferenced(new String(xmlBytes, StandardCharsets.UTF_8)));

                Map<String, Object> fm = new LinkedHashMap<String, Object>();
                fm.put("feedId", def.feedId);
                fm.put("name", def.name == null ? "" : def.name);
                fm.put("sourceId", def.sourceId == null ? "" : def.sourceId);
                fm.put("targetId", def.targetId == null ? "" : def.targetId);
                fm.put("production", def.production);
                feedMeta.add(fm);
            }

            // scripts
            for (Map.Entry<String, File> e : scriptFiles.entrySet()) {
                try {
                    putEntry(zip, "scripts/" + e.getKey(), Files.readAllBytes(e.getValue().toPath()));
                    scriptNames.add(e.getKey());
                } catch (Exception ex) {
                    log.warn("[export] cannot read script {}: {}", e.getValue(), ex.getMessage());
                }
            }

            // datasources (passwords blanked)
            List<DataSourceDef> dsOut = new ArrayList<DataSourceDef>();
            for (String id : dsIds) {
                DataSourceDef d = dataSources.get(id);
                if (d == null) continue;
                DataSourceDef copy = mapper.readValue(mapper.writeValueAsString(d), DataSourceDef.class);
                copy.password = "";
                dsOut.add(copy);
            }
            if (!dsOut.isEmpty()) {
                putEntry(zip, "datasources/datasources.json",
                        mapper.writeValueAsBytes(dsOut.toArray(new DataSourceDef[0])));
            }

            // globals (only referenced file-globals; secrets redacted)
            Map<String, String> fileGlobals = globalVars.fileVars();
            Properties gp = new Properties();
            for (String k : globalKeys) {
                if (!fileGlobals.containsKey(k)) continue;
                String v = fileGlobals.get(k);
                gp.setProperty(k, SECRET_KEY.matcher(k).matches() ? "" : (v == null ? "" : v));
            }
            if (!gp.isEmpty()) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                gp.store(new java.io.OutputStreamWriter(bos, StandardCharsets.UTF_8),
                        "OpenProteo global variables referenced by the exported workflows (secrets redacted).");
                putEntry(zip, "globals/global-vars.properties", bos.toByteArray());
            }

            // manifest
            Map<String, Object> manifest = new LinkedHashMap<String, Object>();
            manifest.put("tool", "openproteo");
            manifest.put("type", "workflow-export");
            manifest.put("version", FORMAT_VERSION);
            manifest.put("exportedAt", java.time.Instant.now().toString());
            manifest.put("feeds", feedMeta);
            manifest.put("scripts", new ArrayList<String>(scriptNames));
            manifest.put("datasources", dsIdList(dsOut));
            manifest.put("globals", new ArrayList<String>(gp.stringPropertyNames()));
            manifest.put("secretsRedacted", true);
            putEntry(zip, "manifest.json", mapper.writeValueAsBytes(manifest));
        } finally {
            zip.finish();
        }
        return written;
    }

    private static List<String> dsIdList(List<DataSourceDef> defs) {
        List<String> l = new ArrayList<String>();
        for (DataSourceDef d : defs) l.add(d.id);
        return l;
    }

    /** Resolve a workflow's XML file (absolute sourceFile if present, else workflows-dir/<name>). */
    private File workflowFile(WorkflowDef def) {
        if (def.sourceFile != null) {
            File f = new File(def.sourceFile);
            if (f.isAbsolute() && f.isFile()) return f;
            File byName = new File(props.getWorkflowsDir(), new File(def.sourceFile).getName());
            if (byName.isFile()) return byName;
        }
        File byId = new File(props.getWorkflowsDir(), def.feedId + ".xml");
        return byId.isFile() ? byId : null;
    }

    /** Add every resolvable step {@code script} file to {@code out}, keyed by base name. */
    private void collectScripts(WorkflowDef def, Map<String, File> out) {
        for (StepDef s : def.steps()) {
            String sc = s.script;
            if (sc == null || sc.trim().isEmpty() || sc.contains("${")) continue;
            String base = new File(sc).getName();
            if (out.containsKey(base)) continue;
            File f = new File(sc);
            if (!f.isAbsolute() || !f.isFile()) f = new File(props.getScriptsDir(), base);
            if (f.isFile()) out.put(base, f);
        }
    }

    /** Literal datasource ids referenced by sql/ifscopy steps (placeholders skipped). */
    private Set<String> datasourceIds(WorkflowDef def) {
        Set<String> ids = new LinkedHashSet<String>();
        for (StepDef s : def.steps()) {
            String ds = s.datasource;
            if (ds != null && !ds.trim().isEmpty() && !ds.contains("${")) ids.add(ds.trim());
        }
        return ids;
    }

    /** ${name} placeholders referenced anywhere in the raw workflow XML. */
    private Set<String> globalKeysReferenced(String xml) {
        Set<String> keys = new LinkedHashSet<String>();
        Matcher m = VAR_REF.matcher(xml);
        while (m.find()) keys.add(m.group(1));
        return keys;
    }

    // ============================================================ IMPORT

    private File stagingRoot() {
        return new File(System.getProperty("java.io.tmpdir"), "openproteo-imports");
    }

    /** Staging directory for a token, or null if the token is unsafe / unknown. */
    public Path stagingDir(String token) {
        if (token == null || !token.matches("[A-Za-z0-9]{8,64}")) return null;
        Path p = stagingRoot().toPath().resolve(token).normalize();
        return p.startsWith(stagingRoot().toPath()) ? p : null;
    }

    /** Extract a ZIP into a fresh staging dir. Returns the token. Zip-slip guarded. */
    public String extractToStaging(byte[] zipBytes) throws IOException {
        sweepOldStaging();
        String token = java.util.UUID.randomUUID().toString().replace("-", "");
        Path dir = stagingRoot().toPath().resolve(token).normalize();
        Files.createDirectories(dir);
        ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes));
        try {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String name = e.getName();
                if (name == null || name.isEmpty()) continue;
                if (name.contains("..") || name.startsWith("/") || name.startsWith("\\") || name.contains(":")) {
                    throw new IOException("Unsafe zip entry: " + name);
                }
                Path target = dir.resolve(name).normalize();
                if (!target.startsWith(dir)) throw new IOException("Zip-slip blocked: " + name);
                if (e.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zis, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        } finally {
            zis.close();
        }
        return token;
    }

    /** The staged workflow XML files (workflows/*.xml), sorted by name. */
    public List<Path> stagedWorkflowXmls(String token) throws IOException {
        Path dir = stagingDir(token);
        List<Path> out = new ArrayList<Path>();
        if (dir == null) return out;
        Path wf = dir.resolve("workflows");
        if (!Files.isDirectory(wf)) return out;
        File[] files = wf.toFile().listFiles(new java.io.FilenameFilter() {
            public boolean accept(File d, String n) { return n.toLowerCase().endsWith(".xml"); }
        });
        if (files == null) return out;
        java.util.Arrays.sort(files);
        for (File f : files) out.add(f.toPath());
        return out;
    }

    /** Read the manifest.json of a staging, or an empty map if absent/unreadable. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> manifest(String token) {
        Path dir = stagingDir(token);
        if (dir == null) return new LinkedHashMap<String, Object>();
        Path m = dir.resolve("manifest.json");
        if (!Files.isRegularFile(m)) return new LinkedHashMap<String, Object>();
        try {
            return mapper.readValue(m.toFile(), Map.class);
        } catch (Exception e) {
            return new LinkedHashMap<String, Object>();
        }
    }

    /** Copy the staged schemas for one feed into its feed directory. Returns names written. */
    public List<String> writeSchemas(String token, String feedId, Path feedDir) throws IOException {
        List<String> out = new ArrayList<String>();
        Path dir = stagingDir(token);
        if (dir == null) return out;
        Path src = dir.resolve("schemas").resolve(feedId);
        String[] names = {"dataschema.json", "displayschema.json"};
        for (String n : names) {
            Path s = src.resolve(n);
            if (Files.isRegularFile(s)) {
                Files.write(feedDir.resolve(n), Files.readAllBytes(s));
                out.add(n);
            }
        }
        return out;
    }

    /** Copy staged scripts into scripts-dir. Existing files are kept (never overwritten). */
    public Map<String, Object> writeScripts(String token) throws IOException {
        List<String> writtenScripts = new ArrayList<String>();
        List<String> skipped = new ArrayList<String>();
        Path dir = stagingDir(token);
        Map<String, Object> res = new LinkedHashMap<String, Object>();
        res.put("written", writtenScripts);
        res.put("skipped", skipped);
        if (dir == null) return res;
        Path src = dir.resolve("scripts");
        if (!Files.isDirectory(src)) return res;
        File scriptsDir = new File(props.getScriptsDir());
        if (!scriptsDir.isDirectory()) scriptsDir.mkdirs();
        File[] files = src.toFile().listFiles();
        if (files == null) return res;
        for (File f : files) {
            if (!f.isFile()) continue;
            File dest = new File(scriptsDir, f.getName());
            if (dest.exists()) { skipped.add(f.getName()); continue; }
            Files.copy(f.toPath(), dest.toPath());
            writtenScripts.add(f.getName());
        }
        return res;
    }

    /** Merge staged datasources: create-if-missing with a blank password; never overwrite. */
    public Map<String, Object> mergeDatasources(String token) {
        List<String> created = new ArrayList<String>();
        List<String> skipped = new ArrayList<String>();
        Map<String, Object> res = new LinkedHashMap<String, Object>();
        res.put("created", created);
        res.put("skipped", skipped);
        Path dir = stagingDir(token);
        if (dir == null) return res;
        Path f = dir.resolve("datasources").resolve("datasources.json");
        if (!Files.isRegularFile(f)) return res;
        try {
            DataSourceDef[] arr = mapper.readValue(f.toFile(), DataSourceDef[].class);
            if (arr != null) for (DataSourceDef d : arr) {
                if (d == null || d.id == null) continue;
                if (dataSources.get(d.id) != null) { skipped.add(d.id); continue; }
                if (d.password == null) d.password = "";
                dataSources.save(d);
                created.add(d.id);
            }
        } catch (Exception e) {
            log.warn("[import] datasources merge failed: {}", e.getMessage());
        }
        return res;
    }

    /** Merge staged global variables: add missing keys; never overwrite an existing value. */
    public Map<String, Object> mergeGlobals(String token) {
        List<String> added = new ArrayList<String>();
        List<String> skipped = new ArrayList<String>();
        Map<String, Object> res = new LinkedHashMap<String, Object>();
        res.put("added", added);
        res.put("skipped", skipped);
        Path dir = stagingDir(token);
        if (dir == null) return res;
        Path f = dir.resolve("globals").resolve("global-vars.properties");
        if (!Files.isRegularFile(f)) return res;
        try {
            Properties incoming = new Properties();
            incoming.load(new java.io.InputStreamReader(
                    new ByteArrayInputStream(Files.readAllBytes(f)), StandardCharsets.UTF_8));
            Map<String, String> current = globalVars.fileVars();
            Map<String, String> merged = new LinkedHashMap<String, String>(current);
            boolean changed = false;
            for (String k : incoming.stringPropertyNames()) {
                if (current.containsKey(k)) { skipped.add(k); continue; }
                merged.put(k, incoming.getProperty(k));
                added.add(k);
                changed = true;
            }
            if (changed) globalVars.saveFile(merged);
        } catch (Exception e) {
            log.warn("[import] globals merge failed: {}", e.getMessage());
        }
        return res;
    }

    /** Best-effort removal of a staging directory. */
    public void cleanup(String token) {
        Path dir = stagingDir(token);
        if (dir != null) deleteRecursive(dir.toFile());
    }

    /** Delete staging directories older than the TTL (best effort). */
    public void sweepOldStaging() {
        File root = stagingRoot();
        File[] dirs = root.listFiles();
        if (dirs == null) return;
        long now = System.currentTimeMillis();
        for (File d : dirs) {
            if (d.isDirectory() && now - d.lastModified() > STAGING_TTL_MS) deleteRecursive(d);
        }
    }

    private void deleteRecursive(File f) {
        if (f == null) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        if (!f.delete()) f.deleteOnExit();
    }

    // ============================================================ helpers

    private void copyIfExists(ZipOutputStream zip, Path src, String entry) throws IOException {
        if (Files.isRegularFile(src)) putEntry(zip, entry, Files.readAllBytes(src));
    }

    private void putEntry(ZipOutputStream zip, String name, byte[] data) throws IOException {
        ZipEntry e = new ZipEntry(name);
        zip.putNextEntry(e);
        zip.write(data);
        zip.closeEntry();
    }
}
