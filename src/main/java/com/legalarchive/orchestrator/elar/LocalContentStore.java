package com.legalarchive.orchestrator.elar;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Documents on the local filesystem, under the family's {@code documentPath}. This is what the executor
 * has always done, moved behind {@link ContentStore} without a byte of behaviour changing.
 *
 * The resolution rule is the legacy {@code updateFilePath}: take only the last path segment of the CSV
 * value and join it to the document directory. A value carrying a directory traversal therefore cannot
 * escape - a consequence of the rule rather than a defence that was designed, but a welcome one.
 */
public final class LocalContentStore implements ContentStore {

    private final File docDir;

    public LocalContentStore(File docDir) {
        if (docDir == null) throw new IllegalArgumentException("the document directory is required");
        this.docDir = docDir;
    }

    /** The resolved handle is the absolute path, so every other method is a plain file operation. */
    public String resolve(String csvValue) {
        String v = csvValue.replace('\\', '/');
        int slash = v.lastIndexOf('/');
        String name = slash >= 0 ? v.substring(slash + 1) : v;
        return new File(docDir, name).getPath();
    }

    public boolean exists(String resolved) { return new File(resolved).isFile(); }
    public long length(String resolved) { return new File(resolved).length(); }
    public long lastModified(String resolved) { return new File(resolved).lastModified(); }
    public InputStream open(String resolved) throws IOException { return new FileInputStream(resolved); }
    public String fileName(String resolved) { return new File(resolved).getName(); }
    public String describe(String resolved) { return resolved; }

    /** Nothing is held open between documents, so there is nothing to release. */
    public void close() { }
}
