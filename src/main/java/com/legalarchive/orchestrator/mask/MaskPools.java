package com.legalarchive.orchestrator.mask;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads value pools (first names, surnames, cities, streets, company parts) used by the
 * pool-based masking strategies. Pools are plain text, one value per line, '#' comments
 * ignored. They are bundled in the WAR under classpath {@code /maskdata/}; an external
 * directory (orchestrator.mask-pools-dir) may override them without a rebuild — useful to
 * drop in larger ISTAT-derived lists in the UBS environment. No external libraries.
 */
public final class MaskPools {

    private final String externalDir;
    private final Map<String, String[]> cache = new HashMap<String, String[]>();

    public MaskPools(String externalDir) {
        this.externalDir = (externalDir == null || externalDir.trim().isEmpty()) ? null : externalDir.trim();
    }

    /** Returns the pool, or an empty array if the resource is missing. Cached. */
    public String[] get(String fileName) {
        String[] cached = cache.get(fileName);
        if (cached != null) return cached;
        List<String> out = new ArrayList<String>();
        InputStream is = null;
        try {
            if (externalDir != null) {
                java.io.File f = new java.io.File(externalDir, fileName);
                if (f.isFile()) is = new java.io.FileInputStream(f);
            }
            if (is == null) is = MaskPools.class.getResourceAsStream("/maskdata/" + fileName);
            if (is != null) {
                BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                String line;
                while ((line = r.readLine()) != null) {
                    String v = line.trim();
                    if (v.isEmpty() || v.charAt(0) == '#') continue;
                    out.add(v);
                }
            }
        } catch (Exception ignore) {
        } finally {
            if (is != null) try { is.close(); } catch (Exception ignore) {}
        }
        String[] arr = out.toArray(new String[0]);
        cache.put(fileName, arr);
        return arr;
    }

    public boolean isEmpty(String fileName) { return get(fileName).length == 0; }

    /** External override directory in use (or null if only bundled pools are available). */
    public String getExternalDir() { return externalDir; }

    /** Known pool files bundled in the WAR under /maskdata/. Used to build the selection
     *  catalog even though a classpath dir cannot be reliably listed at runtime. */
    public static final String[] BUNDLED = {
        "firstnames_it.txt", "firstnames_international.txt",
        "lastnames_it.txt", "lastnames_international.txt",
        "cities_it.txt", "caps_it.txt", "streets_it.txt",
        "company_animals_it.txt", "company_animals_international.txt",
        "company_colors_it.txt", "company_colors_international.txt",
        "company_actions_it.txt", "company_actions_international.txt",
        "company_suffixes_it.txt", "company_suffixes_international.txt"
    };

    public static boolean isBundled(String fileName) {
        for (String b : BUNDLED) if (b.equals(fileName)) return true;
        return false;
    }

    /** True if an external override file currently exists for this name. */
    public boolean hasExternal(String fileName) {
        if (externalDir == null) return false;
        return new java.io.File(externalDir, fileName).isFile();
    }

    /** Raw content of the effective file (external override if present, else bundled),
     *  comments included. Returns null if neither exists. */
    public String readRaw(String fileName) {
        InputStream is = null;
        try {
            if (externalDir != null) {
                java.io.File f = new java.io.File(externalDir, fileName);
                if (f.isFile()) is = new java.io.FileInputStream(f);
            }
            if (is == null) is = MaskPools.class.getResourceAsStream("/maskdata/" + fileName);
            if (is == null) return null;
            BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line; boolean first = true;
            while ((line = r.readLine()) != null) { if (!first) sb.append('\n'); sb.append(line); first = false; }
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (is != null) try { is.close(); } catch (Exception ignore) {}
        }
    }
}
