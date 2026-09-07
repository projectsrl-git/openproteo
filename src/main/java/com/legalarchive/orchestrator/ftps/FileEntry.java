package com.legalarchive.orchestrator.ftps;

/**
 * A candidate file, reduced to what the planner needs: its name and its size.
 *
 * <p>The planner takes these rather than a directory so that the ordering rules can be exercised
 * without a filesystem, and so that the same rules produce the same plan wherever they run.
 */
public final class FileEntry {

    private final String name;
    private final long bytes;

    public FileEntry(String name, long bytes) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("file name is empty");
        }
        if (bytes < 0) {
            throw new IllegalArgumentException("negative size for " + name);
        }
        this.name = name;
        this.bytes = bytes;
    }

    public String name() {
        return name;
    }

    public long bytes() {
        return bytes;
    }

    @Override
    public String toString() {
        return name + " (" + bytes + ")";
    }
}
