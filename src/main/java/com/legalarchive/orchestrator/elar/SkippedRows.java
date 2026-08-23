package com.legalarchive.orchestrator.elar;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;

/**
 * The rows of one input that produced no document, copied out verbatim so they can be fixed and fed
 * back in.
 *
 * <b>Why verbatim.</b> The row is written exactly as it was read, and the file opens with the input's
 * own header line, so the result is a CSV of the same shape as its source: correct the staging, rename
 * it to end in {@code .csv}, and the next run picks it up with no editing. Re-serialising the split
 * fields instead would quietly rewrite quoting and separators, which is the one thing a discards file
 * must not do - it exists to be re-read.
 *
 * <b>Why it is finalised late.</b> The file is written under a temp name and renamed to its final
 * {@code .skipped} name at the same moment its input is renamed to {@code .done} - that is, once every
 * batch that input produced has reached its final name. So {@code .skipped} means the same thing
 * {@code .done} means: this input is finished and what it delivered is delivered. If the run fails,
 * the temp file is removed and the input is reprocessed whole, discards included. A discards file
 * left behind by a run that delivered nothing would be worse than none, because it reads as a
 * complete account of what was dropped.
 *
 * The name is the input's name with {@code .skipped} APPENDED, matching the {@code .done} convention
 * rather than replacing the extension: the original name stays legible, {@code a.csv} and
 * {@code a.txt} cannot collide, and {@code listInputs} only accepts names ending in {@code .csv}, so
 * a discards file is never picked up as an input by accident.
 */
final class SkippedRows implements Closeable {

    static final String SUFFIX = ".skipped";
    private static final String NL = String.valueOf((char) 10);

    private final File input;
    private final File temp;
    private final String headerLine;
    private final Charset charset;

    private BufferedWriter w;      // opened on the first discarded row, never before
    private long rows = 0;

    SkippedRows(File input, String headerLine, String charsetName) {
        this.input = input;
        this.headerLine = headerLine;
        this.charset = Charset.forName(charsetName);
        this.temp = new File(input.getParentFile(), input.getName() + SUFFIX + ".part");
    }

    long rows() { return rows; }
    boolean any() { return rows > 0; }

    /**
     * Records one discarded row. The file is created lazily, so an input with nothing to discard
     * leaves no empty artefact behind to be mistaken for a report.
     */
    void add(String rawLine) throws IOException {
        if (w == null) {
            if (temp.exists() && !temp.delete()) {
                throw new IOException("a leftover discards file could not be removed: " + temp.getAbsolutePath()
                        + ". It is from an interrupted run; remove it by hand so this one can start clean.");
            }
            // REPORT, not REPLACE: a row that cannot be written back in the charset it was read in
            // would be silently altered, and this file's whole purpose is to be re-read unchanged.
            CharsetEncoder enc = charset.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(temp), enc), 1 << 16);
            w.write(headerLine);
            w.write(NL);
        }
        w.write(rawLine);
        w.write(NL);
        rows++;
    }

    /**
     * Publishes the file under its final name. Called at the same point as the {@code .done} rename.
     * Returns the delivered file, or null when there was nothing to discard.
     */
    File commit() throws IOException {
        close();
        if (rows == 0) return null;
        File finalFile = new File(input.getParentFile(), input.getName() + SUFFIX);
        if (finalFile.exists() && !finalFile.delete()) {
            throw new IOException("the discards file already exists and could not be replaced: "
                    + finalFile.getAbsolutePath());
        }
        if (!temp.renameTo(finalFile)) {
            throw new IOException("the discards file could not be renamed from " + temp.getName()
                    + " to " + finalFile.getName() + " in " + input.getParent());
        }
        return finalFile;
    }

    /** Throws the work away: the input was not delivered, so its discards are not an account of anything. */
    void abort() {
        try { close(); } catch (IOException ignored) { }
        if (temp.exists() && !temp.delete()) temp.deleteOnExit();
        rows = 0;
    }

    public void close() throws IOException {
        if (w != null) { w.close(); w = null; }
    }
}
