package com.legalarchive.orchestrator.elar;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * An output file that only exists under its final name once it is complete.
 *
 * The legacy tool wrote straight to the deliverable name, so the OutOfMemoryError left a 0 KB
 * {@code INDX.C152400} in the output directory beside three complete files, indistinguishable from a
 * successful delivery to anything downstream. Here the bytes go to a temporary name in the same
 * directory - the same directory so the rename stays on one volume and is therefore atomic - and the
 * rename happens only after a clean close and flush.
 *
 * Killing the JVM at any point leaves a {@code .part} file and nothing under a final name.
 */
public final class AtomicOutput implements AutoCloseable {

    private final File target;
    private final File temp;
    private final OutputStream out;
    private boolean committed = false;
    private boolean closed = false;

    /**
     * @param overwriteExisting when false (the default), a final name that already exists fails
     *                          before anything is written rather than replacing a delivered file
     */
    public AtomicOutput(File target, boolean overwriteExisting) throws IOException {
        this.target = target;
        if (target.exists() && !overwriteExisting) {
            throw new IOException("output file already exists: " + target.getAbsolutePath()
                    + " - refusing to replace a file that may already have been delivered."
                    + " Set overwriteExisting=true if replacing it is intended.");
        }
        File dir = target.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create the output directory: " + dir.getAbsolutePath());
        }
        this.temp = new File(dir, target.getName() + ".part");
        if (temp.exists() && !temp.delete()) {
            throw new IOException("a leftover temporary file could not be removed: " + temp.getAbsolutePath()
                    + " - it is from an interrupted run and must be cleared before this one can write.");
        }
        this.out = new FileOutputStream(temp);
    }

    public OutputStream stream() { return out; }
    public File temp() { return temp; }
    public File target() { return target; }

    /**
     * Rename to the final name. Call only after the writer over {@link #stream()} has been closed, so
     * everything buffered has actually reached the file.
     */
    public void commit() throws IOException {
        if (committed) return;
        closeStream();
        if (target.exists() && !target.delete()) {
            throw new IOException("cannot replace the existing output file: " + target.getAbsolutePath());
        }
        if (!temp.renameTo(target)) {
            throw new IOException("cannot rename " + temp.getAbsolutePath() + " to " + target.getName()
                    + " - the batch is complete but could not be published, so nothing was delivered under"
                    + " that name.");
        }
        committed = true;
    }

    /** Discard: close and delete the temporary file. Safe to call more than once. */
    public void abort() {
        try { closeStream(); } catch (IOException ignored) { }
        if (!committed && temp.exists()) temp.delete();
    }

    private void closeStream() throws IOException {
        if (!closed) { closed = true; out.close(); }
    }

    /** Aborts unless {@link #commit()} succeeded, so a failure anywhere leaves no deliverable. */
    public void close() { abort(); }
}
