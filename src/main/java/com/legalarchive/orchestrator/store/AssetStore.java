package com.legalarchive.orchestrator.store;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.legalarchive.orchestrator.config.AppProperties;
import com.legalarchive.orchestrator.registry.WorkflowRegistry;

/**
 * Tracks files uploaded through the UI and their classification:
 *   - kind "document" : reference docs (txt, md, json descriptors, ...). No alias.
 *   - kind "script"   : executables runnable as a step (ps1, jar, bat, cmd). Must
 *                       have a UNIQUE alias; the alias becomes a run variable whose
 *                       value is the absolute path of the file, so a step can do
 *                       script="${alias}".
 *
 * Two scopes:
 *   - feed : files live in the feed directory; metadata in {feedDir}/_assets.json
 *   - app  : shared files usable by ALL workflows; in orchestrator.shared-dir,
 *            metadata {sharedDir}/_assets.json
 *
 * Alias uniqueness is enforced across the app scope plus the feed's own scope.
 */
@Component
public class AssetStore {

    private static final Logger log = LoggerFactory.getLogger(AssetStore.class);
    private static final String META = "_assets.json";

    public static class Asset {
        public String fileName;
        public String alias;   // null for documents
        public String kind;    // document | script
    }

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final AppProperties props;
    private final WorkflowRegistry registry;

    public AssetStore(AppProperties props, WorkflowRegistry registry) {
        this.props = props;
        this.registry = registry;
    }

    // ---- scope directories ----
    public Path sharedDir() {
        return new File(props.getSharedDir()).toPath().toAbsolutePath().normalize();
    }

    /** feedId == null -> app scope (shared dir); otherwise the feed directory. */
    public Path scopeDir(String feedId) {
        if (feedId == null) return sharedDir();
        FeedLayout l = registry.layout(feedId);
        return l == null ? null : l.feedDir;
    }

    // ---- metadata I/O ----
    private synchronized List<Asset> readMeta(Path dir) {
        List<Asset> list = new ArrayList<Asset>();
        File f = dir.resolve(META).toFile();
        if (!f.exists()) return list;
        try {
            Asset[] arr = mapper.readValue(f, Asset[].class);
            if (arr != null) for (Asset a : arr) list.add(a);
        } catch (Exception e) {
            log.error("Cannot read {} : {}", f, e.getMessage());
        }
        return list;
    }

    private synchronized void writeMeta(Path dir, List<Asset> list) {
        try {
            Files.createDirectories(dir);
            Path tmp = dir.resolve(META + ".tmp");
            mapper.writeValue(tmp.toFile(), list.toArray(new Asset[0]));
            Files.move(tmp, dir.resolve(META), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            log.error("Cannot write {}/{} : {}", dir, META, e.getMessage());
        }
    }

    public synchronized List<Asset> list(String feedId) {
        Path dir = scopeDir(feedId);
        return dir == null ? new ArrayList<Asset>() : readMeta(dir);
    }

    public synchronized Asset find(String feedId, String fileName) {
        for (Asset a : list(feedId)) if (a.fileName.equals(fileName)) return a;
        return null;
    }

    /** Copy all uploaded files (and their metadata) from one feed to another. Returns the count copied. */
    public synchronized int copyFeedAssets(String fromFeedId, String toFeedId) {
        Path src = scopeDir(fromFeedId), dst = scopeDir(toFeedId);
        if (src == null || dst == null || src.equals(dst)) return 0;
        List<Asset> srcList = readMeta(src);
        if (srcList.isEmpty()) return 0;
        int copied = 0;
        try { Files.createDirectories(dst); } catch (Exception ignore) {}
        List<Asset> dstList = readMeta(dst);
        for (final Asset a : srcList) {
            try {
                Path sf = src.resolve(a.fileName), df = dst.resolve(a.fileName);
                if (Files.exists(sf)) {
                    Files.copy(sf, df, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    dstList.removeIf(x -> x.fileName.equals(a.fileName));
                    dstList.add(a);
                    copied++;
                }
            } catch (Exception ignore) {}
        }
        writeMeta(dst, dstList);
        return copied;
    }
    public synchronized void put(String feedId, Asset asset) {
        Path dir = scopeDir(feedId);
        if (dir == null) return;
        List<Asset> list = readMeta(dir);
        list.removeIf(a -> a.fileName.equals(asset.fileName));
        list.add(asset);
        writeMeta(dir, list);
    }

    public synchronized void remove(String feedId, String fileName) {
        Path dir = scopeDir(feedId);
        if (dir == null) return;
        List<Asset> list = readMeta(dir);
        boolean changed = list.removeIf(a -> a.fileName.equals(fileName));
        if (changed) writeMeta(dir, list);
    }

    // ---- alias handling ----
    /** True if alias is already used in the app scope or in this feed's scope. */
    public synchronized boolean aliasTaken(String alias, String feedId) {
        if (alias == null) return false;
        for (Asset a : list(null)) if (alias.equals(a.alias)) return true;
        if (feedId != null) for (Asset a : list(feedId)) if (alias.equals(a.alias)) return true;
        return false;
    }

    /** Sanitize a candidate alias: keep [A-Za-z0-9_], no spaces/specials, not starting with a digit. */
    public static String sanitizeAlias(String raw) {
        if (raw == null) return "asset";
        // drop extension if a filename was passed
        int dot = raw.lastIndexOf('.');
        String base = dot > 0 ? raw.substring(0, dot) : raw;
        StringBuilder sb = new StringBuilder();
        for (char c : base.toCharArray()) {
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') sb.append(c);
            else sb.append('_');
        }
        String s = sb.toString().replaceAll("_+", "_").replaceAll("^_+|_+$", "");
        if (s.isEmpty()) s = "asset";
        if (Character.isDigit(s.charAt(0))) s = "_" + s;
        return s;
    }

    /** Propose a unique alias derived from the file name within app + feed scope. */
    public synchronized String suggestAlias(String fileName, String feedId) {
        String base = sanitizeAlias(fileName);
        String candidate = base;
        int k = 2;
        while (aliasTaken(candidate, feedId)) {
            candidate = base + "_" + k;
            k++;
        }
        return candidate;
    }

    /**
     * Variables exposed to a run: alias -> absolute path, for every "script" asset in
     * the app scope plus the given feed's scope (feed entries can shadow app ones).
     */
    public synchronized Map<String, String> scriptVars(String feedId) {
        Map<String, String> vars = new LinkedHashMap<String, String>();
        Path appDir = sharedDir();
        for (Asset a : list(null)) {
            if ("script".equals(a.kind) && a.alias != null) {
                vars.put(a.alias, appDir.resolve(a.fileName).toString());
            }
        }
        if (feedId != null) {
            Path fd = scopeDir(feedId);
            if (fd != null) for (Asset a : list(feedId)) {
                if ("script".equals(a.kind) && a.alias != null) {
                    vars.put(a.alias, fd.resolve(a.fileName).toString());
                }
            }
        }
        return vars;
    }
}
