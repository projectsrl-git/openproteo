package com.legalarchive.orchestrator.elar;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the run did, counted by category.
 *
 * The legacy tool printed {@code Skipping doc: file path is null} and {@code Skipping doc: file not
 * found} to stdout and carried on, with no counter and no aggregate. A batch that had silently
 * dropped half its documents looked exactly like a successful one, and the only way to find out was
 * to count the blocks in the delivered XML by hand.
 *
 * Nothing here holds a record, a value or a path that embeds a customer identifier: counters and file
 * names only, so the whole object can go into the step log and the audit trail.
 */
public final class ElarCounters {

    public long filesProcessed;
    public long filesFailed;
    public long documentsWritten;
    public long documentsSkippedNoPath;
    public long documentsSkippedFileMissing;
    public long rowsMalformed;
    public long tagsWritten;
    public long batchesWritten;
    public long documentsOversize;
    public long bytesEmbedded;
    public long sameDayPairsFound;
    /** Inputs that produced a '.skipped' discards file. A gate can branch on it without parsing a log. */
    public long skippedFilesWritten;
    /**
     * Documents deleted from the local document directory after the INDX carrying them was delivered.
     * Zero unless deleteContentAfterEmbed is on, which it is not by default.
     */
    public long documentsDeleted;
    /**
     * Documents that were embedded and delivered but could NOT be deleted afterwards - a file still
     * held open, most often. Never a failure: the output is already delivered. It is counted so a gate
     * can act on leftover staging without reading the log.
     */
    public long documentsDeleteFailed;

    /** Why a document was not written, or null when it was. */
    public enum Skip { NO_PATH, FILE_MISSING }

    public void skipped(Skip s) {
        if (s == Skip.NO_PATH) documentsSkippedNoPath++;
        else if (s == Skip.FILE_MISSING) documentsSkippedFileMissing++;
    }

    /** Everything a document that was written contributes. */
    public void wrote(int tags, long contentBytes) {
        documentsWritten++;
        tagsWritten += tags;
        bytesEmbedded += contentBytes;
    }

    /** For {@code run.vars} and the cross-feed log report. */
    public Map<String, String> asVars() {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("filesProcessed", String.valueOf(filesProcessed));
        m.put("filesFailed", String.valueOf(filesFailed));
        m.put("documentsWritten", String.valueOf(documentsWritten));
        m.put("documentsSkippedNoPath", String.valueOf(documentsSkippedNoPath));
        m.put("documentsSkippedFileMissing", String.valueOf(documentsSkippedFileMissing));
        m.put("rowsMalformed", String.valueOf(rowsMalformed));
        m.put("tagsWritten", String.valueOf(tagsWritten));
        m.put("batchesWritten", String.valueOf(batchesWritten));
        m.put("documentsOversize", String.valueOf(documentsOversize));
        m.put("bytesEmbedded", String.valueOf(bytesEmbedded));
        m.put("sameDayPairsFound", String.valueOf(sameDayPairsFound));
        m.put("skippedFilesWritten", String.valueOf(skippedFilesWritten));
        m.put("documentsDeleted", String.valueOf(documentsDeleted));
        m.put("documentsDeleteFailed", String.valueOf(documentsDeleteFailed));
        return m;
    }

    /**
     * One line for the step log. Skips are stated even when zero: "0 skipped" is information, while a
     * line that only appears when something went wrong trains people not to look for it.
     */
    public String summary() {
        return "documents written " + documentsWritten
                + ", skipped " + (documentsSkippedNoPath + documentsSkippedFileMissing)
                + " (" + documentsSkippedNoPath + " with no content path, "
                + documentsSkippedFileMissing + " whose file was missing)"
                + ", malformed rows " + rowsMalformed
                + ", batches " + batchesWritten
                + ", embedded " + bytesEmbedded + " byte(s)"
                // stated only when the option is on: a permanent ", deleted 0" on every feed that does
                // not use it would train people to read past the one line that says a file was removed
                + (documentsDeleted + documentsDeleteFailed > 0
                        ? ", deleted " + documentsDeleted + " embedded document(s)"
                          + (documentsDeleteFailed > 0 ? " (" + documentsDeleteFailed + " could not be deleted)" : "")
                        : "");
    }
}
