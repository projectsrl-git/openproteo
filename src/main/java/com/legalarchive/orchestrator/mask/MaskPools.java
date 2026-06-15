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
}
