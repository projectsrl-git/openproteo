package com.legalarchive.orchestrator.elar;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;

/**
 * Cuts an input in two when a run stops part-way through it, so that what was delivered is not
 * delivered again and what was not is not lost.
 *
 * The executor's normal failure behaviour is all-or-nothing: the open batch is aborted, the input keeps
 * its name, and the whole file is reprocessed. That is right when nothing was delivered. It is wrong
 * when the disk filled after nine batches out of ten reached their final names: those nine are on the
 * share and re-running the input would send them a second time.
 *
 * So on that one path the input becomes three files:
 *
 * <ul>
 *   <li>{@code <name>.failed} - the original, renamed and otherwise untouched, so the evidence of what
 *       came in survives exactly as it arrived;</li>
 *   <li>{@code <name>.done_before_failure} - the header and the rows the run already dealt with. Not a
 *       {@code .csv}, so no later run picks it up;</li>
 *   <li>{@code <name>.remaining.csv} - the header and the rest, which the next run reads as an ordinary
 *       input.</li>
 * </ul>
 *
 * <b>The invariant that makes this trustworthy</b>: the rows of the two halves add up to the rows of the
 * original, exactly. It is checked here rather than assumed, and a mismatch fails instead of leaving a
 * split nobody can reconcile.
 *
 * <b>Why the file is re-read rather than buffered.</b> The rows could have been kept in memory as they
 * went by, and for a million-row CSV that is a couple of hundred megabytes held for a case that almost
 * never happens - on the exact path where the machine has just run out of resources. A second pass over
 * the input costs a few seconds of disk and holds one line at a time.
 */
final class InputSplitter {

    static final String FAILED = ".failed";
    static final String DONE_BEFORE = ".done_before_failure";
    static final String REMAINING = ".remaining.csv";
    private static final String NL = String.valueOf((char) 10);

    /** What the split produced, for the log and for the assertion that it adds up. */
    static final class Result {
        final File failed, doneBefore, remaining;
        final long rowsDone, rowsRemaining;
        Result(File failed, File doneBefore, File remaining, long rowsDone, long rowsRemaining) {
            this.failed = failed; this.doneBefore = doneBefore; this.remaining = remaining;
            this.rowsDone = rowsDone; this.rowsRemaining = rowsRemaining;
        }
    }

    /**
     * @param stopAtLine the physical line number of the row that was NOT processed. Everything before it
     *                   went into a batch that reached its final name, or was skipped and recorded;
     *                   everything from it onwards has not been dealt with at all.
     */
    static Result split(File input, String charsetName, long stopAtLine) throws IOException {
        Charset cs = Charset.forName(charsetName);
        File dir = input.getParentFile();
        String base = input.getName();

        File doneBefore = new File(dir, base + DONE_BEFORE);
        File remaining = new File(dir, base + REMAINING);
        File failed = new File(dir, base + FAILED);
        for (File f : new File[] { doneBefore, remaining, failed }) {
            if (f.exists() && !f.delete()) {
                throw new IOException("a file from an earlier interrupted run is in the way and could not"
                        + " be removed: " + f.getAbsolutePath() + ". Move it aside by hand.");
            }
        }

        long rowsDone = 0, rowsRemaining = 0, rowsTotal = 0;
        // REPORT on both sides: a row that cannot be read back, or written back, in the charset it was
        // read in would be silently altered - and these two files are the whole account of what happened
        CharsetDecoder dec = cs.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        CharsetEncoder encDone = encoder(cs), encRest = encoder(cs);

        BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(input), dec), 1 << 16);
        BufferedWriter a = null, b = null;
        try {
            String header = in.readLine();
            if (header == null) throw new IOException("the input is empty: " + input.getAbsolutePath());
            if (header.length() > 0 && header.charAt(0) == '\uFEFF') header = header.substring(1);

            a = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(doneBefore), encDone), 1 << 16);
            b = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(remaining), encRest), 1 << 16);
            a.write(header); a.write(NL);
            b.write(header); b.write(NL);

            String line;
            long lineNo = 1;                       // the header was line 1
            while ((line = in.readLine()) != null) {
                lineNo++;
                if (line.isEmpty()) continue;      // as the reader itself skips them
                rowsTotal++;
                if (lineNo < stopAtLine) { a.write(line); a.write(NL); rowsDone++; }
                else                     { b.write(line); b.write(NL); rowsRemaining++; }
            }
        } finally {
            close(a); close(b);
            try { in.close(); } catch (IOException ignored) { }
        }

        if (rowsDone + rowsRemaining != rowsTotal) {
            throw new IOException("splitting " + input.getName() + " does not add up: " + rowsDone
                    + " done plus " + rowsRemaining + " remaining is not " + rowsTotal + " rows. Nothing"
                    + " has been renamed; reconcile by hand before re-running.");
        }

        // the rename comes last, so a failure above leaves the original in place under its own name
        if (!input.renameTo(failed)) {
            throw new IOException("the two halves were written but " + input.getName() + " could not be"
                    + " renamed to " + failed.getName() + ". Rename it by hand before re-running, or the"
                    + " next run will process it whole and deliver its first rows a second time.");
        }
        return new Result(failed, doneBefore, remaining, rowsDone, rowsRemaining);
    }

    private static CharsetEncoder encoder(Charset cs) {
        return cs.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
    }
    private static void close(BufferedWriter w) throws IOException {
        if (w != null) w.close();
    }

    private InputSplitter() { }
}
