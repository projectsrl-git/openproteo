package com.legalarchive.orchestrator.json2csv;

/**
 * What the row builder knows about the file a document came from, and nothing else.
 *
 * <p>Deliberately not a {@code java.io.File}: the core has no filesystem in it, which is what lets
 * the whole package be exercised against trees and names built by hand.
 */
public final class DocumentContext {
    public final String fileName;       // report.json
    public final String relativePath;   // sub/report.json, relative to inputDir
    public final String absolutePath;

    public DocumentContext(String fileName, String relativePath, String absolutePath) {
        this.fileName = fileName == null ? "" : fileName;
        this.relativePath = relativePath == null ? this.fileName : relativePath;
        this.absolutePath = absolutePath == null ? this.fileName : absolutePath;
    }

    /** The extension with its dot, lower-cased; empty when the name carries none. */
    public String extension() {
        int slash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        String base = slash >= 0 ? fileName.substring(slash + 1) : fileName;
        int dot = base.lastIndexOf('.');
        if (dot <= 0 || dot == base.length() - 1) return "";
        return base.substring(dot).toLowerCase(java.util.Locale.ROOT);
    }

    /** The name without its extension. A leading dot is not an extension: {@code .gitignore} stays whole. */
    public String nameWithoutExtension() {
        int slash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        String base = slash >= 0 ? fileName.substring(slash + 1) : fileName;
        int dot = base.lastIndexOf('.');
        return dot > 0 ? base.substring(0, dot) : base;
    }
}
