package com.legalarchive.orchestrator.control;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The instance settings written from the GUI: {@code <workflowsDir>/openproteo-instance.json}
 * (spec {@code .claude/HEADLESS.md} section 4.4, Q3 confirmed 2026-11-18). It holds what a
 * headless instance needs and cannot be told through a page it does not have.
 *
 * <p>The workflows directory is the one location every artifact already knows at bootstrap, and
 * the registry reads only {@code *.xml} there, so this file is never taken for a workflow. The
 * values apply at the next start: {@link InstanceSettingsPostProcessor} reads them before the
 * context exists. Batch 4 (settings from the GUI) absorbs this file into its override file.
 *
 * <p>Keys that this build does not know are kept on write, so an older build never erases what
 * a newer one saved.
 */
public final class InstanceSettings {

    public static final String FILE_NAME = "openproteo-instance.json";
    public static final String CONTROL_DIR = "controlDir";

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    private InstanceSettings() { }

    public static Path file(String workflowsDir) {
        String w = (workflowsDir == null || workflowsDir.trim().isEmpty()) ? "./workflows" : workflowsDir.trim();
        return Paths.get(w).resolve(FILE_NAME).toAbsolutePath().normalize();
    }

    /** The file's content; an empty map when the file does not exist. A malformed file throws. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return new LinkedHashMap<String, Object>();
        Object o = new ObjectMapper().readValue(file.toFile(), Map.class);
        if (!(o instanceof Map)) throw new IOException(file + " is not a JSON object");
        return new LinkedHashMap<String, Object>((Map<String, Object>) o);
    }

    /** A string value, trimmed; null when absent or not a string. */
    public static String string(Map<String, Object> settings, String key) {
        Object v = settings.get(key);
        return (v instanceof String) ? ((String) v).trim() : null;
    }

    /**
     * Sets one key and records who and when. Written to a temporary file and moved over the old
     * one, so a reader never sees half a file.
     */
    public static void write(Path file, String key, String value, String changedBy) throws IOException {
        Map<String, String> one = new LinkedHashMap<String, String>();
        one.put(key, value);
        write(file, one, changedBy);
    }

    /** Several keys in one write, so a reader never sees one changed and the other not. */
    public static void write(Path file, Map<String, String> values, String changedBy) throws IOException {
        Map<String, Object> m = read(file);
        for (Map.Entry<String, String> e : values.entrySet()) m.put(e.getKey(), e.getValue() == null ? "" : e.getValue());
        m.put("changedBy", changedBy == null ? "" : changedBy);
        m.put("changedAt", OffsetDateTime.now().format(TS));
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        ControlProtocol.mapper().writeValue(tmp.toFile(), m);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }
}
