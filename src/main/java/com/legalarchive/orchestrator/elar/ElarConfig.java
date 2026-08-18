package com.legalarchive.orchestrator.elar;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * The mapping configuration of one feed family, read once per step.
 *
 * Every lookup goes through {@link #req} or {@link #opt}, which prepend the family prefix and, when a
 * required key is absent, throw naming both the key and the properties file. The legacy tool did
 * <code>props.getProperty(...).equals(...)</code> and produced a NullPointerException instead, which
 * happened in production by copying a .bat between feeds and leaving one argument unchanged: the
 * error said nothing about which key or which file.
 *
 * No family identifier appears anywhere in this class. The legacy code carried two of them as
 * constants - <code>"CLICT@EV"</code> as a hardcoded familyType and <code>"CLICT@DT.documentPath"</code>
 * as a property key - either of which silently used the wrong feed's configuration.
 */
public final class ElarConfig {

    /** Keys the legacy tool read WITHOUT a family prefix. A prefixed form wins; the bare form still works. */
    private static final String[] UNPREFIXED = { "max.line.length", "elar.namespace", "idms.namespace" };

    private final Properties props;
    private final String family;
    private final String sourcePath;

    private ElarConfig(Properties props, String family, String sourcePath) {
        this.props = props;
        this.family = family;
        this.sourcePath = sourcePath;
    }

    public static ElarConfig load(File propertiesFile, String family) throws Exception {
        if (family == null || family.trim().isEmpty()) {
            throw new IllegalArgumentException("familyType is required (it is the prefix of every key in "
                    + (propertiesFile == null ? "the properties file" : propertiesFile.getAbsolutePath()) + ")");
        }
        if (propertiesFile == null || !propertiesFile.isFile()) {
            throw new IllegalArgumentException("properties file not found: "
                    + (propertiesFile == null ? "(null)" : propertiesFile.getAbsolutePath()));
        }
        Properties p = new Properties();
        InputStream in = new FileInputStream(propertiesFile);
        try {
            // ISO-8859-1 is what Properties.load(InputStream) specifies; a reader would change that
            p.load(in);
        } finally {
            in.close();
        }
        ElarConfig c = new ElarConfig(p, family.trim(), propertiesFile.getAbsolutePath());
        c.assertFamilyPresent();
        return c;
    }

    /** For tests and for callers that already hold the properties. */
    public static ElarConfig of(Properties p, String family, String sourcePath) {
        ElarConfig c = new ElarConfig(p, family, sourcePath);
        c.assertFamilyPresent();
        return c;
    }

    /**
     * A familyType that matches nothing in the file is the single most likely operator error, and the
     * legacy failure mode for it was an NPE. Detect it once, at load, and say what the file does hold.
     */
    private void assertFamilyPresent() {
        String pfx = family + ".";
        for (Object k : props.keySet()) {
            if (String.valueOf(k).startsWith(pfx)) return;
        }
        List<String> seen = new ArrayList<String>();
        for (Object k : props.keySet()) {
            String s = String.valueOf(k);
            int dot = s.indexOf('.');
            if (dot <= 0) continue;
            String f = s.substring(0, dot);
            if (!seen.contains(f)) seen.add(f);
        }
        throw new IllegalArgumentException("familyType '" + family + "' matches no key in " + sourcePath
                + (seen.isEmpty() ? "" : (" - families present: " + String.join(", ", seen))));
    }

    public String family() { return family; }
    public String sourcePath() { return sourcePath; }

    /** Required, family-prefixed. */
    public String req(String suffix) {
        String v = opt(suffix, null);
        if (v == null) {
            throw new IllegalArgumentException("missing property '" + family + "." + suffix + "' in " + sourcePath);
        }
        return v;
    }

    /** Optional, family-prefixed, falling back to the bare key for the three legacy unprefixed ones. */
    public String opt(String suffix, String def) {
        String v = trimToNull(props.getProperty(family + "." + suffix));
        if (v != null) return v;
        for (int i = 0; i < UNPREFIXED.length; i++) {
            if (UNPREFIXED[i].equals(suffix)) {
                String bare = trimToNull(props.getProperty(suffix));
                if (bare != null) return bare;
                break;
            }
        }
        return def;
    }

    public int optInt(String suffix, int def) {
        String v = opt(suffix, null);
        if (v == null) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("property '" + family + "." + suffix + "' in " + sourcePath
                    + " is not a whole number: '" + v + "'");
        }
    }

    public int reqInt(String suffix) {
        String v = req(suffix);
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("property '" + family + "." + suffix + "' in " + sourcePath
                    + " is not a whole number: '" + v + "'");
        }
    }

    // ---------------------------------------------------------------- mapping

    /** CSV column name -> ELAR tag name, in the order the properties file lists them. */
    public Map<String, String> tagNameMapping() {
        Map<String, String> out = new LinkedHashMap<String, String>();
        String pfx = family + ".tagNameMapping.";
        for (Object k : props.keySet()) {
            String key = String.valueOf(k);
            if (!key.startsWith(pfx)) continue;
            String col = key.substring(pfx.length()).trim();
            String tag = trimToNull(props.getProperty(key));
            if (col.isEmpty() || tag == null) continue;
            out.put(col, tag);
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("no '" + pfx + "<column>' entries in " + sourcePath
                    + " - nothing would be written for family '" + family + "'");
        }
        return out;
    }

    /** The CSV column whose value is the document id. */
    public String docIdColumn() { return req("input.doc_id_reference"); }

    /**
     * The ELAR tag the document id maps to.
     *
     * This is the translation the legacy Validator never did. <code>doc_id_reference</code> holds a CSV
     * COLUMN name while the checks are keyed by ELAR TAG name, so the reference lookup returned null
     * for every document and the check threw on the first row of any file - it could never have
     * passed. The asymmetry is real and deliberate in the properties file:
     * <code>not_duplicated_tags_list</code> is already expressed in ELAR tag names and needs no
     * translation, while <code>doc_id_reference</code> does. Handled here rather than assumed away.
     */
    public String docIdTag(Map<String, String> mapping) {
        String col = docIdColumn();
        String tag = mapping.get(col);
        if (tag == null) {
            throw new IllegalArgumentException("property '" + family + ".input.doc_id_reference' names column '"
                    + col + "', which has no '" + family + ".tagNameMapping." + col + "' entry in " + sourcePath
                    + " - the document id would map to no tag");
        }
        return tag;
    }

    /** ELAR tag names whose values must not repeat, already in tag-name form. */
    public List<String> notDuplicatedTags(String listSeparator) {
        List<String> out = new ArrayList<String>();
        String raw = opt("input.not_duplicated_tags_list", null);
        if (raw == null) return out;
        String sep = (listSeparator == null || listSeparator.isEmpty()) ? "," : listSeparator;
        String[] parts = raw.split(java.util.regex.Pattern.quote(sep), -1);
        for (int i = 0; i < parts.length; i++) {
            String t = parts[i].trim();
            if (!t.isEmpty() && !out.contains(t)) out.add(t);
        }
        return out;
    }

    /** Directory the content files are re-rooted under. Family-prefixed, unlike the legacy constant. */
    public String documentPath() { return req("documentPath"); }

    /**
     * The tag carrying the document content, and the tag receiving the file extension.
     *
     * Configurable because every family has its own template with its own tags. The defaults are what
     * this family's template uses and what the legacy code hardcoded, so nothing changes for a
     * properties file that does not set them.
     */
    public String contentTag() { return opt("output.content_tag", "ELAR:Content"); }
    public String dsakTag() { return opt("output.dsak_tag", "ELAR:DSAK"); }

    static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
