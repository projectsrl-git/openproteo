package com.legalarchive.orchestrator.store;

import com.legalarchive.orchestrator.config.AppProperties;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;

/**
 * Common variables shared by ALL workflows. Two sources, merged with properties winning:
 *   1) a properties file (editable in-app), default {@code <sharedDir>/global-vars.properties}
 *   2) {@code orchestrator.global-vars.NAME=value} entries in application.properties (ops-controlled)
 *
 * The merged map is seeded into every run with the lowest precedence, so per-workflow variables
 * (and built-ins) always override a global of the same name.
 */
@Component
public class GlobalVarsStore {

    private final AppProperties props;
    private volatile Map<String, String> fileVars = new LinkedHashMap<String, String>();

    public GlobalVarsStore(AppProperties props) {
        this.props = props;
        reload();
    }

    /** Resolved properties file (explicit path, else &lt;sharedDir&gt;/global-vars.properties). */
    public File file() {
        String p = props.getGlobalVarsFile();
        if (p != null && !p.trim().isEmpty()) return new File(p.trim());
        return new File(props.getSharedDir(), "global-vars.properties");
    }

    public synchronized void reload() {
        Map<String, String> m = new LinkedHashMap<String, String>();
        File f = file();
        if (f.isFile()) {
            Properties pr = new Properties();
            InputStream in = null;
            try {
                in = new FileInputStream(f);
                pr.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                // keep a stable, sorted order for display
                for (String name : new TreeSet<String>(pr.stringPropertyNames())) {
                    m.put(name, pr.getProperty(name));
                }
            } catch (Exception ignore) {
            } finally {
                if (in != null) try { in.close(); } catch (Exception ignore) {}
            }
        }
        fileVars = m;
    }

    /** File-based globals only (editable in-app). */
    public Map<String, String> fileVars() { return new LinkedHashMap<String, String>(fileVars); }

    /** application.properties globals only (read-only, ops-controlled). */
    public Map<String, String> propsVars() {
        Map<String, String> m = new LinkedHashMap<String, String>();
        if (props.getGlobalVars() != null) m.putAll(props.getGlobalVars());
        return m;
    }

    /** Merged view used at runtime: file first, then application.properties override. */
    public Map<String, String> all() {
        Map<String, String> m = new LinkedHashMap<String, String>(fileVars);
        if (props.getGlobalVars() != null) m.putAll(props.getGlobalVars());
        return m;
    }

    /** Persist the file-based globals and reload. Keys must be non-empty and unique. */
    public synchronized void saveFile(Map<String, String> vars) throws Exception {
        File f = file();
        if (f.getParentFile() != null) f.getParentFile().mkdirs();
        Properties pr = new Properties();
        if (vars != null) {
            for (Map.Entry<String, String> e : vars.entrySet()) {
                String k = e.getKey() == null ? "" : e.getKey().trim();
                if (k.isEmpty()) continue;
                pr.setProperty(k, e.getValue() == null ? "" : e.getValue());
            }
        }
        OutputStream o = null;
        try {
            o = new FileOutputStream(f);
            pr.store(new OutputStreamWriter(o, StandardCharsets.UTF_8),
                    "OpenProteo global variables (common to all workflows). Managed via the Variables page.");
        } finally {
            if (o != null) try { o.close(); } catch (Exception ignore) {}
        }
        reload();
    }
}
